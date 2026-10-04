import unittest
import re
from pathlib import Path
import yaml

ROOT=Path(__file__).resolve().parents[1]

class DeploymentTests(unittest.TestCase):
    def test_independent_container_has_only_mydesk_runtime(self):
        compose=yaml.safe_load((ROOT/'deploy/compose.local.yaml').read_text(encoding='utf-8'))
        self.assertIn('mydesk',compose['services'],'Local deployment still depends on HA')
        self.assertNotIn('homeassistant',compose['services'])
        service=compose['services']['mydesk']
        self.assertEqual(service['restart'],'unless-stopped')
        self.assertTrue(all(port.startswith('127.0.0.1:') for port in service['ports']))
        self.assertEqual(service['logging']['options']['max-file'],'3')
        self.assertNotIn('privileged',service)
        self.assertIn('../.local/standalone:/data',service['volumes'])

    def test_release_runtime_does_not_import_home_assistant(self):
        for file in (ROOT/'mydesk').glob('*.py'):
            text=file.read_text(encoding='utf-8')
            self.assertNotIn('from homeassistant',text)
            self.assertNotIn('import homeassistant',text)
        dockerfile=(ROOT/'deploy/Dockerfile').read_text(encoding='utf-8')
        self.assertIn('mydesk',dockerfile)
        self.assertNotIn('home-assistant',dockerfile)

    def test_browser_never_persists_service_credentials_or_uses_ha_interface(self):
        for file in (ROOT/'frontend').glob('*.js'):
            text=file.read_text(encoding='utf-8')
            # Only the non-sensitive appearance enum may survive a reload.
            storage=re.findall(r"localStorage\.(?:getItem|setItem)\(\s*(['\"])(.*?)\1",text)
            self.assertEqual(text.count('localStorage.'),len(storage),'Storage keys must be explicit and allowlisted')
            self.assertTrue(all(key=='mydesk-appearance' for _,key in storage),'Service credentials must never enter browser storage')
            self.assertNotIn('sessionStorage',text)
            self.assertNotIn('callWS',text)
            self.assertNotIn('set hass',text)
