#!/usr/bin/env python3
"""Prepare an already signed APK; never signs, uploads or deploys it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime,timezone
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from mydesk.app_updates import validate_manifest, MAX_APK_BYTES

def apk_metadata(apk,aapt,apksigner,certificate,channel=None):
    badging=subprocess.run([str(aapt),'dump','badging',str(apk)],check=True,capture_output=True,text=True).stdout
    match=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
    sdk=re.search(r"sdkVersion:'(\d+)'",badging)
    if not match or not sdk:raise ValueError('无法读取真实 APK 元数据')
    # apksigner.bat on Windows is executed by subprocess via the native command processor.
    command=[str(apksigner),'verify','--verbose','--print-certs',str(apk)]
    signed=subprocess.run(command,check=True,capture_output=True,text=True).stdout
    fingerprints=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)',signed)
    expected=certificate.lower().replace(':','')
    if len(fingerprints)!=1 or fingerprints[0].lower()!=expected or not re.fullmatch('[a-f0-9]{64}',expected):
        raise ValueError('APK 签名与指定渠道预期证书不一致')
    if "application-debuggable" in badging and certificate and channel=='release':
        raise ValueError('不能将可调试 APK 标为 release')
    return dict(package_name=match[1],version_code=int(match[2]),version_name=match[3],min_sdk=int(sdk[1]),certificate_sha256=expected)

def _prepare(apk,output,channel,notes,certificate,aapt,apksigner):
    apk=Path(apk);output=Path(output)
    if channel not in ('release','debug'):raise ValueError('渠道无效')
    if not 0<apk.stat().st_size<=MAX_APK_BYTES:raise ValueError('APK 大小无效')
    output.mkdir(parents=True,exist_ok=True)
    artifacts=output/'artifacts';artifacts.mkdir(exist_ok=True)
    # Stage on the destination filesystem. Clients see the new pointer only after every file is ready.
    with tempfile.TemporaryDirectory(prefix='.staging-',dir=output) as stage:
        stage=Path(stage);copy=stage/'update.apk';shutil.copyfile(apk,copy)
        value=apk_metadata(copy,aapt,apksigner,certificate,channel)
        h=hashlib.sha256()
        with copy.open('rb') as f:
            for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
        key=h.hexdigest()
        value=validate_manifest(dict(schema=1,channel=channel,artifact_id=key,sha256=key,size_bytes=copy.stat().st_size,
            published_at=datetime.now(timezone.utc).isoformat(),notes=notes,**value),channel)
        dest=artifacts/(key+'.apk');registered=artifacts/(key+'.json')
        if dest.exists():
            if dest.is_symlink():raise ValueError('不可变产物冲突')
            with dest.open('rb') as existing:
                if hashlib.file_digest(existing,'sha256').hexdigest()!=key:raise ValueError('不可变产物冲突')
            if registered.exists():
                old=validate_manifest(json.loads(registered.read_text(encoding='utf-8')),channel)
                if old['sha256']!=key:raise ValueError('产物登记冲突')
                value=old
        else:os.replace(copy,dest)
        encoded=json.dumps(value,ensure_ascii=False,indent=2)
        manifest=stage/'artifact.json';manifest.write_text(encoded,encoding='utf-8')
        if not registered.exists():os.replace(manifest,registered)
        pointer=stage/'channel.json';pointer.write_text(encoded,encoding='utf-8')
        os.replace(pointer,output/(channel+'.json'))
    return value

def prepare(apk,output,channel,notes,certificate,aapt,apksigner):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    lock=output/'.publish.lock'
    try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError:raise ValueError('此目录已有发布准备操作；确认没有进程运行后才可移除残留 .publish.lock') from None
    try:
        os.close(fd)
        return _prepare(apk,output,channel,notes,certificate,aapt,apksigner)
    finally:lock.unlink(missing_ok=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=Path);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--channel',choices=['release','debug'],required=True)
    p.add_argument('--notes-file',type=Path,required=True);p.add_argument('--certificate-sha256',required=True)
    p.add_argument('--build-tools',type=Path,required=True)
    args=p.parse_args();suffix='.exe' if os.name=='nt' else ''
    result=prepare(args.apk,args.output,args.channel,args.notes_file.read_text(encoding='utf-8'),args.certificate_sha256,
        args.build_tools/('aapt'+suffix),args.build_tools/('apksigner.bat' if os.name=='nt' else 'apksigner'))
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
