"""HTTP CONNECT transport for IMAP; Gmail TLS verification stays end-to-end."""
import imaplib
import ipaddress
import re
import socket
import time


class MailProxyError(ValueError):
    pass


def validate_proxy(value):
    if value is None:return
    if not isinstance(value,dict) or set(value)!={'host','port'}:
        raise ValueError('邮件代理需要填写地址和端口')
    host=value['host']
    if not isinstance(host,str) or not host or len(host)>253:
        raise ValueError('邮件代理地址无效')
    if ':' in host:
        try:ipaddress.ip_address(host)
        except ValueError:raise ValueError('邮件代理地址无效') from None
    elif not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*',host):
        raise ValueError('邮件代理填写 IP 或域名，不包含协议和路径')
    if type(value['port']) is not int or not 1<=value['port']<=65535:
        raise ValueError('邮件代理端口需要在 1–65535 之间')


class ProxiedIMAP(imaplib.IMAP4_SSL):
    def __init__(self,host,*,proxy,**kwargs):
        validate_proxy(proxy)
        self.proxy=proxy
        super().__init__(host,**kwargs)

    def _create_socket(self,timeout):
        try:sock=socket.create_connection((self.proxy['host'],self.proxy['port']),timeout=timeout)
        except OSError:raise MailProxyError('无法连接邮件代理，请检查地址、端口与代理是否运行') from None
        try:
            authority=f'[{self.host}]:{self.port}' if ':' in self.host else f'{self.host}:{self.port}'
            sock.sendall(f'CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n\r\n'.encode('ascii'))
            deadline=time.monotonic()+(timeout or 20)
            response=bytearray()
            while not response.endswith(b'\r\n\r\n'):
                remaining=deadline-time.monotonic()
                if remaining<=0:raise MailProxyError('邮件代理连接 Gmail 超时')
                sock.settimeout(remaining)
                part=sock.recv(1)
                if not part or len(response)>=8192:raise MailProxyError('邮件代理响应无效')
                response.extend(part)
            status=re.fullmatch(rb'HTTP/1\.[01] ([0-9]{3})(?: [^\r\n]*)?',bytes(response).split(b'\r\n',1)[0])
            if not status:raise MailProxyError('邮件代理响应无效，请使用 HTTP 代理端口')
            if status[1]==b'407':raise MailProxyError('邮件代理要求认证，请使用无需认证的 HTTP 代理')
            if status[1]!=b'200':raise MailProxyError('邮件代理未能连接 Gmail，请检查代理出口')
            sock.settimeout(timeout)
            return self.ssl_context.wrap_socket(sock,server_hostname=self.host)
        except BaseException:
            sock.close()
            raise
