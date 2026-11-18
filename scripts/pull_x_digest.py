"""Pull immutable X digest payloads from the private collector's export branch."""
import hashlib
import io
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


HOME = Path('/opt/mydesk/x-digest-pull')
TOKEN = Path('/opt/mydesk/data/x-digest-token')
REPO = 'ssh://git@ssh.github.com:443/xiaoxiaoxiaoHuanGe/x-daily-intelligence.git'
REF = 'refs/heads/mydesk/x-digest-export'
LOOPBACK = 'http://127.0.0.1:8787/api/x-digest'
MIME = {'jpg':'image/jpeg','png':'image/png','webp':'image/webp'}
PHOTO = re.compile(r'media/([a-f0-9]{64})\.(jpg|png|webp)')
EDITION = re.compile(r'editions/(\d{4}-\d{2}-\d{2}_\d{6})\.zip')
MAX_JSON = 2*1024*1024
MAX_IMAGE = 6*1024*1024
MAX_ZIP = 48*1024*1024


def import_edition(blob, name, upload, ingest):
    if len(blob)>MAX_ZIP:
        raise ValueError('Archive size limit')
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        entries = archive.infolist()
        files = {entry.filename:entry for entry in entries}
        if len(entries)>169 or len(entries)!=len(files) or 'edition.json' not in files:
            raise ValueError('Unexpected archive entries')
        if any(e.compress_type!=zipfile.ZIP_STORED or e.file_size >
               (MAX_JSON if e.filename=='edition.json' else MAX_IMAGE) for e in entries):
            raise ValueError('Oversized or compressed entry')
        data = json.loads(archive.read('edition.json'))
        if not isinstance(data,dict) or data.get('id')!=name:
            raise ValueError('Edition does not match the archive name')
        hashes = {digest for post in data['posts'] for digest in post.get('media',{}).values()}
        photos = {}
        for entry in entries:
            if entry.filename == 'edition.json':
                continue
            match = PHOTO.fullmatch(entry.filename)
            if match is None:
                raise ValueError('Invalid media filename')
            digest, ext = match.groups()
            if digest in photos or digest not in hashes:
                raise ValueError('Duplicate or unused media')
            content = archive.read(entry)
            if hashlib.sha256(content).hexdigest()!=digest:
                raise ValueError('Photo does not match its SHA-256')
            photos[digest] = (MIME[ext], content)
        if photos.keys()!=hashes:
            raise ValueError('Edition references a missing photo')
    pending = set()
    for digest, (mime, content) in photos.items():
        try:
            upload(digest,mime,content)
        except HTTPError as error:
            if error.code not in (408, 429, 500, 502, 503, 504, 507):
                raise
            pending.add(digest)
    for post in data['posts']:
        post['media'] = {url:digest for url,digest in post.get('media',{}).items() if digest not in pending}
    data.pop('export_complete', None)
    ingest(data)
    return not pending


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def sync(home=HOME):
    gitdir = home/'collector.git'
    ssh = ('ssh -F /dev/null -o BatchMode=yes -o StrictHostKeyChecking=yes -o UpdateHostKeys=no '
           '-o ConnectTimeout=10 -o IdentitiesOnly=yes -o HostKeyAlgorithms=ssh-ed25519 '
           f'-o UserKnownHostsFile={home}/known_hosts -i {home}/id_ed25519')
    env = dict(os.environ,GIT_SSH_COMMAND=ssh,GIT_TERMINAL_PROMPT='0',GIT_CONFIG_NOSYSTEM='1')
    def git(*args):
        return subprocess.run(['git','--git-dir',str(gitdir),*args], env=env, timeout=240,
                              check=True,stdout=subprocess.PIPE).stdout
    if not gitdir.is_dir():
        git('init','--bare')
    git('fetch','--quiet','--no-tags','--depth=1',REPO,REF)
    tree = git('ls-tree','-r','-z','FETCH_HEAD').decode('ascii').rstrip('\x00').split('\x00')
    if len(tree)>12:
        raise ValueError('Too many files in export tree')
    state = home/'imported.json'
    done = json.loads(state.read_text()) if state.is_file() else {}
    token = TOKEN.read_text(encoding='utf-8').strip()
    if not re.fullmatch(r'[a-fA-F0-9]{64}',token):
        raise ValueError('Unexpected local sync token format')
    opener = build_opener(ProxyHandler({}),NoRedirect())

    def request(path, data, mime, method):
        req = Request(LOOPBACK+path,data=data,method=method,
                      headers={'Authorization':'Bearer '+token,'Content-Type':mime})
        with opener.open(req,timeout=25) as response:
            return json.loads(response.read(8192))

    def upload(digest,mime,content):
        if request('/media/'+digest,content,mime,'PUT').get('sha256')!=digest:
            raise ValueError('Unexpected media receipt')

    def ingest(data):
        receipt = request('/ingest',json.dumps(data,ensure_ascii=False,separators=(',',':')).encode(),
                          'application/json','POST')
        if receipt.get('batch')!=data['id'] or receipt.get('accepted')!=len(data['posts']):
            raise ValueError('Unexpected ingest receipt')
        print(f'{data["id"]}: imported {len(data["posts"])} posts, '
              f'{sum(bool(p.get("translation")) for p in data["posts"])} Chinese translations, '
              f'{len({v for p in data["posts"] for v in p.get("media",{}).values()})} photos',flush=True)

    failed = False
    retained = {}
    for item in sorted(tree):
        meta,path = item.split('\t',1)
        mode,kind,oid = meta.split()
        match = EDITION.fullmatch(path)
        if not match or mode!='100644' or kind!='blob' or not re.fullmatch(r'[a-f0-9]{40}',oid):
            raise ValueError('Invalid export tree path')
        name = match[1]
        if done.get(name)==oid:
            retained[name] = oid
            continue
        try:
            if int(git('cat-file','-s',oid))>MAX_ZIP:
                raise ValueError('Bundle exceeds size limit')
            if import_edition(git('cat-file','blob',oid),name,upload,ingest):
                retained[name] = oid
            else:
                print(f'{name}: some photos must be retried',flush=True)
                failed = True
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            print(f'{name}: not imported ({type(error).__name__})',flush=True)
            failed = True
    temp = home/'imported.tmp'
    temp.write_text(json.dumps(retained))
    temp.replace(state)
    return int(failed)


if __name__=='__main__':
    os.umask(0o077)
    raise SystemExit(sync())
