# Resets a LOCAL DevFest database to a brand-new, empty state for testing.
# The database is dropped and recreated empty; Flyway rebuilds every table on the next backend start,
# and the bootstrap administrator from backend/.env is created again.
# Only the local cluster (127.0.0.1:55432) and only the databases devfest / devfest_test are accepted.
param(
  [ValidateSet('devfest', 'devfest_test')][string]$Database = 'devfest',
  [switch]$NoBackup,
  [switch]$Force,
  [string]$PgBin = 'C:\Program Files\PostgreSQL\18\bin'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $root '.local'
$secrets = Join-Path $local 'database.json'
$dbHost = '127.0.0.1'
$dbPort = 55432

function Invoke-Pg([string]$tool, [string[]]$arguments) {
  & (Join-Path $PgBin $tool) @arguments
  if ($LASTEXITCODE -ne 0) { throw "$tool failed (exit code $LASTEXITCODE)." }
}

if (-not (Test-Path -LiteralPath $secrets)) { throw 'Missing .local/database.json. Run .\infrastructure\start-local-database.ps1 first.' }
$config = Get-Content -Raw -LiteralPath $secrets | ConvertFrom-Json

# A running backend keeps connections open and would fail mid-test; ask the user to stop it explicitly.
$backendPort = if ($Database -eq 'devfest') { 8081 } else { 8082 }
if (Get-NetTCPConnection -State Listen -LocalPort $backendPort -ErrorAction SilentlyContinue) {
  throw "A backend is running on port $backendPort. Stop it first (Ctrl+C in its terminal, or .\infrastructure\stop-event-rehearsal.ps1 for devfest_test), then run this script again."
}

if (-not $Force) {
  Write-Host "This permanently deletes ALL data in the local database '$Database' (accounts, teams, rooms, answers, results)." -ForegroundColor Yellow
  if (-not $NoBackup) { Write-Host 'A backup is saved first in .local\backups.' }
  $answer = Read-Host "Type the database name ($Database) to continue"
  if ($answer -ne $Database) { Write-Host 'Cancelled. Nothing was changed.'; exit 1 }
}

$env:PGPASSWORD = $config.password
try {
  $reachable = & (Join-Path $PgBin 'psql.exe') -h $dbHost -p $dbPort -U devfest -d postgres -tAc 'SELECT 1'
  if ($LASTEXITCODE -ne 0 -or $reachable -ne '1') { throw 'The local PostgreSQL cluster is not running. Start it with .\infrastructure\start-local-database.ps1.' }

  if (-not $NoBackup) {
    $backups = Join-Path $local 'backups'
    New-Item -ItemType Directory -Force -Path $backups | Out-Null
    $dump = Join-Path $backups ("$Database-before-reset-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump')
    Invoke-Pg 'pg_dump.exe' @('-h', $dbHost, '-p', "$dbPort", '-U', 'devfest', '-d', $Database, '-Fc', '-f', $dump)
    Write-Host "Backup saved: $dump"
  }

  # Close leftover connections (pgAdmin, an IDE) so the database can be dropped.
  Invoke-Pg 'psql.exe' @('-h', $dbHost, '-p', "$dbPort", '-U', 'devfest', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-qc',
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();")

  Invoke-Pg 'dropdb.exe' @('-h', $dbHost, '-p', "$dbPort", '-U', 'devfest', $Database)
  $owner = if ($Database -eq 'devfest') { 'devfest' } else { 'devfest_test' }
  Invoke-Pg 'createdb.exe' @('-h', $dbHost, '-p', "$dbPort", '-U', 'devfest', '-O', $owner, $Database)
  if ($Database -eq 'devfest') {
    # Same access rule as start-local-database.ps1.
    Invoke-Pg 'psql.exe' @('-h', $dbHost, '-p', "$dbPort", '-U', 'devfest', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-qc',
      'REVOKE CONNECT ON DATABASE devfest FROM PUBLIC; GRANT CONNECT ON DATABASE devfest TO devfest;')
  }
} finally {
  Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Database '$Database' is now empty." -ForegroundColor Green
if ($Database -eq 'devfest') {
  Write-Host 'Next: start the backend (.\infrastructure\start-backend.ps1). It recreates all tables and the administrator from backend\.env.'
} else {
  Write-Host 'Next: run the tests or start the rehearsal; they recreate all tables automatically.'
}
