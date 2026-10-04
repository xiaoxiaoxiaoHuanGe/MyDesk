import tempfile,unittest
from pathlib import Path
from datetime import datetime,timedelta,timezone
from unittest.mock import AsyncMock,patch
from aiohttp import ClientSession
from aiohttp.test_utils import TestServer
from aiohttp import web
from mydesk.domain import Desk
from mydesk.runtime import Runtime
try:
    from mydesk.daily_quote import poll_quote
except ImportError:
    poll_quote=None

NOW=datetime(2026,10,4,15,59,tzinfo=timezone.utc)
QUOTE={'text':'愿你走出半生，归来仍是少年。','source':'网络','author':'','url':'https://hitokoto.cn/'}

class DailyQuoteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.desk=Desk(Path(self.tmp.name)/'desk.db')
        self.session=ClientSession()
        self.runtime=Runtime(self.desk,{'timezone':'Asia/Shanghai','network':{'enabled':False}},self.session,AsyncMock())
    async def asyncTearDown(self):
        await self.session.close();self.tmp.cleanup()
    def supported(self):
        self.assertTrue(hasattr(self.runtime,'refresh_quote'),'Daily quote caching is missing')
    async def test_one_quote_per_local_day_survives_restart_and_manual_sync(self):
        self.supported()
        with patch('mydesk.runtime.poll_quote',AsyncMock(return_value=QUOTE)) as fetch:
            await self.runtime.refresh_quote(NOW)
            await self.runtime.refresh_quote(NOW+timedelta(seconds=30))
            restarted=Runtime(self.desk,self.runtime.config,self.session,AsyncMock())
            await restarted.refresh_quote(NOW+timedelta(seconds=30))
            await restarted.refresh_all(NOW+timedelta(seconds=30))
            self.assertEqual(fetch.await_count,1)
            self.assertEqual((await restarted.snapshot(NOW))['daily_quote']['text'],QUOTE['text'])
            await restarted.refresh_quote(NOW+timedelta(minutes=2))
            self.assertEqual(fetch.await_count,2)
    async def test_failure_preserves_previous_quote_and_waits_before_retry(self):
        self.supported()
        self.desk.set_feed('daily_quote',{**QUOTE,'date':'2026-10-03'},NOW)
        with patch('mydesk.runtime.poll_quote',AsyncMock(side_effect=ValueError('provider unavailable'))) as fetch:
            await self.runtime.refresh_quote(NOW)
            await self.runtime.refresh_quote(NOW+timedelta(minutes=1))
            state=await self.runtime.snapshot(NOW)
            self.assertEqual(state['daily_quote']['text'],QUOTE['text'])
            self.assertFalse(any(row['id']=='daily_quote' for row in state['attention']))
            self.assertEqual(fetch.await_count,1)
            await self.runtime.refresh_quote(NOW+timedelta(minutes=31))
            self.assertEqual(fetch.await_count,2)
    async def test_no_cache_failure_keeps_a_useful_fallback(self):
        self.supported()
        with patch('mydesk.runtime.poll_quote',AsyncMock(side_effect=ValueError())):
            await self.runtime.refresh_quote(NOW)
        state=await self.runtime.snapshot(NOW)
        self.assertEqual(state['daily_quote']['text'],'把今天的事情，安静地安排好。')
        self.assertNotIn('retry_at',state['daily_quote'])
    async def test_provider_validates_text_and_builds_only_a_trusted_source_link(self):
        self.assertIsNotNone(poll_quote,'Quote provider is missing')
        response={'hitokoto':'  每一段路，都有它的风景。  ','from':'散文','from_who':None,'uuid':'not-a-uuid'}
        async def handler(request):return web.json_response(response)
        app=web.Application();app.router.add_get('/',handler)
        server=TestServer(app);await server.start_server()
        try:
            result=await poll_quote(self.session,str(server.make_url('/')))
            self.assertEqual(result['text'],'每一段路，都有它的风景。')
            self.assertEqual(result['url'],'https://hitokoto.cn/')
            response['hitokoto']='x'*100
            with self.assertRaises(ValueError):await poll_quote(self.session,str(server.make_url('/')))
        finally:await server.close()
