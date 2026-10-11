"""Persistent native event outbox. Submission, reception and display request are distinct facts."""
import asyncio
import hashlib
import json
from datetime import timedelta
from uuid import uuid4
from .domain import stamp,instant,text_field
from .mobile import DeviceRemoved
from .fcm import PushRetry,TokenExpired


def push_text(value,limit):
    return str(value).encode("utf-16-le")[:limit*2].decode("utf-16-le",errors="ignore")


def digest(value):return hashlib.sha256(value.encode()).hexdigest()


class NativeNotifications:
    def __init__(self,mobile,sender,session_active):
        self.mobile,self.desk,self.sender,self.session_active=mobile,mobile.desk,sender,session_active
        self.drain_lock=asyncio.Lock()
        with self.desk.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS native_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS native_observed(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS native_events(id TEXT PRIMARY KEY,kind TEXT,channel TEXT,title TEXT,body TEXT,
                    reference TEXT,revision TEXT,created_at TEXT,expires_at TEXT);
                CREATE TABLE IF NOT EXISTS native_deliveries(event_id TEXT,device_id TEXT,token_digest TEXT,
                    submitted_at TEXT,received_at TEXT,display_requested_at TEXT,blocked_at TEXT,
                    error TEXT,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TEXT,
                    PRIMARY KEY(event_id,device_id));
            ''')
            if 'registration_id' not in {row['name'] for row in db.execute('PRAGMA table_info(native_deliveries)')}:
                db.execute("ALTER TABLE native_deliveries ADD COLUMN registration_id TEXT NOT NULL DEFAULT ''")
            if 'superseded_at' not in {row['name'] for row in db.execute('PRAGMA table_info(native_deliveries)')}:
                db.execute('ALTER TABLE native_deliveries ADD COLUMN superseded_at TEXT')
            db.execute("INSERT OR IGNORE INTO native_metadata VALUES ('server_id',?)",(uuid4().hex,))
            self.server_id=db.execute("SELECT value FROM native_metadata WHERE key='server_id'").fetchone()['value']

    def targets(self):
        with self.desk.connect() as db:
            rows=[dict(r) for r in db.execute('SELECT * FROM native_devices WHERE push_token IS NOT NULL AND notifications_enabled=1')]
        return [r for r in rows if self.session_active(r['session_id'])]

    def capture_inbox(self,now):
        targets=self.targets()
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute("SELECT 1 FROM sqlite_master WHERE name='inbox_dispatch'").fetchone():return
            rows=db.execute('SELECT m.*,d.created_at FROM inbox_dispatch d JOIN inbox_messages m ON m.id=d.message_id WHERE d.event_id IS NULL ORDER BY m.seq').fetchall()
            for row in rows:
                if instant(row['created_at'])+timedelta(hours=1)<=now:
                    db.execute('UPDATE inbox_dispatch SET event_id=? WHERE message_id=?',('expired',row['id']))
                    continue
                event=self._enqueue(db,'inbox','inbox',row['source_name']+' · '+row['title'],row['body'],row['id'],'',targets,instant(row['created_at']))
                db.execute('UPDATE inbox_dispatch SET event_id=? WHERE message_id=?',(event,row['id']))

    def capture(self,snapshot,now):
        self.capture_inbox(now)
        observed=[]
        reminders=snapshot.get('reminders',[])
        valid=[r for r in reminders if all(key in r for key in ('id','title','revision','remind_at','status'))]
        if len(valid)==len(reminders):
            revision=digest(json.dumps(sorted((r['id'],r['revision'],r['remind_at'],r['status']) for r in valid)))
            observed.append(('reminder_snapshot',revision,('sync','reminders','提醒已更新','请同步当前提醒','',revision)))
        for reminder in valid:
            due=reminder['status'] in ('pending','snoozed') and instant(reminder['remind_at'])<=now
            observed.append(('reminder_due:'+reminder['id'],reminder['revision']+('due' if due else 'future'),
                ('reminder','reminders',reminder['title'],'到提醒时间了',reminder['id'],reminder['revision']) if due else None))
        for task in snapshot.get('tasks',[]):
            state=task.get('status','unknown')
            revision=str(task.get('event_id') or task.get('timestamp') or '')+'|'+state
            notice=None
            if state in ('success','failed','warning','unknown'):
                notice=('task','tasks' if state=='success' else 'alerts',task.get('task_name') or '自动任务',task.get('message') or '状态未知',str(task.get('task_id','')),revision)
            observed.append(('task:'+str(task.get('task_id','')),revision,notice))
        job=snapshot.get('wxstep')
        if job:
            state=job.get('status','unknown');revision=str(job['id'])+'|'+state
            notice=('task','tasks' if state=='success' else 'alerts','微信步数',job.get('message') or state,str(job['id']),revision) if state in ('success','failed','tracking_error') else None
            observed.append(('wxstep',revision,notice))
        feeds=snapshot.get('feeds',{})
        for name in ('servers','network','mail'):
            feed=feeds.get(name,{})
            data=feed.get('data',{})
            problem=data.get('error') or ('数据已过期' if feed.get('stale') else '')
            notice=('alert','alerts',{'servers':'服务器监控','network':'网络监控','mail':'邮件同步'}[name],str(problem),name,str(problem)) if problem else None
            observed.append(('integration:'+name,str(problem),notice))
            if name=='servers':
                for server in data.get('items',[]):
                    state=server.get('status','unknown')
                    notice=('alert','alerts',server.get('name') or '服务器','服务器离线' if state=='down' else '服务器状态未知',str(server['id']),state) if state!='up' else None
                    observed.append(('server:'+str(server['id']),state,notice))
            if name=='network' and data:
                for node in [{'name':'Internet','online':data.get('online')},*data.get('nodes',[])]:
                    state=str(node.get('online'))
                    notice=('alert','alerts',node['name'],'网络连接异常',node['name'],state) if node.get('online') is False else None
                    observed.append(('network:'+node['name'],state,notice))
        targets=self.targets()
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            for key,value,notice in observed:
                previous=db.execute('SELECT value FROM native_observed WHERE key=?',(key,)).fetchone()
                if previous and previous['value']==value:continue
                db.execute('INSERT OR REPLACE INTO native_observed VALUES (?,?)',(key,value))
                if notice and targets and not (key=='reminder_snapshot' and previous is None):self._enqueue(db,*notice,targets,now)
            cutoff=stamp(now-timedelta(days=30))
            db.execute('DELETE FROM native_deliveries WHERE event_id IN (SELECT id FROM native_events WHERE created_at<?)',(cutoff,))
            db.execute('DELETE FROM native_events WHERE created_at<?',(cutoff,))

    def _enqueue(self,db,kind,channel,title,body,reference,revision,targets,now):
        event=uuid4().hex
        db.execute('INSERT INTO native_events VALUES (?,?,?,?,?,?,?,?,?)',(event,kind,channel,push_text(title,120),push_text(body,500),reference[:200],revision[:200],stamp(now),stamp(now+timedelta(hours=1))))
        for device in targets:
            db.execute('INSERT INTO native_deliveries(event_id,device_id,next_attempt_at,registration_id) VALUES (?,?,?,?)',(event,device['id'],stamp(now),device['registration_id']))
        return event

    def test(self,device_id,now):
        targets=[r for r in self.targets() if r['id']==self.mobile.identifier(device_id)]
        if not self.sender:raise ValueError('后端原生推送凭据尚未配置')
        if not targets:raise DeviceRemoved('设备未启用原生推送或通知权限')
        with self.desk.connect() as db:
            return self._enqueue(db,'test','reminders','MyDesk','原生测试通知：请检查手机通知中心','','',targets,now)

    def pending(self,now):
        targets={r['id']:r for r in self.targets()}
        with self.desk.connect() as db:
            rows=[dict(r) for r in db.execute('SELECT e.*,d.device_id,d.token_digest,d.submitted_at,d.received_at,d.next_attempt_at,d.registration_id FROM native_events e JOIN native_deliveries d ON d.event_id=e.id WHERE e.expires_at>? AND d.received_at IS NULL AND d.blocked_at IS NULL AND d.superseded_at IS NULL AND d.next_attempt_at<=? ORDER BY e.created_at,e.id',(stamp(now),stamp(now)))]
        result=[]
        for row in rows:
            device=targets.get(row['device_id'])
            if device and row['registration_id']==device['registration_id'] and (not row['submitted_at'] or row['token_digest']!=digest(device['push_token'])):
                result.append((row,device))
                if len(result)==100:break
        return result

    async def drain(self,now):
        await asyncio.to_thread(self.capture_inbox,now)
        if not self.sender or self.drain_lock.locked():return
        async with self.drain_lock:
            for event,device in await asyncio.to_thread(self.pending,now):
                # Re-read before each submission: removal/permission/session changes suppress queued work.
                current=next((r for r in await asyncio.to_thread(self.targets) if r['id']==device['id']),None)
                if not current or current['registration_id']!=event['registration_id']:continue
                if event['kind']=='reminder' and not await asyncio.to_thread(self.desk.reminder_is_current,event['reference'],event['revision'],now):
                    await asyncio.to_thread(self.supersede,event['id'],device['id'],now)
                    continue
                token=current['push_token']
                data={key:str(event[key]) for key in ('kind','channel','title','body','created_at','expires_at','reference','revision')}
                data.update(event_id=event['id'],device_id=device['id'],server_id=self.server_id,registration_id=current['registration_id'])
                try:await self.sender.send(token,data)
                except TokenExpired:
                    await asyncio.to_thread(self.expire_token,device['id'],token)
                    await asyncio.to_thread(self.record,event['id'],device['id'],now,token,'原生 Token 已失效',3600)
                except PushRetry as exc:
                    await asyncio.to_thread(self.record,event['id'],device['id'],now,token,str(exc),exc.retry_after)
                except Exception:
                    await asyncio.to_thread(self.record,event['id'],device['id'],now,token,'原生推送提交失败，稍后重试',60)
                else:await asyncio.to_thread(self.record,event['id'],device['id'],now,token)

    def expire_token(self,device_id,token):
        with self.desk.connect() as db:db.execute('UPDATE native_devices SET push_token=NULL WHERE id=? AND push_token=?',(device_id,token))

    def supersede(self,event,device,now):
        with self.desk.connect() as db:db.execute('UPDATE native_deliveries SET superseded_at=? WHERE event_id=? AND device_id=?',(stamp(now),event,device))

    def record(self,event,device,now,token,error=None,retry=60):
        with self.desk.connect() as db:
            if error:
                db.execute('UPDATE native_deliveries SET error=?,attempts=attempts+1,next_attempt_at=? WHERE event_id=? AND device_id=?',(error,stamp(now+timedelta(seconds=retry)),event,device))
            else:
                db.execute('UPDATE native_deliveries SET token_digest=?,submitted_at=?,error=NULL,attempts=attempts+1 WHERE event_id=? AND device_id=?',(digest(token),stamp(now),event,device))

    def receipt(self,data,session_id,now):
        device=self.mobile.identifier(data.get('device_id'));event=self.mobile.identifier(data.get('event_id'))
        phase=data.get('phase')
        if phase not in ('received','display_requested','blocked','superseded'):raise ValueError('设备回执状态无效')
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM native_devices WHERE id=? AND session_id=?',(device,session_id)).fetchone():raise DeviceRemoved('设备已移除或会话不属于此设备')
            if not db.execute('SELECT 1 FROM native_deliveries d JOIN native_devices n ON n.id=d.device_id AND n.registration_id=d.registration_id WHERE d.event_id=? AND d.device_id=?',(event,device)).fetchone():raise ValueError('通知事件不存在或不属于本次设备登记')
            received=stamp(now)
            db.execute('UPDATE native_deliveries SET received_at=COALESCE(received_at,?) WHERE event_id=? AND device_id=?',(received,event,device))
            if phase!='received':
                field={'display_requested':'display_requested_at','blocked':'blocked_at','superseded':'superseded_at'}[phase]
                db.execute(f'UPDATE native_deliveries SET {field}=COALESCE({field},?) WHERE event_id=? AND device_id=?',(received,event,device))
            db.execute('UPDATE native_devices SET received_at=? WHERE id=?',(received,device))
        return {'accepted':True,'phase':phase}

    def status(self,device_id=None):
        if device_id is not None:self.mobile.identifier(device_id)
        with self.desk.connect() as db:
            rows=[dict(r) for r in db.execute('SELECT e.id AS event_id,e.kind,e.title,e.created_at,e.expires_at,d.device_id,d.submitted_at,d.received_at,d.display_requested_at,d.blocked_at,d.superseded_at,d.error,d.attempts FROM native_events e JOIN native_deliveries d ON d.event_id=e.id WHERE (? IS NULL OR d.device_id=?) ORDER BY e.created_at DESC,e.id DESC LIMIT 100',(device_id,device_id))]
        return {'push_available':self.sender is not None,'server_id':self.server_id,'deliveries':rows}
