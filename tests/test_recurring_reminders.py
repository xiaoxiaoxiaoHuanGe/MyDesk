import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from mydesk.domain import Desk

NOW = datetime(2026, 10, 16, 0, 0, tzinfo=timezone.utc)


class RecurringRemindersTest(unittest.TestCase):
    def test_upgrade_keeps_the_original_reminder_table_compatible_with_old_inserts(self):
        with self.desk.connect() as db:
            columns = [row[1] for row in db.execute('PRAGMA table_info(reminders)')]
            self.assertEqual(len(columns), 8, 'Store recurrence separately so the old backend can still create reminders after rollback')
            db.execute('INSERT INTO reminders VALUES (?,?,?,?,?,?,?,?)', ('legacy', '旧版本创建', (NOW+timedelta(hours=1)).isoformat(), 'pending', NOW.isoformat(), None, 'old-revision', None))
        self.assertEqual(self.desk.snapshot(NOW)['reminders'][0]['title'], '旧版本创建')
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.desk = Desk(Path(self.tmp.name) / 'desk.db')
        self.rule = dict(days=[1, 2, 3, 4, 5], hour=9, minute=0, timezone='Asia/Shanghai')

    def tearDown(self):
        self.tmp.cleanup()

    def test_blank_and_duplicate_names_are_independent(self):
        rows = [self.desk.create_reminder(title, NOW + timedelta(minutes=1), NOW) for title in ['', '  ', None, '提醒']]
        self.assertEqual([r['title'] for r in rows], ['提醒'] * 4)
        self.assertEqual(len({r['id'] for r in rows}), 4)

    def test_weekdays_continue_without_completing_the_previous_occurrence(self):
        first = self.desk.create_reminder('', NOW + timedelta(hours=1), NOW, repeat=self.rule)
        monday = NOW + timedelta(days=3, hours=1)
        due = self.desk.due_reminders(monday)
        self.assertEqual(len(due), 1)
        self.assertEqual(due[0]['remind_at'], monday.isoformat())
        self.assertNotEqual(due[0]['revision'], first['revision'])
        with self.assertRaises(ValueError):
            self.desk.reminder_action(first['id'], 'complete', monday, revision=first['revision'])

    def test_completion_and_snooze_do_not_change_the_fixed_repeat_time(self):
        first = self.desk.create_reminder('喝水', NOW + timedelta(hours=1), NOW, repeat=self.rule)
        snoozed = self.desk.reminder_action(first['id'], 'snooze', NOW + timedelta(hours=1), revision=first['revision'], minutes=30)
        completed = self.desk.reminder_action(first['id'], 'complete', NOW + timedelta(hours=1, minutes=31), revision=snoozed['revision'])
        self.assertEqual(completed['status'], 'pending')
        self.assertEqual(completed['remind_at'], (NOW + timedelta(days=3, hours=1)).isoformat())

    def test_pause_resume_and_restart_preserve_the_rule(self):
        first = self.desk.create_reminder('提醒', NOW + timedelta(hours=1), NOW, repeat=self.rule)
        paused = self.desk.reminder_action(first['id'], 'pause', NOW, revision=first['revision'])
        self.assertEqual(paused['status'], 'paused')
        self.assertEqual(self.desk.due_reminders(NOW + timedelta(days=3)), [])
        self.desk = Desk(self.desk.path)
        self.assertEqual(self.desk.snapshot(NOW)['reminders'][0]['status'], 'paused')
        resumed = self.desk.reminder_action(first['id'], 'resume', NOW + timedelta(days=3), revision=paused['revision'])
        self.assertEqual(resumed['remind_at'], (NOW + timedelta(days=3, hours=1)).isoformat())

    def test_repeat_rule_is_validated(self):
        for changes in [dict(days=[]), dict(days=[0]), dict(days=[True]), dict(hour=24), dict(timezone='Invalid/Zone')]:
            with self.assertRaises(ValueError):
                self.desk.create_reminder('x', NOW + timedelta(hours=1), NOW, repeat={**self.rule, **changes})

    def test_editing_frequency_invalidates_old_actions(self):
        first = self.desk.create_reminder('x', NOW + timedelta(hours=1), NOW, repeat=self.rule)
        changed = self.desk.update_reminder_repeat(first['id'], first['revision'], {**self.rule, 'days': [6, 7]}, NOW)
        self.assertEqual(changed['remind_at'], (NOW + timedelta(days=1, hours=1)).isoformat())
        with self.assertRaises(ValueError):
            self.desk.reminder_action(first['id'], 'cancel', NOW, revision=first['revision'])

    def test_edit_accepts_the_same_offline_occurrence_revision_as_the_phone(self):
        from mydesk.reminder_repeat import revision
        first = self.desk.create_reminder('x', NOW + timedelta(hours=1), NOW, repeat=self.rule)
        monday = NOW + timedelta(days=3, hours=1)
        changed = self.desk.update_reminder_repeat(first['id'], revision(first['repeat'], monday), {**self.rule, 'days': [6, 7]}, monday)
        self.assertEqual(changed['repeat']['days'], [6, 7])

    def test_changing_a_paused_rule_does_not_silently_resume_it(self):
        first = self.desk.create_reminder('x', NOW + timedelta(hours=1), NOW, repeat=self.rule)
        paused = self.desk.reminder_action(first['id'], 'pause', NOW, revision=first['revision'])
        changed = self.desk.update_reminder_repeat(first['id'], paused['revision'], {**self.rule, 'days': [6, 7]}, NOW)
        self.assertEqual(changed['status'], 'paused')

    def test_daily_rule_skips_dst_gap_and_does_not_drift(self):
        from mydesk.reminder_repeat import next_time
        rule = dict(days=list(range(1, 8)), hour=2, minute=30, timezone='America/New_York')
        after = datetime(2027, 3, 13, 7, 30, tzinfo=timezone.utc)
        self.assertEqual(next_time(rule, after), datetime(2027, 3, 15, 6, 30, tzinfo=timezone.utc))
