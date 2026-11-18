import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer
from mydesk.app import create_app
import base64
import hashlib


class XDigestTests(unittest.IsolatedAsyncioTestCase):
    async def test_archived_image_is_private_and_empty_replay_keeps_translation(self):
        token = 'a' * 64
        image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD/0AAAAASUVORK5CYII=')
        digest = hashlib.sha256(image).hexdigest()
        root = '/api/x-digest'
        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'image/png'}
        with tempfile.TemporaryDirectory() as tmp, patch.dict('os.environ', {'MYDESK_X_DIGEST_TOKEN': token}):
            async with TestClient(TestServer(create_app(tmp, scheduling=False))) as client:
                route = root+'/media/'+digest
                self.assertEqual((await client.put(route, data=image)).status, 401)
                self.assertEqual((await client.put(route, data=b'not a png', headers=headers)).status, 400)
                self.assertEqual((await client.put(route, data=image, headers=headers)).status, 201)
                self.assertEqual((await client.put(route, data=image, headers=headers)).status, 200)
                self.assertEqual((await client.get(route)).status, 401)
                source = 'https://pbs.twimg.com/media/photo.png'
                batch = dict(id='edition1', collected_at='2026-11-18T01:00:00Z', posts=[
                    dict(id='123', category='ai', author='reader', text='Hello world', translation='你好，世界',
                         posted_at='2026-11-17T12:00:00Z', likes=1000, images=[source], media={source:digest})])
                bearer = {'Authorization': 'Bearer ' + token}
                self.assertEqual((await client.post(root+'/ingest', json=batch, headers=bearer)).status, 200)
                status = await client.post(root+'/status', json={'ids':['123']}, headers=bearer)
                self.assertEqual(await status.json(), {'translated':['123'],'media':{source:digest}})
                batch['posts'][0].pop('media')
                batch['posts'][0]['translation'] = ''
                self.assertEqual((await client.post(root+'/ingest', json=batch, headers=bearer)).status, 200)
                access = json.loads((Path(tmp)/'local-access.json').read_text())
                self.assertEqual((await client.post('/api/login', json=access)).status, 200)
                post = await (await client.get(root+'/posts/123')).json()
                self.assertEqual(post['media'], {source:route})
                self.assertEqual(post['translation'], '你好，世界')
                self.assertEqual((await (await client.get(root+'/feed?q=你好')).json())['items'][0]['id'], '123')
                response = await client.get(route)
                self.assertEqual((response.status, response.content_type, await response.read()), (200, 'image/png', image))

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
