$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location (Join-Path $root 'backend')
& .\mvnw.cmd spring-boot:run
