# Stop pantry-server, back up, then replace the jar. Roll back the jar only.
# Flyway migrations are forward-only. Does not restart PostgreSQL or ptt services.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [int]$ListenPort = 18280,
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$PostgresBin = '',
    [int]$Keep = 14,
    [int]$HealthTimeoutSec = 90,
    [int]$HealthIntervalSec = 2,
    [Parameter(Mandatory = $true)][string]$NewJar
)

. "$PSScriptRoot\common.ps1"
$service = "$ServicePrefix-server"
Assert-PantryScope -Names @($InstallRoot, $ServicePrefix, $service, $DatabaseName, $DatabaseUser)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
Assert-PantryPort -Port $ListenPort
if (-not (Test-Path -LiteralPath $NewJar)) { throw "New jar not found: $NewJar" }
if ([string]::IsNullOrWhiteSpace($PostgresBin)) {
    throw 'Pass -PostgresBin so the upgrade can take a backup before replacing the jar.'
}
$PostgresBin = Trim-PantryPath -Path $PostgresBin

$current = Join-Path $InstallRoot 'bin\pantry-server.jar'
$previous = Join-Path $InstallRoot 'bin\pantry-server.jar.prev'

function Stop-PantryOne {
    param([string]$Name)
    Assert-PantryScope -Names @($Name)
    if (Test-PantryWindowsService -Name $Name) { Stop-Service -Name $Name -Force }
}

function Start-PantryOne {
    param([string]$Name)
    Assert-PantryScope -Names @($Name)
    if (-not (Test-PantryWindowsService -Name $Name)) { throw "$Name is not registered." }
    Start-Service -Name $Name
}

Stop-PantryOne -Name $service
& (Join-Path $PSScriptRoot 'backup.ps1') -InstallRoot $InstallRoot -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser -PostgresBin $PostgresBin -Keep $Keep
if ($LASTEXITCODE -ne 0) { throw 'Pre-upgrade backup failed. The jar was not replaced.' }

if (Test-Path -LiteralPath $current) { Copy-Item -LiteralPath $current -Destination $previous -Force }
Copy-Item -LiteralPath $NewJar -Destination $current -Force
Start-PantryOne -Name $service

$healthy = Wait-PantryHealth -TimeoutSec $HealthTimeoutSec -IntervalSec $HealthIntervalSec -Probe {
    $url = "http://127.0.0.1:$ListenPort/healthz"
    try {
        $res = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
        return ($res.StatusCode -eq 200)
    } catch {
        return $false
    }
}
if (-not $healthy) {
    Stop-PantryOne -Name $service
    if (Test-Path -LiteralPath $previous) { Copy-Item -LiteralPath $previous -Destination $current -Force }
    Start-PantryOne -Name $service
    throw 'Upgrade failed the health check and rolled back pantry-server.jar. Flyway migrations are forward-only. If the new jar already applied a migration, the previous jar may not start; restore the database from the pre-upgrade dump. PostgreSQL was not restarted.'
}
Write-Host "Upgraded $service. Health check on 127.0.0.1:$ListenPort passed within $HealthTimeoutSec seconds."
