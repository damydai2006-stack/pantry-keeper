# Remove only pantry-* services, firewall rule, and scheduled tasks.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [string]$FirewallRuleName = 'pantry-caddy-9443',
    [string]$AcmeFirewallRuleName = 'pantry-caddy-acme-80',
    [switch]$DropDatabase,
    [switch]$ConfirmDropDatabase,
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$PostgresBin = '',
    [string]$PostgresAdminUser = 'postgres'
)

. "$PSScriptRoot\common.ps1"
$names = @("$ServicePrefix-server", "$ServicePrefix-caddy", 'pantry-backup', 'pantry-purge', $FirewallRuleName, $AcmeFirewallRuleName, $InstallRoot)
Assert-PantryScope -Names $names
if ($DropDatabase) {
    Assert-SafeIdent -Name $DatabaseName
    Assert-SafeIdent -Name $DatabaseUser
    if (-not $ConfirmDropDatabase) {
        throw 'Refusing to drop the database without -ConfirmDropDatabase.'
    }
}

foreach ($svc in @("$ServicePrefix-server", "$ServicePrefix-caddy")) {
    Assert-PantryScope -Names @($svc)
    $existing = Get-Service -Name $svc -ErrorAction SilentlyContinue
    if ($existing) {
        Stop-Service -Name $svc -Force -ErrorAction SilentlyContinue
        & (Join-Path $InstallRoot "bin\$svc.exe") uninstall
        if ($LASTEXITCODE -ne 0) { throw "$svc uninstall failed." }
    }
}

netsh advfirewall firewall delete rule name="$FirewallRuleName" | Out-Null
netsh advfirewall firewall delete rule name="$AcmeFirewallRuleName" | Out-Null
Unregister-ScheduledTask -TaskName 'pantry-backup' -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName 'pantry-purge' -Confirm:$false -ErrorAction SilentlyContinue

if ($DropDatabase) {
    if ([string]::IsNullOrWhiteSpace($PostgresBin)) { throw 'Pass -PostgresBin to drop the pantry database.' }
    & (Join-Path $PSScriptRoot 'backup.ps1') -InstallRoot $InstallRoot -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser -PostgresBin (Trim-PantryPath -Path $PostgresBin)
    if ($LASTEXITCODE -ne 0) { throw 'Backup before drop failed. The database was not dropped.' }
    $psql = Join-Path (Trim-PantryPath -Path $PostgresBin) 'psql.exe'
    & $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $DatabaseName"
    if ($LASTEXITCODE -ne 0) { throw 'Dropping the pantry database failed. A backup was written first.' }
}

Write-Host 'Uninstalled pantry services only. PostgreSQL was not restarted.'
