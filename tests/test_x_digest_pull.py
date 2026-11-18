import hashlib
import io
import json
import unittest
import zipfile
from urllib.error import HTTPError

from scripts.pull_x_digest import import_edition


BATCH = '2026-11-18_144925'
URL = 'https://pbs.twimg.com/media/photo.jpg'
CONTENT = b'\xff\xd8\xfftest'
DIGEST = hashlib.sha256(CONTENT).hexdigest()


def bundle(content=CONTENT, extra=None):
    target = io.BytesIO()
    with zipfile.ZipFile(target, 'w') as z:
        z.writestr('edition.json', json.dumps({
            'id': BATCH, 'posts': [{'id':'123', 'text':'Original', 'translation':'简体中文',
                                    'images':[URL], 'media':{URL:DIGEST}}]}))
        z.writestr(f'media/{DIGEST}.jpg', content)
        if extra:
            z.writestr(extra, b'invalid')
    return target.getvalue()


class PullDigestTests(unittest.TestCase):
    def test_uploads_verified_photo_before_writing_edition(self):
        calls = []
        import_edition(bundle(), BATCH, lambda *args: calls.append(args), lambda x: calls.append(x))
        self.assertEqual(calls[0], (DIGEST, 'image/jpeg', CONTENT))
        self.assertEqual(calls[1]['posts'][0]['translation'], '简体中文')

    def test_invalid_photo_or_unexpected_file_performs_no_writes(self):
        for candidate in [bundle(b'wrong'), bundle(extra='../test')]:
            with self.subTest(candidate=len(candidate)), self.assertRaises(ValueError):
                import_edition(candidate, BATCH, lambda *_: self.fail('upload'), lambda _: self.fail('ingest'))

    def test_image_storage_limit_preserves_the_translation_and_marks_bundle_for_retry(self):
        writes = []
        def upload(*args):
            raise HTTPError('http://localhost/media', 507, 'Storage full', {}, None)
        self.assertFalse(import_edition(bundle(), BATCH, upload, writes.append))
        item = writes[0]['posts'][0]
        self.assertEqual(item['translation'], '简体中文')
        self.assertEqual(item['images'], [URL])
        self.assertEqual(item['media'], {})
