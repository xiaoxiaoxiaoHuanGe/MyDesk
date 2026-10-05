"""MyDesk reminder, dispatch and feed orchestration."""
import asyncio
import re
from urllib.parse import quote, urlencode
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo

from .domain import parse_time, validate_steps
from .providers import GitHub, Beszel, OnePanel, poll_mail, poll_network, notification_data, run_state
from .config import mail_accounts,server_sources
from .mail_proxy import MailProxyError
from .github_checkins import CheckinPoller, task_binding, legacy_binding
from .daily_quote import poll_quote,FALLBACK
from .step_plans import StepPlans
from .attention import actionable_attention


GITHUB_POLL_SECONDS = 600


def now_utc():
    return datetime.now(timezone.utc)


class Runtime:
    def __init__(self, desk, config, session, notify, changed=None, executor=None):
        self.desk, self.config, self.session = desk, config, session
        self.notify, self.changed = notify, changed
        self.executor = executor or asyncio.to_thread
        self.github = GitHub(session, config['github']) if config.get('github') else None
        self.beszel = Beszel(session, config['beszel']) if config.get('beszel') else None
        self.tick_lock, self.feed_lock = asyncio.Lock(), asyncio.Lock()
        self.manual_refresh = None
        self.notification_error = None
        self.last_mail_poll = None
        self.quote_lock=asyncio.Lock()
        self.configure_checkins()
        self.step_plans = StepPlans(desk)
        self.step_dispatch_lock = asyncio.Lock()

    async def steps_db(self, method, *args):
        return await self.executor(getattr(self.step_plans, method), *args)

    async def dispatch_steps(self, job):
        await self.publish()
        try:
            details = await self.github.dispatch(job['steps'])
            await self.db('update_job',job['id'],status='queued',message='GitHub Actions 排队中',**details)
        except Exception as exc:
            message = str(exc) if isinstance(exc,ValueError) else '提交连接异常，结果不确定；请检查 GitHub Actions'
            await self.db('update_job',job['id'],status='tracking_error',message=message)
        await self.steps_db('reconcile',now_utc())

    async def advance_steps(self, now):
        # Controls never wait for this network lock; a stop persists immediately.
        if self.step_dispatch_lock.locked():return
        async with self.step_dispatch_lock:
            await self.steps_db('reconcile',now)
            job=await self.steps_db('claim',now,self.config)
            if job:await self.dispatch_steps(job)

    def configure_checkins(self):
        tasks=self.config.get('github_tasks',{})
        self.last_github_poll = None
        self.checkins=CheckinPoller(self.session,tasks) if any(task.get('enabled',True) for task in tasks.values()) else None

    async def db(self, method, *args, **kwargs):
        if kwargs:
            from functools import partial
            return await self.executor(partial(getattr(self.desk, method), *args, **kwargs))
        return await self.executor(getattr(self.desk, method), *args)

    async def publish(self):
        if self.changed:
            await self.changed(await self.snapshot())

    async def snapshot(self, now=None):
        active={key:spec for key,spec in self.config.get('github_tasks',{}).items() if spec.get('enabled',True)}
        expected={**self.config.get('expected_tasks',{}),**{'github.'+key:dict(name=spec['name'],max_age_hours=spec.get('max_age_hours',36)) for key,spec in active.items()}}
        state=await self.db('snapshot',now or now_utc(),expected,self.config.get('stale_seconds',300))
        state['attention']=[item for item in state['attention'] if not (item['kind']=='task' and item['id'].startswith('github.'))]
        tasks=[]
        for task in state['tasks']:
            if not task['task_id'].startswith('github.'):
                tasks.append(task);continue
            key=task['task_id'][7:]
            spec=active.get(key)
            if not spec:continue
            event_id=task.get('event_id','')
            prefix='github.'+key+'.'+task_binding(spec)+'.'
            legacy_event=legacy_binding(key,spec) and re.fullmatch(re.escape('github.'+key)+r'\.\d+\.\d+\.(?:completed|pending_details|in_progress|queued|waiting|requested)',event_id)
            if event_id and not event_id.startswith(prefix) and not legacy_event:
                task=dict(task_id=task['task_id'],task_name=spec['name'],status='unknown',message='尚未收到此工作流的执行结果',timestamp=None)
            task['task_name']=spec['name']
            tasks.append(task)
            if task['status'] in ('failed','warning','unknown'):
                state['attention'].append(dict(kind='task',id=task['task_id'],title=task['task_name'],message=task['message']))
        state['tasks']=tasks
        feed=state['feeds'].get('github_tasks',{})
        if feed.get('updated_at'):
            feed['stale'] = ((now or now_utc()) - datetime.fromisoformat(feed['updated_at'])).total_seconds() > GITHUB_POLL_SECONDS * 2
        for item in feed.get('data',{}).get('items',[]):
            if item['task_id'][7:] in active and (item.get('error') or feed.get('stale')):
                state['attention'].append(dict(kind='integration',id=item['task_id'],title=item['name'],message=item.get('error') or 'GitHub 任务同步数据已过期，等待更新'))
        state['timezone'] = self.config.get('timezone', 'Asia/Shanghai')
        quote_data=state['feeds'].get('daily_quote',{}).get('data',{})
        state['daily_quote']={key:quote_data.get(key,FALLBACK.get(key,'')) for key in ('text','source','author','url','date')}
        state['configured'] = {
            'notifications': bool(self.config.get('notify_targets') or self.config.get('local_alarm_devices')), 'github': bool(self.github),
            'servers': any(s.get('enabled',True) for s in server_sources(self.config).values()), 'mail': any(s.get('enabled',True) for s in mail_accounts(self.config).values()),
            'network': bool(self.config.get('network', {}).get('enabled', True)), 'github_tasks': bool(self.checkins)}
        state['links'] = {'gmail': 'https://mail.google.com/', 'beszel': self.config.get('beszel', {}).get('public_url')}
        if self.notification_error:
            state['attention'].append(dict(kind='integration', id='notifications', title='手机通知', message=self.notification_error))
        state['wxstep_plan']=await self.steps_db('snapshot')
        run=state['wxstep_plan']['run']
        if run and run['status']=='paused':
            state['attention'].append(dict(kind='steps',id=run['id'],title='微信步数 · '+run['name'],message=run['message']))
        elif state.get('wxstep') and state['wxstep']['status'] in ('failed','tracking_error'):
            state['attention'].append(dict(kind='steps',id=state['wxstep']['id'],title='微信步数提交',message=state['wxstep']['message']))
        state['attention']=actionable_attention(state)
        return state

    async def recover(self):
        for job in await self.db('active_jobs'):
            if job['status'] == 'dispatching' and not job['run_id']:
                await self.db('update_job', job['id'], status='tracking_error', message='服务在提交时重启，结果不确定；请先检查 GitHub Actions')
        await self.steps_db('reconcile',now_utc())

    async def command(self, action, payload, now=None):
        now = now or now_utc()
        if action == 'snapshot':
            return await self.snapshot(now)
        if action == 'sync/all':
            # Share overlapping manual refreshes; a disconnected caller must not
            # cancel the refresh another phone is waiting for.
            if self.manual_refresh is None or self.manual_refresh.done():
                self.manual_refresh = asyncio.create_task(self.refresh_all(now))
            await asyncio.shield(self.manual_refresh)
            return await self.snapshot(now)
        if action == 'history':
            return await self.db('task_history', payload.get('limit', 100), payload.get('before'),payload.get('task_id'))
        if action.startswith('wxstep/plan/') or action.startswith('wxstep/preset/'):
            kind=action.rsplit('/',1)[1]
            if action.startswith('wxstep/preset/'):
                result=await self.steps_db('preset',kind,payload)
            elif kind=='save':result=await self.steps_db('configure',payload,now,self.config)
            elif kind=='history':return await self.steps_db('history',payload.get('run_id'),payload.get('limit',100),payload.get('before'))
            elif kind=='start':
                if not self.github:raise ValueError('请先配置微信步数工作流')
                await self.steps_db('start',payload,now,self.config)
                await self.advance_steps(now)
                result=await self.steps_db('snapshot')
            elif kind in ('stop','disable','resume'):
                result=await self.steps_db('control',kind,payload,now,self.config)
                if kind=='resume':await self.advance_steps(now);result=await self.steps_db('snapshot')
            else:raise ValueError('不支持的步数计划操作')
            await self.publish()
            return result
        if action == 'service/check':
            kind=payload.get('kind');key=payload.get('id')
            entries=mail_accounts(self.config) if kind=='mail' else server_sources(self.config) if kind=='servers' else {}
            spec=entries.get(key)
            if not spec:raise ValueError('请先保存此接入')
            try:
                if kind=='mail':await self.executor(poll_mail,spec)
                elif spec['provider']=='1panel':await OnePanel(self.session,spec).poll()
                else:await Beszel(self.session,spec).poll()
            except MailProxyError as exc:raise ValueError(str(exc)) from None
            except Exception:raise ValueError('Gmail 连接失败，请检查应用专用密码与网络' if kind=='mail' else '监控连接失败，请检查地址、API 凭据、证书与白名单') from None
            return dict(connected=True,message='连接成功，可以读取此项数据')
        if action == 'github_task/check':
            key=payload.get('task_id')
            spec=self.config.get('github') if key=='wxstep' else self.config.get('github_tasks',{}).get(key)
            if not spec or not spec.get('token'):
                raise ValueError('请先保存此任务的独立 Token 和工作流配置')
            base=self.checkins.base_url if self.checkins else 'https://api.github.com'
            github=GitHub(self.session,spec,base)
            runs=await github.request('GET',f"/actions/workflows/{quote(spec['workflow'],safe='')}/runs?{urlencode({'branch':spec.get('ref','main'),'per_page':1})}")
            return dict(connected=True,message='工作流连接成功，所选分支尚无执行记录；请核对分支并在 GitHub 运行一次' if not runs.get('workflow_runs') else '连接成功，可以同步此工作流的执行结果')
        if action == 'reminder/create':
            when = parse_time(payload.get('time', ''), self.config.get('timezone', 'Asia/Shanghai'), now)
            result = await self.db('create_reminder', payload.get('title'), when, now)
        elif action == 'reminder/action':
            result = await self.db('reminder_action', payload.get('id'), payload.get('action'), now,
                                    minutes=payload.get('minutes', 10), revision=payload.get('revision'))
        elif action == 'wxstep/submit':
            if not self.github:
                raise ValueError('请先在服务器配置 GitHub 仓库和 Token')
            validate_steps(payload.get('steps'))
            result = await self.steps_db('manual',payload['steps'],now,self.config)
            await self.dispatch_steps(result)
            result = (await self.snapshot(now))['wxstep']
        elif action == 'wxstep/release':
            jobs = await self.db('active_jobs')
            if not jobs or jobs[0]['status'] != 'tracking_error' or payload.get('confirmed') is not True:
                raise ValueError('请先检查 GitHub Actions，确认可以结束本地跟踪')
            await self.db('update_job', jobs[0]['id'], status='failed', message='用户结束了本地跟踪；GitHub 上的任务不会被取消')
            await self.steps_db('reconcile',now)
            result = {'released': True}
        elif action == 'notifications/test':
            if not self.config.get('notify_targets'):
                raise ValueError('手机原生即时推送尚未配置，请连接 MyDesk App 推送服务')
            for target in self.config['notify_targets']:
                await self.notify(target, {'title': 'MyDesk', 'message': '测试通知：请检查手机通知中心', 'data': {'url': '/'}})
            result = {'sent': True}
        else:
            raise ValueError('不支持的 MyDesk 操作')
        await self.publish()
        return result

    async def notification_action(self, action, now=None):
        if not isinstance(action, str) or not action.startswith('MYDESK:'):
            return False
        parts = action.split(':')
        if len(parts) != 4 or parts[3] not in ('complete', 'snooze'):
            return False
        try:
            await self.command('reminder/action', {'id': parts[1], 'revision': parts[2], 'action': parts[3]}, now)
            return True
        except ValueError:
            return False

    async def tick(self, now=None):
        if self.tick_lock.locked():
            return
        async with self.tick_lock:
            now = now or now_utc()
            targets = self.config.get('notify_targets', [])
            failed = False
            for reminder in await self.db('due_reminders', now):
                remaining = await self.db('delivery_targets', reminder['id'], targets)
                success = bool(targets)
                for target in remaining:
                    if not await self.db('reminder_is_current',reminder['id'],reminder['revision'],now):
                        success=False
                        break
                    try:
                        await self.notify(target, notification_data(reminder, self.config.get('dashboard_path', '/')))
                        await self.db('mark_delivered', reminder['id'], reminder['revision'], target, now)
                    except Exception:
                        # Do not log credentials or service payloads. Retry on next tick.
                        success, failed = False, True
                if success:
                    await self.db('mark_notified', reminder['id'], reminder['revision'], now)
            self.notification_error = '通知发送失败，正在重试；请检查推送网络和设备订阅' if failed else None
            if self.github:
                for job in await self.db('active_jobs'):
                    if not job['run_id']:
                        continue
                    try:
                        run = await self.github.run(job['run_id'])
                        state = run_state(run)
                        await self.db('update_job', job['id'], status=state, message=run.get('conclusion') or run['status'], url=run['html_url'])
                    except ValueError as exc:
                        await self.db('update_job', job['id'], status='tracking_error', message=str(exc))
            await self.advance_steps(now)
            await self.db('prune', now, self.config.get('history_days', 90))
            await self.publish()

    async def refresh_all(self, now):
        await self.refresh(now, force=True)
        await self.tick(now)

    async def refresh_quote(self,now=None):
        # Separate from business refreshes: quote outages never delay reminders or manual sync.
        async with self.quote_lock:
            now=now or now_utc()
            today=now.astimezone(ZoneInfo(self.config.get('timezone','Asia/Shanghai'))).date().isoformat()
            cache=(await self.db('snapshot',now)).get('feeds',{}).get('daily_quote',{}).get('data',{})
            if cache.get('date')==today:return
            try:
                if cache.get('retry_at') and datetime.fromisoformat(cache['retry_at'])>now:return
            except (TypeError,ValueError):pass
            try:
                result={**await poll_quote(self.session),'date':today}
            except Exception:
                # Retain the last successful sentence and persist retry backoff across restarts.
                result={**(cache or FALLBACK),'retry_at':(now+timedelta(minutes=30)).isoformat()}
            await self.db('set_feed','daily_quote',result,now)
        await self.publish()

    async def refresh(self, now=None, *, force=False):
        if not force and self.feed_lock.locked():
            return
        async with self.feed_lock:
            now = now or now_utc()
            async def save(name, operation):
                try:
                    data = await operation()
                except Exception:
                    data = {'error': {'servers': '服务器监控同步失败，请检查连接和服务端凭据',
                                      'mail': 'Gmail 同步失败，请检查 IMAP 和应用专用密码',
                                      'network': '网络检测失败，请检查节点配置和 ping 工具'}[name]}
                await self.db('set_feed', name, data, now)
            operations = []
            if self.checkins and (force or self.last_github_poll is None or (now - self.last_github_poll).total_seconds() >= GITHUB_POLL_SECONDS):
                self.last_github_poll = now
                async def checkins():
                    events,data=await self.checkins.poll(lambda event_id:self.db('task_event_exists',event_id),now)
                    for event in events:
                        await self.db('report_task',event,now)
                    await self.db('set_feed','github_tasks',data,now)
                operations.append(checkins())
            if any(s.get('enabled',True) for s in server_sources(self.config).values()):
                operations.append(save('servers',lambda:self.poll_servers(now)))
            if any(s.get('enabled',True) for s in mail_accounts(self.config).values()) and (force or not self.last_mail_poll or (now - self.last_mail_poll).total_seconds() >= 120):
                self.last_mail_poll = now
                operations.append(save('mail',lambda:self.poll_mail_accounts(now)))
            if self.config.get('network', {}).get('enabled', True):
                operations.append(save('network', lambda: poll_network(self.session, self.config.get('network', {}))))
            await asyncio.gather(*operations)
            await self.publish()

    async def poll_mail_accounts(self,now):
        semaphore=asyncio.Semaphore(4)
        async def poll(key,spec):
            row=dict(id=key,name=spec['name'],username=spec['username'],url='https://mail.google.com/mail/u/?authuser='+quote(spec['username'],safe=''),unread=None,items=[])
            async with semaphore:
                try:
                    data=await self.executor(poll_mail,spec)
                    row.update(unread=data['unread'],items=[{**item,'id':key+':'+item['id'],'account_id':key,'account_name':spec['name'],'account_email':spec['username']} for item in data['items']])
                except MailProxyError as exc:row['error']=str(exc)
                except Exception:row['error']='Gmail 同步失败，请检查 IMAP、网络与应用专用密码'
            return row
        accounts=await asyncio.gather(*(poll(key,spec) for key,spec in mail_accounts(self.config).items() if spec.get('enabled',True)))
        items=[item for row in accounts for item in row['items']]
        def received(item):
            try:
                stamp=datetime.fromisoformat(item.get('received_at') or '')
                if stamp.tzinfo is None:stamp=stamp.replace(tzinfo=timezone.utc)
                return stamp.astimezone(timezone.utc)
            except (ValueError,TypeError):return datetime.min.replace(tzinfo=timezone.utc)
        items.sort(key=received,reverse=True)
        return dict(accounts=accounts,items=items[:3],unread=sum(row['unread'] or 0 for row in accounts),unread_complete=all('error' not in row for row in accounts))

    async def poll_servers(self,now):
        semaphore=asyncio.Semaphore(4)
        async def poll(key,spec):
            async with semaphore:
                try:
                    if spec['provider']=='1panel':items=[await OnePanel(self.session,spec).poll()]
                    else:items=(await (self.beszel if key=='legacy' and self.beszel else Beszel(self.session,spec)).poll())['items']
                    return [{**item,'id':key if spec['provider']=='1panel' else key+':'+item['id'],'source_id':key,'source_name':spec['name'],
                             'provider':spec['provider'],'name':spec['name'] if spec['provider']=='1panel' else item['name'],'updated_at':now.isoformat()} for item in items]
                except Exception:
                    return [dict(id=key,name=spec['name'],source_id=key,source_name=spec['name'],provider=spec['provider'],status='unknown',cpu=None,ram=None,disk=None,
                                 error='监控连接异常，请检查地址、凭据、网络与访问白名单',updated_at=None)]
        rows=await asyncio.gather(*(poll(key,spec) for key,spec in server_sources(self.config).items() if spec.get('enabled',True)))
        return {'items':[item for group in rows for item in group]}
