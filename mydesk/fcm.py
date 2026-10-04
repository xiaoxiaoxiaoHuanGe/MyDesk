"""Minimal FCM HTTP v1 transport. Service credentials and OAuth tokens stay on the server."""
import asyncio
import base64
import json
import re
import time
from pathlib import Path
from aiohttp import ClientError,ClientTimeout
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding,rsa

TOKEN_URL='https://oauth2.googleapis.com/token'
SCOPE='https://www.googleapis.com/auth/firebase.messaging'
FIELDS={'event_id','device_id','server_id','registration_id','kind','channel','title','body','created_at','expires_at','reference','revision'}


class PushRetry(Exception):
    def __init__(self,message='原生推送提交失败，稍后重试',retry_after=60):
        super().__init__(message)
        self.retry_after=max(5,min(3600,retry_after))


class TokenExpired(Exception):pass


def encoded(value):return base64.urlsafe_b64encode(value).decode().rstrip('=')


class Fcm:
    def __init__(self,session,credentials,clock=time.time):
        self.session,self.clock=session,clock
        if not isinstance(credentials,dict) or credentials.get('type')!='service_account':raise ValueError('需要 Firebase 服务账号配置')
        self.project=credentials.get('project_id','')
        self.email=credentials.get('client_email','')
        self.key_id=credentials.get('private_key_id','')
        if not re.fullmatch(r'[a-z][a-z0-9-]{4,61}[a-z0-9]',self.project) or not re.fullmatch(r'[A-Za-z0-9_.-]+@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com',self.email):raise ValueError('Firebase 服务账号配置无效')
        try:self.key=serialization.load_pem_private_key(credentials['private_key'].encode(),password=None)
        except (ValueError,TypeError,KeyError,AttributeError):raise ValueError('Firebase 服务账号密钥无效') from None
        if not isinstance(self.key,rsa.RSAPrivateKey) or self.key.key_size<2048:raise ValueError('Firebase 服务账号密钥无效')
        self.cached_token=None;self.expires=0;self.token_lock=asyncio.Lock()

    @classmethod
    def load(cls,session,path):
        path=Path(path)
        if not path.exists():return None
        if path.stat().st_size>65536:raise ValueError('Firebase 配置文件过大')
        try:credentials=json.loads(path.read_text(encoding='utf-8'))
        except (ValueError,UnicodeError):raise ValueError('Firebase 配置文件无效') from None
        return cls(session,credentials)

    def assertion(self):
        now=int(self.clock())-10
        header={'alg':'RS256','typ':'JWT'}
        if self.key_id:header['kid']=self.key_id
        claims={'iss':self.email,'scope':SCOPE,'aud':TOKEN_URL,'iat':now,'exp':now+3600}
        unsigned='.'.join(encoded(json.dumps(value,separators=(',',':')).encode()) for value in (header,claims))
        return unsigned+'.'+encoded(self.key.sign(unsigned.encode(),padding.PKCS1v15(),hashes.SHA256()))

    async def access_token(self):
        async with self.token_lock:
            if self.cached_token and self.expires>self.clock():return self.cached_token
            # token_uri from a supplied JSON is deliberately not used: credentials only go to Google.
            async with self.session.post(TOKEN_URL,data={'grant_type':'urn:ietf:params:oauth:grant-type:jwt-bearer','assertion':self.assertion()},
                                         allow_redirects=False,timeout=ClientTimeout(total=15)) as response:
                if response.status!=200:raise PushRetry('Firebase 服务账号授权失败，请检查发送权限',300)
                data=await response.json()
            token=data.get('access_token');duration=data.get('expires_in')
            if not isinstance(token,str) or not token or type(duration) is not int or not 1<=duration<=86400:raise PushRetry('Firebase 授权响应无效',300)
            self.cached_token=token;self.expires=self.clock()+max(0,duration-60)
            return token

    async def send(self,token,data):
        if not isinstance(data,dict) or data.keys()-FIELDS or any(not isinstance(value,str) for value in data.values()):raise ValueError('原生推送数据无效')
        if not re.fullmatch(r'[a-f0-9]{32}',data.get('event_id','')) or len(json.dumps(data,ensure_ascii=False).encode())>3500:raise ValueError('原生推送数据无效或过大')
        android={'priority':'NORMAL' if data.get('kind')=='sync' else 'HIGH','ttl':'3600s'}
        if data.get('kind')=='sync':android['collapse_key']='mydesk-sync'
        payload={'message':{'token':token,'data':data,'android':android}}
        url=f'https://fcm.googleapis.com/v1/projects/{self.project}/messages:send'
        try:
            for attempt in range(2):
                access=await self.access_token()
                async with self.session.post(url,json=payload,headers={'Authorization':'Bearer '+access},allow_redirects=False,timeout=ClientTimeout(total=15)) as response:
                    if response.status==401 and attempt==0:
                        self.cached_token=None;continue
                    result=await response.json()
                    if response.status==200 and isinstance(result.get('name'),str):return result['name']
                    details=result.get('error',{}).get('details',[])
                    if any(isinstance(item,dict) and item.get('errorCode')=='UNREGISTERED' for item in details):raise TokenExpired('原生设备 Token 已失效')
                    retry=response.headers.get('Retry-After','60')
                    raise PushRetry(retry_after=int(retry) if retry.isdigit() else 60)
        except (ClientError,TimeoutError,ValueError,TypeError,AttributeError):raise PushRetry() from None
        raise PushRetry()
