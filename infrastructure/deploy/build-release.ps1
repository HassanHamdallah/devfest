<#
.SYNOPSIS
  Builds one release zip (backend jar + production frontend) for deploy.sh. Run on the Windows laptop.

.DESCRIPTION
  1. ng build --configuration production      (frontend\dist\devfest\browser)
  2. mvnw package                             (backend\target\devfest-backend-*.jar); tests run unless -SkipTests
  3. one zip: release\devfest-<yyyyMMdd>-<git short hash>.zip containing
        devfest-backend.jar, frontend\ (the browser build), RELEASE.txt

  Run from anywhere:
     powershell -ExecutionPolicy Bypass -File infrastructure\deploy\build-release.ps1
     powershell -ExecutionPolicy Bypass -File infrastructure\deploy\build-release.ps1 -SkipTests
#>
param(
  # Skips the backend tests (they need the local test database). Use only when you already ran them.
  [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Frontend = Join-Path $Repo 'frontend'
$Backend = Join-Path $Repo 'backend'
$OutDir = Join-Path $Repo 'release'

function Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Need($name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "'$name' was not found on PATH. Install it first." }
}
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE). Nothing was packaged." } }

Need node; Need npm; Need java; Need git

# --- version label: date + git short hash (a "-dirty" suffix when there are uncommitted changes) ---------------------
Push-Location $Repo
try {
  $hash = (git rev-parse --short HEAD).Trim()
  if ($LASTEXITCODE -ne 0 -or -not $hash) { throw 'git rev-parse failed: is this a git checkout?' }
  $dirty = (git status --porcelain)
} finally { Pop-Location }
if ($dirty) { $hash = "$hash-dirty"; Write-Warning 'You have uncommitted changes: the zip is named -dirty. Commit before the real event build.' }
$Name = 'devfest-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd'), $hash

# --- 1. frontend ---------------------------------------------------------------------------------------------------
Step 'Frontend: ng build --configuration production'
Push-Location $Frontend
try {
  if (-not (Test-Path 'node_modules')) { npm ci; Check 'npm ci' }
  if (Test-Path 'dist') { Remove-Item 'dist' -Recurse -Force }
  npx ng build --configuration production
  Check 'ng build'
} finally { Pop-Location }
$Dist = Join-Path $Frontend 'dist\devfest\browser'
if (-not (Test-Path (Join-Path $Dist 'index.html'))) { throw "Build output missing: $Dist\index.html" }

# --- 2. backend ----------------------------------------------------------------------------------------------------
Step ("Backend: mvnw package" + $(if ($SkipTests) { ' (tests SKIPPED)' } else { ' (with tests)' }))
Push-Location $Backend
try {
  $mvnArgs = @('-B', 'clean', 'package')
  if ($SkipTests) { $mvnArgs += '-DskipTests' }
  & .\mvnw.cmd @mvnArgs
  Check 'mvn package'
} finally { Pop-Location }
$Jar = Get-ChildItem (Join-Path $Backend 'target') -Filter 'devfest-backend-*.jar' |
  Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Jar) { throw 'No devfest-backend-*.jar in backend\target.' }

# --- 3. one zip ------------------------------------------------------------------------------------------------------
Step "Packaging $Name.zip"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Zip = Join-Path $OutDir "$Name.zip"
if (Test-Path $Zip) { Remove-Item $Zip -Force }

$Info = @(
  "release : $Name",
  "built   : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
  "commit  : $hash",
  "jar     : $($Jar.Name)",
  "tests   : $(if ($SkipTests) { 'SKIPPED' } else { 'run' })"
) -join "`n"
$InfoFile = Join-Path $env:TEMP "$Name-RELEASE.txt"
[IO.File]::WriteAllText($InfoFile, $Info + "`n")

# ZipArchive with forward-slash entry names. (Compress-Archive in Windows PowerShell 5.1 can write backslashes,
# which Linux unzip treats as part of the file name.)
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($Zip, [IO.Compression.ZipArchiveMode]::Create)
try {
  [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $Jar.FullName, 'devfest-backend.jar', [IO.Compression.CompressionLevel]::Optimal)
  [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $InfoFile, 'RELEASE.txt', [IO.Compression.CompressionLevel]::Optimal)
  $root = (Resolve-Path $Dist).Path.TrimEnd('\')
  $count = 0
  Get-ChildItem $Dist -Recurse -File | ForEach-Object {
    $entry = 'frontend/' + $_.FullName.Substring($root.Length + 1).Replace('\', '/')
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $entry, [IO.Compression.CompressionLevel]::Optimal)
    $count++
  }
} finally { $archive.Dispose(); Remove-Item $InfoFile -Force -ErrorAction SilentlyContinue }

$mb = [math]::Round((Get-Item $Zip).Length / 1MB, 1)
Write-Host ""
Write-Host "Release ready: $Zip  ($mb MB, $count frontend files)" -ForegroundColor Green
Write-Host "Next (replace SERVER_IP; the login user is 'ubuntu' on Oracle Cloud, see docs/DEPLOYMENT.md section 3):"
Write-Host "  scp `"$Zip`" ubuntu@SERVER_IP:/home/ubuntu/"
Write-Host "  ssh ubuntu@SERVER_IP `"sudo mv /home/ubuntu/$Name.zip /srv/devfest/releases/ && sudo /srv/devfest/bin/deploy.sh /srv/devfest/releases/$Name.zip`""
