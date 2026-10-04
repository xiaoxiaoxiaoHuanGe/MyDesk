import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from aiohttp import ClientSession, web

from mydesk.config import Settings
from mydesk.domain import Desk
from mydesk.runtime import Runtime
from mydesk.github_checkins import result_summary, CheckinGitHub, SOURCES

NOW = datetime(2026, 10, 2, 8, tzinfo=timezone.utc)


class CheckinSettingsTests(unittest.TestCase):
    def test_timeout_defaults_and_individual_edits_preserve_all_three_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'settings.json'
            settings=Settings(path)
            settings.update({'github':dict(owner='owner',repo='WxStepCustom',workflow='steps.yml',token='steps-secret')})
            tasks={key:{**spec,'owner':'example-user','adapter':key,'enabled':True,'token':key+'-secret'} for key,spec in SOURCES.items()}
            tasks['glados']['max_age_hours']=72
            public=settings.update({'github_tasks':tasks})
            self.assertEqual(public['github_tasks']['52fzwg']['max_age_hours'],36)
            self.assertEqual(public['github_tasks']['glados']['max_age_hours'],72)
            public['github_tasks']['52fzwg']['max_age_hours']=48
            settings.update({'github_tasks':public['github_tasks']})
            restarted=Settings(path)
            self.assertEqual(restarted.value['github_tasks']['52fzwg']['max_age_hours'],48)
            self.assertEqual(restarted.value['github_tasks']['glados']['max_age_hours'],72)
            for key in SOURCES:
                self.assertEqual(restarted.value['github_tasks'][key]['token'],key+'-secret')
            self.assertEqual(restarted.value['github']['token'],'steps-secret')
            self.assertNotIn('secret',str(restarted.public()))

    def test_echoed_script_cannot_report_a_false_success_or_retry_and_missing_step_is_unknown(self):
        log='##[group]Run bash\n✅ 签到成功，增加金币 999\nAUTO_RETRY retry_failed\n##[endgroup]\n✅ 今日已经签到\n'
        status,parts=result_summary('52fzwg',{'conclusion':'success'},{'conclusion':'success'},log)
        self.assertEqual(status,'success')
        self.assertEqual(parts,['今日已签到'])
        status,parts=result_summary('glados',{'conclusion':None},{'conclusion':'cancelled'},'')
        self.assertEqual(status,'unknown')

    def test_each_checkin_has_its_own_redacted_token(self):
        with tempfile.TemporaryDirectory() as directory:
            settings=Settings(Path(directory)/'settings.json')
            values={key:{**spec,'owner':'example-user','adapter':key,'max_age_hours':36,'enabled':True,'token':key+'-secret'} for key,spec in SOURCES.items()}
            public=settings.update({'github_tasks':values})
            self.assertNotIn('secret',str(public))
            public['github_tasks']['glados']['token']=''
            settings.update({'github_tasks':public['github_tasks']})
            self.assertEqual(settings.value['github_tasks']['glados']['token'],'glados-secret')
            self.assertEqual(settings.value['github_tasks']['52fzwg']['token'],'52fzwg-secret')


class CheckinRuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.desk = Desk(Path(self.tmp.name) / 'desk.db')
        self.requests = []
        self.fail_forum = False
        self.attempt = 1
        async def respond(request):
            self.requests.append((request.method, request.path))
            forum = '/52fzwg-checkin/' in request.path
            if forum and self.fail_forum:
                return web.json_response({'message': 'secret response'}, status=403)
            if '/workflows/' in request.path:
                self.assertEqual(request.query['branch'], 'main' if forum else 'master')
                return web.json_response({'workflow_runs': [dict(id=11 if forum else 22, run_attempt=self.attempt,
                    status='completed', conclusion='failure' if forum else 'success', head_branch='main' if forum else 'master',
                    path='.github/workflows/checkin.yml' if forum else '.github/workflows/gladosCheck.yml',
                    updated_at=(NOW-timedelta(hours=1)).isoformat())]})
            if request.path.endswith('/jobs'):
                return web.json_response({'total_count': 1, 'jobs': [dict(id=111 if forum else 222, steps=[
                    dict(name='Check in' if forum else 'Running checkin', conclusion='success', status='completed'),
                    dict(name='Send Feishu notification', conclusion='failure' if forum else 'success', status='completed')])]})
            if request.path.endswith('/logs'):
                log = ('2026-10-02T06:00:00Z ✅ 签到成功，增加金币 1\n' if forum else
                    '2026-10-02T06:00:00Z GLaDOS 签到, 成功1, 失败0, 重复0\n'
                    '2026-10-02T06:00:00Z #1 P:9 剩余:36 天 总积分:76 积分 | 签到成功 | 兑换失败: Not enough points\n')
                # The echoed notification script must never be parsed as runtime output.
                return web.Response(text=log+'2026-10-02T06:00:01Z echo AUTO_RETRY retry_failed\nCookie=private-account-data\n')
            return web.json_response({}, status=404)
        app = web.Application()
        app.router.add_route('*', '/{tail:.*}', respond)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.url = f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}'
        self.session = ClientSession()
        self.config = {'network': {'enabled': False}, 'github_tasks': {key:{**spec,'owner':'example-user','adapter':key,'max_age_hours':36,'enabled':True,'token':key+'-secret'} for key,spec in SOURCES.items()}}
        self.runtime = self.make_runtime()

    def make_runtime(self):
        runtime = Runtime(self.desk, self.config, self.session, None)
        # Test HTTP transport, not replacement business logic.
        if getattr(runtime, 'checkins', None):
            runtime.checkins.base_url = self.url
        return runtime

    async def asyncTearDown(self):
        await self.session.close()
        await self.runner.cleanup()
        self.tmp.cleanup()

    async def test_github_poll_waits_ten_minutes_and_feed_stays_healthy_between_polls(self):
        await self.runtime.refresh(NOW)
        first=len(self.requests)
        for minute in (1,5,9):
            await self.runtime.refresh(NOW+timedelta(minutes=minute))
            state=await self.runtime.snapshot(NOW+timedelta(minutes=minute))
            self.assertFalse(state['feeds']['github_tasks']['stale'])
            self.assertFalse(any(item['kind']=='integration' for item in state['attention']))
        self.assertEqual(first,len(self.requests),'等待十分钟期间不应重复读取 GitHub')
        await self.runtime.refresh(NOW+timedelta(minutes=10))
        self.assertEqual(len(self.requests),first+2)
        state=await self.runtime.snapshot(NOW+timedelta(minutes=31))
        self.assertTrue(state['feeds']['github_tasks']['stale'])

    async def test_each_timeout_uses_its_own_last_result_and_paused_task_is_not_overdue(self):
        self.config['github_tasks']['52fzwg'].pop('max_age_hours')
        self.config['github_tasks']['glados']['max_age_hours']=72
        await self.runtime.refresh(NOW)
        boundary=NOW+timedelta(hours=35)
        state=await self.runtime.snapshot(boundary)
        self.assertTrue(all(task['status']=='success' for task in state['tasks']))
        state=await self.runtime.snapshot(boundary+timedelta(seconds=1))
        by_id={task['task_id']:task for task in state['tasks']}
        self.assertEqual(by_id['github.52fzwg']['status'],'unknown')
        self.assertEqual(by_id['github.glados']['status'],'success')
        self.assertEqual([item['id'] for item in state['attention'] if item['kind']=='task'],['github.52fzwg'])
        self.config['github_tasks']['52fzwg']['enabled']=False
        self.runtime.configure_checkins()
        state=await self.runtime.snapshot(boundary+timedelta(seconds=1))
        self.assertEqual([task['task_id'] for task in state['tasks']],['github.glados'])
        self.assertFalse(any(item['id']=='github.52fzwg' for item in state['attention']))

    async def test_imports_two_actual_signin_results_and_deduplicates_across_restart(self):
        await self.runtime.refresh(NOW)
        state = await self.runtime.snapshot(NOW)
        self.assertEqual(len(state['tasks']), 2, '应导入两个独立签到任务')
        forum = next(t for t in state['tasks'] if t['task_id'] == 'github.52fzwg')
        glados = next(t for t in state['tasks'] if t['task_id'] == 'github.glados')
        self.assertEqual(forum['status'], 'success', '飞书失败不能把成功签到改成失败')
        self.assertIn('金币 +1', forum['message'])
        self.assertIn('飞书通知失败', forum['message'])
        self.assertIn('积分 +9', glados['message'])
        self.assertIn('剩余 36 天', glados['message'])
        self.assertIn('兑换未完成', glados['message'])
        self.assertNotIn('private-account-data', str(state))
        self.assertNotIn('read-secret', str(state))
        requests = len(self.requests)
        restarted = self.make_runtime()
        await restarted.refresh(NOW+timedelta(minutes=1))
        self.assertEqual(len(self.desk.task_history()), 2)
        self.assertEqual(len(self.requests)-requests, 2, '已入库的完成记录只检查列表，不重复下载日志')
        self.attempt = 2
        await restarted.refresh(NOW+timedelta(minutes=11))
        self.assertEqual(len(self.desk.task_history()), 4, '重跑同一 Run 必须保留新的 attempt')
        self.assertTrue(all(method == 'GET' for method, _ in self.requests))

    async def test_one_unavailable_source_does_not_block_other_or_falsify_last_result(self):
        await self.runtime.refresh(NOW)
        self.fail_forum = True
        await self.runtime.refresh(NOW+timedelta(minutes=10))
        state = await self.runtime.snapshot(NOW+timedelta(minutes=10))
        self.assertEqual(len(self.desk.task_history()), 2)
        self.assertTrue(any('52' in item['title'] and '同步失败' in item['message'] for item in state['attention']))
        self.assertNotIn('secret response', str(state))
        state = await self.runtime.snapshot(NOW+timedelta(hours=40))
        self.assertTrue(all(task['status'] == 'unknown' for task in state['tasks']))

    async def test_disconnecting_one_source_hides_its_card_and_error_but_preserves_history(self):
        await self.runtime.refresh(NOW)
        self.fail_forum=True
        await self.runtime.refresh(NOW+timedelta(minutes=10))
        del self.config['github_tasks']['52fzwg']
        self.runtime.configure_checkins()
        state=await self.runtime.snapshot(NOW+timedelta(minutes=10))
        self.assertEqual([task['task_id'] for task in state['tasks']],['github.glados'])
        self.assertFalse(any(item['id']=='github.52fzwg' for item in state['attention']))
        self.assertEqual(len(self.desk.task_history()),2)

    async def test_a_different_binding_cannot_supply_the_current_task_result(self):
        await self.runtime.refresh(NOW)
        self.desk.report_task(dict(task_id='github.glados',task_name='其他任务',status='success',message='其他仓库结果',
            source='GitHub Actions',timestamp=NOW.isoformat(),event_id='github.glados.otherbinding.900.1.completed'),NOW)
        state=await self.runtime.snapshot(NOW)
        current=next(t for t in state['tasks'] if t['task_id']=='github.glados')
        self.assertEqual(current['status'],'unknown')
        self.assertNotIn('其他仓库结果',current['message'])

    async def test_log_redirect_to_non_github_storage_is_rejected_without_sending_token(self):
        app=web.Application()
        async def redirect(request):
            return web.Response(status=302,headers={'Location':'https://attacker.example/logs'})
        app.router.add_get('/repos/a/b/actions/jobs/1/logs',redirect)
        runner=web.AppRunner(app)
        await runner.setup()
        site=web.TCPSite(runner,'127.0.0.1',0)
        await site.start()
        try:
            github=CheckinGitHub(self.session,dict(owner='a',repo='b',token='read-secret'),f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}')
            with self.assertRaisesRegex(ValueError,'下载地址无效'):
                await github.job_log(1)
        finally:
            await runner.cleanup()


if __name__ == '__main__':
    unittest.main()
