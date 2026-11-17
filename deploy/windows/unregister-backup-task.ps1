# Remove only the pantry-backup scheduled task.
param(
    [string]$TaskName = 'pantry-backup'
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($TaskName)
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Write-Host "Unregistered $TaskName if it was present. Other tasks were not changed."
