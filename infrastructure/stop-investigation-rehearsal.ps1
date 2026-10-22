$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$ids=Get-Content -Raw -LiteralPath (Join-Path $root '.local/investigation-rehearsal.json') | ConvertFrom-Json
foreach($entry in @(@{id=$ids.backend;pattern='*devfest-backend-0.1.0.jar*--spring.config.import=*'},@{id=$ids.frontend;pattern='*angular/cli/bin/ng.js*--port 4202*'})) {
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.id)"
 if(-not $process) { continue }
 if($process.CommandLine -notlike $entry.pattern) { throw 'Refusing to stop an unidentified process.' }
 Stop-Process -Id $entry.id
 Wait-Process -Id $entry.id -ErrorAction SilentlyContinue
}
Write-Output 'Stopped only the recorded investigation rehearsal processes.'
