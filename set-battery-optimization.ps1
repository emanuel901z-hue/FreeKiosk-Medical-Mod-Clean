# set-battery-optimization.ps1
# Aktiviert ZUI Battery-Optimierung auf einem verbundenen Lenovo Tablet:
# - Schutz beim Aufladen (40-60% Range)
# - Ladungsoptimierung (Maintenance V1)  
# - Maximale Ladegrenze 80%
#
# Verwendung: .\set-battery-optimization.ps1 [-Action on|off|status]
# Default: on

param(
    [string]$Action = "on"
)

$adb = ".\adb.exe"
$dexPath = "bat_opt_dex\classes.dex"
$remotePath = "/data/local/tmp/BatteryOptimization.dex"

Write-Output "=== ZUI Battery Optimization ==="
Write-Output "Action: $Action"
Write-Output ""

# Check device connected
$devices = & $adb devices 2>&1
if ($devices -notmatch "\tdevice$") {
    Write-Output "ERROR: Kein Tablet verbunden!"
    Write-Output $devices
    exit 1
}

# Push DEX
Write-Output "Pushing BatteryOptimization.dex..."
& $adb push $dexPath $remotePath 2>&1 | Out-Null

# Run
Write-Output "Running BatteryOptimization $Action..."
Write-Output ""
& $adb shell "CLASSPATH=$remotePath app_process / BatteryOptimization $Action" 2>&1

Write-Output ""
Write-Output "=== Verifikation via dumpsys ==="
& $adb shell dumpsys zuibattery 2>&1 | Select-String -Pattern "Battery protected|Battery maintenance|Battery StopCharging|battery_stop_charging"
