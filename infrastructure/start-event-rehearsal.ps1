# Requires PowerShell 7: RandomNumberGenerator.GetBytes(int) is a .NET 6+ API.
#Requires -Version 7.0
param(
 [string]$PgBin='C:\Program Files\PostgreSQL\18\bin',
 [int]$ReadyTimeoutSeconds=240,
 [switch]$NoWait,
 # Restart only the recorded backend on freshly reset devfest_test data (same credentials); the UI keeps running.
 # The browser tests call this before each test so every test starts from an untouched event.
 [switch]$ResetBackend,
 # After startup, each room's manager sets a practice clue key through the API (for manual rehearsals only;
 # no clue key exists in the application, and automated tests set their own keys).
 [switch]$PracticeKeys
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
  # CASCADE also empties event_room (manager_id -> app_user), event_content (correct_suspect_id -> suspect), the
  # room terminal files and the AI mission content (-> event_room), the teams' mission runs and stages (-> event_team) and the round
  # history in room_arrival and round_time_adjustment (-> app_user). event_room and event_content are re-created
  # below as V5 inserts them, with V10's to V19's defaults (terminal on for LINUX, AI mission on for AI, pass score 60); the Linux terminal activity (V13 turns it on for a fresh database) is left off here, so the plain-answer-form specs keep working: the Linux form specs switch it on through the API.
  # The teams' AI mission evaluations go with event_team and room_submission.
  # event_state has no foreign keys and is reset in place, timer fields included.
  $reset=@(
   'SET client_min_messages=warning;',
   'TRUNCATE app_user, rate_bucket, audit_event, event_team, room_submission, room_judgement, room_judgement_history, collected_clue, penalty, suspect, recovery_attempt, accusation, team_final RESTART IDENTITY CASCADE;',
   "INSERT INTO event_room(code,name,position,terminal_enabled,activity) VALUES ('AI','AI',0,false,'AI_QUIZ'),('PROBLEM_SOLVING','Problem solving',1,false,NULL),('LINUX','Linux',2,true,NULL),('HARDWARE','Hardware',3,false,NULL) ON CONFLICT (code) DO NOTHING;",
   "INSERT INTO event_content(id) VALUES (1) ON CONFLICT (id) DO UPDATE SET encrypted_message='', correct_suspect_id=NULL, updated_at=now();",
   'UPDATE event_state SET current_round=0, round_started_at=NULL, round_minutes=25, round_phase=NULL, round_duration_seconds=NULL, round_elapsed_before_seconds=0, round_resumed_at=NULL, round_ended=false, final_open=false, final_started_at=NULL, final_closed_at=NULL, results_published=false, revision=revision+1, updated_at=now() WHERE id=1;'
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
 Start-Process -FilePath node -ArgumentList 'node_modules/@angular/cli/bin/ng.js','serve','--port','4202','--no-prebundle','--proxy-config','../.local/event-proxy.json' -WorkingDirectory (Join-Path $root 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $local 'event-frontend.log') -RedirectStandardError (Join-Path $local 'event-frontend-error.log') -PassThru
}

function Test-Endpoint([string]$Uri,[switch]$Health) {
 try {
  if($Health) { return (Invoke-RestMethod -Uri $Uri -TimeoutSec 2).status -eq 'UP' }
  return (Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200
 } catch { return $false }
}

# The seed runs after the web server started, so "health UP" can come before the seeded accounts exist: the last
# seeded account (the highest team number, created after every room manager) must be able to sign in too.
function Test-SeedReady([string]$SeedPassword) {
 try {
  $null=Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/api/v1/auth/login' -ContentType 'application/json' -TimeoutSec 5 -Body (@{email=$memberEmails[-1];password=$SeedPassword} | ConvertTo-Json)
  return $true
 } catch { return $false }
}

# $Frontend may be $null when only the backend was restarted.
function Wait-RehearsalReady($Backend,$Frontend,[string]$SeedPassword) {
 $deadline=[DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
 $backendUp=$false; $frontendUp=($null -eq $Frontend)
 while([DateTime]::UtcNow -lt $deadline -and -not ($backendUp -and $frontendUp)) {
  if($Backend.HasExited) { throw 'The rehearsal backend exited during startup. See .local/event-backend.log and .local/event-backend-error.log.' }
  if($Frontend -and $Frontend.HasExited) { throw 'The rehearsal frontend exited during startup. See .local/event-frontend.log and .local/event-frontend-error.log.' }
  if(-not $backendUp) { $backendUp=(Test-Endpoint 'http://127.0.0.1:8082/api/v1/health' -Health) -and (Test-SeedReady $SeedPassword) }
  if(-not $frontendUp) { $frontendUp=Test-Endpoint 'http://localhost:4202/' }
  if(-not ($backendUp -and $frontendUp)) { Start-Sleep -Seconds 2 }
 }
 if(-not $backendUp) { throw "The backend did not report UP within $ReadyTimeoutSeconds s. See .local/event-backend.log." }
 if(-not $frontendUp) { throw "The frontend did not respond on 4202 within $ReadyTimeoutSeconds s. See .local/event-frontend.log." }
}

# Practice values live only in this rehearsal script, never in the application.
function Set-PracticeClueKeys([string]$SeedPassword) {
 $api='http://127.0.0.1:8082/api/v1'
 $keys=[ordered]@{AI='WHITE VAN';PROBLEM_SOLVING='RED CLAY';LINUX='WEST TOWER';HARDWARE='+970-59-881'}
 foreach($code in $keys.Keys) {
  $login=Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body (@{email=$managerEmails[$code];password=$SeedPassword} | ConvertTo-Json)
  $headers=@{Authorization="Bearer $($login.accessToken)"}
  $roomId=(Invoke-RestMethod -Uri "$api/event/home" -Headers $headers).roomId
  $room=(Invoke-RestMethod -Uri "$api/event/rooms/$roomId/board" -Headers $headers).room
  $body=@{clueKey=$keys[$code];recoveryPrompt=$room.recoveryPrompt;recoveryAnswer=$room.recoveryAnswer;version=$room.version} | ConvertTo-Json
  $null=Invoke-RestMethod -Method Put -Uri "$api/event/rooms/$roomId/content" -Headers $headers -ContentType 'application/json' -Body $body
 }
 Write-Output 'Practice clue keys set by the room managers through the API.'
}

# A recycled PID could belong to anything; only the exact rehearsal backend command line is stopped.
function Stop-RecordedBackend([int]$ProcessId) {
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId"
 if(-not $process) { return }
 if($process.CommandLine -notlike '*devfest-backend-0.1.0.jar*--spring.config.import=*--server.port=8082*') { throw "Refusing to stop unidentified process $ProcessId." }
 Stop-Process -Id $ProcessId
 Wait-Process -Id $ProcessId -ErrorAction SilentlyContinue
}

$jar=Join-Path $root 'backend/target/devfest-backend-0.1.0.jar'
if(-not (Test-Path -LiteralPath $jar)) { throw 'backend/target/devfest-backend-0.1.0.jar is missing. Build the backend first.' }
$recordPath=Join-Path $local 'event-rehearsal.json'
if($ResetBackend) {
 if(-not (Test-Path -LiteralPath $recordPath)) { throw 'No running rehearsal is recorded; start one first.' }
 $record=Get-Content -Raw -LiteralPath $recordPath | ConvertFrom-Json
 Stop-RecordedBackend $record.backend
 Reset-EventTestData
 $backend=Start-RehearsalBackend $record.adminPassword $record.seedPassword
 $record.backend=$backend.Id
 $record | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $recordPath
 Wait-RehearsalReady $backend $null $record.seedPassword
 if($PracticeKeys) { Set-PracticeClueKeys $record.seedPassword }
 Write-Output 'Rehearsal backend restarted on reset devfest_test data.'
 return
}
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
} | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $recordPath
Write-Output 'Isolated event rehearsal starting: UI http://localhost:4202, API http://127.0.0.1:8082, disposable devfest_test data. Credentials are in the ignored .local/event-rehearsal.json. Do not run backend tests while rehearsing.'
if($NoWait) { return }
Wait-RehearsalReady $backend $frontend $seedPassword
Write-Output 'Rehearsal ready: backend health is UP and the frontend responds on 4202.'
if($PracticeKeys) { Set-PracticeClueKeys $seedPassword }
