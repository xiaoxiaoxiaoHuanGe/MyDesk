"""External adapters with bounded requests and sanitized errors."""
import asyncio
import imaplib
import ipaddress
import re
import ssl
import hashlib
import hmac
import time
import json
import math
import os
from datetime import datetime, timezone
from email import policy
from email.parser import BytesParser
from email.utils import parseaddr, parsedate_to_datetime
from urllib.parse import quote

from aiohttp import ClientError, ClientTimeout
from .mail_proxy import ProxiedIMAP


class GitHub:
    def __init__(self, session, config, base_url='https://api.github.com'):
        self.session, self.config = session, config
        self.base = f"{base_url}/repos/{quote(config['owner'], safe='')}/{quote(config['repo'], safe='')}"

    async def request(self, method, path, body=None):
        headers = {'Authorization': f"Bearer {self.config['token']}", 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2026-03-10'}
        try:
            async with self.session.request(method, self.base + path, json=body, headers=headers,
                                            timeout=ClientTimeout(total=30), allow_redirects=False) as response:
                if response.status not in (200, 204):
                    raise ValueError(f'GitHub API 返回 HTTP {response.status}，请检查仓库、Workflow 和服务端权限')
                if response.status == 204:
                    return {}
                return await response.json()
        except (ClientError, asyncio.TimeoutError) as exc:
            raise ValueError('GitHub 连接失败或超时；提交结果可能不确定，请检查 Actions') from exc

    async def dispatch(self, steps):
        result = await self.request('POST', f"/actions/workflows/{quote(str(self.config['workflow']), safe='')}/dispatches",
                                    {'ref': self.config.get('ref', 'main'), 'inputs': {'steps': str(steps)}, 'return_run_details': True})
        if not result.get('workflow_run_id'):
            raise ValueError('GitHub 未返回本次 Run ID，请检查 Actions；为避免重复执行不会自动重试')
        return {'run_id': result['workflow_run_id'], 'url': result['html_url']}

    async def run(self, run_id):
        return await self.request('GET', f'/actions/runs/{int(run_id)}')


def run_state(run):
    if run['status'] == 'completed':
        return 'success' if run.get('conclusion') == 'success' else 'failed'
    if run['status'] == 'in_progress':
        return 'running'
    return 'queued'


class Beszel:
    def __init__(self, session, config):
        self.session, self.config = session, config
        self.token = None

    async def request(self, method, path, **kwargs):
        try:
            async with self.session.request(method, self.config['url'].rstrip('/') + path,
                                            timeout=ClientTimeout(total=20), allow_redirects=False,
                                            headers={'Authorization': self.token or ''}, **kwargs) as response:
                if response.status != 200:
                    self.token = None
                    raise ValueError(f'Beszel API 返回 HTTP {response.status}')
                return await response.json()
        except (ClientError, asyncio.TimeoutError) as exc:
            raise ValueError('Beszel 连接失败或超时') from exc

    async def poll(self):
        if not self.token:
            auth = await self.request('POST', '/api/collections/users/auth-with-password',
                                      json={'identity': self.config['email'], 'password': self.config['password']})
            self.token = auth['token']
        items, page = [], 1
        while True:
            result = await self.request('GET', '/api/collections/systems/records', params={'page': page, 'perPage': 100})
            for record in result['items']:
                info = record.get('info') or {}
                items.append(dict(id=record['id'], name=record['name'], status=record.get('status', 'unknown'),
                                  cpu=info.get('cpu'), ram=info.get('mp'), disk=info.get('dp'),
                                  load=info.get('la'), network=info.get('bb', info.get('b')),
                                  temperature=info.get('dt'), updated_at=record.get('updated')))
            if page >= result.get('totalPages', 1):
                break
            page += 1
            if page > 100:
                raise ValueError('Beszel 系统数量超过支持范围')
        return {'items': items}

class OnePanel:
    """Read only v2 dashboard; no shell commands, login password or management calls."""
    def __init__(self,session,config):self.session,self.config=session,config

    async def poll(self):
        timestamp=str(int(time.time()));key=self.config['api_key']
        token=(hmac.new(key.encode(),('1panel:'+timestamp).encode(),hashlib.sha256).hexdigest()
               if self.config.get('signature')=='hmac-sha256' else hashlib.md5(('1panel'+key+timestamp).encode()).hexdigest())
        try:
            async with self.session.get(self.config['url'].rstrip('/')+'/api/v2/dashboard/current/all/all',
                headers={'1Panel-Token':token,'1Panel-Timestamp':timestamp,'CurrentNode':'local'},timeout=ClientTimeout(total=15),allow_redirects=False) as response:
                if response.status!=200:raise ValueError(f'1Panel API 返回 HTTP {response.status}，请检查地址、API Key 与 IP 白名单')
                raw=await response.content.read(1024*1024+1)
                if len(raw)>1024*1024:raise ValueError('1Panel 返回数据过大')
                result=json.loads(raw)
            if not isinstance(result,dict) or result.get('code')!=200 or not isinstance(result.get('data'),dict):raise ValueError('1Panel 接口验证失败，请检查 API Key、签名方式与 IP 白名单')
            data=result['data']
            def number(value):return value if type(value) in (int,float) and math.isfinite(value) else None
            def counter(value):return value if type(value) is int and 0 <= value < 2**64 else None
            cpu=number(data.get('cpuUsedPercent'));ram=number(data.get('memoryUsedPercent'))
            if cpu is None or ram is None:raise ValueError('1Panel 返回的监控数据格式不支持')
            disks=[d for d in data.get('diskData',[]) if isinstance(d,dict)]
            root=next((d for d in disks if d.get('path')=='/'),None)
            return dict(status='up',cpu=cpu,ram=ram,disk=number(root.get('usedPercent')) if root else None,
                load=[number(data.get('load'+str(n))) for n in (1,5,15)],
                network=dict(sent_bytes=counter(data.get('netBytesSent')),received_bytes=counter(data.get('netBytesRecv'))),
                temperature=None,uptime=number(data.get('uptime')))
        except (ClientError,asyncio.TimeoutError) as exc:raise ValueError('1Panel 连接失败，请检查面板是否在线、网络和 HTTPS 证书') from exc
        except (json.JSONDecodeError,UnicodeError,TypeError) as exc:raise ValueError('1Panel 未返回有效监控数据，请检查地址') from exc


def parse_mail(raw, metadata, uid):
    message = BytesParser(policy=policy.default).parsebytes(raw)
    name, address = parseaddr(str(message.get('From', '')))
    try:
        internal=re.search(rb'INTERNALDATE "([^"]+)"',metadata)
        received = datetime.strptime(internal[1].decode('ascii'),'%d-%b-%Y %H:%M:%S %z') if internal else parsedate_to_datetime(str(message.get('Date', '')))
        received = received.replace(tzinfo=received.tzinfo or timezone.utc).astimezone(timezone.utc).isoformat()
    except (ValueError, TypeError, OverflowError):
        received = None
    flags = re.search(rb'FLAGS\s*\(([^)]*)\)', metadata)
    return {'id': str(uid), 'sender': name or address or '未知发件人', 'subject': str(message.get('Subject', '无主题')),
            'received_at': received, 'unread': not flags or b'\\Seen' not in flags[1]}


def poll_mail(config):
    """Run in executor. PEEK headers only, read-only mailbox, no body or mark-as-read."""
    transport=ProxiedIMAP if config.get('proxy') else imaplib.IMAP4_SSL
    options={'proxy':config['proxy']} if config.get('proxy') else {}
    with transport(config.get('host', 'imap.gmail.com'), port=993, timeout=20, **options,
                          ssl_context=ssl.create_default_context()) as client:
        client.login(config['username'], config['password'])
        status, _ = client.select(config.get('mailbox', 'INBOX'), readonly=True)
        if status != 'OK':
            raise ValueError('无法打开邮箱文件夹')
        status, result = client.uid('search', None, 'ALL')
        if status != 'OK':
            raise ValueError('无法获取邮件列表')
        uids = result[0].split()[-config.get('limit', 5):]
        status, unread = client.uid('search', None, 'UNSEEN')
        if status != 'OK':
            raise ValueError('无法获取未读数量')
        items = []
        for uid in reversed(uids):
            status, data = client.uid('fetch', uid, '(FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])')
            if status != 'OK':
                raise ValueError('无法获取邮件标题')
            for part in data:
                if isinstance(part, tuple):
                    items.append(parse_mail(part[1], part[0], uid.decode()))
                    break
        return {'items': items, 'unread': len(unread[0].split())}


async def ping_node(name, host):
    # Arguments go directly to exec, never through a shell.
    if not re.fullmatch(r'[a-zA-Z0-9:.\-]+', host) or host.startswith('-'):
        raise ValueError('Ping 节点地址无效')
    result = {'name': name, 'host': host, 'online': None, 'ping': None}
    try:
        process = await asyncio.create_subprocess_exec('ping', '-n', '-c', '1', '-W', '2', host,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            env={**os.environ, 'LC_ALL': 'C'})
    except FileNotFoundError:
        return {**result, 'error': 'Ping 检测工具不可用'}
    except PermissionError:
        return {**result, 'error': 'Ping 检测权限不足'}
    try:
        output, error = await asyncio.wait_for(process.communicate(), timeout=5)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        return {**result, 'error': 'Ping 检测超时'}
    except asyncio.CancelledError:
        process.kill()
        await process.wait()
        raise
    if process.returncode != 0:
        error = error.lower()
        if any(message in error for message in (b'operation not permitted', b'permission denied', b'missing cap_net_raw')):
            return {**result, 'error': 'Ping 检测权限不足'}
        if any(message in error for message in (b'name or service not known', b'temporary failure in name resolution', b'unknown host')):
            return {**result, 'error': '节点域名解析失败'}
        if process.returncode == 1:
            return {**result, 'online': False, 'error': '节点未回应 Ping'}
        return {**result, 'error': 'Ping 检测失败，请检查运行环境'}
    match = re.search(rb'time[=<]([\d.]+)', output)
    return {**result, 'online': True, 'ping': float(match[1]) if match else None}


async def poll_network(session, config):
    async def check(url):
        try:
            async with session.get(url, timeout=ClientTimeout(total=8)) as response:
                return response.status < 400
        except (ClientError, asyncio.TimeoutError):
            return False
    checks = await asyncio.gather(*(check(url) for url in config.get('probes', ['https://www.cloudflare.com/cdn-cgi/trace', 'https://www.google.com/generate_204'])))
    public_ip = None
    try:
        async with session.get('https://api.ipify.org', timeout=ClientTimeout(total=8)) as response:
            if response.status == 200:
                public_ip = str(ipaddress.ip_address((await response.text()).strip()))
    except (ValueError, ClientError, asyncio.TimeoutError):
        pass
    nodes = await asyncio.gather(*(ping_node(node['name'], node['host']) for node in config.get('nodes', [])))
    return {'online': any(checks), 'public_ip': public_ip, 'nodes': nodes,
            'ping': next((n['ping'] for n in nodes if n['online']), None)}


def notification_data(item, dashboard_path):
    prefix = f"MYDESK:{item['id']}:{item['revision']}"
    return {'title': 'MyDesk', 'message': item['title'], 'data': {
        'tag': f"mydesk-{item['id']}", 'url': dashboard_path, 'clickAction': dashboard_path,
        'actions': [
            {'action': f'{prefix}:complete', 'title': '完成'},
            {'action': f'{prefix}:snooze', 'title': '10分钟后提醒'},
            {'action': 'URI', 'title': '打开', 'uri': dashboard_path},
        ]}}
