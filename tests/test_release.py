import tempfile
import unittest
import zipfile
import sys
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from build_release import build


class ReleaseTests(unittest.TestCase):
    def test_release_includes_redistribution_notices_and_project_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            with zipfile.ZipFile(build(Path(directory)/'release.zip')) as package:
                for relative in ('LICENSE','THIRD_PARTY_NOTICES.md','SECURITY.md','CONTRIBUTING.md','assets/mydesk-logo.png'):
                    self.assertIn('mydesk/'+relative,package.namelist())

    def test_android_generated_header_asset_is_in_source_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            with zipfile.ZipFile(build(Path(directory)/'release.zip')) as package:
                name='mydesk/android/app/src/main/res/drawable-nodpi/mydesk_paper_header.png'
                self.assertIn(name,package.namelist())
                asset=Path(__file__).resolve().parents[1]/'android/app/src/main/res/drawable-nodpi/mydesk_paper_header.png'
                self.assertEqual(asset.read_bytes(),package.read(name))

    def test_release_contains_runtime_and_frontend_but_never_private_state(self):
        with tempfile.TemporaryDirectory() as directory:
            archive=build(Path(directory)/'release.zip')
            self.assertIsNotNone(archive)
            with zipfile.ZipFile(archive) as package:
                names=set(package.namelist())
                self.assertIn('mydesk/mydesk/app.py',names)
                self.assertIn('mydesk/frontend/mydesk-card.js',names)
                self.assertIn('mydesk/frontend/index.html',names)
                self.assertIn('mydesk/deploy/Dockerfile',names)
                self.assertFalse(any('/custom_components/' in name or '/homeassistant/' in name for name in names))
                self.assertIn('mydesk/docs/DEPLOY.md',names)
                self.assertIn('mydesk/deploy/compose.local.yaml',names)
                self.assertIn('mydesk/scripts/Start-DockerLocal.ps1',names)
                self.assertIn('mydesk/Build-OfficialRelease.cmd',names)
                self.assertIn('mydesk/scripts/Build-OfficialReleaseWizard.ps1',names)
                self.assertIn('mydesk/android/app/src/main/java/app/mydesk/android/MainActivity.kt',names)
                self.assertIn('mydesk/android/gradle/wrapper/gradle-wrapper.jar',names)
                self.assertFalse(any(name.endswith(('local.properties','google-services.json','mydesk_local_ca.crt','.keystore')) or '/build/' in name or '/.gradle/' in name for name in names))
                self.assertFalse(any('/.local/' in name or '/.venv/' in name or '/.storage/' in name or name.endswith('/secrets.yaml') for name in names))
