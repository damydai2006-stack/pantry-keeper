# Register a daily pantry-backup scheduled task running as SYSTEM.
# Not called by install.ps1. The task arguments do not contain the database password.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$At = '02:00',
    [string]$PostgresBin = '',
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [int]$Keep = 14,
    [string]$TaskName = 'pantry-backup'
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $TaskName, $DatabaseName, $DatabaseUser)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
if ($At -notmatch '^\d{1,2}:\d{2}$') { throw '-At must be HH:mm.' }
if ([string]::IsNullOrWhiteSpace($PostgresBin)) { throw 'Pass -PostgresBin.' }
if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin 'pg_dump.exe'))) { throw 'pg_dump.exe not found in -PostgresBin.' }

$bin = Join-Path $InstallRoot 'bin'
New-Item -ItemType Directory -Force -Path $bin | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'backup.ps1') -Destination (Join-Path $bin 'backup.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'common.ps1') -Destination (Join-Path $bin 'common.ps1') -Force

$scriptPath = Join-Path $bin 'backup.ps1'
$argument = Get-PantryBackupTaskArgument -ScriptPath $scriptPath -InstallRoot $InstallRoot -PostgresBin $PostgresBin -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser -Keep $Keep
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argument
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "Registered $TaskName daily at $At as SYSTEM. install.ps1 does not do this."
