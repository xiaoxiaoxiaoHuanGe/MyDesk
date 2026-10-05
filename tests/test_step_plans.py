import tempfile
import unittest
import asyncio
from pathlib import Path
from datetime import datetime, timedelta, timezone

from mydesk.domain import Desk
from mydesk.runtime import Runtime

NOW = datetime(2026, 10, 5, 0, tzinfo=timezone.utc)
PARAMS = dict(start=1000, increment=30, interval_minutes=3, target=1090)


class FakeGitHub:
    def __init__(self):
        self.sent = []
        self.results = {}
        self.fail_dispatch = False

    async def dispatch(self, steps):
        self.sent.append(steps)
        if self.fail_dispatch:
            raise ValueError('提交结果不确定')
        run = len(self.sent)
        self.results[run] = 'success'
        return dict(run_id=run, url='https://github.com/example/test/actions/runs/'+str(run))

    async def run(self, run):
        result = self.results[run]
        if result == 'unknown':
            raise ValueError('查询失败')
        return dict(status='in_progress' if result == 'running' else 'completed', conclusion=result,
                    html_url='https://github.com/example/test/actions/runs/'+str(run))


class StepPlanTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.desk = Desk(Path(self.tmp.name)/'desk.db')
        self.github = FakeGitHub()
        self.config = dict(timezone='Asia/Shanghai', network={'enabled':False},
                           github=dict(owner='example',repo='test',workflow='steps.yml',ref='main',token='fake'))
        self.rt = self.runtime()

    async def asyncTearDown(self):
        self.tmp.cleanup()

    def runtime(self):
        async def notify(*args): pass
        rt = Runtime(self.desk, self.config, None, notify)
        rt.github = self.github
        return rt

    async def start(self, params=None):
        return await self.rt.command('wxstep/plan/start',params or PARAMS,NOW)

    async def test_exact_sequence_submits_first_strictly_greater_value(self):
        await self.start()
        self.assertEqual(self.github.sent,[1000])
        for minute in [0,3,6,9,12]:
            await self.rt.tick(NOW+timedelta(minutes=minute))
        await self.rt.tick(NOW+timedelta(minutes=13))
        state=(await self.rt.snapshot(NOW+timedelta(minutes=13)))['wxstep_plan']
        self.assertEqual(self.github.sent,[1000,1030,1060,1090,1120])
        self.assertEqual(state['run']['status'],'completed')
        self.assertEqual(state['run']['success_count'],5)

    async def test_pruning_preserves_attempts_for_a_live_round(self):
        await self.start()
        await self.rt.tick(NOW)
        self.desk.prune(NOW+timedelta(days=91))
        state=self.rt.step_plans.snapshot()
        self.assertEqual(len(state['run']['records']),1)

    async def test_resume_cannot_send_below_successful_daily_floor(self):
        await self.start()
        await self.rt.command('wxstep/plan/stop',{'run_id':self.rt.step_plans.snapshot()['run']['id']},NOW)
        await self.rt.tick(NOW)
        await self.rt.command('wxstep/plan/save',dict(**PARAMS,daily=True,start_time='08:00'),NOW)
        tomorrow=NOW+timedelta(days=1)
        await self.rt.command('wxstep/submit',{'steps':2000},tomorrow)
        await self.rt.tick(tomorrow)
        run=self.rt.step_plans.snapshot()['run']
        self.assertEqual(run['status'],'paused')
        with self.assertRaises(ValueError):
            await self.rt.command('wxstep/plan/resume',{'run_id':run['id']},tomorrow)

    async def test_start_rejects_non_text_name(self):
        with self.assertRaises(ValueError):
            await self.start(dict(**PARAMS,name={'bad':'name'}))

    async def test_stop_does_not_wait_for_dispatch_network_request(self):
        entered=asyncio.Event();release=asyncio.Event();original=self.github.dispatch
        async def blocked(steps):
            entered.set();await release.wait();return await original(steps)
        self.github.dispatch=blocked
        starting=asyncio.create_task(self.start())
        await asyncio.wait_for(entered.wait(),5)
        run=self.rt.step_plans.snapshot()['run']
        stopped=await asyncio.wait_for(self.rt.command('wxstep/plan/stop',{'run_id':run['id']},NOW),5)
        self.assertEqual(stopped['run']['status'],'stopped')
        release.set();await starting
        await self.rt.tick(NOW+timedelta(minutes=30))
        self.assertEqual(self.github.sent,[1000])
        self.assertEqual(self.rt.step_plans.snapshot()['run']['status'],'stopped')

    async def test_nondivisible_target_and_equal_start(self):
        await self.start({**PARAMS,'target':1000})
        await self.rt.tick(NOW)
        await self.rt.tick(NOW+timedelta(minutes=3))
        await self.rt.tick(NOW+timedelta(minutes=4))
        self.assertEqual(self.github.sent,[1000,1030])

    async def test_zero_start_is_actually_submitted(self):
        await self.start(dict(start=0,increment=30,interval_minutes=3,target=20))
        self.assertEqual(self.github.sent,[0])

    async def test_stop_is_persistent_and_finishes_tracking_inflight(self):
        run=(await self.start())['run']
        await self.rt.command('wxstep/plan/stop',{'run_id':run['id']},NOW)
        self.rt=self.runtime()
        await self.rt.recover()
        await self.rt.tick(NOW+timedelta(hours=1))
        state=(await self.rt.snapshot())['wxstep_plan']
        self.assertEqual(self.github.sent,[1000])
        self.assertEqual(state['run']['status'],'stopped')
        self.assertEqual(state['run']['success_count'],1)

    async def test_old_stop_cannot_stop_a_new_round(self):
        old=(await self.start())['run']['id']
        await self.rt.tick(NOW)
        await self.rt.command('wxstep/plan/stop',{'run_id':old},NOW)
        new=await self.rt.command('wxstep/plan/start',PARAMS,NOW+timedelta(minutes=1))
        with self.assertRaises(ValueError):
            await self.rt.command('wxstep/plan/stop',{'run_id':old},NOW+timedelta(minutes=2))
        self.assertNotEqual(new['run']['id'],old)

    async def test_busy_run_waits_without_backlog_and_interval_reanchors(self):
        await self.start()
        self.github.results[1]='running'
        await self.rt.tick(NOW+timedelta(minutes=9))
        self.assertEqual(self.github.sent,[1000])
        self.github.results[1]='success'
        await self.rt.tick(NOW+timedelta(minutes=10))
        self.assertEqual(self.github.sent,[1000,1030])
        await self.rt.tick(NOW+timedelta(minutes=11))
        self.assertEqual(self.github.sent,[1000,1030])

    async def test_uncertain_dispatch_pauses_and_never_resubmits(self):
        self.github.fail_dispatch=True
        await self.start()
        await self.rt.tick(NOW+timedelta(minutes=10))
        self.rt=self.runtime()
        await self.rt.recover()
        await self.rt.tick(NOW+timedelta(minutes=20))
        state=await self.rt.snapshot()
        self.assertEqual(self.github.sent,[1000])
        self.assertEqual(state['wxstep_plan']['run']['status'],'paused')
        self.assertTrue(any(x['kind']=='steps' for x in state['attention']))
        with self.assertRaises(ValueError):
            await self.rt.command('wxstep/plan/resume',{'run_id':state['wxstep_plan']['run']['id']},NOW)

    async def test_failed_attempt_explicit_retry_uses_same_value(self):
        run=(await self.start())['run']
        self.github.results[1]='failure'
        await self.rt.tick(NOW+timedelta(minutes=3))
        self.assertEqual(self.github.sent,[1000])
        await self.rt.command('wxstep/plan/resume',{'run_id':run['id']},NOW+timedelta(minutes=4))
        self.assertEqual(self.github.sent,[1000,1000])

    async def test_query_failure_with_run_id_keeps_original_tracking(self):
        await self.start()
        self.github.results[1]='unknown'
        await self.rt.tick(NOW+timedelta(minutes=3))
        self.assertEqual(self.github.sent,[1000])
        self.github.results[1]='success'
        await self.rt.tick(NOW+timedelta(minutes=4))
        self.assertEqual(self.github.sent,[1000,1030])

    async def test_manual_success_rebases_and_can_end_round(self):
        await self.start()
        await self.rt.tick(NOW)
        await self.rt.command('wxstep/submit',{'steps':1080},NOW+timedelta(minutes=1))
        await self.rt.tick(NOW+timedelta(minutes=2))
        await self.rt.tick(NOW+timedelta(minutes=4))
        await self.rt.tick(NOW+timedelta(minutes=5))
        self.assertEqual(self.github.sent,[1000,1080,1110])
        self.assertEqual((await self.rt.snapshot())['wxstep_plan']['run']['status'],'completed')

    async def test_manual_lower_success_value_is_rejected(self):
        await self.start()
        await self.rt.tick(NOW)
        with self.assertRaises(ValueError):
            await self.rt.command('wxstep/submit',{'steps':999},NOW)

    async def test_restart_resumes_once_without_burst(self):
        await self.start()
        await self.rt.tick(NOW)
        self.rt=self.runtime()
        await self.rt.recover()
        await self.rt.tick(NOW+timedelta(minutes=30))
        await self.rt.tick(NOW+timedelta(minutes=30))
        self.assertEqual(self.github.sent,[1000,1030])

    async def test_final_overflow_and_invalid_numbers_are_rejected(self):
        for spec in [{**PARAMS,'start':True},{**PARAMS,'increment':0},{**PARAMS,'interval_minutes':0},
                     {**PARAMS,'target':30000},{**PARAMS,'start':1100},{**PARAMS,'increment':1.5}]:
            with self.assertRaises(ValueError): await self.start(spec)
        self.assertEqual(self.github.sent,[])

    async def test_presets_fill_only_and_running_parameters_are_immutable(self):
        saved=await self.rt.command('wxstep/preset/save',dict(name='散步',**PARAMS),NOW)
        preset=saved['presets'][0]
        self.assertEqual(self.github.sent,[])
        await self.start({**PARAMS,'preset_id':preset['id']})
        await self.rt.command('wxstep/preset/save',dict(id=preset['id'],name='逛街',**{**PARAMS,'increment':60}),NOW)
        await self.rt.tick(NOW)
        await self.rt.tick(NOW+timedelta(minutes=3))
        self.assertEqual(self.github.sent,[1000,1030])
        await self.rt.command('wxstep/preset/delete',{'id':preset['id']},NOW)
        self.assertEqual((await self.rt.snapshot())['wxstep_plan']['presets'],[])

    async def test_daily_save_does_not_submit_and_next_day_runs_once(self):
        await self.rt.command('wxstep/plan/save',dict(**PARAMS,daily=True,start_time='08:00'),NOW)
        await self.rt.tick(NOW)
        self.assertEqual(self.github.sent,[])
        tomorrow=NOW+timedelta(days=1)
        await self.rt.tick(tomorrow)
        await self.rt.tick(tomorrow)
        self.assertEqual(self.github.sent,[1000])
        run=(await self.rt.snapshot(tomorrow))['wxstep_plan']['run']
        await self.rt.command('wxstep/plan/stop',{'run_id':run['id']},tomorrow)
        await self.rt.tick(tomorrow+timedelta(minutes=5))
        self.assertEqual(self.github.sent,[1000])

    async def test_disable_stops_current_round_and_future_daily_rounds(self):
        await self.rt.command('wxstep/plan/save',dict(**PARAMS,daily=True,start_time='08:00'),NOW)
        await self.start()
        await self.rt.command('wxstep/plan/disable',{},NOW)
        await self.rt.tick(NOW+timedelta(days=1))
        state=(await self.rt.snapshot())['wxstep_plan']
        self.assertFalse(state['settings']['daily'])
        self.assertEqual(self.github.sent,[1000])

    async def test_binding_change_pauses_before_new_dispatch(self):
        await self.start()
        await self.rt.tick(NOW)
        self.rt.config={**self.config,'github':{**self.config['github'],'repo':'different'}}
        await self.rt.tick(NOW+timedelta(minutes=3))
        self.assertEqual(self.github.sent,[1000])
        self.assertEqual((await self.rt.snapshot())['wxstep_plan']['run']['status'],'paused')
