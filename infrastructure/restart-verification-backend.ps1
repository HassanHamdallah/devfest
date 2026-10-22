# Only for the background DevFest process started during local verification.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$ids=Get-Content -Raw -LiteralPath (Join-Path $root '.local/processes.json') | ConvertFrom-Json
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($ids.backend)"
if(-not $process -or $process.CommandLine -notlike '*devfest-backend-0.1.0.jar*'){throw 'Refusing to stop an unidentified process'}
Stop-Process -Id $ids.backend
Wait-Process -Id $ids.backend -ErrorAction SilentlyContinue
Push-Location (Join-Path $root 'backend')
try { & .\mvnw.cmd -q -DskipTests package; if($LASTEXITCODE -ne 0){throw 'Package failed'} } finally {Pop-Location}
$started=Start-Process -FilePath java -ArgumentList '-jar','target/devfest-backend-0.1.0.jar' -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root '.local/backend.log') -RedirectStandardError (Join-Path $root '.local/backend-error.log') -PassThru
$ids.backend=$started.Id
$ids | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.local/processes.json')
Write-Output 'Identified DevFest backend rebuilt and restarted.'
