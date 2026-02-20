param(
  [string]$HostAddress = "127.0.0.1",
  [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path .venv)) {
  throw ".venv not found. Run .\\setup.ps1 first."
}

if (-not (Test-Path .env)) {
  throw ".env not found. Fill .env before starting."
}

& .\.venv\Scripts\python -m uvicorn main:app --host $HostAddress --port $Port
