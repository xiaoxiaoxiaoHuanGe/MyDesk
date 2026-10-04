import tempfile
import unittest
import sys
from datetime import datetime,timezone,timedelta
from pathlib import Path
from mydesk.domain import Desk
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))

try:
    from start_local import migrate
except ImportError:
    migrate=None

class MigrationTests(unittest.TestCase):
    def test_ha_business_data_is_copied_and_existing_target_is_preserved(self):
        self.assertIsNotNone(migrate,'Standalone local migration has not been implemented')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            source=root/'.local/homeassistant/.storage/mydesk.sqlite3'
            source.parent.mkdir(parents=True)
            now=datetime.now(timezone.utc)
            desk=Desk(source)
            reminder=desk.create_reminder('迁移保留',now+timedelta(hours=1),now)
            self.assertTrue(migrate(root))
            target=root/'.local/standalone/mydesk.sqlite3'
            self.assertEqual(Desk(target).snapshot(now)['reminders'][0]['id'],reminder['id'])
            self.assertTrue(source.exists())
            self.assertFalse(migrate(root))
            self.assertEqual(len(Desk(target).snapshot(now)['reminders']),1)
