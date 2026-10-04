param([string]$JavaPath = $env:JAVA_HOME,[string]$SigningDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$privateRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.local/android'))
if (!$SigningDirectory) { $SigningDirectory = Join-Path $privateRoot 'release' }
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
if (!$signingRoot.StartsWith($privateRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'SIGNING_PATH_OUTSIDE_PRIVATE_ROOT' }
$keyTool = if ($JavaPath) { Join-Path $JavaPath 'bin/keytool.exe' } else { 'keytool.exe' }
New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
$keyPath = Join-Path $signingRoot 'mydesk-release.keystore'
$passwordPath = Join-Path $signingRoot 'password.dpapi'
$certificatePath = Join-Path $signingRoot 'signing-certificate.cer'
$metadataPath = Join-Path $signingRoot 'signing.json'
if ((Test-Path -LiteralPath $metadataPath) -and !(Test-Path -LiteralPath $keyPath)) { throw 'SIGNING_KEY_MISSING: restore the original key; it will not be regenerated' }
if ((Test-Path -LiteralPath $keyPath) -and !(Test-Path -LiteralPath $passwordPath)) { throw 'SIGNING_PASSWORD_MISSING: existing key will not be replaced' }
if (!(Test-Path -LiteralPath $passwordPath)) {
    $entropy = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($entropy) } finally { $random.Dispose() }
    $securePassword = ConvertTo-SecureString ([Convert]::ToBase64String($entropy)) -AsPlainText -Force
    try { $securePassword | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordPath -Encoding ascii } finally { $securePassword.Dispose();[Array]::Clear($entropy,0,$entropy.Length) }
}
$securePassword = (Get-Content -LiteralPath $passwordPath -Raw).Trim() | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$previousPassword = [Environment]::GetEnvironmentVariable('MYDESK_RELEASE_STORE_PASSWORD','Process')
try {
    $env:MYDESK_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if (!(Test-Path -LiteralPath $keyPath)) {
        & $keyTool -genkeypair -keystore $keyPath -alias mydesk-release -storetype PKCS12 -storepass:env MYDESK_RELEASE_STORE_PASSWORD -keypass:env MYDESK_RELEASE_STORE_PASSWORD -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=MyDesk Release'
        if ($LASTEXITCODE -ne 0) { throw 'SIGNING_KEY_GENERATION_FAILED' }
    }
    & $keyTool -exportcert -keystore $keyPath -alias mydesk-release -storepass:env MYDESK_RELEASE_STORE_PASSWORD -file $certificatePath
    if ($LASTEXITCODE -ne 0) { throw 'SIGNING_CERTIFICATE_EXPORT_FAILED' }
    $fingerprint = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash
    if (Test-Path -LiteralPath $metadataPath) {
        $previousMetadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        if ($previousMetadata.certificate_sha256 -ne $fingerprint -or $previousMetadata.alias -ne 'mydesk-release') { throw 'SIGNING_CERTIFICATE_MISMATCH: existing identity will not be replaced' }
    }
    @{ keystore='mydesk-release.keystore'; alias='mydesk-release'; certificate_sha256=$fingerprint } | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding ascii
    Write-Output "Stable release certificate SHA-256: $fingerprint"
    Write-Output "Private signing directory: $signingRoot"
} finally {
    [Environment]::SetEnvironmentVariable('MYDESK_RELEASE_STORE_PASSWORD',$previousPassword,'Process')
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $securePassword.Dispose()
}
