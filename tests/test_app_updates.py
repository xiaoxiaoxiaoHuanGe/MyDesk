import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from aiohttp.test_utils import TestClient,TestServer
from mydesk.app import create_app
from mydesk.app_updates import AppUpdates,validate_manifest
from scripts.prepare_android_update import prepare

META=dict(package_name='app.mydesk.android',version_code=9,version_name='1.3.0',min_sdk=26,certificate_sha256='c'*64)
class UpdateRegistryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.apk=self.root/'signed.apk';self.apk.write_bytes(b'test-signed-apk')
        self.output=self.root/'updates';self.registry=AppUpdates(self.output)
    def publish(self):
        with patch('scripts.prepare_android_update.apk_metadata',return_value=META):
            return prepare(self.apk,self.output,'debug','测试更新','c'*64,'aapt','apksigner')
    def test_no_release_relabel_or_traversal_and_immutable_old_download(self):
        self.assertEqual(self.registry.latest('release')['available'],False)
        first=self.publish();self.assertTrue(self.registry.latest('debug')['available'])
        self.apk.write_bytes(b'new-signed-apk');second=self.publish()
        self.assertNotEqual(first['artifact_id'],second['artifact_id'])
        self.assertEqual(self.registry.artifact(first['artifact_id']).read_bytes(),b'test-signed-apk')
        for key in ['../signed','a'*63,'A'*64]:
            with self.assertRaises(FileNotFoundError):self.registry.artifact(key)
    def test_bad_metadata_size_channel_and_partial_upload(self):
        first=self.publish()
        for change in [dict(version_code=True),dict(size_bytes=0),dict(min_sdk=25),dict(sha256='invalid'),dict(package_name='elsewhere'),dict(channel='release'),dict(schema=True)]:
            with self.assertRaises(ValueError):validate_manifest({**first,**change},'debug')
        self.registry.artifact(first['artifact_id']).write_bytes(b'truncated')
        with self.assertRaises(ValueError):self.registry.latest('debug')
    def test_failed_publish_preserves_pointer(self):
        first=self.publish();self.apk.write_bytes(b'new')
        from scripts import prepare_android_update
        replace=prepare_android_update.os.replace
        def fail_pointer(src,dest):
            if Path(dest)==self.output/'debug.json':raise OSError('simulated disk failure')
            return replace(src,dest)
        with patch('scripts.prepare_android_update.apk_metadata',return_value=META),patch('scripts.prepare_android_update.os.replace',side_effect=fail_pointer):
            with self.assertRaises(OSError):prepare(self.apk,self.output,'debug','new','c'*64,'aapt','apksigner')
        self.assertEqual(self.registry.latest('debug')['artifact_id'],first['artifact_id'])
    def test_real_tool_parser_checks_signature_not_operator_version(self):
        from scripts.prepare_android_update import apk_metadata
        from subprocess import CompletedProcess
        badging="package: name='app.mydesk.android' versionCode='12' versionName='1.3.3'\nsdkVersion:'26'\n"
        signer='Signer #1 certificate SHA-256 digest: '+'c'*64
        with patch('scripts.prepare_android_update.subprocess.run',side_effect=[CompletedProcess([],0,badging),CompletedProcess([],0,signer)]):
            self.assertEqual(apk_metadata(self.apk,'aapt','apksigner','c'*64)['version_code'],12)
        with patch('scripts.prepare_android_update.subprocess.run',side_effect=[CompletedProcess([],0,badging),CompletedProcess([],0,signer)]):
            with self.assertRaises(ValueError):apk_metadata(self.apk,'aapt','apksigner','d'*64)

class UpdatesApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.app=create_app(self.root,scheduling=False);self.client=TestClient(TestServer(self.app));await self.client.start_server()
    async def asyncTearDown(self):await self.client.close();self.tmp.cleanup()
    async def test_authenticated_manifest_and_download(self):
        apk=self.root/'test.apk';apk.write_bytes(b'fake-apk')
        with patch('scripts.prepare_android_update.apk_metadata',return_value=META):
            info=prepare(apk,self.root/'app-updates','debug','测试','c'*64,'aapt','apksigner')
        path='/api/app-update/artifacts/'+info['artifact_id']+'.apk'
        for endpoint in ['/api/app-update',path]:
            r=await self.client.get(endpoint);self.assertEqual(r.status,401)
        access=json.loads((self.root/'local-access.json').read_text());await self.client.post('/api/login',json=access)
        r=await self.client.get('/api/app-update');self.assertFalse((await r.json())['available'])
        r=await self.client.get('/api/app-update?channel=debug');self.assertEqual((await r.json())['version_code'],9)
        r=await self.client.get(path);self.assertEqual(r.status,200);self.assertEqual(await r.read(),b'fake-apk')
        r=await self.client.get('/api/app-update/artifacts/'+('a'*64)+'.apk');self.assertEqual(r.status,404)
