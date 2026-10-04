"""Password and opaque session storage; notification actions use scoped capabilities."""
import base64
import hashlib
import hmac
import json
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

    def login(self, username, password):
        if not self.check_password(username, password):
            return None
        token = secrets.token_urlsafe(32)
        csrf = secrets.token_urlsafe(32)
        with self.connect() as db:
            db.execute('DELETE FROM sessions WHERE expires < ?', (time.time(),))
            db.execute('INSERT INTO sessions VALUES (?,?,?,?)',
                       (hashlib.sha256(token.encode()).hexdigest(), username, csrf, time.time()+7*86400))
        return token, csrf

    def session(self, token):
        if not token or len(token)>128:
            return None
        with self.connect() as db:
            row = db.execute('SELECT * FROM sessions WHERE id=? AND expires>?',
                             (hashlib.sha256(token.encode()).hexdigest(), time.time())).fetchone()
        return dict(row) if row else None

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
