# Ask pantry-server to delete families that have been empty for 30 days.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry'
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $ServicePrefix)
$java = Join-Path $InstallRoot 'jre\bin\java.exe'
$jar = Join-Path $InstallRoot 'bin\pantry-server.jar'
$envFile = Join-Path $InstallRoot 'conf\server.env'
if (-not (Test-Path -LiteralPath $java)) { throw "JRE missing: $java" }
if (-not (Test-Path -LiteralPath $envFile)) { throw "server.env not found: $envFile" }
$savedEnv = $env:PANTRY_ENV_FILE
$env:PANTRY_ENV_FILE = $envFile
try {
    & $java -Xmx256m -jar $jar purge
    if ($LASTEXITCODE -ne 0) { throw 'purge command failed' }
} finally {
    if ($null -eq $savedEnv) {
        Remove-Item Env:PANTRY_ENV_FILE -ErrorAction SilentlyContinue
    } else {
        $env:PANTRY_ENV_FILE = $savedEnv
    }
}
