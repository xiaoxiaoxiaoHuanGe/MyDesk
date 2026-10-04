import tempfile,unittest
from datetime import datetime,timezone
from pathlib import Path
from unittest.mock import patch
from aiohttp import ClientSession
from mydesk.domain import Desk
from mydesk.runtime import Runtime


class MailWindowTests(unittest.IsolatedAsyncioTestCase):
    async def test_all_accounts_show_latest_three_by_actual_time_and_keep_individual_windows(self):
        cfg={'gmail_accounts':{
            'work':{'name':'工作','username':'work@gmail.com','password':'private-work','enabled':True,'limit':5},
            'home':{'name':'私人','username':'home@gmail.com','password':'private-home','enabled':True,'limit':5}},'network':{'enabled':False}}
        def poll(spec):
            times=['2026-10-04T01:00:00Z','2026-10-04T12:00:00+08:00','2026-10-04T05:00:00Z','2026-10-04T07:00:00Z'] if spec['name']=='工作' else ['2026-10-04T02:00:00Z','2026-10-04T06:00:00Z','2026-10-04T08:00:00Z']
            return {'unread':2,'items':[{'id':str(i),'subject':str(i),'received_at':time,'sender':'sender','unread':True} for i,time in enumerate(times)]}
        with tempfile.TemporaryDirectory() as directory:
            async with ClientSession() as session:
                rt=Runtime(Desk(Path(directory)/'desk.db'),cfg,session,None)
                with patch('mydesk.runtime.poll_mail',poll):
                    await rt.refresh(datetime.now(timezone.utc))
                feed=(await rt.snapshot())['feeds']['mail']['data']
                self.assertEqual([i['id'] for i in feed['items']],['home:2','work:3','home:1'])
                self.assertEqual(feed['items'][0]['account_email'],'home@gmail.com')
                self.assertEqual(feed['items'][1]['account_name'],'工作')
                self.assertEqual(len(feed['accounts'][0]['items']),4)
                self.assertEqual(len(feed['accounts'][1]['items']),3)
                self.assertEqual(feed['unread'],4)
                self.assertNotIn('private-work',str(feed))
