import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from datetime import datetime,timedelta,timezone
from aiohttp import ClientSession, web

from mydesk.providers import GitHub, Beszel, parse_mail, run_state, notification_data
from mydesk.domain import Desk
from mydesk.runtime import Runtime


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.requests = []
        self.responses = {}
        async def respond(request):
            body = await request.json() if request.can_read_body else None
            self.requests.append((request.path, body, request.headers.get('Authorization')))
            status, data = self.responses.get(request.path, (404, {}))
            return web.json_response(data, status=status)
        app = web.Application()
        app.router.add_route('*', '/{tail:.*}', respond)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.url = f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}'
        self.session = ClientSession()

    async def asyncTearDown(self):
        await self.session.close()
        await self.runner.cleanup()

    async def test_github_dispatch_returns_exact_run_and_secret_stays_in_header(self):
        self.responses['/repos/owner/repo/actions/workflows/wx.yml/dispatches'] = (200, {'workflow_run_id': 123, 'html_url': 'https://github.com/owner/repo/actions/runs/123'})
        client = GitHub(self.session, dict(owner='owner', repo='repo', workflow='wx.yml', ref='main', token='secret'), self.url)
        result = await client.dispatch(18888)
        self.assertEqual(result['run_id'], 123)
        path, body, auth = self.requests[0]
        self.assertEqual(body, {'ref': 'main', 'inputs': {'steps': '18888'}, 'return_run_details': True})
        self.assertEqual(auth, 'Bearer secret')
        self.assertNotIn('secret', str(result))

    async def test_github_ambiguous_204_does_not_pick_an_unrelated_run(self):
        self.responses['/repos/a/b/actions/workflows/w.yml/dispatches'] = (204, {})
        client = GitHub(self.session, dict(owner='a', repo='b', workflow='w.yml', token='secret', ref='main'), self.url)
        with self.assertRaisesRegex(ValueError, 'Run ID'):
            await client.dispatch(1)

    async def test_github_http_errors_do_not_expose_response_secrets(self):
        self.responses['/repos/a/b/actions/runs/1'] = (403, {'message': 'secret token leak'})
        client = GitHub(self.session, dict(owner='a', repo='b', workflow='w.yml', token='secret'), self.url)
        with self.assertRaises(ValueError) as context:
            await client.run(1)
        self.assertNotIn('secret', str(context.exception))

    async def test_beszel_regular_user_auth_pagination_and_metrics(self):
        self.responses['/api/collections/users/auth-with-password'] = (200, {'token': 'beszel-token'})
        self.responses['/api/collections/systems/records'] = (200, {'totalPages': 1, 'items': [{'id':'jp', 'name':'JP', 'status':'up', 'info': {'cpu':18, 'mp':42, 'dp':61, 'la':[0.1,0.2,0.3]}}]})
        client = Beszel(self.session, dict(url=self.url, email='me@example.com', password='password'))
        result = await client.poll()
        self.assertEqual(result['items'][0]['cpu'], 18)
        self.assertEqual(result['items'][0]['status'], 'up')
        self.assertEqual(self.requests[-1][2], 'beszel-token')

    async def test_wxstep_custom_runtime_flow_recovers_exact_run_after_restart(self):
        now=datetime(2026,10,1,8,tzinfo=timezone.utc)
        config={'github':{'owner':'example-user','repo':'WxStepCustom','workflow':'reachability.yml','ref':'main','token':'secret'},'network':{'enabled':False}}
        base='/repos/example-user/WxStepCustom'
        self.responses[base+'/actions/workflows/reachability.yml/dispatches']=(200,{'workflow_run_id':123,'html_url':'https://github.com/example-user/WxStepCustom/actions/runs/123'})
        async def notify(*args):
            raise AssertionError('WxStep must not send a reminder notification')
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'desk.db'
            runtime=Runtime(Desk(path),config,self.session,notify)
            runtime.github=GitHub(self.session,config['github'],self.url)
            result=await runtime.command('wxstep/submit',{'steps':18888},now)
            self.assertEqual(result['status'],'queued')
            self.assertEqual(result['run_id'],123)
            self.assertEqual(self.requests[0][1],{'ref':'main','inputs':{'steps':'18888'},'return_run_details':True})
            self.responses[base+'/actions/runs/123']=(200,{'status':'in_progress','html_url':result['url']})
            await runtime.tick(now+timedelta(seconds=15))
            self.assertEqual((await runtime.snapshot(now))['wxstep']['status'],'running')
            self.responses[base+'/actions/runs/123']=(503,{})
            await runtime.tick(now+timedelta(seconds=30))
            self.assertEqual((await runtime.snapshot(now))['wxstep']['status'],'tracking_error')
            restarted=Runtime(Desk(path),config,self.session,notify)
            restarted.github=GitHub(self.session,config['github'],self.url)
            await restarted.recover()
            self.responses[base+'/actions/runs/123']=(200,{'status':'completed','conclusion':'success','html_url':result['url']})
            await restarted.tick(now+timedelta(seconds=45))
            final=(await restarted.snapshot(now))['wxstep']
            self.assertEqual(final['status'],'success')
            self.assertEqual(final['steps'],18888)
            self.assertEqual(len([r for r in self.requests if r[0].endswith('/dispatches')]),1)

    def test_mail_decodes_headers_and_preserves_unread_without_reading_body(self):
        raw = b'From: =?UTF-8?B?R29vZ2xl?= <a@example.com>\r\nSubject: =?UTF-8?B?5a6J5YWo5o+Q6YaS?=\r\nDate: Thu, 1 Oct 2026 16:21:00 +0800\r\n\r\n'
        result = parse_mail(raw, b'123 (UID 99 FLAGS () BODY[HEADER.FIELDS] {100}', '99')
        self.assertEqual(result['sender'], 'Google')
        self.assertEqual(result['subject'], '安全提醒')
        self.assertTrue(result['unread'])
        self.assertEqual(result['received_at'], '2026-10-01T08:21:00+00:00')
        self.assertFalse(parse_mail(raw, b'FLAGS (\\Seen)', '99')['unread'])

    def test_actionable_notification_has_unique_revision_and_cross_platform_open(self):
        item = dict(id='abc', revision='revision', title='续费 VPS')
        data = notification_data(item, '/mydesk/home')
        actions = data['data']['actions']
        self.assertIn('revision', actions[0]['action'])
        self.assertEqual(actions[2]['action'], 'URI')
        self.assertEqual(actions[2]['uri'], '/mydesk/home')

    def test_received_at_uses_imap_internaldate_instead_of_sender_date(self):
        raw=b'From: a@example.com\r\nSubject: test\r\nDate: Wed, 30 Sep 2026 00:00:00 +0000\r\n\r\n'
        data=parse_mail(raw,b'FLAGS () INTERNALDATE "01-Oct-2026 16:22:00 +0800"','99')
        self.assertEqual(data['received_at'],'2026-10-01T08:22:00+00:00')

    def test_run_status_includes_queued_running_and_all_terminal_failures(self):
        self.assertEqual(run_state({'status':'queued'}), 'queued')
        self.assertEqual(run_state({'status':'in_progress'}), 'running')
        self.assertEqual(run_state({'status':'completed','conclusion':'success'}), 'success')
        for conclusion in ['failure','cancelled','timed_out','skipped','neutral']:
            self.assertEqual(run_state({'status':'completed','conclusion':conclusion}), 'failed')
