# Dump the pantry database as the pantry role, and zip data\photos.
# Reads PANTRY_DB_PASSWORD from server.env into PGPASSWORD for the dump, then clears it.
# Does not print the password. Does not include Caddy data or private keys.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$PostgresBin = '',
    [int]$Keep = 14
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $DatabaseName, $DatabaseUser)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
if ($Keep -lt 1) { throw 'Keep must be at least 1.' }
if ([string]::IsNullOrWhiteSpace($PostgresBin)) { throw 'Pass -PostgresBin.' }
$dump = Join-Path $PostgresBin 'pg_dump.exe'
if (-not (Test-Path -LiteralPath $dump)) { throw "pg_dump not found at $dump" }

$envPath = Join-Path $InstallRoot 'conf\server.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw "server.env not found: $envPath" }
$dbPassword = [string](Get-PantryEnvValue -Text ([System.IO.File]::ReadAllText($envPath)) -Key 'PANTRY_DB_PASSWORD' -Missing $null)
if ([string]::IsNullOrWhiteSpace($dbPassword)) { throw 'PANTRY_DB_PASSWORD is missing from server.env.' }

$destDir = Join-Path $InstallRoot 'backup'
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
$stamp = New-PantryBackupStamp
$dest = Join-Path $destDir ("pantry-{0}.dump" -f $stamp)
$photoZip = Join-Path $destDir ("pantry-photos-{0}.zip" -f $stamp)
$envCopy = Join-Path $destDir ("pantry-env-{0}.env" -f $stamp)
$savedPg = $env:PGPASSWORD
try {
    try {
        $env:PGPASSWORD = $dbPassword
        & $dump -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -Fc -f $dest
        if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
    } finally {
        if ($null -eq $savedPg) {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:PGPASSWORD = $savedPg
        }
        $dbPassword = $null
    }

    $photos = Get-PantryDataDir -InstallRoot $InstallRoot
    if (-not (Test-Path -LiteralPath $photos)) {
        New-Item -ItemType Directory -Force -Path $photos | Out-Null
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $photoZip) { Remove-Item -LiteralPath $photoZip -Force }
    [System.IO.Compression.ZipFile]::CreateFromDirectory($photos, $photoZip)
    Copy-Item -LiteralPath $envPath -Destination $envCopy -Force
    if ($env:OS -eq 'Windows_NT') {
        Set-PantryConfAcl -Path $dest
        Set-PantryConfAcl -Path $photoZip
        Set-PantryConfAcl -Path $envCopy
        Protect-PantryTree -Path $destDir
    }
} catch {
    Clear-PantryPartialBackup -Paths @($dest, $photoZip, $envCopy)
    throw
}

function Remove-PantryOldBackups {
    param([string]$Directory, [string]$Filter, [int]$Retain)
    $files = @(Get-ChildItem -LiteralPath $Directory -File -Filter $Filter | Sort-Object Name -Descending)
    $drop = @(Get-PantryExpiredBackupNames -Names @($files | ForEach-Object { $_.Name }) -Keep $Retain)
    foreach ($name in $drop) {
        if ([string]::IsNullOrWhiteSpace([string]$name)) { continue }
        Remove-Item -LiteralPath (Join-Path $Directory $name) -Force
    }
}

Remove-PantryOldBackups -Directory $destDir -Filter 'pantry-*.dump' -Retain $Keep
Remove-PantryOldBackups -Directory $destDir -Filter 'pantry-photos-*.zip' -Retain $Keep
Remove-PantryOldBackups -Directory $destDir -Filter 'pantry-env-*.env' -Retain $Keep
Write-Host "Wrote $dest, $photoZip, and $envCopy"
Write-Host (Get-PantryBackupOffsiteReminder)
