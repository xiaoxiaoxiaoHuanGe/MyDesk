"""Start the independent local app, with non-destructive one-time business-data migration."""
import argparse
import ipaddress
import json
import os
import sqlite3
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import urlopen

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))


def migrate(root):
    source=root/'.local/homeassistant/.storage/mydesk.sqlite3'
    target=root/'.local/standalone/mydesk.sqlite3'
    if target.exists() or not source.exists():
        return False
    target.parent.mkdir(parents=True,exist_ok=True)
    src=sqlite3.connect(source.as_uri()+'?mode=ro',uri=True)
    dst=sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()
    return True


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--phone',action='store_true')
    parser.add_argument('--lan-ip')
    parser.add_argument('--no-build',action='store_true')
    args=parser.parse_args()
    if args.phone and not args.lan_ip:
        parser.error('--phone 需要 --lan-ip 指定本机局域网 IPv4 地址')
    if args.lan_ip:
        address=ipaddress.ip_address(args.lan_ip)
        if address.version!=4 or not address.is_private or address.is_loopback:
            parser.error('请提供本机局域网 IPv4 地址')
    if migrate(ROOT):
        print('Existing MyDesk business data copied; original HA data retained.')
    from local_tls import prepare_tls
    prepare_tls(ROOT/'.local/standalone/tls',args.lan_ip)
    info=json.loads(subprocess.run(['docker','version','--format','{{json .Server}}'],check=True,capture_output=True,text=True).stdout)
    env=os.environ.copy()
    env.setdefault('DOCKER_API_VERSION',info['ApiVersion'])
    if hasattr(os,'getuid'):
        env['MYDESK_UID']=str(os.getuid())
        env['MYDESK_GID']=str(os.getgid())
    command=['docker','compose','-f','deploy/compose.local.yaml']
    if args.phone:
        env['MYDESK_LAN_IP']=args.lan_ip
        command+=['-f','deploy/compose.phone.yaml']
    command+=['up','-d']+([] if args.no_build else ['--build'])
    subprocess.run(command,cwd=ROOT,env=env,check=True)
    for _ in range(120):
        try:
            with urlopen('http://127.0.0.1:8787/health',timeout=2) as response:
                if response.status==200:
                    break
        except OSError:
            time.sleep(1)
    else:
        raise RuntimeError('MyDesk did not become healthy within 120 seconds')
    print('MyDesk: http://127.0.0.1:8787/')
    print('Login details: '+str(ROOT/'.local/standalone/LOCAL_ACCESS.md'))
    if args.phone:
        print('Android LAN URL: https://'+args.lan_ip+':8443/')
        print('Trust the local CA on the phone: '+str(ROOT/'.local/standalone/tls/mydesk-local-ca.crt'))

if __name__=='__main__':
    main()
