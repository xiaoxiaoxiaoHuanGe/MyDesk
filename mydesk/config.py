"""Validated, atomic MyDesk-only configuration. Secrets never appear in public settings."""
import copy
import json
import re
import secrets
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from .mail_proxy import validate_proxy

DEFAULTS = {'timezone':'Asia/Shanghai','history_days':90,'stale_seconds':300,
            'network':{'enabled':False,'nodes':[]},'expected_tasks':{},'github_tasks':{},'dashboard_path':'/'}

def mail_accounts(value):
    if 'gmail_accounts' in value:return value['gmail_accounts']
    old=value.get('gmail')
    return {'legacy':{**old,'name':old.get('name',old['username']),'enabled':True}} if old else {}

def server_sources(value):
    if 'server_sources' in value:return value['server_sources']
    old=value.get('beszel')
    return {'legacy':{**old,'name':old.get('name','Beszel'),'provider':'beszel','enabled':True}} if old else {}


class Settings:
    def __init__(self, path):
        self.path = path
        if not path.exists():
            self.write({**copy.deepcopy(DEFAULTS),'webhook_id':secrets.token_hex(32)})
        self.value = json.loads(path.read_text(encoding='utf-8'))
        if 'github_checkins' in self.value:
            from .github_checkins import SOURCES
            old=self.value.pop('github_checkins') or {}
            tasks=self.value.setdefault('github_tasks',{})
            for key in old.get('sources',[]):
                if key not in SOURCES or key in tasks:
                    continue
                token=old.get('token','') if not old.get('use_steps_token') else ''
                tasks[key]={**SOURCES[key],'adapter':key,'max_age_hours':36,'token':token,'enabled':bool(token and SOURCES[key].get('owner'))}
            self.write(self.value)

    def write(self, value):
        temp = self.path.with_suffix('.tmp')
        temp.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
        temp.replace(self.path)
        self.value = value

    def public(self):
        result = copy.deepcopy(self.value)
        result['gmail_accounts']=copy.deepcopy(mail_accounts(self.value))
        result['server_sources']=copy.deepcopy(server_sources(self.value))
        for entries in (result['gmail_accounts'],result['server_sources']):
            for item in entries.values():
                secret='api_key' if item.get('provider')=='1panel' else 'password'
                item['credential_set']=bool(item.get(secret))
                item.pop('password',None);item.pop('api_key',None)
        result.pop('webhook_id',None)
        result.pop('notify_targets',None)
        for task in result.get('github_tasks',{}).values():
            task['credential_set']=bool(task.pop('token',None))
        for name, field in [('github','token'),('gmail','password'),('beszel','password')]:
            if result.get(name):
                result[name]['credential_set'] = bool(result[name].pop(field,None))
        return result

    def prepare(self, incoming, base=None):
        previous_value = self.value if base is None else base
        if not isinstance(incoming,dict):
            raise ValueError('配置格式无效')
        allowed = {'timezone','history_days','network','github','github_tasks','gmail','beszel','expected_tasks','gmail_accounts','server_sources'}
        if incoming.keys()-allowed:
            raise ValueError('包含不支持的配置项')
        value = copy.deepcopy(previous_value)
        for key, item in incoming.items():
            if key in ('gmail_accounts','server_sources'):
                if not isinstance(item,dict) or len(item)>20:raise ValueError('接入列表最多添加 20 项')
                previous=mail_accounts(previous_value) if key=='gmail_accounts' else server_sources(previous_value)
                entries={}
                for item_id,spec in item.items():
                    if not isinstance(spec,dict):raise ValueError('接入配置格式无效')
                    entry={k:v for k,v in spec.items() if k!='credential_set'}
                    secret='api_key' if entry.get('provider')=='1panel' else 'password'
                    old=previous.get(item_id,{})
                    identity=('username',) if key=='gmail_accounts' else ('provider','url','email')
                    if not entry.get(secret):
                        if old and any(entry.get(field)!=old.get(field) for field in identity):
                            raise ValueError('更换邮箱或服务器地址时，请重新填写此项凭据')
                        entry[secret]=old.get(secret,'')
                    entries[item_id]=entry
                value[key]=entries
                value.pop('gmail' if key=='gmail_accounts' else 'beszel',None)
            elif key=='github_tasks':
                if not isinstance(item,dict) or len(item)>20:
                    raise ValueError('GitHub 任务列表无效，最多添加 20 项')
                tasks={}
                for task_id,task in item.items():
                    if not isinstance(task,dict):
                        raise ValueError('GitHub 任务配置无效')
                    tasks[task_id]={k:v for k,v in task.items() if k!='credential_set'}
                    if not task.get('token'):
                        tasks[task_id]['token']=previous_value.get('github_tasks',{}).get(task_id,{}).get('token','')
                value[key]=tasks
            elif key in ('github','gmail','beszel'):
                collection={'gmail':'gmail_accounts','beszel':'server_sources'}.get(key)
                if collection and collection in value:
                    if item is None:value[collection].pop('legacy',None)
                    else:
                        if not isinstance(item,dict):raise ValueError('接入配置格式无效')
                        old=value[collection].get('legacy',{})
                        entry={**old,**{k:v for k,v in item.items() if k!='credential_set'}}
                        if not item.get('password'):entry['password']=old.get('password','')
                        entry.setdefault('name',entry.get('username','Beszel'));entry.setdefault('enabled',True)
                        if key=='beszel':entry['provider']='beszel'
                        value[collection]['legacy']=entry
                    continue
                if item is None:
                    value.pop(key,None)
                    continue
                if not isinstance(item,dict):
                    raise ValueError('接入配置格式无效')
                credential='token' if key=='github' else 'password'
                filtered={k:v for k,v in item.items() if k not in ('credential_set',)}
                value[key]={**value.get(key,{}),**filtered}
                if not filtered.get(credential):
                    value[key][credential]=previous_value.get(key,{}).get(credential,'')
            else:
                value[key]=item
        try:
            ZoneInfo(value['timezone'])
        except (ZoneInfoNotFoundError,TypeError,ValueError):
            raise ValueError('时区无效') from None
        if type(value['history_days']) is not int or not 1<=value['history_days']<=365:
            raise ValueError('历史保留天数需要在 1–365 之间')
        net=value['network']
        if not isinstance(net,dict) or type(net.get('enabled')) is not bool or not isinstance(net.get('nodes',[]),list):
            raise ValueError('网络配置无效')
        if len(net.get('nodes',[]))>20:
            raise ValueError('最多配置 20 个网络节点')
        for node in net.get('nodes',[]):
            if not isinstance(node,dict) or not node.get('name') or not re.fullmatch(r'[a-zA-Z0-9:.\-]+',str(node.get('host',''))) or str(node['host']).startswith('-'):
                raise ValueError('网络节点需要名称和有效的域名或 IP')
        if not isinstance(value.get('expected_tasks'),dict) or len(value['expected_tasks'])>100:
            raise ValueError('任务配置无效')
        for task in value['expected_tasks'].values():
            if not isinstance(task,dict) or not isinstance(task.get('name'),str) or type(task.get('max_age_hours')) not in (int,float) or not 1<=task['max_age_hours']<=8760:
                raise ValueError('任务需要名称和有效的超期小时数')
        for name,fields in [('github',('owner','repo','workflow','token')),('gmail',('username','password')),('beszel',('url','email','password'))]:
            item=value.get(name)
            if item:
                if any(not isinstance(item.get(field),str) or not item[field].strip() or len(item[field])>4096 for field in fields):
                    raise ValueError('请补全接入信息和服务端凭据')
                if name=='beszel':
                    url=urlsplit(item['url'])
                    if url.scheme not in ('http','https') or not url.hostname or url.username:
                        raise ValueError('Beszel 地址需要为 HTTP 或 HTTPS 地址')
                if name=='gmail' and (type(item.get('limit',5)) is not int or not 3<=item.get('limit',5)<=5):
                    raise ValueError('邮件数量需要在 3–5 之间')
        for collection in ('gmail_accounts','server_sources'):
            identities=set()
            for key,item in value.get(collection,{}).items():
                if not isinstance(key,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',key):raise ValueError('接入标识无效')
                item.setdefault('enabled',True)
                if not isinstance(item.get('name'),str) or not 1<=len(item['name'].strip())<=80 or type(item['enabled']) is not bool:raise ValueError('请填写接入名称及有效启用状态')
                item['name']=item['name'].strip()
                if collection=='gmail_accounts':
                    fields={'name','username','password','limit','enabled','host','mailbox','proxy'}
                    validate_proxy(item.get('proxy'))
                    if not isinstance(item.get('username'),str) or not re.fullmatch(r'[^@\s]+@[^@\s]+\.[^@\s]+',item['username']) or len(item['username'])>254:raise ValueError('请填写有效的 Gmail 邮箱地址')
                    item.setdefault('limit',5)
                    if type(item['limit']) is not int or not 3<=item['limit']<=5:raise ValueError('邮件数量需要在 3–5 之间')
                    if item.get('host') and (not isinstance(item['host'],str) or not re.fullmatch(r'[A-Za-z0-9.-]+',item['host'])):raise ValueError('IMAP 地址无效')
                    identity=item['username'].lower();secret='password'
                else:
                    if item.get('provider') not in ('1panel','beszel'):raise ValueError('服务器接入类型无效')
                    fields={'name','provider','url','enabled'}|({'api_key','signature'} if item['provider']=='1panel' else {'email','password','public_url'})
                    if not isinstance(item.get('url'),str) or not 1<=len(item['url'])<=2048:raise ValueError('服务器地址无效')
                    try:url=urlsplit(item.get('url',''));port=url.port
                    except ValueError:raise ValueError('服务器地址无效') from None
                    if url.scheme not in ('http','https') or not url.hostname or url.username or url.password or url.query or url.fragment:raise ValueError('服务器地址需要为无账号、参数的 HTTP 或 HTTPS 地址')
                    item['url']=item['url'].rstrip('/')
                    identity=(item['provider'],item['url'].lower());secret='api_key' if item['provider']=='1panel' else 'password'
                    if item['provider']=='1panel':
                        item.setdefault('signature','md5')
                        if item['signature'] not in ('md5','hmac-sha256'):raise ValueError('1Panel 签名方式无效')
                    elif not isinstance(item.get('email'),str) or not item['email'].strip():raise ValueError('请填写 Beszel 登录邮箱')
                if item.keys()-fields:raise ValueError('包含不支持的接入配置项')
                if not isinstance(item.get(secret,''),str) or len(item.get(secret,''))>4096 or (item['enabled'] and not item.get(secret,'').strip()):raise ValueError('请为此接入单独填写凭据')
                if identity in identities:raise ValueError('相同邮箱或服务器不能重复添加')
                identities.add(identity)
        identities=set()
        for key,task in value.get('github_tasks',{}).items():
            fields={'name','owner','repo','workflow','ref','adapter','step','max_age_hours','enabled','token'}
            if not isinstance(key,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',key) or key=='wxstep' or task.keys()-fields:
                raise ValueError('GitHub 任务标识或配置项无效')
            for field,limit in [('name',80),('owner',100),('repo',100),('workflow',100),('ref',200)]:
                if not isinstance(task.get(field),str) or not task[field].strip() or len(task[field])>limit:
                    raise ValueError('请补全任务名称、仓库、工作流和分支')
                task[field]=task[field].strip()
            if not all(re.fullmatch(r'[A-Za-z0-9_.-]+',task[field]) and task[field] not in ('.','..') for field in ('owner','repo')) or not re.fullmatch(r'[A-Za-z0-9_.-]+\.ya?ml',task['workflow']):
                raise ValueError('仓库名称或工作流文件名无效')
            task.setdefault('adapter','standard');task.setdefault('step','MyDesk result');task.setdefault('max_age_hours',36);task.setdefault('enabled',True)
            if task['adapter'] not in ('standard','workflow','52fzwg','glados') or not isinstance(task['step'],str) or len(task['step'])>100 or (task['adapter']!='workflow' and not task['step'].strip()):
                raise ValueError('结果格式或结果步骤名称无效')
            if type(task['enabled']) is not bool or type(task['max_age_hours']) not in (int,float) or not 1<=task['max_age_hours']<=8760:
                raise ValueError('任务启用状态或预期周期无效')
            if not isinstance(task.get('token',''),str) or len(task.get('token',''))>4096 or (task['enabled'] and not task.get('token','').strip()):
                raise ValueError('请为此任务单独填写 Token')
            identity=(task['owner'].lower(),task['repo'].lower(),task['workflow'],task['ref'])
            if identity in identities:
                raise ValueError('同一个仓库、工作流和分支不能重复添加')
            identities.add(identity)
        return value

    def update(self, incoming):
        self.write(self.prepare(incoming))
        return self.public()
