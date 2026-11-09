# load-env.ps1
# Loads .env.local into the current PowerShell session (Process scope only).
#
# Usage:
#   . .\load-env.ps1          # dot-source, so the variables stay in this session
#   . .\load-env.ps1 -File .env.example
#
# Requires .env.local (Copy-Item .env.example .env.local).
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less files as ANSI,
# so umlauts or typographic dashes would break parsing.

[CmdletBinding()]
param(
    [string]$File = ''
)

# $PSScriptRoot can be empty when dot-sourced from -Command, so fall back to the CWD
if (-not $File) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
    $File = Join-Path $scriptDir '.env.local'
}

if (-not (Test-Path $File)) {
    throw "ENV file not found: $File - copy the template first: Copy-Item .env.example .env.local"
}

$loaded = 0
$placeholders = @()

Get-Content -LiteralPath $File -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#')) { return }

    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { return }

    $name = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1).Trim()

    # Strip surrounding quotes (needed for values containing # or !)
    if ($value.Length -ge 2 -and (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    if ($value -match '^\{\{[A-Z0-9_]+\}\}$') { $placeholders += $name }

    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    $loaded++
}

Write-Host "ENV loaded: $File ($loaded variables)" -ForegroundColor Green
if ($placeholders.Count -gt 0) {
    Write-Host "WARNING: placeholders not replaced yet: $($placeholders -join ', ')" -ForegroundColor Yellow
}
