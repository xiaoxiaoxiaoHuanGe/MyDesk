"""Public Hitokoto short quotes. No accounts, credentials or device data are sent."""
import json
from uuid import UUID
from aiohttp import ClientTimeout

URL='https://v1.hitokoto.cn/?c=d&c=i&c=k&min_length=8&max_length=32'
FALLBACK={'text':'把今天的事情，安静地安排好。','source':'','author':'','url':''}

async def poll_quote(session,url=URL):
    async with session.get(url,timeout=ClientTimeout(total=5),allow_redirects=False) as response:
        response.raise_for_status()
        chunks=[];size=0
        async for chunk in response.content.iter_chunked(4096):
            size+=len(chunk)
            if size>16384:raise ValueError('一言响应过大')
            chunks.append(chunk)
        value=json.loads(b''.join(chunks))
    if not isinstance(value,dict):raise ValueError('一言格式无效')
    text=value.get('hitokoto')
    if not isinstance(text,str) or not 1<=len(text.strip())<=48 or any(ord(c)<32 for c in text.strip()):
        raise ValueError('一言内容无效')
    def label(key):
        value_text=value.get(key)
        return value_text.strip()[:80] if isinstance(value_text,str) else ''
    link='https://hitokoto.cn/'
    try:link+='?uuid='+str(UUID(value.get('uuid','')))
    except (ValueError,TypeError,AttributeError):pass
    return dict(text=text.strip(),source=label('from'),author=label('from_who'),url=link)
