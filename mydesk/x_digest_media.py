"""Bounded private storage for immutable X image copies."""
import hashlib
import os
import re
import tempfile
import threading
from pathlib import Path

MAX_IMAGE = 6 * 1024 * 1024
HEX = re.compile(r'[a-f0-9]{64}')


def image_type(content):
    if content.startswith(b'\x89PNG\r\n\x1a\n') and content[12:16] == b'IHDR':
        return 'image/png'
    if content.startswith(b'\xff\xd8\xff'):
        return 'image/jpeg'
    if content[:4] == b'RIFF' and content[8:12] == b'WEBP':
        return 'image/webp'
    return None


class XDigestMedia:
    def __init__(self, root, quota=512 * 1024 * 1024):
        self.root = Path(root)
        self.root.mkdir(mode=0o700, exist_ok=True)
        self.quota = quota
        self.lock = threading.Lock()
        self.used = sum(p.stat().st_size for p in self.root.iterdir() if HEX.fullmatch(p.name) and p.is_file())

    def path(self, digest):
        if not isinstance(digest, str) or not HEX.fullmatch(digest):
            raise ValueError('图片标识无效')
        return self.root/digest

    def save(self, digest, content, mime):
        path = self.path(digest)
        if image_type(content) != mime or not mime or hashlib.sha256(content).hexdigest() != digest:
            raise ValueError('图片格式或校验值无效')
        with self.lock:
            if path.is_file():
                return False
            if self.used+len(content)>self.quota:
                raise OverflowError('图片存储空间不足')
            with tempfile.NamedTemporaryFile(dir=self.root, delete=False) as stream:
                staging = Path(stream.name)
                try:
                    stream.write(content)
                    stream.flush()
                    os.fsync(stream.fileno())
                    stream.close()
                    staging.replace(path)
                finally:
                    staging.unlink(missing_ok=True)
            self.used += len(content)
            return True

    def open(self, digest):
        path = self.path(digest)
        try:
            with path.open('rb') as stream:
                mime = image_type(stream.read(16))
        except FileNotFoundError:
            return None
        return (path, mime) if mime else None
