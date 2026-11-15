# Install pantry-server and pantry-caddy. Does not restart PostgreSQL, does not edit
# the PostgreSQL server configuration file, and does not change the ptt role or database.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [int]$ListenPort = 18280,
    [int]$PublicPort = 9443,
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$DatabasePassword = '',
    [string]$FirewallRuleName = 'pantry-caddy-9443',
    [switch]$SkipFirewall,
    [string]$OfflineDir = '',
    [switch]$ResetPantryPassword,
    [string]$PublicIp = '',
    [string]$AcmeEmail = '',
    [int]$AcmeHttpPort = 80,
    [string]$PostgresBin = '',
    [string]$PostgresAdminUser = 'postgres'
)

. "$PSScriptRoot\common.ps1"

Assert-PantryScope -Names @($InstallRoot, $ServicePrefix, $FirewallRuleName, "$ServicePrefix-server", "$ServicePrefix-caddy", 'pantry-backup', 'pantry-purge')
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
Assert-PantryPort -Port $ListenPort
Assert-PantryPort -Port $PublicPort

$serverService = "$ServicePrefix-server"
$caddyService = "$ServicePrefix-caddy"
Assert-PantryScope -Names @($serverService, $caddyService)

function Show-ManualPgHbaInstruction {
    Write-Host 'MANUAL ONLY, not performed by this script: if loopback password login is not already allowed, an operator may append the next line to pg_hba.conf during a maintenance window and then run SELECT pg_reload_conf(); do not restart PostgreSQL.'
    Write-Host ("host {0} {1} 127.0.0.1/32 scram-sha-256" -f $DatabaseName, $DatabaseUser)
    Write-Host 'This script does not edit pg_hba.conf, does not edit the PostgreSQL server configuration file, and does not restart PostgreSQL.'
}

function Test-ListenPortFree {
    param([int]$Port)
    $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listeners) {
        throw "Port $Port is already in use. Refusing to continue."
    }
}

Show-ManualPgHbaInstruction
Test-ListenPortFree -Port $ListenPort
Test-ListenPortFree -Port $PublicPort

New-Item -ItemType Directory -Force -Path @(
    "$InstallRoot\bin", "$InstallRoot\conf", "$InstallRoot\data\photos", "$InstallRoot\logs", "$InstallRoot\backup", "$InstallRoot\web"
) | Out-Null

if ([string]::IsNullOrWhiteSpace($PostgresBin)) {
    throw 'Pass -PostgresBin pointing at the existing psql.exe. This script does not install or restart PostgreSQL.'
}
$psql = Join-Path $PostgresBin 'psql.exe'
if (-not (Test-Path $psql)) { throw "psql not found at $psql" }

$env:PGPASSWORD = $DatabasePassword
$sql = @"
DO `$`$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$DatabaseUser') THEN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L CONNECTION LIMIT 10', '$DatabaseUser', '$DatabasePassword');
  END IF;
END
`$`$;
"@
& $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c $sql
if ($LASTEXITCODE -ne 0) { throw 'CREATE ROLE failed' }

$existsDb = & $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DatabaseName'"
if ($existsDb.Trim() -ne '1') {
    & $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $DatabaseName OWNER $DatabaseUser"
    if ($LASTEXITCODE -ne 0) { throw 'CREATE DATABASE failed' }
}
& $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c "REVOKE CONNECT ON DATABASE $DatabaseName FROM PUBLIC"
& $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c "ALTER ROLE $DatabaseUser SET statement_timeout = '5s'"
if ($ResetPantryPassword) {
    & $psql -h 127.0.0.1 -U $PostgresAdminUser -d postgres -v ON_ERROR_STOP=1 -c "ALTER ROLE $DatabaseUser PASSWORD '$DatabasePassword'"
}

if ([string]::IsNullOrWhiteSpace($PublicIp)) {
    throw 'Pass -PublicIp at install time. No address is stored in the repository.'
}
$caddyTemplate = Get-Content -Raw -Path (Join-Path $PSScriptRoot 'Caddyfile.template')
$caddyText = $caddyTemplate.Replace('PANTRY_PUBLIC_IP', $PublicIp).Replace('PANTRY_PUBLIC_PORT', "$PublicPort").Replace('PANTRY_LISTEN_PORT', "$ListenPort").Replace('PANTRY_ACME_EMAIL', $AcmeEmail).Replace('PANTRY_WEB_ROOT', "$InstallRoot\web").Replace('PANTRY_ACME_HTTP_PORT', "$AcmeHttpPort")
Set-Content -Path "$InstallRoot\conf\Caddyfile" -Value $caddyText -Encoding utf8

function Install-PantryService {
    param([string]$Name, [string]$TemplateName)
    Assert-PantryScope -Names @($Name)
    if ([string]::IsNullOrWhiteSpace($OfflineDir)) { throw 'Pass -OfflineDir with WinSW and binaries.' }
    $winsw = Join-Path $OfflineDir 'WinSW.exe'
    if (-not (Test-Path $winsw)) { throw 'WinSW.exe missing from -OfflineDir' }
    $xml = Get-Content -Raw -Path (Join-Path $PSScriptRoot $TemplateName)
    $xml = $xml.Replace('__SERVICE_ID__', $Name).Replace('__INSTALL_ROOT__', $InstallRoot).Replace('__LISTEN_PORT__', "$ListenPort")
    $target = Join-Path $InstallRoot "bin\$Name.xml"
    Set-Content -Path $target -Value $xml -Encoding utf8
    Copy-Item $winsw (Join-Path $InstallRoot "bin\$Name.exe") -Force
    & (Join-Path $InstallRoot "bin\$Name.exe") install
}

Install-PantryService -Name $serverService -TemplateName 'pantry-server.xml.template'
Install-PantryService -Name $caddyService -TemplateName 'pantry-caddy.xml.template'

if (-not $SkipFirewall) {
    Assert-PantryScope -Names @($FirewallRuleName)
    netsh advfirewall firewall delete rule name="$FirewallRuleName" | Out-Null
    netsh advfirewall firewall add rule name="$FirewallRuleName" dir=in action=allow protocol=TCP localport=$PublicPort | Out-Null
}

Write-Host "Installed $serverService and $caddyService under $InstallRoot. PostgreSQL was not restarted."
