$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$local=Join-Path $root '.local'
$accountsPath=Join-Path $local 'investigation-rehearsal.json'
$accounts=Get-Content -Raw -LiteralPath $accountsPath | ConvertFrom-Json
$receipt=Get-Content -Raw -LiteralPath (Join-Path $local 'investigation-receipt.json') | ConvertFrom-Json
$config=Get-Content -Raw -LiteralPath (Join-Path $local 'database.json') | ConvertFrom-Json
$api='http://127.0.0.1:8082/api/v1'
function Read-Receipt {
 $login=@{email=$accounts.memberEmail;password=$accounts.seedPassword} | ConvertTo-Json
 $session=Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body $login
 Invoke-RestMethod -Uri "$api/investigation/submissions/$($receipt.id)" -Headers @{Authorization="Bearer $($session.accessToken)"}
}
$before=Read-Receipt
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($accounts.backend)"
if(-not $process -or $process.CommandLine -notlike '*devfest-backend-0.1.0.jar*--server.port=8082*') { throw 'Refusing to stop an unidentified backend.' }
Stop-Process -Id $accounts.backend
Wait-Process -Id $accounts.backend -ErrorAction SilentlyContinue
$env:DEVFEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/devfest_test'
$env:DEVFEST_DB_USER='devfest_test'
$env:DEVFEST_DB_PASSWORD=$config.testPassword
$env:DEVFEST_JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
$env:DEVFEST_DEVELOPMENT='true'
$env:DEVFEST_SEED='false'
$env:DEVFEST_WORKER_ENABLED='false'
try {
 $backend=Start-Process -FilePath java -ArgumentList '-jar','target/devfest-backend-0.1.0.jar','--spring.config.import=','--server.port=8082','--app.origins=http://localhost:4202,http://127.0.0.1:4202' -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'investigation-restart.log') -RedirectStandardError (Join-Path $local 'investigation-restart-error.log') -PassThru
 $accounts.backend=$backend.Id
 $accounts | ConvertTo-Json | Set-Content -LiteralPath $accountsPath
} finally {
 foreach($name in @('DEVFEST_DB_URL','DEVFEST_DB_USER','DEVFEST_DB_PASSWORD','DEVFEST_JWT_SECRET','DEVFEST_DEVELOPMENT','DEVFEST_SEED','DEVFEST_WORKER_ENABLED')) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
}
$ready=$false
for($i=0;$i -lt 40;$i++) {
 try { $health=Invoke-RestMethod -Uri "$api/health" -TimeoutSec 1; if($health.status -eq 'UP') {$ready=$true;break} } catch { Start-Sleep -Milliseconds 500 }
}
if(-not $ready) { throw 'Restarted backend did not become healthy.' }
$after=Read-Receipt
foreach($field in @('id','teamId','challengeId','receivedAt','report','points','scoreVersion','challengeSnapshot')) {
 if(($before.$field | ConvertTo-Json -Compress) -ne ($after.$field | ConvertTo-Json -Compress)) { throw "Persisted field changed across restart: $field" }
}
@{verifiedAt=[DateTime]::UtcNow.ToString('o');receipt=$receipt.id;persistedFields=@('id','teamId','challengeId','receivedAt','report','points','scoreVersion','challengeSnapshot');passed=$true;scope='Actual local Java process termination, restart, new sign-in and database read; ephemeral rehearsal signing key rotated.'} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'docs/investigation-restart-results.json')
Write-Output 'The accepted receipt, report, challenge snapshot and score survived an actual backend process restart and new sign-in.'
