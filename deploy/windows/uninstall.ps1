# Remove only pantry-* services, firewall rule, and scheduled tasks.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [string]$FirewallRuleName = 'pantry-caddy-9443',
    [switch]$DropDatabase,
    [string]$DatabaseName = 'pantry',
    [string]$PostgresBin = '',
    [string]$PostgresAdminUser = 'postgres'
)

. "$PSScriptRoot\common.ps1"
$names = @("$ServicePrefix-server", "$ServicePrefix-caddy", 'pantry-backup', 'pantry-purge', $FirewallRuleName, $InstallRoot)
Assert-PantryScope -Names $names
if ($DropDatabase) { Assert-SafeIdent -Name $DatabaseName }

foreach ($svc in @("$ServicePrefix-server", "$ServicePrefix-caddy")) {
    Assert-PantryScope -Names @($svc)
    $existing = Get-Service -Name $svc -ErrorAction SilentlyContinue
    if ($existing) {
        Stop-Service -Name $svc -Force -ErrorAction SilentlyContinue
        & (Join-Path $InstallRoot "bin\$svc.exe") uninstall
    }
}

netsh advfirewall firewall delete rule name="$FirewallRuleName" | Out-Null
Unregister-ScheduledTask -TaskName 'pantry-backup' -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName 'pantry-purge' -Confirm:$false -ErrorAction SilentlyContinue

if ($DropDatabase) {
    if ([string]::IsNullOrWhiteSpace($PostgresBin)) { throw 'Pass -PostgresBin to drop the pantry database.' }
    $psql = Join-Path $PostgresBin 'psql.exe'
    & $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $DatabaseName"
}

Write-Host 'Uninstalled pantry services only. PostgreSQL was not restarted.'
