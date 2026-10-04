param([Parameter(Mandatory=$true)][string]$LanIp)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskData = Join-Path $taskRoot '.local/standalone'
$taskPython = Join-Path $taskRoot '.venv/Scripts/python.exe'
$taskCa = Join-Path $taskData 'tls/mydesk-local-ca.crt'
$taskCert = Join-Path $taskData 'tls/server.pem'
$taskKey = Join-Path $taskData 'tls/server-key.pem'
$taskAddress = [Net.IPAddress]::Parse($LanIp)
if ($taskAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or [Net.IPAddress]::IsLoopback($taskAddress)) { throw 'Provide this computer''s LAN IPv4 address.' }
$taskOctets = $taskAddress.GetAddressBytes()
if (!($taskOctets[0] -eq 10 -or ($taskOctets[0] -eq 172 -and $taskOctets[1] -ge 16 -and $taskOctets[1] -le 31) -or ($taskOctets[0] -eq 192 -and $taskOctets[1] -eq 168))) { throw 'Only private LAN IPv4 addresses are supported.' }
$taskLocalAddresses = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | ForEach-Object { $_.GetIPProperties().UnicastAddresses | ForEach-Object { $_.Address.ToString() } }
if ($LanIp -notin $taskLocalAddresses) { throw 'The specified LAN address does not belong to this computer.' }
foreach ($taskFile in @($taskPython,$taskCa,$taskCert,$taskKey)) { if (!(Test-Path -LiteralPath $taskFile)) { throw ('Required local file missing: ' + $taskFile) } }
$taskProbe = 'import sys,ssl,json; from urllib.request import build_opener,ProxyHandler,HTTPSHandler; op=build_opener(ProxyHandler({}),HTTPSHandler(context=ssl.create_default_context(cafile=sys.argv[2]))); urls=["http://127.0.0.1:8787/health","https://"+sys.argv[1]+":8443/health"]; assert all(json.load(op.open(url,timeout=1)).get("application")=="MyDesk" for url in urls)'
& $taskPython -c $taskProbe $LanIp $taskCa 2>$null
if ($LASTEXITCODE -eq 0) { Write-Output ('MyDesk already ready: https://' + $LanIp + ':8443/'); return }
$taskListeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if ($taskListeners | Where-Object { $_.Port -in @(8787,8443) }) { throw 'MyDesk ports are occupied. Stop the existing MyDesk service before switching startup modes.' }
$taskArguments = @('-u','-m','mydesk','--data',('"'+$taskData+'"'),'--host','127.0.0.1','--port','8787','--tls-host',$LanIp,'--tls-port','8443','--cert',('"'+$taskCert+'"'),'--key',('"'+$taskKey+'"'))
$taskProcess = Start-Process -FilePath $taskPython -ArgumentList $taskArguments -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskData 'native.stdout.log') -RedirectStandardError (Join-Path $taskData 'native.stderr.log') -PassThru
@{ launcher_pid=$taskProcess.Id; lan_ip=$LanIp; http='http://127.0.0.1:8787/'; https=('https://'+$LanIp+':8443/'); started_at=(Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskData 'native-server.json') -Encoding utf8
for ($taskAttempt=0; $taskAttempt -lt 15; $taskAttempt++) {
    & $taskPython -c $taskProbe $LanIp $taskCa 2>$null
    if ($LASTEXITCODE -eq 0) { Write-Output 'MyDesk HTTP and trusted LAN HTTPS are ready.'; Write-Output ('Android URL: https://' + $LanIp + ':8443/'); return }
    Start-Sleep -Seconds 1
}
throw 'MyDesk did not become ready; inspect .local/standalone/native.stderr.log.'
