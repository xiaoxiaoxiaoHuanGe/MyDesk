import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from datetime import datetime, timezone, timedelta
from mydesk.domain import Desk
from mydesk.step_plans import StepPlans, parameters

NOW=datetime(2026,10,7,tzinfo=timezone.utc)
SPEC=dict(start=80,increment=80,interval_minutes=1,target=240,random_percent=10)
CONFIG=dict(timezone='Asia/Shanghai',github=dict(owner='test',repo='test',workflow='steps',ref='main'))

class RandomSteps(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.desk=Desk(Path(self.tmp.name)/'db');self.draw=Mock(side_effect=[72,88,80,80])
        self.plans=StepPlans(self.desk,randint=self.draw)
    def success(self,now=NOW):
        job=self.plans.claim(now,CONFIG)
        self.desk.update_job(job['id'],status='success')
        self.plans.reconcile(now)
        return job['steps']
    def test_draws_bounds_repeated_values_and_persists_after_restart(self):
        self.plans.start({**SPEC,'target':1000},NOW,CONFIG)
        self.assertEqual(self.success(),80)
        self.assertEqual(self.plans.snapshot()['run']['next_steps'],152)
        self.plans.reconcile(NOW);self.assertEqual(self.draw.call_count,1)
        self.plans=StepPlans(self.desk,randint=self.draw)
        self.assertEqual(self.success(NOW+timedelta(minutes=1)),152)
        self.assertEqual(self.success(NOW+timedelta(minutes=2)),240)
        self.assertEqual(self.success(NOW+timedelta(minutes=3)),320)
        self.assertEqual(self.plans.snapshot()['run']['next_steps'],400)
        self.draw.assert_called_with(72,88)
    def test_failed_retry_does_not_draw(self):
        self.plans.start(SPEC,NOW,CONFIG);job=self.plans.claim(NOW,CONFIG)
        self.desk.update_job(job['id'],status='failed');self.plans.reconcile(NOW)
        self.assertEqual(self.draw.call_count,0)
        run=self.plans.snapshot()['run'];self.plans.control('resume',{'run_id':run['id']},NOW,CONFIG)
        self.assertEqual(self.success(),80);self.assertEqual(self.draw.call_count,1)
    def test_cap_and_strict_stop(self):
        self.draw.side_effect=None;self.draw.return_value=88
        self.plans.start({**SPEC,'start':29999,'target':29999},NOW,CONFIG)
        self.assertEqual(self.success(),29999)
        self.assertEqual(self.success(NOW+timedelta(minutes=1)),30000)
        self.assertEqual(self.plans.snapshot()['run']['status'],'completed')
        self.assertEqual(self.draw.call_count,1)
    def test_legacy_defaults_and_strict_validation(self):
        self.assertEqual(parameters({k:v for k,v in SPEC.items() if k!='random_percent'})['random_percent'],0)
        for value in [True,10.0,'10',-10,5,None]:
            with self.assertRaises(ValueError):parameters({**SPEC,'random_percent':value})
        with self.assertRaises(ValueError):parameters({**SPEC,'target':30000})
        self.assertEqual(parameters({**SPEC,'increment':1})['increment'],1)
    def test_daily_validation_uses_slowest_estimate(self):
        with self.assertRaises(ValueError):
            self.plans.configure({**SPEC,'start':80,'target':800,'daily':True,'start_time':'23:50'},NOW,CONFIG)
    def test_presets_and_run_snapshot(self):
        self.plans.preset('save',{**SPEC,'name':'测试'})
        self.assertEqual(self.plans.snapshot()['presets'][0]['random_percent'],10)
        self.plans.start(SPEC,NOW,CONFIG)
        self.plans.configure({**SPEC,'random_percent':0},NOW,CONFIG)
        self.assertEqual(self.plans.snapshot()['run']['params']['random_percent'],10)
