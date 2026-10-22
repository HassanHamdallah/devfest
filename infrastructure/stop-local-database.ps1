param([string]$PgBin='C:\Program Files\PostgreSQL\18\bin')
$root=Split-Path $PSScriptRoot -Parent
& (Join-Path $PgBin 'pg_ctl.exe') stop -D (Join-Path $root '.local/postgres') -m fast
