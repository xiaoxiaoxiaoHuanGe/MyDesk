import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from mydesk.domain import Desk, parse_time, validate_steps

NOW = datetime(2026, 10, 1, 8, tzinfo=timezone.utc)


class DeskTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'desk.db'
        self.desk = Desk(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_relative_today_tomorrow_and_explicit_times(self):
        expected = [('30分钟后', NOW + timedelta(minutes=30)),
                    ('2小时后', NOW + timedelta(hours=2)),
                    ('今天20:00', datetime(2026, 10, 1, 12, tzinfo=timezone.utc)),
                    ('明天09:30', datetime(2026, 10, 2, 1, 30, tzinfo=timezone.utc)),
                    ('2026-10-05 15:00', datetime(2026, 10, 5, 7, tzinfo=timezone.utc))]
        for text, result in expected:
            self.assertEqual(parse_time(text, 'Asia/Shanghai', NOW), result)
        for text in ['今天01:00', '0分钟后', '明天99:00', 'abc']:
            with self.assertRaises(ValueError):
                parse_time(text, 'Asia/Shanghai', NOW)

    def test_steps_rejects_bool_fraction_and_outside_range(self):
        for value in [0, 18888, 30000]:
            self.assertEqual(validate_steps(value), value)
        for value in [True, -1, 30001, 100000, 1.2, '18888', None]:
            with self.assertRaises(ValueError):
                validate_steps(value)

    def test_reminder_survives_restart_and_due_notification_is_idempotent(self):
        reminder = self.desk.create_reminder('续费 VPS', NOW + timedelta(minutes=30), NOW)
        restarted = Desk(self.path)
        due = restarted.due_reminders(NOW + timedelta(hours=1))
        self.assertEqual([r['id'] for r in due], [reminder['id']])
        restarted.mark_delivered(reminder['id'], reminder['revision'], 'phone', NOW)
        self.assertEqual(restarted.delivery_targets(reminder['id'], ['phone', 'other']), ['other'])
        restarted.mark_notified(reminder['id'], reminder['revision'], NOW)
        self.assertEqual(restarted.due_reminders(NOW + timedelta(hours=1)), [])
        self.assertEqual(len(restarted.snapshot(NOW + timedelta(hours=1))['attention']), 1)

    def test_snooze_invalidates_old_notification_and_completion_clears_attention(self):
        r = self.desk.create_reminder('打电话', NOW + timedelta(minutes=1), NOW)
        snoozed = self.desk.reminder_action(r['id'], 'snooze', NOW + timedelta(minutes=2), minutes=10, revision=r['revision'])
        self.assertNotEqual(snoozed['revision'], r['revision'])
        with self.assertRaises(ValueError):
            self.desk.reminder_action(r['id'], 'complete', NOW, revision=r['revision'])
        self.desk.reminder_action(r['id'], 'complete', NOW + timedelta(minutes=3))
        snapshot = self.desk.snapshot(NOW + timedelta(hours=1))
        self.assertEqual(snapshot['reminders'], [])
        self.assertEqual(snapshot['attention'], [])

    def test_task_history_deduplicates_and_older_events_do_not_override_current(self):
        event = dict(task_id='glados', task_name='GLaDOS', status='failed', message='Cookie 已失效',
                     timestamp=NOW.isoformat(), source='github_actions', event_id='run1')
        self.desk.report_task(event, NOW)
        self.desk.report_task(event, NOW)
        older = dict(event, status='success', event_id='old', timestamp=(NOW - timedelta(days=1)).isoformat())
        self.desk.report_task(older, NOW)
        self.assertEqual(len(self.desk.task_history()), 2)
        self.assertEqual(self.desk.snapshot(NOW)['tasks'][0]['status'], 'failed')
        self.desk.report_task(dict(event, status='success', event_id='new', timestamp=(NOW + timedelta(minutes=1)).isoformat()), NOW)
        self.assertEqual(self.desk.snapshot(NOW + timedelta(minutes=1))['attention'], [])

    def test_task_validation_and_stale_task_detection(self):
        with self.assertRaises(ValueError):
            self.desk.report_task({'task_id': 'x', 'status': 'ok'}, NOW)
        state = self.desk.snapshot(NOW, expected_tasks={'backup': {'name': '备份', 'max_age_hours': 24}})
        self.assertEqual(state['tasks'][0]['status'], 'unknown')
        self.assertEqual(state['attention'][0]['kind'], 'task')

    def test_history_orders_by_execution_time_and_cursor_handles_late_reports(self):
        event = dict(task_id='x',task_name='x',status='success',message='ok',source='github_actions')
        self.desk.report_task(dict(event,event_id='today',timestamp=NOW.isoformat()),NOW)
        self.desk.report_task(dict(event,event_id='late',timestamp=(NOW-timedelta(days=1)).isoformat()),NOW)
        first=self.desk.task_history(limit=1)
        self.assertEqual(first[0]['event_id'],'today')
        second=self.desk.task_history(limit=1,before=first[0]['id'])
        self.assertEqual(second[0]['event_id'],'late')

    def test_server_network_unknown_and_failure_states_are_not_healthy(self):
        self.desk.set_feed('servers', {'items': [{'id': 'jp', 'name': 'VPS-JP', 'status': 'down'}]}, NOW)
        self.desk.set_feed('network', {'online': False, 'nodes': []}, NOW)
        kinds = [a['kind'] for a in self.desk.snapshot(NOW)['attention']]
        self.assertCountEqual(kinds, ['server', 'network'])
        self.desk.set_feed('servers', {'items': [{'id': 'jp', 'name': 'VPS-JP', 'status': 'up'}]}, NOW)
        self.desk.set_feed('network', {'online': True, 'nodes': []}, NOW)
        self.assertEqual(self.desk.snapshot(NOW)['attention'], [])
        stale = self.desk.snapshot(NOW + timedelta(minutes=10))
        self.assertTrue(stale['feeds']['servers']['stale'])
        self.assertEqual(stale['feeds']['servers']['data']['items'][0]['status'], 'unknown')

    def test_wxstep_prevents_parallel_submission_and_persists_run(self):
        job = self.desk.create_job(18888, NOW)
        with self.assertRaises(ValueError):
            self.desk.create_job(20000, NOW)
        self.desk.update_job(job['id'], status='queued', run_id=123, url='https://github.com/a/b/actions/runs/123')
        restarted = Desk(self.path)
        self.assertEqual(restarted.active_jobs()[0]['run_id'], 123)
        restarted.update_job(job['id'], status='success')
        self.assertEqual(restarted.active_jobs(), [])
        self.assertEqual(restarted.snapshot(NOW)['wxstep']['steps'], 18888)

    def test_retention_keeps_current_tasks_and_pending_reminders(self):
        self.desk.create_reminder('未来提醒', NOW + timedelta(days=100), NOW - timedelta(days=100))
        self.desk.report_task(dict(task_id='x', task_name='x', status='failed', message='error',
                                   timestamp=(NOW - timedelta(days=100)).isoformat(), source='github_actions'), NOW)
        self.desk.prune(NOW, 14)
        self.assertEqual(len(self.desk.snapshot(NOW)['reminders']), 1)
        self.assertEqual(len(self.desk.snapshot(NOW)['tasks']), 1)


if __name__ == '__main__':
    unittest.main()
