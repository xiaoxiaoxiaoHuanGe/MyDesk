"""Password and opaque session storage; notification actions use scoped capabilities."""
import base64
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager


def encode(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def decode(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


class Auth:
    def __init__(self, path):
        self.path = path
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS account(username TEXT PRIMARY KEY, salt TEXT, password_hash TEXT);
                CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, username TEXT, csrf TEXT, expires REAL);
                CREATE TABLE IF NOT EXISTS keys(name TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE IF NOT EXISTS qr_logins(
                    id TEXT PRIMARY KEY,scan_hash TEXT NOT NULL,browser_hash TEXT NOT NULL,
                    browser TEXT NOT NULL,expires REAL NOT NULL,status TEXT NOT NULL,
                    approver_session TEXT,username TEXT);
            ''')
            db.execute('INSERT OR IGNORE INTO keys VALUES (?,?)', ('action', secrets.token_urlsafe(48)))
            self.key = db.execute("SELECT value FROM keys WHERE name='action'").fetchone()[0].encode()
        self.dummy_salt = secrets.token_hex(16)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def digest(self, password, salt):
        return hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), 600000).hex()

    def bootstrap(self):
        with self.connect() as db:
            if db.execute('SELECT 1 FROM account').fetchone():
                return None
            password, salt = secrets.token_urlsafe(24), secrets.token_hex(16)
            db.execute('INSERT INTO account VALUES (?,?,?)', ('mydesk', salt, self.digest(password, salt)))
            return {'username':'mydesk', 'password':password}

    def check_password(self, username, password):
        if not isinstance(username, str) or not isinstance(password, str) or len(password) > 1024:
            return False
        with self.connect() as db:
            account = db.execute('SELECT * FROM account WHERE username=?', (username,)).fetchone()
        digest = self.digest(password, account['salt'] if account else self.dummy_salt)
        return bool(account and hmac.compare_digest(digest, account['password_hash']))

    def login(self, username, password, persistent=False):
        if not self.check_password(username, password):
            return None
        with self.connect() as db:
            return self._issue_session(db,username,persistent)

    def _issue_session(self, db, username, persistent=False):
        token = secrets.token_urlsafe(32)
        csrf = secrets.token_urlsafe(32)
        db.execute('DELETE FROM sessions WHERE expires < ?', (time.time(),))
        db.execute('INSERT INTO sessions VALUES (?,?,?,?)',
                   (self._hash(token), username, csrf, None if persistent else time.time()+7*86400))
        return token, csrf

    @staticmethod
    def _hash(value):
        return hashlib.sha256(value.encode()).hexdigest()

    def session(self, token):
        if not token or len(token)>128:
            return None
        with self.connect() as db:
            row = db.execute('SELECT * FROM sessions WHERE id=? AND (expires IS NULL OR expires>?)',
                             (self._hash(token), time.time())).fetchone()
        return dict(row) if row else None

    def session_active(self, session_id):
        with self.connect() as db:
            return db.execute('SELECT 1 FROM sessions WHERE id=? AND (expires IS NULL OR expires>?)',
                              (session_id,time.time())).fetchone() is not None

    def persist_session(self, session_id):
        with self.connect() as db:
            db.execute('UPDATE sessions SET expires=NULL WHERE id=? AND expires>?',(session_id,time.time()))

    def start_qr(self, browser):
        identifier,scan,browser_secret=secrets.token_hex(16),secrets.token_urlsafe(32),secrets.token_urlsafe(32)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM qr_logins WHERE expires<=?',(time.time(),))
            if db.execute('SELECT COUNT(*) FROM qr_logins').fetchone()[0]>=2048:
                raise ValueError('登录请求过多，请稍后重试')
            db.execute('INSERT INTO qr_logins VALUES (?,?,?,?,?,?,NULL,NULL)',
                       (identifier,self._hash(scan),self._hash(browser_secret),browser,time.time()+120,'waiting'))
        return identifier,scan,browser_secret

    def _scan_request(self, db, identifier, token):
        if not isinstance(identifier,str) or not re.fullmatch(r'[a-f0-9]{32}',identifier) or not isinstance(token,str) or len(token)>128:
            raise ValueError('二维码无效，请扫描 MyDesk 登录二维码')
        row=db.execute('SELECT * FROM qr_logins WHERE id=?',(identifier,)).fetchone()
        if not row or row['expires']<=time.time() or row['status'] not in ('waiting','scanned') or not hmac.compare_digest(row['scan_hash'],self._hash(token)):
            raise ValueError('二维码已失效，请在电脑上刷新')
        return row

    def preview_qr(self, identifier, token, session):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._scan_request(db,identifier,token)
            if row['approver_session'] and row['approver_session']!=session['id']:
                raise ValueError('二维码已由其他设备扫描，请刷新')
            db.execute("UPDATE qr_logins SET status='scanned',approver_session=?,username=? WHERE id=?",
                       (session['id'],session['username'],identifier))
            return {'browser':row['browser'],'expires_in':max(0,int(row['expires']-time.time()))}

    def confirm_qr(self, identifier, token, session, approve):
        if type(approve) is not bool:
            raise ValueError('请确认是否允许电脑登录')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._scan_request(db,identifier,token)
            if row['status']!='scanned' or row['approver_session']!=session['id']:
                return False
            db.execute('UPDATE qr_logins SET status=? WHERE id=?',('approved' if approve else 'rejected',identifier))
            return True

    def poll_qr(self, identifier, browser_secret):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT * FROM qr_logins WHERE id=?',(identifier,)).fetchone()
            if not browser_secret or len(browser_secret)>128 or (row and not hmac.compare_digest(row['browser_hash'],self._hash(browser_secret))):
                raise PermissionError('请使用显示该二维码的浏览器登录')
            if not row or row['expires']<=time.time():
                return 'expired',None
            if row['status']=='approved':
                approver=db.execute('SELECT 1 FROM sessions WHERE id=? AND expires IS NULL',(row['approver_session'],)).fetchone()
                if not approver:
                    db.execute("UPDATE qr_logins SET status='expired' WHERE id=?",(identifier,))
                    return 'expired',None
                result=self._issue_session(db,row['username'])
                db.execute("UPDATE qr_logins SET status='used' WHERE id=?",(identifier,))
                return 'approved',(row['username'],*result)
            return row['status'],None

    def revoke(self, session_id):
        with self.connect() as db:
            db.execute('DELETE FROM sessions WHERE id=?', (session_id,))

    def change_password(self, username, current, new):
        if not self.check_password(username, current):
            raise ValueError('当前密码不正确')
        if not isinstance(new, str) or not 12 <= len(new) <= 256:
            raise ValueError('新密码需要 12–256 个字符')
        salt = secrets.token_hex(16)
        with self.connect() as db:
            db.execute('UPDATE account SET salt=?,password_hash=? WHERE username=?',
                       (salt, self.digest(new, salt), username))
            db.execute('DELETE FROM sessions WHERE username=?', (username,))

    def action_token(self, reminder_id, revision, expires=None):
        payload = encode(json.dumps({'id':reminder_id,'revision':revision,
                                     'expires':expires or int(time.time()+7*86400)}, separators=(',',':')).encode())
        return payload + '.' + encode(hmac.digest(self.key, payload.encode(), 'sha256'))

    def verify_action(self, token):
        try:
            if not isinstance(token,str) or len(token)>1500:
                return None
            payload, signature = token.split('.')
            if not hmac.compare_digest(hmac.digest(self.key,payload.encode(),'sha256'),decode(signature)):
                return None
            data = json.loads(decode(payload))
            if data['expires'] < time.time():
                return None
            return data
        except (ValueError, TypeError, KeyError):
            return None
