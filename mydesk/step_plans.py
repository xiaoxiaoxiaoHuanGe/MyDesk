"""Durable, serial step rounds. All claims and stops use SQLite transactions."""
import hashlib
import json
import re
from datetime import timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

from .domain import instant, stamp, validate_steps, text_field

LIVE = ('waiting', 'running', 'paused')
BUSY = ('dispatching', 'queued', 'running', 'tracking_error')


def parameters(value):
    if not isinstance(value, dict):
        raise ValueError('步数计划格式无效')
    result = {}
    for key, label in [('start','起始步数'),('increment','每次增加'),('interval_minutes','间隔分钟'),('target','终止步数')]:
        number = value.get(key)
        if type(number) is not int:
            raise ValueError(label+'必须为整数')
        result[key] = number
    validate_steps(result['start']); validate_steps(result['target'])
    if not 1 <= result['increment'] <= 30000 or not 1 <= result['interval_minutes'] <= 1440:
        raise ValueError('增量必须为 1–30000，间隔必须为 1–1440 分钟')
    if result['start'] > result['target']:
        raise ValueError('起始步数不能高于终止步数')
    increments = (result['target']-result['start']) // result['increment'] + 1
    final = result['start'] + increments*result['increment']
    if final > 30000:
        raise ValueError('首次超过终止步数的提交也必须不超过 30000 步')
    return result


def binding(config):
    github = config.get('github') or {}
    raw = [github.get(key,'') for key in ('owner','repo','workflow','ref')]
    return hashlib.sha256(json.dumps(raw).encode()).hexdigest()


class StepPlans:
    def __init__(self, desk):
        self.desk = desk
        with desk.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS step_settings(id INTEGER PRIMARY KEY CHECK(id=1), data TEXT);
                CREATE TABLE IF NOT EXISTS step_runs(id TEXT PRIMARY KEY, day TEXT UNIQUE, data TEXT);
                CREATE TABLE IF NOT EXISTS step_attempts(seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT, job_id TEXT UNIQUE, source TEXT, applied INTEGER NOT NULL DEFAULT 0,
                    finished_at TEXT);
                CREATE INDEX IF NOT EXISTS step_attempt_run ON step_attempts(run_id,seq);
            ''')
            db.execute('INSERT OR IGNORE INTO step_settings VALUES (1,?)',
                       (json.dumps(dict(settings={'daily':False,'start_time':'08:00'},presets=[],revision=uuid4().hex)),))

    def _settings(self, db):
        return json.loads(db.execute('SELECT data FROM step_settings WHERE id=1').fetchone()[0])

    def _save_settings(self, db, value):
        value['revision'] = uuid4().hex
        db.execute('UPDATE step_settings SET data=? WHERE id=1',(json.dumps(value,ensure_ascii=False),))

    def _latest(self, db):
        row = db.execute('SELECT data FROM step_runs ORDER BY rowid DESC LIMIT 1').fetchone()
        return json.loads(row[0]) if row else None

    def _write(self, db, run):
        db.execute('UPDATE step_runs SET data=? WHERE id=?',(json.dumps(run,ensure_ascii=False),run['id']))

    def _active_job(self, db):
        return db.execute("SELECT * FROM jobs WHERE status IN ('dispatching','queued','running','tracking_error') LIMIT 1").fetchone()

    def _new_run(self, db, spec, now, config, source='manual', day=None):
        current = self._latest(db)
        if current and current['status'] in LIVE:
            raise ValueError('已有渐进任务，请先终止本轮')
        if self._active_job(db):
            raise ValueError('已有步数提交尚未完成，请先确认结果')
        params = parameters(spec)
        preset = next((p for p in self._settings(db)['presets'] if p['id']==spec.get('preset_id')),None)
        if self._floor(db,now,config) > params['start']:
            raise ValueError('起始步数低于本日已确认成功提交值，请调整起始步数')
        name=text_field(spec.get('name') or (preset['name'] if preset else '渐进任务'),'计划名称',40)
        run = dict(id=uuid4().hex,params=params,name=name,
                   source=source,status='waiting',started_at=stamp(now),next_at=stamp(now),next_steps=params['start'],
                   last_success=None,last_success_at=None,success_count=0,current_job_id=None,message='',
                   pause_reason='',binding=binding(config),timezone=config.get('timezone','Asia/Shanghai'),ended_at=None)
        db.execute('INSERT INTO step_runs VALUES (?,?,?)',(run['id'],day,json.dumps(run,ensure_ascii=False)))
        return run

    def _floor(self, db, now, config):
        zone = ZoneInfo(config.get('timezone','Asia/Shanghai'))
        day = instant(now).astimezone(zone).replace(hour=0,minute=0,second=0,microsecond=0)
        row = db.execute("SELECT MAX(steps) FROM jobs WHERE status='success' AND created_at>=? AND created_at<=?",
                         (stamp(day),stamp(now))).fetchone()
        return row[0] if row[0] is not None else -1

    def configure(self, payload, now, config):
        params=parameters(payload)
        daily=payload.get('daily',False)
        clock=payload.get('start_time','08:00')
        if type(daily) is not bool or not isinstance(clock,str) or not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d',clock):
            raise ValueError('每日开始时间或重复开关无效')
        if daily:
            count=(params['target']-params['start'])//params['increment']+1
            hour,minute=map(int,clock.split(':'))
            if hour*60+minute+count*params['interval_minutes'] >= 1440:
                raise ValueError('每日计划预计跨日，请提前开始或调整参数')
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            value=self._settings(db)
            if payload.get('revision') and payload['revision'] != value['revision']:
                raise ValueError('计划或预设已在其他端修改，请刷新后保存')
            tomorrow=instant(now).astimezone(ZoneInfo(config.get('timezone','Asia/Shanghai'))).date()+timedelta(days=1)
            value['settings']=dict(**params,daily=daily,start_time=clock,eligible_day=tomorrow.isoformat(),
                                   name=text_field(payload.get('name') or '渐进任务','计划名称',40))
            self._save_settings(db,value)
        return self.snapshot()

    def preset(self, action, payload):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            value=self._settings(db)
            if payload.get('revision') and payload['revision'] != value['revision']:
                raise ValueError('预设已在其他端修改，请刷新')
            rows=value['presets']; key=payload.get('id')
            if action=='save':
                params=parameters(payload); name=text_field(payload.get('name'),'预设名称',40)
                existing=next((p for p in rows if p['id']==key),None)
                if key and not existing: raise ValueError('该预设已删除，请刷新')
                if any(p['name']==name and p['id']!=key for p in rows):raise ValueError('预设名称已存在')
                if not existing and len(rows)>=50:raise ValueError('最多保存 50 个预设')
                item=dict(id=key or uuid4().hex,name=name,**params)
                if existing: rows[rows.index(existing)]=item
                else: rows.append(item)
            elif action=='delete':
                if not any(p['id']==key for p in rows):raise ValueError('该预设已删除')
                value['presets']=[p for p in rows if p['id']!=key]
            elif action=='order':
                ids=payload.get('ids')
                if not isinstance(ids,list) or len(ids)!=len(rows) or set(ids)!={p['id'] for p in rows}:raise ValueError('预设排序无效')
                value['presets']=sorted(rows,key=lambda p:ids.index(p['id']))
            else: raise ValueError('不支持的预设操作')
            self._save_settings(db,value)
        return self.snapshot()

    def start(self, payload, now, config):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE'); self._new_run(db,payload,now,config)

    def control(self, action, payload, now, config):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE'); run=self._latest(db)
            if action=='disable':
                value=self._settings(db);value['settings']['daily']=False;self._save_settings(db,value)
            else:
                if not run or payload.get('run_id')!=run['id']:raise ValueError('当前轮次已变化，请刷新')
            if run and run['status'] in LIVE:
                if action in ('stop','disable'):
                    run.update(status='stopped',next_at=None,ended_at=stamp(now),message='用户终止本轮；已发出的提交继续跟踪')
                elif action=='resume':
                    if run['status']!='paused' or self._active_job(db):raise ValueError('请先确认当前提交结果，再恢复计划')
                    if run['binding']!=binding(config):raise ValueError('步数工作流已变更，请终止后开始新一轮')
                    if run['next_steps'] < self._floor(db,now,config):raise ValueError('下一次步数低于本日成功提交值，请终止后调整')
                    run.update(status='waiting',next_at=stamp(now),message='',pause_reason='')
                self._write(db,run)
            elif action=='resume':raise ValueError('本轮已结束，不能恢复')
        return self.snapshot()

    def _job(self, db, steps, now, run=None, source='plan'):
        job=dict(id=uuid4().hex,steps=steps,status='dispatching',created_at=stamp(now),run_id=None,url=None,message='正在提交')
        db.execute('INSERT INTO jobs VALUES (:id,:steps,:status,:created_at,:run_id,:url,:message)',job)
        if run:
            db.execute('INSERT INTO step_attempts(run_id,job_id,source) VALUES (?,?,?)',(run['id'],job['id'],source))
            run.update(status='running',current_job_id=job['id'],next_at=None,
                       dispatched_at=stamp(now),next_steps=steps)
            self._write(db,run)
        return job

    def manual(self, steps, now, config):
        validate_steps(steps)
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if self._active_job(db):raise ValueError('已有步数任务正在执行，请等待完成')
            if steps < self._floor(db,now,config):raise ValueError('不能低于本日已确认成功提交值')
            run=self._latest(db)
            if run and run['status'] in LIVE and run['status']!='waiting':raise ValueError('请先处理或终止暂停的渐进任务')
            return self._job(db,steps,now,run if run and run['status']=='waiting' else None,'manual')

    def reconcile(self, now):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            rows=db.execute('SELECT a.*,j.steps,j.status,j.message,j.run_id AS github_run,j.created_at '
                            'FROM step_attempts a JOIN jobs j ON j.id=a.job_id WHERE a.applied=0 ORDER BY a.seq').fetchall()
            for row in rows:
                run=json.loads(db.execute('SELECT data FROM step_runs WHERE id=?',(row['run_id'],)).fetchone()[0])
                if row['status'] in BUSY:
                    if row['status']=='tracking_error' and run['status'] in LIVE:
                        run.update(status='paused',message=row['message'],pause_reason='tracking')
                        self._write(db,run)
                    continue
                db.execute('UPDATE step_attempts SET applied=1,finished_at=? WHERE seq=?',(stamp(now),row['seq']))
                run['current_job_id']=None
                if row['status']=='success':
                    run.update(last_success=row['steps'],last_success_at=stamp(now),success_count=run['success_count']+1)
                    if run['status'] in LIVE:
                        if row['steps']>run['params']['target']:
                            run.update(status='completed',ended_at=stamp(now),next_at=None,message='首次超过终止步数，已完成本轮')
                        else:
                            run.update(status='waiting',next_steps=row['steps']+run['params']['increment'],
                                       next_at=stamp(instant(row['created_at'])+timedelta(minutes=run['params']['interval_minutes'])),
                                       message='',pause_reason='')
                elif run['status'] in LIVE:
                    run.update(status='paused',next_steps=row['steps'],next_at=None,message=row['message'] or '提交失败，请检查后重试',pause_reason='failed')
                self._write(db,run)

    def claim(self, now, config):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE'); run=self._latest(db); value=self._settings(db); settings=value['settings']
            local=instant(now).astimezone(ZoneInfo(config.get('timezone','Asia/Shanghai')))
            day=local.date().isoformat()
            if settings.get('daily') and day>=settings.get('eligible_day','9999') and local.strftime('%H:%M')>=settings['start_time']:
                if not db.execute('SELECT 1 FROM step_runs WHERE day=?',(day,)).fetchone() and (not run or run['status'] not in LIVE) and not self._active_job(db):
                    try:run=self._new_run(db,settings,now,config,'daily',day)
                    except ValueError:
                        # Record a blocked day once, instead of retrying a lower starting value every tick.
                        run=dict(id=uuid4().hex,params=parameters(settings),name=settings.get('name','每日计划'),source='daily',
                                 status='paused',started_at=stamp(now),next_at=None,next_steps=settings['start'],last_success=None,
                                 last_success_at=None,success_count=0,current_job_id=None,message='起始步数低于本日成功提交值，请终止后调整',
                                 pause_reason='start',binding=binding(config),timezone=config.get('timezone','Asia/Shanghai'),ended_at=None)
                        db.execute('INSERT INTO step_runs VALUES (?,?,?)',(run['id'],day,json.dumps(run,ensure_ascii=False)))
            if not run or run['status']!='waiting':return None
            if run['source']=='daily' and instant(run['started_at']).astimezone(ZoneInfo(run['timezone'])).date()!=local.date():
                run.update(status='stopped',next_at=None,ended_at=stamp(now),message='当天未完成，已停止跨日补发');self._write(db,run);return None
            if run['binding']!=binding(config) or not config.get('github'):
                run.update(status='paused',next_at=None,message='步数工作流已变更或未配置，请终止后重新开始',pause_reason='binding');self._write(db,run);return None
            if self._active_job(db) or instant(run['next_at'])>instant(now):return None
            if run['next_steps'] < self._floor(db,now,config):
                run.update(status='paused',next_at=None,message='下一次步数低于本日成功提交值，请终止后调整',pause_reason='start');self._write(db,run);return None
            if run['next_steps']>30000:
                run.update(status='paused',next_at=None,message='下一次提交将超过 30000 步，请终止本轮',pause_reason='limit');self._write(db,run);return None
            return self._job(db,run['next_steps'],now,run)

    def history(self, run_id, limit=100, before=None):
        if not isinstance(run_id,str) or type(limit) is not int or not 1<=limit<=200 or (before is not None and (type(before) is not int or before<1)):
            raise ValueError('轮次记录查询参数无效')
        with self.desk.connect() as db:
            if not db.execute('SELECT 1 FROM step_runs WHERE id=?',(run_id,)).fetchone():raise ValueError('本轮记录已不存在')
            return self._records(db,run_id,limit,before)

    def _records(self, db, run_id, limit, before=None):
        return [dict(row) for row in db.execute('SELECT a.seq,a.source,a.finished_at,j.* FROM step_attempts a JOIN jobs j ON j.id=a.job_id '
                                                'WHERE a.run_id=? AND (? IS NULL OR a.seq<?) ORDER BY a.seq DESC LIMIT ?',
                                                (run_id,before,before,limit))]

    def snapshot(self):
        with self.desk.connect() as db:
            value=self._settings(db);run=self._latest(db)
            if run:
                run['records']=self._records(db,run['id'],20)
                row=db.execute('SELECT * FROM jobs WHERE id=?',(run['current_job_id'],)).fetchone()
                run['current_job']=dict(row) if row else None
            value['run']=run
            value['recent_runs']=[dict(id=row['id'],**{k:json.loads(row['data'])[k] for k in ('name','status','started_at','success_count')})
                                  for row in db.execute('SELECT id,data FROM step_runs ORDER BY rowid DESC LIMIT 20')]
            return value
