param([string]$SdkPath = 'E:\as-sdk',[string]$JavaPath = $env:JAVA_HOME,[string]$SigningDirectory,[switch]$SkipTests,[switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidRoot = Join-Path $projectRoot 'android'
if (!$SigningDirectory) { $SigningDirectory = Join-Path $projectRoot '.local/android/release' }
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
$metadataPath = Join-Path $signingRoot 'signing.json'
$passwordPath = Join-Path $signingRoot 'password.dpapi'
if (!(Test-Path -LiteralPath $metadataPath) -or !(Test-Path -LiteralPath $passwordPath)) { throw 'SIGNING_NOT_CONFIGURED: initialize a private release key first' }
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($metadata.keystore -ne 'mydesk-release.keystore' -or $metadata.alias -ne 'mydesk-release' -or $metadata.certificate_sha256 -notmatch '^[A-Fa-f0-9]{64}$') { throw 'SIGNING_METADATA_INVALID' }
$keyPath = Join-Path $signingRoot $metadata.keystore
if (!(Test-Path -LiteralPath $keyPath)) { throw 'SIGNING_NOT_CONFIGURED: keystore missing' }
$keyTool = if ($JavaPath) { Join-Path $JavaPath 'bin/keytool.exe' } else { 'keytool.exe' }
$securePassword = (Get-Content -LiteralPath $passwordPath -Raw).Trim() | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$signingVariables = @('MYDESK_RELEASE_KEYSTORE','MYDESK_RELEASE_STORE_PASSWORD','MYDESK_RELEASE_KEY_ALIAS','MYDESK_RELEASE_KEY_PASSWORD')
$previousValues = @{}
foreach ($name in $signingVariables) { $previousValues[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:MYDESK_RELEASE_KEYSTORE = $keyPath
    $env:MYDESK_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:MYDESK_RELEASE_KEY_PASSWORD = $env:MYDESK_RELEASE_STORE_PASSWORD
    $env:MYDESK_RELEASE_KEY_ALIAS = $metadata.alias
    $checkRoot = Join-Path $projectRoot '.local/android'
    New-Item -ItemType Directory -Path $checkRoot -Force | Out-Null
    $checkCertificate = Join-Path $checkRoot 'release-certificate-check.cer'
    & $keyTool -exportcert -keystore $keyPath -alias $metadata.alias -storepass:env MYDESK_RELEASE_STORE_PASSWORD -file $checkCertificate
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $checkCertificate -Algorithm SHA256).Hash -ne $metadata.certificate_sha256) { throw 'SIGNING_CERTIFICATE_MISMATCH' }
    if ($ValidateOnly) { Write-Output 'Release signing preflight passed'; return }
    if (!(Test-Path -LiteralPath (Join-Path $SdkPath 'platforms/android-36/android.jar'))) { throw 'SDK_36_REQUIRED' }
    if ($JavaPath) { $env:JAVA_HOME = $JavaPath }
    $env:ANDROID_USER_HOME = $checkRoot
    Set-Content -LiteralPath (Join-Path $androidRoot 'local.properties') -Value ('sdk.dir=' + $SdkPath.Replace('\','/').Replace(':','\:')) -Encoding utf8
    if (!$SkipTests) {
        & (Join-Path $PSScriptRoot 'Build-AndroidLocal.ps1') -SdkPath $SdkPath -JavaPath $JavaPath -NoDaemon
    }
    $gradle = Join-Path $projectRoot '.local/tools/gradle-8.13/bin/gradle.bat'
    if (!(Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $androidRoot 'gradlew.bat' }
    Push-Location $androidRoot
    try {
        & $gradle -g (Join-Path $projectRoot '.local/gradle') '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleRelease --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'RELEASE_BUILD_FAILED' }
    } finally { Pop-Location }
    $apkPath = Join-Path $androidRoot 'app/build/outputs/apk/release/app-release.apk'
    $signer = Join-Path $SdkPath 'build-tools/36.0.0/apksigner.bat'
    $verification = & $signer verify --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'RELEASE_SIGNATURE_INVALID' }
    $certificateLine = $verification | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' }
    if (!$certificateLine -or ($certificateLine -replace '^.*digest:\s*','').Trim() -ne $metadata.certificate_sha256) { throw 'RELEASE_CERTIFICATE_MISMATCH' }
    $outputDirectory = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $output = Join-Path $outputDirectory 'MyDesk-1.1.0-release.apk'
    Copy-Item -LiteralPath $apkPath -Destination $output
    Write-Output "Signed release APK: $output"
    Write-Output "Release certificate SHA-256: $($metadata.certificate_sha256)"
} finally {
    foreach ($name in $signingVariables) { [Environment]::SetEnvironmentVariable($name,$previousValues[$name],'Process') }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $securePassword.Dispose()
}
