# Read-only status for pantry-server and pantry-caddy.
# Does not restart services, edit files, or change firewall rules.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [int]$ListenPort = 18280,
    [string]$PublicHealthUrl = ''
)

. "$PSScriptRoot\common.ps1"
$names = @("$ServicePrefix-server", "$ServicePrefix-caddy")
Assert-PantryScope -Names @($InstallRoot, $ServicePrefix) + $names
Assert-PantryPort -Port $ListenPort

foreach ($svc in $names) {
    $row = Get-Service -Name $svc -ErrorAction SilentlyContinue
    if ($row) { Write-Host "$svc $($row.Status)" } else { Write-Host "$svc missing" }
}

$local = "http://127.0.0.1:$ListenPort/healthz"
try {
    $res = Invoke-WebRequest -Uri $local -UseBasicParsing -TimeoutSec 5
    Write-Host "local healthz $($res.StatusCode)"
} catch {
    Write-Host 'local healthz unreachable'
}

if ([string]::IsNullOrWhiteSpace($PublicHealthUrl)) {
    Write-Host 'public healthz skipped (pass -PublicHealthUrl)'
} else {
    try {
        $pub = Invoke-WebRequest -Uri $PublicHealthUrl -UseBasicParsing -TimeoutSec 10
        Write-Host "public healthz $($pub.StatusCode)"
    } catch {
        Write-Host 'public healthz unreachable'
    }
    Show-PantryCertificate -Url $PublicHealthUrl
}

$backupDir = Join-Path $InstallRoot 'backup'
if (Test-Path -LiteralPath $backupDir) {
    $latest = Get-ChildItem -LiteralPath $backupDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'pantry-*.dump' -or $_.Name -like 'pantry-photos-*.zip' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($latest) {
        Write-Host ("last backup {0:yyyy-MM-dd HH:mm:ss} {1}" -f $latest.LastWriteTime, $latest.Name)
    } else {
        Write-Host 'last backup none'
    }
} else {
    Write-Host 'last backup none'
}

function Show-PantryCertificate {
    param([Parameter(Mandatory = $true)][string]$Url)
    $uri = $null
    try { $uri = [Uri]$Url } catch { Write-Host 'cert skipped (url)'; return }
    if ($uri.Scheme -ne 'https') { Write-Host 'cert skipped (not https)'; return }
    $port = $uri.Port
    if ($port -lt 1) { $port = 443 }
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $tcp.Connect($uri.Host, $port)
        $accept = { param($sender, $certificate, $chain, $errors) $true }
        $ssl = New-Object System.Net.Security.SslStream($tcp.GetStream(), $false, $accept)
        $ssl.AuthenticateAsClient($uri.Host)
        $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
        $days = Get-PantryCertDaysLeft -NotAfter $cert.NotAfter -Now ([datetime]::UtcNow)
        Write-Host ("cert issuer={0} notAfter={1:yyyy-MM-dd} daysLeft={2}" -f $cert.Issuer, $cert.NotAfter, $days)
        if (Test-PantryCertWarning -DaysLeft $days) {
            Write-Host 'WARN certificate has under 3 days left. Short-lived certificates last about 6 days.'
        }
        $ssl.Dispose()
    } catch {
        Write-Host 'cert unreadable'
    } finally {
        $tcp.Close()
    }
}
