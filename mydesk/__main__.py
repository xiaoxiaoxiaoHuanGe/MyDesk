import argparse
import asyncio
import signal
import ipaddress
import ssl
from aiohttp import web
from .app import create_app

parser=argparse.ArgumentParser(description='MyDesk independent server')
parser.add_argument('--data',default='.local/standalone')
parser.add_argument('--host',default='127.0.0.1')
parser.add_argument('--port',type=int,default=8787)
parser.add_argument('--cert')
parser.add_argument('--key')
parser.add_argument('--tls-port',type=int,default=8443)
parser.add_argument('--tls-host',type=lambda value:str(ipaddress.ip_address(value)),help='HTTPS 的监听地址；默认与 --host 相同')
parser.add_argument('--trusted-proxy',action='append',default=[],type=lambda value:str(ipaddress.ip_address(value)))
args=parser.parse_args()
context=None
if args.cert or args.key:
    if not args.cert or not args.key:
        parser.error('--cert 和 --key 需要同时提供')
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert,args.key)
async def serve():
    runner=web.AppRunner(create_app(args.data,trusted_proxies=args.trusted_proxy),access_log=None)
    await runner.setup()
    stop=asyncio.Event()
    loop=asyncio.get_running_loop()
    for signum in (signal.SIGINT,signal.SIGTERM):
        try:
            loop.add_signal_handler(signum,stop.set)
        except NotImplementedError:
            pass
    try:
        await web.TCPSite(runner,args.host,args.port).start()
        if context:
            await web.TCPSite(runner,args.tls_host or args.host,args.tls_port,ssl_context=context).start()
        print(f'MyDesk running on port {args.port}; data: {args.data}')
        await stop.wait()
    finally:
        await runner.cleanup()

try:
    asyncio.run(serve())
except KeyboardInterrupt:
    pass
