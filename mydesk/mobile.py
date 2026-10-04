"""Native device registration and transactional, idempotent offline reminder actions."""
import hashlib
import json
import re
from uuid import uuid4
from datetime import timedelta
from .domain import instant,stamp,text_field


class MobileConflict(ValueError):pass
class DeviceRemoved(ValueError):pass


class Mobile:
    def __init__(self,desk):
        self.desk=desk
        with desk.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS native_devices (
                    id TEXT PRIMARY KEY,name TEXT NOT NULL,session_id TEXT NOT NULL,
                    local_alarm INTEGER NOT NULL,push_token TEXT,received_at TEXT);
                CREATE TABLE IF NOT EXISTS mobile_operations (
                    device_id TEXT,operation_id TEXT,fingerprint TEXT,result TEXT,created_at TEXT,
                    PRIMARY KEY(device_id,operation_id));
            ''')
            if 'notifications_enabled' not in {row['name'] for row in db.execute('PRAGMA table_info(native_devices)')}:
                db.execute('ALTER TABLE native_devices ADD COLUMN notifications_enabled INTEGER NOT NULL DEFAULT 0')
            if 'registration_id' not in {row['name'] for row in db.execute('PRAGMA table_info(native_devices)')}:
                db.execute("ALTER TABLE native_devices ADD COLUMN registration_id TEXT NOT NULL DEFAULT ''")
                for row in db.execute('SELECT id FROM native_devices').fetchall():
                    db.execute('UPDATE native_devices SET registration_id=? WHERE id=?',(uuid4().hex,row['id']))

    def identifier(self,value):
        if not isinstance(value,str) or not re.fullmatch(r'[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}',value):
            raise ValueError('设备或操作标识无效')
        return value

    def register(self,data,session_id):
        device_id=self.identifier(data.get('id'))
        name=text_field(data.get('name'),'设备名称',80)
        local=data.get('local_alarm',True)
        token=data.get('push_token')
        if type(local) is not bool or (token is not None and (not isinstance(token,str) or not 20<=len(token)<=4096 or not re.fullmatch(r'[A-Za-z0-9_:.-]+',token))):
            raise ValueError('设备配置无效')
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            previous=db.execute('SELECT * FROM native_devices WHERE id=?',(device_id,)).fetchone()
            if 'push_token' not in data:
                if previous:token=previous['push_token']
            enabled=data.get('notifications_enabled',bool(previous['notifications_enabled']) if previous else False)
            registration=previous['registration_id'] if previous and previous['session_id']==session_id else uuid4().hex
            if type(enabled) is not bool:raise ValueError('通知权限状态无效')
            db.execute('INSERT INTO native_devices(id,name,session_id,local_alarm,push_token,received_at,notifications_enabled,registration_id) VALUES (?,?,?,?,?,NULL,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,session_id=excluded.session_id,local_alarm=excluded.local_alarm,push_token=excluded.push_token,notifications_enabled=excluded.notifications_enabled,registration_id=excluded.registration_id',
                       (device_id,name,session_id,int(local),token,int(enabled),registration))
        return {'id':device_id,'name':name,'local_alarm':local,'push_registered':bool(token),'notifications_enabled':enabled}

    def devices(self):
        with self.desk.connect() as db:
            return [{'id':r['id'],'name':r['name'],'local_alarm':bool(r['local_alarm']),
                     'push_registered':bool(r['push_token']),'received_at':r['received_at'],'notifications_enabled':bool(r['notifications_enabled']),'registration_id':r['registration_id']} for r in db.execute('SELECT * FROM native_devices ORDER BY name')]

    def update_push(self,data,session_id):
        device_id=self.identifier(data.get('id'))
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            current=db.execute('SELECT * FROM native_devices WHERE id=? AND session_id=?',(device_id,session_id)).fetchone()
            if not current:raise DeviceRemoved('设备已移除或会话不属于此设备')
            token=data.get('push_token',current['push_token'])
            enabled=data.get('notifications_enabled',bool(current['notifications_enabled']))
            if type(enabled) is not bool or (token is not None and (not isinstance(token,str) or not 20<=len(token)<=4096 or not re.fullmatch(r'[A-Za-z0-9_:.-]+',token))):raise ValueError('原生推送配置无效')
            db.execute('UPDATE native_devices SET push_token=?,notifications_enabled=? WHERE id=? AND session_id=?',(token,int(enabled),device_id,session_id))
        return {'id':device_id,'push_registered':bool(token),'notifications_enabled':enabled}

    def remove(self,device_id=None,session_id=None):
        with self.desk.connect() as db:
            if device_id is not None:db.execute('DELETE FROM native_devices WHERE id=?',(self.identifier(device_id),))
            elif session_id is not None:db.execute('DELETE FROM native_devices WHERE session_id=?',(session_id,))
            else:db.execute('DELETE FROM native_devices')

    def rename(self,data,session_id):
        device_id=self.identifier(data.get('id'))
        name=text_field(data.get('name'),'设备名称',80)
        if any(ord(char)<32 or 127<=ord(char)<=159 for char in data['name']):raise ValueError('设备名称不能包含控制字符')
        with self.desk.connect() as db:
            result=db.execute('UPDATE native_devices SET name=? WHERE id=? AND session_id=?',(name,device_id,session_id))
            if result.rowcount!=1:raise DeviceRemoved('设备已移除或会话不属于此设备')
        return {'id':device_id,'name':name}

    def reminder_action(self,data,now):
        if not isinstance(data,dict):raise ValueError('操作格式无效')
        device=self.identifier(data.get('device_id'))
        operation=self.identifier(data.get('operation_id'))
        reminder=text_field(data.get('id'),'提醒标识',80)
        revision=text_field(data.get('revision'),'提醒版本',80)
        action=data.get('action');minutes=data.get('minutes',10)
        occurred=instant(data.get('occurred_at'))
        if action not in ('complete','cancel','snooze') or type(minutes) is not int or minutes not in (10,30,60):
            raise ValueError('提醒操作无效')
        normalized={'id':reminder,'revision':revision,'action':action,'minutes':minutes,'occurred_at':stamp(occurred)}
        fingerprint=hashlib.sha256(json.dumps(normalized,sort_keys=True).encode()).hexdigest()
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM native_devices WHERE id=?',(device,)).fetchone():
                raise DeviceRemoved('设备已移除，请重新登录或注册')
            receipt=db.execute('SELECT * FROM mobile_operations WHERE device_id=? AND operation_id=?',(device,operation)).fetchone()
            if receipt:
                if receipt['fingerprint']!=fingerprint:raise MobileConflict('操作标识已用于另一项操作')
                return json.loads(receipt['result'])
            if occurred>now+timedelta(minutes=5) or occurred<now-timedelta(days=7):
                raise ValueError('离线操作时间无效或已超过七天')
            try:
                result=self.desk._reminder_action(db,reminder,action,occurred,minutes,revision)
            except ValueError as exc:raise MobileConflict(str(exc)) from None
            db.execute('INSERT INTO mobile_operations VALUES (?,?,?,?,?)',(device,operation,fingerprint,json.dumps(result),stamp(now)))
            db.execute('DELETE FROM mobile_operations WHERE created_at<?',(stamp(now-timedelta(days=30)),))
        return result
