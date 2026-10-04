import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timedelta, timezone
from aiohttp import ClientSession

from mydesk.domain import Desk
from mydesk.runtime import Runtime

NOW = datetime(2026, 10, 1, 8, tzinfo=timezone.utc)


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.desk = Desk(Path(self.tmp.name) / 'desk.db')
        self.session = ClientSession()
        self.sent = []
        self.fail_notification = False
        async def notify(target, data):
            if self.fail_notification:
                raise RuntimeError('phone unavailable')
            self.sent.append((target, data))
        self.runtime = Runtime(self.desk, {'timezone': 'Asia/Shanghai', 'notify_targets': ['mobile_app_phone']}, self.session, notify)

    async def asyncTearDown(self):
        await self.session.close()
        self.tmp.cleanup()

    async def test_notify_failure_retries_and_success_does_not_duplicate(self):
        r = self.desk.create_reminder('续费 VPS', NOW + timedelta(minutes=1), NOW)
        self.fail_notification = True
        await self.runtime.tick(NOW + timedelta(minutes=2))
        self.assertEqual(len(self.desk.due_reminders(NOW + timedelta(minutes=2))), 1)
        self.fail_notification = False
        await self.runtime.tick(NOW + timedelta(minutes=3))
        await self.runtime.tick(NOW + timedelta(minutes=4))
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(self.desk.due_reminders(NOW + timedelta(minutes=4)), [])

    async def test_commands_create_snooze_complete_and_snapshot_hides_config_secrets(self):
        self.runtime.config['github'] = {'token': 'do-not-leak'}
        r = await self.runtime.command('reminder/create', {'title': '打电话', 'time': '30分钟后'}, NOW)
        await self.runtime.command('reminder/action', {'id': r['id'], 'action': 'snooze', 'minutes': 30}, NOW)
        state = await self.runtime.snapshot(NOW)
        self.assertEqual(state['reminders'][0]['status'], 'snoozed')
        self.assertNotIn('do-not-leak', str(state))
        await self.runtime.command('reminder/action', {'id': r['id'], 'action': 'complete'}, NOW)
        self.assertEqual((await self.runtime.snapshot(NOW))['reminders'], [])

    async def test_start_recovers_interrupted_dispatch_without_redispatch(self):
        job = self.desk.create_job(18888, NOW)
        await self.runtime.recover()
        current = self.desk.active_jobs()[0]
        self.assertEqual(current['status'], 'tracking_error')
        self.assertIn('不确定', current['message'])
        with self.assertRaises(ValueError):
            self.desk.create_job(18888, NOW)

    async def test_notification_actions_require_matching_revision(self):
        r = self.desk.create_reminder('提醒', NOW + timedelta(minutes=1), NOW)
        result = await self.runtime.notification_action(f"MYDESK:{r['id']}:{r['revision']}:snooze", NOW)
        self.assertTrue(result)
        result = await self.runtime.notification_action(f"MYDESK:{r['id']}:{r['revision']}:complete", NOW)
        self.assertFalse(result)
        self.assertEqual(len(self.desk.snapshot(NOW)['reminders']), 1)

    async def test_unconfigured_notifications_are_visible_and_due_reminder_is_preserved(self):
        self.runtime.config['notify_targets'] = []
        self.desk.create_reminder('提醒', NOW + timedelta(minutes=1), NOW)
        await self.runtime.tick(NOW + timedelta(minutes=2))
        self.assertEqual(len(self.desk.due_reminders(NOW + timedelta(minutes=2))), 1)
        state = await self.runtime.snapshot(NOW)
        self.assertFalse(state['configured']['notifications'])

    async def test_unknown_commands_are_rejected(self):
        with self.assertRaises(ValueError):
            await self.runtime.command('anything', {}, NOW)

    async def test_completion_during_delivery_stops_remaining_device_notifications(self):
        reminder=self.desk.create_reminder('提醒',NOW+timedelta(minutes=1),NOW)
        self.runtime.config['notify_targets']=['mobile_app_first','mobile_app_second']
        sent=[]
        async def notify(target,data):
            sent.append(target)
            self.desk.reminder_action(reminder['id'],'complete',NOW+timedelta(minutes=2))
        self.runtime.notify=notify
        await self.runtime.tick(NOW+timedelta(minutes=2))
        self.assertEqual(sent,['mobile_app_first'])

    async def test_partial_device_delivery_retries_only_failed_targets(self):
        self.desk.create_reminder('提醒',NOW+timedelta(minutes=1),NOW)
        self.runtime.config['notify_targets']=['mobile_app_first','mobile_app_second']
        sent=[]
        failing=True
        async def notify(target,data):
            sent.append(target)
            if target=='mobile_app_second' and failing:
                raise RuntimeError('Device service unavailable')
        self.runtime.notify=notify
        await self.runtime.tick(NOW+timedelta(minutes=2))
        failing=False
        await self.runtime.tick(NOW+timedelta(minutes=3))
        self.assertEqual(sent,['mobile_app_first','mobile_app_second','mobile_app_second'])
