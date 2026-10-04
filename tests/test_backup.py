import copy,json,tempfile,unittest
from pathlib import Path
from mydesk.config import Settings
try:
    from mydesk.backup import Backups
except ImportError:
    Backups=None

class BackupTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(Backups, 'Encrypted configuration backup has not been implemented')
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.settings=Settings(self.root/'settings.json')
        self.settings.update({'github':{'owner':'owner','repo':'steps','workflow':'steps.yml','ref':'main','token':'steps-private'},
            'gmail_accounts':{'mail':{'name':'邮箱','username':'test@gmail.com','password':'mail-private','enabled':True}},
            'server_sources':{'server':{'name':'服务器','provider':'1panel','url':'https://panel.example.com','api_key':'panel-private','enabled':True}}})
        self.backups=Backups(self.settings,self.root)
        self.password='independent-backup-password'

    def test_encrypted_roundtrip_restores_real_credentials_on_a_fresh_server(self):
        blob=self.backups.export(self.password,'dark','https://desk.example.com')
        text=json.dumps(blob)
        for secret in ('steps-private','mail-private','panel-private',self.settings.value['webhook_id']):self.assertNotIn(secret,text)
        fresh=Settings(self.root/'fresh.json');manager=Backups(fresh,self.root/'fresh-backups')
        preview=manager.preview(blob,self.password,'replace','session-one')
        self.assertNotIn('private',json.dumps(preview))
        result=manager.apply(preview['preview_id'],'session-one',True)
        self.assertEqual(fresh.value['github']['token'],'steps-private')
        self.assertEqual(fresh.value['gmail_accounts']['mail']['password'],'mail-private')
        self.assertEqual(fresh.value['server_sources']['server']['api_key'],'panel-private')
        self.assertNotEqual(fresh.value['webhook_id'],self.settings.value['webhook_id'])
        self.assertEqual(result['appearance'],'dark')

    def test_wrong_password_and_tampering_preserve_current_configuration(self):
        blob=self.backups.export(self.password)
        before=self.settings.path.read_bytes()
        with self.assertRaises(ValueError):self.backups.preview(blob,'wrong-backup-password','merge','s')
        tampered=copy.deepcopy(blob);value=tampered['ciphertext'];tampered['ciphertext']=('A' if value[0]!='A' else 'B')+value[1:]
        with self.assertRaises(ValueError):self.backups.preview(tampered,self.password,'merge','s')
        self.assertEqual(self.settings.path.read_bytes(),before)

    def test_merge_matches_mail_identity_preserves_other_entries_and_creates_encrypted_rollback(self):
        blob=self.backups.export(self.password)
        self.settings.update({'gmail_accounts':{'new-id':{'name':'同一邮箱','username':'test@gmail.com','password':'old-private','enabled':True},
            'other':{'name':'另一邮箱','username':'other@gmail.com','password':'other-private','enabled':True}}})
        preview=self.backups.preview(blob,self.password,'merge','s')
        self.assertEqual(preview['counts']['gmail_accounts']['updated'],1)
        self.backups.apply(preview['preview_id'],'s',False)
        self.assertEqual(set(self.settings.value['gmail_accounts']),{'new-id','other'})
        self.assertEqual(self.settings.value['gmail_accounts']['new-id']['password'],'mail-private')
        self.assertEqual(self.settings.value['gmail_accounts']['other']['password'],'other-private')
        files=list((self.root/'config-backups').glob('*.mydesk'))
        self.assertEqual(len(files),1)
        self.assertNotIn('private',files[0].read_text())

    def test_changed_settings_or_wrong_session_reject_restore(self):
        blob=self.backups.export(self.password)
        preview=self.backups.preview(blob,self.password,'merge','session-one')
        with self.assertRaises(ValueError):self.backups.apply(preview['preview_id'],'session-two',False)
        self.settings.update({'history_days':30})
        with self.assertRaises(ValueError):self.backups.apply(preview['preview_id'],'session-one',False)
        self.assertEqual(self.settings.value['history_days'],30)

    def test_replace_requires_explicit_confirmation(self):
        preview=self.backups.preview(self.backups.export(self.password),self.password,'replace','s')
        with self.assertRaises(ValueError):self.backups.apply(preview['preview_id'],'s',False)

    def test_unsupported_version_does_not_change_settings(self):
        blob=self.backups.export(self.password);blob['version']=999
        with self.assertRaises(ValueError):self.backups.preview(blob,self.password,'merge','s')

    def test_replace_removes_absent_integrations_and_preserves_internal_identity(self):
        fresh=Settings(self.root/'empty.json')
        blob=Backups(fresh,self.root).export(self.password)
        webhook=self.settings.value['webhook_id']
        preview=self.backups.preview(blob,self.password,'replace','s')
        self.assertEqual(preview['counts']['gmail_accounts']['removed'],1)
        self.backups.apply(preview['preview_id'],'s',True)
        self.assertNotIn('github',self.settings.value)
        self.assertEqual(self.settings.value['gmail_accounts'],{})
        self.assertEqual(self.settings.value['server_sources'],{})
        self.assertEqual(self.settings.value['webhook_id'],webhook)
