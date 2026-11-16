# Status of pantry-server and pantry-caddy only.
param(
    [string]$ServicePrefix = 'pantry',
    [int]$ListenPort = 18280
)

. "$PSScriptRoot\common.ps1"
$names = @("$ServicePrefix-server", "$ServicePrefix-caddy")
Assert-PantryScope -Names $names
Assert-PantryPort -Port $ListenPort

foreach ($svc in $names) {
    $row = Get-Service -Name $svc -ErrorAction SilentlyContinue
    if ($row) { Write-Host "$svc $($row.Status)" } else { Write-Host "$svc missing" }
}

$url = "http://127.0.0.1:$ListenPort/healthz"
try {
    $res = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
    Write-Host "healthz $($res.StatusCode)"
} catch {
    Write-Host 'healthz unreachable'
}
