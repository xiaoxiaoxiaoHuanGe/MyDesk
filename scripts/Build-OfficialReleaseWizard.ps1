$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$expectedCertificate = 'c3eb54d9aa145f3a96c41b566a507de2e8dbc4e44d51e561d5dc033b5b73f180'

function Select-Folder([string]$Description) {
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = $Description
    $dialog.ShowNewFolderButton = $false
    try {
        if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { throw '已取消，未开始构建。' }
        return $dialog.SelectedPath
    } finally { $dialog.Dispose() }
}

function Find-SigningDirectory([string]$Root) {
    foreach ($candidate in @((Join-Path $Root '.local\android\release'), (Join-Path $Root 'release'), $Root)) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'signing.json')) { return $candidate }
    }
    return $null
}

function Test-Sdk([string]$Root) {
    return $Root -and (Test-Path -LiteralPath (Join-Path $Root 'platforms\android-36\android.jar')) -and (Test-Path -LiteralPath (Join-Path $Root 'build-tools\36.0.0\apksigner.bat'))
}

function Test-Java([string]$Root) {
    if (!$Root -or !(Test-Path -LiteralPath (Join-Path $Root 'bin\keytool.exe')) -or !(Test-Path -LiteralPath (Join-Path $Root 'bin\java.exe'))) { return $false }
    $releaseFile = Join-Path $Root 'release'
    if (!(Test-Path -LiteralPath $releaseFile)) { return $false }
    $version = [regex]::Match((Get-Content -LiteralPath $releaseFile -Raw), 'JAVA_VERSION="(?:1\.)?(\d+)')
    return $version.Success -and [int]$version.Groups[1].Value -ge 17 -and [int]$version.Groups[1].Value -le 23
}

try {
    Write-Host 'MyDesk 正式版构建工具' -ForegroundColor Cyan
    Write-Host '请使用创建密钥时的 Windows 电脑和账号。密钥、密码不会上传。'
    $signingRoot = Find-SigningDirectory $projectRoot
    $originalRoot = $projectRoot
    if (!$signingRoot) {
        $selected = Select-Folder '选择原 MyDesk 项目文件夹，或解压 release.rar 后包含四个签名文件的 release 文件夹。'
        $signingRoot = Find-SigningDirectory $selected
        if (!$signingRoot) { throw '这个文件夹没有 signing.json。请重新运行，选择原项目文件夹或解压后的 release 文件夹。' }
        $originalRoot = $selected
        for ($level = 0; $level -lt 5; $level++) {
            if (Test-Path -LiteralPath (Join-Path $originalRoot 'android\app\build.gradle.kts')) { break }
            $parent = Split-Path -Parent $originalRoot
            if (!$parent -or $parent -eq $originalRoot) { break }
            $originalRoot = $parent
        }
    }
    foreach ($name in @('mydesk-release.keystore', 'password.dpapi', 'signing.json')) {
        if (!(Test-Path -LiteralPath (Join-Path $signingRoot $name))) { throw "签名文件不完整：缺少 $name。不要重新生成密钥。" }
    }
    $metadata = Get-Content -LiteralPath (Join-Path $signingRoot 'signing.json') -Raw | ConvertFrom-Json
    if ($metadata.keystore -ne 'mydesk-release.keystore' -or $metadata.alias -ne 'mydesk-release' -or $metadata.certificate_sha256 -ne $expectedCertificate) { throw '签名身份与现有正式版不同，已停止。不会更换密钥。' }
    try {
        $securePassword = (Get-Content -LiteralPath (Join-Path $signingRoot 'password.dpapi') -Raw).Trim() | ConvertTo-SecureString
        $securePassword.Dispose()
    } catch { throw '此 Windows 账号无法解锁密码。请登录当初创建密钥的账号后重试，不要重新生成密钥。' }
    Write-Host '原签名密码已在本机解锁。' -ForegroundColor Green

    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'E:\as-sdk')
    if ($env:LOCALAPPDATA) { $sdkCandidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $properties = Join-Path $originalRoot 'android\local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdkCandidates = @((($line -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\'))) + $sdkCandidates }
    }
    $sdkPath = $sdkCandidates | Where-Object { Test-Sdk $_ } | Select-Object -First 1
    if (!$sdkPath) { $sdkPath = Select-Folder '选择 Android SDK 文件夹，例如 E:\as-sdk 或 AppData\Local\Android\Sdk。需要 Android 36 和 Build Tools 36.0.0。' }
    if (!(Test-Sdk $sdkPath)) { throw '所选目录缺少 SDK 36 或 Build Tools 36.0.0。请在 Android Studio 的 SDK Manager 安装这两项，再运行本工具。' }

    $javaCandidates = @($env:JAVA_HOME)
    if ($env:ProgramFiles) {
        $javaCandidates += Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        foreach ($base in @((Join-Path $env:ProgramFiles 'Java'), (Join-Path $env:ProgramFiles 'Eclipse Adoptium'))) {
            if (Test-Path -LiteralPath $base) { $javaCandidates += Get-ChildItem -LiteralPath $base -Directory | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName }
        }
    }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaCandidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    $javaPath = $javaCandidates | Where-Object { Test-Java $_ } | Select-Object -First 1
    if (!$javaPath) { $javaPath = Select-Folder '选择 JDK 文件夹（里面应有 bin\java.exe）。支持 JDK 17 至 23，也可选择 Android Studio 的 jbr 文件夹。' }
    if (!(Test-Java $javaPath)) { throw '所选目录不是支持的 JDK。请选择 JDK 17 至 23 或 Android Studio 的 jbr 文件夹。' }
    & (Join-Path $PSScriptRoot 'Build-AndroidRelease.ps1') -SdkPath $sdkPath -JavaPath $javaPath -SigningDirectory $signingRoot -ValidateOnly

    $firebaseSource = Join-Path $originalRoot 'android\app\google-services.json'
    $firebaseTarget = Join-Path $projectRoot 'android\app\google-services.json'
    if ((Test-Path -LiteralPath $firebaseSource) -and $firebaseSource -ne $firebaseTarget -and !(Test-Path -LiteralPath $firebaseTarget)) { Copy-Item -LiteralPath $firebaseSource -Destination $firebaseTarget }
    if (!(Test-Path -LiteralPath $firebaseTarget)) { Write-Host '未找到本机 Firebase 配置；APK 可构建，Firebase 推送需要后续配置和验收。' -ForegroundColor Yellow }

    $caPath = Join-Path $projectRoot '.local\standalone\tls\mydesk-local-ca.crt'
    if (!(Test-Path -LiteralPath $caPath)) {
        $caDirectory = Split-Path -Parent $caPath
        New-Item -ItemType Directory -Path $caDirectory -Force | Out-Null
        $originalCa = Join-Path $originalRoot '.local\standalone\tls\mydesk-local-ca.crt'
        if (Test-Path -LiteralPath $originalCa) { Copy-Item -LiteralPath $originalCa -Destination $caPath }
        else {
            $temporaryKey = Join-Path $caDirectory ("test-ca-$([guid]::NewGuid().ToString('N')).keystore")
            try {
                & (Join-Path $javaPath 'bin\keytool.exe') -genkeypair -keystore $temporaryKey -alias mydesk-test-ca -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=MyDesk Disposable Test CA' -ext 'bc:critical=ca:true'
                if ($LASTEXITCODE -ne 0) { throw '本地测试证书准备失败。' }
                & (Join-Path $javaPath 'bin\keytool.exe') -exportcert -rfc -keystore $temporaryKey -alias mydesk-test-ca -storepass android -file $caPath
                if ($LASTEXITCODE -ne 0) { throw '本地测试证书导出失败。' }
            } finally { if (Test-Path -LiteralPath $temporaryKey) { Remove-Item -LiteralPath $temporaryKey } }
        }
    }
    Write-Host '正在运行 Android 测试并构建正式 APK，首次运行可能需要下载依赖，请保持网络连接。' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'Build-AndroidRelease.ps1') -SdkPath $sdkPath -JavaPath $javaPath -SigningDirectory $signingRoot
    $outputDirectory = Join-Path $projectRoot 'dist'
    Write-Host "完成！请将打开的文件夹中的 MyDesk-*-release.apk 上传给我继续发布。" -ForegroundColor Green
    Start-Process explorer.exe -ArgumentList @('"' + $outputDirectory + '"')
} catch {
    Write-Host "构建未完成：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host '请把窗口里的报错发给我。不要发送密码或私钥内容。'
    exit 1
}
