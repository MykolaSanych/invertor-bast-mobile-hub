param(
  [string]$TaskName = "MyHomePushBridge",
  [string]$HostAddress = "0.0.0.0",
  [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$runScript = Join-Path $root "run.ps1"

if (-not (Test-Path $runScript)) {
  throw "run.ps1 not found: $runScript"
}

$escapedScript = $runScript.Replace('"', '""')
$arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$escapedScript`" -HostAddress $HostAddress -Port $Port"

$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arg
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description "My Home push bridge" -Force | Out-Null
Write-Host "Scheduled task '$TaskName' installed"
