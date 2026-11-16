# Replace pantry-server.jar and roll back that jar only. Does not restart PostgreSQL or ptt services.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [int]$ListenPort = 18280,
    [Parameter(Mandatory = $true)][string]$NewJar
)

. "$PSScriptRoot\common.ps1"
$service = "$ServicePrefix-server"
Assert-PantryScope -Names @($InstallRoot, $ServicePrefix, $service)
Assert-PantryPort -Port $ListenPort
if (-not (Test-Path $NewJar)) { throw "New jar not found: $NewJar" }

$current = Join-Path $InstallRoot 'bin\pantry-server.jar'
$previous = Join-Path $InstallRoot 'bin\pantry-server.jar.prev'
if (Test-Path $current) { Copy-Item $current $previous -Force }
Copy-Item $NewJar $current -Force

function Restart-PantryService {
    param([string]$Name)
    Assert-PantryScope -Names @($Name)
    Restart-Service -Name $Name
}

function Test-PantryHealth {
    $url = "http://127.0.0.1:$ListenPort/healthz"
    try {
        $res = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20
        return $res.StatusCode -eq 200
    } catch {
        return $false
    }
}

Restart-PantryService -Name $service
Start-Sleep -Seconds 3
if (-not (Test-PantryHealth)) {
    if (Test-Path $previous) { Copy-Item $previous $current -Force }
    Restart-PantryService -Name $service
    throw 'Upgrade failed health check. Rolled back pantry-server.jar. PostgreSQL was not restarted.'
}
Write-Host "Upgraded $service. Health check on 127.0.0.1:$ListenPort passed."
