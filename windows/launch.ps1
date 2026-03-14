$ErrorActionPreference = "Stop"

$windowsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoDir = Resolve-Path (Join-Path $windowsDir "..")
$dataDir = Join-Path $windowsDir "data"
$pidFile = Join-Path $dataDir "server.pid"
$port = 8765
$healthUrl = "http://127.0.0.1:$port/api/health"

New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

function Get-PythonPath {
  $cmd = Get-Command python -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw "Python was not found in PATH."
}

function Get-EdgePath {
  $cmd = Get-Command msedge.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }

  $candidates = @(
    "$Env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "${Env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
  )

  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path $candidate)) {
      return $candidate
    }
  }

  return $null
}

function Test-Health {
  try {
    $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2
    return $response.StatusCode -eq 200
  } catch {
    return $false
  }
}

if (Test-Path $pidFile) {
  $existingPid = Get-Content -Path $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($existingPid) {
    $existingProcess = Get-Process -Id ([int]$existingPid) -ErrorAction SilentlyContinue
    if (-not $existingProcess) {
      Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
    }
  }
}

if (-not (Test-Health)) {
  $pythonPath = Get-PythonPath
  $server = Start-Process `
    -FilePath $pythonPath `
    -ArgumentList @("windows\app.py", "--host", "127.0.0.1", "--port", "$port") `
    -WorkingDirectory $repoDir `
    -PassThru

  Set-Content -Path $pidFile -Value $server.Id -Encoding ASCII

  $started = $false
  for ($attempt = 0; $attempt -lt 50; $attempt++) {
    Start-Sleep -Milliseconds 200
    if (Test-Health) {
      $started = $true
      break
    }
  }

  if (-not $started) {
    throw "Desktop host did not start on $healthUrl."
  }
}

$edgePath = Get-EdgePath
$appUrl = "http://127.0.0.1:$port/"

if ($edgePath) {
  $profileDir = Join-Path $windowsDir "edge-profile"
  Start-Process -FilePath $edgePath -ArgumentList @("--app=$appUrl", "--user-data-dir=$profileDir")
} else {
  Start-Process $appUrl
}
