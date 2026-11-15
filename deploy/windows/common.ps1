# Shared guards for pantry-* scripts. Never operate on ptt services, roles, or databases.
$ErrorActionPreference = 'Stop'

function Assert-PantryScope {
    param([Parameter(Mandatory = $true)][string[]]$Names)
    foreach ($n in $Names) {
        if ([string]::IsNullOrWhiteSpace($n)) {
            throw 'Refusing an empty name.'
        }
        if ($n -match '(?i)ptt') {
            throw 'Refusing to touch a name outside pantry scope.'
        }
        if ($n -notmatch '(?i)pantry') {
            throw 'Refusing a name that is not in the pantry scope.'
        }
    }
}

function Assert-SafeIdent {
    param([Parameter(Mandatory = $true)][string]$Name)
    if ($Name -notmatch '^[a-z][a-z0-9_]{0,30}$') {
        throw 'Database name or role must be a simple lowercase identifier.'
    }
    Assert-PantryScope -Names @($Name)
}

function Assert-PantryPort {
    param([Parameter(Mandatory = $true)][int]$Port)
    if ($Port -lt 1 -or $Port -gt 65535) { throw "Port $Port is invalid." }
    if ($Port -eq 8080 -or $Port -eq 18080 -or $Port -eq 18443 -or $Port -eq 8443) {
        throw "Port $Port belongs to the other product. Refusing."
    }
}

function Write-PantryUtf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value
    )
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $Value, $utf8)
}

function Get-PantryPsqlPlan {
    param(
        [Parameter(Mandatory = $true)][string]$AdminUser,
        [Parameter(Mandatory = $true)][string]$DatabaseUser,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [AllowEmptyString()][string]$RolePassword = '',
        [bool]$ResetPassword = $false
    )
    $stdin = @'
\getenv pantry_user PANTRY_DB_USER
\getenv pantry_pass PANTRY_DB_PASSWORD
\getenv pantry_db PANTRY_DB_NAME
\getenv pantry_reset PANTRY_RESET_PASSWORD
SELECT CASE
  WHEN NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'pantry_user')
    THEN format('CREATE ROLE %I LOGIN PASSWORD %L CONNECTION LIMIT 10', :'pantry_user', :'pantry_pass')
  WHEN :'pantry_reset' = '1'
    THEN format('ALTER ROLE %I PASSWORD %L', :'pantry_user', :'pantry_pass')
  ELSE 'SELECT 1'
END
\gexec
SELECT CASE
  WHEN NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'pantry_db')
    THEN format('CREATE DATABASE %I OWNER %I', :'pantry_db', :'pantry_user')
  ELSE 'SELECT 1'
END
\gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'pantry_db')
\gexec
SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'pantry_user', '5s')
\gexec
'@
    $argumentList = @('-h', '127.0.0.1', '-U', $AdminUser, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-f', '-')
    return @{
        ArgumentList = $argumentList
        Arguments     = ($argumentList -join ' ')
        Stdin         = $stdin
        Env           = @{
            PANTRY_DB_USER         = $DatabaseUser
            PANTRY_DB_PASSWORD     = $RolePassword
            PANTRY_DB_NAME         = $DatabaseName
            PANTRY_RESET_PASSWORD  = $(if ($ResetPassword) { '1' } else { '0' })
        }
    }
}

function Invoke-PantryPsql {
    param(
        [Parameter(Mandatory = $true)][string]$PsqlPath,
        [Parameter(Mandatory = $true)][string]$AdminUser,
        [AllowEmptyString()][string]$AdminPassword = '',
        [Parameter(Mandatory = $true)][string]$DatabaseUser,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [AllowEmptyString()][string]$RolePassword = '',
        [bool]$ResetPassword = $false
    )
    $savedPg = $env:PGPASSWORD
    $savedRole = $env:PANTRY_DB_PASSWORD
    try {
        $plan = Get-PantryPsqlPlan -AdminUser $AdminUser -DatabaseUser $DatabaseUser -DatabaseName $DatabaseName -RolePassword $RolePassword -ResetPassword $ResetPassword
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $PsqlPath
        $psi.Arguments = $plan.Arguments
        $psi.UseShellExecute = $false
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        foreach ($key in $plan.Env.Keys) {
            $psi.EnvironmentVariables[$key] = [string]$plan.Env[$key]
        }
        if (-not [string]::IsNullOrEmpty($AdminPassword)) {
            $psi.EnvironmentVariables['PGPASSWORD'] = $AdminPassword
        }
        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        [void]$proc.Start()
        $proc.StandardInput.Write($plan.Stdin)
        $proc.StandardInput.Close()
        $stdout = $proc.StandardOutput.ReadToEnd()
        $stderr = $proc.StandardError.ReadToEnd()
        $proc.WaitForExit()
        if ($proc.ExitCode -ne 0) {
            throw "psql exited $($proc.ExitCode). $stderr"
        }
        return $stdout
    } finally {
        if ($null -eq $savedPg) {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:PGPASSWORD = $savedPg
        }
        if ($null -eq $savedRole) {
            Remove-Item Env:PANTRY_DB_PASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:PANTRY_DB_PASSWORD = $savedRole
        }
    }
}
