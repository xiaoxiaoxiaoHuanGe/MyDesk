import json,tempfile,unittest
from pathlib import Path
from mydesk.github_checkins import SOURCES
from mydesk.config import Settings

class PublicDefaultsTests(unittest.TestCase):
    def test_repository_presets_require_the_users_own_account(self):
        for spec in SOURCES.values():
            self.assertEqual(spec['owner'],'','A public preset must not connect to the author account')

    def test_incomplete_legacy_preset_is_paused_without_losing_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'settings.json'
            path.write_text(json.dumps({'github_checkins':{'sources':['glados'],'token':'fixture-token'}}))
            task=Settings(path).value['github_tasks']['glados']
            self.assertFalse(task['enabled'],'An incomplete migration must not poll an author repository')
            self.assertEqual(task['token'],'fixture-token')
