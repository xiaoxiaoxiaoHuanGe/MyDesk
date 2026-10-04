import base64
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import http_ece
from requests import Response
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from mydesk.auth import Auth
from mydesk.push import Push

class PushTests(unittest.IsolatedAsyncioTestCase):
    async def test_actual_vapid_and_encryption_preserve_scoped_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            auth=Auth(root/'auth.sqlite3')
            push=Push(root,auth)
            key=ec.generate_private_key(ec.SECP256R1())
            public=key.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
            auth_secret=b'0123456789abcdef'
            encode=lambda x:base64.urlsafe_b64encode(x).decode().rstrip('=')
            subscription={'endpoint':'https://fcm.googleapis.com/fcm/send/test-id',
                          'keys':{'p256dh':encode(public),'auth':encode(auth_secret)}}
            device=push.subscribe(subscription,'Android test')
            response=Response();response.status_code=201;response._content=b''
            # Only replace external network I/O. Real VAPID signing and payload encryption execute.
            with patch('requests.post',return_value=response) as post:
                await push.notify(device['id'],{'title':'MyDesk','message':'到期提醒','data':{'tag':'mydesk-reminder','actions':[{'action':'MYDESK:abc:1:complete'}]}})
            args=post.call_args.kwargs
            plaintext=http_ece.decrypt(args['data'],private_key=key,auth_secret=auth_secret,version='aes128gcm')
            data=json.loads(plaintext)
            self.assertEqual(data['body'],'到期提醒')
            self.assertEqual([a['action'] for a in data['actions']],['complete','snooze'])
            scope=auth.verify_action(data['token'])
            self.assertEqual(scope['id'],'abc')
            self.assertIn('vapid',str(args['headers']).lower())
            self.assertNotIn('到期提醒',str(args['data']))

    async def test_expired_device_is_removed_and_failure_not_acknowledged(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            push=Push(root,Auth(root/'auth.sqlite3'))
            key=ec.generate_private_key(ec.SECP256R1())
            encode=lambda x:base64.urlsafe_b64encode(x).decode().rstrip('=')
            sub={'endpoint':'https://fcm.googleapis.com/fcm/send/test-id','keys':{
                'p256dh':encode(key.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)),
                'auth':encode(b'0123456789abcdef')}}
            device=push.subscribe(sub,'Android test')
            response=Response();response.status_code=410;response._content=b'expired';response.reason='Gone'
            with patch('requests.post',return_value=response):
                with self.assertRaises(ValueError):
                    await push.notify(device['id'],{'title':'MyDesk','message':'提醒','data':{}})
            self.assertEqual(push.devices(),[])
