# Remove only the pantry-backup and pantry-purge scheduled tasks.
param(
    [string[]]$TaskName = @('pantry-backup', 'pantry-purge')
)

. "$PSScriptRoot\common.ps1"
foreach ($name in $TaskName) {
    Assert-PantryScope -Names @($name)
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Unregistered $name if it was present."
}
Write-Host 'Other tasks were not changed.'
