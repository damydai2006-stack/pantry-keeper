# Dump only the pantry database into C:\pantry\backup.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$PostgresBin = ''
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $DatabaseName, $DatabaseUser)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
if ([string]::IsNullOrWhiteSpace($PostgresBin)) { throw 'Pass -PostgresBin.' }
$dump = Join-Path $PostgresBin 'pg_dump.exe'
$destDir = Join-Path $InstallRoot 'backup'
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
$dest = Join-Path $destDir ("pantry-{0}.dump" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
& $dump -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -Fc -f $dest
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
Write-Host "Wrote $dest"
