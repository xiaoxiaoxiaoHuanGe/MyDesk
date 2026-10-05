"""Persistent business logic. No dependency on Home Assistant or network clients."""
from __future__ import annotations

import json
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo

UTC = timezone.utc
ACTIVE = ('pending', 'snoozed')
JOB_ACTIVE = ('dispatching', 'queued', 'running', 'tracking_error')
TASK_STATES = {'success', 'failed', 'running', 'warning', 'unknown'}


def instant(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00')) if isinstance(value, str) else value
    if not isinstance(result, datetime) or result.tzinfo is None:
        raise ValueError('时间必须包含时区')
    return result.astimezone(UTC)


def stamp(value):
    return instant(value).isoformat()


def parse_time(text, timezone_name, now):
    now = instant(now)
    local = now.astimezone(ZoneInfo(timezone_name))
    text = text.strip()
    relative = re.fullmatch(r'(\d+)\s*(分钟|小时)后', text)
    clock = re.fullmatch(r'(今天|明天)\s*(\d{1,2}):(\d{2})', text)
    try:
        if relative:
            amount = int(relative[1])
            result = now + timedelta(**{('minutes' if relative[2] == '分钟' else 'hours'): amount})
        elif clock:
            result = (local + timedelta(days=clock[1] == '明天')).replace(
                hour=int(clock[2]), minute=int(clock[3]), second=0, microsecond=0)
        else:
            result = datetime.fromisoformat(text.replace('Z', '+00:00'))
            if result.tzinfo is None:
                result = result.replace(tzinfo=local.tzinfo)
        result = result.astimezone(UTC)
    except (ValueError, OverflowError) as exc:
        raise ValueError('请输入分钟后、小时后、今天/明天 HH:mm 或指定日期时间') from exc
    if result <= now:
        raise ValueError('提醒时间必须在未来')
    return result


def validate_steps(value):
    if type(value) is not int or not 0 <= value <= 30000:
        raise ValueError('请输入 0–30000 的整数')
    return value


def text_field(value, label, maximum=500):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError(f'{label}不能为空，且不超过 {maximum} 个字符')
    return value.strip()


class Desk:
    """Each operation owns a connection and transaction; safe in HA's executor."""
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, remind_at TEXT NOT NULL,
                    status TEXT NOT NULL, created_at TEXT NOT NULL, completed_at TEXT,
                    revision TEXT NOT NULL, notified_at TEXT);
                CREATE TABLE IF NOT EXISTS deliveries (
                    reminder_id TEXT, revision TEXT, target TEXT, delivered_at TEXT,
                    PRIMARY KEY(reminder_id, revision, target));
                CREATE TABLE IF NOT EXISTS task_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE,
                    task_id TEXT, task_name TEXT, status TEXT, message TEXT,
                    timestamp TEXT, source TEXT);
                CREATE INDEX IF NOT EXISTS task_latest ON task_events(task_id, timestamp DESC, id DESC);
                CREATE TABLE IF NOT EXISTS feeds (name TEXT PRIMARY KEY, data TEXT, updated_at TEXT);
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY, steps INTEGER, status TEXT, created_at TEXT,
                    run_id INTEGER, url TEXT, message TEXT);
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def create_reminder(self, title, remind_at, now):
        title = text_field(title, '提醒内容', 200)
        if instant(remind_at) <= instant(now):
            raise ValueError('提醒时间必须在未来')
        item = dict(id=uuid4().hex, title=title, remind_at=stamp(remind_at), status='pending',
                    created_at=stamp(now), completed_at=None, revision=uuid4().hex, notified_at=None)
        with self.connect() as db:
            db.execute('INSERT INTO reminders VALUES (:id,:title,:remind_at,:status,:created_at,:completed_at,:revision,:notified_at)', item)
        return item

    def due_reminders(self, now):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM reminders WHERE status IN ('pending','snoozed') AND remind_at <= ? AND notified_at IS NULL ORDER BY remind_at", (stamp(now),))]

    def delivery_targets(self, reminder_id, targets):
        with self.connect() as db:
            sent = {r[0] for r in db.execute('SELECT target FROM deliveries WHERE reminder_id=? AND revision=(SELECT revision FROM reminders WHERE id=?)', (reminder_id, reminder_id))}
        return [t for t in targets if t not in sent]

    def reminder_is_current(self, reminder_id, revision, now):
        with self.connect() as db:
            return db.execute("SELECT 1 FROM reminders WHERE id=? AND revision=? AND status IN ('pending','snoozed') AND remind_at<=?",
                              (reminder_id,revision,stamp(now))).fetchone() is not None

    def mark_delivered(self, reminder_id, revision, target, now):
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO deliveries VALUES (?,?,?,?)', (reminder_id, revision, target, stamp(now)))

    def mark_notified(self, reminder_id, revision, now):
        with self.connect() as db:
            db.execute('UPDATE reminders SET notified_at=? WHERE id=? AND revision=?', (stamp(now), reminder_id, revision))

    def reminder_action(self, reminder_id, action, now, minutes=10, revision=None):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            return self._reminder_action(db,reminder_id,action,now,minutes,revision)

    def _reminder_action(self,db,reminder_id,action,now,minutes=10,revision=None):
        row = db.execute('SELECT * FROM reminders WHERE id=?', (reminder_id,)).fetchone()
        if not row or row['status'] not in ACTIVE:
            raise ValueError('提醒已处理或不存在')
        if revision and revision != row['revision']:
            raise ValueError('这条通知已失效，请打开最新提醒')
        if action == 'snooze':
            if type(minutes) is not int or minutes not in (10, 30, 60):
                raise ValueError('稍后提醒支持 10、30、60 分钟')
            db.execute("UPDATE reminders SET status='snoozed',remind_at=?,revision=?,notified_at=NULL WHERE id=?",
                       (stamp(instant(now) + timedelta(minutes=minutes)), uuid4().hex, reminder_id))
        elif action in ('complete', 'cancel'):
            db.execute('UPDATE reminders SET status=?,completed_at=? WHERE id=?',
                       ('completed' if action == 'complete' else 'cancelled', stamp(now), reminder_id))
        else:raise ValueError('不支持的提醒操作')
        return dict(db.execute('SELECT * FROM reminders WHERE id=?', (reminder_id,)).fetchone())

    def report_task(self, event, now):
        data = {k: text_field(event.get(k), k, 200 if k != 'message' else 2000)
                for k in ('task_id', 'task_name', 'status', 'message', 'source')}
        if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,100}', data['task_id']) or data['status'] not in TASK_STATES:
            raise ValueError('task_id 或 status 无效')
        data['timestamp'] = stamp(event.get('timestamp'))
        if instant(data['timestamp']) > instant(now) + timedelta(minutes=5):
            raise ValueError('任务时间不能超过当前时间五分钟')
        event_id = event.get('event_id') or '|'.join(data.values())
        data['event_id'] = text_field(event_id, 'event_id', 4000)
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO task_events(event_id,task_id,task_name,status,message,timestamp,source) VALUES (:event_id,:task_id,:task_name,:status,:message,:timestamp,:source)', data)
        return data

    def task_event_exists(self,event_id):
        with self.connect() as db:
            return db.execute('SELECT 1 FROM task_events WHERE event_id=?',(event_id,)).fetchone() is not None

    def task_history(self, limit=100, before=None, task_id=None):
        if type(limit) is not int or not 1 <= limit <= 200:
            raise ValueError('历史条数必须为 1–200')
        if before is not None and (type(before) is not int or before < 1):
            raise ValueError('历史分页位置无效')
        if task_id is not None and (not isinstance(task_id,str) or not re.fullmatch(r'[a-zA-Z0-9_.-]{1,100}',task_id)):
            raise ValueError('任务历史筛选标识无效')
        with self.connect() as db:
            if task_id is not None and before is not None and not db.execute('SELECT 1 FROM task_events WHERE id=? AND task_id=?',(before,task_id)).fetchone():
                raise ValueError('分页位置不属于当前任务')
            rows = db.execute('SELECT * FROM task_events WHERE (? IS NULL OR task_id=?) AND (? IS NULL OR (timestamp,id) < (SELECT timestamp,id FROM task_events WHERE id=?)) ORDER BY timestamp DESC,id DESC LIMIT ?', (task_id,task_id,before,before,limit))
            return [dict(r) for r in rows]

    def set_feed(self, name, data, now):
        with self.connect() as db:
            db.execute('INSERT OR REPLACE INTO feeds VALUES (?,?,?)', (name, json.dumps(data, ensure_ascii=False), stamp(now)))

    def remove_feed(self,name):
        with self.connect() as db:
            db.execute('DELETE FROM feeds WHERE name=?',(name,))

    def remove_feed(self,name):
        with self.connect() as db:
            db.execute('DELETE FROM feeds WHERE name=?',(name,))

    def create_job(self, steps, now):
        validate_steps(steps)
        item = dict(id=uuid4().hex, steps=steps, status='dispatching', created_at=stamp(now), run_id=None, url=None, message='正在提交')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute("SELECT 1 FROM jobs WHERE status IN ('dispatching','queued','running','tracking_error')").fetchone():
                raise ValueError('已有步数任务正在执行，请等待完成')
            db.execute('INSERT INTO jobs VALUES (:id,:steps,:status,:created_at,:run_id,:url,:message)', item)
        return item

    def update_job(self, job_id, **changes):
        if not changes or not set(changes) <= {'status', 'run_id', 'url', 'message'}:
            raise ValueError('任务更新字段无效')
        with self.connect() as db:
            db.execute('UPDATE jobs SET ' + ','.join(f'{key}=?' for key in changes) + ' WHERE id=?', (*changes.values(), job_id))

    def active_jobs(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM jobs WHERE status IN ('dispatching','queued','running','tracking_error')")]

    def prune(self, now, days=90):
        cutoff = stamp(instant(now) - timedelta(days=days))
        with self.connect() as db:
            db.execute('DELETE FROM task_events WHERE timestamp < ? AND id NOT IN (SELECT e.id FROM task_events e WHERE e.id=(SELECT t.id FROM task_events t WHERE t.task_id=e.task_id ORDER BY timestamp DESC,id DESC LIMIT 1))', (cutoff,))
            db.execute("DELETE FROM reminders WHERE status IN ('completed','cancelled') AND completed_at < ?", (cutoff,))
            db.execute('DELETE FROM deliveries WHERE reminder_id NOT IN (SELECT id FROM reminders) OR revision != (SELECT revision FROM reminders WHERE id=reminder_id)')
            # A live round retains its attempts even when it spans the retention window.
            if db.execute("SELECT 1 FROM sqlite_master WHERE name='step_runs'").fetchone():
                rows=db.execute('SELECT id,data FROM step_runs').fetchall()
                expired=[r['id'] for r in rows if (data:=json.loads(r['data'])).get('ended_at') and data['ended_at']<cutoff
                         and not db.execute("SELECT 1 FROM step_attempts a JOIN jobs j ON j.id=a.job_id WHERE a.run_id=? AND j.status IN ('dispatching','queued','running','tracking_error')",(r['id'],)).fetchone()]
                db.executemany('DELETE FROM step_attempts WHERE run_id=?',[(key,) for key in expired])
                db.executemany('DELETE FROM step_runs WHERE id=?',[(key,) for key in expired])
                db.execute("DELETE FROM jobs WHERE status NOT IN ('dispatching','queued','running','tracking_error') AND created_at < ? AND id NOT IN (SELECT job_id FROM step_attempts)",(cutoff,))
            else:
                db.execute("DELETE FROM jobs WHERE status NOT IN ('dispatching','queued','running','tracking_error') AND created_at < ?", (cutoff,))
            db.execute('PRAGMA incremental_vacuum')

    def snapshot(self, now, expected_tasks=None, stale_seconds=180):
        now = instant(now)
        with self.connect() as db:
            reminders = [dict(r) for r in db.execute("SELECT * FROM reminders WHERE status IN ('pending','snoozed') ORDER BY remind_at")]
            tasks = [dict(r) for r in db.execute('SELECT e.* FROM task_events e WHERE e.id=(SELECT t.id FROM task_events t WHERE t.task_id=e.task_id ORDER BY timestamp DESC,id DESC LIMIT 1) ORDER BY task_id')]
            feeds = {r['name']: {'data': json.loads(r['data']), 'updated_at': r['updated_at'],
                     'stale': (now - instant(r['updated_at'])).total_seconds() > stale_seconds} for r in db.execute('SELECT * FROM feeds')}
            job = db.execute('SELECT * FROM jobs ORDER BY created_at DESC, rowid DESC LIMIT 1').fetchone()
        attention = []
        for reminder in reminders:
            if instant(reminder['remind_at']) <= now:
                attention.append(dict(kind='reminder', id=reminder['id'], title=reminder['title'], message='已到提醒时间'))
        by_id = {t['task_id']: t for t in tasks}
        for task_id, config in (expected_tasks or {}).items():
            task = by_id.get(task_id)
            if task is None:
                task = dict(task_id=task_id, task_name=config.get('name', task_id), status='unknown', message='尚未收到执行结果', timestamp=None)
                tasks.append(task)
            elif (now - instant(task['timestamp'])).total_seconds() > config.get('max_age_hours', 36) * 3600:
                task.update(status='unknown', message='超过预期时间未收到新结果')
        for task in tasks:
            if task['status'] in ('failed', 'warning', 'unknown'):
                attention.append(dict(kind='task', id=task['task_id'], title=task['task_name'], message=task['message']))
        for name in ('servers', 'network', 'mail'):
            feed = feeds.get(name)
            if not feed:
                continue
            data = feed['data']
            if feed['stale'] or data.get('error'):
                attention.append(dict(kind='integration', id=name, title={'servers': '服务器监控', 'network': '网络监控', 'mail': '邮件同步'}[name], message=data.get('error') or '数据已过期，等待更新'))
                if name == 'servers':
                    for server in data.get('items', []):
                        server['status'] = 'unknown'
                elif name == 'network':
                    data['online'] = None
                continue
            if name == 'servers':
                for server in data.get('items', []):
                    if server['status'] != 'up':
                        attention.append(dict(kind='server', id=server['id'], title=server['name'], message=server.get('error') or ('服务器离线' if server['status'] == 'down' else '服务器状态未知')))
            elif name == 'network':
                if data.get('online') is False:
                    attention.append(dict(kind='network', id='internet', title='Internet', message='网络连接异常'))
                for node in data.get('nodes', []):
                    if node.get('online') is False or node.get('error'):
                        attention.append(dict(kind='network', id=node['name'], title=node['name'], message=node.get('error') or '节点未回应 Ping'))
            elif name == 'mail':
                for account in data.get('accounts',[]):
                    if account.get('error'):attention.append(dict(kind='integration',id='mail.'+account['id'],title=account['name'],message=account['error']))
        return dict(reminders=reminders, tasks=tasks, feeds=feeds, wxstep=dict(job) if job else None,
                    attention=attention, updated_at=stamp(now))
