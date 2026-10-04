"""Standards-based encrypted Web Push. Provider receipt is not device delivery proof."""
import asyncio
import base64
import json
import secrets
import sqlite3
from contextlib import contextmanager
from urllib.parse import urlsplit

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from pywebpush import webpush, WebPushException


class Push:
    def __init__(self, directory, auth):
        self.path=directory/'push.sqlite3'
        self.auth=auth
        self.key=directory/'vapid.pem'
        if not self.key.exists():
            private=ec.generate_private_key(ec.SECP256R1())
            self.key.write_bytes(private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        private=serialization.load_pem_private_key(self.key.read_bytes(),password=None)
        self.public_key=base64.urlsafe_b64encode(private.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)).decode().rstrip('=')
        with self.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT, subscription TEXT, error TEXT)')

    @contextmanager
    def connect(self):
        db=sqlite3.connect(self.path,timeout=15)
        try:
            with db:
                yield db
        finally:
            db.close()

    def devices(self):
        with self.connect() as db:
            return [{'id':row[0],'name':row[1],'error':row[2]} for row in db.execute('SELECT id,name,error FROM devices ORDER BY name')]

    def subscribe(self, subscription, name):
        try:
            url=urlsplit(subscription['endpoint'])
            if url.scheme!='https' or url.hostname not in ('fcm.googleapis.com','updates.push.services.mozilla.com','web.push.apple.com') or url.username or url.port not in (None,443):
                raise ValueError()
            decode=lambda x:base64.urlsafe_b64decode(x+'='*(-len(x)%4))
            ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(),decode(subscription['keys']['p256dh']))
            if len(decode(subscription['keys']['auth']))!=16:
                raise ValueError()
            if not isinstance(name,str) or not 1<=len(name.strip())<=80:
                raise ValueError()
        except (ValueError,KeyError,TypeError,AttributeError):
            raise ValueError('通知订阅无效，请使用支持推送的浏览器重新启用') from None
        encoded=json.dumps(subscription,separators=(',',':'))
        with self.connect() as db:
            for row in db.execute('SELECT id,subscription FROM devices'):
                if json.loads(row[1])['endpoint']==subscription['endpoint']:
                    device_id=row[0]
                    break
            else:
                device_id=secrets.token_hex(16)
            db.execute('INSERT OR REPLACE INTO devices VALUES (?,?,?,NULL)',(device_id,name.strip(),encoded))
        return {'id':device_id,'name':name.strip()}

    def remove(self, device_id):
        with self.connect() as db:
            db.execute('DELETE FROM devices WHERE id=?',(device_id,))

    async def notify(self, device_id, data):
        with self.connect() as db:
            row=db.execute('SELECT subscription FROM devices WHERE id=?',(device_id,)).fetchone()
        if not row:
            raise ValueError('通知设备已移除')
        payload={'title':data['title'],'body':data['message'],'url':'/',
                 'tag':data.get('data',{}).get('tag','mydesk-test'),'actions':[]}
        actions=data.get('data',{}).get('actions',[])
        if actions:
            parts=actions[0]['action'].split(':')
            payload['token']=self.auth.action_token(parts[1],parts[2])
            payload['actions']=[{'action':'complete','title':'完成'},{'action':'snooze','title':'10分钟后提醒'}]
        try:
            await asyncio.to_thread(webpush, subscription_info=json.loads(row[0]),data=json.dumps(payload,ensure_ascii=False),
                vapid_private_key=str(self.key),vapid_claims={'sub':'mailto:mydesk@localhost.invalid'},ttl=900,timeout=15)
            error=None
        except WebPushException as exc:
            if exc.response is not None and exc.response.status_code in (404,410):
                self.remove(device_id)
            else:
                with self.connect() as db:
                    db.execute('UPDATE devices SET error=? WHERE id=?',('推送请求失败，等待重试',device_id))
            raise ValueError('推送请求失败；请检查网络和设备订阅') from None
        with self.connect() as db:
            db.execute('UPDATE devices SET error=? WHERE id=?',(error,device_id))
