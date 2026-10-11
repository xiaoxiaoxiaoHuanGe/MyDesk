"""Login contracts use disposable accounts, browsers and databases."""
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlparse, parse_qs

from aiohttp import CookieJar, ClientSession
from aiohttp.test_utils import TestClient, TestServer
from mydesk.app import create_app, STATE


class QrLoginTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)
        self.app = create_app(self.path, scheduling=False)
        self.server = TestServer(self.app)
        self.browser = TestClient(self.server, cookie_jar=CookieJar(unsafe=True))
        await self.browser.start_server()
        self.phone = TestClient(self.server, cookie_jar=CookieJar(unsafe=True))
        await self.phone.start_server()
        self.access = json.loads((self.path/'local-access.json').read_text())

    async def asyncTearDown(self):
        await self.phone.close()
        await self.browser.close()
        self.tmp.cleanup()

    async def login_phone(self):
        response = await self.phone.post('/api/login', json={**self.access, 'client':'android'})
        self.assertEqual(response.status, 200)
        self.headers = {'X-MyDesk-CSRF':(await response.json())['csrf']}
        self.token = response.cookies['mydesk_session'].value
        response = await self.phone.post('/api/mobile/devices', headers=self.headers,
            json={'id':'a'*32, 'name':'测试手机', 'local_alarm':True})
        self.assertEqual(response.status, 200)

    async def start_qr(self):
        response = await self.browser.post('/api/qr-login', headers={'User-Agent':'Mozilla/5.0 (Windows NT 10.0) Chrome/130.0'})
        self.assertEqual(response.status, 200)
        data = await response.json()
        self.assertTrue(data['image'].startswith('data:image/svg+xml;base64,'))
        parts = parse_qs(urlparse(data['uri']).query)
        self.scan = {'id':parts['id'][0], 'token':parts['token'][0]}
        return data

    async def preview(self):
        return await self.phone.post('/api/qr-login/preview', headers=self.headers, json=self.scan)

    async def confirm(self, approve=True):
        return await self.phone.post('/api/qr-login/confirm', headers=self.headers,
            json={**self.scan, 'approve':approve})

    async def poll(self):
        return await self.browser.get('/api/qr-login/'+self.scan['id'])

    async def test_app_session_has_no_time_expiry_and_web_still_expires(self):
        await self.login_phone()
        auth = self.app[STATE]['auth']
        self.assertIsNone(auth.session(self.token)['expires'])
        response = await self.browser.post('/api/login', json=self.access)
        web_token = response.cookies['mydesk_session'].value
        with patch('mydesk.auth.time.time', return_value=4102444800):
            self.assertIsNotNone(auth.session(self.token))
            self.assertIsNone(auth.session(web_token))

    async def test_legacy_app_session_becomes_permanent_when_registering(self):
        response = await self.phone.post('/api/login', json=self.access)
        token = response.cookies['mydesk_session'].value
        headers = {'X-MyDesk-CSRF':(await response.json())['csrf']}
        response = await self.phone.post('/api/mobile/devices', headers=headers,
            json={'id':'b'*32, 'name':'旧版手机'})
        self.assertEqual(response.status, 200)
        self.assertIsNone(self.app[STATE]['auth'].session(token)['expires'])

    async def test_updated_app_promotes_existing_session_on_first_authenticated_sync(self):
        response=await self.phone.post('/api/login',json=self.access)
        token=response.cookies['mydesk_session'].value
        self.assertIsNotNone(self.app[STATE]['auth'].session(token)['expires'])
        response=await self.phone.get('/api/mobile/devices',headers={'X-MyDesk-Client':'android'})
        self.assertEqual(response.status,200)
        self.assertIsNone(self.app[STATE]['auth'].session(token)['expires'])

    async def test_permanent_session_survives_backend_restart(self):
        await self.login_phone()
        from mydesk.auth import Auth
        reopened=Auth(self.path/'auth.sqlite3')
        with patch('mydesk.auth.time.time',return_value=4102444800):
            self.assertIsNotNone(reopened.session(self.token))
            self.assertTrue(reopened.session_active(reopened.session(self.token)['id']))

    async def test_qr_requires_preview_and_explicit_confirmation_then_only_browser_can_redeem(self):
        await self.login_phone()
        await self.start_qr()
        self.assertEqual((await (await self.poll()).json())['status'], 'waiting')
        self.assertEqual((await self.confirm()).status, 409)
        response = await self.preview()
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())['browser'], 'Windows · Chrome')
        self.assertEqual((await (await self.poll()).json())['status'], 'scanned')
        self.assertEqual((await self.browser.get('/api/session')).status, 401)
        self.assertEqual((await self.confirm()).status, 200)
        other = ClientSession(cookie_jar=CookieJar(unsafe=True))
        try:
            self.assertEqual((await other.get(self.browser.make_url('/api/qr-login/'+self.scan['id']))).status, 403)
        finally:
            await other.close()
        result = await self.poll()
        self.assertEqual((await result.json())['status'], 'approved')
        self.assertEqual(result.cookies['mydesk_session']['max-age'], str(7*86400))
        self.assertEqual((await self.browser.get('/api/session')).status, 200)
        self.assertEqual((await self.confirm()).status, 400)

    async def test_rejected_expired_and_wrong_scan_tokens_cannot_login(self):
        await self.login_phone()
        await self.start_qr()
        response = await self.phone.post('/api/qr-login/preview', headers=self.headers,
            json={**self.scan, 'token':'wrong'})
        self.assertEqual(response.status, 400)
        self.assertEqual((await self.preview()).status, 200)
        self.assertEqual((await self.confirm(False)).status, 200)
        self.assertEqual((await (await self.poll()).json())['status'], 'rejected')
        self.assertEqual((await self.browser.get('/api/session')).status, 401)
        await self.start_qr()
        with self.app[STATE]['auth'].connect() as db:
            db.execute('UPDATE qr_logins SET expires=0')
        self.assertEqual((await self.preview()).status, 400)
        self.assertEqual((await (await self.poll()).json())['status'], 'expired')

    async def test_preview_requires_app_session_and_csrf(self):
        await self.start_qr()
        self.assertEqual((await self.phone.post('/api/qr-login/preview', json=self.scan)).status, 401)
        await self.login_phone()
        self.assertEqual((await self.phone.post('/api/qr-login/preview', json=self.scan)).status, 403)
        response = await self.browser.post('/api/login', json=self.access)
        csrf = (await response.json())['csrf']
        self.assertEqual((await self.browser.post('/api/qr-login/preview',
            headers={'X-MyDesk-CSRF':csrf}, json=self.scan)).status, 403)

    async def test_logout_and_device_revocation_invalidate_app_and_pending_approval(self):
        await self.login_phone()
        await self.start_qr()
        await self.preview()
        await self.confirm()
        await self.phone.post('/api/logout', headers=self.headers)
        self.assertIsNone(self.app[STATE]['auth'].session(self.token))
        self.assertEqual((await (await self.poll()).json())['status'], 'expired')
        await self.login_phone()
        response = await self.browser.post('/api/login', json=self.access)
        csrf = (await response.json())['csrf']
        response = await self.browser.delete('/api/mobile/devices/'+'a'*32, headers={'X-MyDesk-CSRF':csrf})
        self.assertEqual(response.status, 200)
        self.assertIsNone(self.app[STATE]['auth'].session(self.token))

    async def test_password_change_revokes_permanent_session_and_pending_qr(self):
        await self.login_phone()
        await self.start_qr()
        await self.preview()
        await self.confirm()
        response = await self.phone.post('/api/password', headers=self.headers,
            json={'current_password':self.access['password'], 'new_password':'new-test-password-123'})
        self.assertEqual(response.status, 200)
        self.assertIsNone(self.app[STATE]['auth'].session(self.token))
        self.assertEqual((await (await self.poll()).json())['status'], 'expired')

    async def test_qr_origin_guard_and_creation_rate_limit(self):
        response = await self.browser.post('/api/qr-login', headers={'Origin':'https://other.example'})
        self.assertEqual(response.status, 403)
        for _ in range(10):
            self.assertEqual((await self.browser.post('/api/qr-login')).status, 200)
        self.assertEqual((await self.browser.post('/api/qr-login')).status, 429)

    async def test_simultaneous_browser_poll_consumes_approval_once(self):
        await self.login_phone()
        await self.start_qr()
        await self.preview()
        await self.confirm()
        replies = await asyncio.gather(self.poll(), self.poll())
        self.assertEqual(sum('mydesk_session' in response.cookies for response in replies), 1)
