"""Authenticated X digest ingest and reader endpoints. Never fetch remote media."""
import asyncio
import hmac
import json
import re
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from aiohttp import web

CATEGORIES = dict(ai='AI 人工智能', tech='科技 / 开源', world='全球新闻',
                  finance='财经 / 加密货币', chinese='中文互联网', digital='数码 / 软件')
PREFIX = '/api/x-digest'
MAX_BODY = 2 * 1024 * 1024


def text(value, limit):
    if not isinstance(value, str) or len(value) > limit or '\x00' in value:
        raise ValueError('无效文本')
    return value.strip()


def timestamp(value):
    parsed = datetime.fromisoformat(text(value, 40).replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('时间必须包含时区')
    return parsed.astimezone(timezone.utc).isoformat(timespec='seconds')


def photo(value):
    value = text(value, 1024)
    url = urlsplit(value)
    if (url.scheme != 'https' or url.netloc != 'pbs.twimg.com' or
            not re.fullmatch(r'/media/[A-Za-z0-9_.-]+', url.path) or url.fragment
            or any(ord(c) < 33 for c in value)):
        raise ValueError('图片地址无效')
    return value


def count(value):
    if type(value) is not int or not 0 <= value <= 10**12:
        raise ValueError('互动计数无效')
    return value


class XDigest:
    def __init__(self, desk, retention=0):
        self.desk, self.retention = desk, max(0, min(3650, int(retention)))
        with desk.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS x_briefings(
                    id TEXT PRIMARY KEY, collected_at TEXT NOT NULL, summary TEXT NOT NULL, trends TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS x_posts(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL, batch TEXT NOT NULL,
                    collected_at TEXT NOT NULL, observed_at TEXT NOT NULL, posted_at TEXT NOT NULL, category TEXT NOT NULL,
                    author TEXT NOT NULL, name TEXT NOT NULL, text TEXT NOT NULL, translation TEXT NOT NULL,
                    title TEXT NOT NULL, summary TEXT NOT NULL, likes INTEGER NOT NULL,
                    reposts INTEGER NOT NULL, images TEXT NOT NULL, video INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS x_posts_category ON x_posts(category,seq);
                CREATE INDEX IF NOT EXISTS x_posts_collected ON x_posts(collected_at,seq);
                CREATE TABLE IF NOT EXISTS x_reader(
                    username TEXT NOT NULL, id TEXT NOT NULL, read INTEGER NOT NULL DEFAULT 0,
                    saved INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(username,id));
            """)

    def ingest(self, payload):
        batch = text(payload['id'], 80)
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+', batch):
            raise ValueError('批次标识无效')
        collected = timestamp(payload['collected_at'])
        summary = text(payload.get('summary', ''), 40000)
        trends = payload.get('trends', [])
        posts = payload['posts']
        if not isinstance(trends, list) or len(trends) > 200 or not isinstance(posts, list) or len(posts) > 100:
            raise ValueError('批次过大')
        trends = [
            dict(name=text(v['name'],240),location=text(v.get('location',''),40),
                 rank=count(v['rank']) if v.get('rank') is not None else None)
            if isinstance(v,dict) else text(v,300) for v in trends
        ]
        with self.desk.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute("""INSERT INTO x_briefings VALUES (?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                summary=COALESCE(NULLIF(excluded.summary,''),x_briefings.summary),
                trends=CASE WHEN excluded.trends='[]' THEN x_briefings.trends ELSE excluded.trends END""",
                (batch, collected, summary, json.dumps(trends, ensure_ascii=False)))
            for post in posts:
                identifier, author = text(post['id'], 24), text(post['author'], 30).removeprefix('@')
                if not re.fullmatch(r'[0-9]{1,24}', identifier) or not re.fullmatch(r'[A-Za-z0-9_]{1,30}', author):
                    raise ValueError('帖子标识或账号无效')
                category = post['category']
                images = post.get('images', [])
                video = post.get('video', False)
                if category not in CATEGORIES or not isinstance(images, list) or len(images) > 4 or type(video) is not bool:
                    raise ValueError('分类或附件无效')
                original = text(post['text'], 40000)
                if not original and not db.execute('SELECT 1 FROM x_posts WHERE id=?', (identifier,)).fetchone():
                    raise ValueError('缺少原帖正文')
                db.execute("""INSERT INTO x_posts
                    (id,batch,collected_at,observed_at,posted_at,category,author,name,text,translation,title,summary,likes,reposts,images,video)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                    likes=CASE WHEN excluded.observed_at>=x_posts.observed_at THEN excluded.likes ELSE x_posts.likes END,
                    reposts=CASE WHEN excluded.observed_at>=x_posts.observed_at THEN excluded.reposts ELSE x_posts.reposts END,
                    observed_at=MAX(excluded.observed_at,x_posts.observed_at),
                    translation=CASE WHEN excluded.observed_at>=x_posts.observed_at OR x_posts.translation=''
                        THEN COALESCE(NULLIF(excluded.translation,''),x_posts.translation) ELSE x_posts.translation END,
                    title=CASE WHEN excluded.observed_at>=x_posts.observed_at OR x_posts.title=''
                        THEN COALESCE(NULLIF(excluded.title,''),x_posts.title) ELSE x_posts.title END,
                    summary=CASE WHEN excluded.observed_at>=x_posts.observed_at OR x_posts.summary=''
                        THEN COALESCE(NULLIF(excluded.summary,''),x_posts.summary) ELSE x_posts.summary END,
                    images=CASE WHEN excluded.images<>'[]' AND (excluded.observed_at>=x_posts.observed_at OR x_posts.images='[]')
                        THEN excluded.images ELSE x_posts.images END,
                    video=MAX(x_posts.video,excluded.video)""",
                    (identifier,batch,collected,collected,timestamp(post['posted_at']),category,author,text(post.get('name',''),100),
                     original,text(post.get('translation',''),40000),text(post.get('title',''),300),
                     text(post.get('summary',''),5000),count(post['likes']),count(post.get('reposts',0)),
                     json.dumps(list(dict.fromkeys(photo(v) for v in images))),int(video)))
            if self.retention:
                cutoff = (datetime.now(timezone.utc)-timedelta(days=self.retention)).isoformat(timespec='seconds')
                db.execute("""DELETE FROM x_posts WHERE collected_at<? AND NOT EXISTS
                    (SELECT 1 FROM x_reader WHERE id=x_posts.id AND saved=1)""", (cutoff,))
                db.execute('DELETE FROM x_reader WHERE id NOT IN (SELECT id FROM x_posts)')
                db.execute('DELETE FROM x_briefings WHERE collected_at<? AND id NOT IN (SELECT batch FROM x_posts)', (cutoff,))
        return {'accepted': len(posts), 'batch': batch}

    @staticmethod
    def card(row):
        post = dict(row)
        post.pop('seq')
        post['images'] = json.loads(post['images'])
        post['url'] = f"https://x.com/{post['author']}/status/{post['id']}"
        for key in ('saved', 'read', 'video'):
            post[key] = bool(post[key])
        return post

    def feed(self, username, query, zone):
        limit = max(1, min(50, int(query.get('limit', 20))))
        clauses, params = [], [username]
        if 'before' in query:
            collected, seq = query['before'].split('|',1)
            before = int(seq)
            if not 0 < before < 2**63:
                raise ValueError('游标无效')
            clauses.append('(p.collected_at,p.seq)<(?,?)'); params.extend((timestamp(collected),before))
        if query.get('category'):
            clauses.append('p.category=?'); params.append(query['category'])
        if query.get('date'):
            day = date.fromisoformat(query['date'])
            local = ZoneInfo(zone)
            clauses.append('p.collected_at>=? AND p.collected_at<?')
            params.extend(datetime.combine(d,datetime.min.time(),local).astimezone(timezone.utc).isoformat(timespec='seconds')
                          for d in (day,day+timedelta(days=1)))
        if query.get('bookmarks') == '1':
            clauses.append('r.saved=1')
        word = text(query.get('q',''), 120)
        if word:
            clauses.append('(instr(lower(p.title),lower(?)) OR instr(lower(p.summary),lower(?)) OR instr(lower(p.text),lower(?)))')
            params.extend([word]*3)
        where = (' WHERE '+' AND '.join(clauses)) if clauses else ''
        with self.desk.connect() as db:
            rows = db.execute("""SELECT p.seq,p.collected_at,p.id,p.category,p.name,p.author,p.title,p.summary,
                substr(p.text,1,280) AS preview,p.posted_at,p.likes,p.reposts,p.images,p.video,
                r.read,r.saved FROM x_posts p LEFT JOIN x_reader r ON r.id=p.id AND r.username=?"""
                + where + ' ORDER BY p.collected_at DESC,p.seq DESC LIMIT ?', (*params,limit+1)).fetchall()
            unread = db.execute("""SELECT COUNT(*) FROM x_posts p WHERE NOT EXISTS
                (SELECT 1 FROM x_reader r WHERE r.id=p.id AND r.username=? AND r.read=1)""", (username,)).fetchone()[0]
            latest = db.execute('SELECT collected_at FROM x_briefings ORDER BY collected_at DESC LIMIT 1').fetchone()
        return {'items':[self.card(r) for r in rows[:limit]],
                'next':f"{rows[limit-1]['collected_at']}|{rows[limit-1]['seq']}" if len(rows)>limit else None,
                'unread':unread, 'updated_at':latest[0] if latest else '',
                'categories':[{'id':k,'label':v} for k,v in CATEGORIES.items()]}

    def post(self, username, identifier, read=False, saved=None):
        with self.desk.connect() as db:
            if read or saved is not None:
                db.execute('BEGIN IMMEDIATE')
                if not db.execute('SELECT 1 FROM x_posts WHERE id=?', (identifier,)).fetchone():
                    return None
                db.execute("""INSERT INTO x_reader(username,id,read,saved) VALUES (?,?,?,?)
                    ON CONFLICT(username,id) DO UPDATE SET read=MAX(x_reader.read,excluded.read),
                    saved=COALESCE(?,x_reader.saved)""", (username,identifier,int(read),int(bool(saved)),saved))
            row = db.execute("""SELECT p.*,r.read,r.saved FROM x_posts p
                LEFT JOIN x_reader r ON r.id=p.id AND r.username=? WHERE p.id=?""", (username,identifier)).fetchone()
        return self.card(row) if row else None

    def briefings(self, query):
        limit = max(1,min(20,int(query.get('limit',10))))
        before = int(query.get('before',2**63-1))
        with self.desk.connect() as db:
            rows = db.execute('SELECT rowid AS seq,* FROM x_briefings WHERE rowid<? ORDER BY rowid DESC LIMIT ?', (before,limit+1)).fetchall()
        return {'items':[dict(r, trends=json.loads(r['trends'])) for r in rows[:limit]],
                'next':str(rows[limit-1]['seq']) if len(rows)>limit else None}


def register_x_digest(app, token, storage, zone):
    attempts = []

    async def ingest(request):
        supplied = request.headers.get('Authorization','')
        if len(token) < 32 or not hmac.compare_digest(supplied.encode(), ('Bearer '+token).encode()):
            raise web.HTTPUnauthorized(text='接收凭据无效')
        now = asyncio.get_running_loop().time()
        attempts[:] = [t for t in attempts if now-t<60]
        if len(attempts) >= 20:
            raise web.HTTPTooManyRequests(text='稍后重试')
        attempts.append(now)
        if request.content_type != 'application/json':
            raise web.HTTPUnsupportedMediaType()
        body = bytearray()
        async for chunk in request.content.iter_chunked(65536):
            if len(body)+len(chunk)>MAX_BODY:
                raise web.HTTPRequestEntityTooLarge(max_size=MAX_BODY,actual_size=len(body)+len(chunk))
            body.extend(chunk)
        return web.json_response(await asyncio.to_thread(storage().ingest,json.loads(body)))

    async def feed(request):
        return web.json_response(await asyncio.to_thread(storage().feed,request['session']['username'],request.query,zone()))

    async def detail(request):
        action = request.match_info.get('action')
        saved = None
        if action == 'bookmark':
            saved = (await request.json()).get('saved')
            if type(saved) is not bool:
                raise web.HTTPBadRequest(text='收藏状态无效')
        result = await asyncio.to_thread(storage().post,request['session']['username'],request.match_info['id'],action=='read',saved)
        if result is None:
            raise web.HTTPNotFound(text='帖子不存在')
        return web.json_response(result)

    async def briefings(request):
        return web.json_response(await asyncio.to_thread(storage().briefings,request.query))

    app.router.add_post(PREFIX+'/ingest',ingest)
    app.router.add_get(PREFIX+'/feed',feed)
    app.router.add_get(PREFIX+'/briefings',briefings)
    app.router.add_get(PREFIX+'/posts/{id:[0-9]{1,24}}',detail)
    app.router.add_post(PREFIX+'/posts/{id:[0-9]{1,24}}/{action:read|bookmark}',detail)
