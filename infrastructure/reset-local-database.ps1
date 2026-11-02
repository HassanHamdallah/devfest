# Resets a LOCAL DevFest database to a brand-new, empty state for testing.
# The database is dropped and recreated empty; Flyway rebuilds every table on the next backend start,
# and the bootstrap administrator from backend/.env is created again.
#   -Database devfest       resets the database the backend uses, read from DEVFEST_DB_URL in backend/.env
#   -Database devfest_test  resets the disposable test database on the project's cluster (127.0.0.1:55432)
# Only localhost servers and only the database names devfest / devfest_test are accepted.
param(
  [ValidateSet('devfest', 'devfest_test')][string]$Database = 'devfest',
  [switch]$NoBackup,
  [switch]$Force,
  [string]$PgBin = 'C:\Program Files\PostgreSQL\18\bin'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $root '.local'

function Read-EnvFile([string]$path) {
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $path) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
  }
  return $values
}

function Get-Target {
  if ($Database -eq 'devfest_test') {
    $config = Get-Content -Raw -LiteralPath (Join-Path $local 'database.json') | ConvertFrom-Json
    return @{ Host = '127.0.0.1'; Port = 55432; User = 'devfest'; Password = $config.password; Owner = 'devfest_test'; BackendPort = 8082 }
  }
  $envFile = Join-Path $root 'backend\.env'
  if (-not (Test-Path -LiteralPath $envFile)) { throw 'Missing backend\.env.' }
  $values = Read-EnvFile $envFile
  $url = $values['DEVFEST_DB_URL']
  if ($url -notmatch '^jdbc:postgresql://([^/:?]+)(?::(\d+))?/([^/?]+)') { throw "Cannot read DEVFEST_DB_URL in backend\.env: $url" }
  $dbHost = $Matches[1]; $port = if ($Matches[2]) { [int]$Matches[2] } else { 5432 }; $name = $Matches[3]
  if ($dbHost -notin @('localhost', '127.0.0.1', '::1')) { throw "Refusing to reset a non-local server ($dbHost)." }
  if ($name -ne 'devfest') { throw "backend\.env points to database '$name', not 'devfest'. Refusing." }
  $port = [int]$port
  $backendPort = if ($values['PORT']) { [int]$values['PORT'] } else { 8081 }
  return @{ Host = $dbHost; Port = $port; User = $values['DEVFEST_DB_USER']; Password = $values['DEVFEST_DB_PASSWORD']; Owner = $values['DEVFEST_DB_USER']; BackendPort = $backendPort }
}

function Invoke-Pg([string]$tool, [string[]]$arguments) {
  & (Join-Path $PgBin $tool) @arguments
  if ($LASTEXITCODE -ne 0) { throw "$tool failed (exit code $LASTEXITCODE)." }
}

$target = Get-Target
$connection = @('-h', $target.Host, '-p', "$($target.Port)", '-U', $target.User)

# A running backend keeps connections open and would fail mid-test; ask the user to stop it explicitly.
if (Get-NetTCPConnection -State Listen -LocalPort $target.BackendPort -ErrorAction SilentlyContinue) {
  throw "A backend is running on port $($target.BackendPort). Stop it first (stop it in IntelliJ / Ctrl+C in its terminal, or .\infrastructure\stop-event-rehearsal.ps1 for devfest_test), then run this script again."
}

Write-Host "Target: database '$Database' on $($target.Host):$($target.Port) as user '$($target.User)'."
if (-not $Force) {
  Write-Host "This permanently deletes ALL data in this database (accounts, teams, rooms, answers, results)." -ForegroundColor Yellow
  if (-not $NoBackup) { Write-Host 'A backup is saved first in .local\backups.' }
  $answer = Read-Host "Type the database name ($Database) to continue"
  if ($answer -ne $Database) { Write-Host 'Cancelled. Nothing was changed.'; exit 1 }
}

$env:PGPASSWORD = $target.Password
try {
  $reachable = & (Join-Path $PgBin 'psql.exe') @connection -d postgres -tAc 'SELECT 1'
  if ($LASTEXITCODE -ne 0 -or $reachable -ne '1') { throw "PostgreSQL on $($target.Host):$($target.Port) is not reachable with these credentials." }

  $exists = & (Join-Path $PgBin 'psql.exe') @connection -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$Database'"
  if ($exists -eq '1') {
    if (-not $NoBackup) {
      $backups = Join-Path $local 'backups'
      New-Item -ItemType Directory -Force -Path $backups | Out-Null
      $dump = Join-Path $backups ("$Database-$($target.Port)-before-reset-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump')
      Invoke-Pg 'pg_dump.exe' ($connection + @('-d', $Database, '-Fc', '-f', $dump))
      Write-Host "Backup saved: $dump"
    }
    # Close leftover connections (pgAdmin, an IDE) so the database can be dropped.
    Invoke-Pg 'psql.exe' ($connection + @('-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-qc',
      "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();"))
    Invoke-Pg 'dropdb.exe' ($connection + @($Database))
  }
  Invoke-Pg 'createdb.exe' ($connection + @('-O', $target.Owner, $Database))
} finally {
  $env:PGPASSWORD = ''
}

Write-Host ''
Write-Host "Database '$Database' on port $($target.Port) is now empty." -ForegroundColor Green
if ($Database -eq 'devfest') {
  Write-Host 'Next: start the backend again (IntelliJ or .\infrastructure\start-backend.ps1). It recreates all tables and the administrator from backend\.env.'
} else {
  Write-Host 'Next: run the tests or start the rehearsal; they recreate all tables automatically.'
}
