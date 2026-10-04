import json,tempfile,unittest
from datetime import datetime,timezone
from pathlib import Path
from aiohttp.test_utils import TestClient,TestServer
from mydesk.app import create_app,STATE

class BackupApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.app=create_app(self.root,scheduling=False)
        self.client=TestClient(TestServer(self.app));await self.client.start_server()
        self.access=json.loads((self.root/'local-access.json').read_text())
    async def asyncTearDown(self):
        await self.client.close();self.tmp.cleanup()
    async def login(self):
        r=await self.client.post('/api/login',json=self.access)
        self.csrf=(await r.json())['csrf'];return {'X-MyDesk-CSRF':self.csrf}
    async def test_backup_requires_session_and_csrf(self):
        r=await self.client.post('/api/backup/export',json={'password':'long-backup-password'})
        self.assertEqual(r.status,401)
        await self.login()
        r=await self.client.post('/api/backup/export',json={'password':'long-backup-password'})
        self.assertEqual(r.status,403)
    async def test_export_preview_apply_is_authenticated_and_single_use(self):
        headers=await self.login()
        await self.client.put('/api/settings',headers=headers,json={'history_days':42})
        r=await self.client.post('/api/backup/export',headers=headers,json={'password':'long-backup-password','appearance':'light'})
        self.assertEqual(r.status,200)
        blob=(await r.json())['file']
        await self.client.put('/api/settings',headers=headers,json={'history_days':10})
        r=await self.client.post('/api/backup/preview',headers=headers,json={'file':blob,'password':'long-backup-password','mode':'replace'})
        self.assertEqual(r.status,200);preview=await r.json()
        self.assertEqual(self.app[STATE]['settings'].value['history_days'],10)
        r=await self.client.post('/api/backup/apply',headers=headers,json={'preview_id':preview['preview_id'],'confirmed':True})
        self.assertEqual(r.status,200)
        self.assertEqual(self.app[STATE]['settings'].value['history_days'],42)
        self.assertEqual(self.app[STATE]['runtime'].config['history_days'],42)
        r=await self.client.post('/api/backup/apply',headers=headers,json={'preview_id':preview['preview_id'],'confirmed':True})
        self.assertEqual(r.status,400)
    async def test_unsupported_or_wrong_password_does_not_apply(self):
        headers=await self.login()
        r=await self.client.post('/api/backup/preview',headers=headers,json={'file':{},'password':'long-backup-password','mode':'merge'})
        self.assertEqual(r.status,400)
        self.assertEqual(self.app[STATE]['settings'].value['history_days'],90)

    async def test_restore_cannot_remove_a_tracked_steps_workflow(self):
        headers=await self.login()
        r=await self.client.post('/api/backup/export',headers=headers,json={'password':'long-backup-password'})
        blob=(await r.json())['file']
        await self.client.put('/api/settings',headers=headers,json={'github':{'owner':'o','repo':'steps','workflow':'steps.yml','ref':'main','token':'test-token'}})
        rt=self.app[STATE]['runtime']
        await rt.db('create_job',1000,datetime.now(timezone.utc))
        r=await self.client.post('/api/backup/preview',headers=headers,json={'file':blob,'password':'long-backup-password','mode':'replace'})
        preview=await r.json()
        r=await self.client.post('/api/backup/apply',headers=headers,json={'preview_id':preview['preview_id'],'confirmed':True})
        self.assertEqual(r.status,409)
        self.assertEqual(self.app[STATE]['settings'].value['github']['repo'],'steps')
        self.assertFalse((self.root/'config-backups').exists())

    async def test_backup_route_has_a_bounded_larger_body_limit(self):
        headers=await self.login()
        r=await self.client.post('/api/backup/export',headers=headers,json={'password':'long-backup-password','padding':'x'*70000})
        self.assertEqual(r.status,200)
        r=await self.client.post('/api/backup/export',headers=headers,json={'password':'long-backup-password','padding':'x'*270000})
        self.assertEqual(r.status,413)
