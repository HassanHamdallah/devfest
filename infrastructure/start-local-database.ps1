param([string]$PgBin='C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$local=Join-Path $root '.local'
New-Item -ItemType Directory -Force -Path $local | Out-Null
$data=Join-Path $local 'postgres'
$secrets=Join-Path $local 'database.json'
if (-not (Test-Path -LiteralPath $secrets)) {
 $dbPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
 $testPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
 @{password=$dbPassword;testPassword=$testPassword} | ConvertTo-Json | Set-Content -LiteralPath $secrets
}
$config=Get-Content -Raw -LiteralPath $secrets | ConvertFrom-Json
if (-not (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))) {
 $pwfile=Join-Path $local 'init-password'
 Set-Content -LiteralPath $pwfile -Value $config.password
 & (Join-Path $PgBin 'initdb.exe') -D $data -U devfest --pwfile=$pwfile --auth=scram-sha-256 --encoding=UTF8 --locale=C
 if ($LASTEXITCODE -ne 0) {throw 'DevFest cluster initialization failed'}
 Remove-Item -LiteralPath $pwfile
 Add-Content -LiteralPath (Join-Path $data 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = 55432"
}
& (Join-Path $PgBin 'pg_ctl.exe') status -D $data *> $null
if ($LASTEXITCODE -ne 0) { & (Join-Path $PgBin 'pg_ctl.exe') start -D $data -l (Join-Path $local 'postgres.log') -w }
if ($LASTEXITCODE -ne 0) {throw 'DevFest PostgreSQL failed to start; check port 55432'}
$env:PGPASSWORD=$config.password
$exists=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='devfest'"
if ($exists -ne '1') { & (Join-Path $PgBin 'createdb.exe') -h 127.0.0.1 -p 55432 -U devfest devfest }
$exists=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='devfest_test'"
if ($exists -ne '1') { & (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d postgres -c "CREATE ROLE devfest_test LOGIN PASSWORD '$($config.testPassword)'" }
$exists=& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='devfest_test'"
if ($exists -ne '1') { & (Join-Path $PgBin 'createdb.exe') -h 127.0.0.1 -p 55432 -U devfest -O devfest_test devfest_test }
& (Join-Path $PgBin 'psql.exe') -h 127.0.0.1 -p 55432 -U devfest -d postgres -c 'REVOKE CONNECT ON DATABASE devfest FROM PUBLIC; GRANT CONNECT ON DATABASE devfest TO devfest;'
Remove-Item Env:PGPASSWORD
$envfile=Join-Path $root 'backend\.env'
if (-not (Test-Path -LiteralPath $envfile)) {
 $jwt=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
 $admin=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))
 @("DEVFEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/devfest","DEVFEST_DB_USER=devfest","DEVFEST_DB_PASSWORD=$($config.password)","DEVFEST_JWT_SECRET=$jwt",'DEVFEST_ADMIN_EMAIL=admin@devfest.local',"DEVFEST_ADMIN_PASSWORD=$admin",'DEVFEST_SEED=true','DEVFEST_DEVELOPMENT=true','JUDGE0_URL=https://ce.judge0.com') | Set-Content -LiteralPath $envfile
}
# The AI quiz's order secret (added later): appended once to an existing .env, never printed.
if (-not (Select-String -LiteralPath $envfile -Pattern '^DEVFEST_QUIZ_SHUFFLE_SECRET=' -Quiet)) {
 $shuffle=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
 Add-Content -LiteralPath $envfile -Value "DEVFEST_QUIZ_SHUFFLE_SECRET=$shuffle"
}
Write-Output 'DevFest-only cluster ready on 127.0.0.1:55432. Development credentials are in backend/.env; test credentials in .local/database.json. No credentials printed.'
