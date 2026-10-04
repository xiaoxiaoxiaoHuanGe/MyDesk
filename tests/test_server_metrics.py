import unittest

from aiohttp import ClientSession, web

from mydesk.providers import OnePanel


class OnePanelNetworkTests(unittest.IsolatedAsyncioTestCase):
    async def test_counters_preserve_zero_and_partial_data_without_inventing_missing_values(self):
        data = dict(cpuUsedPercent=10, memoryUsedPercent=20, diskData=[])

        async def respond(request):
            return web.json_response({'code': 200, 'data': data})

        app = web.Application()
        app.router.add_get('/api/v2/dashboard/current/all/all', respond)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, '127.0.0.1', 0)
        await site.start()
        url = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])
        try:
            async with ClientSession() as session:
                panel = OnePanel(session, {'url': url, 'api_key': 'fixture-key'})
                cases = [
                    ({}, (None, None)),
                    ({'netBytesSent': 0, 'netBytesRecv': 0}, (0, 0)),
                    ({'netBytesSent': True, 'netBytesRecv': -1}, (None, None)),
                    ({'netBytesSent': '100', 'netBytesRecv': 1.5}, (None, None)),
                    ({'netBytesSent': 17090330372}, (17090330372, None)),
                ]
                for counters, expected in cases:
                    with self.subTest(counters=counters):
                        data.clear()
                        data.update(cpuUsedPercent=10, memoryUsedPercent=20, diskData=[],
                                    gpuData=[{'temperature': '60'}], **counters)
                        result = await panel.poll()
                        self.assertEqual(result['network'], dict(sent_bytes=expected[0], received_bytes=expected[1]))
                        # GPU temperature is not a CPU/system temperature reading.
                        self.assertIsNone(result['temperature'])
        finally:
            await runner.cleanup()
