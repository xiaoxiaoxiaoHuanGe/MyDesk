"""Text inbox with per-source authentication and transactional dispatch outbox."""
import hashlib
import hmac
import re
import secrets
from datetime import timedelta
from uuid import uuid4
from .domain import stamp,instant,text_field

ID=re.compile('[a-f0-9]{32}')
KEY=re.compile('[a-f0-9]{64}')
class IncomingError(ValueError):
    def __init__(self,status,message):self.status=status;super().__init__(message)

def parse_text(payload):
    if not isinstance(payload,dict) or payload.get('msg_type')!='text':raise IncomingError(400,'仅支持 msg_type=text')
    content=payload.get('content')
    if not isinstance(content,dict) or set(content)!={'text'} or not isinstance(content.get('text'),str) or not content['text'] or '\x00' in content['text']:
        raise IncomingError(400,'content.text 必须为非空纯文本')
    if set(payload)-{'msg_type','content','timestamp','sign'}:raise IncomingError(400,'消息结构不支持')
    if len(content['text'].encode('utf-8'))>65536:raise IncomingError(413,'请求内容过大')
    return content['text']

class Inbox:
    def __init__(self,desk):
        self.desk=desk
        with desk.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS notification_sources(id TEXT PRIMARY KEY,name TEXT NOT NULL,
                    kind TEXT NOT NULL,enabled INTEGER NOT NULL,secret_hash TEXT NOT NULL,deleted_at TEXT,last_received_at TEXT);
                CREATE TABLE IF NOT EXISTS inbox_messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,
                    source_id TEXT NOT NULL,source_name TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,
                    msg_type TEXT NOT NULL,received_at TEXT NOT NULL,read_at TEXT,idempotency_key TEXT,
                    UNIQUE(source_id,idempotency_key));
                CREATE INDEX IF NOT EXISTS inbox_source_seq ON inbox_messages(source_id,seq);
                CREATE TABLE IF NOT EXISTS inbox_dispatch(message_id TEXT PRIMARY KEY,created_at TEXT NOT NULL,event_id TEXT);
                CREATE TABLE IF NOT EXISTS inbox_rate(source_id TEXT NOT NULL,received_at TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS inbox_rate_source ON inbox_rate(source_id,received_at);
            ''')
    def sources(self):
        with self.desk.connect() as db:
            return [dict(r) for r in db.execute('SELECT id,name,kind,enabled,last_received_at FROM notification_sources WHERE deleted_at IS NULL ORDER BY rowid')]
    def create(self,data):
        name=text_field(data.get('name'),'来源名称',80);kind=data.get('kind','cpe')
        if kind not in ('cpe','text'):raise ValueError('来源类型无效')
        key=secrets.token_hex(32);identifier=uuid4().hex
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT COUNT(*) FROM notification_sources WHERE deleted_at IS NULL').fetchone()[0]>=50:raise ValueError('最多 50 个来源')
            db.execute('INSERT INTO notification_sources VALUES (?,?,?,1,?,NULL,NULL)',(identifier,name,kind,hashlib.sha256(key.encode()).hexdigest()))
        return {'id':identifier,'name':name,'kind':kind,'enabled':True,'receive_path':f'/api/incoming/feishu/{identifier}/{key}'}
    def change(self,identifier,data=None,action='edit',now=None):
        if not ID.fullmatch(identifier):raise ValueError('来源标识无效')
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT * FROM notification_sources WHERE id=? AND deleted_at IS NULL',(identifier,)).fetchone()
            if not row:raise IncomingError(404,'来源不存在')
            if action=='rotate':
                key=secrets.token_hex(32)
                db.execute('UPDATE notification_sources SET secret_hash=? WHERE id=?',(hashlib.sha256(key.encode()).hexdigest(),identifier))
                return {'receive_path':f'/api/incoming/feishu/{identifier}/{key}'}
            if action=='delete':db.execute('UPDATE notification_sources SET deleted_at=?,enabled=0 WHERE id=?',(stamp(now),identifier))
            else:
                name=text_field(data.get('name',row['name']),'来源名称',80);enabled=data.get('enabled',bool(row['enabled']))
                if type(enabled) is not bool:raise ValueError('来源开关无效')
                db.execute('UPDATE notification_sources SET name=?,enabled=? WHERE id=?',(name,enabled,identifier))
        return {'saved':True}
    def receive(self,identifier,secret,payload,key,now):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT * FROM notification_sources WHERE id=? AND deleted_at IS NULL',(identifier,)).fetchone()
            if not row:raise IncomingError(404,'接收地址无效')
            if not isinstance(secret,str) or not hmac.compare_digest(hashlib.sha256(secret.encode()).hexdigest(),row['secret_hash']):raise IncomingError(401,'接收凭据无效')
            if not row['enabled']:raise IncomingError(403,'接收地址不可用')
            body=parse_text(payload)
            if (row['kind']=='cpe' and not key) or (key is not None and (not isinstance(key,str) or not KEY.fullmatch(key))):raise IncomingError(400,'Idempotency-Key 必须为 64 位小写十六进制')
            self._prune(db,now)
            if key and db.execute('SELECT 1 FROM inbox_messages WHERE source_id=? AND idempotency_key=?',(identifier,key)).fetchone():return False
            cutoff=stamp(now-timedelta(minutes=1))
            db.execute('DELETE FROM inbox_rate WHERE received_at<=?',(cutoff,))
            if db.execute('SELECT COUNT(*) FROM inbox_rate WHERE source_id=?',(identifier,)).fetchone()[0]>=60:raise IncomingError(429,'接收频率过高，请稍后重试')
            db.execute('INSERT INTO inbox_rate VALUES (?,?)',(identifier,stamp(now)))
            message=uuid4().hex;title=(body.splitlines()[0] or row['name'])[:120]
            db.execute('INSERT INTO inbox_messages(id,source_id,source_name,title,body,msg_type,received_at,idempotency_key) VALUES (?,?,?,?,?,?,?,?)',
                (message,identifier,row['name'],title,body,'text',stamp(now),key))
            db.execute('INSERT INTO inbox_dispatch VALUES (?,?,NULL)',(message,stamp(now)))
            db.execute('UPDATE notification_sources SET last_received_at=? WHERE id=?',(stamp(now),identifier))
            self._prune(db,now)
        return True
    def _prune(self,db,now):
        cutoff=stamp(now-timedelta(days=90))
        removed=[r[0] for r in db.execute('SELECT id FROM inbox_messages WHERE received_at<? OR seq IN (SELECT seq FROM inbox_messages ORDER BY seq DESC LIMIT -1 OFFSET 10000)',(cutoff,))]
        for identifier in removed:
            dispatch=db.execute('SELECT event_id FROM inbox_dispatch WHERE message_id=?',(identifier,)).fetchone()
            if dispatch and dispatch['event_id']:
                db.execute('DELETE FROM native_deliveries WHERE event_id=?',(dispatch['event_id'],))
                db.execute('DELETE FROM native_events WHERE id=?',(dispatch['event_id'],))
            db.execute('DELETE FROM inbox_dispatch WHERE message_id=?',(identifier,));db.execute('DELETE FROM inbox_messages WHERE id=?',(identifier,))
    def page(self,limit=50,before=None,source_id=None,now=None):
        if type(limit) is not int or not 1<=limit<=100 or (before is not None and (type(before) is not int or before<1)) or (source_id is not None and (not isinstance(source_id,str) or not ID.fullmatch(source_id))):raise ValueError('分页参数无效')
        with self.desk.connect() as db:
            if now:self._prune(db,now)
            rows=[dict(r) for r in db.execute('SELECT * FROM inbox_messages WHERE (? IS NULL OR seq<?) AND (? IS NULL OR source_id=?) ORDER BY seq DESC LIMIT ?', (before,before,source_id,source_id,limit))]
        for row in rows:row['summary']=row['body'][:500];row.pop('idempotency_key',None)
        return {'items':rows,'next_cursor':rows[-1]['seq'] if len(rows)==limit else None}
    def detail(self,identifier):
        with self.desk.connect() as db:row=db.execute('SELECT * FROM inbox_messages WHERE id=?',(identifier,)).fetchone()
        if not row:raise IncomingError(404,'消息已清理或不存在')
        result=dict(row);result.pop('idempotency_key',None);return result
    def read(self,data,now):
        ids=data.get('ids');through=data.get('through_seq')
        if ids is not None and through is not None:raise ValueError('已读范围无效')
        with self.desk.connect() as db:
            if ids is not None:
                if not isinstance(ids,list) or len(ids)>100 or any(not isinstance(i,str) or not ID.fullmatch(i) for i in ids):raise ValueError('消息标识无效')
                for i in ids:db.execute('UPDATE inbox_messages SET read_at=COALESCE(read_at,?) WHERE id=?',(stamp(now),i))
            elif type(through) is int and through>=0:
                db.execute('UPDATE inbox_messages SET read_at=COALESCE(read_at,?) WHERE seq<=?',(stamp(now),through))
            else:raise ValueError('已读范围无效')
        return {'saved':True}
    def snapshot(self,now):
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            self._prune(db,now)
            recent=[dict(r) for r in db.execute('SELECT seq,id,source_id,source_name,title,received_at,read_at,substr(body,1,500) AS summary FROM inbox_messages ORDER BY seq DESC LIMIT 3')]
            unread=db.execute('SELECT COUNT(*) FROM inbox_messages WHERE read_at IS NULL').fetchone()[0]
            latest=db.execute('SELECT COALESCE(MAX(seq),0) FROM inbox_messages').fetchone()[0]
            sources=[dict(r) for r in db.execute('SELECT id,name,kind,enabled,last_received_at FROM notification_sources WHERE deleted_at IS NULL ORDER BY rowid')]
        return {'supported':True,'recent':recent,'unread':unread,'latest_seq':latest,'sources':sources}
