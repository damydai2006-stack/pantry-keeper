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
    [string]$PostgresAdminUser = 'postgres',
    [string]$PostgresAdminPassword = ''
)

. "$PSScriptRoot\common.ps1"

Assert-PantryScope -Names @($InstallRoot, $ServicePrefix, $FirewallRuleName, "$ServicePrefix-server", "$ServicePrefix-caddy", 'pantry-backup', 'pantry-purge')
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
Assert-PantryPort -Port $ListenPort
Assert-PantryPort -Port $PublicPort
Assert-PantryPort -Port $AcmeHttpPort

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
Test-ListenPortFree -Port $AcmeHttpPort

New-Item -ItemType Directory -Force -Path @(
    "$InstallRoot\bin", "$InstallRoot\conf", "$InstallRoot\data\photos", "$InstallRoot\logs", "$InstallRoot\backup", "$InstallRoot\web"
) | Out-Null

if ([string]::IsNullOrWhiteSpace($PostgresBin)) {
    throw 'Pass -PostgresBin pointing at the existing psql.exe. This script does not install or restart PostgreSQL.'
}
$psql = Join-Path $PostgresBin 'psql.exe'
if (-not (Test-Path $psql)) { throw "psql not found at $psql" }

$adminPassword = $PostgresAdminPassword
if ([string]::IsNullOrEmpty($adminPassword)) { $adminPassword = $DatabasePassword }
try {
    Invoke-PantryPsql -PsqlPath $psql -AdminUser $PostgresAdminUser -AdminPassword $adminPassword `
        -DatabaseUser $DatabaseUser -DatabaseName $DatabaseName -RolePassword $DatabasePassword `
        -ResetPassword ([bool]$ResetPantryPassword)
} catch {
    Write-Host 'Install stopped after the database step. If this run created the pantry role or database, they are left in place and a later run will reuse them without changing the role password unless -ResetPantryPassword is set. PostgreSQL was not restarted.'
    throw
}

if ([string]::IsNullOrWhiteSpace($PublicIp)) {
    throw 'Pass -PublicIp at install time. No address is stored in the repository.'
}
$caddyTemplate = Get-Content -Raw -Path (Join-Path $PSScriptRoot 'Caddyfile.template')
$caddyText = $caddyTemplate.Replace('PANTRY_PUBLIC_IP', $PublicIp).Replace('PANTRY_PUBLIC_PORT', "$PublicPort").Replace('PANTRY_LISTEN_PORT', "$ListenPort").Replace('PANTRY_ACME_EMAIL', $AcmeEmail).Replace('PANTRY_WEB_ROOT', "$InstallRoot\web").Replace('PANTRY_ACME_HTTP_PORT', "$AcmeHttpPort")
Write-PantryUtf8NoBom -Path "$InstallRoot\conf\Caddyfile" -Value $caddyText

function Install-PantryService {
    param([string]$Name, [string]$TemplateName)
    Assert-PantryScope -Names @($Name)
    if ([string]::IsNullOrWhiteSpace($OfflineDir)) { throw 'Pass -OfflineDir with WinSW and binaries.' }
    $winsw = Join-Path $OfflineDir 'WinSW.exe'
    if (-not (Test-Path $winsw)) { throw 'WinSW.exe missing from -OfflineDir' }
    $xml = Get-Content -Raw -Path (Join-Path $PSScriptRoot $TemplateName)
    $xml = $xml.Replace('__SERVICE_ID__', $Name).Replace('__INSTALL_ROOT__', $InstallRoot).Replace('__LISTEN_PORT__', "$ListenPort")
    $target = Join-Path $InstallRoot "bin\$Name.xml"
    Write-PantryUtf8NoBom -Path $target -Value $xml
    $exe = Join-Path $InstallRoot "bin\$Name.exe"
    Copy-Item $winsw $exe -Force
    & $exe status
    if ($LASTEXITCODE -eq 0) {
        Write-Host "$Name is already installed; leaving the service registration in place."
    } else {
        & $exe install
        if ($LASTEXITCODE -ne 0) {
            throw "$Name install failed. Directories and the pantry database may already exist; rerun is safe and will not change an existing role password unless -ResetPantryPassword is set."
        }
    }
}

Install-PantryService -Name $serverService -TemplateName 'pantry-server.xml.template'
Install-PantryService -Name $caddyService -TemplateName 'pantry-caddy.xml.template'

if (-not $SkipFirewall) {
    Assert-PantryScope -Names @($FirewallRuleName)
    netsh advfirewall firewall delete rule name="$FirewallRuleName" | Out-Null
    netsh advfirewall firewall add rule name="$FirewallRuleName" dir=in action=allow protocol=TCP localport=$PublicPort | Out-Null
}

Write-Host "Installed $serverService and $caddyService under $InstallRoot. PostgreSQL was not restarted."
