"""Check the running independent container without printing credentials or notification endpoints."""
import argparse
import asyncio
import json
import ssl
from pathlib import Path
from aiohttp import ClientSession,CookieJar,TCPConnector

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'.local/standalone'
BASE='http://127.0.0.1:8787'
FIXTURE=DATA/'smoke-fixture.json'


async def check(after_restart=False,network_check=False):
    access=json.loads((DATA/'local-access.json').read_text(encoding='utf-8'))
    async with ClientSession(cookie_jar=CookieJar(unsafe=True)) as session:
        if after_restart:
            fixture=json.loads(FIXTURE.read_text(encoding='utf-8'))
            session.cookie_jar.update_cookies({'mydesk_session':fixture['session']})
            async with session.get(BASE+'/api/session') as response:
                assert response.status==200,'Session was not preserved across container restart'
                csrf=(await response.json())['csrf']
        else:
            async with session.post(BASE+'/api/login',json=access) as response:
                assert response.status==200,'Login failed'
                csrf=(await response.json())['csrf']
                token=response.cookies['mydesk_session'].value
        headers={'X-MyDesk-CSRF':csrf}
        async def command(action,payload=None):
            async with session.post(BASE+'/api/command',json={'action':action,'payload':payload or {}},headers=headers) as response:
                assert response.status==200,'Command failed: '+action
                return await response.json()
        async with session.ws_connect(BASE+'/api/ws') as socket:
            state=await socket.receive_json(timeout=5)
            assert state['type']=='snapshot'
        if after_restart:
            state=await command('snapshot')
            reminder=next(r for r in state['reminders'] if r['id']==fixture['reminder']['id'])
            assert all(reminder[key]==fixture['reminder'][key] for key in ('title','remind_at','status','revision'))
            await command('reminder/action',{'id':reminder['id'],'action':'complete'})
            FIXTURE.unlink()
            print('Container restart verified: existing session and reminder ID/time/status/revision preserved.')
        else:
            reminder=await command('reminder/create',{'title':'独立容器重启验证','time':'30分钟后'})
            fixture={'session':token,'reminder':reminder}
            FIXTURE.write_text(json.dumps(fixture,ensure_ascii=False),encoding='utf-8')
            print('Independent HTTP login, authenticated WebSocket and persistent reminder verified.')
        if network_check:
            async with session.get(BASE+'/api/settings') as response:
                settings=await response.json()
            network=settings['network']
            if not network['enabled']:
                network={'enabled':True,'nodes':[{'name':'Internet','host':'1.1.1.1'}]}
                async with session.put(BASE+'/api/settings',json={'network':network},headers=headers) as response:
                    assert response.status==200
            for _ in range(90):
                state=await command('snapshot')
                if state['feeds'].get('network'):
                    break
                await asyncio.sleep(1)
            data=state['feeds']['network']['data']
            assert 'error' not in data and type(data['online']) is bool
            assert data['public_ip'] is not None,'Public IP probe did not return a result'
            assert data['nodes'] and type(data['nodes'][0]['online']) is bool
            print('Actual container network probes returned connectivity, public IP and ICMP node result.')
        tls=ssl.create_default_context(cafile=str(DATA/'tls/mydesk-local-ca.crt'))
        async with ClientSession(cookie_jar=CookieJar(unsafe=True),connector=TCPConnector(ssl=tls)) as secure:
            async with secure.post('https://127.0.0.1:8443/api/login',json=access) as response:
                assert response.status==200
                assert response.cookies['mydesk_session']['secure']
            async with secure.ws_connect('https://127.0.0.1:8443/api/ws') as socket:
                assert (await socket.receive_json(timeout=5))['type']=='snapshot'
        print('Local HTTPS chain/hostname, Secure login cookie and authenticated WSS verified.')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--after-restart',action='store_true')
    parser.add_argument('--network-check',action='store_true')
    args=parser.parse_args()
    asyncio.run(check(args.after_restart,args.network_check))
