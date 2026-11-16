# Ask pantry-server to delete families that have been empty for 30 days.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry'
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $ServicePrefix)
$java = Join-Path $InstallRoot 'jre\bin\java.exe'
$jar = Join-Path $InstallRoot 'bin\pantry-server.jar'
if (-not (Test-Path $java)) { throw "JRE missing: $java" }
& $java -Xmx256m -jar $jar purge
if ($LASTEXITCODE -ne 0) { throw 'purge command failed' }
