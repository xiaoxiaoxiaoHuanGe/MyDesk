"""Native FCM protocol with a local OAuth/FCM server and real RSA verification."""
import base64
import json
import unittest
from aiohttp import ClientSession,web
from aiohttp.test_utils import TestServer
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa,padding

try:
    from mydesk.fcm import Fcm,PushRetry,TokenExpired
except ImportError:
    Fcm=PushRetry=TokenExpired=None


class FcmTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.assertIsNotNone(Fcm,'Native HTTP v1 FCM sender is missing')
        self.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.credentials={'type':'service_account','project_id':'mydesk-test','private_key_id':'key1',
            'client_email':'sender@mydesk-test.iam.gserviceaccount.com',
            'private_key':self.key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()}
        self.oauth_calls=0;self.sent=[];self.status=200;self.error_code=None
        async def oauth(request):
            self.oauth_calls+=1
            data=await request.post()
            self.assertEqual(data['grant_type'],'urn:ietf:params:oauth:grant-type:jwt-bearer')
            header,body,signature=data['assertion'].split('.')
            decode=lambda text:base64.urlsafe_b64decode(text+'='*(-len(text)%4))
            self.key.public_key().verify(decode(signature),(header+'.'+body).encode(),padding.PKCS1v15(),hashes.SHA256())
            claims=json.loads(decode(body))
            self.assertEqual(claims['aud'],'https://oauth2.googleapis.com/token')
            self.assertEqual(claims['scope'],'https://www.googleapis.com/auth/firebase.messaging')
            self.assertLessEqual(claims['exp']-claims['iat'],3600)
            return web.json_response({'access_token':'server-access-secret','expires_in':3600})
        async def send(request):
            self.assertEqual(request.headers['Authorization'],'Bearer server-access-secret')
            self.sent.append(await request.json())
            if self.status != 200:return web.json_response({'error':{'details':[{'errorCode':self.error_code}],'message':'private-error-detail'}},status=self.status,headers={'Retry-After':'120'})
            return web.json_response({'name':'projects/mydesk-test/messages/id1'})
        app=web.Application();app.router.add_post('/token',oauth);app.router.add_post('/send',send)
        self.server=TestServer(app);await self.server.start_server()
        self.http=ClientSession()
        test=self
        class LocalGoogle:
            def post(self,url,**kwargs):
                test.assertFalse(kwargs.get('allow_redirects',True),'Credentials must never follow redirects')
                if url=='https://oauth2.googleapis.com/token':path='/token'
                else:
                    test.assertEqual(url,'https://fcm.googleapis.com/v1/projects/mydesk-test/messages:send')
                    path='/send'
                return test.http.post(test.server.make_url(path),**kwargs)
        self.sender=Fcm(LocalGoogle(),self.credentials)
        self.data={'event_id':'a'*32,'kind':'alert','title':'MyDesk','body':'服务器离线'}

    async def asyncTearDown(self):
        if hasattr(self,'http'):await self.http.close()
        if hasattr(self,'server'):await self.server.close()

    async def test_signed_oauth_data_only_payload_and_cached_access_token(self):
        await self.sender.send('native-token',self.data)
        await self.sender.send('native-token',self.data)
        self.assertEqual(self.oauth_calls,1)
        message=self.sent[0]['message']
        self.assertEqual(message['token'],'native-token')
        self.assertEqual(message['data'],self.data)
        self.assertNotIn('notification',message)
        self.assertNotIn('webpush',message)
        self.assertEqual(message['android']['priority'],'HIGH')

    async def test_unregistered_token_is_distinct_from_bad_request_and_safe_errors(self):
        self.status=404;self.error_code='UNREGISTERED'
        with self.assertRaises(TokenExpired):await self.sender.send('native-token',self.data)
        self.status=400;self.error_code='INVALID_ARGUMENT'
        with self.assertRaises(PushRetry) as error:await self.sender.send('native-token',self.data)
        self.assertNotIn('private-error-detail',str(error.exception))

    async def test_quota_response_preserves_server_retry_after(self):
        self.status=429;self.error_code='QUOTA_EXCEEDED'
        with self.assertRaises(PushRetry) as error:await self.sender.send('native-token',self.data)
        self.assertEqual(error.exception.retry_after,120)

    async def test_silent_sync_uses_normal_priority_and_can_collapse_obsolete_sync_requests(self):
        await self.sender.send('native-token',{**self.data,'kind':'sync'})
        message=self.sent[0]['message']
        self.assertEqual(message['android']['priority'],'NORMAL')
        self.assertEqual(message['android']['collapse_key'],'mydesk-sync')

    async def test_service_account_uri_and_payload_cannot_redirect_or_export_credentials(self):
        credentials={**self.credentials,'token_uri':'https://evil.example/token'}
        sender=Fcm(self.sender.session,credentials)
        await sender.send('native-token',self.data)
        with self.assertRaises(ValueError):await sender.send('native-token',{**self.data,'github_token':'private'})
        self.assertEqual(self.oauth_calls,1)
