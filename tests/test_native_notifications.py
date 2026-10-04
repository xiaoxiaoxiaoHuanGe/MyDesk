"""Durable native event outbox: retries, device changes, receipts and state transitions."""
import tempfile
import unittest
from datetime import datetime,timedelta,timezone
from pathlib import Path
from mydesk.domain import Desk
from mydesk.mobile import Mobile,DeviceRemoved
from mydesk.fcm import PushRetry,TokenExpired

try:
    from mydesk.native_notifications import NativeNotifications
except ImportError:NativeNotifications=None

NOW=datetime(2026,10,2,8,tzinfo=timezone.utc)
DEVICE='a'*32


class NativeNotificationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.assertIsNotNone(NativeNotifications,'Native notification outbox is missing')
        self.temp=tempfile.TemporaryDirectory();self.desk=Desk(Path(self.temp.name)/'desk.sqlite3')
        self.mobile=Mobile(self.desk)
        self.mobile.register({'id':DEVICE,'name':'Android','local_alarm':True,'push_token':'token:'+('x'*40),'notifications_enabled':True},'session1')
        self.sent=[];self.failure=None;self.active=True
        parent=self
        class Sender:
            async def send(self,token,data):
                parent.sent.append((token,data))
                if parent.failure:raise parent.failure
                return 'projects/test/messages/accepted'
        self.sender=Sender()
        self.queue=NativeNotifications(self.mobile,self.sender,lambda session: self.active)

    async def asyncTearDown(self):
        if hasattr(self,'temp'):self.temp.cleanup()

    def snapshot(self,status='down'):
        return {'reminders':[{'id':'r1','revision':'v1','title':'本地提醒'}], 'tasks':[], 'feeds':{'servers':{'data':{'items':[{'id':'s1','name':'VPS','status':status}]}}},'attention':[],'wxstep':None}

    async def test_repeated_state_and_restart_do_not_resubmit_and_submit_is_not_receipt(self):
        self.queue.capture(self.snapshot(),NOW)
        await self.queue.drain(NOW)
        self.queue=NativeNotifications(self.mobile,self.sender,lambda session: True)
        self.queue.capture(self.snapshot(),NOW+timedelta(seconds=20))
        await self.queue.drain(NOW+timedelta(seconds=20))
        self.assertEqual(len(self.sent),1)
        payload=self.sent[0][1]
        self.assertEqual(payload['kind'],'alert')
        self.assertIn('server_id',payload)
        status=self.queue.status()['deliveries'][0]
        self.assertIsNotNone(status['submitted_at'])
        self.assertIsNone(status['received_at'])
        self.assertIsNone(status['display_requested_at'])

    async def test_failure_backoff_and_removed_or_revoked_device_never_sends(self):
        self.queue.capture(self.snapshot(),NOW)
        self.failure=PushRetry(retry_after=120)
        await self.queue.drain(NOW)
        self.failure=None
        await self.queue.drain(NOW+timedelta(seconds=30))
        self.assertEqual(len(self.sent),1)
        self.mobile.remove(device_id=DEVICE)
        await self.queue.drain(NOW+timedelta(seconds=121))
        self.assertEqual(len(self.sent),1)

    async def test_inactive_session_and_disabled_notifications_are_not_targets(self):
        self.active=False
        self.queue.capture(self.snapshot(),NOW)
        await self.queue.drain(NOW)
        self.assertEqual(self.sent,[])
        self.active=True
        self.mobile.register({'id':DEVICE,'name':'Android','notifications_enabled':False},'session1')
        self.queue.capture(self.snapshot('up'),NOW)
        self.queue.capture(self.snapshot('down'),NOW+timedelta(seconds=1))
        await self.queue.drain(NOW+timedelta(seconds=1))
        self.assertEqual(self.sent,[])

    async def test_reregistering_device_in_another_session_cannot_inherit_old_queue_or_receipts(self):
        self.queue.capture(self.snapshot(),NOW)
        event=self.queue.status()['deliveries'][0]['event_id']
        self.mobile.remove(session_id='session1')
        self.mobile.register({'id':DEVICE,'name':'Android','push_token':'new:'+('y'*40),'notifications_enabled':True},'session2')
        await self.queue.drain(NOW)
        self.assertEqual(self.sent,[])
        with self.assertRaises(ValueError):
            self.queue.receipt({'device_id':DEVICE,'event_id':event,'phase':'received'},'session2',NOW)

    async def test_receipt_has_device_scope_and_does_not_regress_on_duplicate(self):
        self.queue.capture(self.snapshot(),NOW);await self.queue.drain(NOW)
        event=self.sent[0][1]['event_id']
        data={'device_id':DEVICE,'event_id':event,'phase':'display_requested'}
        self.queue.receipt(data,'session1',NOW+timedelta(seconds=3))
        self.queue.receipt({**data,'phase':'received'},'session1',NOW+timedelta(seconds=4))
        result=self.queue.status()['deliveries'][0]
        self.assertIsNotNone(result['received_at'])
        self.assertIsNotNone(result['display_requested_at'])
        self.assertNotIn('token:',str(result))
        with self.assertRaises(DeviceRemoved):self.queue.receipt(data,'other-session',NOW)
        with self.assertRaises(ValueError):self.queue.receipt({**data,'event_id':'b'*32},'session1',NOW)

    async def test_token_rotation_retries_unreceived_message_without_repeating_received(self):
        self.queue.capture(self.snapshot(),NOW);await self.queue.drain(NOW)
        self.mobile.register({'id':DEVICE,'name':'Android','push_token':'new:'+('y'*40)},'session1')
        await self.queue.drain(NOW+timedelta(seconds=1))
        self.assertEqual(len(self.sent),2)
        self.assertEqual(self.sent[1][0],'new:'+('y'*40))
        event=self.sent[0][1]['event_id']
        self.queue.receipt({'device_id':DEVICE,'event_id':event,'phase':'received'},'session1',NOW+timedelta(seconds=2))
        self.mobile.register({'id':DEVICE,'name':'Android','push_token':'third:'+('z'*40)},'session1')
        await self.queue.drain(NOW+timedelta(seconds=3))
        self.assertEqual(len(self.sent),2)

    async def test_recovery_then_a_new_failure_and_new_task_execution_create_new_events(self):
        self.queue.capture(self.snapshot(),NOW);await self.queue.drain(NOW)
        self.queue.capture(self.snapshot('up'),NOW+timedelta(seconds=1))
        self.queue.capture(self.snapshot('down'),NOW+timedelta(seconds=2));await self.queue.drain(NOW+timedelta(seconds=2))
        state=self.snapshot('up')
        state['tasks']=[{'task_id':'backup','task_name':'备份','status':'success','event_id':'run1','message':'完成'}]
        self.queue.capture(state,NOW+timedelta(seconds=3));await self.queue.drain(NOW+timedelta(seconds=3))
        state['tasks'][0]['event_id']='run2'
        self.queue.capture(state,NOW+timedelta(seconds=4));await self.queue.drain(NOW+timedelta(seconds=4))
        self.assertEqual(len(self.sent),4)
        self.assertEqual(len({data['event_id'] for _,data in self.sent}),4)
        self.assertEqual(self.sent[-1][1]['channel'],'tasks')

    async def test_expired_event_and_unregistered_token_are_not_retried(self):
        self.queue.capture(self.snapshot(),NOW)
        await self.queue.drain(NOW+timedelta(hours=2))
        self.assertEqual(self.sent,[])
        self.queue.capture(self.snapshot('up'),NOW+timedelta(hours=2))
        self.queue.capture(self.snapshot('down'),NOW+timedelta(hours=2,seconds=1))
        self.failure=TokenExpired()
        await self.queue.drain(NOW+timedelta(hours=2,seconds=1))
        self.assertFalse(self.mobile.devices()[0]['push_registered'])

    async def test_old_submitted_rows_cannot_starve_a_new_queued_event(self):
        for count in range(101):
            self.queue.test(DEVICE,NOW+timedelta(seconds=count))
            await self.queue.drain(NOW+timedelta(seconds=count))
        self.assertEqual(len(self.sent),101)

    async def test_reminder_changes_request_silent_sync_and_due_event_occurs_once(self):
        value={'reminders':[{'id':'r1','title':'提醒','status':'pending','revision':'v1','remind_at':(NOW+timedelta(minutes=10)).isoformat()}]}
        self.queue.capture(value,NOW);await self.queue.drain(NOW)
        self.assertEqual(self.sent,[])
        value['reminders'][0]['revision']='v2'
        self.queue.capture(value,NOW+timedelta(seconds=1));await self.queue.drain(NOW+timedelta(seconds=1))
        self.assertEqual([data['kind'] for _,data in self.sent],['sync'])
        # A real reminder is needed to validate the authoritative current revision before sending.
        reminder=self.desk.create_reminder('真实提醒',NOW+timedelta(minutes=10),NOW)
        value={'reminders':[reminder]}
        self.queue.capture(value,NOW+timedelta(minutes=11));await self.queue.drain(NOW+timedelta(minutes=11))
        self.queue.capture(value,NOW+timedelta(minutes=12));await self.queue.drain(NOW+timedelta(minutes=12))
        due=[data for _,data in self.sent if data['kind']=='reminder']
        self.assertEqual(len(due),1)
        self.assertEqual(due[0]['reference'],reminder['id'])
        self.assertEqual(due[0]['revision'],reminder['revision'])

    async def test_completion_before_remote_submission_suppresses_outdated_reminder(self):
        reminder=self.desk.create_reminder('到期提醒',NOW-timedelta(seconds=1),NOW-timedelta(minutes=1))
        self.queue.capture({'reminders':[reminder]},NOW)
        self.assertEqual([row['kind'] for row in self.queue.status()['deliveries']],['reminder'])
        self.desk.reminder_action(reminder['id'],'complete',NOW,revision=reminder['revision'])
        await self.queue.drain(NOW)
        self.assertEqual(self.sent,[])
        self.assertIsNone(self.queue.status()['deliveries'][0]['received_at'])
