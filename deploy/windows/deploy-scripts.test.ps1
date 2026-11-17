# Plain checks for the deploy script fixes. No Pester. Safe on Windows PowerShell 5.1 and pwsh.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$existing = "PANTRY_DB_PASSWORD=keep-this`r`n# comment`r`nPANTRY_PROFILE=prod`r`nPANTRY_PHONE_ENC_KEY=`r`n"
$fill = @(
    @{ Key = 'PANTRY_DB_PASSWORD'; Value = 'replacement-value' },
    @{ Key = 'PANTRY_PROFILE'; Value = 'prod' },
    @{ Key = 'PANTRY_PHONE_ENC_KEY'; Value = 'filled-enc-key' },
    @{ Key = 'PANTRY_DATA_DIR'; Value = 'C:\pantry\data\photos' },
    @{ Key = 'AUTH_TEST_CODE'; Value = '' }
)
$merged = Merge-PantryEnvText -Existing $existing -Fill $fill
if ($merged -match 'replacement-value') { throw 'existing secret was overwritten' }
if ($merged -notmatch 'keep-this') { throw 'existing secret was dropped' }
if ($merged -notmatch 'PANTRY_PHONE_ENC_KEY=filled-enc-key') { throw 'empty secret was not completed' }
if ($merged -notmatch 'photos') { throw 'photos dir was not added' }
if ($merged -match 'caddy') { throw 'env merge pointed at caddy data' }
if ($merged -notmatch '# comment') { throw 'comment was dropped' }
$kept = Merge-PantryEnvText -Existing "AUTH_TEST_CODE=already-set`r`n" -Fill @(@{ Key = 'AUTH_TEST_CODE'; Value = '' })
if ($kept -notmatch 'AUTH_TEST_CODE=already-set') { throw 'existing auth code was cleared' }

$dataDir = Get-PantryDataDir -InstallRoot 'C:\pantry'
if ($dataDir -notmatch 'photos') { throw 'data dir is not photos' }
if ($dataDir -match 'caddy') { throw 'data dir includes caddy' }

$a = New-PantryCsprngText -Bytes 32 -Encoding base64
$b = New-PantryCsprngText -Bytes 32 -Encoding base64
if ($a -eq $b) { throw 'csprng repeated' }
if ($a -match '\s') { throw 'secret contains whitespace' }
$decoded = [Convert]::FromBase64String($a)
if ($decoded.Length -ne 32) { throw 'enc key is not 32 bytes' }
$hex = New-PantryCsprngText -Bytes 24 -Encoding hex
if ($hex.Length -ne 48) { throw 'hex secret length' }
if ($hex -notmatch '^[0-9a-f]+$') { throw 'hex secret alphabet' }

$template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Caddyfile.template'))
if ($template -match '(?m)^\s*email\b') { throw 'template has an email line' }
if ($template -notmatch 'disable_tlsalpn_challenge') { throw 'template missing tls-alpn disable' }
if ($template -notmatch 'profile shortlived') { throw 'template missing shortlived' }
$caddy = New-PantryCaddyfile -Template $template -PublicIp 'public-address' -PublicPort 9443 -ListenPort 18280 -AcmeEmail '' -WebRoot 'C:\pantry\web' -LogFile 'C:\pantry\logs\caddy.log' -AcmeHttpPort 80
if ($caddy -match '(?m)^\s*email\b') { throw 'empty email was written' }
if ($caddy -match '\\') { throw 'caddyfile kept a backslash' }
if ($caddy -notmatch 'C:/pantry/web') { throw 'web root is not forward slashes' }
if ($caddy -notmatch 'C:/pantry/logs/caddy.log') { throw 'caddy log path' }
if ($caddy -notmatch 'reverse_proxy 127.0.0.1:18280') { throw 'reverse proxy' }
if ($caddy -notmatch 'http://public-address:80') { throw 'http-01 port' }
if ($caddy -notmatch 'https://public-address:9443') { throw 'https port' }
if ($caddy -match 'PANTRY_') { throw 'placeholder left in caddyfile' }
if ((Get-PantryCaddyGlobalBlock -AcmeEmail '  ') -ne '') { throw 'blank email was kept' }
$rejectedEmail = $false
try { Get-PantryCaddyGlobalBlock -AcmeEmail "two words" } catch { $rejectedEmail = $true }
if (-not $rejectedEmail) { throw 'email with a space was accepted' }

$coveredText = "host all all 127.0.0.1/32 scram-sha-256`nhost pantry pantry 127.0.0.1/32 reject"
$covered = Get-PantryPgHbaLoopbackDecision -Text $coveredText
if (-not $covered.Covered) { throw 'top loopback scram line should cover pantry' }
$blockedText = "host all all 127.0.0.1/32 reject`nhost all all 127.0.0.1/32 scram-sha-256"
$blocked = Get-PantryPgHbaLoopbackDecision -Text $blockedText
if ($blocked.Covered) { throw 'an earlier reject must win' }
$specific = Get-PantryPgHbaLoopbackDecision -Text "host pantry pantry 127.0.0.1/32 scram-sha-256"
if (-not $specific.Covered) { throw 'specific pantry line should cover' }

$rules = @(Get-PantryFirewallPlan)
if ($rules.Count -ne 2) { throw 'expected two firewall rules' }
if ($rules[0].Name -ne 'pantry-caddy-9443' -or $rules[0].Port -ne 9443) { throw 'public firewall rule' }
if ($rules[1].Name -ne 'pantry-caddy-acme-80' -or $rules[1].Port -ne 80) { throw 'acme firewall rule' }

$sql = Get-PantryManualSql -DatabaseName 'pantry' -DatabaseUser 'pantry'
if ($sql -notmatch 'CONNECTION LIMIT 10') { throw 'manual sql lost connection limit' }
if ($sql -notmatch 'REVOKE CONNECT ON DATABASE pantry FROM PUBLIC') { throw 'manual sql lost revoke' }
if ($sql -notmatch "statement_timeout = '5s'") { throw 'manual sql lost statement timeout' }
if ($sql -notmatch '<password>') { throw 'manual sql should keep a placeholder' }
$plan = Get-PantryPsqlPlan -AdminUser 'postgres' -DatabaseUser 'pantry' -DatabaseName 'pantry' -RolePassword 'placeholder-not-printed' -ResetPassword $false
if ($plan.Stdin -notmatch 'CONNECTION LIMIT 10') { throw 'psql plan lost connection limit' }
if ($plan.Stdin -notmatch 'REVOKE CONNECT') { throw 'psql plan lost revoke' }
if ($plan.Stdin -notmatch 'statement_timeout') { throw 'psql plan lost timeout' }
if ($plan.Stdin -notmatch '\\getenv pantry_pass PANTRY_DB_PASSWORD') { throw 'psql plan does not use getenv' }
if ($plan.Arguments -match 'placeholder-not-printed') { throw 'plan arguments contain the role password' }

$names = @('pantry-20261118-020000.dump', 'pantry-20261117-020000.dump', 'pantry-20261116-020000.dump')
$drop = @(Get-PantryExpiredBackupNames -Names $names -Keep 2)
if ($drop.Count -ne 1 -or $drop[0] -ne 'pantry-20261116-020000.dump') { throw 'retention did not drop the oldest' }

$trustees = @(Get-PantryConfTrustees)
if ($trustees -notcontains 'S-1-5-32-544' -or $trustees -notcontains 'S-1-5-18') { throw 'conf ACL trustees' }
if ($trustees.Count -ne 2) { throw 'conf ACL has an extra trustee' }

$notAfter = [datetime]::Parse('2026-11-20T00:00:00Z', [cultureinfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)
$now = [datetime]::Parse('2026-11-17T00:00:00Z', [cultureinfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)
$days = Get-PantryCertDaysLeft -NotAfter $notAfter -Now $now
if ($days -ne 3) { throw "expected 3 days, got $days" }
if (Test-PantryCertWarning -DaysLeft 3) { throw '3 days should not warn' }
if (-not (Test-PantryCertWarning -DaysLeft 2)) { throw 'under 3 days should warn' }

$taskArg = Get-PantryBackupTaskArgument -ScriptPath 'C:\pantry\bin\backup.ps1' -InstallRoot 'C:\pantry' -PostgresBin 'C:\Program Files\PostgreSQL\16\bin' -Keep 14
if ($taskArg -notmatch 'backup.ps1') { throw 'task argument missing script' }
if ($taskArg -notmatch 'Program Files') { throw 'task argument dropped the postgres path' }
if ($taskArg -match 'PGPASSWORD') { throw 'task argument contains PGPASSWORD' }

$dir = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-jre-test-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $dir | Out-Null
try {
    $nested = Join-Path (Join-Path $dir 'src') 'jdk-21-jre'
    $bin = Join-Path $nested 'bin'
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $bin 'java.exe'), 'not-a-real-jre')
    $zip = Join-Path $dir 'temurin-21-jre.zip'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $dir 'src'), $zip)
    $dest = Join-Path $dir 'jre'
    Install-PantryJre -ZipPath $zip -Destination $dest
    $java = Join-Path (Join-Path $dest 'bin') 'java.exe'
    if (-not (Test-Path -LiteralPath $java)) { throw 'jre bin\java.exe missing' }
} finally {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}

function Read-Script([string]$Name) {
    return [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot $Name))
}
$install = Read-Script 'install.ps1'
$init = Read-Script 'init-db.ps1'
$backup = Read-Script 'backup.ps1'
$status = Read-Script 'status.ps1'
$register = Read-Script 'register-backup-task.ps1'
$unregister = Read-Script 'unregister-backup-task.ps1'
$serverXml = Read-Script 'pantry-server.xml.template'
$caddyXml = Read-Script 'pantry-caddy.xml.template'

if ($install -match 'DatabasePassword|PostgresAdminPassword') { throw 'install still takes a database password parameter' }
if ($install -match 'Invoke-PantryPsql') { throw 'install still creates the database' }
if ($install -match '(?m)(?i)^\s*(\.|\&).{0,120}register-backup-task') { throw 'install runs the backup task script' }
if ($install -notmatch 'Install-PantryJre') { throw 'install does not extract the JRE' }
if ($install -notmatch 'pantry-server.jar') { throw 'install does not copy the jar' }
if ($install -notmatch 'caddy.exe') { throw 'install does not copy caddy' }
if ($install -notmatch 'Protect-PantryTree') { throw 'install does not lock the private directories' }
if ($install -notmatch 'pantry-caddy-acme-80') { throw 'install missing the port 80 rule' }
if ($install -notmatch 'JRE 21') { throw 'install does not say JRE 21' }
if ($install -match '(?i)(Set-Content|Add-Content|Out-File|WriteAllText)[^\r\n]*pg_hba') { throw 'install writes pg_hba' }
if ($install -notmatch 'index.html') { throw 'install does not place a placeholder page' }

if ($init -notmatch 'Read-Host -AsSecureString') { throw 'init-db does not prompt' }
if ($init -notmatch 'Invoke-PantryPsql') { throw 'init-db does not use the psql plan' }
if ($init -notmatch 'PANTRY_DB_PASSWORD') { throw 'init-db does not read the role password from server.env' }
if ($init -match 'Write-Host[^\r\n]*\$(rolePassword|admin)\b') { throw 'init-db prints a password' }

if ($backup -notmatch '\$env:PGPASSWORD = \$dbPassword') { throw 'backup does not set PGPASSWORD' }
if ($backup -notmatch 'Remove-Item Env:PGPASSWORD') { throw 'backup does not clear PGPASSWORD' }
if ($backup -notmatch 'Get-PantryDataDir') { throw 'backup does not zip the photos dir' }
if ($backup -match 'data\\caddy') { throw 'backup mentions caddy data' }
if ($backup -notmatch '\$Keep = 14') { throw 'backup keep default' }

if ($status -notmatch 'PublicHealthUrl') { throw 'status has no public url parameter' }
if ($status -match 'https?://(?!127\.0\.0\.1)\d+\.') { throw 'status hardcodes a public address' }
if ($status -match 'Restart-Service|Stop-Service|netsh|Set-Content|Add-Content') { throw 'status is not read-only' }
if ($status -notmatch 'Test-PantryCertWarning') { throw 'status does not warn on the certificate' }
if ($status -notmatch 'last backup') { throw 'status does not report the backup' }

if ($register -notmatch 'SYSTEM') { throw 'backup task is not SYSTEM' }
if ($register -notmatch '-Daily') { throw 'backup task is not daily' }
if ($register -notmatch '\$At = ''02:00''') { throw 'backup task time is not a parameter' }
if ($unregister -notmatch 'pantry-backup') { throw 'unregister task name' }
foreach ($xml in @($serverXml, $caddyXml)) {
    if ($xml -notmatch '<logpath>__INSTALL_ROOT__\\logs</logpath>') { throw 'winsw logpath' }
    if ($xml -notmatch '<startmode>Automatic</startmode>') { throw 'winsw startmode' }
    if ($xml -notmatch 'onfailure action="restart" delay="10 sec"') { throw 'winsw onfailure' }
    if ($xml -notmatch 'PANTRY_ENV_FILE') { throw 'winsw env file' }
    if ($xml -notmatch '__INSTALL_ROOT__\\conf\\server.env') { throw 'winsw server.env path' }
}

$onceText = "PANTRY_PROFILE=prod`r`nPANTRY_DB_PASSWORD=keep-this`r`nPANTRY_PHONE_ENC_KEY=filled-enc-key`r`n"
$fillAgain = @(
    @{ Key = 'PANTRY_PROFILE'; Value = 'prod' },
    @{ Key = 'PANTRY_DB_PASSWORD'; Value = '' },
    @{ Key = 'PANTRY_PHONE_ENC_KEY'; Value = '' },
    @{ Key = 'PANTRY_DATA_DIR'; Value = 'C:\pantry\data\photos' }
)
$once = Merge-PantryEnvText -Existing $onceText -Fill $fillAgain
$twice = Merge-PantryEnvText -Existing $once -Fill $fillAgain
if ($twice -ne $once) { throw 'running the env merge twice changed the file' }
foreach ($keyName in @('PANTRY_PROFILE', 'PANTRY_DB_PASSWORD', 'PANTRY_PHONE_ENC_KEY', 'PANTRY_DATA_DIR')) {
    $count = 0
    foreach ($envLine in @(Split-PantryEnvLines -Text $twice)) {
        $trim = $envLine.Trim()
        $eq = $trim.IndexOf('=')
        if ($eq -gt 0 -and $trim.Substring(0, $eq).Trim() -eq $keyName) { $count++ }
    }
    if ($count -ne 1) { throw "key $keyName appears $count times" }
}
if ($twice -notmatch 'PANTRY_DB_PASSWORD=keep-this') { throw 'second merge cleared the password' }

$spaced = Get-PantryBackupTaskArgument -ScriptPath 'C:\pantry\bin\backup.ps1' -InstallRoot 'C:\pantry\' -PostgresBin 'C:\Program Files\PostgreSQL\16\bin\' -Keep 14
if ($spaced -match 'bin\\"') { throw 'a trailing backslash escapes the closing quote' }
if ($spaced -notmatch '"C:\\Program Files\\PostgreSQL\\16\\bin"') { throw 'postgres path with spaces was not quoted' }
if ($spaced -notmatch '-DatabaseName pantry -DatabaseUser pantry -Keep 14') { throw 'arguments after the postgres path were swallowed' }
$plain = Get-PantryBackupTaskArgument -ScriptPath 'C:\pantry\bin\backup.ps1' -InstallRoot 'C:\pantry' -PostgresBin 'C:\pg\bin\' -Keep 14
if ($plain -notmatch '-PostgresBin C:\\pg\\bin -DatabaseName') { throw 'trailing backslash was left on a path without spaces' }

$winswSpec = Get-PantryWinSwSpec
if ($winswSpec.Version -ne '2.12.0' -or $winswSpec.FileName -ne 'WinSW-x64.exe') { throw 'WinSW is not pinned to v2.12.0 x64' }
if ($install -match '\$exe status') { throw 'install still trusts the WinSW status exit code' }
if ($install -notmatch 'Invoke-PantryServiceInstall') { throw 'install does not use Get-Service to decide registration' }
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-winsw-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    $callLog = Join-Path $work 'calls.txt'
    $fake = Join-Path $work 'WinSW-x64.exe'
    $fakeBody = @"
#!/bin/sh
printf '%s\n' "`$*" >> '$callLog'
if [ "`$1" = status ]; then
  echo NonExistent
  exit 0
fi
exit 0
"@
    [System.IO.File]::WriteAllText($fake, $fakeBody.Replace("`r`n", "`n"))
    if (Get-Command chmod -ErrorAction SilentlyContinue) { & chmod +x $fake }
    & $fake status | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'fake WinSW status should exit 0' }
    Remove-Item -LiteralPath $callLog -Force
    & {
        function Get-Service { param($Name) return $null }
        Invoke-PantryServiceInstall -Exe $fake -Name 'pantry-server'
    }
    $calls = [System.IO.File]::ReadAllText($callLog)
    if ($calls -notmatch 'install') { throw 'NonExistent WinSW with exit 0 did not lead to install' }
    Remove-Item -LiteralPath $callLog -Force
    & {
        function Get-Service { param($Name) return [pscustomobject]@{ Status = 'Stopped' } }
        Invoke-PantryServiceInstall -Exe $fake -Name 'pantry-server'
    }
    if (Test-Path -LiteralPath $callLog) { throw 'install ran even though Get-Service found the service' }
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}

if ($status -notmatch 'Assert-PantryScope -Names \(@') { throw 'status.ps1 still passes + as a positional argument' }
$certDef = $status.IndexOf('function Show-PantryCertificate')
$certUse = $status.IndexOf('Show-PantryCertificate -Url')
if ($certDef -lt 0 -or $certUse -lt 0 -or $certDef -gt $certUse) { throw 'certificate function is used before it is defined' }
$statusRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-status-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $statusRoot | Out-Null
try {
    $statusOut = & {
        function Get-Service { param($Name) return $null }
        function Invoke-WebRequest { param($Uri, [switch]$UseBasicParsing, $TimeoutSec) throw 'down' }
        & (Join-Path $PSScriptRoot 'status.ps1') -InstallRoot $statusRoot -ServicePrefix 'pantry' -PublicHealthUrl 'https://127.0.0.1:9/healthz' *>&1 | Out-String
    }
    if ($statusOut -notmatch 'pantry-server missing') { throw "status did not list the service: $statusOut" }
    if ($statusOut -notmatch 'cert unreadable') { throw "status did not reach the certificate check: $statusOut" }
    if ($statusOut -notmatch 'last backup none') { throw "status stopped before the backup line: $statusOut" }
} finally {
    if (Test-Path -LiteralPath $statusRoot) { Remove-Item -LiteralPath $statusRoot -Recurse -Force }
}

$upgrade = Read-Script 'upgrade.ps1'
if ($upgrade -match 'LASTEXITCODE') { throw 'upgrade still treats LASTEXITCODE after backup.ps1 as the result' }
if ($upgrade -notmatch 'Invoke-PantryUpgrade') { throw 'upgrade does not use the guarded upgrade helper' }
if ($upgrade -notmatch 'HealthTimeoutSec = 90') { throw 'upgrade health timeout' }
if ($upgrade -notmatch 'forward-only') { throw 'upgrade does not say migrations are forward-only' }
$script:healthTries = 0
$healthy = Wait-PantryHealth -TimeoutSec 5 -IntervalSec 0 -Probe {
    $script:healthTries++
    return ($script:healthTries -ge 3)
}
if (-not $healthy) { throw 'health wait should succeed after retries' }
$gaveUp = Wait-PantryHealth -TimeoutSec 1 -IntervalSec 0 -Probe { return $false }
if ($gaveUp) { throw 'health wait should fail when the probe never succeeds' }

$protected = @(Get-PantryProtectedDirs -InstallRoot 'C:\pantry')
if ($protected.Count -ne 9) { throw "expected 9 protected directories, got $($protected.Count)" }
if ($protected -notcontains 'C:\pantry') { throw 'install root is not in the protected list' }
$protectedText = $protected -join "`n"
foreach ($part in @('\bin', '\jre', '\web', '\conf', '\backup', '\data\caddy', '\data\photos', '\logs')) {
    if ($protectedText -notmatch [regex]::Escape($part)) { throw "protected dirs missing $part" }
}
if ($install -notmatch 'Protect-PantryTree') { throw 'install does not tighten the private directories' }

if ($backup -notmatch 'pantry-env-') { throw 'backup does not copy server.env' }
if ($backup -notmatch 'Set-PantryConfAcl') { throw 'backup does not tighten the ACL on new backup files' }
if ($backup -notmatch 'Get-PantryBackupOffsiteReminder') { throw 'backup does not tell the operator to copy files off this disk' }
$envWork = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-envcopy-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $envWork | Out-Null
try {
    $envSrc = Join-Path $envWork 'server.env'
    $envDst = Join-Path $envWork 'pantry-env-stamp.env'
    [System.IO.File]::WriteAllText($envSrc, "PANTRY_DB_PASSWORD=placeholder-not-a-secret`r`n")
    Copy-Item -LiteralPath $envSrc -Destination $envDst -Force
    $copied = [System.IO.File]::ReadAllText($envDst)
    if ($copied -notmatch 'placeholder-not-a-secret') { throw 'env backup copy dropped the file' }
} finally {
    if (Test-Path -LiteralPath $envWork) { Remove-Item -LiteralPath $envWork -Recurse -Force }
}

$xmlOut = Expand-PantryWinSwXml -Template $serverXml -ServiceId 'pantry-server' -InstallRoot 'C:\pantry & co' -ListenPort '18280'
if ($xmlOut -notmatch '&amp;') { throw 'WinSW xml did not escape the install path' }
if ($xmlOut -match 'pantry & co') { throw 'WinSW xml kept a raw ampersand' }
if ($xmlOut -notmatch '"C:\\pantry &amp; co\\bin\\pantry-server.jar"') { throw 'jar path is not quoted' }
if ($caddyXml -notmatch '"__INSTALL_ROOT__\\conf\\Caddyfile"') { throw 'caddy config path is not quoted' }
$commonText = Read-Script 'common.ps1'
if ($commonText -notmatch 'Failed to add firewall rule') { throw 'firewall add does not check the exit code' }

$verifier = New-PantryScramVerifier -Password 'placeholder-not-printed'
if ($verifier -notmatch '^SCRAM-SHA-256\$4096:') { throw 'SCRAM verifier format' }
if ($verifier.Contains('placeholder-not-printed')) { throw 'SCRAM verifier contains the password' }
if ($init -notmatch 'New-PantryScramVerifier') { throw 'init-db sends the plaintext password to psql' }
$scramPlan = Get-PantryPsqlPlan -AdminUser 'postgres' -DatabaseUser 'pantry' -DatabaseName 'pantry' -RolePassword $verifier
if ($scramPlan.Stdin -notmatch "SET log_statement = 'none'") { throw 'psql session does not disable statement logging' }
if ($scramPlan.Stdin.Contains('placeholder-not-printed')) { throw 'plaintext password is in the psql script' }

$purge = Read-Script 'purge.ps1'
if ($purge -notmatch 'PANTRY_ENV_FILE') { throw 'purge does not point the jar at server.env' }
$uninstall = Read-Script 'uninstall.ps1'
if ($uninstall -notmatch 'ConfirmDropDatabase') { throw 'drop database has no explicit confirmation' }
if ($uninstall -notmatch 'backup.ps1') { throw 'drop database does not take a backup first' }
if ($uninstall -notmatch 'uninstall failed') { throw 'WinSW uninstall exit code is ignored' }

$stampA = New-PantryBackupStamp
$stampB = New-PantryBackupStamp
if ($stampA -eq $stampB) { throw 'backup stamps collided' }
if ($stampA -notmatch '^\d{8}-\d{9}-[0-9a-f]{8}$') { throw 'backup stamp shape' }
$keptNames = @(
    "pantry-$stampB.dump",
    'pantry-20261116-020000000-bbbbbbbb.dump',
    'pantry-20261115-020000000-cccccccc.dump'
)
$expired = @(Get-PantryExpiredBackupNames -Names $keptNames -Keep 2)
if ("$($expired[0])" -notmatch '20261115-020000000-cccccccc') { throw 'retention did not keep the newest stamped backups' }
$partialDir = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-partial-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $partialDir | Out-Null
try {
    $partials = @(
        (Join-Path $partialDir 'pantry-partial.dump'),
        (Join-Path $partialDir 'pantry-photos-partial.zip'),
        (Join-Path $partialDir 'pantry-env-partial.env')
    )
    foreach ($partial in $partials) { [System.IO.File]::WriteAllText($partial, 'partial') }
    Clear-PantryPartialBackup -Paths $partials
    foreach ($partial in $partials) {
        if (Test-Path -LiteralPath $partial) { throw 'partial backup file was left behind' }
    }
} finally {
    if (Test-Path -LiteralPath $partialDir) { Remove-Item -LiteralPath $partialDir -Recurse -Force }
}
if ($backup -notmatch 'Clear-PantryPartialBackup') { throw 'backup does not delete partial files' }

$upgradeWork = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry-upgrade-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $upgradeWork | Out-Null
try {
    $currentJar = Join-Path $upgradeWork 'pantry-server.jar'
    $newJar = Join-Path $upgradeWork 'incoming.jar'
    [System.IO.File]::WriteAllText($currentJar, 'old-jar')
    [System.IO.File]::WriteAllText($newJar, 'new-jar')
    $script:upgradeStarts = 0
    $failed = $false
    try {
        Invoke-PantryUpgrade -CurrentJar $currentJar -NewJar $newJar -PrevDirectory $upgradeWork `
            -Stop { } -Start { $script:upgradeStarts++ } -Backup { throw 'pg_dump failed' } -Health { return $true }
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch 'aborted because the backup failed') { throw }
        if ($_.Exception.Message -notmatch 'restarted on the old version') { throw }
    }
    if (-not $failed) { throw 'a failed backup did not abort the upgrade' }
    if ([System.IO.File]::ReadAllText($currentJar) -ne 'old-jar') { throw 'failed backup modified the jar' }
    if ($script:upgradeStarts -lt 1) { throw 'failed backup did not restart the service' }

    [System.IO.File]::WriteAllText($newJar, 'old-jar')
    $prevBefore = @(Get-ChildItem -LiteralPath $upgradeWork -Filter 'pantry-server.jar.prev-*')
    $skipped = Invoke-PantryUpgrade -CurrentJar $currentJar -NewJar $newJar -PrevDirectory $upgradeWork `
        -Stop { } -Start { $script:upgradeStarts++ } -Backup { } -Health { return $true }
    if ($skipped -ne 'skipped') { throw 'identical jar was not skipped' }
    $prevAfter = @(Get-ChildItem -LiteralPath $upgradeWork -Filter 'pantry-server.jar.prev-*')
    if ($prevAfter.Count -ne $prevBefore.Count) { throw 'identical jar overwrote the previous jar' }

    [System.IO.File]::WriteAllText($newJar, 'replacement-jar')
    $script:upgradeStartPhase = 'fail'
    $startsBeforeRollback = $script:upgradeStarts
    $rolled = $false
    try {
        Invoke-PantryUpgrade -CurrentJar $currentJar -NewJar $newJar -PrevDirectory $upgradeWork `
            -Stop { } `
            -Start {
                if ($script:upgradeStartPhase -eq 'fail') {
                    $script:upgradeStartPhase = 'ok'
                    throw 'Start-Service failed'
                }
                $script:upgradeStarts++
            } `
            -Backup { } -Health { return $true }
    } catch {
        $rolled = $true
        if ($_.Exception.Message -notmatch 'rolled back') { throw }
    }
    if (-not $rolled) { throw 'a failed start did not roll back' }
    if ([System.IO.File]::ReadAllText($currentJar) -ne 'old-jar') { throw 'failed start left the new jar in place' }
    if ($script:upgradeStarts -le $startsBeforeRollback) { throw 'failed start did not restart the service on the previous jar' }
} finally {
    if (Test-Path -LiteralPath $upgradeWork) { Remove-Item -LiteralPath $upgradeWork -Recurse -Force }
}

if ($register -notmatch 'pantry-purge') { throw 'register script does not register pantry-purge' }
if ($register -notmatch 'PurgeAt') { throw 'purge time is not a parameter' }
if ($unregister -notmatch 'pantry-purge') { throw 'unregister script leaves pantry-purge' }
if ($install -match '(?m)(?i)^\s*(\.|\&).{0,120}register-backup-task') { throw 'install registers scheduled tasks' }

Write-Host 'deploy-scripts.test.ps1 ok'
