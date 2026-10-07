"""Authenticated, immutable local APK registry. No remote fetches or user paths."""
import hashlib
import json
import re
from pathlib import Path

MAX_APK_BYTES=200*1024*1024
CHANNELS=('release','debug')
ARTIFACT=re.compile(r'[a-f0-9]{64}')

def validate_manifest(value,channel=None):
    if not isinstance(value,dict) or value.get('schema')!=1 or type(value.get('schema')) is not int:
        raise ValueError('更新清单格式无效')
    if value.get('channel') not in CHANNELS or (channel and value['channel']!=channel):raise ValueError('更新渠道无效')
    for key,low,high in [('version_code',1,2147483647),('min_sdk',26,10000),('size_bytes',1,MAX_APK_BYTES)]:
        if type(value.get(key)) is not int or not low<=value[key]<=high:raise ValueError('更新元数据无效')
    for key,limit in [('version_name',100),('notes',20000),('published_at',50)]:
        if not isinstance(value.get(key),str) or len(value[key])>limit:raise ValueError('更新说明无效')
    from .domain import instant
    instant(value['published_at'])
    if value.get('package_name')!='app.mydesk.android':raise ValueError('更新包名不符')
    for key in ('sha256','artifact_id','certificate_sha256'):
        if not isinstance(value.get(key),str) or not ARTIFACT.fullmatch(value[key]):raise ValueError('更新摘要无效')
    if value['artifact_id']!=value['sha256']:raise ValueError('产物标识不符')
    return value

class AppUpdates:
    def __init__(self,directory):self.directory=Path(directory)
    def read(self,path,channel=None):
        if path.is_symlink():raise ValueError('不允许符号链接清单')
        value=validate_manifest(json.loads(path.read_text(encoding='utf-8')),channel)
        apk=self.directory/'artifacts'/(value['artifact_id']+'.apk')
        if apk.is_symlink() or not apk.is_file() or apk.stat().st_size!=value['size_bytes']:raise ValueError('更新产物尚未完整就位')
        return value
    def latest(self,channel):
        if channel not in CHANNELS:raise ValueError('更新渠道无效')
        path=self.directory/(channel+'.json')
        if not path.exists():return {'available':False,'channel':channel}
        value=self.read(path,channel)
        registered=self.read(self.directory/'artifacts'/(value['artifact_id']+'.json'),channel)
        if registered!=value:raise ValueError('更新产物登记不符')
        return {**value,'available':True,'apk_path':'/api/app-update/artifacts/'+value['artifact_id']+'.apk'}
    def artifact(self,identifier):
        if not isinstance(identifier,str) or not ARTIFACT.fullmatch(identifier):raise FileNotFoundError()
        try:self.read(self.directory/'artifacts'/(identifier+'.json'))
        except FileNotFoundError:raise FileNotFoundError() from None
        return self.directory/'artifacts'/(identifier+'.apk')
