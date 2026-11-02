$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$recordPath=Join-Path $root '.local/event-rehearsal.json'
if(-not (Test-Path -LiteralPath $recordPath)) { throw 'No .local/event-rehearsal.json found; nothing recorded to stop.' }
$ids=Get-Content -Raw -LiteralPath $recordPath | ConvertFrom-Json
$recorded=@(
 @{id=$ids.backend;pattern='*devfest-backend-0.1.0.jar*--spring.config.import=*--server.port=8082*'},
 @{id=$ids.frontend;pattern='*angular/cli/bin/ng.js*--port 4202*'}
)
foreach($entry in $recorded) {
 if(-not $entry.id) { continue }
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.id)"
 if(-not $process) { continue }
 # A recycled PID could belong to anything; only the exact rehearsal command lines are stopped.
 if($process.CommandLine -notlike $entry.pattern) { throw "Refusing to stop unidentified process $($entry.id)." }
 Stop-Process -Id $entry.id
 Wait-Process -Id $entry.id -ErrorAction SilentlyContinue
}
Write-Output 'Stopped only the recorded event rehearsal processes.'
