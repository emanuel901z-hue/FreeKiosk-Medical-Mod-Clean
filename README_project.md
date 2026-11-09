# KIOSK-TABS

Projekt: **6 Lenovo Android-Tablets** als Kiosk-Geräte für den Patienteneinsatz
(Praxis / Klinik), betrieben mit [FreeKiosk](https://github.com/RushB-fr/freekiosk)
im **Device Owner Mode**.

> Upstream-Repo: https://github.com/RushB-fr/freekiosk
> Upstream-Doku (Entwicklung): [`freekiosk-main/CLAUDE.md`](freekiosk-main/CLAUDE.md)
> Agenten-Kontext: [`AGENTS.md`](AGENTS.md)

> **Platzhalter:** Alle installationsspezifischen Werte sind durch Platzhalter
> ersetzt (`{{API_KEY}}`, `{{PIN}}`, `{{TABLET_IP}}`, `{{SUBNET}}`, `{{IP_*}}`, `{{MAC_*}}`,
> `{{SERIAL_*}}`, `{{TABLET_LINK_ID_*}}`, `{{WLAN_PASSWORT_*}}`).
>
> **Eigene Werte setzen:**
> ```powershell
> Copy-Item .env.example .env.local   # .env.local ist gitignored
> # Werte in .env.local eintragen, dann:
> . .\load-env.ps1                   # setzt alle KIOSK_*-Variablen in der Session
> ```
> Skripte wie `get-tablet-url.ps1` lesen `$env:KIOSK_API_KEY`. Die Zuordnung
> Platzhalter → ENV-Variable beschreibt die ebenfalls gitignored
> `PRIVATE-CREDENTIALS.md` (ohne Werte).

---

## Einsatzprofil

| Gerät | Gruppe | Aufgabe | Kiosk-Modus | Ziel-URL |
|---|---|---|---|---|
| `PAT-01` | Patienten-Tablet | Patient füllt Web-Formular aus | WebView | `https://www.med-mv.de/qr-code-scanner/` |
| `PAT-02` | Patienten-Tablet | Patient füllt Web-Formular aus | WebView | `https://www.med-mv.de/qr-code-scanner/` |
| `PAT-03` | Patienten-Tablet | Patient füllt Web-Formular aus | WebView | `https://www.med-mv.de/qr-code-scanner/` |
| `QR-01` | QR-Display-Tablet | Zeigt Node.js-Website mit QR-Code für Patientengerät | WebView | `https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_01}}` |
| `QR-02` | QR-Display-Tablet | Zeigt Node.js-Website mit QR-Code für Patientengerät | WebView | `https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_02}}` |
| `QR-03` | QR-Display-Tablet | Zeigt Node.js-Website mit QR-Code für Patientengerät | WebView | `https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_03}}` |

### Wie es funktioniert

- **PAT-Tablets** werden dem Patienten in die Hand gedrückt. FreeKiosk sperrt das Gerät
  auf eine WebView-URL, die das Formular hostet. Der Patient kann nichts anderes öffnen.
- **QR-Tablets** stehen fest und zeigen eine von uns gehostete **Node.js-basierte Website**,
  die einen QR-Code rendert. Der Patient scannt diesen mit seinem eigenen Smartphone und
  landet so auf derselben / einer verknüpften Seite auf seinem Gerät.

### Netzwerk

- Tablets hängen im **Praxis-LAN** mit Internet-Zugriff.
- Die Node.js-Webserver für die QR-Tablets sind **öffentlich** über eine Domain erreichbar.
- Formular-Websites: je nach Setup LAN-intern oder öffentlich (siehe Tabelle oben, sobald
  final festgelegt).

---

## Verwendete Software / Versionen

| Komponente | Version | Pfad |
|---|---|---|
| FreeKiosk APK (Original) | `1.2.20-beta.3` | `freekiosk-v1.2.20-beta.3.apk` |
| FreeKiosk APK (Patched) | `1.2.20-beta.3-patched6` | `freekiosk-v1.2.20-beta.3-patched6.apk` |
| FreeKiosk Source | Stand 07.09.2026 (Patch 26) | `freekiosk-main/` |
| ADB platform-tools | latest (Windows) | `adb.exe` + DLLs im Projekt-Root |
| Android | 8.0+ (SDK 26+) | — |
| Tablet-Hardware | Lenovo Android-Tablets | 6 Stück |

> **Welche APK?** Für alle Tablets die **Patched**-APK verwenden. Sie enthält die
> zusätzlichen REST-Endpunkte (`/api/home`, `/api/webview/*`, `/api/inject-*`,
> `GET/POST /api/config`, `/api/wifi/*`, `/api/banner`, `/api/pin`, `/api/shutdown`)
> und die nativen Overlays (Home-Button + Anti-Theft Banner). Die Original-APK ist
> nur als Backup/Fallback behalten.
>
> **APK-Fixes (Bug 1-17):** Die Patched-APK enthält zusätzlich alle Bug-Fixes aus
> [`APK-FIXES-REQUIRED.md`](APK-FIXES-REQUIRED.md). Stand 20.07.2026 sind Bug 1-4,
> 7, 9-13, 15-17 fixiert (v1.2.20-beta.3-patched3). Bug 5, 6, 8 sind durch
> bestehende Patches/Workarounds kompensiert. Ab `patched4` trägt die APK-Datei das
> Patch-Level im Namen (`freekiosk-v1.2.20-beta.3-patched<N>.apk`), damit mehrere
> Patch-Stände parallel vorliegen und ein Rollback möglich ist. Aktuell:
> `-patched6` (Patch 26, DHCP Auto-Recovery).

## Releases

Fertige, installierbare APKs liegen als Pre-Release unter
[Releases](https://github.com/emanuel901z-hue/KIOSK-TABS/releases):

| Tag | APK-Asset | Inhalt |
|---|---|---|
| `v1.2.20-beta.3-patched6` | `freekiosk-v1.2.20-beta.3-patched6.apk` | Patch 1-26 (aktuell) |

Die APK ist Debug-signiert (`CN=Android Debug`). Updates auf einem bereits
provisionierten Tablet laufen über `adb install -r`; der Wechsel von der
Original-APK (Rushb-Signatur) erfordert einen Factory Reset.
>
> **Patch 24 (22.07.2026, patched4):** Device Identity Config-Keys
> (`device_serial`, `device_mac`). Seriennummer + MAC werden beim Provisioning per
> ADB ausgelesen (Android 10+ verbirgt `Build.SERIAL`) und als Config-Keys
> gespeichert. Abruf per `GET /api/config/device_serial` und
> `GET /api/config/device_mac` — für externe REST-Apps als eindeutiger
> Tablet-Identifikator. Siehe [REST-API.md](REST-API.md) → Device Identity.

---

## Provisioning (Schritt-für-Schritt)

Pro Tablet einmalig durchführen. Voraussetzung für Device Owner: **keine aktiven
User-Accounts** auf dem Tablet (Google/Samsung/Work-Accounts entfernen, SIM deaktivieren).
Ein Factory Reset ist **nicht** zwingend nötig — nur Fallback, falls `dpm` nach
Account-Removal trotzdem fehlschlägt. Siehe `freekiosk-main/docs/installation.md`.

### 0. Tablet vorbereiten

1. **Alle Accounts entfernen** — Settings → Accounts → jeden Account (Google, Samsung,
   Work/M365, …) einzeln entfernen. SIM-Karte raus oder Profil deaktivieren.
   Verifikation: Settings → Accounts zeigt "No accounts".
2. **Entwickleroptionen aktivieren** — Settings → About tablet → "Build number" 7× tippen.
3. **USB-Debugging an** — Settings → System → Developer options → "USB debugging" an.
4. **FreeKiosk noch NICHT öffnen** — Device Owner muss *vor* dem ersten Start gesetzt
   werden.

### 1. ADB-Verbindung herstellen

```powershell
# Im Projekt-Root:
.\adb.exe devices
```

Falls das Tablet nicht auftaucht: USB-Debugging in den Entwickleroptionen aktivieren
und den RSA-Fingerprint auf dem Tablet zulassen.

### 2. FreeKiosk installieren

```powershell
# Patched APK mit Home-Button + zusätzlichen REST-Endpunkten:
.\adb.exe install -r freekiosk-v1.2.20-beta.3-patched6.apk
```

> **Nach der Installation:** Android versucht die Activity unter Lock-Task
> wiederherzustellen — das führt oft zu einem weißen Bildschirm (UI rendert nicht,
> REST-Server läuft aber). Daher nach jedem `install -r` immer:
> ```powershell
> .\adb.exe shell am force-stop com.freekiosk
> Start-Sleep -Seconds 3
> .\adb.exe shell am start -n com.freekiosk/.MainActivity --activity-clear-task
> ```
> Danach den Config-Intent aus Schritt 5 senden. Siehe auch
> "Workflow: APK-Update" in `AGENTS.md`.

### 3. Device Owner setzen (Kiosk-Lockdown)

```powershell
.\adb.exe shell dpm set-device-owner com.freekiosk/.DeviceAdminReceiver
```

> **Achtung:** Device Owner ist nur reversibel via Factory Reset.
> Voraussetzung: keine aktiven Accounts auf dem Tablet (siehe Schritt 0).
> Falls `dpm` trotzdem fehlschlägt: Factory Reset durchführen und *keinen* Account
> vor Device Owner anlegen.

### 4. Permissions setzen (Device Owner)

Diese Permissions sind **zwingend erforderlich** für den Kiosk-Betrieb und müssen
**pro Tablet** nach dem Device Owner gesetzt werden. Ohne sie funktionieren bestimmte
Funktionen nicht oder die App stürzt ab:

```powershell
# Usage Stats — für App-Monitoring / Foreground-Detection
.\adb.exe shell appops set com.freekiosk android:get_usage_stats allow

# Secure Settings — für Helligkeit, Rotation, Screen-On/Off, Accessibility
.\adb.exe shell pm grant com.freekiosk android.permission.WRITE_SECURE_SETTINGS

# System Alert Window — für den nativen Home-Button-Overlay (TYPE_APPLICATION_OVERLAY).
# Ohne diese Permission schlägt showHomeButton() mit "permission denied for window
# type 2038" fehl und der Home-Button erscheint nicht. Auch wenn der Home-Button
# auf einem Tablet nicht genutzt wird, ist die Permission harmlos und sollte
# immer gesetzt werden.
.\adb.exe shell appops set com.freekiosk SYSTEM_ALERT_WINDOW allow
```

#### 4d. Accessibility Service aktivieren

Der Accessibility Service ermöglicht Key/Text-Injection in die WebView (z.B. für
automatisierte Formular-Ausfüllung per REST). Muss nach Device Owner manuell
aktiviert werden — wird nach Reboot automatisch re-aktiviert (BootReceiver).

```powershell
.\adb.exe shell settings put secure enabled_accessibility_services "com.freekiosk/com.freekiosk.FreeKioskAccessibilityService"
.\adb.exe shell settings put secure accessibility_enabled 1
```

Verifikation:
```powershell
.\adb.exe shell settings get secure enabled_accessibility_services
# Erwartet: com.freekiosk/com.freekiosk.FreeKioskAccessibilityService
```

> **Wichtig:** Diese Permissions (4a-4d) gehen bei einem Factory Reset verloren und
> müssen danach neu gesetzt werden. Bei neuen Tablets ebenfalls nicht vergessen — sie
> sind nicht Teil der APK-Installation, sondern müssen explizit per ADB gewährt werden.

### 5. Kiosk + REST API + Dauerbetrieb konfigurieren

Ein Intent, der **alle** Settings auf einmal setzt: URL, PIN, Kiosk-Modus, REST API,
Auto-Launch, Auto-Relaunch, Auto-Reload, Screensaver (2 Min, 1% Helligkeit, Wake-on-Content),
Power-Button blockiert, Home-Button, WebView Back-Button, Banner, URL-Filter,
Screen Scheduler (Mo-Fr 22:30-05:30, Sa+So ganztägig aus), Keep-Screen-On, Brightness-Management, Status-Bar aus.

**Vollständiger Intent siehe `AGENTS.md` → "Wichtige Befehle → ADB Provisioning".**

Dort ist der Best-Practice-Intent mit allen `--es`-Parametern dokumentiert. Nur
`$Url`, `$Pin` und `$ApiKey` pro Tablet anpassen.

```powershell
# Variablen pro Tablet anpassen:
$Url   = "https://med.informme.info/tablet3?tabletLinkId=..."
$Pin   = "{{PIN}}"
$ApiKey = "{{API_KEY}}"

# Vollständigen Intent aus AGENTS.md kopieren und $Url/$Pin/$ApiKey ersetzen.
# Wichtig: --activity-clear-task nicht vergessen!
```

> `--activity-clear-task` erzwingt einen sauberen Neustart der Activity, damit die
> Extras zuverlässig übernommen werden. Ohne dieses Flag kann die bereits laufende
> Activity den Intent ignorieren.
>
> **Home-Button-Extras:**
> - `home_button_enabled "true"` — aktiviert den sichtbaren Home-Button (nativer Overlay)
> - `home_button_x_percent "95"` — X-Position in % (Zentrum des Buttons)
> - `home_button_y_percent "17"` — Y-Position in % (Zentrum des Buttons)
>
> Der Home-Button benötigt **Schritt 4c** (`SYSTEM_ALERT_WINDOW` Permission), sonst
> wird er nicht angezeigt.

### 6. Android-System-Settings (Dauerbetrieb)

> **Hinweis:** Die folgenden Befehle sind die QR-Tablet-Defaults. Für PAT-Tablets
> siehe Provisioning-Checkliste Schritt 7 (`battery_protection_mode 0`, sonst gleich).

```powershell
# Screen bleibt an, solange Stromversorgung (USB/AC/Wireless)
.\adb.exe shell settings put global stay_on_while_plugged_in 3

# Auto-Rotation aus, Portrait lock
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0

# Screen-Timeout auf max (nie ausgehen)
.\adb.exe shell settings put global screen_off_timeout 2147483647

# Immersive Mode für FreeKiosk (Status- / Nav-Bar beim Swipe nicht persistent
# einblenden — sonst bleibt der graue Balken oben sichtbar unter Android 16 / ZUI)
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
```

### 6b. Lenovo ZUI Lockdown (TB336FU und andere ZUI-Geräte)

Lenovo-ZUI-Tablets zeigen im Freeform-Modus einen Window-Header mit 3-Punkte-Menü
an, über den sich das FreeKiosk-Fenster verschieben und **schließen** lässt —
ein Kiosk-No-Go. Die Lock-Task blockt das nicht, weil der Header vom ZUI Launcher
bzw. der ZUI Freeform Sidebar gerendert wird. Beide Komponenten müssen deaktiviert
werden. Zusätzlich gibt es ein ZUI-Setting für den Freeform-Window-Header.

```powershell
# ZUI Freeform Sidebar deaktivieren (rendert den 3-Punkte-Window-Header)
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar

# ZUI Launcher deaktivieren (Home-Screen — im Kiosk nicht benötigt)
.\adb.exe shell pm disable-user --user 0 com.zui.launcher

# ZUI Freeform-Window-Header Setting deaktivieren
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0

# ZUI Floating-Pen-Overlay deaktivieren
.\adb.exe shell settings put secure pen_floating_all 0
```

> **Wieder aktivieren** (falls das Tablet aus dem Kiosk-Betrieb genommen wird):
> ```powershell
> .\adb.exe shell pm enable com.zui.launcher
> .\adb.exe shell pm enable com.zui.freeform.sidebar
> .\adb.exe shell settings put secure enable_temp_zuifreeformbar 1
> .\adb.exe shell settings put secure pen_floating_all 1
> ```

> **Wichtig:** `--ez kiosk_enabled true` im Config-Intent (Schritt 5) ist
> **zwingend erforderlich**, damit die Android Lock-Task aktiviert wird. Ohne
> Lock-Task lässt sich die System-Leiste unten hochziehen und der ZUI Window-Header
> erscheint. Mit Lock-Task + deaktivierten ZUI-Komponenten ist das Tablet komplett
> gesperrt.

### 7. Verifikation

```powershell
# REST API per ADB-Forward erreichbar machen
.\adb.exe forward tcp:8081 tcp:8080

# Status abfragen
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}"
```

Erwartet: `"isDeviceOwner": true`, `"kioskMode": true`,
`"currentUrl"` = gesetzte URL, `"loading": false`, `"screensaverActive": false`.

**Home-Button verifizieren** (falls `home_button_enabled` gesetzt):
```powershell
# Prüfe ob der native Overlay erstellt wurde:
.\adb.exe logcat -d -t 200 | Select-String "HomeButton overlay shown"
# Erwartet: "HomeButton overlay shown at (X,Y) size=112px"
# Falls "permission denied for window type 2038" → Schritt 4c vergessen!
```

### 8. Screenshot zur visuellen Verifikation

```powershell
# Screenshot auf Tablet speichern und pullen (PowerShell-Redirect korrumpiert Binary!)
.\adb.exe shell screencap -p /sdcard/check.png
.\adb.exe pull /sdcard/check.png "check.png"
```

> **Nicht** `.\adb.exe exec-out screencap -p > file.png` in PowerShell verwenden —
> PowerShell interpretiert die Binary-Daten als UTF-8-String und korrumpiert das PNG.

Weitere ADB-Konfigurationsoptionen siehe
[`freekiosk-main/docs/adb-configuration.md`](freekiosk-main/docs/adb-configuration.md).
REST-Endpunkte siehe [`freekiosk-main/docs/rest-api.md`](freekiosk-main/docs/rest-api.md).

### 9. Provisioning-Checkliste (pro Tablet)

Diese Checkliste **pro Tablet** durchgehen und abhaken. Sie ist die einzige Stelle,
die alle Schritte in der richtigen Reihenfolge bündelt — wenn etwas fehlt, funktioniert
der Kiosk-Betrieb nicht oder nur unvollständig.

| # | Schritt | Befehl / Aktion | Erledigt |
|---|---|---|---|
| 0 | Accounts entfernt | Settings → Accounts → alle entfernen, SIM deaktivieren | ☐ |
| 0 | USB-Debugging an | Entwickleroptionen → USB debugging | ☐ |
| 1 | ADB-Verbindung | `.\adb.exe devices` zeigt das Tablet | ☐ |
| 2 | APK installiert | `.\adb.exe install -r freekiosk-v1.2.20-beta.3-patched6.apk` | ☐ |
| 3 | Device Owner gesetzt | `.\adb.exe shell dpm set-device-owner com.freekiosk/.DeviceAdminReceiver` | ☐ |
| 4a | Usage Stats Permission | `.\adb.exe shell appops set com.freekiosk android:get_usage_stats allow` | ☐ |
| 4b | Secure Settings Permission | `.\adb.exe shell pm grant com.freekiosk android.permission.WRITE_SECURE_SETTINGS` | ☐ |
| 4c | System Alert Window Permission | `.\adb.exe shell appops set com.freekiosk SYSTEM_ALERT_WINDOW allow` | ☐ |
| 4d | Accessibility Service | `.\adb.exe shell settings put secure enabled_accessibility_services "com.freekiosk/com.freekiosk.FreeKioskAccessibilityService"` + `accessibility_enabled 1` | ☐ |
| 5 | Config-Intent gesendet | `am start` mit URL, PIN, REST-API, Kiosk-Modus (siehe AGENTS.md). Startet die App + REST-Server. | ☐ |
| 5a | Full Config per REST | `POST /api/config` mit allen Keys (inkl. `device_serial` + `device_mac`) + `POST /api/restart-ui` — **zwingend!** Der ADB-Intent allein schreibt Config unzuverlässig in AsyncStorage. Siehe AGENTS.md Schritt 2. Serial/MAC per `getprop ro.serialno` + `dumpsys wifi \| grep mWifiInfo` auslesen. | ☐ |
| 5b | Lock-Task verifiziert | `dumpsys activity \| grep mLockTaskModeState` = `LOCKED`. Falls `NONE`: `force-stop` + `am start --ez kiosk_enabled true --activity-clear-task` | ☐ |
| 6a | WLAN-Netzwerke gespeichert | `cmd wifi connect-network office_24 wpa2 "{{WLAN_PASSWORT_OFFICE24}}" -r none` + `cmd wifi connect-network TabConnect wpa2 "{{WLAN_PASSWORT_TABCONNECT}}" -r none` — **vor** WiFi-Lock! MAC-Randomisierung aus (`-r none` = Hardware-MAC für static DHCP) | ☐ |
| 6b | WiFi-Lock aktiviert | `POST /api/wifi/lock` mit `allowed_ssids:["office_24","WiFi","TabConnect"]`, `lockActive: true`, `disallowConfigWifi: true` | ☐ |
| 7a | Stay-Awake | `.\adb.exe shell settings put global stay_on_while_plugged_in 3` (immer an, bei Stromversorgung) | ☐ |
| 7b | Rotation gesperrt | `.\adb.exe shell settings put system accelerometer_rotation 0` + `user_rotation 0` | ☐ |
| 7c | Screen-Timeout max | `.\adb.exe shell settings put global screen_off_timeout 2147483647` (immer an) | ☐ |
| 7d | Immersive Mode | `.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk` | ☐ |
| 7e | ZUI Launcher deaktiviert | `.\adb.exe shell pm disable-user --user 0 com.zui.launcher` | ☐ |
| 7e | ZUI Sidebar deaktiviert | `.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar` | ☐ |
| 7e | ZUI Freeform-Bar aus | `.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0` | ☐ |
| 7e | ZUI Floating-Pen aus | `.\adb.exe shell settings put secure pen_floating_all 0` | ☐ |
| 7f | Batterieschutz-Modus | QR: `.\adb.exe shell settings put global battery_protection_mode 1` (Dauerbetrieb am Strom) — PAT: `battery_protection_mode 0` (voller Akku, Tablets werden herumgereicht) | ☐ |
| 8 | REST-Status verifiziert | `isDeviceOwner: true`, `kioskMode: true`, URL korrekt, `mLockTaskModeState=LOCKED` | ☐ |
| 9 | Screenshot geprüft | WebView lädt, kein grauer Balken, Home-Button sichtbar (falls aktiviert), keine Navigation Bar | ☐ |
| 10 | Screensaver verifiziert | Nach Inaktivität: Display dimmt auf 1%, `screensaverActive: true` in REST | ☐ |
| 11 | Immersive Mode verifiziert | `settings get global policy_control` = `immersive.full=com.freekiosk` (auch nach Reboot!) | ☐ |
| 12 | ZUI-Lockdown verifiziert | `pm list packages -d` enthält `com.zui.freeform.sidebar` + `com.zui.launcher` (auch nach Reboot!) | ☐ |

> **Häufigste vergessene Schritte:**
> - **Schritt 4c** (`SYSTEM_ALERT_WINDOW`) — wird oft vergessen, Home-Button erscheint dann nicht
> - **Schritt 4d** (Accessibility Service) — ohne ihn keine Key/Text-Injection in WebView
> - **Schritt 7e** (ZUI-Komponenten) — ohne diese bleibt der graue Window-Header sichtbar. **Müssen als letztes gesetzt werden** (nach allen force-stop/Neustarts), sonst werden sie reverted!
> - **Schritt 5** (`--activity-clear-task`) — ohne dieses Flag werden Extras ignoriert
> - **Schritt 5a** (REST Config) — **der häufigste Fehler!** Der ADB-Intent allein übernimmt nicht zuverlässig alle Config-Keys in AsyncStorage. Ohne Schritt 5a fehlen `allow_power_button`, `home_button_enabled`, `banner_enabled`, `screensaver_*`, `screen_scheduler_*`, `device_serial`, `device_mac` etc.
> - **Schritt 5b** (Lock-Task) — falls `mLockTaskModeState=NONE`: `force-stop` + `am start --ez kiosk_enabled true --activity-clear-task` (minimaler Intent)
> - **Schritt 6b** (WiFi-Lock) — muss per REST aktiviert werden, nicht per ADB-Intent
> - **Schritt 11** (Immersive Mode) — wenn `policy_control=null`, ist die Android Navigation Bar sichtbar (Zurück-Button durch Hochziehen)
> - **Schritt 12** (ZUI-Lockdown nach Reboot) — `pm disable-user` kann bei ZUI-Geräten nach Reboot verloren gehen
> - **Nach APK-Update** — `install -r` lässt die App oft in halbtotem Zustand. Immer `force-stop` + `am start --activity-clear-task` danach
> - **Schritt 6a** (WLAN-Netzwerke) — muss **vor** WiFi-Lock (Schritt 6b) erfolgen, sonst blockiert `DISALLOW_CONFIG_WIFI`. `-r none` nicht vergessen (sonst randomisierte MAC, unbrauchbar für static DHCP)
> - **Schritt 7** generell — System-Settings müssen **als letztes** gesetzt werden (nach allen App-Neustarts), sonst gehen sie durch `force-stop`/Neustart verloren
> - **Schritt 4** generell — Permissions sind nicht Teil der APK und gehen bei Factory Reset verloren

---

## Wartung & Settings-Zugang

Im laufenden Kiosk-Betrieb ist das Tablet gesperrt (Lock-Task, kein Launcher).
Folgende Wege führen in die Settings — vom einfachsten zum mächtigsten.

### 1. FreeKiosk Settings per Touch (5-Tap)

**5x auf die untere rechte Ecke tippen** → PIN-Eingabe (`1234`) → FreeKiosk Settings UI.
Dort: URL ändern, Kiosk-Modus, REST-API, Screensaver, etc.

### 2. FreeKiosk Settings per Volume-Button (im Lock-Task)

**5x Volume-Up tippen** (mit Pausen dazwischen, ~250ms+) → PIN-Screen → FreeKiosk Settings.
Funktioniert auch im aktiven Lock-Task-Modus — FreeKiosk blockt den Auto-Relaunch und
navigiert zum PIN-Screen. Perfekt für fest verbaute Tablets.

### 3. Android System Settings (aus FreeKiosk Settings heraus)

In den FreeKiosk Settings gibt es einen Zugang zu den Android-System-Settings.
FreeKiosk pausiert die Lock-Task automatisch, öffnet Settings, re-lockt danach.

### 4. ADB (Notfall-Zugang)

```powershell
# Android Settings direkt öffnen (FreeKiosk pausiert Lock-Task automatisch)
.\adb.exe shell am start -n com.android.settings/.Settings

# Spezifische Settings-Seiten:
.\adb.exe shell am start -a android.settings.WIFI_SETTINGS
.\adb.exe shell am start -a android.settings.DISPLAY_SETTINGS
.\adb.exe shell am start -a android.settings.SOUND_SETTINGS

# Falls ZUI Launcher / Freeform Sidebar wieder aktiviert werden müssen:
.\adb.exe shell pm enable com.zui.launcher
.\adb.exe shell pm enable com.zui.freeform.sidebar

# Falls Lock-Task manuell gestoppt werden muss:
.\adb.exe shell am start -n com.freekiosk/.MainActivity --es pin "1234" --ez kiosk_enabled false --activity-clear-task
```

### 5. REST API (Remote, ohne Tablet-Interaktion)

Ersetze `TABLET_IP` und `API_KEY` durch die Werte aus der Geräteliste.

```powershell
# UI neu starten (Troubleshooting)
curl.exe -s -X POST "http://TABLET_IP:8080/api/restart-ui" -H "X-Api-Key: {{API_KEY}}"

# URL ändern (Body in Datei wegen PowerShell-Escaping)
'{"url":"https://neue-url"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/url" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# WebView neu laden
curl.exe -s -X POST "http://TABLET_IP:8080/api/reload" -H "X-Api-Key: {{API_KEY}}"

# Reboot (Device Owner erforderlich)
curl.exe -s -X POST "http://TABLET_IP:8080/api/reboot" -H "X-Api-Key: {{API_KEY}}"

# Shutdown / Power Off (Patch 22: POST /api/shutdown — Device Owner + Accessibility Service erforderlich)
# Fährt das Tablet komplett herunter. Startet nicht automatisch neu.
# Beim nächsten Boot (Power-Button drücken) startet FreeKiosk automatisch
# (Device Owner + Auto-Launch) und geht selbstständig in den Lock-Task-Modus.
# Implementierung: Lock-Task stoppen → GLOBAL_ACTION_POWER_DIALOG → "Ausschalten"
# Button im Accessibility Tree finden und klicken → Bestätigung klicken.
# Funktioniert auch im Kiosk-Modus (Lock-Task LOCKED).
curl.exe -s -X POST "http://TABLET_IP:8080/api/shutdown" -H "X-Api-Key: {{API_KEY}}"

# Alle Tablets herunterfahren (vor Transport / Einsammeln):
$ips = @("{{IP_QR_01}}","{{IP_QR_02}}","{{IP_QR_03}}","{{IP_PAT_01}}","{{IP_PAT_02}}","{{IP_PAT_03}}")
foreach ($ip in $ips) {
    Write-Output "Shutting down $ip..."
    curl.exe -s --connect-timeout 5 -X POST "http://${ip}:8080/api/shutdown" -H "X-Api-Key: {{API_KEY}}"
}

# PIN ändern (Patch 18: POST /api/pin)
# Mit old_pin Verifikation:
'{"old_pin":"1234","new_pin":"{{PIN}}"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/pin" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
# Oder mit force=true (ohne old_pin, nur API-Key als Autorisierung):
'{"new_pin":"{{PIN}}","force":true}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/pin" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# Config-Keys remote setzen (Patch: POST /api/config)
# Schreibt Keys in FreeKioskPendingConfig (has_pending_config=true).
# Mit /api/restart-ui werden die Keys in AsyncStorage übernommen und
# die Settings werden via JS-Event neu geladen (ohne Activity-Neustart).
'{"screensaver_enabled":"false","auto_brightness_enabled":"true"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/config" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
# Response: {"success":true,"data":{"executed":true,"applied":2,"appliedKeys":["screensaver_enabled","auto_brightness_enabled"]}}

# Config anwenden (nach /api/config):
curl.exe -s -X POST "http://TABLET_IP:8080/api/restart-ui" -H "X-Api-Key: {{API_KEY}}"
# Die App stürzt nicht ab — Config wird im Hintergrund angewendet.

# Config lesen (Patch 21: GET /api/config)
# Liefert die aktuell in AsyncStorage gespeicherte Config zurück.
curl.exe -s "http://TABLET_IP:8080/api/config" -H "X-Api-Key: {{API_KEY}}"
# Response: {"success":true,"data":{"url":"...","home_button_enabled":"true",...},"timestamp":...}

# Einzelnen Config-Key lesen (Patch 21: GET /api/config/:key)
curl.exe -s "http://TABLET_IP:8080/api/config/home_button_enabled" -H "X-Api-Key: {{API_KEY}}"
# Response: {"success":true,"data":{"key":"home_button_enabled","value":"true"},"timestamp":...}

# Status abfragen
curl.exe -s "http://TABLET_IP:8080/api/status" -H "X-Api-Key: {{API_KEY}}"

# WebView-Navigation (Patch: /api/webview/*)
curl.exe -s -X POST "http://TABLET_IP:8080/api/webview/back" -H "X-Api-Key: {{API_KEY}}"
curl.exe -s -X POST "http://TABLET_IP:8080/api/webview/forward" -H "X-Api-Key: {{API_KEY}}"
curl.exe -s -X POST "http://TABLET_IP:8080/api/webview/scroll-top" -H "X-Api-Key: {{API_KEY}}"

# CSS in aktuelle WebView-Seite injizieren (Patch: /api/inject-css)
'{"css":"body { background-color: #ffe0e0 !important; }"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/inject-css" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# JS injizieren — one-shot (nur aktuelle Seite) oder persistent (jeder Page-Load)
'{"code":"document.title = \"Remote\";","persistent":false}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/inject-js" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# Home-Button: Zurück zur Start-URL (baseUrl) navigieren (Patch: /api/home)
curl.exe -s -X POST "http://TABLET_IP:8080/api/home" -H "X-Api-Key: {{API_KEY}}"

# WiFi-Lock: Tablet bei WLAN-Verlust sperren (Patch: /api/wifi/lock)
'{"allowed_ssids":["Praxis-WLAN","office_24"]}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# WiFi-Lock Status:
curl.exe -s "http://TABLET_IP:8080/api/wifi/lock-status" -H "X-Api-Key: {{API_KEY}}"

# WiFi-Lock aufheben:
curl.exe -s -X POST "http://TABLET_IP:8080/api/wifi/unlock" -H "X-Api-Key: {{API_KEY}}"

# SSID hinzufügen (auch bei aktivem Lock):
'{"ssid":"Gast-WLAN"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/wifi/allow" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# Anti-Theft Banner: konfigurieren + sofort anzeigen (Patch: /api/banner)
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":14,"banner_show_icon":true}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# Anti-Theft Banner ausblenden:
curl.exe -s -X POST "http://TABLET_IP:8080/api/banner/hide" -H "X-Api-Key: {{API_KEY}}"
```

> **Achtung:** Nach einem Reboot startet FreeKiosk automatisch (Auto-Launch bei Boot).
> Der graue Balken / Window-Header sollte nach Reboot weg sein, da die ZUI-Komponenten
> (`com.zui.launcher`, `com.zui.freeform.sidebar`) deaktiviert sind.

### 5b. POST /api/config — unterstützte Keys (Patch)

Der `/api/config` Endpunkt wurde als Patch hinzugefügt (nicht im Upstream-FreeKiosk).
Er akzeptiert ein JSON-Object mit Key-Value-Paaren. Die Keys entsprechen den
ADB-Intent-Extras (gleiche Namen wie in `keyMapping` in `MainActivity.kt`).

**Wichtige Keys (Auswahl):**

| Key | AsyncStorage-Key | Beschreibung |
|---|---|---|
| `url` | `@kiosk_url` | WebView-URL |
| `pin` | `@kiosk_pin` | Settings-PIN |
| `auto_reload` | `@kiosk_auto_reload` | Reload on Error (WebView neu laden bei Fehler) |
| `kiosk_enabled` | `@kiosk_enabled` | Kiosk-Modus an/aus |
| `auto_launch` | `@kiosk_auto_launch` | Auto-Launch bei Boot |
| `auto_relaunch` | `@kiosk_auto_relaunch` | Auto-Relaunch bei Crash |
| `screensaver_enabled` | `@screensaver_enabled` | Screensaver an/aus |
| `screensaver_delay` | `@screensaver_inactivity_delay` | Inaktivitäts-Timeout in ms (120000 = 2 Min) |
| `screensaver_brightness` | `@screensaver_brightness` | Screensaver-Helligkeit (0-1 Skala, 0.01 = 1% Hardware-Min, 0 = schwarz) |
| `screensaver_wake_on_content_change` | `@screensaver_wake_on_content_change` | Screensaver bei DOM-Änderung aufwecken (Default: false) |
| `screensaver_inactivity_enabled` | `@screensaver_inactivity_enabled` | Inactivity-basierte Screensaver-Aktivierung |
| `screensaver_type` | `@screensaver_type` | Screensaver-Typ: "dim" oder "url" |
| `allow_power_button` | `@kiosk_allow_power_button` | Power-Menu erlauben (false = blockiert via Lock Task) |
| `back_button_mode` | `@kiosk_back_button_mode` | Back-Button-Modus: "immediate", "test", "timer" |
| `webview_back_button_enabled` | `@kiosk_webview_back_button_enabled` | WebView Back-Button anzeigen |
| `home_button_enabled` | `@kiosk_home_button_enabled` | Home-Button (Rückkehr zur Start-URL) anzeigen |
| `home_button_x_percent` | `@kiosk_home_button_x_percent` | Home-Button X-Position in % (Standard: 95) |
| `home_button_y_percent` | `@kiosk_home_button_y_percent` | Home-Button Y-Position in % (Standard: 17) |
| `keep_screen_on` | `@kiosk_keep_screen_on` | Bildschirm dauerhaft an (wichtig für Screensaver) |
| `pause_web_media_when_hidden` | `@kiosk_pause_web_media_when_hidden` | Media pausieren wenn WebView verdeckt |
| `default_brightness` | `@default_brightness` | Standard-Helligkeit (0-1 Skala, 1 = 100%) |
| `brightness_management_enabled` | `@brightness_management_enabled` | Helligkeits-Verwaltung durch App (false = System übernimmt) |
| `auto_brightness_enabled` | `@auto_brightness_enabled` | Auto-Helligkeit (false = aus, fest auf `default_brightness`) |
| `screen_scheduler_enabled` | `@kiosk_screen_scheduler_enabled` | Screen Sleep Scheduler an/aus |
| `screen_scheduler_rules` | `@kiosk_screen_scheduler_rules` | JSON-Array mit Scheduler-Rules (siehe unten) |
| `screen_scheduler_wake_on_touch` | `@kiosk_screen_scheduler_wake_on_touch` | Aufwecken durch Touch im Sleep-Zeitfenster |
| `url_filter_enabled` | `@kiosk_url_filter_enabled` | URL-Filter an/aus |
| `url_filter_mode` | `@kiosk_url_filter_mode` | Filter-Modus: "whitelist" oder "blacklist" |
| `url_filter_list` | `@kiosk_url_filter_list` | JSON-Array mit URL-Patterns |
| `url_filter_show_feedback` | `@kiosk_url_filter_show_feedback` | Feedback-Seite bei geblockter URL |
| `rest_api_enabled` | `@kiosk_rest_api_enabled` | REST-API an/aus |
| `rest_api_port` | `@kiosk_rest_api_port` | REST-API-Port |
| `rest_api_key` | `@kiosk_rest_api_key` | REST-API-Key |
| `rest_api_allow_control` | `@kiosk_rest_api_allow_control` | REST-API Control-Befehle erlauben |
| `pin_mode` | `@kiosk_pin_mode` | PIN-Modus: "numeric" oder "alphanumeric" |
| `pin_max_attempts` | `@kiosk_pin_max_attempts` | Max. PIN-Versuche |
| `allow_notifications` | `@kiosk_allow_notifications` | System-Benachrichtigungen erlauben |
| `allow_system_info` | `@kiosk_allow_system_info` | System-Info Zugriff erlauben |
| `banner_enabled` | `@kiosk_banner_enabled` | Anti-Theft Banner anzeigen |
| `banner_text` | `@kiosk_banner_text` | Banner-Text |
| `banner_font_size` | `@kiosk_banner_font_size` | Banner-Schriftgröße in sp |
| `banner_show_icon` | `@kiosk_banner_show_icon` | Dieb-Symbol im Banner anzeigen |

Unbekannte Keys werden ignoriert und in der Response als `unknownKeys` zurückgegeben.
Die Config wird **nicht** automatisch angewendet — nach `POST /api/config` muss
`POST /api/restart-ui` aufgerufen werden. Das `restart-ui` schreibt die Keys direkt in
AsyncStorage (RKStorage SQLite) und sendet ein `freekiosk_config_changed` JS-Event,
worauf `KioskScreen` die Settings neu lädt. Die App stürzt dabei nicht ab und der
Kiosk-Betrieb läuft ununterbrochen weiter.

**Config lesen:**
Mit `GET /api/config` (Patch 21) kann die aktuell gespeicherte Config ausgelesen werden.
Optional `GET /api/config/:key` für einen einzelnen Key. Beide Endpunkte greifen direkt
auf `AsyncStorage` zu und liefern das aktuelle Settings-Abbild zurück.

### 5c. WebView-Navigation & Code-Injection (Patch)

Die folgenden Endpunkte wurden als Patch hinzugefügt (nicht im Upstream-FreeKiosk).
Sie erlauben ferne Steuerung der WebView-Navigation und das Injizieren von CSS/JS
in die aktuell geladene Seite — nützlich für PAT-Tablets (Formulare) und QR-Tablets.

| Endpunkt | Methode | Body | Beschreibung |
|---|---|---|---|
| `/api/webview/back` | GET/POST | — | WebView eine Seite zurück (wie Browser-Zurück-Button) |
| `/api/webview/forward` | GET/POST | — | WebView eine Seite vorwärts |
| `/api/webview/scroll-top` | GET/POST | — | Sanftes Scroll-to-Top der aktuellen Seite |
| `/api/home` | GET/POST | — | Zur Start-URL (baseUrl) navigieren — simuliert Home-Button-Klick |
| `/api/inject-css` | POST | `{"css":"..."}` | Injiziert CSS als `<style>`-Tag in die aktuelle Seite (one-shot) |
| `/api/inject-js` | POST | `{"code":"...","persistent":false}` | Injiziert JS: `persistent=false` = one-shot (nur aktuelle Seite), `persistent=true` = wird bei jedem Page-Load ausgeführt (wird an `injectedJavaScript` angehängt) |

**Beispiele:**

```powershell
# PAT-Tablet: Formular nach oben scrollen
curl.exe -s -X POST "http://TABLET_IP:8080/api/webview/scroll-top" -H "X-Api-Key: {{API_KEY}}"

# PAT-Tablet: CSS injizieren um z.B. Header zu verstecken
'{"css":"header { display: none !important; } #footer { display: none !important; }"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/inject-css" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# QR-Tablet: Persistent JS das bei jedem Page-Load läuft (z.B. Analytics-Event)
'{"code":"console.log(\"[Kiosk] page loaded: \" + location.href);","persistent":true}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/inject-js" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
```

> **Hinweis:** `inject-css` ist ein Convenience-Wrapper — es konvertiert CSS in einen
> `<style>`-Tag und nutzt den gleichen JS-Injection-Kanal wie `/api/js`. Für komplexe
> Stylesheets ist `/api/inject-js` mit manuellem DOM-Code flexibler.
>
> **Persistent vs. One-Shot:** `persistent=true` bei `/api/inject-js` hängt den Code an
> den `injectedJavaScript`-Prop der WebView an und wird bei jedem Page-Load ausgeführt
> (auch nach Reload/Navigation). Ein erneuter Aufruf mit `persistent=true` akkumuliert
> den Code — es gibt kein "Zurücksetzen". Bei einem App-Neustart geht der persistente
> Code verloren (er ist nicht in AsyncStorage gespeichert).

### 5d. Home-Button & PAT-Tablet Workflow (Patch)

**Wann wird der Home-Button benötigt?**

| Tablet-Typ | Home-Button? | Grund |
|---|---|---|
| **PAT-01..03** | **Ja** (empfohlen) | Patient navigiert per QR-Scan weg von der Startseite; Personal muss manuell zurückkehren können |
| **QR-01..03** | Optional | QR-Display-Tablets haben normalerweise keinen Workflow mit Weg-Navigation; Home-Button schadet aber nicht |

> **Empfehlung:** Auf **allen 6 Tablets** aktivieren — ein Home-Button oben-rechts
> stört nicht auf reinen Display-Tablets, ist aber lebensrettend wenn jemand doch
> mal weg-navigiert (z.B. durch einen Link in der angezeigten Seite).

**Problem:** Auf PAT-Tablets durchläuft der Patient einen Workflow:
1. Tablet zeigt QR-Scanner-Seite (Start-URL = `baseUrl`)
2. Patient scannt QR-Code → Web-Seite forwarded zu informme Patienten-Formular
3. Patient füllt Formular aus
4. Personal muss Tablet manuell auf die QR-Scanner-Seite zurücksetzen

**Lösung:** Ein sichtbarer Home-Button (blaues Overlay mit Home-Icon) auf dem
Tablet-Bildschirm. Ein Tippen navigiert zurück zur `baseUrl` (QR-Scanner-Seite).
Zusätzlich kann die Rückkehr auch per REST `/api/home` ausgelöst werden.

> **Technische Details:** Der Home-Button wird als **nativer WindowManager-Overlay**
> (`TYPE_APPLICATION_OVERLAY`) gerendert, nicht als React Native View. Grund: React
> Native Views können keine Touch-Events empfangen, wenn eine native WebView (Platform
> View) den Bildschirm abdeckt. Der native Overlay schwebt über der WebView und bekommt
> Touch-Events zuverlässig. Bei Touch wird ein `homeButtonPressed` DeviceEventEmitter-
> Event an React Native gesendet, das die Navigation zur `baseUrl` auslöst.
>
> **Voraussetzung:** Die `SYSTEM_ALERT_WINDOW` Permission muss gewährt sein (Schritt 4c
> im Provisioning). Ohne diese Permission schlägt `showHomeButton` mit
> `permission denied for window type 2038` fehl und der Button erscheint nicht.

**Konfiguration:**

```powershell
# Home-Button aktivieren (via ADB-Intent oder /api/config)
# --es home_button_enabled "true" im am start Intent
# ODER per REST:
'{"home_button_enabled":"true","home_button_x_percent":"95","home_button_y_percent":"17"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/config" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
curl.exe -s -X POST "http://TABLET_IP:8080/api/restart-ui" -H "X-Api-Key: {{API_KEY}}"

# Home-Button Position: X=0-100% (links→rechts), Y=0-100% (oben→unten)
# Prozentwerte definieren das ZENTRUM des Buttons (nicht die Ecke oben-links)
# Standard: X=95% (rechts), Y=17% (oben-rechts)
```

**REST-Alternative:** `/api/home` navigiert zur `baseUrl` ohne Button-Klick:

```powershell
curl.exe -s -X POST "http://TABLET_IP:8080/api/home" -H "X-Api-Key: {{API_KEY}}"
```

**Wichtig:** Die `baseUrl` wird beim App-Start aus der konfigurierten URL gesetzt.
Web-interne Navigation (QR-Scan-Forward, Links, `window.location`) ändert die
aktuelle URL, aber **nicht** die `baseUrl`. Nur `/api/url` aktualisiert sowohl
`url` als auch `baseUrl` — daher für den Workflow die Start-URL via ADB-Intent
oder `/api/config` setzen, nicht via `/api/url` ändern.

**Kombination mit InactivityReturn:** Für automatische Rückkehr nach Inaktivität
kann zusätzlich `inactivity_return_enabled=true` mit einem Timeout konfiguriert
werden. Dann kehrt das Tablet nach X Sekunden Inaktivität selbstständig zur
`baseUrl` zurück — der Home-Button ist dann nur noch für manuelle Rückkehr nötig.

---

## WiFi-Lock (Tablet bei WLAN-Verlust sperren)

Das Tablet sperrt sich automatisch (`dpm.lockNow()`), wenn es das erlaubte WLAN
verlässt. Zusätzlich wird `DISALLOW_CONFIG_WIFI` gesetzt — niemand kann manuell
ein anderes WLAN konfigurieren. Das Tablet ist dann außerhalb der Praxis
effektiv unbrauchbar: kein Internet, Display gesperrt, Kiosk-Mode aktiv.

### Power-Button-Schutz

Bei WLAN-Verlust werden zusätzliche Maßnahmen ergriffen:

- `setLockTaskFeatures` ohne `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` → Power-Menü blockiert
- `SCREEN_ON`/`USER_PRESENT` BroadcastReceiver → bei jedem Aufwecken sofort
  `lockNow()` + `ScreenController.turnScreenOff()` + Brightness=0
- Bei Rückkehr ins erlaubte WLAN werden alle Features wiederhergestellt

> **Power-Button:** Der Power-Button kann das Display kurz aufwecken lassen
> (Android limitation), aber der BroadcastReceiver sperrt es sofort wieder +
> dimmt auf Brightness=0. Ein längeres Drücken (Power-Menü) wird blockiert.

### REST Endpunkte

| Endpunkt | Methode | Beschreibung |
|---|---|---|
| `/api/wifi/lock` | POST | WiFi-Lock aktivieren + erlaubte SSIDs setzen |
| `/api/wifi/unlock` | POST | WiFi-Lock deaktivieren + Restrictions aufheben |
| `/api/wifi/allow` | POST | SSID zur erlaubten Liste hinzufügen (auch bei aktivem Lock) |
| `/api/wifi/lock-status` | GET/POST | Aktuelle WiFi-Lock-Config + Verbindungsstatus |

### WiFi-Lock aktivieren

```powershell
'{"allowed_ssids":["Praxis-WLAN","office_24"]}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
```

### Status auslesen

```powershell
curl.exe -s "http://TABLET_IP:8080/api/wifi/lock-status" -H "X-Api-Key: {{API_KEY}}"
# Response: {"lockActive":true,"disallowConfigWifi":true,"currentSsid":"Praxis-WLAN",
#            "isConnected":true,"isOnAllowedNetwork":true,"allowed_ssids":["Praxis-WLAN","office_24"]}
```

### WiFi-Lock deaktivieren

```powershell
curl.exe -s -X POST "http://TABLET_IP:8080/api/wifi/unlock" -H "X-Api-Key: {{API_KEY}}"
```

> **Voraussetzung:** Device Owner Mode. Der WiFi-Lock nutzt `DevicePolicyManager`
> APIs (`addUserRestriction`, `clearUserRestriction`, `lockNow`,
> `setLockTaskFeatures`). Funktioniert nur wenn FreeKiosk Device Owner ist.

---

## Anti-Theft Banner (Diebstahl-Warnung auf Display)

Ein halbtransparenter schwarzer Balken am oberen Bildschirmrand mit konfigurierbarem
Text und optionalem Diebstahl-Symbol (aus `icons/tablet_anti_thef_plain.svg`).
Der Banner wird als **inline React Native View** gerendert — die Website beginnt
erst **unterhalb** des Banners, wird also nicht überdeckt. Bei `banner_enabled=false`
oder fehlendem Banner nutzt die Website den gesamten Bildschirm.

### REST Endpunkte

| Endpunkt | Methode | Beschreibung |
|---|---|---|
| `/api/banner` | POST | Banner konfigurieren + anzeigen (sofort, kein restart-ui nötig) |
| `/api/banner/hide` | GET/POST | Banner ausblenden |

### Banner aktivieren

```powershell
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":14,"banner_show_icon":true}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
```

### Banner ausblenden

```powershell
curl.exe -s -X POST "http://TABLET_IP:8080/api/banner/hide" -H "X-Api-Key: {{API_KEY}}"
```

### Parameter

| Parameter | Typ | Default | Beschreibung |
|---|---|---|---|
| `banner_enabled` | bool | `false` | Banner anzeigen/verstecken |
| `banner_text` | string | `"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert."` | Anzeigetext |
| `banner_font_size` | number | `25` | Schriftgröße in sp (Icon wird 2.5× dieser Größe gerendert, min. 36px) |
| `banner_show_icon` | bool | `true` | Diebstahl-Symbol anzeigen |

### Config via ADB-Intent

Banner-Parameter können auch beim Provisioning via ADB-Intent gesetzt werden:

```powershell
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es banner_enabled "true" `
    --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." `
    --es banner_font_size "25" `
    --es banner_show_icon "true" `
    # ... andere Parameter ...
```

> **Hinweis:** Der Banner wird als inline React Native View gerendert (kein
> `WindowManager` Overlay mehr). Es wird **keine** `SYSTEM_ALERT_WINDOW` Permission
> für den Banner benötigt (nur noch für den Home-Button).

---

## Monitoring & Warnungen (Akku, Netzwerk, Kiosk-Status)

FreeKiosk hat **keine eingebauten Warnungen** für Low-Battery oder Netzwerkverlust
("Enhanced Notifications / Network Monitoring" stehen auf der Roadmap für v1.3+).
Aber die Bausteine sind da — folgende Optionen zur Umsetzung:

### Verfügbare Bausteine

| Endpunkt | Funktion |
|---|---|
| `GET /api/status` | Liefert `battery.level`, `battery.charging`, `battery.health`, `wifi.connected`, `wifi.signalLevel`, `device.kioskMode` |
| `POST /api/toast` | Zeigt Toast-Text auf dem Tablet an: `{"text":"Akku niedrig!"}` |
| `POST /api/js` | Führt JS im WebView aus: `{"code":"alert('Akku niedrig!')"}` |
| `POST /api/tts` | Text-to-Speech, Tablet spricht den Warnungstext |
| MQTT | Published alle 30s den Status + LWT für Offline-Erkennung |

### Option A: Node.js-Website übernimmt es (empfohlen für QR-Tablets)

Eure Node.js-Websites können per REST `/api/status` das eigene Tablet pollen und bei
niedrigem Akku / Netzwerkverlust eine Warnung im WebView rendern. Vorteil: keine extra
Infrastruktur, die Website ist eh da.

```javascript
// Beispiel: alle 60s Akku-Status prüfen
setInterval(async () => {
  const res = await fetch('http://localhost:8080/api/status', {
    headers: { 'X-Api-Key': 'kiosk-XX-key' }
  });
  const { data } = await res.json();
  if (data.battery.level < 20 && !data.battery.charging) {
    // Warnung im WebView anzeigen
    document.getElementById('battery-warning').style.display = 'block';
  }
  if (!data.wifi.connected) {
    // Netzwerk-Warnung anzeigen
  }
}, 60000);
```

### Option B: Zentrales Monitoring-Script

Ein kleines Script auf einem Server pollt alle 6 Tablets per REST und schickt bei
Problemen Toast/TTS/Alert aufs jeweilige Tablet.

```
Trigger: Akku < 20% & nicht am Laden  → Toast + TTS auf Tablet
Trigger: WiFi getrennt                → Log + Alert
Trigger: kioskMode === false          → Alert (Kiosk ausgebrochen)
Trigger: Tablet nicht erreichbar      → Alert (Tablet offline / abgestürzt)
```

### Option C: Home Assistant + MQTT

FreeKiosk published automatisch Status + LWT (Last Will and Testament) über MQTT.
Home Assistant macht Automations daraus:

- QR-01 offline → Push-Notification ans Personal-Handy
- Akku < 20% → Ansage auf dem Tablet per TTS
- Kiosk-Modus aus → Alarm

Siehe [`freekiosk-main/docs/MQTT.md`](freekiosk-main/docs/MQTT.md) für die
Topic-Struktur und Home Assistant Discovery.

---

## Screen-Time & Screensaver

### Screen Sleep Scheduler (05:30 – 22:30)

FreeKiosk hat einen nativen Screen Sleep Scheduler (AlarmManager-basiert), der das
Display zu festgelegten Zeiten aus- und wieder einschaltet. Konfigurierbar **nur über
die Settings UI** (kein ADB-Parameter, kein REST-Endpunkt).

**Pro Tablet manuell einstellen:**

1. 5-Tap unten rechts → PIN `1234` → **Display** Tab
2. **Screen Sleep Schedule** → Enable
3. Rule hinzufügen:
   - Name: `Nacht`
   - Days: alle 7 (So–Sa)
   - Sleep: `22:30`
   - Wake: `05:30`
   - Wake on Touch: an (Tablet wacht bei Berührung auf, auch nachts)
4. Save → Exit Settings → Kiosk läuft weiter

> Der Scheduler nutzt `AlarmManager` + `WakeLock` — funktioniert zuverlässig auch wenn
> der Screen aus ist und JS schläft. Midnight-Crossing (22:30 → 05:30) wird korrekt
> behandelt.

### Screensaver (Inaktivitäts-Dimming)

| Tablet-Typ | Screensaver | Grund |
|---|---|---|
| **QR-01..03** | **An** (2 Min Delay, 1% Brightness, Wake-on-Content-Change) | Display dimmt bei Inaktivität; Website weckt Tablet bei neuem Inhalt über MutationObserver |
| **PAT-01..03** | **An** (30 Min Delay, 1% Brightness) | Patient legt Tablet hin → Display dimmt nach 30 Min, schont Akku/Screen, wacht bei Berührung wieder auf |

**PAT-Tablets: Screensaver per ADB aktivieren:**

```powershell
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es pin "1234" --es screensaver_enabled "true" `
    --es screensaver_delay "1800000" `
    --es screensaver_brightness "0.01" `
    --activity-clear-task
```

| Parameter | Wert | Bedeutung |
|---|---|---|
| `screensaver_enabled` | `"true"` | Screensaver aktivieren |
| `screensaver_delay` | `"1800000"` | 30 Minuten in ms (1800000 = 30×60×1000) |
| `screensaver_brightness` | `"0.01"` | Auf 1% dimmen (Hardware-Minimum) |

**QR-Tablets: Screensaver + Content-Change Wake:**

QR-Tablets dimmen nach Inaktivität. Wenn die Website neuen Inhalt zeigt (z.B. ein
neuer QR-Code wird generiert), wacht das Tablet automatisch auf — via `MutationObserver`
im WebView, der DOM-Änderungen erkennt und den Screensaver deaktiviert.

```powershell
# Per ADB-Intent:
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es screensaver_enabled "true" `
    --es screensaver_delay "120000" `
    --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" `
    --activity-clear-task

# Per REST API:
'{"screensaver_enabled":"true","screensaver_delay":"120000","screensaver_brightness":"0.01","screensaver_wake_on_content_change":"true"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/config" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
curl.exe -s -X POST "http://localhost:8081/api/restart-ui" '-H' 'X-Api-Key: {{API_KEY}}'
```

| Parameter | Wert | Bedeutung |
|---|---|---|
| `screensaver_wake_on_content_change` | `"true"` | DOM-Änderungen wecken den Screensaver auf |
| `screensaver_brightness` | `"0.01"` | Display auf 1% dimmen (Hardware-Minimum) |

> **Hinweis:** Der `MutationObserver` filtert Canvas/Video/Source/Track (Kamera-Feed)
> und ignoriert Text-Änderungen <10 Zeichen. Throttle: 5s. Nur hinzugefügte Nodes mit
> Text oder img/canvas werden als "significant" betrachtet.

**REST-Endpunkte für Screensaver:**

```powershell
# Screensaver aktivieren
curl.exe -s -X POST "http://TABLET_IP:8080/api/screensaver/on" -H "X-Api-Key: {{API_KEY}}"

# Screensaver deaktivieren
curl.exe -s -X POST "http://TABLET_IP:8080/api/screensaver/off" -H "X-Api-Key: {{API_KEY}}"

# Aus Screensaver aufwecken
curl.exe -s -X POST "http://TABLET_IP:8080/api/wake" -H "X-Api-Key: {{API_KEY}}"

# Wake-on-Content-Change aktivieren (QR-Tablets)
curl.exe -s -X POST "http://TABLET_IP:8080/api/screensaver/wake-content/on" -H "X-Api-Key: {{API_KEY}}"

# Wake-on-Content-Change deaktivieren
curl.exe -s -X POST "http://TABLET_IP:8080/api/screensaver/wake-content/off" -H "X-Api-Key: {{API_KEY}}"
```

> **Wake-on-Content-Change** aktiviert/deaktiviert live (ohne `restart-ui`). Die WebView
> wird neu geladen, um den `MutationObserver` zu injizieren bzw. zu entfernen.

### Screen Sleep Scheduler

Der Screen Sleep Scheduler schaltet das Display zu festgelegten Zeiten aus und wieder
an (z.B. Mo-Fr 22:30–05:30, Sa+So ganztägig aus). Verwendet native AlarmManager-Wecker für zuverlässiges
Aufwecken auch wenn die JS-Runtime pausiert.

**Config per ADB-Intent:**
```powershell
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es screen_scheduler_enabled "true" `
    --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" `
    --es screen_scheduler_wake_on_touch "true" `
    --ez kiosk_enabled true --activity-clear-task
```

**Config per REST:**
```powershell
$body = '{"screen_scheduler_enabled":"true","screen_scheduler_rules":"[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]","screen_scheduler_wake_on_touch":"true"}'
$body | Out-File "$env:TEMP\cfg.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://TABLET_IP:8080/api/config" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\cfg.json"
Remove-Item "$env:TEMP\cfg.json"
curl.exe -s -X POST "http://TABLET_IP:8080/api/restart-ui" -H "X-Api-Key: {{API_KEY}}"
```

**Rule-Format:**
- `id`: Eindeutige ID (z.B. "night")
- `name`: Anzeigename
- `enabled`: Rule aktiv
- `days`: Wochentage-Array (0=So, 1=Mo, ..., 6=Sa)
- `sleepTime`: "HH:MM" — Display aus
- `wakeTime`: "HH:MM" — Display an

### Power Button blockieren

Setze `allow_power_button` auf `false`, um das Power-Menü (Global Actions) zu blockieren.
Dies wird via `setLockTaskFeatures(flags=0)` umgesetzt — der Power-Button schaltet nur
noch das Display aus, aber das Lang-Drück-Menü (Power Off / Restart) erscheint nicht.

```powershell
# Per ADB-Intent
--es allow_power_button "false"

# Per REST
$body = '{"allow_power_button":"false"}'
# ... POST /api/config + /api/restart-ui
```

Verifikation in logcat: `Lock task features set: blockPowerButton=true, flags=0`

### Accessibility Service

Der Accessibility Service (`FreeKioskAccessibilityService`) ermöglicht Key/Text-Injection
in die WebView und andere Apps. Er muss nach Device Owner manuell aktiviert werden:

```powershell
.\adb.exe shell settings put secure enabled_accessibility_services "com.freekiosk/com.freekiosk.FreeKioskAccessibilityService"
.\adb.exe shell settings put secure accessibility_enabled 1
```

Nach Reboot wird er automatisch re-aktiviert (BootReceiver), sofern `WRITE_SECURE_SETTINGS`
Permission erteilt ist. Verifikation:
```powershell
.\adb.exe shell settings get secure enabled_accessibility_services
# Erwartet: com.freekiosk/com.freekiosk.FreeKioskAccessibilityService
```

### Backup / Restore

FreeKiosk hat eine interne Backup/Restore-Funktion (in den Settings erreichbar), aber
**keine** dedizierten REST-Endpunkte dafür. Backup exportiert die Config als JSON-Datei
in den Gerätespeicher. Für Remote-Backups stattdessen `/api/config` verwenden um die
aktuelle Config auszulesen und extern zu speichern.

---

## Geräteliste

| Name | Seriennummer | Lenovo-Modell | MAC / IP | PIN | Status |
|---|---|---|---|---|---|
| PAT-01 | {{SERIAL_PAT_01}} | TB336FU (EEA) | {{IP_PAT_01}} | {{PIN}} | **provisioniert** (patched5 APK + alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery Protection AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}}) |
| PAT-02 | {{SERIAL_PAT_02}} | TB336FU (EEA) | {{IP_PAT_02}} | {{PIN}} | **provisioniert** (patched5 APK + alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery Protection AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}}) |
| PAT-03 | {{SERIAL_PAT_03}} | TB336FU (EEA) | {{IP_PAT_03}} | {{PIN}} | **provisioniert** (patched5 APK + alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery Protection AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}}) |
| QR-01 | {{SERIAL_QR_01}} | TB336FU (EEA) | {{IP_QR_01}} | {{PIN}} | **provisioniert** (patched5 APK + alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **Battery-Optimierung AN** (Schutz beim Aufladen 40-60% + Ladungsoptimierung + Max. Ladegrenze 80%), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}}) |
| QR-02 | {{SERIAL_QR_02}} | TB336FU (EEA) | {{IP_QR_02}} | {{PIN}} | **provisioniert** (patched5 APK + alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **Battery-Optimierung AN** (Schutz beim Aufladen 40-60% + Ladungsoptimierung + Max. Ladegrenze 80%), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}}) |
| QR-03 | {{SERIAL_QR_03}} | TB336FU (EEA) | {{IP_QR_03}} | {{PIN}} | **provisioniert** (patched5 APK + alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **Battery-Optimierung AN** (Schutz beim Aufladen 40-60% + Ladungsoptimierung + Max. Ladegrenze 80%), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}}) |

> PINs **nicht** in dieses Repo committen — nur Platzhalter pflegen, echte Werte in einem
> Passwort-Safe ablegen.

---

## Verzeichnisstruktur

```
KIOSK-TABS/
├── AGENTS.md                      # Kontext für KI-Agenten
├── README_project.md              # diese Datei
├── REST-API.md                    # REST-API Dokumentation (patches)
├── adb.exe, AdbWinApi.dll, …      # ADB platform-tools (Windows, im Projekt-Root)
├── freekiosk-v1.2.20-beta.3.apk           # Original-APK (Backup/Fallback, gitignored)
├── freekiosk-v1.2.20-beta.3-patched6.apk   # zu installierende Kiosk-APK (mit Patches, gitignored)
├── get-tablet-url.ps1             # Helper: ermittelt echte WebView-URL nach QR-Scan
├── alarm_sound/                   # Custom Alarm-Sounds (→ res/raw/alarm.ogg im Build)
├── icons/                         # Icons (Anti-Theft, etc.)
├── freekiosk-main/                # FreeKiosk Upstream-Source (React Native/Kotlin)
│   ├── CLAUDE.md                  #   → Entwickler-Doku des Upstream-Projekts
│   ├── README.md                  #   → Upstream-README
│   └── docs/                      #   → REST-API, MQTT, ADB-Konfig, Installation, …
└── BAUPLAN_GET_CONFIG_ENDPOINT.md # Doku zu Patch 21 (GET /api/config)
```

---

## Weiterführende Doku (im Upstream-Tree)

| Thema | Datei |
|---|---|
| Installation / Device Owner | [`freekiosk-main/docs/installation.md`](freekiosk-main/docs/installation.md) |
| Features & Modi (WebView, External App, Dashboard, Media) | [`freekiosk-main/docs/features-and-modes.md`](freekiosk-main/docs/features-and-modes.md) |
| ADB-Konfiguration | [`freekiosk-main/docs/adb-configuration.md`](freekiosk-main/docs/adb-configuration.md) |
| REST-API (40+ Endpunkte) | [`freekiosk-main/docs/rest-api.md`](freekiosk-main/docs/rest-api.md) |
| MQTT / Home Assistant | [`freekiosk-main/docs/MQTT.md`](freekiosk-main/docs/MQTT.md) |
| Entwicklung am FreeKiosk-Code | [`freekiosk-main/CLAUDE.md`](freekiosk-main/CLAUDE.md) |

---

## Offene TODOs

- [x] URLs für PAT-Formulare finalisiert (`https://www.med-mv.de/qr-code-scanner/`)
- [x] URLs für QR-Code-Websites (QR-01, QR-02, QR-03) finalisiert
- [x] Seriennummern / IPs / Modelle aller Tablets eingetragen (siehe AGENTS.md Geräteliste)
- [x] Settings-PIN für alle Tablets vergeben ({{PIN}})
- [ ] Boot-Verhalten / Auto-Start nach Stromausfall pro Tablet prüfen
- [ ] Entscheidung: MQTT / Home Assistant Integration ja/nein
- [x] Alle Tablets auf aktuellen Config-Stand gebracht (WiFi-Lock, Power-Button, Screensaver, auto_reload, etc.)
- [ ] Backup-/Wartungsstrategie für die 6 Tablets festlegen
- [ ] Falls Formular-Website LAN-intern: internes Zertifikat / TLS-Handling klären
- [x] WiFi-Lock auf allen Tablets aktiviert (office_24 + WiFi + TabConnect)
- [x] Anti-Theft Banner auf allen Tablets aktiviert

---

## Lizenz

MIT — siehe [`LICENSE`](LICENSE). Dieses Projekt ist ein Fork von
[FreeKiosk](https://github.com/RushB-fr/freekiosk) (MIT, Copyright (c) 2025 Rushb);
die Upstream-Lizenz liegt zusaetzlich unter `freekiosk-main/LICENSE`.
