# Register a daily pantry-backup scheduled task running as SYSTEM.
# Not called by install.ps1. The task arguments do not contain the database password.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$At = '02:00',
    [string]$PurgeAt = '03:10',
    [string]$PostgresBin = '',
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [int]$Keep = 14,
    [string]$TaskName = 'pantry-backup',
    [string]$PurgeTaskName = 'pantry-purge'
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $TaskName, $PurgeTaskName, $DatabaseName, $DatabaseUser)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
if ($At -notmatch '^\d{1,2}:\d{2}$') { throw '-At must be HH:mm.' }
if ($PurgeAt -notmatch '^\d{1,2}:\d{2}$') { throw '-PurgeAt must be HH:mm.' }
if ([string]::IsNullOrWhiteSpace($PostgresBin)) { throw 'Pass -PostgresBin.' }
if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin 'pg_dump.exe'))) { throw 'pg_dump.exe not found in -PostgresBin.' }

$bin = Join-Path $InstallRoot 'bin'
New-Item -ItemType Directory -Force -Path $bin | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'backup.ps1') -Destination (Join-Path $bin 'backup.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'purge.ps1') -Destination (Join-Path $bin 'purge.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'common.ps1') -Destination (Join-Path $bin 'common.ps1') -Force

$scriptPath = Join-Path $bin 'backup.ps1'
$argument = Get-PantryBackupTaskArgument -ScriptPath $scriptPath -InstallRoot $InstallRoot -PostgresBin $PostgresBin -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser -Keep $Keep
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argument
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

$purgeScript = Join-Path $bin 'purge.ps1'
$purgeArgument = Get-PantryPurgeTaskArgument -ScriptPath $purgeScript -InstallRoot $InstallRoot
$purgeAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $purgeArgument
$purgeTrigger = New-ScheduledTaskTrigger -Daily -At $PurgeAt
Register-ScheduledTask -TaskName $PurgeTaskName -Action $purgeAction -Trigger $purgeTrigger -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "Registered $TaskName daily at $At and $PurgeTaskName daily at $PurgeAt as SYSTEM. purge.ps1 sets PANTRY_ENV_FILE. install.ps1 does not do this."
