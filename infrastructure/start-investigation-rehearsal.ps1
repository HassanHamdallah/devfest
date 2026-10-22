param([string]$PgBin='C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$local=Join-Path $root '.local'
$config=Get-Content -Raw -LiteralPath (Join-Path $local 'database.json') | ConvertFrom-Json
foreach($port in @(8082,4202)) {
 if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Rehearsal port $port is already occupied. Stop only the identified rehearsal process first." }
}
# This reset is deliberately confined to the disposable test DB, never the development/event DB.
$env:PGPASSWORD=$config.testPassword
try {
 $database=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest_test -d devfest_test -tAc 'SELECT current_database()'
 if($LASTEXITCODE -ne 0 -or $database -ne 'devfest_test') { throw 'Test database identity check failed.' }
 & (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest_test -d devfest_test -v ON_ERROR_STOP=1 -c 'TRUNCATE app_user,room,investigation_challenge RESTART IDENTITY CASCADE; UPDATE investigation_settings SET rules_confirmed=false,results_published=false;'
 if($LASTEXITCODE -ne 0) { throw 'Test fixture reset failed. Run backend migrations/tests first.' }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
$seedPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$adminPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$env:DEVFEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/devfest_test'
$env:DEVFEST_DB_USER='devfest_test'
$env:DEVFEST_DB_PASSWORD=$config.testPassword
$env:DEVFEST_JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
$env:DEVFEST_ADMIN_EMAIL='rehearsal-admin@investigation.test'
$env:DEVFEST_ADMIN_PASSWORD=$adminPassword
$env:DEVFEST_INVESTIGATION_SEED_PASSWORD=$seedPassword
$env:DEVFEST_DEVELOPMENT='true'
$env:DEVFEST_SEED='false'
$env:DEVFEST_WORKER_ENABLED='false'
$env:JUDGE0_URL=''
$env:PORT='8082'
try {
 $backend=Start-Process -FilePath java -ArgumentList '-jar','target/devfest-backend-0.1.0.jar','--spring.config.import=','--server.port=8082','--app.origins=http://localhost:4202,http://127.0.0.1:4202' -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'investigation-backend.log') -RedirectStandardError (Join-Path $local 'investigation-backend-error.log') -PassThru
} finally {
 foreach($name in @('DEVFEST_DB_URL','DEVFEST_DB_USER','DEVFEST_DB_PASSWORD','DEVFEST_JWT_SECRET','DEVFEST_ADMIN_EMAIL','DEVFEST_ADMIN_PASSWORD','DEVFEST_INVESTIGATION_SEED_PASSWORD','DEVFEST_DEVELOPMENT','DEVFEST_SEED','DEVFEST_WORKER_ENABLED','JUDGE0_URL','PORT')) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
}
'{"/api":{"target":"http://127.0.0.1:8082","secure":false,"changeOrigin":true}}' | Set-Content -LiteralPath (Join-Path $local 'investigation-proxy.json')
$frontend=Start-Process -FilePath node -ArgumentList 'node_modules/@angular/cli/bin/ng.js','serve','--port','4202','--proxy-config','../.local/investigation-proxy.json' -WorkingDirectory (Join-Path $root 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'investigation-frontend.log') -RedirectStandardError (Join-Path $local 'investigation-frontend-error.log') -PassThru
@{adminEmail='rehearsal-admin@investigation.test';adminPassword=$adminPassword;managerEmail='manager@investigation.test';memberEmail='member1@investigation.test';otherMemberEmail='member2@investigation.test';seedPassword=$seedPassword;backend=$backend.Id;frontend=$frontend.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $local 'investigation-rehearsal.json')
Write-Output 'Isolated rehearsal starting at http://localhost:4202 with API 8082 and disposable devfest_test data. Credentials remain in ignored .local/investigation-rehearsal.json. Do not run backend tests while rehearsing.'
