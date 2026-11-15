# Proves the installer plan never places a role password on the psql command line or in the SQL text,
# and that UTF-8 files are written without a BOM. Safe to run on PowerShell 5.1 and pwsh.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$quote = [char]39
$secret = 'p' + $quote + 'a"ss;w\$' + ' %L'
$plan = Get-PantryPsqlPlan -AdminUser 'postgres' -DatabaseUser 'pantry' -DatabaseName 'pantry' -RolePassword $secret -ResetPassword $true
if ($plan.Arguments.Contains($secret)) { throw 'role password leaked into psql arguments' }
if ($plan.Stdin.Contains($secret)) { throw 'role password leaked into psql stdin' }
if ($plan.Stdin -notmatch '\\getenv pantry_pass PANTRY_DB_PASSWORD') { throw 'stdin does not load the password from the child environment' }
if ($plan.Env['PANTRY_DB_PASSWORD'] -ne $secret) { throw 'child environment did not keep the password' }
if ($plan.Arguments -match 'PASSWORD') { throw 'password clause is on the psql command line' }

$dir = Join-Path ([System.IO.Path]::GetTempPath()) 'pantry-install-secret-test'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$utfPath = Join-Path $dir 'Caddyfile'
Write-PantryUtf8NoBom -Path $utfPath -Value "pantry`n中文"
$bytes = [System.IO.File]::ReadAllBytes($utfPath)
if ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) {
    throw 'UTF-8 BOM was written'
}
$text = [System.IO.File]::ReadAllText($utfPath, [System.Text.UTF8Encoding]::new($false))
if ($text -notmatch '中文') { throw 'UTF-8 text was not preserved' }

$install = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'install.ps1'))
if ($install -notmatch 'Assert-PantryPort -Port \$AcmeHttpPort') { throw 'ACME port is not guarded' }
if ($install -notmatch 'Test-ListenPortFree -Port \$AcmeHttpPort') { throw 'ACME port is not checked for listeners' }
if ($install -match 'Set-Content[^\r\n]*-Encoding utf8') { throw 'install.ps1 still writes UTF-8 with Set-Content' }
if ($install -match '\$env:PGPASSWORD\s*=') { throw 'install.ps1 assigns the session PGPASSWORD' }
if ($install -match "PASSWORD '") { throw 'install.ps1 still quotes a password into SQL' }

$refused = $false
try { Assert-PantryPort -Port 8080 } catch { $refused = $true }
if (-not $refused) { throw 'port 8080 was accepted' }
Assert-PantryPort -Port 80

$argvFile = Join-Path $dir 'argv.txt'
$fake = Join-Path $dir 'psql-fake.sh'
$nl = [Environment]::NewLine
$fakeBody = "#!/bin/sh$nl" + "cat >/dev/null$nl" + "printf '%s\n' " + '"' + '$*' + '"' + " > '$argvFile'$nl" + "exit 0$nl"
[System.IO.File]::WriteAllText($fake, $fakeBody)
if (Get-Command chmod -ErrorAction SilentlyContinue) { & chmod +x $fake }

Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:PANTRY_DB_PASSWORD -ErrorAction SilentlyContinue
Invoke-PantryPsql -PsqlPath $fake -AdminUser 'postgres' -AdminPassword $secret `
    -DatabaseUser 'pantry' -DatabaseName 'pantry' -RolePassword $secret -ResetPassword $true
if (Test-Path Env:PGPASSWORD) { throw 'PGPASSWORD remained in the session' }
if (Test-Path Env:PANTRY_DB_PASSWORD) { throw 'PANTRY_DB_PASSWORD remained in the session' }
if (Test-Path $argvFile) {
    $argv = [System.IO.File]::ReadAllText($argvFile)
    if ($argv.Contains($secret)) { throw 'fake psql argv contained the password' }
}

Write-Host 'install-secrets.test.ps1 ok'
