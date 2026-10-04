import os
import subprocess
import unittest
import tempfile
import shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

@unittest.skipUnless(os.name=='nt','Local release script uses Windows DPAPI')
class AndroidReleaseTests(unittest.TestCase):
    def test_single_task_and_no_daemon_are_forwarded_as_whole_arguments(self):
        private_root=ROOT/'.local'
        with tempfile.TemporaryDirectory(dir=private_root) as directory:
            fixture=Path(directory)
            self.assertTrue(fixture.resolve().is_relative_to(private_root.resolve()))
            paths=('scripts','sdk/platforms/android-36','.local/android','.local/standalone/tls','.local/tools/gradle-8.13/bin','android/app/build/outputs/apk/debug')
            for path in paths:(fixture/path).mkdir(parents=True,exist_ok=True)
            shutil.copyfile(ROOT/'scripts/Build-AndroidLocal.ps1',fixture/'scripts/Build-AndroidLocal.ps1')
            (fixture/'sdk/platforms/android-36/android.jar').write_bytes(b'fixture')
            (fixture/'.local/android/debug.keystore').write_bytes(b'fixture')
            (fixture/'.local/standalone/tls/mydesk-local-ca.crt').write_text('fixture')
            (fixture/'android/app/build/outputs/apk/debug/app-debug.apk').write_bytes(b'fixture')
            gradle=fixture/'.local/tools/gradle-8.13/bin/gradle.bat'
            gradle.write_text('@echo off\n> "%~dp0arguments.txt" echo %*\nexit /b 0\n')
            for executable in filter(None,(shutil.which('pwsh'),shutil.which('powershell'))):
                result=subprocess.run([executable,'-NoProfile','-File',str(fixture/'scripts/Build-AndroidLocal.ps1'),'-SdkPath',str(fixture/'sdk'),'-SkipTests','-NoDaemon'],capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stderr)
                forwarded=(gradle.parent/'arguments.txt').read_text()
                self.assertIn(':app:assembleDebug',forwarded)
                self.assertIn('--no-daemon',forwarded)
    def test_release_preflight_rejects_missing_credentials_before_build(self):
        script=ROOT/'scripts/Build-AndroidRelease.ps1'
        self.assertTrue(script.exists(),'Release build script is missing')
        result=subprocess.run(['powershell','-NoProfile','-File',str(script),'-ValidateOnly','-SigningDirectory',str(ROOT/'.local/nonexistent-release-test')],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('SIGNING_NOT_CONFIGURED',result.stdout+result.stderr)
    def test_release_gradle_requires_explicit_signing_and_never_uses_debug_key(self):
        gradle=ROOT/'.local/tools/gradle-8.13/bin/gradle.bat'
        if not gradle.exists():self.skipTest('Local Gradle toolchain not installed')
        env=dict(os.environ)
        for name in ('MYDESK_RELEASE_KEYSTORE','MYDESK_RELEASE_STORE_PASSWORD','MYDESK_RELEASE_KEY_ALIAS','MYDESK_RELEASE_KEY_PASSWORD'):env.pop(name,None)
        env['JAVA_HOME']=r'C:\Program Files\Java\jdk-23'
        env['ANDROID_USER_HOME']=str(ROOT/'.local/android')
        result=subprocess.run(['cmd.exe','/d','/c',str(gradle),'-g',str(ROOT/'.local/gradle'),':app:verifyReleaseSigning','--console=plain'],cwd=ROOT/'android',env=env,capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=180)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('SIGNING_NOT_CONFIGURED',result.stdout+result.stderr)
