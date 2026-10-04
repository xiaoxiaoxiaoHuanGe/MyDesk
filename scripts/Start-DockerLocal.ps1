param([switch]$Restart)

$ErrorActionPreference = 'Stop'
$taskDockerInstall = Join-Path $env:ProgramFiles 'Docker\Docker'
$taskDockerCli = Join-Path $taskDockerInstall 'resources\bin\docker.exe'
$taskDockerDesktop = Join-Path $taskDockerInstall 'Docker Desktop.exe'
$taskDockerRoot = Join-Path $env:LOCALAPPDATA 'Docker'
$taskRunPath = Join-Path $taskDockerRoot 'run'

function Test-LocalDockerEngine {
    $taskInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $taskInfo.FileName = $taskDockerCli
    $taskInfo.Arguments = 'info --format "{{.ServerVersion}}"'
    $taskInfo.UseShellExecute = $false
    $taskInfo.CreateNoWindow = $true
    $taskInfo.RedirectStandardOutput = $true
    $taskInfo.RedirectStandardError = $true
    $taskProbe = [System.Diagnostics.Process]::Start($taskInfo)
    try {
        if (-not $taskProbe.WaitForExit(4000)) {
            $taskProbe.Kill()
            $taskProbe.WaitForExit()
            return $false
        }
        return $taskProbe.ExitCode -eq 0
    } finally { $taskProbe.Dispose() }
}

if (-not (Test-Path -LiteralPath $taskDockerDesktop)) { throw 'Docker Desktop is not installed.' }
$taskMutex = [System.Threading.Mutex]::new($false, 'Local\MyDeskDockerLocalStart')
$taskMutexHeld = $false
try {
    $taskMutexHeld = $taskMutex.WaitOne(0)
    if (-not $taskMutexHeld) { throw 'Another Docker recovery is already running.' }
    if (Test-LocalDockerEngine) {
        if (-not $Restart) { Write-Output 'Docker engine is already healthy.'; exit 0 }
        & $taskDockerCli desktop stop --timeout 20
        if ($LASTEXITCODE -ne 0) { throw 'Docker did not stop cleanly; no runtime directories were changed.' }
    }
    # Only Docker's own failed desktop/backend processes may be stopped.
    Get-Process -Name 'Docker Desktop','com.docker.backend' -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Path -and $_.Path.StartsWith($taskDockerInstall + '\', [StringComparison]::OrdinalIgnoreCase)) {
            $taskProcessId = $_.Id
            Stop-Process -Id $taskProcessId -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $taskProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path -LiteralPath $taskRunPath) {
        $taskRunItem = Get-Item -LiteralPath $taskRunPath -Force
        if ($taskRunItem.FullName -ne $taskRunPath -or -not $taskRunItem.PSIsContainer -or
            ($taskRunItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Unexpected Docker runtime directory; recovery stopped.'
        }
        $taskEndpoints = @(Get-ChildItem -LiteralPath $taskRunPath -Force)
        if ($taskEndpoints.Count) {
            foreach ($taskEndpoint in $taskEndpoints) {
                if ($taskEndpoint.PSIsContainer -or $taskEndpoint.Length -ne 0 -or
                    -not ($taskEndpoint.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                    throw 'Runtime directory contains more than zero-byte communication endpoints; recovery stopped.'
                }
            }
            $taskBackupName = 'run.stale-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6)
            Rename-Item -LiteralPath $taskRunPath -NewName $taskBackupName
            Write-Output ('Preserved stale endpoints: ' + (Join-Path $taskDockerRoot $taskBackupName))
        }
    }
    New-Item -Path $taskRunPath -ItemType Directory -Force | Out-Null
    Start-Process -FilePath $taskDockerDesktop -WindowStyle Hidden
    $taskReadyDeadline = (Get-Date).AddSeconds(90)
    do {
        if (Test-LocalDockerEngine) { Write-Output 'Docker Linux engine is ready.'; exit 0 }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $taskReadyDeadline)
    throw 'Docker did not become ready within 90 seconds. Existing data disks were not changed.'
} finally {
    if ($taskMutexHeld) { $taskMutex.ReleaseMutex() }
    $taskMutex.Dispose()
}
