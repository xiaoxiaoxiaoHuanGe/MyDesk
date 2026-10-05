"""Independent application's public contract; no Home Assistant test runtime."""
import asyncio
import base64
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from aiohttp import CookieJar, ClientSession
from aiohttp.test_utils import TestClient, TestServer

try:
    from mydesk.app import create_app
except ImportError:
    create_app = None


class StandaloneTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.assertIsNotNone(create_app, 'Independent MyDesk server has not been implemented')
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)
        self.client = await self.start_client()
        self.access = json.loads((self.path/'local-access.json').read_text())

    async def start_client(self):
        app = await asyncio.to_thread(create_app,self.path,scheduling=False)
        client=TestClient(TestServer(app),cookie_jar=CookieJar(unsafe=True))
        await client.start_server()
        return client

    async def asyncTearDown(self):
        if hasattr(self, 'client'):
            await self.client.close()
        if hasattr(self, 'tmp'):
            self.tmp.cleanup()

    async def login(self, client=None):
        client = client or self.client
        response = await client.post('/api/login', json=self.access)
        self.assertEqual(response.status, 200)
        self.csrf = (await response.json())['csrf']
        return response

    async def command(self, action, payload=None):
        return await self.client.post('/api/command', headers={'X-MyDesk-CSRF':self.csrf},
                                      json={'action':action, 'payload':payload or {}})

    async def test_login_has_http_only_cookie_and_opaque_server_session(self):
        response = await self.login()
        cookie = response.cookies['mydesk_session']
        self.assertTrue(cookie['httponly'])
        self.assertEqual(cookie['samesite'], 'Strict')
        self.assertFalse((await response.json()).get('password'))
        stored = (self.path/'auth.sqlite3').read_bytes()
        self.assertNotIn(self.access['password'].encode(), stored)
        self.assertNotIn(cookie.value.encode(), stored)

    async def test_step_plan_commands_require_session_and_csrf(self):
        response=await self.client.post('/api/command',json={'action':'wxstep/plan/start','payload':{}})
        self.assertEqual(response.status,401)
        await self.login()
        response=await self.client.post('/api/command',json={'action':'wxstep/preset/save','payload':{}})
        self.assertEqual(response.status,403)

    async def test_step_preset_api_is_fill_only_and_revision_checked(self):
        await self.login()
        spec=dict(name='散步',start=1000,increment=30,interval_minutes=3,target=1090)
        response=await self.command('wxstep/preset/save',spec)
        self.assertEqual(response.status,200)
        saved=await response.json()
        self.assertEqual(saved['presets'][0]['name'],'散步')
        self.assertIsNone(saved['run'])
        response=await self.command('wxstep/preset/save',dict(**spec,revision='stale'))
        self.assertEqual(response.status,400)
        response=await self.command('wxstep/preset/save',{**spec,'increment':0})
        self.assertEqual(response.status,400)

    async def test_private_http_and_websocket_require_login(self):
        for path in ['/api/session','/api/settings','/api/notifications']:
            response = await self.client.get(path)
            self.assertEqual(response.status,401)
        response = await self.client.get('/api/ws')
        self.assertEqual(response.status,401)

    async def test_writable_bind_mount_without_chmod_support_can_start(self):
        with patch.object(Path,'chmod',side_effect=PermissionError('mount does not support chmod')):
            try:
                app=await asyncio.to_thread(create_app,self.path/'mounted',scheduling=False)
            except PermissionError:
                self.fail('Writable Docker Desktop mounts must not require POSIX chmod support')
        client=TestClient(TestServer(app),cookie_jar=CookieJar(unsafe=True))
        await client.start_server()
        try:
            self.assertEqual((await client.get('/health')).status,200)
        finally:
            await client.close()

    async def test_wrong_password_rate_limit_and_generic_errors(self):
        for _ in range(5):
            response = await self.client.post('/api/login',json={'username':'mydesk','password':'wrong'})
            self.assertEqual(response.status,401)
            self.assertEqual((await response.json())['error'],'账号或密码不正确')
        response = await self.client.post('/api/login',json=self.access)
        self.assertEqual(response.status,429)

    async def test_csrf_and_cross_origin_mutations_are_rejected(self):
        await self.login()
        data={'action':'reminder/create','payload':{'title':'禁止创建','time':'30分钟后'}}
        response=await self.client.post('/api/command',json=data)
        self.assertEqual(response.status,403)
        response=await self.client.post('/api/command',json=data,
            headers={'X-MyDesk-CSRF':self.csrf,'Origin':'https://untrusted.example'})
        self.assertEqual(response.status,403)
        response=await self.client.get('/api/ws',headers={'Origin':'https://untrusted.example'})
        self.assertEqual(response.status,403)

    async def test_forwarded_https_is_accepted_only_from_explicit_trusted_proxy(self):
        from mydesk.app import STATE
        origin=str(self.client.make_url('/').origin()).replace('http:','https:')
        headers={'X-Forwarded-Proto':'https','Origin':origin}
        response=await self.client.post('/api/login',json=self.access,headers=headers)
        self.assertEqual(response.status,403)
        self.client.server.app[STATE]['trusted_proxies'].add('127.0.0.1')
        response=await self.client.post('/api/login',json=self.access,headers=headers)
        self.assertEqual(response.status,200)
        self.assertTrue(response.cookies['mydesk_session']['secure'])

    async def test_websocket_initial_snapshot_and_reminder_changes(self):
        await self.login()
        socket=await self.client.ws_connect('/api/ws')
        first=await socket.receive_json(timeout=3)
        self.assertEqual(first['type'],'snapshot')
        response=await self.command('reminder/create',{'title':'独立版提醒','time':'30分钟后'})
        reminder=await response.json()
        self.assertEqual(response.status,200)
        changed=await socket.receive_json(timeout=3)
        self.assertEqual(changed['state']['reminders'][0]['id'],reminder['id'])
        await socket.close()

    async def test_restart_keeps_account_session_settings_and_reminders(self):
        await self.login()
        token=self.client.session.cookie_jar.filter_cookies(self.client.make_url('/'))['mydesk_session'].value
        response=await self.command('reminder/create',{'title':'重启保留','time':'30分钟后'})
        reminder=await response.json()
        response=await self.client.put('/api/settings',json={'timezone':'Asia/Shanghai','history_days':30},
                                        headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        await self.client.close()
        self.client=await self.start_client()
        self.client.session.cookie_jar.update_cookies({'mydesk_session':token}, self.client.make_url('/'))
        response=await self.client.get('/api/session')
        self.assertEqual(response.status,200)
        response=await self.command('snapshot')
        self.assertEqual((await response.json())['reminders'][0]['id'],reminder['id'])
        response=await self.client.get('/api/settings')
        self.assertEqual((await response.json())['history_days'],30)
        self.assertEqual(json.loads((self.path/'local-access.json').read_text()),self.access)

    async def test_logout_revokes_cookie_and_open_socket(self):
        await self.login()
        socket=await self.client.ws_connect('/api/ws')
        await socket.receive_json(timeout=3)
        response=await self.client.post('/api/logout',headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        message=await socket.receive(timeout=3)
        self.assertIn(message.type.name,('CLOSE','CLOSED','CLOSING'))
        self.assertEqual((await self.client.get('/api/session')).status,401)

    async def test_logout_and_login_keep_independent_integration_credentials(self):
        from mydesk.github_checkins import SOURCES
        await self.login()
        configuration={
            'github':{'owner':'owner','repo':'steps','workflow':'steps.yml','ref':'main','token':'steps-private-token'},
            'github_tasks':{key:{**spec,'owner':'example-user','adapter':key,'max_age_hours':36,'enabled':False,'token':key+'-private-token'} for key,spec in SOURCES.items()}}
        response=await self.client.put('/api/settings',json=configuration,headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        before=(self.path/'settings.json').read_bytes()
        response=await self.client.post('/api/logout',headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        self.assertEqual((await self.client.get('/api/settings')).status,401)
        await self.login()
        response=await self.client.get('/api/settings')
        self.assertEqual(response.status,200)
        public=await response.json()
        self.assertTrue(public['github']['credential_set'])
        for key in SOURCES:
            self.assertTrue(public['github_tasks'][key]['credential_set'])
            self.assertEqual(public['github_tasks'][key]['repo'],configuration['github_tasks'][key]['repo'])
        self.assertNotIn('private-token',json.dumps(public))
        self.assertEqual((self.path/'settings.json').read_bytes(),before)

    async def test_change_password_revokes_old_sessions(self):
        await self.login()
        response=await self.client.post('/api/password',headers={'X-MyDesk-CSRF':self.csrf},
            json={'current_password':self.access['password'],'new_password':'independent-test-password-2026'})
        self.assertEqual(response.status,200)
        self.assertEqual((await self.client.get('/api/session')).status,401)
        self.access['password']='independent-test-password-2026'
        await self.login()

    async def test_settings_never_return_secrets_and_empty_password_preserves_existing(self):
        await self.login()
        settings={'github':{'owner':'owner','repo':'repo','workflow':'wxstep.yml','ref':'main','token':'private-github-token'},
                  'gmail':{'username':'test@gmail.com','password':'private-app-password'},
                  'beszel':{'url':'http://localhost:8090','email':'test@example.com','password':'private-beszel-password'}}
        response=await self.client.put('/api/settings',json=settings,headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        text=await response.text()
        self.assertNotIn('private-',text)
        response=await self.client.put('/api/settings',json={'github':{**settings['github'],'token':''}},
                                      headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        response=await self.client.get('/api/settings')
        self.assertTrue((await response.json())['github']['credential_set'])
        response=await self.command('snapshot')
        self.assertNotIn('private-',await response.text())

    async def test_settings_reject_invalid_timezone_retention_and_node(self):
        await self.login()
        for settings in [{'timezone':'fake/timezone'},{'history_days':0},
                         {'network':{'enabled':True,'nodes':[{'name':'bad','host':'-c 100'}]}}]:
            response=await self.client.put('/api/settings',json=settings,headers={'X-MyDesk-CSRF':self.csrf})
            self.assertEqual(response.status,400)

    async def test_disabling_source_removes_its_old_integration_error(self):
        from mydesk.app import STATE
        await self.login()
        rt=self.client.server.app[STATE]['runtime']
        rt.desk.set_feed('network',{'error':'旧网络错误'},datetime.now(timezone.utc))
        self.assertTrue((await rt.snapshot())['attention'])
        response=await self.client.put('/api/settings',json={'network':{'enabled':False,'nodes':[]}},
                                      headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,200)
        self.assertEqual((await rt.snapshot())['attention'],[])

    async def test_active_step_run_cannot_be_switched_to_a_different_repository(self):
        from mydesk.app import STATE
        await self.login()
        github={'owner':'owner','repo':'first','workflow':'wxstep.yml','ref':'main','token':'private-token'}
        await self.client.put('/api/settings',json={'github':github},headers={'X-MyDesk-CSRF':self.csrf})
        rt=self.client.server.app[STATE]['runtime']
        job=rt.desk.create_job(18888,datetime.now(timezone.utc))
        rt.desk.update_job(job['id'],status='queued',run_id=123)
        response=await self.client.put('/api/settings',json={'github':{**github,'repo':'different'}},
                                      headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,409)

    async def test_webhook_secret_reports_without_browser_session(self):
        await self.login()
        response=await self.client.get('/api/webhook-info')
        path=(await response.json())['path']
        await self.client.post('/api/logout',headers={'X-MyDesk-CSRF':self.csrf})
        payload={'task_id':'glados','task_name':'GLaDOS','status':'failed','message':'登录失效',
                 'timestamp':datetime.now(timezone.utc).isoformat(),'event_id':'local-test-1','source':'github_actions'}
        self.assertEqual((await self.client.post('/api/webhook/invalid',json=payload)).status,404)
        response=await self.client.post(path,json=payload)
        self.assertEqual(response.status,200)
        await self.login()
        response=await self.command('snapshot')
        state=await response.json()
        self.assertEqual(state['tasks'][0]['status'],'failed')
        self.assertEqual(state['attention'][0]['kind'],'task')

    async def test_legacy_browser_registration_and_actions_are_retired(self):
        await self.login()
        headers={'X-MyDesk-CSRF':self.csrf}
        response=await self.client.post('/api/notifications',json={'subscription':{},'name':'旧浏览器'},headers=headers)
        self.assertEqual(response.status,410)
        response=await self.client.post('/api/notification-action',json={'token':'old','action':'complete'})
        self.assertEqual(response.status,410)
        self.assertEqual((await self.client.get('/api/notifications')).status,410)
        self.assertEqual((await self.command('snapshot')).status,200)

    async def test_native_local_alarm_device_is_visible_in_workbench_configuration(self):
        await self.login()
        await self.client.post('/api/mobile/devices',json={'id':'a'*32,'name':'MyDesk App','local_alarm':True},headers={'X-MyDesk-CSRF':self.csrf})
        response=await self.command('snapshot')
        self.assertTrue((await response.json())['configured']['notifications'])
        await self.client.delete('/api/mobile/devices/'+'a'*32,headers={'X-MyDesk-CSRF':self.csrf})
        response=await self.command('snapshot')
        self.assertFalse((await response.json())['configured']['notifications'])

    async def test_oversized_and_malformed_requests_have_safe_errors(self):
        await self.login()
        response=await self.client.post('/api/command',data='not-json',headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,400)
        response=await self.client.post('/api/command',data='x'*70000,headers={'X-MyDesk-CSRF':self.csrf})
        self.assertEqual(response.status,413)
        response=await self.client.get('/health')
        self.assertEqual(await response.json(),{'status':'ok','application':'MyDesk'})

    async def test_static_application_has_no_ha_or_cached_personal_data(self):
        response=await self.client.get('/')
        page=await response.text()
        self.assertIn('MyDesk',page)
        self.assertNotIn('Home Assistant',page)
        response=await self.client.get('/sw.js')
        script=await response.text()
        self.assertIn('unsubscribe',script)
        self.assertNotIn('showNotification',script)
        self.assertNotIn('caches.open',script)
        response=await self.client.get('/manifest.webmanifest')
        self.assertEqual((await response.json())['name'],'MyDesk')
