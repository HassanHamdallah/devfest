$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=Get-Content -Raw -LiteralPath (Join-Path $root '.local/database.json') | ConvertFrom-Json
$env:DEVFEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/devfest_test'
$env:DEVFEST_DB_USER='devfest_test'
$env:DEVFEST_DB_PASSWORD=$config.testPassword
$env:DEVFEST_JWT_SECRET='devfest-test-only-secret-with-48-characters-123456'
Push-Location (Join-Path $root 'backend')
try { & .\mvnw.cmd test; if ($LASTEXITCODE -ne 0) { throw 'DevFest backend tests failed' } }
finally { Pop-Location;Remove-Item Env:DEVFEST_DB_URL,Env:DEVFEST_DB_USER,Env:DEVFEST_DB_PASSWORD,Env:DEVFEST_JWT_SECRET -ErrorAction SilentlyContinue }
