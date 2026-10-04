import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone
from unittest.mock import patch

from aiohttp import ClientSession, web
from mydesk.config import Settings
from mydesk.domain import Desk
from mydesk.runtime import Runtime

NOW=datetime(2026,10,3,8,tzinfo=timezone.utc)

def mailbox(name):
    return dict(name=name,username=name+'@gmail.com',password=name+'-secret',limit=5,enabled=True)

def panel(name,url):
    return dict(name=name,provider='1panel',url=url,api_key=name+'-secret',signature='md5',enabled=True)

class MultiServiceSettingsTests(unittest.TestCase):
    def test_independent_secrets_blank_preservation_removal_and_legacy_conversion(self):
        with tempfile.TemporaryDirectory() as directory:
            s=Settings(Path(directory)/'settings.json')
            s.update({'gmail':dict(username='old@gmail.com',password='old-secret')})
            self.assertTrue(s.public()['gmail_accounts']['legacy']['credential_set'])
            accounts=s.public()['gmail_accounts']
            accounts['second']=mailbox('second')
            servers={'one':panel('one','https://one.example.com'),'two':panel('two','https://two.example.com')}
            public=s.update({'gmail_accounts':accounts,'server_sources':servers})
            self.assertNotIn('secret',json.dumps(public))
            public['gmail_accounts']['second']['name']='私人邮箱'
            public['server_sources']['one']['name']='东京服务器'
            s.update({'gmail_accounts':public['gmail_accounts'],'server_sources':public['server_sources']})
            self.assertEqual(s.value['gmail_accounts']['legacy']['password'],'old-secret')
            self.assertEqual(s.value['gmail_accounts']['second']['password'],'second-secret')
            self.assertEqual(s.value['server_sources']['one']['api_key'],'one-secret')
            del public['server_sources']['one']
            s.update({'server_sources':public['server_sources']})
            self.assertEqual(set(s.value['server_sources']),{'two'})
            self.assertNotIn('gmail',s.value)
            self.assertEqual(Settings(s.path).public(),s.public())

    def test_invalid_duplicate_accounts_and_rebinding_without_new_secret_are_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            s=Settings(Path(directory)/'settings.json')
            s.update({'gmail_accounts':{'one':mailbox('one')},'server_sources':{'one':panel('one','https://one.example.com')}})
            before=s.path.read_bytes()
            invalid=[{'gmail_accounts':{'one':mailbox('one'),'two':mailbox('one')}},
                     {'server_sources':{'one':panel('one','https://one.example.com'),'two':panel('two','https://one.example.com/')}},
                     {'server_sources':{'one':panel('one','https://user:password@example.com')}},
                     {'server_sources':{'one':{**s.public()['server_sources']['one'],'url':'https://other.example.com'}}},
                     {'gmail_accounts':{'one':{**s.public()['gmail_accounts']['one'],'username':'other@gmail.com'}}}]
            for value in invalid:
                with self.assertRaises(ValueError):s.update(value)
                self.assertEqual(s.path.read_bytes(),before)

class MultiServiceRuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def test_mailbox_failure_isolated_ids_namespaced_and_unread_not_falsified(self):
        with tempfile.TemporaryDirectory() as directory:
            async with ClientSession() as session:
                config={'network':{'enabled':False},'gmail_accounts':{'one':mailbox('one'),'two':mailbox('two')}}
                rt=Runtime(Desk(Path(directory)/'desk.db'),config,session,None)
                def fake_poll(spec):
                    if spec['username']=='two@gmail.com':raise ValueError('two-secret private failure')
                    return {'unread':3,'items':[dict(id='7',subject='标题',sender='发件人',received_at=NOW.isoformat(),unread=True)]}
                with patch('mydesk.runtime.poll_mail',fake_poll):
                    await rt.refresh(NOW)
                    self.assertTrue((await rt.command('service/check',{'kind':'mail','id':'one'},NOW))['connected'])
                    with self.assertRaisesRegex(ValueError,'Gmail'):
                        await rt.command('service/check',{'kind':'mail','id':'two'},NOW)
                state=await rt.snapshot(NOW)
                data=state['feeds']['mail']['data']
                self.assertEqual(len(data['accounts']),2)
                self.assertEqual(data['unread'],3)
                self.assertEqual(data['items'][0]['id'],'one:7')
                self.assertEqual(data['items'][0]['account_name'],'one')
                self.assertIsNone(data['accounts'][1]['unread'])
                self.assertTrue(any(item['id']=='mail.two' for item in state['attention']))
                self.assertNotIn('secret',str(state))

    async def test_two_panels_signed_read_only_metrics_and_auth_error_stays_unknown(self):
        requests=[]
        async def respond(request):
            requests.append((request.method,request.path,dict(request.headers)))
            if request.path.startswith('/two/'):
                return web.json_response({'code':401,'message':'two-secret'},status=200)
            return web.json_response({'code':200,'data':dict(cpuUsedPercent=18,memoryUsedPercent=42,
                diskData=[dict(path='/',total=100,used=61,usedPercent=61)],load1=.1,load5=.2,load15=.3,uptime=3600,netBytesSent=1572864,netBytesRecv=2415919104)})
        app=web.Application();app.router.add_route('*','/{tail:.*}',respond)
        runner=web.AppRunner(app);await runner.setup()
        site=web.TCPSite(runner,'127.0.0.1',0);await site.start()
        base='http://127.0.0.1:'+str(site._server.sockets[0].getsockname()[1])
        try:
            with tempfile.TemporaryDirectory() as directory:
                async with ClientSession() as session:
                    rt=Runtime(Desk(Path(directory)/'desk.db'),{'network':{'enabled':False},'server_sources':{
                        'one':panel('one',base+'/one'),'two':panel('two',base+'/two')}},session,None)
                    await rt.refresh(NOW)
                    state=await rt.snapshot(NOW)
                    items=state['feeds']['servers']['data']['items']
                    self.assertEqual([i['id'] for i in items],['one','two'])
                    self.assertEqual(items[0]['cpu'],18)
                    self.assertEqual(items[0]['disk'],61)
                    self.assertEqual(items[0]['network'],{'sent_bytes':1572864,'received_bytes':2415919104})
                    self.assertEqual(items[0]['status'],'up')
                    self.assertEqual(items[1]['status'],'unknown')
                    self.assertIsNone(items[1]['cpu'])
                    self.assertNotIn('secret',str(state))
                    self.assertTrue(state['configured']['servers'])
                    self.assertTrue(any(item['id']=='two' for item in state['attention']))
            self.assertEqual(len(requests),2)
            for method,path,headers in requests:
                self.assertEqual(method,'GET')
                self.assertEqual(headers['CurrentNode'],'local')
                self.assertTrue(path.endswith('/api/v2/dashboard/current/all/all'))
                key='one-secret' if path.startswith('/one/') else 'two-secret'
                signature=hashlib.md5(('1panel'+key+headers['1Panel-Timestamp']).encode()).hexdigest()
                self.assertEqual(headers['1Panel-Token'],signature)
        finally:await runner.cleanup()
