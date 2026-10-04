"""Manual refresh bypasses provider schedules without dispatching external jobs."""
import asyncio
import unittest
from datetime import timedelta
from unittest.mock import patch

from tests import test_runtime

NOW=test_runtime.NOW


class SyncAllTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await test_runtime.RuntimeTests.asyncSetUp(self)
        self.calls=[]
        self.runtime.config.update({
            'gmail_accounts':{'mail':{'name':'邮箱','username':'test@example.com','enabled':True}},
            'server_sources':{'server':{'name':'服务器','provider':'1panel','enabled':True}},
            'network':{'enabled':True},
        })
        self.runtime.last_github_poll=NOW
        self.runtime.last_mail_poll=NOW
        self.started=asyncio.Event()
        self.release=asyncio.Event()
        self.block=False

        async def checkins(exists,now):
            self.calls.append('github')
            self.started.set()
            if self.block:await self.release.wait()
            return [],{'items':[{'task_id':'github.checkin','name':'签到'}]}
        class Poller:pass
        self.runtime.checkins=Poller()
        self.runtime.checkins.poll=checkins
        async def mail(now):
            self.calls.append('mail')
            return {'accounts':[{'id':'mail','unread':2}], 'items':[], 'unread':2}
        async def servers(now):
            self.calls.append('servers')
            return {'items':[{'id':'server','name':'服务器','status':'up'}]}
        async def network(*args):
            self.calls.append('network')
            return {'online':True,'nodes':[]}
        self.runtime.poll_mail_accounts=mail
        self.runtime.poll_servers=servers
        self.network=patch('mydesk.runtime.poll_network',network)
        self.network.start()
        self.addCleanup(self.network.stop)

    async def asyncTearDown(self):
        await test_runtime.RuntimeTests.asyncTearDown(self)

    async def test_manual_sync_reads_all_providers_inside_cooldown(self):
        result=await self.runtime.command('sync/all',{},NOW+timedelta(seconds=1))
        self.assertCountEqual(self.calls,['github','mail','servers','network'])
        self.assertEqual(result['feeds']['mail']['data']['unread'],2)
        self.assertEqual(result['feeds']['servers']['data']['items'][0]['status'],'up')
        self.calls.clear()
        await self.runtime.refresh(NOW+timedelta(seconds=61))
        self.assertCountEqual(self.calls,['servers','network'])

    async def test_provider_failure_preserves_other_refreshes(self):
        async def fail(now):raise OSError('secret upstream error')
        self.runtime.poll_mail_accounts=fail
        result=await self.runtime.command('sync/all',{},NOW+timedelta(seconds=1))
        self.assertIn('error',result['feeds']['mail']['data'])
        self.assertTrue(result['feeds']['network']['data']['online'])
        self.assertEqual(result['feeds']['servers']['data']['items'][0]['status'],'up')
        self.assertNotIn('secret upstream error',str(result))

    async def test_manual_sync_tracks_existing_step_run_without_dispatch(self):
        job=self.desk.create_job(12345,NOW)
        self.desk.update_job(job['id'],status='queued',run_id=42)
        calls=[]
        class GitHub:
            async def run(self,run_id):
                calls.append(run_id)
                return {'status':'completed','conclusion':'success','html_url':'https://github.com/example/run/42'}
            async def dispatch(self,*args):raise AssertionError('同步不得提交任务')
        self.runtime.github=GitHub()
        result=await self.runtime.command('sync/all',{},NOW)
        self.assertEqual(result['wxstep']['status'],'success')
        self.assertEqual(calls,[42])

    async def test_overlapping_manual_requests_share_one_refresh(self):
        self.block=True
        first=asyncio.create_task(self.runtime.command('sync/all',{},NOW))
        try:
            await asyncio.wait_for(self.started.wait(),1)
            second=asyncio.create_task(self.runtime.command('sync/all',{},NOW))
            await asyncio.sleep(0)
            self.release.set()
            results=await asyncio.gather(first,second)
            self.assertEqual(len(results),2)
            self.assertEqual(self.calls.count('github'),1)
            self.assertEqual(self.calls.count('mail'),1)
        finally:
            self.release.set()
            if not first.done():first.cancel()
            await asyncio.gather(first,return_exceptions=True)
