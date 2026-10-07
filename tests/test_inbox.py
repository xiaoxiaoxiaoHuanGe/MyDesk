import asyncio
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone,timedelta
from pathlib import Path
from aiohttp.test_utils import TestClient,TestServer
from mydesk.app import create_app,STATE
from mydesk.domain import Desk
from mydesk.inbox import Inbox,IncomingError

NOW=datetime.now(timezone.utc)
TEXT='📱 短信通知\n发送方: CPE 测试发送方\n内容: 【示例】验证码 "123456"，这是测试短信。\n时间: 2026-10-07 20:00:00 +08:00'
PAYLOAD={'msg_type':'text','content':{'text':TEXT},'timestamp':'1791374400','sign':'sender-sign'}

class InboxTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.desk=Desk(Path(self.tmp.name)/'db');self.inbox=Inbox(self.desk)
        self.source=self.inbox.create({'name':'CPE 短信','kind':'cpe'})
        self.secret=self.source['receive_path'].split('/')[-1]
    def receive(self,key='a'*64,body=PAYLOAD,now=NOW,source=None,secret=None):
        return self.inbox.receive(source or self.source['id'],secret or self.secret,body,key,now)
    def test_concurrent_retries_commit_one_message_and_dispatch(self):
        with ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(lambda _:self.receive(),range(20)))
        self.assertEqual(sum(results),1)
        self.assertFalse(self.receive(body={'msg_type':'text','content':{'text':'changed'}}))
        self.assertEqual(self.inbox.page()['items'][0]['body'],TEXT)
        with self.desk.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM inbox_dispatch').fetchone()[0],1)
        restarted=Inbox(self.desk);self.assertEqual(restarted.page()['items'][0]['body'],TEXT)
    def test_auth_rotation_disable_and_soft_delete(self):
        self.receive();old=self.secret
        self.secret=self.inbox.change(self.source['id'],action='rotate')['receive_path'].split('/')[-1]
        with self.assertRaises(IncomingError):self.receive(secret=old)
        self.inbox.change(self.source['id'],{'enabled':False})
        with self.assertRaises(IncomingError):self.receive()
        self.inbox.change(self.source['id'],action='delete',now=NOW)
        self.assertEqual(self.inbox.sources(),[]);self.assertEqual(len(self.inbox.page()['items']),1)
    def test_strict_formats_and_cpe_id(self):
        for key in [None,'A'*64,'a'*63,'a'*65,'bad']:
            with self.assertRaises(IncomingError):self.receive(key)
        for body in [[],{}, {'msg_type':'post','content':{'text':'x'}},{'msg_type':'text','content':{'text':True}}, {'msg_type':'text','content':{'text':'x','image':'x'}}]:
            with self.assertRaises(IncomingError):self.receive(body=body)
    def test_source_scoping_general_without_id_and_read_cutoff(self):
        self.receive();other=self.inbox.create({'name':'other','kind':'text'})
        self.receive(source=other['id'],secret=other['receive_path'].split('/')[-1]);self.receive(None,source=other['id'],secret=other['receive_path'].split('/')[-1]);self.receive(None,source=other['id'],secret=other['receive_path'].split('/')[-1])
        cutoff=self.inbox.snapshot(NOW)['latest_seq'];self.receive('b'*64)
        self.inbox.read({'through_seq':cutoff},NOW);self.inbox.read({'through_seq':cutoff},NOW)
        self.assertEqual(self.inbox.snapshot(NOW)['unread'],1)
        self.assertEqual(len(self.inbox.page(source_id=other['id'])['items']),3)
        first=self.inbox.page(2);second=self.inbox.page(2,first['next_cursor'])
        self.assertFalse({x['id'] for x in first['items']} & {x['id'] for x in second['items']})
    def test_rate_limit_and_retention(self):
        for i in range(60):self.receive(f'{i:064x}')
        with self.assertRaises(IncomingError) as caught:self.receive('f'*64)
        self.assertEqual(caught.exception.status,429)
        self.receive('f'*64,now=NOW+timedelta(minutes=2))
        self.assertEqual(self.inbox.page(now=NOW+timedelta(days=91))['items'],[])
        self.assertEqual(len(self.inbox.sources()),1)
    def test_pending_dispatch_is_not_limited_to_snapshot(self):
        from mydesk.mobile import Mobile
        from mydesk.native_notifications import NativeNotifications
        native=NativeNotifications(Mobile(self.desk),None,lambda _:True)
        for i in range(30):self.receive(f'{i:064x}')
        native.capture(self.inbox.snapshot(NOW),NOW);native.capture_inbox(NOW)
        with self.desk.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM native_events WHERE kind='inbox'").fetchone()[0],30)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM inbox_dispatch WHERE event_id IS NULL').fetchone()[0],0)

class InboxApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.app=create_app(self.root,scheduling=False);self.client=TestClient(TestServer(self.app));await self.client.start_server()
        access=json.loads((self.root/'local-access.json').read_text());r=await self.client.post('/api/login',json=access)
        self.headers={'X-MyDesk-CSRF':(await r.json())['csrf']}
        r=await self.client.post('/api/notification-sources',headers=self.headers,json={'name':'CPE','kind':'cpe'});self.source=await r.json()
    async def asyncTearDown(self):await self.client.close();self.tmp.cleanup()
    async def test_exact_uploaded_sender_protocol_sample(self):
        import re
        protocol=(Path(__file__).resolve().parents[1]/'docs/CPE_WEBHOOK_PROTOCOL_REFERENCE.md').read_text(encoding='utf-8')
        sample=json.loads(re.search(r'```json\n(.*?)\n```',protocol,re.S)[1])
        headers={'Idempotency-Key':'0123456789abcdef'*4,'Content-Type':'application/json; charset=utf-8'}
        response=await self.client.post(self.source['receive_path'],json=sample,headers=headers)
        self.assertEqual(response.status,200)
        ack=await response.json();self.assertIs(type(ack['code']),int);self.assertEqual(ack,{'code':0,'msg':'success'})
        # Simulate loss of this committed ACK: retry the identical sender record.
        response=await self.client.post(self.source['receive_path'],json=sample,headers=headers)
        self.assertEqual(response.status,200);self.assertEqual(await response.json(),ack)
        inbox=self.app[STATE]['runtime'].inbox
        self.assertEqual(inbox.page()['items'][0]['body'],sample['content']['text'])
        self.assertEqual(len(inbox.page()['items']),1)
        with inbox.desk.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM inbox_dispatch').fetchone()[0],1)

    async def test_sender_ack_auth_csrf_and_size(self):
        r=await self.client.post('/api/notification-sources',json={'name':'x'});self.assertEqual(r.status,403)
        await self.client.post('/api/logout',headers=self.headers)
        r=await self.client.get('/api/inbox');self.assertEqual(r.status,401)
        for _ in range(2):
            r=await self.client.post(self.source['receive_path'],json=PAYLOAD,headers={'Idempotency-Key':'a'*64})
            self.assertEqual(r.status,200);value=await r.json();self.assertEqual(value,{'code':0,'msg':'success'});self.assertIs(type(value['code']),int)
        r=await self.client.post(self.source['receive_path'],json=PAYLOAD);self.assertEqual(r.status,400)
        r=await self.client.post(self.source['receive_path'],data='x'*65537);self.assertEqual(r.status,413);self.assertEqual((await r.json())['code'],413)
        bad=self.source['receive_path'][:-64]+'b'*64
        r=await self.client.post(bad,json=PAYLOAD,headers={'Idempotency-Key':'a'*64});self.assertEqual(r.status,401)
        self.assertEqual(len(self.app[STATE]['runtime'].inbox.page()['items']),1)

class PushTextBoundsTests(unittest.TestCase):
    def test_emoji_summary_fits_android_utf16_whitelist(self):
        from mydesk.native_notifications import push_text
        text=push_text('📱'*500,500)
        self.assertEqual(len(text.encode('utf-16-le'))//2,500)
        self.assertEqual(text,'📱'*250)

class InboxNativeRetryTests(unittest.IsolatedAsyncioTestCase):
    async def test_failed_fcm_keeps_message_and_restart_reuses_the_event(self):
        from mydesk.mobile import Mobile
        from mydesk.native_notifications import NativeNotifications
        from mydesk.fcm import PushRetry
        with tempfile.TemporaryDirectory() as directory:
            desk=Desk(Path(directory)/'db');inbox=Inbox(desk);source=inbox.create({'name':'CPE'})
            mobile=Mobile(desk);mobile.register({'id':'a'*32,'name':'test','push_token':'test:'+('x'*40),'notifications_enabled':True},'session')
            calls=[]
            class Sender:
                async def send(self,token,data):calls.append(data);raise PushRetry(retry_after=60)
            inbox.receive(source['id'],source['receive_path'].split('/')[-1],PAYLOAD,'a'*64,NOW)
            native=NativeNotifications(mobile,Sender(),lambda _:True);await native.drain(NOW)
            self.assertEqual(len(inbox.page()['items']),1)
            self.assertEqual(calls[0]['kind'],'inbox');self.assertEqual(len(calls[0]['event_id']),32)
            native=NativeNotifications(mobile,Sender(),lambda _:True);await native.drain(NOW+timedelta(seconds=61))
            self.assertEqual(len(calls),2);self.assertEqual(calls[0]['event_id'],calls[1]['event_id'])
            self.assertFalse(inbox.receive(source['id'],source['receive_path'].split('/')[-1],PAYLOAD,'a'*64,NOW))
            with desk.connect() as db:self.assertEqual(db.execute("SELECT COUNT(*) FROM native_events WHERE kind='inbox'").fetchone()[0],1)
