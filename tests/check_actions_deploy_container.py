"""Linux CI integration check against disposable containers and private test data."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.deploy_server import command, deploy


def check():
    sha = os.environ['GITHUB_SHA']
    tag = 'mydesk-deploy:' + sha
    archive = Path('image.tar.gz').resolve()
    original_image = json.loads(command(['docker', 'image', 'inspect', tag]))[0]['Id']
    with tempfile.TemporaryDirectory(prefix='mydesk-actions-check-') as directory:
        root = Path(directory)
        data = root / 'data'
        data.mkdir()
        os.chown(data, 10001, 10001)
        os.chmod(data, 0o700)
        marker = data / 'preserved.txt'
        marker.write_text('test-owned data must survive both updates')
        service = {
            'container_name': 'mydesk', 'image': tag, 'network_mode': 'host',
            'command': ['python', '-m', 'mydesk', '--host', '127.0.0.1', '--port', '8787', '--data', '/data'],
            'volumes': [str(data) + ':/data'], 'stop_grace_period': '60s',
            'healthcheck': {'test': ['CMD', 'python', '-c', "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/health',timeout=2)"],
                            'interval': '1s', 'timeout': '3s', 'retries': 3, 'start_period': '2s'},
        }
        config = root / 'compose.yaml'
        config.write_text(json.dumps({'name': 'mydesk', 'services': {'mydesk': service}}))
        compose = ['docker', 'compose', '-p', 'mydesk', '-f', str(config)]
        try:
            command(compose + ['up', '-d', '--wait', '--wait-timeout', '60'])
            backup = deploy(root, archive, sha)
            assert marker.read_text() == 'test-owned data must survive both updates'
            with tarfile.open(backup / 'data.tar.gz') as saved:
                assert saved.extractfile('data/auth.sqlite3').read()
                assert saved.extractfile('data/preserved.txt').read() == marker.read_bytes()

            # Build a validly labelled image whose application cannot start. The
            # receiver must detect the failure and bring the previous image back.
            base_tag = 'mydesk-ci-base:' + sha
            command(['docker', 'tag', original_image, base_tag])
            dockerfile = 'FROM ' + base_tag + '\nUSER root\nRUN rm -rf /app/mydesk\nUSER mydesk\n'
            subprocess.run(['docker', 'build', '--label', 'org.opencontainers.image.revision=' + sha,
                            '-t', tag, '-'], input=dockerfile, text=True, check=True)
            failed_archive = root / 'unhealthy.tar.gz'
            # docker save writes an uncompressed tar; gzip it into the wire format.
            import gzip
            raw = subprocess.run(['docker', 'save', tag], capture_output=True, check=True).stdout
            with gzip.open(failed_archive, 'wb') as output:
                output.write(raw)
            try:
                deploy(root, failed_archive, sha)
            except RuntimeError as error:
                assert 'previous image was restarted' in str(error)
            else:
                raise AssertionError('An image without the application must fail deployment')
            state = json.loads(command(['docker', 'inspect', 'mydesk']))[0]
            assert state['Image'] == original_image
            assert state['State']['Health']['Status'] == 'healthy'
            assert marker.read_text() == 'test-owned data must survive both updates'
            print('Real Docker deployment, private-data backup and unhealthy-image rollback passed.')
        finally:
            command(compose + ['down'])


if __name__ == '__main__':
    check()
