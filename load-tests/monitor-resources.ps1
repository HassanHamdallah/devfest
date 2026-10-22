param([int]$Seconds=120,[int]$Interval=2)
$root=Split-Path $PSScriptRoot -Parent
$rows=@()
$end=(Get-Date).AddSeconds($Seconds)
while((Get-Date) -lt $end){
 $ids=Get-Content -Raw (Join-Path $root '.local/processes.json') | ConvertFrom-Json
 foreach($id in @($ids.backend,$ids.frontend)){
  $process=Get-Process -Id $id -ErrorAction SilentlyContinue
  if($process){$rows += [pscustomobject]@{timestamp=(Get-Date).ToUniversalTime().ToString('o');pid=$id;process=$process.ProcessName;cpuSeconds=$process.CPU;workingSetMiB=[math]::Round($process.WorkingSet64/1MB,2)}}
 }
 Start-Sleep -Seconds $Interval
}
$rows | Export-Csv -NoTypeInformation -Path (Join-Path $root 'docs/resource-samples.csv')
