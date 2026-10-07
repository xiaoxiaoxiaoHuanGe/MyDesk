"""Authenticated HTTP/WebSocket application and managed background jobs."""
import asyncio
import hmac
import json
import logging
import os
import time
import sqlite3
import re
from contextlib import suppress
from pathlib import Path

from aiohttp import ClientSession, web

from .app_updates import AppUpdates
from .inbox import IncomingError
from .auth import Auth
from .config import Settings,mail_accounts,server_sources
from .domain import Desk
from .providers import GitHub, Beszel
from .runtime import Runtime, now_utc
from .mobile import Mobile,MobileConflict,DeviceRemoved
from .fcm import Fcm
from .native_notifications import NativeNotifications
from .backup import Backups, selected, MAX_FILE_BYTES

ROOT=Path(__file__).resolve().parents[1]
STATE=web.AppKey('mydesk',dict)
COOKIE='mydesk_session'


def create_app(directory, scheduling=True, trusted_proxies=()):
    directory=Path(directory)
    directory.mkdir(parents=True,exist_ok=True)
    # Docker Desktop bind mounts may be writable without POSIX chmod support.
    try:
        directory.chmod(0o700)
    except PermissionError:
        pass
    auth=Auth(directory/'auth.sqlite3')
    access=auth.bootstrap()
    if access:
        (directory/'local-access.json').write_text(json.dumps(access),encoding='utf-8')
        (directory/'LOCAL_ACCESS.md').write_text(
            '# MyDesk 本地登录\n\n用户名：`mydesk`\n\n密码：`'+access['password']+'`\n\n请在设置中修改密码。此文件只保存在本地，不进入发布包。\n',encoding='utf-8')
    settings=Settings(directory/'settings.json')
    backups=Backups(settings,directory)
    backup_lock=asyncio.Lock()
    updates=AppUpdates(os.environ.get('MYDESK_UPDATE_DIR',str(directory/'app-updates')))
    sockets={}
    failures={}

    @web.middleware
    async def guard(request,handler):
        try:
            if request.remote in app[STATE]['trusted_proxies']:
                scheme=request.headers.get('X-Forwarded-Proto')
                if scheme in ('http','https'):
                    request=request.clone(scheme=scheme)
            origin=request.headers.get('Origin')
            if (request.method not in ('GET','HEAD') or request.path=='/api/ws') and origin and origin!=str(request.url.origin()):
                raise web.HTTPForbidden(text='请求来源无效')
            incoming=request.method=='POST' and re.fullmatch(r'/api/incoming/feishu/[a-f0-9]{32}/[a-f0-9]{64}',request.path) is not None
            private=request.path.startswith('/api/') and request.path not in ('/api/login','/api/notification-action') and not request.path.startswith('/api/webhook/') and not incoming
            if private:
                session=await asyncio.to_thread(auth.session,request.cookies.get(COOKIE))
                if not session:
                    raise web.HTTPUnauthorized(text='请先登录 MyDesk')
                request['session']=session
                if request.method not in ('GET','HEAD') and not hmac.compare_digest(request.headers.get('X-MyDesk-CSRF',''),session['csrf']):
                    raise web.HTTPForbidden(text='会话验证失败，请重新登录')
            response=await handler(request)
        except IncomingError as exc:
            response=web.json_response({'code':exc.status,'msg':str(exc)},status=exc.status,headers={'Retry-After':'60'} if exc.status==429 else None)
        except web.HTTPException as exc:
            if re.fullmatch(r'/api/incoming/feishu/[a-f0-9]{32}/[a-f0-9]{64}',request.path):
                response=web.json_response({'code':exc.status,'msg':'请求内容过大' if exc.status==413 else '请求无效'},status=exc.status)
            else:response=web.json_response({'error':exc.text if exc.status!=413 else '请求内容过大'},status=exc.status)
        except (ValueError,TypeError,KeyError,AttributeError):
            response=web.json_response({'code':400,'msg':'请求格式或内容无效'} if request.path.startswith('/api/incoming/feishu/') else {'error':'请求格式或内容无效'},status=400)
        response.headers['Cache-Control']='no-store'
        response.headers['X-Content-Type-Options']='nosniff'
        response.headers['Referrer-Policy']='same-origin'
        response.headers['Content-Security-Policy']="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
        return response

    app=web.Application(middlewares=[guard],client_max_size=65536)
    app[STATE]={'auth':auth,'settings':settings,'sockets':sockets,'trusted_proxies':set(trusted_proxies)}

    async def runtime():
        return app[STATE]['runtime']

    async def changed(snapshot):
        native=app[STATE].get('native_notifications')
        if native:await asyncio.to_thread(native.capture,snapshot,now_utc())
        # Each socket has a bounded latest-state queue; slow clients cannot block operations.
        for socket,(session_id,queue) in list(sockets.items()):
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(snapshot)

    def update_targets():
        rt=app[STATE]['runtime']
        rt.config['notify_targets']=[]
        rt.config['local_alarm_devices']=[device['id'] for device in app[STATE]['mobile'].devices() if device['local_alarm']]

    async def login(request):
        peer=request.remote or 'unknown'
        now=time.monotonic()
        attempts=[t for t in failures.get(peer,[]) if now-t<300]
        if len(attempts)>=5:
            raise web.HTTPTooManyRequests(text='登录尝试过多，请 5 分钟后重试')
        data=await request.json()
        result=await asyncio.to_thread(auth.login,data.get('username'),data.get('password'))
        if not result:
            if len(failures)>=2048:
                failures.clear()
            failures[peer]=attempts+[now]
            raise web.HTTPUnauthorized(text='账号或密码不正确')
        failures.pop(peer,None)
        token,csrf=result
        response=web.json_response({'username':data['username'],'csrf':csrf})
        response.set_cookie(COOKIE,token,httponly=True,secure=request.secure,samesite='Strict',max_age=7*86400,path='/')
        return response

    async def notification_sources(request):
        inbox=(await runtime()).inbox
        if request.method=='GET':
            result={'sources':await asyncio.to_thread(inbox.sources),'push_available':app[STATE]['native_notifications'].sender is not None}
        elif request.method=='POST' and 'source' not in request.match_info:
            result=await asyncio.to_thread(inbox.create,await request.json())
        else:
            action='rotate' if request.path.endswith('/rotate-secret') else 'delete' if request.method=='DELETE' else 'edit'
            result=await asyncio.to_thread(inbox.change,request.match_info['source'],await request.json() if action=='edit' else None,action,now_utc())
        await (await runtime()).publish()
        return web.json_response(result)

    async def incoming_text(request):
        try:
            await asyncio.to_thread((await runtime()).inbox.receive,request.match_info['source'],request.match_info['secret'],await request.json(),request.headers.get('Idempotency-Key'),now_utc())
        except sqlite3.Error:raise IncomingError(500,'临时存储失败，请稍后重试') from None
        # Persistence includes a durable dispatch entry. Socket refresh/FCM never gate the ACK.
        app[STATE]['inbox_changed'].set()
        return web.json_response({'code':0,'msg':'success'})

    async def inbox_page(request):
        query=request.query
        result=await asyncio.to_thread((await runtime()).inbox.page,int(query.get('limit','50')),int(query['before']) if 'before' in query else None,query.get('source_id'),now_utc())
        return web.json_response(result)

    async def inbox_detail(request):
        return web.json_response(await asyncio.to_thread((await runtime()).inbox.detail,request.match_info['message']))

    async def inbox_read(request):
        result=await asyncio.to_thread((await runtime()).inbox.read,await request.json(),now_utc())
        await (await runtime()).publish()
        return web.json_response(result)

    async def app_update(request):
        try:result=await asyncio.to_thread(updates.latest,request.query.get('channel','release'))
        except (OSError,ValueError):raise web.HTTPServiceUnavailable(text='应用更新配置暂不可用') from None
        return web.json_response(result)

    async def app_artifact(request):
        try:path=await asyncio.to_thread(updates.artifact,request.match_info['artifact'])
        except FileNotFoundError:raise web.HTTPNotFound(text='更新产物不存在') from None
        except (OSError,ValueError):raise web.HTTPServiceUnavailable(text='更新产物暂不可用') from None
        return web.FileResponse(path,headers={'Content-Type':'application/vnd.android.package-archive'})

    async def session(request):
        return web.json_response({'username':request['session']['username'],'csrf':request['session']['csrf']})

    async def close_sessions(session_id=None):
        await asyncio.gather(*(socket.close(code=4401,message=b'Session ended') for socket,(sid,_) in list(sockets.items()) if session_id is None or sid==session_id))

    async def logout(request):
        session_id=request['session']['id']
        await asyncio.to_thread(auth.revoke,session_id)
        await asyncio.to_thread(app[STATE]['mobile'].remove,session_id=session_id)
        await close_sessions(session_id)
        response=web.json_response({'logged_out':True})
        response.del_cookie(COOKIE,path='/')
        return response

    async def password(request):
        data=await request.json()
        try:
            await asyncio.to_thread(auth.change_password,request['session']['username'],data.get('current_password'),data.get('new_password'))
        except ValueError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from None
        await close_sessions()
        await asyncio.to_thread(app[STATE]['mobile'].remove)
        (directory/'local-access.json').unlink(missing_ok=True)
        (directory/'LOCAL_ACCESS.md').write_text('# MyDesk 本地登录\n\n用户名：`mydesk`\n\n密码已由用户修改，旧随机密码不再有效。\n',encoding='utf-8')
        response=web.json_response({'changed':True})
        response.del_cookie(COOKIE,path='/')
        return response

    async def command(request):
        message=await request.json()
        if not isinstance(message,dict) or not isinstance(message.get('payload',{}),dict):
            raise web.HTTPBadRequest(text='操作格式无效')
        update_targets()
        try:
            result=await (await runtime()).command(message['action'],message.get('payload',{}))
        except ValueError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from None
        return web.json_response(result)

    async def websocket(request):
        socket=web.WebSocketResponse(heartbeat=20,max_msg_size=4096)
        await socket.prepare(request)
        queue=asyncio.Queue(maxsize=1)
        sockets[socket]=(request['session']['id'],queue)
        update_targets()
        queue.put_nowait(await (await runtime()).snapshot())
        async def sender():
            while True:
                snapshot=await queue.get()
                if not await asyncio.to_thread(auth.session,request.cookies.get(COOKIE)):
                    await socket.close(code=4401,message=b'Session expired')
                    return
                await socket.send_json({'type':'snapshot','state':snapshot})
        task=asyncio.create_task(sender())
        try:
            async for message in socket:
                if message.type==web.WSMsgType.TEXT:
                    await socket.close(code=1008,message=b'Receive-only state channel')
        finally:
            sockets.pop(socket,None)
            task.cancel()
            with suppress(asyncio.CancelledError,ConnectionResetError):
                await task
        return socket

    async def get_settings(request):
        return web.json_response(settings.public())

    async def reset_runtime(rt, incoming):
        rt.config={**settings.value,'notify_targets':[]}
        update_targets()
        rt.github=GitHub(rt.session,rt.config['github']) if rt.config.get('github') else None
        rt.beszel=Beszel(rt.session,rt.config['beszel']) if rt.config.get('beszel') else None
        rt.last_mail_poll=None
        rt.configure_checkins()
        if not rt.checkins:
            await rt.db('remove_feed','github_tasks')
        for name,enabled,keys in [('servers',any(s.get('enabled',True) for s in server_sources(rt.config).values()),{'server_sources','beszel'}),
                                 ('mail',any(s.get('enabled',True) for s in mail_accounts(rt.config).values()),{'gmail_accounts','gmail'}),
                                 ('network',rt.config['network']['enabled'],{'network'})]:
            if not enabled or incoming.keys() & keys:
                await rt.db('remove_feed',name)

    async def apply_settings(incoming, restore=None):
        rt=await runtime()
        async with rt.tick_lock,rt.feed_lock:
            if isinstance(incoming,dict) and 'github' in incoming and settings.value.get('github') and await rt.db('active_jobs'):
                replacement=incoming['github']
                current=settings.value['github']
                if not replacement or any(replacement.get(key,current.get(key))!=current.get(key) for key in ('owner','repo','workflow','ref')):
                    raise web.HTTPConflict(text='步数任务仍在跟踪中，请先完成或结束跟踪再更换仓库')
            if restore:
                result=await asyncio.to_thread(backups.apply,*restore)
            else:
                result=settings.update(incoming)
            await reset_runtime(rt,incoming)
        await rt.publish()
        return result

    async def save_settings(request):
        try:
            return web.json_response(await apply_settings(await request.json()))
        except ValueError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from None

    async def backup_data(request):
        # Backup files can exceed the normal 64 KiB settings request limit.
        # Read these routes with a separate, explicit bound; other routes keep it.
        chunks=[];size=0
        async for chunk in request.content.iter_chunked(16384):
            size+=len(chunk)
            if size>MAX_FILE_BYTES*2:
                raise web.HTTPRequestEntityTooLarge(max_size=MAX_FILE_BYTES*2,actual_size=size)
            chunks.append(chunk)
        data=json.loads(b''.join(chunks))
        if not isinstance(data,dict):raise ValueError('备份请求格式无效')
        return data

    async def backup_export(request):
        try:
            data=await backup_data(request)
            async with backup_lock:
                blob=await asyncio.to_thread(backups.export,data.get('password'),data.get('appearance','system'),str(request.url.origin()))
            return web.json_response({'file':blob,'filename':'MyDesk-'+now_utc().strftime('%Y%m%d-%H%M%S')+'.mydesk'})
        except ValueError as exc:raise web.HTTPBadRequest(text=str(exc)) from None

    async def backup_preview(request):
        try:
            data=await backup_data(request)
            rt=await runtime()
            async with backup_lock,rt.tick_lock,rt.feed_lock:
                result=await asyncio.to_thread(backups.preview,data.get('file'),data.get('password'),data.get('mode','merge'),request['session']['id'],data.get('current_appearance','system'))
            return web.json_response(result)
        except ValueError as exc:raise web.HTTPBadRequest(text=str(exc)) from None

    async def backup_apply(request):
        try:
            data=await backup_data(request)
            async with backup_lock:
                args=(data.get('preview_id'),request['session']['id'],data.get('confirmed') is True)
                plan=backups.plan(*args)
                incoming=selected(plan['candidate'])
                # Absence of the steps integration in replacement also counts as removal.
                if 'github' not in incoming:incoming['github']=None
                result=await apply_settings(incoming,restore=args)
            return web.json_response(result)
        except ValueError as exc:raise web.HTTPBadRequest(text=str(exc)) from None

    async def webhook_info(request):
        return web.json_response({'path':'/api/webhook/'+settings.value['webhook_id']})

    async def webhook(request):
        if not hmac.compare_digest(request.match_info['secret'],settings.value['webhook_id']):
            raise web.HTTPNotFound(text='接口不存在')
        rt=await runtime()
        try:
            result=await rt.db('report_task',await request.json(),now_utc())
        except ValueError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from None
        await rt.publish()
        return web.json_response({'accepted':True,'result':result})

    async def notifications(request):
        raise web.HTTPGone(text='网页通知已停用，请使用 MyDesk Android App')

    async def notification_action(request):
        raise web.HTTPGone(text='旧网页通知操作已停用，请打开 MyDesk App 查看当前提醒')

    async def file_response(request):
        file={'/':'index.html','/sw.js':'sw.js','/manifest.webmanifest':'manifest.webmanifest'}[request.path]
        return web.FileResponse(ROOT/'frontend'/file)

    async def health(request):
        return web.json_response({'status':'ok','application':'MyDesk'})

    async def mobile_devices(request):
        mobile=app[STATE]['mobile']
        if request.method=='GET':
            native=app[STATE]['native_notifications']
            return web.json_response({'devices':await asyncio.to_thread(mobile.devices),'server_id':native.server_id,'push_available':native.sender is not None})
        if request.method=='DELETE':
            await asyncio.to_thread(mobile.remove,device_id=request.match_info['device'])
            return web.json_response({'removed':True})
        data=await request.json()
        return web.json_response(await asyncio.to_thread(mobile.register,data,request['session']['id']))

    async def mobile_push(request):
        try:result=await asyncio.to_thread(app[STATE]['mobile'].update_push,await request.json(),request['session']['id'])
        except DeviceRemoved as exc:raise web.HTTPForbidden(text=str(exc)) from None
        return web.json_response(result)

    async def mobile_notifications(request):
        native=app[STATE]['native_notifications']
        if request.method=='GET':return web.json_response(await asyncio.to_thread(native.status,request.query.get('device_id')))
        data=await request.json()
        try:
            if request.path.endswith('/test'):
                event=await asyncio.to_thread(native.test,data.get('device_id'),now_utc())
                return web.json_response({'event_id':event,'queued':True,'message':'请求已入队，请以设备回执和手机实际通知为准'})
            result=await asyncio.to_thread(native.receipt,data,request['session']['id'],now_utc())
        except DeviceRemoved as exc:raise web.HTTPForbidden(text=str(exc)) from None
        return web.json_response(result)

    async def mobile_device_name(request):
        try:result=await asyncio.to_thread(app[STATE]['mobile'].rename,await request.json(),request['session']['id'])
        except DeviceRemoved as exc:raise web.HTTPForbidden(text=str(exc)) from None
        return web.json_response(result)

    async def mobile_action(request):
        try:result=await asyncio.to_thread(app[STATE]['mobile'].reminder_action,await request.json(),now_utc())
        except DeviceRemoved as exc:raise web.HTTPForbidden(text=str(exc)) from None
        except MobileConflict as exc:raise web.HTTPConflict(text=str(exc)) from None
        await (await runtime()).publish()
        return web.json_response(result)

    async def background(operation,interval):
        while True:
            update_targets()
            try:
                await operation()
            except Exception:
                logging.getLogger('mydesk').error('后台任务失败，下一轮重试')
            await asyncio.sleep(interval)

    async def lifecycle(app):
        async with ClientSession() as http:
            desk=Desk(directory/'mydesk.sqlite3')
            mobile=Mobile(desk)
            try:sender=await asyncio.to_thread(Fcm.load,http,directory/'fcm-service-account.json')
            except (ValueError,OSError):
                sender=None
                logging.getLogger('mydesk').warning('原生推送配置无效；其余 MyDesk 功能继续运行')
            def session_active(session_id):
                with auth.connect() as db:return db.execute('SELECT 1 FROM sessions WHERE id=? AND expires>?',(session_id,time.time())).fetchone() is not None
            native=NativeNotifications(mobile,sender,session_active)
            async def native_notify(target,payload):
                raise ValueError('原生即时推送尚未配置')
            rt=Runtime(desk,{**settings.value,'notify_targets':[]},http,native_notify,changed)
            app[STATE]['runtime']=rt
            app[STATE]['mobile']=mobile
            app[STATE]['native_notifications']=native
            update_targets()
            app[STATE]['inbox_changed']=asyncio.Event()
            async def inbox_updates():
                while True:
                    await app[STATE]['inbox_changed'].wait()
                    app[STATE]['inbox_changed'].clear()
                    publishing=asyncio.create_task(rt.publish())
                    try:await asyncio.shield(publishing)
                    except asyncio.CancelledError:
                        # Executor DB work cannot be cancelled by cancelling its awaiting coroutine.
                        # Finish this publication before teardown releases the database directory.
                        await publishing
                        raise
                    except Exception:logging.getLogger('mydesk').error('通知同步暂时失败，持久派发记录将在后台重试')
            await rt.recover()
            async def deliver_native():await native.drain(now_utc())
            tasks=[asyncio.create_task(background(rt.tick,15)),asyncio.create_task(background(rt.refresh,60)),asyncio.create_task(background(rt.refresh_quote,60)),asyncio.create_task(background(deliver_native,5))] if scheduling else []
            tasks.append(asyncio.create_task(inbox_updates()))
            try:
                yield
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks,return_exceptions=True)

    async def shutdown(app):
        await close_sessions()

    app.cleanup_ctx.append(lifecycle)
    app.on_shutdown.append(shutdown)
    app.router.add_get('/health',health)
    app.router.add_post('/api/login',login)
    app.router.add_get('/api/session',session)
    app.router.add_get('/api/notification-sources',notification_sources)
    app.router.add_post('/api/notification-sources',notification_sources)
    app.router.add_patch('/api/notification-sources/{source}',notification_sources)
    app.router.add_delete('/api/notification-sources/{source}',notification_sources)
    app.router.add_post('/api/notification-sources/{source}/rotate-secret',notification_sources)
    app.router.add_post('/api/incoming/feishu/{source:[a-f0-9]{32}}/{secret:[a-f0-9]{64}}',incoming_text)
    app.router.add_get('/api/inbox',inbox_page)
    app.router.add_post('/api/inbox/read',inbox_read)
    app.router.add_get('/api/inbox/{message}',inbox_detail)
    app.router.add_get('/api/app-update',app_update)
    app.router.add_get('/api/app-update/artifacts/{artifact}.apk',app_artifact)
    app.router.add_post('/api/logout',logout)
    app.router.add_post('/api/password',password)
    app.router.add_post('/api/command',command)
    app.router.add_get('/api/ws',websocket)
    app.router.add_post('/api/backup/export',backup_export)
    app.router.add_post('/api/backup/preview',backup_preview)
    app.router.add_post('/api/backup/apply',backup_apply)
    app.router.add_get('/api/settings',get_settings)
    app.router.add_put('/api/settings',save_settings)
    app.router.add_get('/api/webhook-info',webhook_info)
    app.router.add_post('/api/webhook/{secret}',webhook)
    app.router.add_get('/api/notifications',notifications)
    app.router.add_post('/api/notifications',notifications)
    app.router.add_delete('/api/notifications/{device}',notifications)
    app.router.add_post('/api/notification-action',notification_action)
    app.router.add_get('/api/mobile/devices',mobile_devices)
    app.router.add_post('/api/mobile/devices',mobile_devices)
    app.router.add_delete('/api/mobile/devices/{device}',mobile_devices)
    app.router.add_post('/api/mobile/reminder-action',mobile_action)
    app.router.add_post('/api/mobile/push',mobile_push)
    app.router.add_post('/api/mobile/device-name',mobile_device_name)
    app.router.add_get('/api/mobile/notifications',mobile_notifications)
    app.router.add_post('/api/mobile/notifications/test',mobile_notifications)
    app.router.add_post('/api/mobile/notification-receipt',mobile_notifications)
    for path in ('/','/sw.js','/manifest.webmanifest'):
        app.router.add_get(path,file_response)
    app.router.add_static('/frontend/',ROOT/'frontend',show_index=False)
    return app
