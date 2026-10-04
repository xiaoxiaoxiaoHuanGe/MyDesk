"""Build an allowlisted deployment archive. Private runtime state is never read."""
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def build(output):
    output=Path(output)
    output.parent.mkdir(parents=True,exist_ok=True)
    files=[ROOT/name for name in ('README.md','LICENSE','THIRD_PARTY_NOTICES.md','SECURITY.md','CONTRIBUTING.md','requirements-dev.txt','requirements.txt','.dockerignore','.gitignore')]
    files.append(ROOT/'assets/mydesk-logo.png')
    files.append(ROOT/'.github/workflows/ci.yml')
    rules={'mydesk':{'.py'}, 'frontend':{'.js','.css','.json','.html','.svg','.png','.webp','.webmanifest'},
           'docs':{'.md'},'scripts':{'.py','.sh','.cjs','.ps1'},'tests':{'.py','.mjs'}}
    for folder,extensions in rules.items():
        files.extend(path for path in (ROOT/folder).glob('*') if path.is_file() and path.suffix in extensions and not path.name.startswith(('ha_','start_local_ha','browser_check_ha','dev_server')))
    files.extend(ROOT/'deploy'/name for name in ['Dockerfile','compose.yaml','compose.local.yaml','compose.phone.yaml','compose.server.yaml','mydesk-ping.conf','.env.example','openresty-location.conf','github-report-step.yaml'])
    android=ROOT/'android'
    for path in android.rglob('*'):
        relative=path.relative_to(android)
        if not path.is_file() or any(part in {'.gradle','.kotlin','.local','build'} for part in relative.parts):continue
        if path.name in {'local.properties','google-services.json','mydesk_local_ca.crt'}:continue
        if path.suffix in {'.kt','.kts','.xml','.md','.properties','.pro','.png'} or relative.as_posix() in {'gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar'}:
            files.append(path)
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(files):
            archive.write(file,'mydesk/'+file.relative_to(ROOT).as_posix())
    return output


if __name__=='__main__':
    output=build(ROOT/'dist/MyDesk-1.0.0-source.zip')
    print(f'Release: {output}')
