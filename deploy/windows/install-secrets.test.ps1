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

$dir = Join-Path ([System.IO.Path]::GetTempPath()) ('pantry install secret ' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $dir | Out-Null
try {
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
$fake = Join-Path $dir 'psql.exe'
if ($env:OS -eq 'Windows_NT') {
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path -LiteralPath $csc)) {
        $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
    }
    if (-not (Test-Path -LiteralPath $csc)) {
        throw 'Windows test requires csc.exe to build a real psql.exe double. Refusing to pass with a script.'
    }
    $src = Join-Path $dir 'PsqlStub.cs'
    $cs = @'
using System;
using System.IO;
class PsqlStub {
    static int Main(string[] args) {
        string dir = Path.GetDirectoryName(System.Reflection.Assembly.GetExecutingAssembly().Location);
        string stdin = Console.In.ReadToEnd();
        File.WriteAllText(Path.Combine(dir, "argv.txt"), string.Join(" ", args));
        File.WriteAllText(Path.Combine(dir, "stdin.txt"), stdin);
        if (File.Exists(Path.Combine(dir, "fail-psql"))) return 4;
        return 0;
    }
}
'@
    [System.IO.File]::WriteAllText($src, $cs)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $csc
    $psi.Arguments = '/nologo /target:exe /out:"' + $fake + '" "' + $src + '"'
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.WaitForExit()
        if ($proc.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $fake)) {
        throw "csc.exe failed ($($proc.ExitCode)) while building the psql test double."
    }
    $magic = [System.IO.File]::ReadAllBytes($fake)
    if ($magic.Length -lt 2 -or $magic[0] -ne 0x4D -or $magic[1] -ne 0x5A) {
        throw 'Windows psql double is not a PE.'
    }
    $script:psqlPlatform = 'windows-csc'
} else {
    $sh = @'
#!/bin/sh
dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
printf '%s\n' "$*" > "$dir/argv.txt"
cat > "$dir/stdin.txt"
if [ -f "$dir/fail-psql" ]; then
  exit 4
fi
exit 0
'@
    [System.IO.File]::WriteAllText($fake, $sh.Replace("`r`n", "`n"))
    if (-not ([System.IO.File]::ReadAllText($fake).StartsWith('#!'))) {
        throw 'Linux psql double is missing a shebang.'
    }
    if (-not (Get-Command chmod -ErrorAction SilentlyContinue)) {
        throw 'Linux test requires chmod so the psql shebang can be executed. Refusing to pass without it.'
    }
    & chmod +x $fake
    if ($LASTEXITCODE -ne 0) { throw 'chmod failed for the psql test double.' }
    $script:psqlPlatform = 'linux-shebang'
}
Write-Host "psql double=$script:psqlPlatform"
function Invoke-ClosedStdinDouble {
    param([string]$File, [string]$Arguments)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $File
    $psi.Arguments = $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $started = [System.Diagnostics.Process]::Start($psi)
    $started.StandardInput.Close()
    [void]$started.StandardOutput.ReadToEnd()
    [void]$started.StandardError.ReadToEnd()
    $started.WaitForExit()
    return $started.ExitCode
}
$okCode = Invoke-ClosedStdinDouble -File $fake -Arguments '-h 127.0.0.1 -U postgres -d postgres -v ON_ERROR_STOP=1 -f -'
if ($okCode -ne 0) { throw "psql double success exit $okCode" }
if (-not (Test-Path -LiteralPath $argvFile)) { throw 'psql double did not record arguments' }
$recorded = [System.IO.File]::ReadAllText($argvFile).Trim()
if ($recorded -ne '-h 127.0.0.1 -U postgres -d postgres -v ON_ERROR_STOP=1 -f -') {
    throw 'psql double did not record the success arguments'
}
[System.IO.File]::WriteAllText((Join-Path $dir 'fail-psql'), '1')
$badCode = Invoke-ClosedStdinDouble -File $fake -Arguments '-h 127.0.0.1'
if ($badCode -ne 4) { throw "psql double fail exit $badCode" }
Remove-Item -LiteralPath (Join-Path $dir 'fail-psql') -Force
Remove-Item -LiteralPath $argvFile -Force -ErrorAction SilentlyContinue

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
$stdinFile = Join-Path $dir 'stdin.txt'
if (Test-Path -LiteralPath $stdinFile) {
    $stdinText = [System.IO.File]::ReadAllText($stdinFile)
    if ($stdinText.Contains($secret)) { throw 'fake psql stdin contained the password' }
    if ($stdinText -notmatch '\\getenv pantry_pass PANTRY_DB_PASSWORD') { throw 'fake psql did not receive the sql script' }
}
[System.IO.File]::WriteAllText((Join-Path $dir 'fail-psql'), '1')
$psqlFailed = $false
try {
    Invoke-PantryPsql -PsqlPath $fake -AdminUser 'postgres' -AdminPassword $secret `
        -DatabaseUser 'pantry' -DatabaseName 'pantry' -RolePassword $secret -ResetPassword $true | Out-Null
} catch {
    $psqlFailed = $true
    if ($_.Exception.Message -notmatch 'psql exited 4') { throw }
}
if (-not $psqlFailed) { throw 'non-zero psql was treated as success' }

if ([string]::IsNullOrWhiteSpace($script:psqlPlatform)) {
    throw 'psql double did not run; refusing to report success.'
}
Write-Host "install-secrets.test.ps1 ok (psql double=$script:psqlPlatform)"
} finally {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}
