# get-tablet-url.ps1
# Liest die echte WebView-URL nach QR-Scan aus dem Tablet aus.
# Workflow:
#   1. Tablet zeigt https://www.med-mv.de/qr-code-scanner/
#   2. QR-Code vom Einrichtungsportal scannen
#   3. Website leitet weiter auf med.informme.info/tabletX?tabletLinkId=...
#   4. Dieses Script pollt alle 2s die URL bis die Weiterleitung erkannt wird

# API-Key aus der ENV (siehe .env.local + load-env.ps1). Wird automatisch geladen,
# wenn .env.local existiert und KIOSK_API_KEY noch nicht gesetzt ist.
if (-not $env:KIOSK_API_KEY) {
    $envFile = Join-Path $PSScriptRoot '.env.local'
    if (Test-Path $envFile) { . (Join-Path $PSScriptRoot 'load-env.ps1') | Out-Null }
}
if (-not $env:KIOSK_API_KEY -or $env:KIOSK_API_KEY -match '^\{\{') {
    throw "KIOSK_API_KEY ist nicht gesetzt. Vorlage kopieren (Copy-Item .env.example .env.local), Werte eintragen, dann . .\load-env.ps1"
}

$ApiKey = $env:KIOSK_API_KEY
$Port   = 8081  # ADB-Forward Port
$MaxAttempts = 30  # 30 x 2s = 60s max

# ADB-Forward sicherstellen
.\adb.exe forward tcp:$Port tcp:8080 | Out-Null

# logcat leeren
.\adb.exe shell "logcat -c" | Out-Null

Write-Host "Polling WebView URL (alle 2s, max 60s)..." -ForegroundColor Cyan
Write-Host "Scanne jetzt den QR-Code vom Einrichtungsportal." -ForegroundColor Yellow
Write-Host ""

$found = $null

for ($i = 0; $i -lt $MaxAttempts; $i++) {
    # JS injizieren — postMessage mit window.location.href
    $jsCode = 'window.ReactNativeWebView.postMessage("KIOSK_URL:" + window.location.href); true;'
    $body = @{ code = $jsCode; persistent = $false } | ConvertTo-Json -Compress
    $body | Out-File "$env:TEMP\inj.json" -Encoding ascii -NoNewline
    & curl.exe -s -X POST "http://localhost:$Port/api/inject-js" '-H' "X-Api-Key: $ApiKey" -H "Content-Type: application/json" -d "@$env:TEMP\inj.json" | Out-Null
    Remove-Item "$env:TEMP\inj.json" -ErrorAction SilentlyContinue

    Start-Sleep -Seconds 2

    # logcat nach KIOSK_URL durchsuchen
    $urlMatches = .\adb.exe shell "logcat -d" 2>&1 | Select-String "KIOSK_URL message"
    if ($urlMatches) {
        $latest = $urlMatches[-1].ToString()
        if ($latest -notmatch "qr-code-scanner") {
            # Echte URL gefunden
            $found = $latest -replace ".*KIOSK_URL:", "" -replace "[\r\n'""].*", "" -replace "\\/", "/"
            $found = $found.Trim()
            Write-Host "[$i] GEFUNDEN: $found" -ForegroundColor Green
            break
        } else {
            Write-Host "[$i] Noch auf QR-Scanner..." -ForegroundColor DarkGray
        }
    } else {
        Write-Host "[$i] Keine Antwort..." -ForegroundColor DarkGray
    }
}

if ($found) {
    Write-Host "`n=== Ergebnis ===" -ForegroundColor Cyan
    Write-Host "Tablet URL: $found" -ForegroundColor Green
    try { Set-Clipboard -Value $found } catch {}
    Write-Host "(URL in Zwischenablage kopiert)" -ForegroundColor DarkGray
} else {
    Write-Host "`nKeine Weiterleitung erkannt." -ForegroundColor Yellow
    Write-Host "Letzte REST currentUrl:" -ForegroundColor DarkGray
    $status = & curl.exe -s "http://localhost:$Port/api/status" '-H' "X-Api-Key: $ApiKey" | ConvertFrom-Json
    Write-Host "  $($status.data.webview.currentUrl)" -ForegroundColor DarkGray
}
