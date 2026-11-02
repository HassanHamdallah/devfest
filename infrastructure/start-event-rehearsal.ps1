# Requires PowerShell 7: RandomNumberGenerator.GetBytes(int) is a .NET 6+ API.
#Requires -Version 7.0
param(
 [string]$PgBin='C:\Program Files\PostgreSQL\18\bin',
 [int]$ReadyTimeoutSeconds=240,
 [switch]$NoWait
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$local=Join-Path $root '.local'
$psql=Join-Path $PgBin 'psql.exe'
$config=Get-Content -Raw -LiteralPath (Join-Path $local 'database.json') | ConvertFrom-Json
$adminEmail='rehearsal-admin@event.test'
$managerEmails=[ordered]@{AI='manager.ai@event.test';PROBLEM_SOLVING='manager.ps@event.test';LINUX='manager.linux@event.test';HARDWARE='manager.hw@event.test'}
$memberEmails=@(1..16 | ForEach-Object { 'team{0:D2}@event.test' -f $_ })
$childEnvironment=@('DEVFEST_DB_URL','DEVFEST_DB_USER','DEVFEST_DB_PASSWORD','DEVFEST_JWT_SECRET','DEVFEST_ADMIN_EMAIL','DEVFEST_ADMIN_PASSWORD','DEVFEST_EVENT_SEED_PASSWORD','DEVFEST_DEVELOPMENT','DEVFEST_SEED','DEVFEST_WORKER_ENABLED','JUDGE0_URL','PORT')

function Assert-RehearsalPortsFree {
 foreach($port in @(8082,4202)) {
  if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Rehearsal port $port is already occupied. Stop only the identified rehearsal process first (infrastructure/stop-event-rehearsal.ps1)." }
 }
}

function New-RandomSecret([int]$Bytes) {
 [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes($Bytes))
}

function Invoke-TestDatabaseQuery([string]$Sql) {
 $output=& $psql -h 127.0.0.1 -p 55432 -U devfest_test -d devfest_test -v ON_ERROR_STOP=1 -tAc $Sql
 if($LASTEXITCODE -ne 0) { throw "Query against devfest_test failed: $Sql" }
 return $output
}

# The reset is deliberately confined to the disposable test DB; the event database 'devfest' is never touched.
function Reset-EventTestData {
 $env:PGPASSWORD=$config.testPassword
 try {
  if((Invoke-TestDatabaseQuery 'SELECT current_database()') -ne 'devfest_test') { throw 'Test database identity check failed.' }
  $schemaReady=Invoke-TestDatabaseQuery "SELECT to_regclass('public.event_state') IS NOT NULL AND to_regclass('public.team_final') IS NOT NULL"
  if($schemaReady -ne 't') { throw 'The event tables (V5) are missing in devfest_test. Run infrastructure/test-backend.ps1 once to migrate it, then start the rehearsal again.' }
  # CASCADE also empties event_room (manager_id -> app_user) and event_content (correct_suspect_id -> suspect),
  # so both are re-created below exactly as V5 inserts them. event_state has no foreign keys and is reset in place.
  $reset=@(
   'SET client_min_messages=warning;',
   'TRUNCATE app_user, rate_bucket, audit_event, event_team, room_submission, room_judgement, room_judgement_history, collected_clue, penalty, suspect, recovery_attempt, accusation, team_final RESTART IDENTITY CASCADE;',
   "INSERT INTO event_room(code,name,position) VALUES ('AI','AI',0),('PROBLEM_SOLVING','Problem solving',1),('LINUX','Linux',2),('HARDWARE','Hardware',3) ON CONFLICT (code) DO NOTHING;",
   "INSERT INTO event_content(id) VALUES (1) ON CONFLICT (id) DO UPDATE SET encrypted_message='', correct_suspect_id=NULL, updated_at=now();",
   'UPDATE event_state SET current_round=0, round_started_at=NULL, round_minutes=25, final_open=false, final_started_at=NULL, final_closed_at=NULL, results_published=false, revision=revision+1, updated_at=now() WHERE id=1;'
  ) -join ' '
  & $psql -h 127.0.0.1 -p 55432 -U devfest_test -d devfest_test -v ON_ERROR_STOP=1 -1 -q -c $reset
  if($LASTEXITCODE -ne 0) { throw 'Event fixture reset failed in devfest_test.' }
 } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

function Start-RehearsalBackend([string]$AdminPassword,[string]$SeedPassword) {
 $env:DEVFEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/devfest_test'
 $env:DEVFEST_DB_USER='devfest_test'
 $env:DEVFEST_DB_PASSWORD=$config.testPassword
 $env:DEVFEST_JWT_SECRET=New-RandomSecret 48
 $env:DEVFEST_ADMIN_EMAIL=$adminEmail
 $env:DEVFEST_ADMIN_PASSWORD=$AdminPassword
 $env:DEVFEST_EVENT_SEED_PASSWORD=$SeedPassword
 $env:DEVFEST_DEVELOPMENT='true'
 $env:DEVFEST_SEED='false'
 $env:DEVFEST_WORKER_ENABLED='false'
 $env:JUDGE0_URL=''
 $env:PORT='8082'
 try {
  Start-Process -FilePath java -ArgumentList '-jar','target/devfest-backend-0.1.0.jar','--spring.config.import=','--server.port=8082','--app.origins=http://localhost:4202,http://127.0.0.1:4202' -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'event-backend.log') -RedirectStandardError (Join-Path $local 'event-backend-error.log') -PassThru
 } finally {
  foreach($name in $childEnvironment) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
 }
}

function Start-RehearsalFrontend {
 $proxy=Join-Path $local 'event-proxy.json'
 '{"/api":{"target":"http://127.0.0.1:8082","secure":false,"changeOrigin":true}}' | Set-Content -LiteralPath $proxy
 Start-Process -FilePath node -ArgumentList 'node_modules/@angular/cli/bin/ng.js','serve','--port','4202','--proxy-config','../.local/event-proxy.json' -WorkingDirectory (Join-Path $root 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'event-frontend.log') -RedirectStandardError (Join-Path $local 'event-frontend-error.log') -PassThru
}

function Test-Endpoint([string]$Uri,[switch]$Health) {
 try {
  if($Health) { return (Invoke-RestMethod -Uri $Uri -TimeoutSec 2).status -eq 'UP' }
  return (Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200
 } catch { return $false }
}

function Wait-RehearsalReady($Backend,$Frontend) {
 $deadline=[DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
 $backendUp=$false; $frontendUp=$false
 while([DateTime]::UtcNow -lt $deadline -and -not ($backendUp -and $frontendUp)) {
  if($Backend.HasExited) { throw 'The rehearsal backend exited during startup. See .local/event-backend.log and .local/event-backend-error.log.' }
  if($Frontend.HasExited) { throw 'The rehearsal frontend exited during startup. See .local/event-frontend.log and .local/event-frontend-error.log.' }
  if(-not $backendUp) { $backendUp=Test-Endpoint 'http://127.0.0.1:8082/api/v1/health' -Health }
  if(-not $frontendUp) { $frontendUp=Test-Endpoint 'http://localhost:4202/' }
  if(-not ($backendUp -and $frontendUp)) { Start-Sleep -Seconds 2 }
 }
 if(-not $backendUp) { throw "The backend did not report UP within $ReadyTimeoutSeconds s. See .local/event-backend.log." }
 if(-not $frontendUp) { throw "The frontend did not respond on 4202 within $ReadyTimeoutSeconds s. See .local/event-frontend.log." }
}

$jar=Join-Path $root 'backend/target/devfest-backend-0.1.0.jar'
if(-not (Test-Path -LiteralPath $jar)) { throw 'backend/target/devfest-backend-0.1.0.jar is missing. Build the backend first.' }
Assert-RehearsalPortsFree
Reset-EventTestData
$adminPassword=New-RandomSecret 24
$seedPassword=New-RandomSecret 24
$backend=Start-RehearsalBackend $adminPassword $seedPassword
$frontend=Start-RehearsalFrontend
[ordered]@{
 adminEmail=$adminEmail; adminPassword=$adminPassword; seedPassword=$seedPassword
 managers=$managerEmails; members=$memberEmails
 backend=$backend.Id; frontend=$frontend.Id
} | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $local 'event-rehearsal.json')
Write-Output 'Isolated event rehearsal starting: UI http://localhost:4202, API http://127.0.0.1:8082, disposable devfest_test data. Credentials are in the ignored .local/event-rehearsal.json. Do not run backend tests while rehearsing.'
if($NoWait) { return }
Wait-RehearsalReady $backend $frontend
Write-Output 'Rehearsal ready: backend health is UP and the frontend responds on 4202.'
