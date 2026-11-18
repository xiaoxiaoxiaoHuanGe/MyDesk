import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer
from mydesk.app import create_app


class XDigestTests(unittest.IsolatedAsyncioTestCase):
    async def test_authenticated_ingest_search_paging_and_reader_state(self):
        token = 'a' * 64
        with tempfile.TemporaryDirectory() as tmp, patch.dict('os.environ', {'MYDESK_X_DIGEST_TOKEN': token}):
            async with TestClient(TestServer(create_app(tmp, scheduling=False))) as client:
                root = '/api/x-digest'
                headers = {'Authorization': 'Bearer ' + token}
                post = dict(id='123', category='ai', author='reader', text='原文 Python',
                            summary='中文摘要', posted_at='2026-11-17T12:00:00Z',
                            likes=1100, images=['https://pbs.twimg.com/media/one.jpg'])
                batch = dict(id='edition1', collected_at='2026-11-18T01:00:00Z',
                             summary='本期简报', trends=['AI'], posts=[post, {**post, 'id': '124'}])
                self.assertEqual((await client.get(root+'/feed')).status, 401)
                self.assertEqual((await client.post(root+'/ingest', json=batch)).status, 401)
                self.assertEqual((await client.post(root+'/ingest', json=batch, headers=headers)).status, 200)
                self.assertEqual((await client.post(root+'/ingest', json={**batch, 'posts': [{**post, 'text': '', 'likes': 1200}]}, headers=headers)).status, 200)
                invalid = {**batch, 'posts': [{**post, 'images': ['https://pbs.twimg.com.evil.example/media/x']}]}
                self.assertEqual((await client.post(root+'/ingest', json=invalid, headers=headers)).status, 400)
                access = json.loads((Path(tmp)/'local-access.json').read_text())
                login = await (await client.post('/api/login', json=access)).json()
                csrf = {'X-MyDesk-CSRF': login['csrf']}
                page = await (await client.get(root+'/feed', params={'q': 'Python', 'limit': '1'})).json()
                self.assertEqual(len(page['items']), 1)
                after = await (await client.get(root+'/feed', params={'q': 'Python', 'limit': '1', 'before': page['next']})).json()
                self.assertNotEqual(page['items'][0]['id'], after['items'][0]['id'])
                self.assertIsNone(after['next'])
                self.assertEqual((await client.post(root+'/posts/123/read')).status, 403)
                self.assertEqual((await client.post(root+'/posts/123/read', headers=csrf)).status, 200)
                self.assertEqual((await client.post(root+'/posts/123/bookmark', json={'saved': True}, headers=csrf)).status, 200)
                saved = await (await client.get(root+'/feed?bookmarks=1')).json()
                self.assertEqual(saved['unread'], 1)
                self.assertEqual([p['id'] for p in saved['items']], ['123'])
                detail = await (await client.get(root+'/posts/123')).json()
                self.assertEqual((detail['text'], detail['likes'], detail['read']), ('原文 Python', 1200, True))
                editions = await (await client.get(root+'/briefings')).json()
                self.assertEqual([e['summary'] for e in editions['items']], ['本期简报'])
