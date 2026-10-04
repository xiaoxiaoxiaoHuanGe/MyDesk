import tempfile
import unittest
from pathlib import Path
from datetime import datetime,timezone,timedelta
from aiohttp import ClientSession
from mydesk.domain import Desk
from mydesk.runtime import Runtime

NOW=datetime(2026,10,2,8,tzinfo=timezone.utc)

class TaskHistoryTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.desk=Desk(Path(self.temp.name)/'desk.sqlite3')
        self.http=ClientSession()
        self.runtime=Runtime(self.desk,{},self.http,lambda *args:None)
        for i,task in enumerate(('backup','mail','backup','mail','backup')):
            self.desk.report_task(dict(task_id=task,task_name=task,status='success',message=str(i),timestamp=NOW.isoformat(),source='test',event_id=str(i)),NOW)
    async def asyncTearDown(self):
        await self.http.close()
        self.temp.cleanup()
    async def test_selected_task_pages_keep_equal_timestamp_order_without_other_tasks(self):
        page=await self.runtime.command('history',{'limit':2,'task_id':'backup'},NOW)
        self.assertEqual([row['message'] for row in page],['4','2'])
        older=await self.runtime.command('history',{'limit':2,'task_id':'backup','before':page[-1]['id']},NOW)
        self.assertEqual([row['message'] for row in older],['0'])
        self.assertEqual({row['task_id'] for row in page+older},{'backup'})
        self.assertEqual(len(await self.runtime.command('history',{'limit':100},NOW)),5)
    async def test_foreign_cursor_and_invalid_task_filter_are_rejected(self):
        foreign=next(row['id'] for row in self.desk.task_history() if row['task_id']=='mail')
        for payload in ({'task_id':'backup','before':foreign},{'task_id':''},{'task_id':'a'*101},{'task_id':'bad/id'},{'task_id':123}):
            with self.assertRaises(ValueError):await self.runtime.command('history',payload,NOW)
