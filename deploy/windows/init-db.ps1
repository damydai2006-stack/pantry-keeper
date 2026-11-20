# Optional. Create the pantry role and database. Not called by install.ps1.
# Prompts for the PostgreSQL superuser password. Reads PANTRY_DB_PASSWORD from
# server.env and passes it to psql with \getenv. Does not print either password,
# does not edit pg_hba.conf, and does not restart PostgreSQL.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$PostgresBin = '',
    [string]$PostgresAdminUser = 'postgres',
    [switch]$ResetPantryPassword
)

. "$PSScriptRoot\common.ps1"
Assert-PantryScope -Names @($InstallRoot, $DatabaseName, $DatabaseUser)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
if ([string]::IsNullOrWhiteSpace($PostgresBin)) {
    throw 'Pass -PostgresBin pointing at the existing psql.exe. This script does not install or restart PostgreSQL.'
}
$psql = Join-Path $PostgresBin 'psql.exe'
if (-not (Test-Path -LiteralPath $psql)) { throw "psql not found at $psql" }

$envPath = Join-Path $InstallRoot 'conf\server.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw "server.env not found: $envPath" }
$rolePassword = [string](Get-PantryEnvValue -Text ([System.IO.File]::ReadAllText($envPath)) -Key 'PANTRY_DB_PASSWORD' -Missing $null)
if ([string]::IsNullOrWhiteSpace($rolePassword)) { throw 'PANTRY_DB_PASSWORD is missing from server.env.' }

$secure = Read-Host -AsSecureString -Prompt 'PostgreSQL superuser password'
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$admin = $null
try {
    $admin = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    if ([string]::IsNullOrEmpty($admin)) { throw 'Superuser password was empty.' }
    $verifier = New-PantryScramVerifier -Password $rolePassword
    Invoke-PantryPsql -PsqlPath $psql -AdminUser $PostgresAdminUser -AdminPassword $admin `
        -DatabaseUser $DatabaseUser -DatabaseName $DatabaseName -RolePassword $verifier `
        -ResetPassword ([bool]$ResetPantryPassword) | Out-Null
} finally {
    if ($ptr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
    $admin = $null
    $rolePassword = $null
    $verifier = $null
}

Write-Host "pantry role and database are ready. PostgreSQL was not restarted. Passwords were not printed."
Write-Host 'If loopback login is not already covered, follow the pg_hba note in DEPLOY.md. This script does not edit pg_hba.conf.'
