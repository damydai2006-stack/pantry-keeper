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
SET log_statement = 'none';
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

function Convert-PantryForwardSlash {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Path)
    return ($Path -replace '\\', '/')
}

function Get-PantryDataDir {
    param([Parameter(Mandatory = $true)][string]$InstallRoot)
    $root = $InstallRoot.TrimEnd('\', '/')
    # Windows paths are concatenated. Join-Path would look up drive C: on non-Windows hosts.
    if ($root -match '^[A-Za-z]:') { return ($root + '\data\photos') }
    return (Join-Path (Join-Path $root 'data') 'photos')
}

function Get-PantryConfTrustees {
    # Language-independent SIDs: BUILTIN\Administrators and NT AUTHORITY\SYSTEM.
    return @('S-1-5-32-544', 'S-1-5-18')
}

function Set-PantryConfAcl {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($env:OS -ne 'Windows_NT') { throw 'Set-PantryConfAcl only runs on Windows.' }
    if (-not (Test-Path -LiteralPath $Path)) { throw "Path not found: $Path" }
    $isContainer = (Get-Item -LiteralPath $Path).PSIsContainer
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) {
        [void]$acl.RemoveAccessRule($rule)
    }
    $rights = [System.Security.AccessControl.FileSystemRights]::FullControl
    $allow = [System.Security.AccessControl.AccessControlType]::Allow
    if ($isContainer) {
        $inherit = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        $propagation = [System.Security.AccessControl.PropagationFlags]::None
    } else {
        $inherit = [System.Security.AccessControl.InheritanceFlags]::None
        $propagation = [System.Security.AccessControl.PropagationFlags]::None
    }
    foreach ($sidText in (Get-PantryConfTrustees)) {
        $sid = New-Object System.Security.Principal.SecurityIdentifier($sidText)
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, $rights, $inherit, $propagation, $allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Protect-PantryConf {
    param([Parameter(Mandatory = $true)][string]$ConfDir)
    Set-PantryConfAcl -Path $ConfDir
    Get-ChildItem -LiteralPath $ConfDir -Force -File | ForEach-Object {
        Set-PantryConfAcl -Path $_.FullName
    }
}

function New-PantryCsprngText {
    param(
        [int]$Bytes = 32,
        [ValidateSet('hex', 'base64')][string]$Encoding = 'base64'
    )
    if ($Bytes -lt 16) { throw 'Refusing a short secret.' }
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $buf = New-Object byte[] $Bytes
        $rng.GetBytes($buf)
    } finally {
        $rng.Dispose()
    }
    if ($Encoding -eq 'hex') {
        $sb = New-Object System.Text.StringBuilder
        foreach ($b in $buf) { [void]$sb.AppendFormat('{0:x2}', $b) }
        return $sb.ToString()
    }
    return [Convert]::ToBase64String($buf)
}

function New-PantryScramVerifier {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Password,
        [int]$Iterations = 4096
    )
    if ($Iterations -lt 1) { throw 'SCRAM iteration count must be positive.' }
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $salt = New-Object byte[] 16
        $rng.GetBytes($salt)
    } finally {
        $rng.Dispose()
    }
    $kdf = New-Object System.Security.Cryptography.Rfc2898DeriveBytes(
        ([System.Text.Encoding]::UTF8.GetBytes($Password)),
        $salt,
        $Iterations,
        [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        $salted = $kdf.GetBytes(32)
    } finally {
        $kdf.Dispose()
    }
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = $salted
        $clientKey = $hmac.ComputeHash([System.Text.Encoding]::ASCII.GetBytes('Client Key'))
        $serverKey = $hmac.ComputeHash([System.Text.Encoding]::ASCII.GetBytes('Server Key'))
    } finally {
        $hmac.Dispose()
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $storedKey = $sha.ComputeHash($clientKey)
    } finally {
        $sha.Dispose()
    }
    return ('SCRAM-SHA-256${0}:{1}${2}:{3}' -f $Iterations, [Convert]::ToBase64String($salt), [Convert]::ToBase64String($storedKey), [Convert]::ToBase64String($serverKey))
}

function Split-PantryEnvLines {
    param([AllowEmptyString()][string]$Text = '')
    $lines = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrEmpty($Text)) { return ,@() }
    $start = 0
    $n = $Text.Length
    for ($i = 0; $i -lt $n; $i++) {
        $ch = $Text[$i]
        $isCr = ($ch -eq [char]13)
        $isLf = ($ch -eq [char]10)
        if (-not $isCr -and -not $isLf) { continue }
        $len = $i - $start
        if ($len -le 0) { [void]$lines.Add('') } else { [void]$lines.Add($Text.Substring($start, $len)) }
        if ($isCr -and ($i + 1) -lt $n -and $Text[$i + 1] -eq [char]10) { $i++ }
        $start = $i + 1
    }
    if ($start -lt $n) { [void]$lines.Add($Text.Substring($start)) }
    foreach ($line in $lines) { Write-Output $line }
}

function Test-PantryEnvHasKey {
    param(
        [AllowEmptyString()][string]$Text = '',
        [Parameter(Mandatory = $true)][string]$Key
    )
    return $null -ne (Get-PantryEnvValue -Text $Text -Key $Key -Missing $null)
}

function Get-PantryEnvValue {
    param(
        [AllowEmptyString()][string]$Text = '',
        [Parameter(Mandatory = $true)][string]$Key,
        [object]$Missing = ''
    )
    if ([string]::IsNullOrEmpty($Text)) { return $Missing }
    foreach ($raw in @(Split-PantryEnvLines -Text $Text)) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -le 0) { continue }
        $name = $line.Substring(0, $eq).Trim()
        if ($name -eq $Key) { return $line.Substring($eq + 1).Trim() }
    }
    return $Missing
}

function Merge-PantryEnvText {
    param(
        [AllowEmptyString()][string]$Existing = '',
        [Parameter(Mandatory = $true)][object[]]$Fill
    )
    $seen = @{}
    $lines = @()
    if (-not [string]::IsNullOrEmpty($Existing)) {
        foreach ($raw in @(Split-PantryEnvLines -Text $Existing)) {
            $trim = $raw.Trim()
            if ($trim -ne '' -and -not $trim.StartsWith('#')) {
                $eq = $trim.IndexOf('=')
                if ($eq -gt 0) {
                    $name = $trim.Substring(0, $eq).Trim()
                    $seen[$name] = $trim.Substring($eq + 1).Trim()
                }
            }
            $lines += $raw
        }
    }
    $added = @()
    foreach ($pair in $Fill) {
        $key = [string]$pair.Key
        $value = [string]$pair.Value
        if ([string]::IsNullOrWhiteSpace($key)) { throw 'Refusing an empty env key.' }
        if ($seen.ContainsKey($key)) {
            $current = [string]$seen[$key]
            if (-not [string]::IsNullOrWhiteSpace($current)) { continue }
            if ([string]::IsNullOrWhiteSpace($value)) { continue }
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $trim = $lines[$i].Trim()
                if ($trim -eq '' -or $trim.StartsWith('#')) { continue }
                $eq = $trim.IndexOf('=')
                if ($eq -le 0) { continue }
                if ($trim.Substring(0, $eq).Trim() -eq $key) {
                    $lines[$i] = '{0}={1}' -f $key, $value
                    $seen[$key] = $value
                    break
                }
            }
            continue
        }
        $added += ('{0}={1}' -f $key, $value)
        $seen[$key] = $value
    }
    if ($added.Count -eq 0) {
        return ($lines -join "`r`n")
    }
    if ($lines.Count -gt 0 -and $lines[-1] -ne '') { $lines += '' }
    $lines += $added
    return ($lines -join "`r`n")
}

function Get-PantryEnvFill {
    param(
        [Parameter(Mandatory = $true)][string]$InstallRoot,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$DatabaseUser,
        [Parameter(Mandatory = $true)][int]$ListenPort,
        [AllowEmptyString()][string]$DbPassword = '',
        [AllowEmptyString()][string]$EncKey = '',
        [AllowEmptyString()][string]$HmacKey = ''
    )
    Assert-SafeIdent -Name $DatabaseName
    Assert-SafeIdent -Name $DatabaseUser
    Assert-PantryPort -Port $ListenPort
    $dataDir = Get-PantryDataDir -InstallRoot $InstallRoot
    return @(
        @{ Key = 'PANTRY_PROFILE'; Value = 'prod' },
        @{ Key = 'PANTRY_TEST_CLOCK'; Value = '' },
        @{ Key = 'PANTRY_TEST_TOKEN'; Value = '' },
        @{ Key = 'PANTRY_DB_URL'; Value = "jdbc:postgresql://127.0.0.1:5432/$DatabaseName" },
        @{ Key = 'PANTRY_DB_USER'; Value = $DatabaseUser },
        @{ Key = 'PANTRY_DB_PASSWORD'; Value = $DbPassword },
        @{ Key = 'PANTRY_PHONE_ENC_KEY'; Value = $EncKey },
        @{ Key = 'PANTRY_PHONE_HMAC_KEY'; Value = $HmacKey },
        @{ Key = 'AUTH_MODE'; Value = 'disabled' },
        @{ Key = 'AUTH_TEST_CODE'; Value = '' },
        @{ Key = 'AUTH_TEST_PHONES'; Value = '' },
        @{ Key = 'PANTRY_FAMILY_PHOTO_MB'; Value = '1024' },
        @{ Key = 'PANTRY_MIN_FREE_MB'; Value = '1500' },
        @{ Key = 'PANTRY_LISTEN_HOST'; Value = '127.0.0.1' },
        @{ Key = 'PANTRY_LISTEN_PORT'; Value = "$ListenPort" },
        @{ Key = 'PANTRY_COOKIE_SECURE'; Value = 'true' },
        @{ Key = 'PANTRY_DATA_DIR'; Value = $dataDir },
        @{ Key = 'PANTRY_PHOTO_MAX_BYTES'; Value = '8388608' },
        @{ Key = 'PANTRY_OFF_BASE_URL'; Value = 'https://world.openfoodfacts.org' },
        @{ Key = 'PANTRY_OFF_USER_AGENT'; Value = 'pantry-keeper/0.1 (family expiry tracker)' },
        @{ Key = 'PANTRY_OFF_TIMEOUT_MS'; Value = '3000' }
    )
}

function Get-PantryCaddyGlobalBlock {
    param([AllowEmptyString()][string]$AcmeEmail = '')
    if ([string]::IsNullOrWhiteSpace($AcmeEmail)) { return '' }
    if ($AcmeEmail -match '[\s"{}\r\n]' -or $AcmeEmail -notmatch '@') {
        throw 'ACME email must be a single address, or omit it.'
    }
    return "{`r`n`temail $AcmeEmail`r`n}`r`n"
}

function New-PantryCaddyfile {
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][string]$PublicIp,
        [Parameter(Mandatory = $true)][int]$PublicPort,
        [Parameter(Mandatory = $true)][int]$ListenPort,
        [AllowEmptyString()][string]$AcmeEmail = '',
        [Parameter(Mandatory = $true)][string]$WebRoot,
        [Parameter(Mandatory = $true)][string]$LogFile,
        [Parameter(Mandatory = $true)][int]$AcmeHttpPort
    )
    Assert-PantryPort -Port $PublicPort
    Assert-PantryPort -Port $ListenPort
    Assert-PantryPort -Port $AcmeHttpPort
    if ($PublicIp -match '[\s\r\n{}]') { throw 'Public address must be a single token.' }
    $global = Get-PantryCaddyGlobalBlock -AcmeEmail $AcmeEmail
    $web = Convert-PantryForwardSlash -Path $WebRoot
    $log = Convert-PantryForwardSlash -Path $LogFile
    $text = $Template.Replace('PANTRY_GLOBAL_BLOCK', $global.TrimEnd())
    $text = $text.Replace('PANTRY_PUBLIC_IP', $PublicIp)
    $text = $text.Replace('PANTRY_PUBLIC_PORT', "$PublicPort")
    $text = $text.Replace('PANTRY_LISTEN_PORT', "$ListenPort")
    $text = $text.Replace('PANTRY_WEB_ROOT', $web)
    $text = $text.Replace('PANTRY_LOG_FILE', $log)
    $text = $text.Replace('PANTRY_ACME_HTTP_PORT', "$AcmeHttpPort")
    if ([string]::IsNullOrWhiteSpace($AcmeEmail) -and $text -match '(?m)^\s*email\b') {
        throw 'Caddyfile still contains an email line.'
    }
    if ($text -match '\\') { throw 'Caddyfile still contains a backslash.' }
    return $text.TrimEnd() + "`r`n"
}

function Get-PantryPgHbaLoopbackDecision {
    param(
        [AllowEmptyString()][string]$Text = '',
        [string]$DatabaseName = 'pantry',
        [string]$DatabaseUser = 'pantry'
    )
    Assert-SafeIdent -Name $DatabaseName
    Assert-SafeIdent -Name $DatabaseUser
    $passwordMethods = @('scram-sha-256', 'md5', 'password')
    foreach ($raw in ($Text -split "`r?`n")) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $parts = $line -split '\s+'
        if ($parts.Count -lt 5) { continue }
        $type = $parts[0].ToLowerInvariant()
        if ($type -ne 'host' -and $type -ne 'hostssl' -and $type -ne 'hostnossl') { continue }
        $db = $parts[1]
        $user = $parts[2]
        $addr = $parts[3]
        $method = $parts[4].ToLowerInvariant()
        if ($addr -ne '127.0.0.1/32' -and $addr -ne '127.0.0.1') { continue }
        $dbNames = $db -split ','
        $userNames = $user -split ','
        $dbMatch = ($db -eq 'all') -or ($dbNames -contains $DatabaseName)
        $userMatch = ($user -eq 'all') -or ($userNames -contains $DatabaseUser)
        if ($dbMatch -and $userMatch) {
            $covered = $passwordMethods -contains $method
            return @{ Covered = $covered; Line = $line; Method = $method }
        }
    }
    return @{ Covered = $false; Line = ''; Method = '' }
}

function Get-PantryFirewallPlan {
    param(
        [string]$PublicRule = 'pantry-caddy-9443',
        [int]$PublicPort = 9443,
        [string]$AcmeRule = 'pantry-caddy-acme-80',
        [int]$AcmePort = 80
    )
    Assert-PantryScope -Names @($PublicRule, $AcmeRule)
    Assert-PantryPort -Port $PublicPort
    Assert-PantryPort -Port $AcmePort
    return @(
        @{ Name = $PublicRule; Port = $PublicPort },
        @{ Name = $AcmeRule; Port = $AcmePort }
    )
}

function Get-PantryManualSql {
    param(
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$DatabaseUser
    )
    Assert-SafeIdent -Name $DatabaseName
    Assert-SafeIdent -Name $DatabaseUser
    return @"
CREATE ROLE $DatabaseUser LOGIN PASSWORD '<password>' CONNECTION LIMIT 10;
CREATE DATABASE $DatabaseName OWNER $DatabaseUser;
REVOKE CONNECT ON DATABASE $DatabaseName FROM PUBLIC;
ALTER ROLE $DatabaseUser SET statement_timeout = '5s';
"@
}

function Install-PantryJre {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $ZipPath)) { throw "JRE zip not found: $ZipPath" }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-jre-' + [guid]::NewGuid().ToString('n'))
        New-Item -ItemType Directory -Force -Path $temp | Out-Null
        try {
            [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $temp)
        $java = Get-ChildItem -LiteralPath $temp -Recurse -Filter 'java.exe' -File |
            Where-Object { $_.Directory.Name -eq 'bin' } |
            Select-Object -First 1
        if (-not $java) { throw 'Temurin 21 JRE zip does not contain bin\java.exe. JDK/JRE 21 is required.' }
        $sourceRoot = $java.Directory.Parent.FullName
        if (Test-Path -LiteralPath $Destination) {
            Remove-Item -LiteralPath $Destination -Recurse -Force
        }
        $parent = Split-Path -Parent $Destination
        if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
        if ($sourceRoot -eq $temp) {
            New-Item -ItemType Directory -Force -Path $Destination | Out-Null
            Get-ChildItem -LiteralPath $temp -Force | ForEach-Object {
                Move-Item -LiteralPath $_.FullName -Destination $Destination
            }
        } else {
            Move-Item -LiteralPath $sourceRoot -Destination $Destination
        }
    } finally {
        if (Test-Path -LiteralPath $temp) {
            Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    $installed = Join-Path (Join-Path $Destination 'bin') 'java.exe'
    if (-not (Test-Path -LiteralPath $installed)) { throw "JRE 21 was not installed at $installed" }
}

function Resolve-PantryOfflineFile {
    param(
        [string]$OfflineDir = '',
        [string]$Explicit = '',
        [Parameter(Mandatory = $true)][string]$Label,
        [scriptblock]$Match
    )
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "$Label not found: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    if ([string]::IsNullOrWhiteSpace($OfflineDir)) { throw "Pass -OfflineDir or an explicit path for $Label." }
    if (-not (Test-Path -LiteralPath $OfflineDir)) { throw "OfflineDir not found: $OfflineDir" }
    $hits = @(Get-ChildItem -LiteralPath $OfflineDir -File | Where-Object $Match)
    if ($hits.Count -ne 1) {
        throw "Expected one $Label in $OfflineDir, found $($hits.Count)."
    }
    return $hits[0].FullName
}

function Get-PantryExpiredBackupNames {
    param(
        [string[]]$Names = @(),
        [int]$Keep = 14
    )
    if ($Keep -lt 1) { throw 'Keep must be at least 1.' }
    $sorted = @($Names | Sort-Object -Descending)
    if ($sorted.Count -le $Keep) { return ,@() }
    $rest = @($sorted | Select-Object -Skip $Keep)
    return ,$rest
}

function Get-PantryCertDaysLeft {
    param(
        [Parameter(Mandatory = $true)][datetime]$NotAfter,
        [Parameter(Mandatory = $true)][datetime]$Now
    )
    $end = $NotAfter.ToUniversalTime()
    $start = $Now.ToUniversalTime()
    return [math]::Floor(($end - $start).TotalDays)
}

function Test-PantryCertWarning {
    param([Parameter(Mandatory = $true)][int]$DaysLeft)
    return $DaysLeft -lt 3
}

function Format-PantryPsArg {
    param([AllowEmptyString()][string]$Text = '')
    if ($Text -notmatch '[\s"]') { return $Text }
    # CommandLineToArgvW: backslashes that precede a quote are escaped, including the closing quote.
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $slashes = 0
    foreach ($ch in $Text.ToCharArray()) {
        if ($ch -eq '\') { $slashes++; continue }
        if ($ch -eq '"') {
            for ($i = 0; $i -lt (($slashes * 2) + 1); $i++) { [void]$sb.Append('\') }
            [void]$sb.Append('"')
            $slashes = 0
            continue
        }
        for ($i = 0; $i -lt $slashes; $i++) { [void]$sb.Append('\') }
        $slashes = 0
        [void]$sb.Append($ch)
    }
    for ($i = 0; $i -lt ($slashes * 2); $i++) { [void]$sb.Append('\') }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Trim-PantryPath {
    param([AllowEmptyString()][string]$Path = '')
    return $Path.TrimEnd('\')
}

function Get-PantryBackupTaskArgument {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string]$InstallRoot,
        [Parameter(Mandatory = $true)][string]$PostgresBin,
        [string]$DatabaseName = 'pantry',
        [string]$DatabaseUser = 'pantry',
        [int]$Keep = 14
    )
    Assert-SafeIdent -Name $DatabaseName
    Assert-SafeIdent -Name $DatabaseUser
    if ($Keep -lt 1) { throw 'Keep must be at least 1.' }
    $ScriptPath = Trim-PantryPath -Path $ScriptPath
    $InstallRoot = Trim-PantryPath -Path $InstallRoot
    $PostgresBin = Trim-PantryPath -Path $PostgresBin
    $parts = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', (Format-PantryPsArg $ScriptPath),
        '-InstallRoot', (Format-PantryPsArg $InstallRoot),
        '-PostgresBin', (Format-PantryPsArg $PostgresBin),
        '-DatabaseName', $DatabaseName,
        '-DatabaseUser', $DatabaseUser,
        '-Keep', "$Keep"
    )
    $joined = $parts -join ' '
    if ($joined -match '(?i)PGPASSWORD|PANTRY_DB_PASSWORD=') { throw 'Backup task argument must not carry a password.' }
    return $joined
}

function Get-PantryWinSwSpec {
    # v2.12.0 release assets do not publish a SHA256. Do not invent one.
    return @{ Version = '2.12.0'; FileName = 'WinSW-x64.exe' }
}

function Convert-PantryXmlText {
    param([AllowEmptyString()][string]$Text = '')
    $escaped = [System.Security.SecurityElement]::Escape($Text)
    if ($null -eq $escaped) { return '' }
    return $escaped
}

function Expand-PantryWinSwXml {
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][string]$ServiceId,
        [Parameter(Mandatory = $true)][string]$InstallRoot,
        [Parameter(Mandatory = $true)][string]$ListenPort
    )
    $xml = $Template.Replace('__SERVICE_ID__', (Convert-PantryXmlText $ServiceId))
    $xml = $xml.Replace('__INSTALL_ROOT__', (Convert-PantryXmlText $InstallRoot))
    $xml = $xml.Replace('__LISTEN_PORT__', (Convert-PantryXmlText $ListenPort))
    return $xml
}

function Test-PantryWindowsService {
    param([Parameter(Mandatory = $true)][string]$Name)
    Assert-PantryScope -Names @($Name)
    $row = Get-Service -Name $Name -ErrorAction SilentlyContinue
    return ($null -ne $row)
}

function Invoke-PantryServiceInstall {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [Parameter(Mandatory = $true)][string]$Name
    )
    Assert-PantryScope -Names @($Name)
    # WinSW v2 status exits 0 for NonExistent. Never use that exit code.
    if (Test-PantryWindowsService -Name $Name) {
        Write-Host "$Name is already installed; leaving the service registration in place."
        return
    }
    & $Exe install
    if ($LASTEXITCODE -ne 0) { throw "$Name install failed." }
}

function Add-PantryFirewallRule {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Port
    )
    Assert-PantryScope -Names @($Name)
    Assert-PantryPort -Port $Port
    # Deleting a missing rule is allowed to fail. Adding the rule is not.
    netsh advfirewall firewall delete rule name="$Name" | Out-Null
    netsh advfirewall firewall add rule name="$Name" dir=in action=allow protocol=TCP localport=$Port | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to add firewall rule $Name." }
}

function Wait-PantryHealth {
    param(
        [int]$TimeoutSec = 90,
        [int]$IntervalSec = 2,
        [Parameter(Mandatory = $true)][scriptblock]$Probe
    )
    if ($TimeoutSec -lt 1) { throw 'Health timeout must be at least 1 second.' }
    if ($IntervalSec -lt 0) { throw 'Health interval cannot be negative.' }
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSec)
    while ($true) {
        if ((& $Probe)) { return $true }
        if ([datetime]::UtcNow -ge $deadline) { return $false }
        if ($IntervalSec -gt 0) { Start-Sleep -Seconds $IntervalSec }
    }
}

function Get-PantryProtectedDirs {
    param([Parameter(Mandatory = $true)][string]$InstallRoot)
    $root = $InstallRoot.TrimEnd('\', '/')
    $names = @('conf', 'backup', 'data\caddy', 'data\photos', 'logs')
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($name in $names) {
        if ($root -match '^[A-Za-z]:') {
            [void]$out.Add($root + '\' + $name)
        } else {
            $child = $name -replace '\\', [System.IO.Path]::DirectorySeparatorChar
            [void]$out.Add((Join-Path $root $child))
        }
    }
    foreach ($dir in $out) { Write-Output $dir }
}

function Protect-PantryTree {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($env:OS -ne 'Windows_NT') { return }
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Set-PantryConfAcl -Path $Path
    Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        Set-PantryConfAcl -Path $_.FullName
    }
}

function Get-PantryBackupOffsiteReminder {
    return 'Copy these backups to another disk or another machine. A backup that stays on this disk is lost with the disk.'
}
