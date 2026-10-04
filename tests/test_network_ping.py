import asyncio
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch

from mydesk.providers import ping_node
from mydesk.domain import Desk


class PingDetectionTests(unittest.IsolatedAsyncioTestCase):
    async def run_ping(self, code, stdout=b'', stderr=b''):
        process = AsyncMock()
        process.returncode = code
        process.communicate.return_value = (stdout, stderr)
        with patch('mydesk.providers.asyncio.create_subprocess_exec', return_value=process):
            return await ping_node('github', 'www.github.com')

    async def test_permission_failure_is_unknown_instead_of_unreachable(self):
        result = await self.run_ping(2, stderr=b'ping: socket: Operation not permitted\nping: missing cap_net_raw+p capability or setuid?')
        self.assertIsNone(result['online'])
        self.assertIn('权限', result['error'])
        self.assertIsNone(result['ping'])

    async def test_packet_loss_remains_unreachable(self):
        result = await self.run_ping(1, stdout=b'1 packets transmitted, 0 received, 100% packet loss')
        self.assertIs(result['online'], False)
        self.assertIn('Ping', result.get('error', ''))

    async def test_dns_failure_is_a_detection_error(self):
        result = await self.run_ping(2, stderr=b'ping: www.github.com: Name or service not known')
        self.assertIsNone(result['online'])
        self.assertIn('解析', result['error'])

    async def test_success_keeps_measured_round_trip(self):
        result = await self.run_ping(0, stdout=b'64 bytes from 1.2.3.4: icmp_seq=1 ttl=57 time=12.34 ms')
        self.assertIs(result['online'], True)
        self.assertEqual(result['ping'], 12.34)
        self.assertNotIn('error', result)

    async def test_missing_ping_keeps_the_node_with_actionable_error(self):
        with patch('mydesk.providers.asyncio.create_subprocess_exec', side_effect=FileNotFoundError):
            try:
                result = await ping_node('github', 'www.github.com')
            except FileNotFoundError:
                self.fail('Missing ping must keep an actionable per-node error')
        self.assertIsNone(result['online'])
        self.assertIn('工具', result['error'])

    def test_permission_error_appears_in_attention_with_correct_cause(self):
        now = datetime.now(timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            desk = Desk(Path(directory) / 'desk.sqlite3')
            desk.set_feed('network', {'online': True, 'nodes': [
                {'name': 'github', 'host': 'www.github.com', 'online': None,
                 'ping': None, 'error': 'Ping 检测权限不足'}]}, now)
            state = desk.snapshot(now)
        self.assertEqual(len(state['attention']), 1)
        self.assertEqual(state['attention'][0]['message'], 'Ping 检测权限不足')
