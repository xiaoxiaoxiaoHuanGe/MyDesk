#!/usr/bin/env python3
"""Restricted SSH receiver for an existing /opt/mydesk Docker Compose deployment.

No shell commands or filesystem paths are accepted from SSH. Uploads contain only
a Docker image; the server's Compose configuration and private data stay local.
"""
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path('/opt/mydesk')
IMAGE_ID = re.compile(r'sha256:[0-9a-f]{64}')


def parse_command(command):
    match = re.fullmatch(r'deploy ([0-9a-f]{40})', command)
    if not match:
        raise ValueError('Only deploy followed by a complete commit SHA is allowed')
    return match[1]


def receive(stream, destination, limit=1024**3):
    size = 0
    try:
        with destination.open('xb') as output:
            os.chmod(destination, 0o600)
            while chunk := stream.read(1024**2):
                size += len(chunk)
                if size > limit:
                    raise ValueError('Image upload exceeds the size limit')
                output.write(chunk)
        if not size:
            raise ValueError('Image upload is empty')
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def validate_archive(path, sha):
    """Check Docker save metadata without extracting any uploaded files."""
    with tarfile.open(path, 'r:gz') as package:
        names = set()
        size = 0
        for member in package:
            name = PurePosixPath(member.name)
            size += member.size
            if (name.is_absolute() or '..' in name.parts or '\\' in member.name
                    or not (member.isfile() or member.isdir()) or member.name in names
                    or size > 4 * 1024**3 or len(names) >= 10000):
                raise ValueError('Invalid image archive')
            names.add(member.name)

        def read_json(name):
            member = package.getmember(name)
            if not member.isfile() or member.size > 1024**2:
                raise ValueError('Invalid image metadata')
            with package.extractfile(member) as source:
                return json.load(source)

        manifest = read_json('manifest.json')
        if len(manifest) != 1 or manifest[0].get('RepoTags') != ['mydesk-deploy:' + sha]:
            raise ValueError('Upload must contain exactly the requested MyDesk image')
        config = read_json(manifest[0]['Config'])
        labels = config.get('config', {}).get('Labels') or {}
        if (config.get('os') != 'linux' or config.get('architecture') != 'amd64'
                or labels.get('org.opencontainers.image.revision') != sha):
            raise ValueError('Image platform or commit does not match the deployment')


def command(args):
    result = subprocess.run(args, check=True, capture_output=True, text=True, timeout=600)
    return result.stdout


def backup_data(root, destination):
    with tarfile.open(destination / 'data.tar.gz', 'w:gz', dereference=False) as package:
        package.add(root / 'data', arcname='data')
    os.chmod(destination / 'data.tar.gz', 0o600)


def write_image(path, image):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps({'services': {'mydesk': {'image': image}}}) + '\n', encoding='utf-8')
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def deploy(root, archive, sha, run=command):
    parse_command('deploy ' + sha)
    validate_archive(archive, sha)
    if (not (root / 'compose.yaml').is_file() or not (root / 'data').is_dir()
            or (root / 'data').is_symlink()):
        raise ValueError('Existing Compose configuration and local data directory are required')
    current = json.loads(run(['docker', 'inspect', 'mydesk']))[0]
    previous = current['Image']
    project = (current['Config'].get('Labels') or {}).get('com.docker.compose.project')
    mounts = [mount for mount in current['Mounts'] if mount['Destination'] == '/data']
    if (not IMAGE_ID.fullmatch(previous) or project != 'mydesk'
            or current['HostConfig']['NetworkMode'] != 'host'
            or not current['State']['Running'] or len(mounts) != 1
            or Path(mounts[0]['Source']).resolve() != (root / 'data').resolve()
            or not mounts[0].get('RW')):
        raise ValueError('Existing container must be running with the expected project, network and data mount')

    # Receive, validate and load while the old application is still running.
    run(['docker', 'load', '--input', str(archive)])
    loaded = json.loads(run(['docker', 'image', 'inspect', 'mydesk-deploy:' + sha]))[0]['Id']
    if not IMAGE_ID.fullmatch(loaded):
        raise ValueError('Docker did not return an immutable image ID')
    backups = root / 'backups'
    backups.mkdir(mode=0o700, exist_ok=True)
    os.chmod(backups, 0o700)
    destination = Path(tempfile.mkdtemp(prefix='actions-' + sha[:12] + '-', dir=backups))
    os.chmod(destination, 0o700)
    override = root / 'compose.actions.json'
    (destination / 'compose.yaml').write_bytes((root / 'compose.yaml').read_bytes())
    if override.exists():
        (destination / 'compose.actions.json').write_bytes(override.read_bytes())
    (destination / 'deployment.json').write_text(json.dumps({
        'commit': sha, 'previous_image': previous, 'new_image': loaded,
    }) + '\n', encoding='utf-8')
    compose = ['docker', 'compose', '--project-name', 'mydesk', '--project-directory', str(root),
               '-f', str(root / 'compose.yaml'), '-f', str(override)]
    up = ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '120', 'mydesk']
    try:
        # Stopping the writer makes SQLite, credentials and account backups consistent.
        run(['docker', 'stop', '--time', '60', 'mydesk'])
        backup_data(root, destination)
        write_image(override, loaded)
        run(compose + up)
    except Exception as error:
        try:
            write_image(override, previous)
            run(compose + up)
        except Exception as rollback_error:
            raise RuntimeError('Deployment and image rollback failed; inspect the server. Backup: ' + str(destination)) from rollback_error
        raise RuntimeError('Deployment failed; the previous image was restarted. Backup: ' + str(destination)) from error
    print('Deployed commit ' + sha + '; backup: ' + str(destination), flush=True)
    return destination


def main():
    import fcntl  # Linux only; importing the module for tests works on Windows.
    os.umask(0o077)
    sha = parse_command(os.environ.get('SSH_ORIGINAL_COMMAND', ''))
    if os.geteuid() != 0:
        raise ValueError('The installed receiver must run as root')
    state = ROOT / '.actions'
    state.mkdir(mode=0o700, exist_ok=True)
    os.chmod(state, 0o700)
    # Serialise deployments even if another workflow or SSH connection starts.
    with (state / 'deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with tempfile.TemporaryDirectory(dir=state) as directory:
            path = Path(directory) / 'image.tar.gz'
            receive(sys.stdin.buffer, path)
            # Finish the transaction even if the client disconnects; SIGTERM still
            # triggers the normal rollback path when delivered during an update.
            signal.signal(signal.SIGHUP, signal.SIG_IGN)
            def interrupted(signum, frame):
                raise RuntimeError('Deployment interrupted')
            signal.signal(signal.SIGTERM, interrupted)
            signal.signal(signal.SIGINT, interrupted)
            deploy(ROOT, path, sha)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Do not dump captured Docker inspection, Compose environment or credentials.
        print(str(error) if isinstance(error, (ValueError, RuntimeError)) else 'Deployment failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
