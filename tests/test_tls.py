import ipaddress
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from urllib.request import build_opener, ProxyHandler, HTTPSHandler
from cryptography import x509
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from local_tls import prepare_tls

class LocalTlsTests(unittest.TestCase):
    def test_native_server_keeps_http_loopback_and_tls_on_the_selected_address(self):
        root=Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            data=Path(directory)
            tls=data/'tls'
            prepare_tls(tls,'127.0.0.2')
            def unused_port():
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1',0))
                    return listener.getsockname()[1]
            http_port=unused_port()
            tls_port=unused_port()
            while tls_port==http_port:
                tls_port=unused_port()
            process=subprocess.Popen([sys.executable,'-m','mydesk','--data',str(data),'--host','127.0.0.1','--port',str(http_port),
                '--tls-host','127.0.0.2','--tls-port',str(tls_port),'--cert',str(tls/'server.pem'),'--key',str(tls/'server-key.pem')],
                cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            try:
                context=ssl.create_default_context(cafile=str(tls/'mydesk-local-ca.crt'))
                opener=build_opener(ProxyHandler({}),HTTPSHandler(context=context))
                deadline=time.monotonic()+12
                ready=False
                while time.monotonic()<deadline:
                    if process.poll() is not None:
                        self.fail('Native server exited: '+process.stdout.read())
                    try:
                        with opener.open(f'https://127.0.0.2:{tls_port}/health',timeout=0.5) as response:
                            self.assertEqual(json.load(response)['application'],'MyDesk')
                            ready=True
                            break
                    except OSError:
                        time.sleep(0.1)
                self.assertTrue(ready,'HTTPS should start on the selected address')
                with opener.open(f'http://127.0.0.1:{http_port}/health',timeout=2) as response:
                    self.assertEqual(response.status,200)
                for address,port in [('127.0.0.2',http_port),('127.0.0.1',tls_port)]:
                    with self.assertRaises(OSError,msg='The other interface must not accept this listener'):
                        with socket.create_connection((address,port),timeout=0.5):
                            pass
            finally:
                if process.poll() is None:
                    if os.name=='nt':
                        stopped=subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True,check=False)
                        self.assertEqual(stopped.returncode,0,'Could not stop the test-owned process: '+stopped.stderr.decode(errors='replace'))
                    else:
                        process.terminate()
                process.communicate(timeout=10)

    def test_ca_is_preserved_and_server_certificate_covers_requested_lan_ip(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            prepare_tls(path)
            ca=(path/'mydesk-local-ca.crt').read_bytes()
            prepare_tls(path,'192.168.1.20')
            self.assertEqual((path/'mydesk-local-ca.crt').read_bytes(),ca)
            cert=x509.load_pem_x509_certificate((path/'server.pem').read_bytes())
            san=cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
            self.assertIn(x509.IPAddress(ipaddress.ip_address('192.168.1.20')),san)
            server=(path/'server.pem').read_bytes()
            prepare_tls(path,'192.168.1.20')
            self.assertEqual((path/'server.pem').read_bytes(),server)
