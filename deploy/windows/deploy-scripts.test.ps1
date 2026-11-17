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
if ($install -notmatch 'Protect-PantryConf') { throw 'install does not lock the conf ACL' }
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

Write-Host 'deploy-scripts.test.ps1 ok'
