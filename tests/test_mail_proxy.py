"""Exercise proxy-backed IMAP against a local TLS endpoint, with no real mail."""
import socketserver,ssl,tempfile,threading,unittest
from pathlib import Path
from unittest.mock import patch

from mydesk.config import Settings
from mydesk.providers import poll_mail
from scripts.local_tls import prepare_tls


class MailProxySettingsTests(unittest.TestCase):
    def test_proxy_is_per_mailbox_and_edit_keeps_saved_password(self):
        with tempfile.TemporaryDirectory() as directory:
            s=Settings(Path(directory)/'settings.json')
            s.update({'gmail_accounts':{'one':{'name':'邮箱','username':'one@gmail.com','password':'private-password'}}})
            accounts=s.public()['gmail_accounts']
            accounts['one']['proxy']={'host':'127.0.0.1','port':17891}
            s.update({'gmail_accounts':accounts})
            self.assertEqual(Settings(s.path).public()['gmail_accounts']['one']['proxy']['port'],17891)
            self.assertEqual(s.value['gmail_accounts']['one']['password'],'private-password')
            self.assertNotIn('private-password',str(s.public()))
            before=s.path.read_bytes()
            for proxy in ({'host':'http://localhost','port':80},{'host':'localhost\r\nHeader: value','port':80},{'host':'localhost','port':0},{'host':'localhost','port':True},{'host':'localhost','port':65536},{'host':'localhost','port':80,'extra':'value'}):
                accounts['one']['proxy']=proxy
                with self.assertRaises(ValueError):s.update({'gmail_accounts':accounts})
                self.assertEqual(s.path.read_bytes(),before)
            accounts['one']['proxy']=None
            s.update({'gmail_accounts':accounts})
            self.assertIsNone(s.value['gmail_accounts']['one']['proxy'])


class MailProxyTransportTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=Path(self.tmp.name)
        prepare_tls(self.path)
        self.commands=[];self.connects=[];self.reject=False
        owner=self
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(str(self.path/'server.pem'),str(self.path/'server-key.pem'))
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                sock=self.request;sock.settimeout(3)
                head=b''
                while not head.endswith(b'\r\n\r\n'):
                    part=sock.recv(1)
                    if not part:return
                    head+=part
                owner.connects.append(head)
                if owner.reject:
                    sock.sendall(b'HTTP/1.1 407 Authentication Required\r\nContent-Length: 13\r\n\r\nprivate-token');return
                sock.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
                try:secure=tls.wrap_socket(sock,server_side=True)
                except OSError:return
                with secure:
                    secure.sendall(b'* OK local test IMAP\r\n')
                    stream=secure.makefile('rb')
                    while line:=stream.readline():
                        tag,command=line.rstrip().split(b' ',1)
                        owner.commands.append(command)
                        upper=command.upper()
                        if upper.startswith(b'CAPABILITY'):data=b'* CAPABILITY IMAP4rev1\r\n'
                        elif upper.startswith(b'EXAMINE'):data=b'* 1 EXISTS\r\n* 0 RECENT\r\n'
                        elif upper.startswith(b'UID SEARCH'):data=b'* SEARCH 42\r\n'
                        elif upper.startswith(b'UID FETCH'):
                            raw=b'From: sender@example.com\r\nSubject: test\r\nDate: Sun, 4 Oct 2026 01:00:00 +0800\r\n\r\n'
                            data=b'* 1 FETCH (UID 42 FLAGS () BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {'+str(len(raw)).encode()+b'}\r\n'+raw+b')\r\n'
                        elif upper.startswith(b'LOGOUT'):data=b'* BYE\r\n'
                        else:data=b''
                        secure.sendall(data+tag+b' OK completed\r\n')
                        if upper.startswith(b'LOGOUT'):break
        self.server=socketserver.ThreadingTCPServer(('127.0.0.1',0),Handler)
        self.server.daemon_threads=True
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.config={'host':'127.0.0.1','username':'test@example.com','password':'private-password','limit':3,
                     'proxy':{'host':'127.0.0.1','port':self.server.server_address[1]}}
        self.context=ssl.create_default_context(cafile=str(self.path/'mydesk-local-ca.crt'))

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.tmp.cleanup()

    def test_proxy_carries_verified_tls_and_readonly_headers_without_credentials_in_connect(self):
        with patch('mydesk.providers.ssl.create_default_context',return_value=self.context):
            data=poll_mail(self.config)
        self.assertEqual(data['unread'],1)
        self.assertEqual(data['items'][0]['subject'],'test')
        self.assertEqual(len(self.connects),1)
        self.assertTrue(self.connects[0].startswith(b'CONNECT 127.0.0.1:993 HTTP/1.1'))
        self.assertNotIn(b'private-password',self.connects[0])
        self.assertTrue(any(c.startswith(b'EXAMINE') for c in self.commands))
        self.assertTrue(any(b'BODY.PEEK[HEADER.FIELDS' in c for c in self.commands))

    def test_proxy_never_disables_certificate_checks_or_sends_login_before_tls(self):
        with self.assertRaises(ssl.SSLCertVerificationError):poll_mail(self.config)
        self.assertEqual(len(self.connects),1)
        self.assertEqual(self.commands,[])

    def test_proxy_rejection_is_sanitized_and_does_not_fall_back_to_direct(self):
        self.reject=True
        with self.assertRaisesRegex(ValueError,'代理') as caught:poll_mail(self.config)
        self.assertNotIn('private-token',str(caught.exception))
        self.assertEqual(len(self.connects),1)
        self.assertEqual(self.commands,[])


class MailProxyStatusTests(unittest.IsolatedAsyncioTestCase):
    async def test_proxy_error_reaches_status_and_saved_connection_check(self):
        from aiohttp import ClientSession
        from datetime import datetime,timezone
        from mydesk.domain import Desk
        from mydesk.runtime import Runtime
        from mydesk.mail_proxy import MailProxyError
        with tempfile.TemporaryDirectory() as directory:
            async with ClientSession() as session:
                cfg={'network':{'enabled':False},'gmail_accounts':{'one':{'name':'邮箱','username':'test@gmail.com','password':'private-password','enabled':True}}}
                runtime=Runtime(Desk(Path(directory)/'desk.db'),cfg,session,None)
                def failed(spec):raise MailProxyError('无法连接邮件代理，请检查地址、端口与代理是否运行')
                now=datetime.now(timezone.utc)
                with patch('mydesk.runtime.poll_mail',failed):
                    await runtime.refresh(now)
                    state=await runtime.snapshot(now)
                    self.assertIn('邮件代理',state['feeds']['mail']['data']['accounts'][0]['error'])
                    with self.assertRaisesRegex(ValueError,'邮件代理'):
                        await runtime.command('service/check',{'kind':'mail','id':'one'},now)
                self.assertNotIn('private-password',str(state))
