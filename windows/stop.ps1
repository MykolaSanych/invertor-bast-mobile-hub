$ErrorActionPreference = "Stop"

$windowsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $windowsDir "data\server.pid"

if (-not (Test-Path $pidFile)) {
  Write-Host "No PID file found."
  exit 0
}

$pidValue = Get-Content -Path $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $pidValue) {
  Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
  Write-Host "PID file was empty."
  exit 0
}

$process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
if ($process) {
  Stop-Process -Id $process.Id -Force
  Write-Host "Stopped desktop host process $($process.Id)."
} else {
  Write-Host "Process $pidValue was not running."
}

Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
