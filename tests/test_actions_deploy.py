"""Exercise deployment with real archives/files and a simulated Docker daemon."""
import importlib.util
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SHA = 'a' * 40
OLD = 'sha256:' + 'b' * 64
NEW = 'sha256:' + 'c' * 64


def archive(path, tags=None, revision=SHA):
    with tarfile.open(path, 'w:gz') as package:
        entries = {
            'manifest.json': [{'Config': 'config.json', 'RepoTags': tags or ['mydesk-deploy:' + SHA], 'Layers': []}],
            'config.json': {'os': 'linux', 'architecture': 'amd64', 'config': {'Labels': {'org.opencontainers.image.revision': revision}}},
        }
        for name, value in entries.items():
            data = json.dumps(value).encode()
            info = tarfile.TarInfo(name)
            info.size = len(data)
            package.addfile(info, io.BytesIO(data))


class Docker:
    def __init__(self, root, fail_new=False):
        self.root = root
        self.calls = []
        self.fail_new = fail_new
        self.stopped = False

    def __call__(self, args):
        self.calls.append(args)
        if args[1:3] == ['inspect', 'mydesk']:
            return json.dumps([{'Image': OLD, 'State': {'Running': True},
                'Config': {'Labels': {'com.docker.compose.project': 'mydesk'}},
                'HostConfig': {'NetworkMode': 'host'},
                'Mounts': [{'Source': str(self.root / 'data'), 'Destination': '/data', 'RW': True}]}])
        if args[1:3] == ['image', 'inspect']:
            return json.dumps([{'Id': NEW}])
        if args[1] == 'stop':
            self.stopped = True
        if 'up' in args:
            image = json.loads((self.root / 'compose.actions.json').read_text())['services']['mydesk']['image']
            if image == NEW and self.fail_new:
                raise subprocess.CalledProcessError(1, args)
            self.stopped = False
        return ''


class ActionsDeployTests(unittest.TestCase):
    def setUp(self):
        script = ROOT / 'scripts/deploy_server.py'
        self.assertTrue(script.is_file(), 'The Actions deployment receiver is missing')
        spec = importlib.util.spec_from_file_location('actions_receiver', script)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'data').mkdir()
        (self.root / 'data/auth.sqlite3').write_bytes(b'original account database')
        (self.root / 'data/settings.json').write_bytes(b'private settings')
        (self.root / 'compose.yaml').write_text('name: mydesk\nservices: {}\n')
        self.package = self.root / 'image.tar.gz'
        archive(self.package)

    def test_only_exact_deploy_command_is_accepted(self):
        self.assertEqual(self.module.parse_command('deploy ' + SHA), SHA)
        for command in ['', 'bash', 'deploy main', 'deploy ' + SHA + '; id', 'deploy ' + SHA + '\n', 'deploy ../data']:
            with self.subTest(command=command), self.assertRaises(ValueError):
                self.module.parse_command(command)

    def test_success_backs_up_stopped_data_and_keeps_live_settings(self):
        docker = Docker(self.root)
        backup = self.module.backup_data
        def stopped_backup(*args):
            self.assertTrue(docker.stopped, 'SQLite files must be copied after the application stops')
            return backup(*args)
        with patch.object(self.module, 'backup_data', side_effect=stopped_backup):
            result = self.module.deploy(self.root, self.package, SHA, run=docker)
        with tarfile.open(result / 'data.tar.gz') as package:
            self.assertEqual(package.extractfile('data/auth.sqlite3').read(), b'original account database')
        self.assertEqual((self.root / 'data/settings.json').read_bytes(), b'private settings')
        self.assertEqual(json.loads((self.root / 'compose.actions.json').read_text())['services']['mydesk']['image'], NEW)
        self.assertEqual(json.loads((result / 'deployment.json').read_text())['previous_image'], OLD)
        self.assertFalse(docker.stopped)
        self.assertFalse(any('down' in args for args in docker.calls))

    def test_unhealthy_new_image_restores_previous_image_and_reports_failure(self):
        docker = Docker(self.root, fail_new=True)
        with self.assertRaises(RuntimeError):
            self.module.deploy(self.root, self.package, SHA, run=docker)
        self.assertEqual(json.loads((self.root / 'compose.actions.json').read_text())['services']['mydesk']['image'], OLD)
        self.assertFalse(docker.stopped)
        self.assertEqual((self.root / 'data/auth.sqlite3').read_bytes(), b'original account database')
        self.assertTrue(list((self.root / 'backups').glob('actions-*/data.tar.gz')))

    def test_backup_failure_restarts_old_image(self):
        docker = Docker(self.root)
        with patch.object(self.module, 'backup_data', side_effect=OSError('disk full')):
            with self.assertRaises(RuntimeError):
                self.module.deploy(self.root, self.package, SHA, run=docker)
        self.assertFalse(docker.stopped)
        self.assertEqual(json.loads((self.root / 'compose.actions.json').read_text())['services']['mydesk']['image'], OLD)

    def test_extra_image_tags_are_rejected_before_docker_load_or_stop(self):
        archive(self.package, tags=['mydesk-deploy:' + SHA, 'other-service:latest'])
        docker = Docker(self.root)
        with self.assertRaises(ValueError):
            self.module.deploy(self.root, self.package, SHA, run=docker)
        self.assertEqual(docker.calls, [])

    def test_wrong_revision_is_rejected(self):
        archive(self.package, revision='d' * 40)
        with self.assertRaises(ValueError):
            self.module.validate_archive(self.package, SHA)

    def test_upload_limit_rejects_incomplete_or_excessive_stream(self):
        target = self.root / 'upload'
        with self.assertRaises(ValueError):
            self.module.receive(io.BytesIO(b'123456'), target, limit=5)
        self.assertFalse(target.exists())
        with self.assertRaises(ValueError):
            self.module.receive(io.BytesIO(b''), target, limit=5)

    def test_different_data_mount_is_rejected_without_stopping_container(self):
        docker = Docker(self.root)
        def wrong_mount(args):
            result = docker(args)
            if args[1:3] == ['inspect', 'mydesk']:
                info = json.loads(result)
                info[0]['Mounts'][0]['Source'] = str(self.root / 'wrong-data')
                return json.dumps(info)
            return result
        with self.assertRaises(ValueError):
            self.module.deploy(self.root, self.package, SHA, run=wrong_mount)
        self.assertFalse(docker.stopped)
        self.assertFalse(any(args[1] == 'stop' for args in docker.calls))

    def test_release_contains_manual_deployment_workflow(self):
        from scripts.build_release import build
        import zipfile
        with zipfile.ZipFile(build(self.root / 'release.zip')) as package:
            self.assertIn('mydesk/.github/workflows/deploy.yml', package.namelist())

