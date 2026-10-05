param([string]$SdkPath = 'E:\as-sdk',[string]$JavaPath = $env:JAVA_HOME,[switch]$SkipTests,[switch]$NoDaemon)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidRoot = Join-Path $projectRoot 'android'
$privateRoot = Join-Path $projectRoot '.local/android'
if (!(Test-Path -LiteralPath (Join-Path $SdkPath 'platforms/android-36/android.jar'))) { throw '需要安装 Android SDK 36，请用 -SdkPath 指定已有 SDK 目录。' }
if ($JavaPath) { $env:JAVA_HOME = $JavaPath }
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
$env:ANDROID_USER_HOME = $privateRoot
New-Item -ItemType Directory -Path (Join-Path $projectRoot '.local/build-home') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $androidRoot 'local.properties') -Value ('sdk.dir=' + $SdkPath.Replace('\','/').Replace(':','\:')) -Encoding utf8
$keyPath = Join-Path $privateRoot 'debug.keystore'
if (!(Test-Path -LiteralPath $keyPath)) {
    $keyTool = if ($JavaPath) { Join-Path $JavaPath 'bin/keytool.exe' } else { 'keytool' }
    & $keyTool -genkeypair -keystore $keyPath -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=MyDesk Local Debug'
    if ($LASTEXITCODE -ne 0) { throw '无法生成调试签名。' }
}
$caPath = Join-Path $projectRoot '.local/standalone/tls/mydesk-local-ca.crt'
if (!(Test-Path -LiteralPath $caPath)) { throw '先运行 scripts/start_local.py，生成本地 HTTPS 测试证书。' }
$rawPath = Join-Path $androidRoot 'app/src/debug/res/raw'
New-Item -ItemType Directory -Path $rawPath -Force | Out-Null
Copy-Item -LiteralPath $caPath -Destination (Join-Path $rawPath 'mydesk_local_ca.crt')
$gradle = Join-Path $projectRoot '.local/tools/gradle-8.13/bin/gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $androidRoot 'gradlew.bat' }
[string[]]$tasks = if ($SkipTests) { @(':app:assembleDebug') } else { @(':app:testDebugUnitTest',':app:assembleDebug') }
[string[]]$daemonOptions = if ($NoDaemon) { @('--no-daemon') } else { @() }
Push-Location $androidRoot
try {
    & $gradle -g (Join-Path $projectRoot '.local/gradle') '-Pkotlin.compiler.execution.strategy=in-process' @tasks @daemonOptions --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建或测试失败。' }
} finally { Pop-Location }
$outputDirectory = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$output = Join-Path $outputDirectory 'MyDesk-1.1.1-local-debug.apk'
Copy-Item -LiteralPath (Join-Path $androidRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $output
Write-Output "APK: $output"
