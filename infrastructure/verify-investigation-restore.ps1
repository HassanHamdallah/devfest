param([string]$PgBin='C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$local=Join-Path $root '.local'
$config=Get-Content -Raw -LiteralPath (Join-Path $local 'database.json') | ConvertFrom-Json
# A fresh database every run: never restore over an existing database.
$destination='devfest_restore_'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$dump=Join-Path $local ($destination+'.dump')
$env:PGPASSWORD=$config.password
$started=[DateTime]::UtcNow
try {
 $source=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d devfest_test -tAc 'SELECT current_database()'
 if($LASTEXITCODE -ne 0 -or $source -ne 'devfest_test') { throw 'Refusing to back up an unexpected source.' }
 # A consistent logical snapshot with data fingerprints embedded in the dump verification.
 & (Join-Path $PgBin 'pg_dump.exe') -h 127.0.0.1 -p 55432 -U devfest -d devfest_test -Fc -f $dump
 if($LASTEXITCODE -ne 0) { throw 'Backup failed.' }
 & (Join-Path $PgBin 'createdb.exe') -h 127.0.0.1 -p 55432 -U devfest $destination
 if($LASTEXITCODE -ne 0) { throw 'Could not create a separate restore destination.' }
 & (Join-Path $PgBin 'pg_restore.exe') -h 127.0.0.1 -p 55432 -U devfest -d $destination --no-owner --no-privileges --exit-on-error $dump
 if($LASTEXITCODE -ne 0) { throw 'Restore failed.' }
 $tables=@('app_user','room','room_manager','team','investigation_settings','investigation_challenge','investigation_lifecycle','investigation_draft','investigation_submission','investigation_score','investigation_score_history','audit_event')
 $checks=@()
 foreach($table in $tables) {
  $sql="SELECT count(*)::text || ':' || md5(coalesce(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text),'')) FROM $table t"
  $before=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d devfest_test -tAc $sql
  if($LASTEXITCODE -ne 0) { throw "Source fingerprint failed: $table" }
  $after=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d $destination -tAc $sql
  if($LASTEXITCODE -ne 0 -or $before -ne $after) { throw "Restore mismatch for $table. Keep the rehearsal idle during this check." }
  $checks+=@{table=$table;matches=$true;rows=[int]($after.Split(':')[0])}
 }
 @{verifiedAt=[DateTime]::UtcNow.ToString('o');source='devfest_test';destination=$destination;elapsedSeconds=([DateTime]::UtcNow-$started).TotalSeconds;tables=$checks;scope='Local PostgreSQL logical restore in a separate database; not a hosting-platform backup verification.'} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'docs/investigation-restore-results.json')
 Write-Output "Restore and all table fingerprints passed in separate database $destination. Dump retained in ignored .local. Hosting-platform backups remain unverified."
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
