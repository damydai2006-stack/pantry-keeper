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
