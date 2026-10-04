"""Native device and offline reminder protocol, against the real local HTTP application."""
import json
import asyncio
import tempfile
import unittest
from datetime import datetime,timedelta,timezone
from pathlib import Path
from aiohttp import CookieJar
from aiohttp.test_utils import TestClient,TestServer
from mydesk.app import create_app


class MobileTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.directory=Path(self.temp.name)
        self.client=TestClient(TestServer(create_app(self.directory,scheduling=False)),cookie_jar=CookieJar(unsafe=True))
        await self.client.start_server()
        access=json.loads((self.directory/'local-access.json').read_text(encoding='utf-8'))
        response=await self.client.post('/api/login',json=access)
        self.headers={'X-MyDesk-CSRF':(await response.json())['csrf']}

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    async def register(self):
        response=await self.client.post('/api/mobile/devices',headers=self.headers,
            json={'id':'a'*32,'name':'真我 GT5 Pro','local_alarm':True})
        self.assertEqual(response.status,200)
        return await response.json()

    async def reminder(self):
        response=await self.client.post('/api/command',headers=self.headers,
            json={'action':'reminder/create','payload':{'title':'离线提醒','time':'30分钟后'}})
        return await response.json()

    async def test_device_rename_preserves_push_and_registration_and_requires_same_session(self):
        await self.register()
        await self.client.post('/api/mobile/push',headers=self.headers,json={'id':'a'*32,'push_token':'native:'+('x'*100),'notifications_enabled':True})
        before=(await (await self.client.get('/api/mobile/devices')).json())['devices'][0]
        response=await self.client.post('/api/mobile/device-name',headers=self.headers,json={'id':'a'*32,'name':'我的真我手机'})
        self.assertEqual(response.status,200)
        after=(await (await self.client.get('/api/mobile/devices')).json())['devices'][0]
        self.assertEqual(after['name'],'我的真我手机')
        for field in ('registration_id','push_registered','notifications_enabled','local_alarm'):
            self.assertEqual(before[field],after[field])
        other=TestClient(TestServer(create_app(self.directory,scheduling=False)),cookie_jar=CookieJar(unsafe=True))
        await other.start_server()
        try:
            self.assertEqual((await other.post('/api/mobile/device-name',json={'id':'a'*32,'name':'另一个会话'})).status,401)
            access=json.loads((self.directory/'local-access.json').read_text(encoding='utf-8'))
            login=await other.post('/api/login',json=access)
            headers={'X-MyDesk-CSRF':(await login.json())['csrf']}
            self.assertEqual((await other.post('/api/mobile/device-name',headers=headers,json={'id':'a'*32,'name':'另一个会话'})).status,403)
        finally:await other.close()

    async def test_invalid_or_removed_device_name_never_creates_a_registration(self):
        await self.register()
        for name in ('','   ','x'*81,'手机\n新名',123):
            response=await self.client.post('/api/mobile/device-name',headers=self.headers,json={'id':'a'*32,'name':name})
            self.assertEqual(response.status,400)
        self.assertEqual((await self.client.post('/api/mobile/device-name',json={'id':'a'*32,'name':'手机'})).status,403)
        await self.client.delete('/api/mobile/devices/'+'a'*32,headers=self.headers)
        response=await self.client.post('/api/mobile/device-name',headers=self.headers,json={'id':'a'*32,'name':'手机'})
        self.assertEqual(response.status,403)
        self.assertEqual((await (await self.client.get('/api/mobile/devices')).json())['devices'],[])

    async def test_device_needs_authentication_and_never_returns_push_token(self):
        await self.register()
        response=await self.client.get('/api/mobile/devices')
        self.assertEqual(response.status,200)
        self.assertNotIn('token',json.dumps(await response.json()))
        other=TestClient(TestServer(create_app(self.directory,scheduling=False)),cookie_jar=CookieJar(unsafe=True))
        await other.start_server()
        try:
            self.assertEqual((await other.get('/api/mobile/devices')).status,401)
        finally:await other.close()

    async def test_retry_has_same_result_and_preserves_original_snooze_time(self):
        await self.register()
        reminder=await self.reminder()
        occurred=datetime.now(timezone.utc)-timedelta(minutes=2)
        payload={'operation_id':'b'*32,'device_id':'a'*32,'id':reminder['id'],'revision':reminder['revision'],
                 'action':'snooze','minutes':30,'occurred_at':occurred.isoformat()}
        first=await self.client.post('/api/mobile/reminder-action',headers=self.headers,json=payload)
        self.assertEqual(first.status,200)
        result=await first.json()
        second=await self.client.post('/api/mobile/reminder-action',headers=self.headers,json=payload)
        self.assertEqual(second.status,200)
        self.assertEqual(result,await second.json())
        self.assertEqual(datetime.fromisoformat(result['remind_at']),occurred+timedelta(minutes=30))
        changed={**payload,'minutes':60}
        self.assertEqual((await self.client.post('/api/mobile/reminder-action',headers=self.headers,json=changed)).status,409)

    async def test_old_revision_cannot_override_a_newer_server_action(self):
        await self.register()
        reminder=await self.reminder()
        payload={'operation_id':'c'*32,'device_id':'a'*32,'id':reminder['id'],'revision':reminder['revision'],
                 'action':'snooze','minutes':10,'occurred_at':datetime.now(timezone.utc).isoformat()}
        self.assertEqual((await self.client.post('/api/mobile/reminder-action',headers=self.headers,json=payload)).status,200)
        stale={**payload,'operation_id':'d'*32,'action':'complete'}
        self.assertEqual((await self.client.post('/api/mobile/reminder-action',headers=self.headers,json=stale)).status,409)

    async def test_device_removal_prevents_later_operations(self):
        await self.register()
        self.assertEqual((await self.client.delete('/api/mobile/devices/'+'a'*32,headers=self.headers)).status,200)
        reminder=await self.reminder()
        data={'operation_id':'e'*32,'device_id':'a'*32,'id':reminder['id'],'revision':reminder['revision'],
              'action':'complete','occurred_at':datetime.now(timezone.utc).isoformat()}
        self.assertEqual((await self.client.post('/api/mobile/reminder-action',headers=self.headers,json=data)).status,403)

    async def test_registration_without_token_preserves_existing_push_registration(self):
        secret='native-token:'+'x'*100
        response=await self.client.post('/api/mobile/devices',headers=self.headers,
            json={'id':'a'*32,'name':'手机','local_alarm':True,'push_token':secret})
        self.assertEqual(response.status,200)
        self.assertNotIn(secret,await response.text())
        result=await self.register()
        self.assertTrue(result['push_registered'])
        response=await self.client.get('/api/mobile/devices')
        self.assertNotIn(secret,await response.text())
        response=await self.client.post('/api/mobile/devices',headers=self.headers,
            json={'id':'a'*32,'name':'手机','local_alarm':True,'push_token':None})
        self.assertFalse((await response.json())['push_registered'])

    async def test_push_refresh_is_session_scoped_and_cannot_resurrect_a_removed_device(self):
        await self.register()
        body={'id':'a'*32,'push_token':'native:'+('y'*100),'notifications_enabled':True}
        response=await self.client.post('/api/mobile/push',headers=self.headers,json=body)
        self.assertEqual(response.status,200)
        self.assertTrue((await response.json())['push_registered'])
        await self.client.delete('/api/mobile/devices/'+'a'*32,headers=self.headers)
        self.assertEqual((await self.client.post('/api/mobile/push',headers=self.headers,json=body)).status,403)
        response=await self.client.get('/api/mobile/devices')
        self.assertEqual((await response.json())['devices'],[])

    async def test_native_status_and_test_submission_do_not_claim_device_reception(self):
        await self.register()
        response=await self.client.get('/api/mobile/notifications')
        self.assertEqual(response.status,200)
        self.assertFalse((await response.json())['push_available'])
        response=await self.client.post('/api/mobile/notifications/test',headers=self.headers,json={'device_id':'a'*32})
        self.assertEqual(response.status,400)
        from mydesk.app import STATE
        queue=self.client.server.app[STATE]['native_notifications']
        class Sender:
            async def send(self,token,data):return 'projects/test/messages/test'
        queue.sender=Sender()
        await self.client.post('/api/mobile/push',headers=self.headers,
            json={'id':'a'*32,'push_token':'native:'+('z'*100),'notifications_enabled':True})
        response=await self.client.post('/api/mobile/notifications/test',headers=self.headers,json={'device_id':'a'*32})
        self.assertEqual(response.status,200)
        event=(await response.json())['event_id']
        response=await self.client.get('/api/mobile/notifications')
        delivery=(await response.json())['deliveries'][0]
        self.assertIsNone(delivery['submitted_at'])
        self.assertIsNone(delivery['received_at'])
        await queue.drain(datetime.now(timezone.utc))
        response=await self.client.get('/api/mobile/notifications')
        delivery=(await response.json())['deliveries'][0]
        self.assertIsNotNone(delivery['submitted_at'])
        self.assertIsNone(delivery['received_at'])
        response=await self.client.post('/api/mobile/notification-receipt',headers=self.headers,
            json={'device_id':'a'*32,'event_id':event,'phase':'display_requested'})
        self.assertEqual(response.status,200)
        response=await self.client.get('/api/mobile/notifications')
        self.assertIsNotNone((await response.json())['deliveries'][0]['display_requested_at'])

    async def test_native_test_request_queues_without_waiting_for_google_network(self):
        await self.register()
        from mydesk.app import STATE
        queue=self.client.server.app[STATE]['native_notifications']
        class Sender:
            async def send(self,token,data):await asyncio.sleep(1);return 'accepted'
        queue.sender=Sender()
        await self.client.post('/api/mobile/push',headers=self.headers,
            json={'id':'a'*32,'push_token':'native:'+('x'*100),'notifications_enabled':True})
        try:
            response=await asyncio.wait_for(self.client.post('/api/mobile/notifications/test',headers=self.headers,
                json={'device_id':'a'*32}),timeout=0.5)
        except TimeoutError:self.fail('测试接口必须先入队返回，不能等待 Google 网络')
        self.assertEqual(response.status,200)
        self.assertTrue((await response.json())['queued'])
