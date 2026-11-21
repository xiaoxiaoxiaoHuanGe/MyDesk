import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch

from aiohttp.test_utils import TestClient, TestServer

from mydesk.app import STATE, create_app
from mydesk.providers import fetch_mail_body, mail_body_text
from mydesk.runtime import now_utc


class MailDetailTests(unittest.TestCase):
    def test_reads_plain_body_and_skips_attachments(self):
        content = (
            b'MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=outer\r\n\r\n'
            b'--outer\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nHello reader\r\n'
            b'--outer\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=private.txt\r\n\r\nattachment text\r\n--outer--\r\n'
        )
        self.assertEqual(mail_body_text(content), 'Hello reader')

    def test_html_only_mail_returns_text_without_trackers_or_script(self):
        content = b'Content-Type: text/html; charset=utf-8\r\n\r\n<p>First</p><script>alert(1)</script><img src="https://example.org/track"><p>Second</p>'
        self.assertEqual(mail_body_text(content), 'First\nSecond')

    def test_readonly_fetch_checks_mailbox_identity_before_reading_a_bounded_body(self):
        client = Mock()
        context = Mock()
        context.__enter__ = Mock(return_value=client)
        context.__exit__ = Mock(return_value=False)
        client.select.return_value = ('OK',[])
        client.response.return_value = ('UIDVALIDITY',[b'19'])
        client.uid.return_value = ('OK',[(b'RFC822.SIZE 500', b'From: me@example.org\r\n\r\nbody')])
        spec = {'username':'me@example.org','password':'example'}
        with patch('mydesk.providers.mail_connection',return_value=context):
            with self.assertRaises(LookupError):
                fetch_mail_body(spec,'12','18')
            client.uid.assert_not_called()
            self.assertEqual(fetch_mail_body(spec,'12','19'),{'body':'body','truncated':True})
        client.select.assert_called_with('INBOX',readonly=True)
        client.uid.assert_called_once_with('fetch','12','(RFC822.SIZE BODY.PEEK[]<0.262144>)')


class MailDetailApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_requires_login_and_only_reads_mail_from_the_selected_account_window(self):
        with tempfile.TemporaryDirectory() as directory:
            app = create_app(directory,scheduling=False)
            async with TestClient(TestServer(app)) as client:
                self.assertEqual((await client.get('/api/mail/gmail/12')).status,401)
                access = json.loads((Path(directory)/'local-access.json').read_text())
                self.assertEqual((await client.post('/api/login',json=access)).status,200)
                runtime = app[STATE]['runtime']
                spec = {'username':'me@example.org','password':'fixture','name':'Inbox'}
                runtime.config['gmail_accounts'] = {'gmail':spec}
                await runtime.db('set_feed','mail',{'accounts':[{
                    'id':'gmail','username':spec['username'],'url':'https://mail.google.com/',
                    'items':[{'id':'gmail:12','uidvalidity':'19','subject':'Test','sender':'Alice','received_at':None}],
                }]},now_utc())
                with patch('mydesk.runtime.fetch_mail_body',return_value={'body':'Fixture','truncated':False}) as fetch:
                    self.assertEqual((await client.get('/api/mail/gmail/13')).status,404)
                    response = await client.get('/api/mail/gmail/12')
                    self.assertEqual(response.status,200)
                    self.assertEqual((await response.json())['body'],'Fixture')
                fetch.assert_called_once_with(spec,'12','19')
