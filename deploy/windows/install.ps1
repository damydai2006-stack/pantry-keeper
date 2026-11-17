# Install pantry-server and pantry-caddy.
# Extracts a Temurin 21 JRE, copies caddy.exe and the jar, and completes server.env.
# Does not create the database role, does not restart PostgreSQL, does not edit
# pg_hba.conf or the PostgreSQL server configuration, and does not register the
# backup task. Does not change ptt services, files, ports, or firewall rules.
param(
    [string]$InstallRoot = 'C:\pantry',
    [string]$ServicePrefix = 'pantry',
    [int]$ListenPort = 18280,
    [int]$PublicPort = 9443,
    [string]$DatabaseName = 'pantry',
    [string]$DatabaseUser = 'pantry',
    [string]$FirewallRuleName = 'pantry-caddy-9443',
    [string]$AcmeFirewallRuleName = 'pantry-caddy-acme-80',
    [switch]$SkipFirewall,
    [string]$OfflineDir = '',
    [string]$JreZip = '',
    [string]$CaddyExe = '',
    [string]$ServerJar = '',
    [string]$PublicIp = '',
    [string]$AcmeEmail = '',
    [int]$AcmeHttpPort = 80,
    [string]$PgHbaPath = ''
)

. "$PSScriptRoot\common.ps1"

Assert-PantryScope -Names @(
    $InstallRoot, $ServicePrefix, $FirewallRuleName, $AcmeFirewallRuleName,
    "$ServicePrefix-server", "$ServicePrefix-caddy", 'pantry-backup', 'pantry-purge'
)
Assert-SafeIdent -Name $DatabaseName
Assert-SafeIdent -Name $DatabaseUser
Assert-PantryPort -Port $ListenPort
Assert-PantryPort -Port $PublicPort
Assert-PantryPort -Port $AcmeHttpPort

$serverService = "$ServicePrefix-server"
$caddyService = "$ServicePrefix-caddy"
Assert-PantryScope -Names @($serverService, $caddyService)

function Show-ManualPgHbaInstruction {
    Write-Host 'MANUAL ONLY. This script does not edit pg_hba.conf, does not reload PostgreSQL, and does not restart PostgreSQL.'
    Write-Host 'PostgreSQL uses the first matching line. If the following line is already near the top, loopback login already covers pantry and no change is needed:'
    Write-Host 'host all all 127.0.0.1/32 scram-sha-256'
    Write-Host 'How to check: from the top, skip comments and blank lines. Find the first host, hostssl, or hostnossl line whose address is 127.0.0.1/32 and whose database and user are all, or the pantry database and role. If the method is scram-sha-256, md5, or password, stop. A later line does not override that match.'
    if (-not [string]::IsNullOrWhiteSpace($PgHbaPath)) {
        if ($PgHbaPath -match '(?i)ptt') { throw 'Refusing to read a path outside pantry scope.' }
        if (-not (Test-Path -LiteralPath $PgHbaPath)) { throw "pg_hba.conf not found: $PgHbaPath" }
        $hbaText = [System.IO.File]::ReadAllText($PgHbaPath)
        $decision = Get-PantryPgHbaLoopbackDecision -Text $hbaText -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser
        if ($decision.Covered) {
            Write-Host 'Read-only check: loopback password login already covers pantry. Do not edit pg_hba.conf.'
        } else {
            Write-Host 'Read-only check: loopback password login does not already cover pantry. No file was changed.'
            Write-Host ("If an operator chooses to append a line during a maintenance window: host {0} {1} 127.0.0.1/32 scram-sha-256" -f $DatabaseName, $DatabaseUser)
            Write-Host 'Then run SELECT pg_reload_conf(); do not restart PostgreSQL.'
        }
    } else {
        Write-Host ("If that covering line is absent, an operator may append: host {0} {1} 127.0.0.1/32 scram-sha-256" -f $DatabaseName, $DatabaseUser)
        Write-Host 'Then run SELECT pg_reload_conf(); do not restart PostgreSQL. Pass -PgHbaPath for a read-only decision. This script never writes pg_hba.conf.'
    }
}

function Test-ListenPortFree {
    param([int]$Port)
    $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listeners) {
        throw "Port $Port is already in use. Refusing to continue."
    }
}

Show-ManualPgHbaInstruction
Test-ListenPortFree -Port $ListenPort
Test-ListenPortFree -Port $PublicPort
Test-ListenPortFree -Port $AcmeHttpPort

if ([string]::IsNullOrWhiteSpace($PublicIp)) {
    throw 'Pass -PublicIp at install time. No address is stored in the repository.'
}

New-Item -ItemType Directory -Force -Path @(
    "$InstallRoot\bin", "$InstallRoot\conf", "$InstallRoot\data\photos", "$InstallRoot\data\caddy",
    "$InstallRoot\logs", "$InstallRoot\backup", "$InstallRoot\web"
) | Out-Null

$jreZipPath = Resolve-PantryOfflineFile -OfflineDir $OfflineDir -Explicit $JreZip -Label 'Temurin 21 JRE zip' -Match {
    $_.Extension -eq '.zip' -and $_.Name -match '(?i)(temurin|jre|jdk)'
}
$caddyPath = Resolve-PantryOfflineFile -OfflineDir $OfflineDir -Explicit $CaddyExe -Label 'caddy.exe' -Match {
    $_.Name -eq 'caddy.exe'
}
$jarPath = Resolve-PantryOfflineFile -OfflineDir $OfflineDir -Explicit $ServerJar -Label 'pantry-server.jar' -Match {
    $_.Name -eq 'pantry-server.jar'
}
Install-PantryJre -ZipPath $jreZipPath -Destination (Join-Path $InstallRoot 'jre')
Copy-Item -LiteralPath $caddyPath -Destination (Join-Path $InstallRoot 'bin\caddy.exe') -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $InstallRoot 'bin\pantry-server.jar') -Force

$envPath = Join-Path $InstallRoot 'conf\server.env'
$existingText = ''
if (Test-Path -LiteralPath $envPath) {
    $existingText = [System.IO.File]::ReadAllText($envPath)
}
$dbPassword = ''
$encKey = ''
$hmacKey = ''
if ([string]::IsNullOrWhiteSpace([string](Get-PantryEnvValue -Text $existingText -Key 'PANTRY_DB_PASSWORD' -Missing $null))) {
    $dbPassword = New-PantryCsprngText -Bytes 24 -Encoding hex
}
if ([string]::IsNullOrWhiteSpace([string](Get-PantryEnvValue -Text $existingText -Key 'PANTRY_PHONE_ENC_KEY' -Missing $null))) {
    $encKey = New-PantryCsprngText -Bytes 32 -Encoding base64
}
if ([string]::IsNullOrWhiteSpace([string](Get-PantryEnvValue -Text $existingText -Key 'PANTRY_PHONE_HMAC_KEY' -Missing $null))) {
    $hmacKey = New-PantryCsprngText -Bytes 32 -Encoding base64
}
$fill = Get-PantryEnvFill -InstallRoot $InstallRoot -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser -ListenPort $ListenPort -DbPassword $dbPassword -EncKey $encKey -HmacKey $hmacKey
$merged = Merge-PantryEnvText -Existing $existingText -Fill $fill
Write-PantryUtf8NoBom -Path $envPath -Value $merged

$caddyTemplate = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Caddyfile.template'))
$caddyText = New-PantryCaddyfile -Template $caddyTemplate -PublicIp $PublicIp -PublicPort $PublicPort -ListenPort $ListenPort -AcmeEmail $AcmeEmail -WebRoot (Join-Path $InstallRoot 'web') -LogFile (Join-Path $InstallRoot 'logs\caddy.log') -AcmeHttpPort $AcmeHttpPort
Write-PantryUtf8NoBom -Path (Join-Path $InstallRoot 'conf\Caddyfile') -Value $caddyText
foreach ($privateDir in @(Get-PantryProtectedDirs -InstallRoot $InstallRoot)) {
    if (-not (Test-Path -LiteralPath $privateDir)) {
        New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
    }
    Protect-PantryTree -Path $privateDir
}

$indexDest = Join-Path $InstallRoot 'web\index.html'
if (-not (Test-Path -LiteralPath $indexDest)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'index.html') -Destination $indexDest
}

function Install-PantryService {
    param([string]$Name, [string]$TemplateName)
    Assert-PantryScope -Names @($Name)
    $winswSpec = Get-PantryWinSwSpec
    $winsw = Resolve-PantryOfflineFile -OfflineDir $OfflineDir -Explicit '' -Label $winswSpec.FileName -Match { $_.Name -eq $winswSpec.FileName }
    $template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot $TemplateName))
    $xml = Expand-PantryWinSwXml -Template $template -ServiceId $Name -InstallRoot $InstallRoot -ListenPort "$ListenPort"
    $target = Join-Path $InstallRoot "bin\$Name.xml"
    Write-PantryUtf8NoBom -Path $target -Value $xml
    $exe = Join-Path $InstallRoot "bin\$Name.exe"
    Copy-Item -LiteralPath $winsw -Destination $exe -Force
    Invoke-PantryServiceInstall -Exe $exe -Name $Name
}

Install-PantryService -Name $serverService -TemplateName 'pantry-server.xml.template'
Install-PantryService -Name $caddyService -TemplateName 'pantry-caddy.xml.template'

if (-not $SkipFirewall) {
    $rules = Get-PantryFirewallPlan -PublicRule $FirewallRuleName -PublicPort $PublicPort -AcmeRule $AcmeFirewallRuleName -AcmePort $AcmeHttpPort
    foreach ($rule in $rules) {
        Add-PantryFirewallRule -Name $rule.Name -Port $rule.Port
    }
}

Write-Host "Installed $serverService and $caddyService under $InstallRoot."
Write-Host 'JDK/JRE 21. Photos are in data\photos (PANTRY_DATA_DIR). Secrets were written to conf\server.env and were not printed.'
Write-Host 'Database role creation is a separate step: run init-db.ps1, or apply the manual SQL in DEPLOY.md. PostgreSQL was not restarted.'
Write-Host 'The backup scheduled task is not registered by install. Run register-backup-task.ps1 yourself after the database exists.'
Write-Host 'AUTH_MODE stays disabled until an operator sets AUTH_MODE=test, a 6-digit AUTH_TEST_CODE, and AUTH_TEST_PHONES, then restarts pantry-server.'
