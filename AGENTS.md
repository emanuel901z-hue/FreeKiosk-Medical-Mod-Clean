# AGENTS.md — KIOSK-TABS Projekt

Dieses File gibt KI-Agenten (Devin, Claude, etc.) Kontext über das KIOSK-TABS Projekt.
Es beschreibt **unsere** Projektkonfiguration, nicht den Upstream FreeKiosk-Code.

> **Platzhalter in dieser Doku:** Alle installationsspezifischen Werte sind durch
> Platzhalter ersetzt — `{{API_KEY}}`, `{{PIN}}`, `{{SUBNET}}`, `{{IP_GATEWAY}}`, `{{IP_AP}}`,
> `{{IP_DIAG_PC}}`, `{{IP_QR_01}}`…`{{IP_PAT_03}}`, `{{MAC_*}}`, `{{BSSID_AP}}`,
> `{{SERIAL_*}}`, `{{TABLET_LINK_ID_*}}`, `{{WLAN_PASSWORT_OFFICE24}}`,
> `{{WLAN_PASSWORT_TABCONNECT}}` sowie `{{TABLET_IP}}` / `{{TABLET_URL}}`.
> Die echten Werte stehen ausschließlich in der lokalen, **nicht versionierten**
> `.env.local` (Vorlage: `.env.example`). `PRIVATE-CREDENTIALS.md` beschreibt nur die
> Zuordnung Platzhalter → ENV-Variable und den Betriebskontext.
>
> **ENV-Workflow:** `Copy-Item .env.example .env.local`, Werte eintragen, dann
> `. .\load-env.ps1` — setzt alle `KIOSK_*`-Variablen in der aktuellen PowerShell-Session
> (Process-Scope). Skripte wie `get-tablet-url.ps1` lesen `$env:KIOSK_API_KEY` und laden
> `.env.local` automatisch, wenn die Variable fehlt.

## Projektüberblick

Wir betreiben **6 Lenovo Android-Tablets** als Kiosk-Geräte in einer Patienten-Umgebung
(Praxis / Klinik). Alle Tablets werden im **Device Owner Mode** (Android Device Owner)
per ADB provisioniert und mit **FreeKiosk** (v1.2.20-beta.3) als Kiosk-Shell betrieben.

Zwei Einsatzprofilgruppen:

| Gruppe | Anzahl | Rolle | FreeKiosk-Modus |
|---|---|---|---|
| `PAT-01` … `PAT-03` | 3 | Patienten-Tablets: werden dem Patienten in die Hand gedrückt zum Ausfüllen von Formularen (Web-Formulare im WebView) | **WebView Kiosk** |
| `QR-01` … `QR-03` | 3 | Display-Tablets: zeigen Node.js-basierte Websites an, die QR-Codes rendern, die der Patient mit seinem eigenen Gerät scannt | **WebView Kiosk** |

### Netzwerk

- Tablets hängen im **Praxis-LAN** und haben Internet-Zugriff.
- Die Node.js-Webserver (für die QR-Tablets) sind **öffentlich** über eine Domain erreichbar,
  die Tablets rufen sie aber über ihre öffentliche URL ab.
- Formular-Websites für PAT-Tablets: siehe `README_project.md` (je nach Setup LAN oder öffentlich).

**LAN-Eckdaten (Praxis-Netz, Stand 04.11.2026):**

| Komponente | Wert |
|---|---|
| Subnetz | `{{SUBNET}}` |
| Gateway + **DHCP-Server** | `{{IP_GATEWAY}}` (Sophos-Firewall, MAC `{{MAC_FIREWALL}}`) |
| DNS | `{{IP_GATEWAY}}`, `8.8.4.4` |
| WLAN-AP (`office_24`) | `{{IP_AP}}` (Xiaomi, BSSID `{{BSSID_AP}}`) |
| IP-Vergabe | **Static DHCP** (MAC-Bindung) pro Tablet — siehe „Geräteliste" |
| Diagnose-PC (dieser Rechner) | `{{IP_DIAG_PC}}` (statisch, Ethernet) |

> **DHCP-Fehlerbild „Tablet bekommt keine WLAN-Verbindung":** WLAN assoziiert und
> WPA2-Handshake läuft durch, aber es kommt kein `DHCPOFFER` → `IP: null` →
> `NETWORK_SELECTION_DISABLED_DHCP_FAILURE` (Netz wird 280 s gesperrt, Zähler steigt).
> Diagnose in dieser Reihenfolge:
> 1. `adb shell cmd wifi status` → `Wifi is connected ... IP: null`
> 2. `adb logcat -d | grep -i dhcp` → zählt `Broadcasting DHCPDISCOVER` ohne Antwort
> 3. DHCP-Server gegenprüfen vom PC: `nmap --script broadcast-dhcp-discover`
>    (Antwort mit `Server Identifier: {{IP_GATEWAY}}` = Server läuft)
> 4. Mitschneiden, ob die DISCOVERs des Tablets im LAN ankommen (Python-Listener auf
>    UDP/67, zeigt `chaddr` + Hostname) — kommen sie an, liegt es am DHCP-Server
>    (MAC-Bindung/Sperre), nicht am Tablet oder AP.
> 5. Im Sophos die MAC-Bindung prüfen: MAC muss die **Hardware-MAC** aus der
>    Geräteliste sein (`-r none` beim `connect-network`, MAC-Randomisierung aus).
>    Eine Bindung auf eine falsche/randomisierte MAC führt zu genau diesem Fehlerbild.
> 6. Nach dem Fix: `adb shell cmd wifi set-wifi-enabled disabled/enabled` und den
>    WebView per `POST /api/reload` neu laden (das Lade-Overlay bleibt sonst hängen).

## Verzeichnisstruktur

```
D:\Projekte\KIOSK-TABS\
├── AGENTS.md                      # dieses File
├── LICENSE                        # MIT (Fork von FreeKiosk, Upstream-Notice erhalten)
├── README_project.md              # Projektdokumentation (menschlich)
├── REST-API.md                    # REST-API Dokumentation (patches)
├── APK-FIXES-REQUIRED.md          # Bug-Liste + Fix-Status (Bug 1-17, patched3)
├── PRIVATE-CREDENTIALS.md         # ECHTE Zugangsdaten (gitignored, NIE committen!)
├── .env.example                   # Vorlage für .env.local (nur Platzhalter, committet)
├── .env.local                     # ECHTE Zugangsdaten als ENV (gitignored, NIE committen!)
├── load-env.ps1                   # lädt .env.local in die PowerShell-Session
├── ARCHIVE\                       # Archivierte Pläne/Doku (historisch, nicht mehr gepflegt)
│   └── BAUPLAN_GET_CONFIG_ENDPOINT.md  # Doku zu Patch 21 (GET /api/config)
├── .gitignore                     # Root-Level gitignore (APKs etc.)
├── adb.exe + *.dll                # ADB platform-tools (Windows) für Provisioning
├── get-tablet-url.ps1             # Helper: ermittelt WebView-URL nach QR-Scan
├── freekiosk-v1.2.20-beta.3.apk           # Original-APK (gitignored, Backup/Fallback)
├── freekiosk-v1.2.20-beta.3-patched5.apk  # vorherige Kiosk-APK (gitignored, patched5 =
│                                          #   Patch 1-24 + Keyguard-Fix Patch 25)
├── freekiosk-v1.2.20-beta.3-patched6.apk  # aktuelle Kiosk-APK (gitignored, patched6 =
│                                          #   patched5 + DHCP Auto-Recovery Patch 26,
│                                          #   gebaut 07.09.2026 — Rollout ausstehend)
├── alarm_sound\                           # Custom Alarm-Sounds (Source-Dateien)
│   └── Tablet_ALARM.ogg                  # Diebstahl-Alarm (→ res/raw/alarm.ogg im Build)
├── icons\                                # Icons (Anti-Theft, etc.)
├── freekiosk-main\                # FreeKiosk Source-Tree (Upstream + unsere Patches)
│   ├── CLAUDE.md                  # Upstream-Entwickler-Doku (React Native / Kotlin)
│   ├── README.md                  # Upstream-Projekt-README
│   └── docs\                      # Upstream-Doku (REST-API, MQTT, ADB-Konfig, etc.)
└── .vscode\                       # VS Code Settings
```

> **Wichtig:** `freekiosk-main\` ist der **Upstream-Sourcecode** von FreeKiosk
> (https://github.com/RushB-fr/freekiosk). Wir ändern ihn nur, wenn wir einen Patch brauchen.
> Unsere projektspezifische Doku lebt **außerhalb** von `freekiosk-main\` direkt im
> Projekt-Root (`AGENTS.md`, `README_project.md`).

## Wichtige Befehle

### ADB (Provisioning)

```powershell
# Im Projekt-Root arbeiten, damit adb.exe gefunden wird:
.\adb.exe devices

# APK installieren (Patched-Version mit Home-Button + REST-Patches)
.\adb.exe install -r freekiosk-v1.2.20-beta.3-patched6.apk

# Device Owner setzen (Tablet muss frisch sein, kein Google-Account!)
.\adb.exe shell dpm set-device-owner com.freekiosk/.DeviceAdminReceiver

# Permissions setzen (nach Device Owner)
.\adb.exe shell appops set com.freekiosk android:get_usage_stats allow
.\adb.exe shell pm grant com.freekiosk android.permission.WRITE_SECURE_SETTINGS
.\adb.exe shell appops set com.freekiosk SYSTEM_ALERT_WINDOW allow  # für nativen Home-Button-Overlay

# Accessibility Service aktivieren (für Key/Text-Injection in WebView)
.\adb.exe shell settings put secure enabled_accessibility_services "com.freekiosk/com.freekiosk.FreeKioskAccessibilityService"
.\adb.exe shell settings put secure accessibility_enabled 1

# Kiosk + REST API + Dauerbetrieb konfigurieren (vollständiger Intent)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://med.informme.info/tablet3?tabletLinkId=..." --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "120000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# Android-System-Settings: Stay-Awake + Orientation-Lock + Screen-Timeout max
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
# Immersive Mode für FreeKiosk (sonst bleibt Status-Bar beim Swipe persistent sichtbar)
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk

# Lenovo ZUI Lockdown (TB336FU und andere ZUI-Geräte) — sonst Window-Header mit
# 3-Punkte-Menü sichtbar, über den sich das Fenster schließen lässt.
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0

# Verifikation: Lock-Task muss LOCKED sein
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
# Erwartet: mLockTaskModeState=LOCKED

# Verifikation per REST (ADB-Forward)
.\adb.exe forward tcp:8081 tcp:8080
curl.exe -s "http://localhost:8081/api/status" '-H' 'X-Api-Key: {{API_KEY}}'
```

> **Vollständiger Provisioning-Ablauf** mit Erklärungen siehe `README_project.md`
> Abschnitt "Provisioning (Schritt-für-Schritt)".

### FreeKiosk-Source (nur falls am Code gearbeitet wird)

Siehe `freekiosk-main\CLAUDE.md` für die vollständige Entwickler-Doku. Kurz:

```bash
cd freekiosk-main
npm install
npm start            # Metro bundler
npm run android      # auf angeschlossenem Gerät starten
npm run lint
npm test
```

Build: `cd android && ./gradlew assembleRelease` → APK unter
`android/app/build/outputs/apk/release/app-release.apk`.

Anforderungen: Node 20+, JDK 17+, Android SDK 26+.

> **Build-Hinweis (Windows):** Gradle 8.14.3 auf Windows hat einen Bug mit
> `Files.move()` in der Transform-Cache (`Could not move temporary workspace`).
> Workaround: `local.properties` mit Forward-Slashes (`sdk.dir=C:/Users/...`),
> `gradle.properties` mit `org.gradle.parallel=false` und `org.gradle.workers.max=1`.
> Bei wiederkehrenden Transform-Fehlern: Temp-Verzeichnis (Hash-UUID) manuell nach
> Hash-Only verschieben, dann Build fortsetzen. Siehe Build-Logs in `$TEMP/gradle-build*.log`.

### Build-Workflow (gepatchte APK)

```powershell
# 1. JS-Bundle neu bauen (nach src/ Änderungen)
cd D:\Projekte\KIOSK-TABS\freekiosk-main
$env:JAVA_HOME = "C:\Users\Manu\AppData\Local\jdk17\jdk-17.0.13+11"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# Gradle-Cache auf D: (C: hat zu wenig Platz für ~5GB Gradle-Cache)
$env:GRADLE_USER_HOME = "D:\Projekte\KIOSK-TABS\.gradle-home"
npx react-native bundle --platform android --dev false `
    --entry-file index.js `
    --bundle-output android/app/src/main/assets/index.android.bundle `
    --assets-dest android/app/src/main/res/

# 1b. Custom Alarm-Sound kopieren (falls aktualisiert)
Copy-Item "..\alarm_sound\Tablet_ALARM.ogg" `
    "android\app\src\main\res\raw\alarm.ogg" -Force

# 2. APK bauen (nach Kotlin/Java Änderungen — JS-Bundle muss schon aktuell sein)
Get-Process -Name "gradle*" -ErrorAction SilentlyContinue | Stop-Process -Force
cd D:\Projekte\KIOSK-TABS\freekiosk-main\android
.\gradlew.bat assembleRelease --max-workers=1

# 3. APK kopieren
Copy-Item "app\build\outputs\apk\release\app-release.apk" `
    "..\..\freekiosk-v1.2.20-beta.3-patched6.apk" -Force

# 4. Auf Tablet installieren (gleiche Debug-Signatur → Update mit -r möglich)
cd D:\Projekte\KIOSK-TABS
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"
```

> **Wichtig:** Android Studio muss **geschlossen** sein während des Gradle-Builds —
> sonst gibt es Konflikte mit laufenden Java/Gradle-Prozessen (Build hängt).

## Patches (freekiosk-main)

Wir haben folgende Patches im Upstream-Tree angewendet (nicht im offiziellen FreeKiosk):

1. **`MainActivity.kt` — erweitertes `keyMapping`**: Weitere Config-Keys für ADB-Intent
   Provisioning hinzugefügt (Screen Scheduler, Inactivity Return, URL Filter,
   Auto-Brightness, Blocking Overlays, URL Rotation, URL Planner, etc.).
2. **`KioskHttpServer.kt` — `POST /api/config` Endpunkt**: Neuer REST-Endpunkt, der
   JSON-Config entgegennimmt und an `HttpServerModule.handleSetConfig()` weiterleitet.
3. **`HttpServerModule.kt` — `handleSetConfig()` + `configKeyMapping`**: Schreibt
   Config-Keys in `FreeKioskPendingConfig` SharedPreferences und setzt
   `has_pending_config = true`. Die Config wird beim nächsten `/api/restart-ui`
   angewendet: Keys werden direkt in AsyncStorage (RKStorage SQLite DB) geschrieben
   und ein `freekiosk_config_changed` JS-Event feuert, worauf `KioskScreen` die
   Settings neu lädt. Kein `activity.recreate()` oder `ReactHost.reload()` — beide
   crashen die App auf Android 16 / RN 0.82 (WebView-Ref-Invalidierung während
   `pauseMedia`).
4. **`WebViewComponent.tsx` — `pauseMedia`/`resumeMedia` Patch**: `findNodeHandle(wv)`
   entfernt — es crasht mit `JavascriptException: Argument appears to not be a
   ReactComponent` beim `onResume` nach `onNewIntent`. JS-seitige Media-Pause via
   `injectJavaScript` funktioniert weiterhin.
5. **`KioskScreen.tsx` — `freekiosk_config_changed` Listener**: Horcht auf das
   Native Event und ruft `loadSettings()` auf um Settings neu zu laden.
6. **`HttpServerModule.kt` — `restartUi` Patch**: Statt `activity.recreate()` (crasht
   auf Android 16 / RN 0.82) werden die pending Config-Keys direkt in AsyncStorage
   (RKStorage SQLite) geschrieben und ein `freekiosk_config_changed` JS-Event
   gesendet. Die App läuft ununterbrochen weiter.
7. **`MainActivity.kt` — `applyPendingConfig()`**: Fallback-Methode die in
   `onNewIntent` aufgerufen wird falls `apply_pending_config=true` im Intent steht.
   Schreibt pending Config-Keys in AsyncStorage und sendet JS-Event. Wird aktuell
   nicht vom `restartUi`-Pfad verwendet (der geht direkt über `HttpServerModule`),
   aber als Fallback für ADB-Intents mit `apply_pending_config`-Extra.
8. **`KioskHttpServer.kt` — WebView-Navigation & Code-Injection Endpunkte**: Neue
   REST-Endpunkte für ferne WebView-Steuerung:
   - `POST /api/webview/back` — WebView eine Seite zurück
   - `POST /api/webview/forward` — WebView eine Seite vorwärts
   - `POST /api/webview/scroll-top` — Sanftes Scroll-to-Top
   - `POST /api/inject-css` — CSS als `<style>`-Tag in aktuelle Seite injizieren (one-shot)
   - `POST /api/inject-js` — JS injizieren: `persistent=false` (one-shot) oder
     `persistent=true` (wird an `injectedJavaScript` angehängt, läuft bei jedem Page-Load)
   Die Commands werden via `sendEvent("onApiCommand", ...)` an JS gesendet.
9. **`ApiService.ts` — Neue Callbacks**: `onWebViewBack`, `onWebViewForward`,
   `onWebViewScrollTop`, `onInjectCss`, `onInjectJs` im `ApiCallbacks`-Interface
   und `handleCommand`-Switch.
10. **`KioskScreen.tsx` — Callback-Implementierung**: WebView-Nav-Callbacks rufen
    `webViewRef.current?.goBack()/goForward()/scrollToTop()` auf. `onInjectCss`
    wrappt CSS in einen `<style>`-Tag und nutzt den `jsToExecute`-Kanal.
    `onInjectJs(persistent=true)` setzt `persistentInjectedJs`-State, der als
    `customInjectedJavaScript`-Prop an `WebViewComponent` weitergereicht wird.
    `onGoHome` liest `baseUrlRef.current` (Ref, kein State — vermeidet stale
    Closure im API-Callback) und navigiert dorthin.
11. **`WebViewComponent.tsx` — `customInjectedJavaScript` Prop**: Neuer optionaler
    Prop, der an `combinedInjectedJavaScript` angehängt wird (nach
    `injectedJavaScript + getKeyboardModeScript()`).
12. **Home-Button Feature (Storage + Native Overlay + REST)**:
    - **`storage.ts`**: Neue Keys `HOME_BUTTON_ENABLED`, `HOME_BUTTON_X_PERCENT`,
      `HOME_BUTTON_Y_PERCENT` + StorageService-Methoden.
    - **`KioskScreen.tsx`**: State + `loadSettings` + `useEffect` der den nativen
      Overlay via `BlockingOverlayModule.showHomeButton(x, y)` zeigt/versteckt.
      Positionierung: x/y-Prozent definieren das **Zentrum** des Buttons (nicht
      die Ecke oben-links). Default: x=95%, y=17%. Deckkraft: 25% (argb 64/255).
      `baseUrlRef` als Ref-Mirror von `baseUrl` für API-Callbacks. Event-Listener
      für `homeButtonPressed` DeviceEventEmitter-Event.
    - **`BlockingOverlayModule.kt`**: Neue `showHomeButton`/`hideHomeButton` Methoden
      + `HomeButtonView` (native View, zeichnet blauen Kreis mit Home-Icon).
      Verwendet `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` damit der
      Button über der WebView schwebt und Touch-Events empfängt.
    - **`BlockingOverlayModule.ts`**: TS-Brücke für `showHomeButton`/`hideHomeButton`.
    - **`MainActivity.kt` + `HttpServerModule.kt`**: `home_button_*` Keys in
      `keyMapping` und `configKeyMapping` aufgenommen.
    - **`KioskHttpServer.kt`**: `POST /api/home` Endpunkt → `commandHandler("goHome")`.
    - **`ApiService.ts`**: `onGoHome` Callback.
    - **Wichtig:** `SYSTEM_ALERT_WINDOW` Permission muss per `appops set` gewährt
      werden, sonst schlägt der native Overlay fehl (`permission denied type 2038`).
13. **WiFi-Lock Feature (WifiLockModule + REST Endpunkte + Auto-Restore)**:
    - **`WifiLockModule.kt`** (neu): Native Module mit WiFi-Monitor
      (`ConnectivityManager.NetworkCallback`), `dpm.lockNow()` bei WLAN-Verlust,
      `addUserRestriction("no_config_wifi")` / `clearUserRestriction()`.
      Erlaubte SSIDs in `FreeKioskWifiLock` SharedPreferences gespeichert.
      React-Native-Methoden: `enableWifiLock`, `disableWifiLock`, `addAllowedSsid`,
      `removeAllowedSsid`, `getWifiLockStatus`, `isDeviceOwner`.
      **Auto-Restore:** `initialize()` liest SharedPreferences beim App-Start und
      stellt WiFi-Lock automatisch wieder her (nach Reboot/Crash). `disableWifiLock`
      löscht SharedPreferences, damit es nicht wiederhergestellt wird.
      **Power-Button-Schutz:** Bei WLAN-Verlust wird `setLockTaskFeatures()` ohne
      `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` aufgerufen (Power-Menü blockiert) + ein
      `SCREEN_ON`/`USER_PRESENT` BroadcastReceiver registriert, der bei jedem
      Aufwecken sofort `lockNow()` + `ScreenController.turnScreenOff()` +
      Brightness=0 aufruft. Bei Rückkehr ins erlaubte WLAN werden die Features
      wiederhergestellt (`unlockDevice()` + Brightness auf 255).
      **lockNow() Timing:** `lockNow()` wird 1,5s nach Alarm-Start ausgeführt,
      damit der AlarmService zuerst Audio initialisieren kann. Danach wird
      Master-Mute erneut gecleared (lockNow() kann es reaktivieren).
      **SSID-Retry:** Wenn `getCurrentSsid()` `<unknown ssid>` zurückgibt (direkt
      nach WLAN-Verbindung, bevor Android die SSID lesbar macht), retry `checkNetwork`
      alle 2 Sekunden bis die SSID lesbar ist.
    - **`WifiLockPackage.kt`** (neu): ReactPackage-Registrierung.
    - **`MainApplication.kt`**: `WifiLockPackage()` hinzugefügt.
    - **`KioskHttpServer.kt`**: `wifiLockHandler` Constructor-Parameter + 4 neue
      REST-Endpunkte: `POST /api/wifi/lock`, `POST /api/wifi/unlock`,
      `POST /api/wifi/allow`, `GET /api/wifi/lock-status`.
    - **`HttpServerModule.kt`**: `handleWifiLockCommand()` — brückt REST-Endpunkte
      zu DPM-Aufrufen und `WifiLockModule`. Unlock-Pfad stellt auch
      `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` wieder her.
14. **Anti-Theft Banner (Inline RN View + REST Endpunkte)**:
    - **`KioskScreen.tsx`**: Banner wird als **inline React Native View** gerendert
      (halbetransparenter schwarzer Balken, weißer Text + Diebstahl-Symbol als PNG
      aus `icons/tablet_anti_thef_plain.svg`). Die Website beginnt **unterhalb** des
      Banners — kein Overlay mehr. State + `loadSettings` + API-Callbacks
      (`onSetBanner`, `onHideBanner`). Icon-Größe = 2.5× Schriftgröße (min. 36px).
    - **`BlockingOverlayModule.kt`**: `showBanner`/`hideBanner` + `BannerOverlayView`
      noch vorhanden als Fallback (nativer Overlay), wird aber nicht mehr aktiv
      verwendet. `banner_icon.xml` (VectorDrawable) im `res/drawable/` Ordner.
    - **`src/assets/banner_icon.png`**: PNG des Diebstahl-Symbols (144×144, aus SVG
      konvertiert mit ImageMagick).
    - **`storage.ts`**: Keys `BANNER_ENABLED`, `BANNER_TEXT`, `BANNER_FONT_SIZE`,
      `BANNER_SHOW_ICON` + StorageService-Methoden.
    - **`KioskHttpServer.kt`**: `POST /api/banner` (setzen) +
      `GET/POST /api/banner/hide` (ausblenden).
    - **`ApiService.ts`**: `onSetBanner` / `onHideBanner` Callbacks +
      `set_banner` / `hide_banner` Command-Handler.
    - **`MainActivity.kt` + `HttpServerModule.kt`**: `banner_*` Keys in
      `keyMapping` und `configKeyMapping` aufgenommen.
    - **Keine `SYSTEM_ALERT_WINDOW` Permission nötig** für den Banner (nur für
      Home-Button).
15. **Screensaver Wake on Content Change (MutationObserver)**:
    - **`WebViewComponent.tsx`**: Neuer Prop `screensaverWakeOnContentChange` +
      `onContentChange` Callback. Wenn aktiviert, wird ein `MutationObserver` in
      `injectedJavaScript` injiziert, der DOM-Änderungen erkennt (added nodes mit
      Text >10 Zeichen oder img/canvas). Canvas/Video/Source/Track werden gefiltert
      (Kamera-Feed-Rauschen). Throttle: 5s. Sendet `CONTENT_CHANGE` per `postMessage`.
    - **`onMessageHandler`**: Neuer Handler für `CONTENT_CHANGE` → ruft `onContentChange`
      auf.
    - **`KioskScreen.tsx`**: State `screensaverWakeOnContentChange` + `loadSettings` +
      `onContentChange` Callback der `setIsScreensaverActive(false)` + `resetTimer()` +
      Helligkeit wiederherstellt + `markUserInteraction()` aufruft.
    - **`storage.ts`**: Key `SCREENSAVER_WAKE_ON_CONTENT_CHANGE` + Service-Methoden.
    - **`MainActivity.kt` + `HttpServerModule.kt`**: `screensaver_wake_on_content_change`
      in `keyMapping` und `configKeyMapping`.
    - **Use-Case:** QR-Tablets dimmen nach Inaktivität. Wenn ein neuer QR-Code generiert
      wird (Website ändert DOM), wacht das Tablet automatisch auf.
    - **Config:** `screensaver_wake_on_content_change` (Default: false), per ADB-Intent
      oder `/api/config` + `/api/restart-ui` setzbar.
    - **Dedizierte REST-Endpunkte:** `POST /api/screensaver/wake-content/on` und
      `POST /api/screensaver/wake-content/off` — aktivieren/deaktivieren live ohne
      `restart-ui` (WebView wird neu geladen via `webViewKey` inkrement).
16. **`auto_reload` Key-Mapping (Patch 16)**:
    - **`MainActivity.kt`**: `auto_reload` → `@kiosk_auto_reload` in `keyMapping`
      und `adbConfigKeys` Array hinzugefügt.
    - **`HttpServerModule.kt`**: `auto_reload` → `@kiosk_auto_reload` in
      `configKeyMapping` hinzugefügt.
    - **`adbConfigKeys` Array** massiv erweitert — jetzt alle Config-Keys
      abgedeckt (vorher nur ~15, jetzt ~60+). Verhindert dass ADB-Intents mit
      nur Screensaver/Banner/etc.-Extras stillschweigend ignoriert werden.
17. **WebView Back-Button Position-Keys (Patch 17)**:
    - **`MainActivity.kt`**: `webview_back_button_x_percent` →
      `@kiosk_webview_back_button_x_percent` und `webview_back_button_y_percent`
      → `@kiosk_webview_back_button_y_percent` in `keyMapping` und `adbConfigKeys`
      Array hinzugefügt.
    - **`HttpServerModule.kt`**: Gleiche zwei Keys in `configKeyMapping` hinzugefügt.
    - **Problem:** Die Position-Keys existierten in `storage.ts` und
      `KioskScreen.tsx` (State + `loadSettings`), fehlten aber in den Kotlin
      Key-Mappings. Folge: ADB-Intent und REST `/api/config` ignorierten sie
      (unknownKeys), Default-Position (x=5%, y=50% = Mitte links) wurde verwendet.
    - **WebView Back-Button** ist eine RN-View (kein nativer Overlay wie der
      Home-Button). Position: `left: x%`, `top: y%`. Nur sichtbar wenn
      `canGoBack=true` (WebView hat Navigation-History). Default: x=5%, y=17%.
18. **REST PIN-Endpunkt (Patch 18)**:
    - **`KioskHttpServer.kt`**: `POST /api/pin` Endpunkt + `pinHandler` Callback
      hinzugefügt.
    - **`HttpServerModule.kt`**: `handleSetPin()` implementiert — ändert den PIN
      per REST API. Aktualisiert sowohl den ADB-PIN-Hash (`FreeKioskAdbConfig`
      SharedPreferences) als auch AsyncStorage `@kiosk_pin` (für UI-Anzeige).
    - **Body:** `{"old_pin":"1234","new_pin":"{{PIN}}"}` — wechselt PIN mit
      Verifikation des alten. Oder `{"new_pin":"{{PIN}}","force":true}` — setzt
      PIN ohne alte PIN-Prüfung (API-Key als Autorisierung).
    - **Sicherheit:** `force=true` überspringt die old_pin-Prüfung, aber der
      REST-Endpunkt erfordert immer den gültigen API-Key (`X-Api-Key` Header).
    - **Verwendung:** Wenn der ADB-PIN-Hash nicht mehr zum bekannten PIN passt
      (z.B. nach `pm clear` oder Firmware-Update), kann der PIN per REST
      zurückgesetzt werden ohne Factory Reset.
19. **Anti-Theft Alarm Service (AlarmService.kt + Master-Mute Fix)**:
    - **`AlarmService.kt`** (neu): Foreground Service in separatem Prozess
      (`android:process=":alarm"` im Manifest), spielt Diebstahl-Alarm-Sound ab.
      Läuft isoliert vom Haupt-Prozess — WebView `pauseMedia` hat keinen Einfluss.
      - Spielt `res/raw/alarm.ogg` (custom Alarm-Sound) via `MediaPlayer` in Schleife.
      - Fallback auf `ToneGenerator` (`TONE_PROP_BEEP`) falls `MediaPlayer` fehlschlägt.
      - Vibration: Waveform-Pattern `[0, 1000, 500]` in Schleife.
      - `startForeground()` wird **immer** als erstes in `onStartCommand` aufgerufen
        (auch bei STOP-Intent), um `ForegroundServiceDidNotStartInTimeException` zu
        vermeiden.
      - Actions: `ACTION_START` → `startAlarm()`, `ACTION_STOP` → `stopAlarm()` + `stopSelf()`.
    - **`AndroidManifest.xml`**: `AlarmService` als `<service>` mit
      `android:process=":alarm"`, `android:foregroundServiceType="specialUse"`,
      `android:exported="false"`.
    - **`WifiLockModule.kt` — Alarm-Integration**:
      - `startAlarm()`: Setzt Volume auf max, cleared `DISALLOW_ADJUST_VOLUME`
        (blockiert sonst alle Volume-Änderungen inkl. Unmute), cleared Master-Mute
        via `dpm.setMasterVolumeMuted(false)` + `AudioManager.ADJUST_UNMUTE` +
        Reflection (`setMasterMute(false, 0)` als Fallback). Startet `AlarmService`
        via `startForegroundService(Intent(ACTION_START))`.
      - `stopAlarm()`: Sendet `ACTION_STOP` Intent an `AlarmService`, restauriert
        Volume. Wird bei `unlockDevice()` und bei `enableWifiLock()` (neue SSIDs)
        aufgerufen. Safety-Net: `checkNetwork()` sendet immer `ACTION_STOP` wenn
        auf erlaubtem Netzwerk.
      - `lockDevice()`: `lockNow()` mit 1,5s Delay (nach Audio-Init), danach
        Master-Mute erneut gecleared. Brightness wird nach `unlockDevice()` auf 255
        restauriert.
    - **`res/raw/alarm.ogg`**: Custom Alarm-Sound (aus `alarm_sound/Tablet_ALARM.ogg`),
      wird via `android.resource://` URI im MediaPlayer geladen. Format: OGG Vorbis.
    - **Master-Mute Problem (Lenovo TB336FU / Android 16)**: Dieses Gerät hat
      `Master mute: on` auf AudioFlinger-Ebene. Ursachen: `lockNow()` aktiviert
      Master-Mute, `DISALLOW_ADJUST_VOLUME` blockiert Unmute. Lösung:
      `dpm.clearUserRestriction(DISALLOW_ADJUST_VOLUME)` +
      `dpm.setMasterVolumeMuted(false)` + `AudioManager.ADJUST_UNMUTE` auf allen
      Streams. Verifikation: `adb shell dumpsys media.audio_flinger | grep "Master mute"`
      → muss `off` sein.
    - **REST-API Nutzung**:
      ```powershell
      # WiFi-Lock aktivieren (mit erlaubten SSIDs)
      curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" `
          -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" `
          --data-binary '{"allowed_ssids":["TabConnect","office_24","WiFi"]}'
      # WiFi-Lock deaktivieren
      curl.exe -s -X POST "http://localhost:8081/api/wifi/unlock" `
          -H "X-Api-Key: {{API_KEY}}"
      # Status abfragen
      curl.exe -s "http://localhost:8081/api/wifi/lock-status" `
          -H "X-Api-Key: {{API_KEY}}"
      ```

20. **`KioskModule.kt` + `HttpServerModule.kt` + `KioskHttpServer.kt` — `POST /api/shutdown` Endpunkt**:
    Neuer REST-Endpunkt zum Herunterfahren (Power Off) der Tablets. Verwendung:
    `curl -X POST /api/shutdown` — Tablet fährt komplett herunter, startet nicht
    automatisch neu. Beim nächsten Boot (Power-Button drücken) startet FreeKiosk
    automatisch (Device Owner + Auto-Launch) und geht selbstständig in den Lock-Task.
    JS-Seite: `KioskModule.shutdown()`, `ApiService.onShutdown`, `KioskScreen.onShutdown`.
    MQTT: `shutdown` command + Discovery Button.
    **Implementierung siehe Patch 22** — die ursprüngliche `PowerManager.shutdown()`
    Reflection wurde durch den Accessibility-Service-Ansatz ersetzt.

21. **`KioskHttpServer.kt` + `HttpServerModule.kt` — `GET /api/config` Endpunkt (Patch 21)**:
    Neuer lesender REST-Endpunkt zum Auslesen der aktuell in AsyncStorage gespeicherten
    Config. Liest alle Keys aus `configKeyMapping` aus der `RKStorage` SQLite DB
    (`catalystLocalStorage` Tabelle). Zusaetzlich: `GET /api/config/:key` fuer einzelne
    Config-Keys. Response: `{"success":true,"data":{...},"timestamp":...}`.
    TabControl kann damit die Live-Config vom Tablet abrufen und `lastConfig` als
    Fallback-Cache verwenden.

22. **`FreeKioskAccessibilityService.kt` + `HttpServerModule.kt` + `AndroidManifest.xml` — Shutdown via Accessibility Service (Patch 22)**:
    Der ursprüngliche Shutdown-Endpunkt (Patch 20) funktionierte nicht auf Android 16
    Production-Builds, weil SELinux den Zugriff auf `IPowerManager.shutdown()` und
    `sys.powerctl` für `untrusted_app` blockiert — selbst als Device Owner. `dpm.reboot()`
    funktioniert (läuft im `system_server` mit `REBOOT`-Permission), aber es gibt keine
    `dpm.shutdown()`-Methode.
    **Lösung:** Shutdown über den Accessibility Service. Die App hat bereits
    `FreeKioskAccessibilityService` aktiviert (für Key/Text-Injection). Der neue Ablauf:
    (1) Lock-Task per `activity.stopLockTask()` beenden (sonst wird der Power-Dialog
    blockiert). (2) `performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)` öffnet das
    systemeigene Energie-Menü. (3) Accessibility Tree wird nach einem Button mit Text
    "Power off"/"Shut down"/"Ausschalten"/"herunterfahren" (mehrsprachig) durchsucht
    und per `ACTION_CLICK` geklickt. (4) Falls ein Bestätigungs-Dialog erscheint, wird
    auch der "OK"/"Ja"/"Confirm"-Button geklickt. Das System-UI läuft als `system_app`
    mit `REBOOT`-Permission und führt den eigentlichen Shutdown aus.
    **Fallbacks** (alle schlagen auf Android 16 fehl, aber als Absicherung für ältere
    Android-Versionen erhalten): Raw `IBinder.transact(54)` auf `IPowerManager`,
    `reboot -p` Shell-Kommando.
    **Verifikation:** Auf QR-01 ({{SERIAL_QR_01}}, TB336FU, Android 16) getestet — Tablet fährt
    sowohl im Kiosk-Modus (Lock-Task LOCKED) als auch ohne Kiosk-Modus sauber herunter.
    Nach manuellem Neustart (Power-Button) startet FreeKiosk automatisch und geht in
    den Lock-Task. `REBOOT`-Permission wurde im Manifest deklariert
    (`tools:ignore="ProtectedPermissions"`), wird aber von SELinux nicht gewährt
    (`untrusted_app` Kontext) — nur der Accessibility-Ansatz funktioniert.
23. **`HttpServerModule.kt` + `MainActivity.kt` + `BootLockActivity.kt` + `KioskHttpServer.kt` + `storage.ts` — Volume-Keys Block/Unblock via API (Patch 23)**:
    Die Lautstärketasten (Hardware) konnten bisher nur vom `AntiTheftAlarmModule`
    intern blockiert werden (`DISALLOW_ADJUST_VOLUME` User-Restriction). Wenn der
    Alarm-Stop-Pfad fehlschlug (App-Crash, Exception), blieb die Restriction
    aktiv — die Tasten waren dauerhaft blockiert, ohne Möglichkeit das per API
    zu beheben. Jetzt gibt es (analog zum Power-Button-Pattern) drei Endpunkte:
    - `GET /api/volume/keys` — liest den **tatsächlichen DPM-Restriction-State**
      (`allowed`, `restrictionActive`, `configAllowed`), nicht nur die Config.
    - `POST /api/volume/keys/block` — setzt `DISALLOW_ADJUST_VOLUME` sofort und
      persistiert `@kiosk_allow_volume_buttons=false`.
    - `POST /api/volume/keys/unblock` — cleared die Restriction und persistiert
      `@kiosk_allow_volume_buttons=true`. **Recovery-Pfad** für steckengebliebene
      Tasten nach Alarm-Crash.
    Die Restriction wird in `enableKioskRestrictions()` (MainActivity) und in
    `BootLockActivity` (beim Boot) automatisch re-applied/re-cleared, basierend
    auf dem Config-Wert. Damit ist der Zustand nach Reboot/restart-ui determiniert.
    Neue React-Methods: `setVolumeKeysBlocked(blocked, promise)`,
    `getVolumeKeysBlocked(promise)`. Config-Key `allow_volume_buttons` in
    `keyMapping` (MainActivity) und `configKeyMapping` (HttpServerModule)
    aufgenommen. Default: `true` (Tasten erlaubt) — bestehende Tablets ohne
    expliziten Config-Wert bleiben unverändert.

24. **`HttpServerModule.kt` + `MainActivity.kt` — Device Identity Config-Keys (Patch 24)**:
    Die Seriennummer und MAC-Adresse eines Tablets lassen sich aus einer Android-App
    seit Android 10 nicht mehr auslesen (`Build.SERIAL`="unknown",
    `Build.getSerial()` braucht `READ_PRIVILEGED_PHONE_STATE`). Daher werden beide
    Werte beim Provisioning per ADB ausgelesen (`getprop ro.serialno` /
    `dumpsys wifi | grep mWifiInfo`) und als Config-Keys in AsyncStorage gespeichert:
    - `device_serial` → `@kiosk_device_serial` (z.B. "{{SERIAL_QR_02}}")
    - `device_mac` → `@kiosk_device_mac` (z.B. "{{MAC_QR_02}}")
    Abruf per REST: `GET /api/config/device_serial`, `GET /api/config/device_mac`.
    Beide Keys in `configKeyMapping` (HttpServerModule.kt) und `keyMapping`
    (MainActivity.kt `applyJsonConfigToPrefs`) sowie in der ADB-Intent-Key-Liste
    (`adbConfigKeys`) aufgenommen. Geht bei Factory Reset verloren — beim
    Re-Provisioning neu setzen (ist Teil des Provisioning-Workflows).

25. **`KioskModule.kt` + `ScreenController.kt` + `ScreenSchedulerReceiver.kt` — Keyguard Fix (Patch 25, patched5)**:
    `ScreenSchedulerReceiver` hat beim Aufwecken des Bildschirms (z.B. morgens 05:30)
    nur `turnScreenOn()` aufgerufen, aber nicht geprüft, ob der Keyguard (Lock-Screen)
    aktiv ist. Auf Android 16 mit Lock-Task-Modus blieb der Bildschirm dann schwarz,
    weil der Keyguard den Wake-Up blockierte. Fix: `ScreenController.turnScreenOn()`
    ruft jetzt `KeyguardManager.requestDismissKeyguard()` via `Activity` auf, und
    `ScreenSchedulerReceiver` sendet einen Broadcast an `MainActivity`, der den
    Keyguard dismissiert. Zusätzlich prüft `KioskModule` beim `screen/on` REST-Endpunkt,
    ob der Keyguard aktiv ist und dismissiert ihn ebenfalls.

26. **`WifiLockModule.kt` — DHCP Auto-Recovery (Patch 26, patched6)**:
    Nach einem Reboot kann es vorkommen, dass das Tablet sich auf L2-Ebene mit dem
    WLAN verbindet (SSID korrekt, Supplicant state=COMPLETED), aber DHCP keine
    IP-Adresse zuweist (`CMD_IP_CONFIGURATION_LOST`). Das Tablet hat dann keine
    IP (0.0.0.0) und ist nicht per REST erreichbar, obwohl FreeKiosk läuft.
    Ursache: abgelaufene DHCP-Lease, Router antwortet nicht auf DHCPDISCOVER.
    **Fix:** `checkNetwork()` prüft jetzt zusätzlich zur SSID auch, ob eine gültige
    IP-Adresse vorliegt (`hasValidIpAddress()` via `LinkProperties.linkAddresses`).
    Wenn nach 15s Grace-Period keine IP vorhanden ist, startet `attemptDhcpRecovery()`:
    1. `DISALLOW_CONFIG_WIFI` temporär entfernen (sonst blockiert `setWifiEnabled`)
    2. WiFi off → 3s warten → WiFi on
    3. 12s warten (DHCP-Zeit geben)
    4. `DISALLOW_CONFIG_WIFI` wieder setzen
    5. Prüfen ob IP jetzt vorhanden; ggf. Retry (max 3 Versuche)
    Recovery-Variablen werden beim `enableWifiLock`/`disableWifiLock` resetet.

Die gepatchte APK ist Debug-signiert (`CN=Android Debug`). Die Original-APK ist
Rushb-signiert (`CN=Valentin GOMY`). Update nur mit Factory Reset möglich (siehe
Konvention 5).

## Konventionen für Agenten

1. **Keine Änderungen an `freekiosk-main\`**, außer es ist explizit ein Patch gewünscht.
   Upstream-Code wird primär von Rushb gepflegt.
2. Projektspezifische Config/Doku **immer** im Projekt-Root (`D:\Projekte\KIOSK-TABS\`)
   oder in neu anzulegenden Unterordnern ablegen — **niemals** in `freekiosk-main\`.
3. Bevor ADB-Befehle gegen ein Live-Tablet ausgeführt werden: `.\adb.exe devices` prüfen,
   dass genau das gewünschte Gerät verbunden ist. Keine Bulk-Operationen über alle Geräte
   ohne explizite Freigabe.
4. Device Owner setzen (`dpm set-device-owner`) **vor** dem ersten Öffnen von FreeKiosk.
   Voraussetzung: keine aktiven Accounts auf dem Tablet (Google/Samsung/Work/SIM).
   Factory Reset ist nur Fallback, falls `dpm` nach Account-Removal fehlschlägt — siehe
   `freekiosk-main/docs/installation.md`.
5. Device Owner ist **nicht** reversibel ohne Factory Reset — Änderungen an der
   Device-Owner-Konfiguration nur nach Rückfrage.
6. URLs / Endpunkte für PAT- und QR-Tablets zentral in `README_project.md` pflegen,
   nicht verstreut in Skripten hardcoden.
7. Sprache für neue Doku: Deutsch (Projektsprache). Code-Kommentare englisch, sofern
   sie im Upstream-Tree liegen.
8. **PowerShell korrumpiert Binary-Daten** bei `> file` Redirects (interpretiert als
   UTF-8-String). Für Screenshots/Binärdateien immer `adb shell screencap /sdcard/x.png`
   + `adb pull` verwenden, **niemals** `adb exec-out screencap -p > x.png` in PowerShell.
9. **`am start` mit `--activity-clear-task`** verwenden, wenn FreeKiosk schon läuft —
   sonst wird der Intent an die laufende Instanz geliefert und Extras (URL, Config)
   werden oft ignoriert.
10. **`--es pin` ist immer erforderlich** beim `am start`-Intent (auch beim ersten
    Setup). Ohne PIN kommt "PIN required for first setup".
11. REST API ist direkt übers Netzwerk erreichbar, sobald PC und Tablet im selben
    Subnetz sind (z. B. beide in `{{SUBNET}}`). Bei Subnetz-Trennung als Fallback
    `adb forward tcp:8081 tcp:8080` verwenden.
12. **`--ez kiosk_enabled true` ist zwingend erforderlich** im Config-Intent, damit die
    Android Lock-Task aktiviert wird. Ohne Lock-Task lässt sich die System-Leiste
    hochziehen und der ZUI Window-Header erscheint. Verifikation:
    `adb shell "dumpsys activity activities | grep mLockTaskModeState"` → muss `LOCKED` sein.
13. **Lenovo ZUI-Geräte** (TB336FU etc.) brauchen zusätzlichen Lockdown: ZUI Freeform
    Sidebar (`com.zui.freeform.sidebar`) und ZUI Launcher (`com.zui.launcher`)
    deaktivieren, sonst rendert ZUI einen Window-Header mit 3-Punkte-Menü, über den
    sich das FreeKiosk-Fenster verschieben und schließen lässt. Siehe `README_project.md`
    Schritt 6b. Nach Deaktivierung Reboot empfohlen, damit der Window-Manager sauber
    neu startet.
14. **REST `/api/url` Body-Parsing** ist empfindlich — PowerShell-escaped JSON mit
    Backslashes (`{\"url\":...}`) wird vom FreeKiosk-Server abgelehnt
    (`JSONException: Expected literal value at character 1 of {\`). Workaround:
    JSON in eine Datei schreiben und mit `-d "@body.json"` übergeben.
15. **`SYSTEM_ALERT_WINDOW` Permission** ist zwingend für den nativen Home-Button-Overlay
    (WindowManager `TYPE_APPLICATION_OVERLAY`). Ohne sie schlägt `showHomeButton()`
    mit `permission denied for window type 2038` fehl. Wird per
    `adb shell appops set com.freekiosk SYSTEM_ALERT_WINDOW allow` gesetzt und geht
    bei Factory Reset verloren. Siehe `README_project.md` Schritt 4c.
16. **Provisioning-Checkliste** in `README_project.md` Schritt 9 **pro Tablet**
    durchgehen — die häufigsten vergessenen Schritte sind: `SYSTEM_ALERT_WINDOW`
    Permission (4c), Accessibility Service (4d), ZUI-Komponenten deaktivieren (6e),
    `--activity-clear-task` im Config-Intent (5).
17. **Accessibility Service** muss per ADB aktiviert werden nach Device Owner:
    `adb shell settings put secure enabled_accessibility_services "com.freekiosk/com.freekiosk.FreeKioskAccessibilityService"`
    + `adb shell settings put secure accessibility_enabled 1`. Wird nach Reboot
    automatisch re-aktiviert (BootReceiver), aber das initiale Setzen muss manuell
    erfolgen. Wird in der Checkliste Schritt 4d dokumentiert.
18. **`auto_reload` (Reload on Error)** war nicht in den ADB/REST Key-Mappings
    enthalten (Patch 16). Key: `auto_reload` → `@kiosk_auto_reload`. Setzbar per
    ADB-Intent (`--es auto_reload "true"`) oder REST (`{"auto_reload":"true"}`).
19. **Screen Scheduler** benötigt JSON-Format für Rules. Standard-Config:
    Mo-Fr 22:30-05:30, Sa+So ganztägig aus:
    `[{"id":"weeknight","name":"Mo-Fr Nacht","enabled":true,"days":[1,2,3,4,5],"sleepTime":"22:30","wakeTime":"05:30"},{"id":"weekend","name":"Sa-So aus","enabled":true,"days":[0,6],"sleepTime":"00:00","wakeTime":"23:59"}]`.
    `days` = Wochentage (0=So, 1=Mo, ..., 6=Sa). Setzbar per ADB-Intent oder REST
    config + restart-ui. Gilt für alle Tablets (QR + PAT).
20. **`allow_power_button "false"`** blockiert das Power-Menü (Global Actions) via
    `setLockTaskFeatures(flags=0)`. Verifikation in logcat:
    `Lock task features set: blockPowerButton=true`. Default ist `true` (Power-Menu
    erlaubt) — muss explizit auf `false` gesetzt werden.
21. **Brightness-Werte verwenden 0-1 Skala** (nicht 0-255!): `default_brightness=1`
    = 100%, `screensaver_brightness=0.01` = 1% (Hardware-Minimum des TB336FU).
    `screensaver_brightness=0` = komplett
    schwarz (schwarzes Overlay statt Helligkeits-Reduktion). Verifikation in logcat:
    `BrightnessEvent: brt=0.01 (17.0%)` beim Screensaver-Activate. Werte <0.01
    werden vom Display auf 0.01 gecappt (Hardware-Limit).
22. **`keep_screen_on=true` + Screensaver** sind kompatibel: `keep_screen_on` hält das
    Display an (via `FLAG_KEEP_SCREEN_ON`), der Screensaver dimmt die Helligkeit per
    `RNBrightness.setBrightnessLevel()` auf `screensaver_brightness` runter. Ohne
    `keep_screen_on` würde das System das Display nach `screen_off_timeout` abschalten
    — wir haben `screen_off_timeout=2147483647` (max), also schaltet es sich nie ab.
    Der FreeKiosk-Screensaver ist die einzige Dimm-Methode.
23. **QR-Tablet Screensaver-Workflow**: `screensaver_delay=120000` (2 Min) +
    `screensaver_brightness=0.01` (1%, Hardware-Minimum) + `screensaver_wake_on_content_change=true`.
    Ablauf: Tablet dimmt nach 2 Min Inaktivität auf 1% → Website generiert neuen
    QR-Code (DOM ändert sich) → `MutationObserver` erkennt Änderung → Tablet wacht
    auf (100% Helligkeit) → nach 2 Min ohne weitere Änderung → wieder dimmen.
24. **WiFi-Lock** muss als separater Schritt nach dem Config-Intent aktiviert werden
    (nicht per ADB-Intent, sondern per REST `/api/wifi/lock`). Grund: Der WiFi-Lock
    nutzt `DevicePolicyManager` APIs direkt, nicht den AsyncStorage-Config-Pfad.
    Siehe Workflow Schritt 4.
25. **Immersive Mode (`policy_control`) muss nach Reboot verifiziert werden**:
    `adb shell settings get global policy_control` muss `immersive.full=com.freekiosk`
    zurückgeben. Wenn `null`, ist die Android Navigation Bar (Gesture Bar) sichtbar
    und der Benutzer kann durch Hochziehen von unten eine Zurück-Taste erreichen.
    Neu setzen: `adb shell settings put global policy_control immersive.full=com.freekiosk`.
    Sollte eigentlich persistieren, aber bei manchen Geräten/Reboots verloren.
26. **ZUI-Komponenten müssen nach Reboot verifiziert werden**: `pm disable-user`
    sollte eigentlich persistieren, aber bei ZUI-Geräten (TB336FU) wurden Fälle
    beobachtet wo ZUI Launcher/Sidebar nach Reboot wieder aktiv waren. Prüfen:
    `adb shell pm list packages -d | grep zui` — muss `com.zui.freeform.sidebar`
    und `com.zui.launcher` enthalten. Falls nicht, neu deaktivieren (siehe Schritt 6b).
27. **WebView Back-Button vs. Home-Button**: Zwei verschiedene Features:
    - **WebView Back-Button** (`webview_back_button_enabled`): RN-View, navigiert
      in der WebView-History zurück (`goBack()`). Nur sichtbar wenn `canGoBack=true`.
      Position: `webview_back_button_x_percent` / `webview_back_button_y_percent`
      (Default: x=5%, y=17% = oben links).
    - **Home-Button** (`home_button_enabled`): Nativer Overlay (WindowManager),
      navigiert zur Base URL. Immer sichtbar. Position: `home_button_x_percent` /
      `home_button_y_percent` (Default: x=95%, y=17% = oben rechts).
    Beide können gleichzeitig aktiv sein — Back-Button oben links, Home-Button
    oben rechts.
28. **PIN und REST API Key werden nur beim App-Start übernommen**: Der `--es pin`
    und `--es rest_api_key` aus dem ADB-Intent werden beim `onCreate`/`onNewIntent`
    in AsyncStorage geschrieben. Der HTTP-Server liest den API-Key aber nur beim
    Start — ein `restart-ui` aktualisiert den laufenden Server-Key nicht. Um den
    API-Key zu ändern: APK neu installieren (triggers App-Neustart) oder
    `adb reboot` (App startet via `auto_launch=true` neu). Der alte API-Key
    funktioniert weiterhin bis die App komplett neu gestartet wurde.
    **PIN ändern:** Per REST `POST /api/pin` mit `{"old_pin":"...","new_pin":"..."}`
    oder `{"new_pin":"...","force":true}` (Patch 18). Aktualisiert sofort den
    ADB-PIN-Hash und AsyncStorage — kein App-Neustart nötig.

## Workflow: APK-Update (gepatchte APK neu installieren)

Nach jedem APK-Update (`adb install -r`) muss die App sauber neu gestartet werden.
Android killt den Prozess bei `-r` und versucht, die Activity wiederherzustellen —
aber unter Lock-Task bleibt sie oft in einem halbtoten Zustand hängen (weißer
Bildschirm, REST-Server läuft aber UI rendert nicht).

**Zuverlässiger Post-Install-Workflow:**

```powershell
# 1. APK installieren
.\adb.exe install -r freekiosk-v1.2.20-beta.3-patched6.apk

# 2. App sauber killen (Lock-Task verhindert nicht force-stop bei Device Owner)
.\adb.exe shell am force-stop com.freekiosk
Start-Sleep -Seconds 3

# 3. App neu starten mit vollem Config-Intent
#    WICHTIG: --activity-clear-task verhindert, dass alte Activity-Instanz
#    wiederhergestellt wird. pin muss dem aktuell gesetzten PIN entsprechen.
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "TABLET_URL" --es pin "{{PIN}}" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es screensaver_enabled "true" --es screensaver_delay "120000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. System-Settings verifizieren + ZUI Battery-Optimierung setzen (nach Reboot evtl. verloren)
.\adb.exe shell settings get global policy_control   # muss immersive.full=com.freekiosk sein
.\adb.exe shell "pm list packages -d" | Select-String zui  # muss zui.launcher + zui.freeform.sidebar enthalten
# ZUI Battery-Optimierung für QR-Tablets (Dauerbetrieb am Strom — schont den Akku)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization on"

# 5. WiFi-Lock reaktivieren (geht durch App-Neustart verloren)
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json

# 6. Verifikation
Start-Sleep -Seconds 10
curl.exe -s "http://localhost:8081/api/status" '-H' 'X-Api-Key: {{API_KEY}}' | Select-String "currentUrl|brightness|screensaverActive"
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"  # muss LOCKED sein
```

> **Wichtig:** Nach `force-stop` + `am start` startet die App komplett neu —
> der REST-Server wird neu initialisiert und liest den API-Key aus dem Intent.
> Der alte API-Key funktioniert nicht mehr, der neue aus dem Intent ist aktiv.
> Ein `restart-ui` nach APK-Update ist **nicht nötig** und sollte vermieden werden —
> die pending Config wird beim App-Start automatisch angewendet.

---

## Workflow: Tablet provisionieren (QR + PAT)

### Schritt 1: App starten mit Config-Intent (alle Tablets)

**Immer** als initiale Base URL `https://www.med-mv.de/qr-code-scanner/` setzen —
egal ob QR- oder PAT-Tablet. Der QR-Code-Scanner läuft auf `med-mv.de` und ist
 die neutrale Startseite.

Der Config-Intent startet die App und setzt die Grundkonfiguration (URL, PIN,
REST-API, Kiosk-Modus). **Achtung:** Der Intent schreibt die Config-Keys in
`FreeKioskPendingConfig` SharedPreferences, aber die App liest beim ersten
Start nicht zuverlässig alle Keys davon in AsyncStorage. Daher ist **Schritt 2
zwingend erforderlich** — dort werden alle Config-Keys per REST direkt in
AsyncStorage geschrieben.

```powershell
# Vollständigen Intent aus "Wichtige Befehle → ADB Provisioning" verwenden.
# Mindestens url, pin, rest_api_key, kiosk_enabled müssen im Intent stehen,
# damit die App startet und der REST-Server erreichbar ist.
```

### Schritt 2: Full Config per REST setzen (alle Tablets) — ZWINGEND!

Der ADB-Intent aus Schritt 1 schreibt Config-Keys unzuverlässig in AsyncStorage.
Daher müssen **alle** Config-Keys zusätzlich per REST `/api/config` gesetzt und
mit `/api/restart-ui` angewendet werden. Dies schreibt direkt in die RKStorage
SQLite DB und ist zuverlässig.

```powershell
.\adb.exe forward tcp:8081 tcp:8080
Start-Sleep -Seconds 10  # REST-Server braucht einen Moment nach App-Start

# Alle Config-Keys als JSON (gleiche Keys wie im ADB-Intent):
$config = @'
{"allow_power_button":"false","back_button_mode":"immediate","home_button_enabled":"true","home_button_x_percent":"95","home_button_y_percent":"17","webview_back_button_enabled":"true","webview_back_button_x_percent":"5","webview_back_button_y_percent":"17","banner_enabled":"true","banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":"25","banner_show_icon":"true","screensaver_enabled":"true","screensaver_delay":"120000","screensaver_brightness":"0.01","screensaver_wake_on_content_change":"true","screensaver_inactivity_enabled":"true","screensaver_type":"dim","url_filter_enabled":"true","url_filter_mode":"whitelist","url_filter_list":"[\"*informme.info/*\",\"*med-mv.de/*\"]","url_filter_show_feedback":"true","screen_scheduler_enabled":"true","screen_scheduler_rules":"[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]","screen_scheduler_wake_on_touch":"true","keep_screen_on":"true","pause_web_media_when_hidden":"true","default_brightness":"1","brightness_management_enabled":"false","auto_brightness_enabled":"false","status_bar":"false","status_bar_show_battery":"false","status_bar_show_wifi":"false","status_bar_show_time":"false","pin_mode":"numeric","pin_max_attempts":"3","allow_notifications":"false","allow_system_info":"false","auto_launch":"true","auto_relaunch":"true","auto_reload":"true"}
'@
$config | Out-File "$env:TEMP\config.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/config" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@$env:TEMP\config.json"
Remove-Item "$env:TEMP\config.json"

# Config anwenden:
curl.exe -s -X POST "http://localhost:8081/api/restart-ui" '-H' 'X-Api-Key: {{API_KEY}}'
```

> **Wichtig:** Dieser Schritt ist **nicht optional** — ohne ihn fehlen
> Config-Keys wie `allow_power_button`, `home_button_enabled`, `banner_enabled`,
> `screensaver_*`, `screen_scheduler_*` etc. Der ADB-Intent allein reicht nicht!
>
> **Hinweis:** `restart-ui` startet die Activity nicht neu — es schreibt die
> pending Config-Keys direkt in AsyncStorage (RKStorage SQLite) und emittet ein
> `freekiosk_config_changed` JS-Event. Die App läuft ununterbrochen weiter.
> Falls die UI danach trotzdem nicht reagiert: `adb shell am force-stop
> com.freekiosk` + `am start` mit vollem Intent (siehe "Workflow: APK-Update").

### Schritt 3: QR-Tablets — Base URL durch JS ermitteln und ersetzen

Für **QR-01..QR-03** muss die Base URL von der QR-Scanner-Seite auf die
echte `tabletLinkId`-URL geändert werden. Der Workflow:

1. Tablet zeigt `https://www.med-mv.de/qr-code-scanner/` (aus Schritt 1)
2. Patient scannt QR-Code → Website leitet auf `med.informme.info/tablet3?tabletLinkId=...` weiter
3. **Aktuelle URL per JS auslesen** (die WebView zeigt jetzt die tabletLinkId-URL):

```powershell
# JS injizieren, die echte URL an logcat senden:
'{"code":"window.ReactNativeWebView.postMessage(\"KIOSK_URL:\" + window.location.href);","persistent":false}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/inject-js" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
.\adb.exe shell "logcat -d | grep KIOSK_URL"
```

4. **Base URL durch ADB-Intent ersetzen** mit der ausgelesenen URL:

Vollständigen Intent aus "Wichtige Befehle → ADB Provisioning" verwenden,
nur `--es url` durch die ausgelesene `tabletLinkId`-URL ersetzen. Alle anderen
Settings (Screensaver, Banner, URL-Filter, Screen Scheduler, etc.) beibehalten.

5. **Verifikation**:

```powershell
curl.exe -s "http://localhost:8081/api/status" '-H' 'X-Api-Key: {{API_KEY}}' | Select-String "currentUrl"
```

> **Wichtig:** Die `tabletLinkId`-URL nicht direkt als erste Base URL setzen —
> das Backend der Website mag das nicht, wenn das Display-Tablet selbst die
> Patienten-URL lädt. Erst den QR-Scanner zeigen, dann per JS die URL ermitteln,
> dann per ADB-Intent als Base URL setzen.

### Schritt 3a: WLAN-Netzwerke konfigurieren (alle Tablets)

Bevor der WiFi-Lock aktiviert wird, müssen alle WLAN-Netzwerke auf dem Tablet
 gespeichert sein — der WiFi-Lock sperrt nämlich WiFi-Config-Änderungen
 (`DISALLOW_CONFIG_WIFI`). Danach können keine neuen Netzwerke mehr hinzugefügt
 werden.

**Wichtig: MAC-Randomisierung deaktivieren!** Android 16 verwendet standardmäßig
 randomisierte MACs pro SSID (rotieren alle ~24h). Für static DHCP im Router
 brauchen wir die Hardware-MAC. Daher `-r none` bei `connect-network`.

```powershell
# 1. Falls bereits Netzwerke mit Randomisierung gespeichert sind: erst entfernen
.\adb.exe shell cmd wifi list-networks
# Alte Netzwerke entfernen (Network ID aus obiger Liste):
.\adb.exe shell cmd wifi forget-network 0
.\adb.exe shell cmd wifi forget-network 1
# ...usw.

# 2. office_24 hinzufügen (Haupt-WLAN, -r none = Hardware-MAC)
.\adb.exe shell 'cmd wifi connect-network office_24 wpa2 "{{WLAN_PASSWORT_OFFICE24}}" -r none'
Start-Sleep -Seconds 3

# 3. TabConnect hinzufügen (Backup-WLAN, noch nicht in Reichweite — das ist OK)
.\adb.exe shell 'cmd wifi connect-network TabConnect wpa2 "{{WLAN_PASSWORT_TABCONNECT}}" -r none'
Start-Sleep -Seconds 3

# 4. Verifikation
.\adb.exe shell cmd wifi list-networks
# Erwartet: office_24 + TabConnect als wpa2-psk

# 5. Hardware-MAC auslesen (für static DHCP im Router)
.\adb.exe shell "dumpsys wifi | grep 'mWifiInfo'" | Select-String 'MAC: \S+'
# Diese MAC ist die Hardware-MAC (nicht randomisiert) und bleibt fix.
```

> **WLAN-Credentials:**
> - `office_24`: PW `{{WLAN_PASSWORT_OFFICE24}}`
> - `TabConnect`: PW `{{WLAN_PASSWORT_TABCONNECT}}` (noch nicht aktiv, wird später eingerichtet)
>
> **MAC-Randomisierung:** `-r none` = Hardware-MAC (fix, für static DHCP).
> `-r persistent` = randomisierte MAC pro SSID (rotiert alle 24h, nicht für static DHCP).
> `-r auto` = System-Default (meist persistent randomization).
>
> **WiFi-Lock beachten:** Sobald der WiFi-Lock aktiv ist, können keine WLAN-Netzwerke
> mehr hinzugefügt oder geändert werden. Daher immer zuerst Netzwerke speichern,
> dann WiFi-Lock aktivieren. Bei nachträglichen Änderungen: erst `POST /api/wifi/unlock`,
> dann Netzwerk hinzufügen, dann `POST /api/wifi/lock` mit aktualisierter SSID-Liste.

### Schritt 4a: WiFi-Lock aktivieren (alle Tablets)

Nachdem alle WLAN-Netzwerke gespeichert sind, WiFi-Lock aktivieren.
Das sperrt das Tablet bei WLAN-Verlust (`dpm.lockNow()`), blockiert das Power-Menü
und verhindert WiFi-Config-Änderungen (`DISALLOW_CONFIG_WIFI`).

```powershell
# WiFi-Lock aktivieren (alle erlaubten SSIDs)
$body = '{"allowed_ssids":["office_24","WiFi","TabConnect"]}'
$body | Out-File "$env:TEMP\wifilock.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@$env:TEMP\wifilock.json"
Remove-Item "$env:TEMP\wifilock.json"

# Verifikation
curl.exe -s "http://localhost:8081/api/wifi/lock-status" '-H' 'X-Api-Key: {{API_KEY}}'
```

Erwartet: `lockActive: true`, `disallowConfigWifi: true`, `isOnAllowedNetwork: true`,
`allowed_ssids` enthält alle drei SSIDs.

### Schritt 5: System-Settings & ZUI-Lockdown (alle Tablets) — NACH App-Neustarts!

**Wichtig:** Diese Einstellungen müssen **als letztes** gesetzt werden — nach allen
`force-stop` + `am start` Zyklen (Schritte 1-4a). Der Grund: `pm disable-user`
und `settings put` können bei ZUI-Geräten durch App-Neustarts oder Reboots
verloren gehen. Wenn sie vor dem Lock-Task-Fix gesetzt werden, sind sie danach weg.

```powershell
# Android-System-Settings (QR-Tabs: Display immer an)
.\adb.exe shell settings put global stay_on_while_plugged_in 3  # QR: immer an am Strom
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647  # QR: max (immer an)
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk

# Lenovo ZUI Lockdown (sonst Window-Header mit 3-Punkte-Menü sichtbar)
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0

# ZUI Battery-Optimierung für QR-Tablets (Dauerbetrieb am Strom — schont den Akku)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization on"

# Verifikation
.\adb.exe shell settings get global policy_control  # muss immersive.full=com.freekiosk
.\adb.exe shell "pm list packages -d | grep zui"  # muss sidebar + launcher enthalten
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization status"
```

**PAT-Tags abweichende System-Settings:**

PAT-Tags werden herumgereicht und brauchen vollen Akku. Daher abweichend:

```powershell
# PAT-Tabs: ZUI Battery-Optimierung AUS (brauchen vollen Akku, werden herumgereicht)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization off"
# Alle anderen Settings (stay_on_while_plugged_in, screen_off_timeout, Rotation, Immersive Mode,
# ZUI Lockdown) gleich wie QR — Display bleibt an, Screensaver dimmt nach 30 Min auf 1%.
```

> **Wichtig:** Diese Einstellungen können nach einem Reboot verloren gehen.
Bei der Verifikation nach Reboot (Schritt 11/12) mitprüfen.

### Schritt 6: PAT-Tablets — Base URL bleibt QR-Scanner

Für **PAT-01..PAT-03** bleibt die Base URL auf
`https://www.med-mv.de/qr-code-scanner/` — kein Schritt 3 nötig. Die
Patienten-Tablets zeigen den QR-Scanner, der Patient scannt mit seinem eigenen
Gerät und füllt das Formular dort aus.

### PAT-Tablets — Display-Steuerung & Lock-Taste

Patienten-Tablets werden selten benötigt. Das Display soll nicht permanent an sein,
aber das Tablet muss per REST/ADB fernsteuerbar bleiben. Wenn ein PAT-Tab gebraucht
wird, wird er manuell entsperrt (Power-Button oder REST `/api/screen/on`).

**Config-Keys für PAT-Tablets (abweichend vom QR-Default):**

| Key | Wert | Bedeutung |
|---|---|---|
| `allow_power_button` | `false` | **Power-Menu blockiert!** Patienten können das Tablet sonst herunterfahren/neustarten. Display kann weiterhin per REST `/api/screen/on` geweckt werden. |
| `keep_screen_on` | `true` | Display bleibt an (wie QR-Tabs). Screensaver dimmt nach 30 Min auf 1%. |
| `screensaver_enabled` | `true` | Screensaver aktiv — als Fallback falls Tab nicht manuell gesperrt wird |
| `screensaver_delay` | `1800000` | 30 Min Inaktivität bevor Screensaver — Patienten haben das Tablet in der Hand zum Ausfüllen, Screensaver soll nicht zu früh greifen. Danach manuell sperren per Power-Button. |
| `screensaver_brightness` | `0.01` | 1% Helligkeit (Hardware-Minimum) |
| `auto_wake_on_screen_off` | `false` | **Darf nicht `true` sein!** Sonst weckt die App das Display sofort wieder, sobald man es per Power-Button sperrt. REST `/api/screen/on` funktioniert auch ohne dieses Flag (über `adb shell input keyevent KEYCODE_WAKEUP`). |
| `battery_protection_mode` | — | **Veraltet — keine Auswirkung!** ZUI Battery-Optimierung wird über den `zuibattery` HAL-Service gesteuert, nicht über dieses Global-Setting. Siehe Abschnitt "ZUI Battery-Optimierung" unten. PAT-Tablets: `BatteryOptimization off`. QR-Tablets: `BatteryOptimization on`. |
| `banner_font_size` | `25` | Schriftgröße im Anti-Diebstahl-Banner. Rechts im Banner wird zusätzlich der Akkustand angezeigt (alle 5s aktualisiert): "Lädt 23%" (grün beim Laden), "23%" (weiß), "18%" (rot bei ≤20% und nicht am Laden). |

> **Screen Scheduler** ist für PAT-Tabs gleich wie für QR-Tabs (Mo-Fr 22:30-05:30,
> Sa+So ganztägig aus). Das sichert, dass vergessene PAT-Tabs spätestens um 22:30
> ausgehen und am Wochenende gar nicht erst aufwachen.

**Per REST API setzen (nach Provisioning mit QR-Default-Intent):**

```powershell
'{"allow_power_button":"false","keep_screen_on":"true","screensaver_enabled":"true","screensaver_delay":"1800000","screensaver_brightness":"0.01","auto_wake_on_screen_off":"false"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/config" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
curl.exe -s -X POST "http://localhost:8081/api/restart-ui" '-H' 'X-Api-Key: {{API_KEY}}'
```

> **Hinweis:** Falls die UI nach `restart-ui` nicht reagiert (weißer Bildschirm):
> `adb shell am force-stop com.freekiosk` + `am start` mit vollem Intent
> (siehe "Workflow: APK-Update").

> **Wichtig:** `keep_screen_on: true` hält das Display aktiv. Der Screensaver
> dimmt nach 30 Min auf 1%, aber FreeKiosk hält intern `FLAG_KEEP_SCREEN_ON`
> aktiv — JS-Timer, REST Server und ADB (USB + WiFi) laufen **weiterhin**.
>
> **Screensaver vs. Screen Scheduler:** Der Screensaver (30 Min) ist ein Fallback
> für den Fall, dass ein PAT-Tab nach Gebrauch nicht manuell gesperrt wird. Nach
> 30 Min Inaktivität dimmt das Display auf 1%. Der Screen Scheduler schaltet es
> dann um 22:30 Uhr komplett aus.

**Display aus der Ferne steuern (REST):**

| Endpunkt | Wirkung |
|---|---|
| `POST /api/screen/on` | Display anschalten |
| `POST /api/screen/off` | Display ausschalten (dimmen) |
| `POST /api/wake` | Aus Screensaver aufwecken |
| `POST /api/reboot` | Tablet neu starten (Device Owner) |
| `POST /api/shutdown` | Tablet herunterfahren / power off (Device Owner) |
| `POST /api/lock` | Device locken |
| `POST /api/reload` | WebView neu laden |
| `POST /api/restart-ui` | FreeKiosk UI neu starten |
| `POST /api/pin` | PIN ändern (Body: `{"old_pin":"...","new_pin":"..."}` oder `{"new_pin":"...","force":true}`) |

```powershell
# Display anschalten
curl.exe -s -X POST "http://TABLET_IP:8080/api/screen/on" -H "X-Api-Key: {{API_KEY}}"

# Display ausschalten
curl.exe -s -X POST "http://TABLET_IP:8080/api/screen/off" -H "X-Api-Key: {{API_KEY}}"

# Reboot
curl.exe -s -X POST "http://TABLET_IP:8080/api/reboot" -H "X-Api-Key: {{API_KEY}}"

# Shutdown (Power Off — für Transport / Einsammeln der Tablets)
curl.exe -s -X POST "http://TABLET_IP:8080/api/shutdown" -H "X-Api-Key: {{API_KEY}}"

# Alle Tablets herunterfahren (vor dem Transport)
$ips = @("{{IP_QR_01}}","{{IP_QR_02}}","{{IP_QR_03}}","{{IP_PAT_01}}","{{IP_PAT_02}}","{{IP_PAT_03}}")
foreach ($ip in $ips) {
    Write-Output "Shutting down $ip..."
    curl.exe -s --connect-timeout 5 -X POST "http://${ip}:8080/api/shutdown" -H "X-Api-Key: {{API_KEY}}"
}
```

> **Shutdown vs. Reboot:** `shutdown` fährt das Tablet komplett herunter (Power Off).
> Es startet nicht automatisch neu — muss manuell per Power-Button eingeschaltet werden.
> Beim nächsten Boot startet FreeKiosk automatisch (Device Owner + Auto-Launch).
> Der Anti-Theft-Alarm ist während des Shutdowns nicht aktiv — erst nach dem nächsten Boot.

> **ADB über WiFi:** Solange das Tablet im WLAN ist, läuft ADB über Netzwerk
> (`adb connect TABLET_IP:5555`). REST API ist direkt über `http://TABLET_IP:8080`
> erreichbar. Beide funktionieren auch bei ausgeschaltetem Display.

**Screen Scheduler (Standard für alle Tablets)**

Alle Tablets (QR + PAT) haben den Screen Scheduler aktiviert:
- **Mo–Fr**: 22:30 Uhr → Display aus (Sleep), 05:30 Uhr → Display an (Wake)
- **Sa + So**: Display **ganztägig aus** (00:00–23:59 Sleep-Window)
- **Wake-on-Touch**: Display lässt sich durch Berührung vorzeitig aufwecken

Bei den QR-Tabs sichert das, dass die Displays nachts und am Wochenende aus sind.
Bei den PAT-Tabs sichert es, dass vergessene (nicht gesperrte) Displays spätestens
um 22:30 Uhr ausgehen und am Wochenende gar nicht erst aufwachen — zusätzlich
zur manuellen Lock-Taste. Wenn ein PAT-Tab gebraucht wird, wird er manuell
entsperrt (Power-Button oder REST `/api/screen/on`).

Der Screen Scheduler wird bereits im vollständigen Config-Intent (Schritt 1)
mitgesetzt (`--es screen_scheduler_*`). Für nachträgliche Änderungen per REST:

```powershell
# Screen Scheduler: Mo-Fr 22:30-05:30, Sa/So ganztägig aus
'{"screen_scheduler_enabled":"true","screen_scheduler_rules":"[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]","screen_scheduler_wake_on_touch":"true"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/config" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
curl.exe -s -X POST "http://localhost:8081/api/restart-ui" '-H' 'X-Api-Key: {{API_KEY}}'
```

### QR-Code-Scanner Konfiguration

Der QR-Scanner auf `med-mv.de/qr-code-scanner/` nutzt `html5-qrcode` mit
**Rückkamera** (`facingMode: "environment"`). Der Scanner ist als Fullscreen-Overlay
implementiert, bei erfolgreichem Scan wird zur gescannten URL navigiert.

```html
<!-- html5-qrcode mit Rückkamera (kein Camera-Dropdown, kein File-Upload) -->
<script src="https://unpkg.com/html5-qrcode"></script>
<script>
  var html5Qrcode = new Html5Qrcode("reader");
  html5Qrcode.start(
    { facingMode: "environment" },
    { fps: 10, qrbox: {width: 250, height: 250} },
    function(decodedText) {
      html5Qrcode.stop();
      window.location.href = decodedText;
    },
    function(error) { /* per-frame scan failure, ignore */ }
  ).catch(function(err) { console.error("Camera start failed:", err); });
</script>
```

## Workflow: WiFi-Lock (Tablet bei WLAN-Verlust sperren)

Das Tablet sperrt sich automatisch (`dpm.lockNow()`), wenn es das erlaubte WLAN
verlässt. Zusätzlich wird `DISALLOW_CONFIG_WIFI` gesetzt — niemand kann manuell
ein anderes WLAN konfigurieren. Das Tablet ist dann außerhalb der Praxis
effektiv unbrauchbar: kein Internet, Display gesperrt, Kiosk-Mode aktiv.

### REST Endpunkte

| Endpunkt | Methode | Beschreibung |
|---|---|---|
| `/api/wifi/lock` | POST | WiFi-Lock aktivieren + erlaubte SSIDs setzen |
| `/api/wifi/unlock` | POST | WiFi-Lock deaktivieren + Restrictions aufheben |
| `/api/wifi/allow` | POST | SSID zur erlaubten Liste hinzufügen (auch bei aktivem Lock) |
| `/api/wifi/lock-status` | GET/POST | Aktuelle WiFi-Lock-Config + Verbindungsstatus |

### WiFi-Lock aktivieren

```powershell
# Erlaubte SSIDs als JSON-Array
'{"allowed_ssids":["office_24","Praxis-WLAN"]}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
```

### SSID hinzufügen (z.B. neues WLAN)

```powershell
'{"ssid":"Gast-WLAN"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/allow" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
```

### WiFi-Lock deaktivieren

```powershell
curl.exe -s -X POST "http://localhost:8081/api/wifi/unlock" '-H' 'X-Api-Key: {{API_KEY}}'
```

### Status auslesen

```powershell
curl.exe -s "http://localhost:8081/api/wifi/lock-status" '-H' 'X-Api-Key: {{API_KEY}}'
```

Response:
```json
{
  "lockActive": true,
  "disallowConfigWifi": true,
  "currentSsid": "office_24",
  "isConnected": true,
  "isOnAllowedNetwork": true,
  "allowed_ssids": ["office_24", "Praxis-WLAN"]
}
```

### Was passiert bei WLAN-Verlust

1. WiFi-Monitor (`ConnectivityManager.NetworkCallback`) erkennt Disconnect
2. 3 Sekunden Wartezeit (für kurze Unterbrechungen / Roaming)
3. `setLockTaskFeatures` ohne `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` → Power-Menü blockiert
4. `dpm.lockNow()` — Display sperren
5. `SCREEN_ON`/`USER_PRESENT` BroadcastReceiver registriert: bei jedem Aufwecken
   sofort `lockNow()` + `ScreenController.turnScreenOff()` + Brightness=0
6. `DISALLOW_CONFIG_WIFI` verhindert WLAN-Änderung
7. Kiosk-Mode (Lock-Task) bleibt aktiv
8. Tablet ist unbrauchbar bis es wieder ein erlaubtes WLAN findet

> **Power-Button:** Der Power-Button kann das Display kurz aufwecken lassen
> (Android limitation — `SCREEN_ON` feuert), aber der BroadcastReceiver sperrt
> es sofort wieder + dimmt auf Brightness=0. Ein längeres Drücken (Power-Menü)
> wird via `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` blockiert.

### Wiederherstellung

- **Ins erlaubte WLAN zurückkehren** → Tablet verbindet sich automatisch →
  `unlockDevice()` stellt Power-Button + Features wieder her
- **REST `/api/wifi/unlock`** → hebt Lock + Restrictions + Power-Button-Block auf
- **ADB-USB** → immer möglich, auch offline → WiFi-Lock deaktivieren oder Config überschreiben
- **ADB Intent** → `--es wifi_lock_enabled "false"` (falls via Intent konfiguriert)

> **Voraussetzung:** Device Owner Mode. Der WiFi-Lock nutzt `DevicePolicyManager`
> APIs (`addUserRestriction`, `clearUserRestriction`, `lockNow`,
> `setLockTaskFeatures`). Funktioniert nur wenn FreeKiosk Device Owner ist.

## Workflow: Anti-Theft Banner (Diebstahl-Warnung auf Display)

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
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
```

### Banner ausblenden

```powershell
curl.exe -s -X POST "http://localhost:8081/api/banner/hide" '-H' 'X-Api-Key: {{API_KEY}}'
```

### Parameter

| Parameter | Typ | Default | Beschreibung |
|---|---|---|---|
| `banner_enabled` | bool | `false` | Banner anzeigen/verstecken |
| `banner_text` | string | `"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert."` | Anzeigetext |
| `banner_font_size` | number | `25` | Schriftgröße in sp (Icon wird 2.5× dieser Größe gerendert, min. 36px) |
| `banner_show_icon` | bool | `true` | Diebstahl-Symbol anzeigen (aus `icons/tablet_anti_thef_plain.svg`) |

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

### Config via `/api/config` + `/api/restart-ui`

Alternativ können die Banner-Keys auch über den generischen Config-Endpunkt
gesetzt werden (wirkt erst nach `restart-ui`):

```powershell
'{"banner_enabled":"true","banner_text":"Diebstahl zwecklos.","banner_font_size":"25","banner_show_icon":"true"}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/config" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
curl.exe -s -X POST "http://localhost:8081/api/restart-ui" '-H' 'X-Api-Key: {{API_KEY}}'
```

> **Hinweis:** Der Banner wird als inline React Native View gerendert (kein
> `WindowManager` Overlay mehr). Es wird **keine** `SYSTEM_ALERT_WINDOW` Permission
> für den Banner benötigt (nur noch für den Home-Button).

## Workflow: Aktuell angezeigte URL auslesen

Die REST API `/api/status` zeigt die `currentUrl` aus der WebView. Bei Redirects
oder Iframes kann diese von der sichtbaren URL abweichen. Zuverlässige Methoden:

1. **REST API** (Top-Level WebView URL):
```powershell
curl.exe -s "http://localhost:8081/api/status" '-H' 'X-Api-Key: {{API_KEY}}' | Select-String "currentUrl"
```

2. **Screenshot** (sichtbare Seite):
```powershell
.\adb.exe shell screencap -p /sdcard/screen.png
.\adb.exe pull /sdcard/screen.png "$env:TEMP\kiosk_screen.png"
.\adb.exe shell rm /sdcard/screen.png
```

3. **JS-Injection** (echte window.location.href):
```powershell
'{"code":"window.ReactNativeWebView.postMessage(\"KIOSK_URL:\" + window.location.href);","persistent":false}' | Out-File body.json -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/inject-js" '-H' 'X-Api-Key: {{API_KEY}}' -H "Content-Type: application/json" -d "@body.json"
Remove-Item body.json
.\adb.exe shell "logcat -d | grep KIOSK_URL"
```

## Offene Punkte / TODO

- [x] QR-01 provisioniert (gepatchte APK v1.2.20-beta.3-patched, Debug-signiert)
- [x] QR-02 provisioniert (gepatchte APK, Debug-signiert, alle Features aktiv)
- [x] `/api/config` + `/api/restart-ui` Endpunkte implementiert und getestet
- [x] WebView-Navigation + Code-Injection Endpunkte (`/api/webview/*`, `/api/inject-css`, `/api/inject-js`) implementiert und getestet
- [x] Home-Button Feature + `/api/home` Endpunkt implementiert und getestet
- [x] Nativer Home-Button-Overlay (WindowManager) — Touch funktioniert über WebView
- [x] WiFi-Lock Feature (`/api/wifi/lock`, `/api/wifi/unlock`, `/api/wifi/allow`, `/api/wifi/lock-status`) implementiert und getestet
- [x] WiFi-Lock Power-Button-Schutz (`setLockTaskFeatures` + `SCREEN_ON` Receiver) implementiert und getestet
- [x] Anti-Theft Banner (`/api/banner`, `/api/banner/hide`) implementiert und getestet
- [x] Screensaver Wake-on-Content-Change (MutationObserver) implementiert und auf QR-02 aktiv
- [x] `auto_reload` Key-Mapping (Patch 16) — ADB + REST
- [x] `adbConfigKeys` Array erweitert (alle ~60 Config-Keys abgedeckt)
- [x] Accessibility Service in Provisioning-Doku aufgenommen (Schritt 4d)
- [x] Provisioning-Checkliste in README_project.md dokumentiert (Schritt 9)
- [x] `SYSTEM_ALERT_WINDOW` Permission in Doku aufgenommen (Schritt 4c)
- [x] Brightness 0-1 Skala in Doku korrigiert (Konvention 21)
- [x] Screen Scheduler in Doku korrigiert (Mo-Fr 22:30-05:30, Sa+So ganztägig aus)
- [x] WiFi-Lock auf QR-02 aktiviert (SSID: office_24)
- [x] Power-Button auf QR-02 blockiert (`allow_power_button=false`)
- [x] Konkrete URLs für QR-Code-Websites (QR-01, QR-02, QR-03) festgelegt
- [x] PIN für QR-02 vergeben ({{PIN}})
- [x] REST PIN-Endpunkt `/api/pin` implementiert (Patch 18)
- [x] `/api/shutdown` Endpunkt repariert — funktioniert jetzt via Accessibility Service (Patch 22): Lock-Task stoppen → Power-Dialog öffnen → "Ausschalten" Button klicken → Bestätigen. Getestet auf QR-01 (Android 16) im Kiosk-Modus und ohne. Nach Neustart geht FreeKiosk automatisch in den Lock-Task.
- [x] PAT-01 provisioniert ({{SERIAL_PAT_01}}, {{IP_PAT_01}}, patched5 APK, alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery Protection AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] PAT-02 provisioniert ({{SERIAL_PAT_02}}, {{IP_PAT_02}}, patched5 APK, alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery Protection AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] PAT-03 provisioniert ({{SERIAL_PAT_03}}, {{IP_PAT_03}}, patched5 APK, alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery Protection AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] QR-01 provisioniert ({{SERIAL_QR_01}}, {{IP_QR_01}}, patched5 APK, alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **ZUI Battery-Optimierung AN** (Schutz beim Aufladen 40-60% + Ladungsoptimierung + Max. Ladegrenze 80%), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] QR-02 provisioniert ({{SERIAL_QR_02}}, {{IP_QR_02}}, patched5 APK, alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **ZUI Battery-Optimierung AN** (Schutz beim Aufladen 40-60% + Ladungsoptimierung + Max. Ladegrenze 80%), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] QR-03 provisioniert ({{SERIAL_QR_03}}, {{IP_QR_03}}, patched5 APK, alle Features: WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **ZUI Battery-Optimierung AN** (Schutz beim Aufladen 40-60% + Ladungsoptimierung + Max. Ladegrenze 80%), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] Konkrete URLs für Formular-Websites (PAT-01..03) festgelegt (`https://www.med-mv.de/qr-code-scanner/`)
- [x] Seriennummern aller Lenovo-Tablets → PAT-/QR-Nummern zugeordnet (siehe Geräteliste)
- [ ] MQTT / Home Assistant Integration gewünscht? (FreeKiosk unterstützt es)
- [ ] Boot-Verhalten / Auto-Start nach Stromausfall pro Tablet prüfen
- [ ] **`-patched6` (Patch 26, DHCP Auto-Recovery) auf alle 6 Tablets ausrollen** —
  APK gebaut (07.09.2026), Rollout steht aus (alle Tablets noch auf `-patched5`)
- [x] QR-01 auf aktuellen Config-Stand gebracht (gepatchte APK, alle Config-Keys, WiFi-Lock office_24, Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] QR-02 auf aktuellen Config-Stand gebracht (gepatchte APK, alle Config-Keys, WiFi-Lock office_24, Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] QR-03 auf aktuellen Config-Stand gebracht (gepatchte APK, alle Config-Keys, WiFi-Lock office_24, Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, API-Key {{API_KEY}})
- [x] `GET /api/config` Endpunkt implementiert (Patch 21) — liest Config direkt aus AsyncStorage
- [x] `GET /api/config/:key` Endpunkt implementiert (Patch 21) — einzelne Config-Keys auslesen
- [x] **APK-Fixes v1.2.20-beta.3-patched2** (20.07.2026, siehe `APK-FIXES-REQUIRED.md`):
  - [x] Bug 1 (P1) — Screensaver dimmt Brightness nicht (KioskScreen.tsx: Brightness-Transition-Effect)
  - [x] Bug 2 (P1) — `default_brightness` wird von `/api/brightness` überschrieben (onSetBrightness schreibt nicht mehr in default_brightness)
  - [x] Bug 3 (P1) — `screensaver/off` killt Inaktivitäts-Timer (resetTimerRef in API-Callbacks)
  - [x] Bug 4 (P2) — `wake` re-armt Timer nicht (resetTimerRef.current() in onWake)
  - [x] Bug 7 (P4) — Front-Kamera Init schlägt fehl (CameraPhotoModule.kt: Pre-Init Back-Kamera)
  - [x] Bug 9 (P5) — `/api/url` JSON-Parsing zu strikt (KioskHttpServer.kt: toleranter parseBody-Fallback)
  - [x] Bug 10 (P5) — `reboot`/`shutdown` Connection-Reset (Response-Delay per Handler.postDelayed)
  - Alle 7 Fixes auf {{SERIAL_PAT_03}} (PAT-03) verifiziert.
- [x] **APK-Fixes v1.2.20-beta.3-patched3** (20.07.2026, Gap-Analyse):
  - [x] Bug 11 (P2) — SQLiteDatabase Leak in `restartUi` (try/finally)
  - [x] Bug 12 (P2) — SQLiteDatabase Leak in `savePinToAsyncStorage` (try/finally)
  - [x] Bug 13 (P2) — SQLiteDatabase Leak in `getAsyncStorageValueOrNull` (try/finally)
  - [x] Bug 14 (P3) — setTimeout Leak in KioskScreen focus listener (blockingOverlayFocusTimeoutRef)
  - [x] Bug 15 (P3) — Camera Surface Leak bei Exception (dummySurface/dummyTexture try/finally)
  - [x] Bug 16 (P3) — Screen Scheduler triggert unnötig oft (enterScheduledSleepRef/exitScheduledSleepRef)
  - [x] Bug 17 (P2) — WifiLockModule Handler Leak (mainHandler.removeCallbacksAndMessages)
  - Bug 18 war False Positive (handleAudioPlay hat checkControlAllowed bereits).
  - Sanity-checked auf {{SERIAL_PAT_03}} (PAT-03): Health, GET /api/config, POST /api/config + restart-ui OK.
- [x] PAT-03 ({{SERIAL_PAT_03}}) auf patched3 aktualisiert + korrekte Config gepusht
  (URL `https://www.med-mv.de/qr-code-scanner/`, 41 Config-Keys, WiFi-Lock, System-Settings)
- [x] Alle 6 Tablets auf patched3 aktualisiert (20.07.2026):
  - [x] QR-02 ({{SERIAL_QR_02}}) — auf patched3 aktualisiert + Config gepusht
  - [x] PAT-02 ({{SERIAL_PAT_02}}) — auf patched3 aktualisiert + Config gepusht
  - [x] PAT-01 ({{SERIAL_PAT_01}}) — auf patched3 aktualisiert + Config gepusht
  - [x] QR-03 ({{SERIAL_QR_03}}) — auf patched3 aktualisiert + Config gepusht
  - [x] QR-01 ({{SERIAL_QR_01}}) — auf patched3 aktualisiert + Config gepusht
  - [x] PAT-03 ({{SERIAL_PAT_03}}) — auf patched3 aktualisiert + Config gepusht (Test-Device, Bug 1-17 verifiziert)

## Geräteliste

> **APK-Rollout-Stand (09.11.2026):** Auf allen 6 Tablets läuft `-patched5`.
> `-patched6` (Patch 26, DHCP Auto-Recovery) ist gebaut, aber noch **nicht**
> ausgerollt — die Status-Spalte dokumentiert daher weiterhin `patched5`.
> Rollout je Tablet: `adb install -r freekiosk-v1.2.20-beta.3-patched6.apk`,
> danach "Workflow: APK-Update" (force-stop → Config-Intent → WiFi-Lock) durchlaufen.

| Tablet | Seriennr. | MAC (HW) | IP | URL | API-Key | Status |
|---|---|---|---|---|---|---|
| QR-01 | {{SERIAL_QR_01}} | `{{MAC_QR_01}}` | {{IP_QR_01}} | `https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_01}}` | `{{API_KEY}}` | aktiv, **patched5** APK + alle Features (WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **ZUI Battery-Optimierung AN** (Schutz 40-60% + Ladungsoptimierung + 80% Limit), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, auto_brightness_enabled=false, brightness_management_enabled=false) — 22.07.2026 auf patched5 aktualisiert |
| QR-02 | {{SERIAL_QR_02}} | `{{MAC_QR_02}}` | {{IP_QR_02}} | `https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_02}}` | `{{API_KEY}}` | aktiv, **patched5** APK + alle Features (WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **ZUI Battery-Optimierung AN** (Schutz 40-60% + Ladungsoptimierung + 80% Limit), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, auto_brightness_enabled=false, brightness_management_enabled=false) — 22.07.2026 auf patched5 aktualisiert |
| QR-03 | {{SERIAL_QR_03}} | `{{MAC_QR_03}}` | {{IP_QR_03}} | `https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_03}}` | `{{API_KEY}}` | aktiv, **patched5** APK + alle Features (WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, **ZUI Battery-Optimierung AN** (Schutz 40-60% + Ladungsoptimierung + 80% Limit), Power-Button blockiert, Screensaver 1%, Banner, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, auto_brightness_enabled=false, brightness_management_enabled=false) — 22.07.2026 auf patched5 aktualisiert |
| PAT-01 | {{SERIAL_PAT_01}} | `{{MAC_PAT_01}}` | {{IP_PAT_01}} | `https://www.med-mv.de/qr-code-scanner/` | `{{API_KEY}}` | aktiv, **patched5** APK + alle Features (WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery-Optimierung AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner mit Akku-Anzeige, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, keep_screen_on=true, auto_wake_on_screen_off=false, auto_brightness_enabled=false, brightness_management_enabled=false) — 22.07.2026 auf patched5 aktualisiert |
| PAT-02 | {{SERIAL_PAT_02}} | `{{MAC_PAT_02}}` | {{IP_PAT_02}} | `https://www.med-mv.de/qr-code-scanner/` | `{{API_KEY}}` | aktiv, **patched5** APK + alle Features (WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery-Optimierung AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner mit Akku-Anzeige, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, keep_screen_on=true, auto_wake_on_screen_off=false, auto_brightness_enabled=false, brightness_management_enabled=false) — 22.07.2026 auf patched5 aktualisiert |
| PAT-03 | {{SERIAL_PAT_03}} | `{{MAC_PAT_03}}` | {{IP_PAT_03}} | `https://www.med-mv.de/qr-code-scanner/` | `{{API_KEY}}` | aktiv, **patched5** APK + alle Features (WiFi-Lock office_24+WiFi+TabConnect, MAC-Randomisierung aus, Battery-Optimierung AUS, Power-Button blockiert, Screensaver 30Min/1%, Banner mit Akku-Anzeige, Home-Button, Back-Button, Screen Scheduler Mo-Fr 22:30/05:30 + Sa/So aus, auto_reload, PIN {{PIN}}, keep_screen_on=true, auto_wake_on_screen_off=false, auto_brightness_enabled=false, brightness_management_enabled=false) — 22.07.2026 auf patched5 aktualisiert, 27.08.2026 Config repariert (kiosk_enabled + allow_power_button + Battery-Optimierung AUS) |

### Vollständige Provisioning-Befehle pro Tablet

> **Ablauf:** (1) APK installieren → (2) App stoppen → (3) Config-Intent per ADB (inkl. device_serial + device_mac) → (4) Full Config per REST (zwingend!) → (5) WiFi-Lock per REST → (6) Banner per REST → (7) System-Settings per ADB → (8) Verifikation
>
> **Voraussetzung:** Tablet per USB verbunden, Device Owner bereits gesetzt (`dpm set-device-owner`).
> Prüfen: `.\adb.exe shell "dpm list-owners"` → muss `com.freekiosk` zeigen.
>
> **Device Identity (neu):** Die Seriennummer und MAC-Adresse werden beim Provisioning
> als Config-Keys `device_serial` und `device_mac` in AsyncStorage gespeichert und sind
> per REST abrufbar:
> ```powershell
> # Serial + MAC auslesen (vor dem Config-Intent):
> $serial = (.adb.exe shell getprop ro.serialno).Trim()
> $mac = (.adb.exe shell "dumpsys wifi | grep mWifiInfo" | Select-String 'MAC: \S+').Matches.Value
> # Werden in den Full-Config-JSON-Block aufgenommen (Schritt 4):
> #   "device_serial":"{{SERIAL_QR_02}}","device_mac":"{{MAC_QR_02}}"
> # Abruf per REST:
> curl.exe -s "http://TABLET_IP:8080/api/config/device_serial" -H "X-Api-Key: {{API_KEY}}"
> curl.exe -s "http://TABLET_IP:8080/api/config/device_mac" -H "X-Api-Key: {{API_KEY}}"
> ```
> Die Keys werden in `configKeyMapping` (HttpServerModule.kt) und `keyMapping`
> (MainActivity.kt) auf `@kiosk_device_serial` / `@kiosk_device_mac` gemappt.
> Geht bei Factory Reset verloren — beim Re-Provisioning neu setzen.

#### QR-01 ({{SERIAL_QR_01}}, {{IP_QR_01}})

```powershell
cd D:\Projekte\KIOSK-TABS

# 1. APK installieren
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"

# 2. App stoppen
.\adb.exe shell am force-stop com.freekiosk
.\adb.exe shell am force-stop com.freekiosk:alarm
Start-Sleep -Seconds 2

# 3. Config-Intent (QR-Profil: Screensaver 2 Min/1%)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_01}}" --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "120000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. WiFi-Lock per REST (nach 15s Wartezeit bis REST API ready)
Start-Sleep -Seconds 15
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File "$env:TEMP\wl.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\wl.json"
Remove-Item "$env:TEMP\wl.json"

# 5. Banner per REST
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File "$env:TEMP\banner.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\banner.json"
Remove-Item "$env:TEMP\banner.json"

# 6. System-Settings per ADB
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0
.\adb.exe shell settings put global wifi_randomization_enabled 0
# ZUI Battery-Optimierung AN (Dauerbetrieb am Strom — schont den Akku)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization on"

# 7. Verifikation
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
# Erwartet: mLockTaskModeState=LOCKED
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}" | ConvertFrom-Json | Select-Object -ExpandProperty data | Select-Object -ExpandProperty webview
# Erwartet: currentUrl = https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_01}}
```

#### QR-02 ({{SERIAL_QR_02}}, {{IP_QR_02}})

```powershell
cd D:\Projekte\KIOSK-TABS

# 1. APK installieren
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"

# 2. App stoppen
.\adb.exe shell am force-stop com.freekiosk
.\adb.exe shell am force-stop com.freekiosk:alarm
Start-Sleep -Seconds 2

# 3. Config-Intent (QR-Profil: Screensaver 2 Min/1%)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_02}}" --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "120000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. WiFi-Lock per REST
Start-Sleep -Seconds 15
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File "$env:TEMP\wl.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\wl.json"
Remove-Item "$env:TEMP\wl.json"

# 5. Banner per REST
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File "$env:TEMP\banner.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\banner.json"
Remove-Item "$env:TEMP\banner.json"

# 6. System-Settings per ADB
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0
.\adb.exe shell settings put global wifi_randomization_enabled 0
# ZUI Battery-Optimierung AN (Dauerbetrieb am Strom — schont den Akku)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization on"

# 7. Verifikation
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}" | ConvertFrom-Json | Select-Object -ExpandProperty data | Select-Object -ExpandProperty webview
```

#### QR-03 ({{SERIAL_QR_03}}, {{IP_QR_03}})

```powershell
cd D:\Projekte\KIOSK-TABS

# 1. APK installieren
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"

# 2. App stoppen
.\adb.exe shell am force-stop com.freekiosk
.\adb.exe shell am force-stop com.freekiosk:alarm
Start-Sleep -Seconds 2

# 3. Config-Intent (QR-Profil: Screensaver 2 Min/1%)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://med.informme.info/tablet3?tabletLinkId={{TABLET_LINK_ID_QR_03}}" --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "120000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. WiFi-Lock per REST
Start-Sleep -Seconds 15
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File "$env:TEMP\wl.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\wl.json"
Remove-Item "$env:TEMP\wl.json"

# 5. Banner per REST
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File "$env:TEMP\banner.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\banner.json"
Remove-Item "$env:TEMP\banner.json"

# 6. System-Settings per ADB
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0
.\adb.exe shell settings put global wifi_randomization_enabled 0
# ZUI Battery-Optimierung AN (Dauerbetrieb am Strom — schont den Akku)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization on"

# 7. Verifikation
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}" | ConvertFrom-Json | Select-Object -ExpandProperty data | Select-Object -ExpandProperty webview
```

#### PAT-01 ({{SERIAL_PAT_01}}, {{IP_PAT_01}})

```powershell
cd D:\Projekte\KIOSK-TABS

# 1. APK installieren
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"

# 2. App stoppen
.\adb.exe shell am force-stop com.freekiosk
.\adb.exe shell am force-stop com.freekiosk:alarm
Start-Sleep -Seconds 2

# 3. Config-Intent (PAT-Profil: Screensaver 30 Min/1%)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://www.med-mv.de/qr-code-scanner/" --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "1800000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. WiFi-Lock per REST
Start-Sleep -Seconds 15
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File "$env:TEMP\wl.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\wl.json"
Remove-Item "$env:TEMP\wl.json"

# 5. Banner per REST
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File "$env:TEMP\banner.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\banner.json"
Remove-Item "$env:TEMP\banner.json"

# 6. System-Settings per ADB
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0
.\adb.exe shell settings put global wifi_randomization_enabled 0
# ZUI Battery-Optimierung AUS — PAT-Tablets brauchen vollen Akku (werden herumgereicht)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization off"

# 7. Verifikation
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}" | ConvertFrom-Json | Select-Object -ExpandProperty data | Select-Object -ExpandProperty webview
```

#### PAT-02 ({{SERIAL_PAT_02}}, {{IP_PAT_02}})

```powershell
cd D:\Projekte\KIOSK-TABS

# 1. APK installieren
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"

# 2. App stoppen
.\adb.exe shell am force-stop com.freekiosk
.\adb.exe shell am force-stop com.freekiosk:alarm
Start-Sleep -Seconds 2

# 3. Config-Intent (PAT-Profil: Screensaver 30 Min/1%)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://www.med-mv.de/qr-code-scanner/" --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "1800000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. WiFi-Lock per REST
Start-Sleep -Seconds 15
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File "$env:TEMP\wl.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\wl.json"
Remove-Item "$env:TEMP\wl.json"

# 5. Banner per REST
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File "$env:TEMP\banner.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\banner.json"
Remove-Item "$env:TEMP\banner.json"

# 6. System-Settings per ADB
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0
.\adb.exe shell settings put global wifi_randomization_enabled 0
# ZUI Battery-Optimierung AUS — PAT-Tablets brauchen vollen Akku (werden herumgereicht)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization off"

# 7. Verifikation
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}" | ConvertFrom-Json | Select-Object -ExpandProperty data | Select-Object -ExpandProperty webview
```

#### PAT-03 ({{SERIAL_PAT_03}}, {{IP_PAT_03}})

```powershell
cd D:\Projekte\KIOSK-TABS

# 1. APK installieren
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"

# 2. App stoppen
.\adb.exe shell am force-stop com.freekiosk
.\adb.exe shell am force-stop com.freekiosk:alarm
Start-Sleep -Seconds 2

# 3. Config-Intent (PAT-Profil: Screensaver 30 Min/1%)
.\adb.exe shell am start -n com.freekiosk/.MainActivity `
    --es url "https://www.med-mv.de/qr-code-scanner/" --es pin "{{PIN}}" `
    --es auto_launch "true" --es auto_relaunch "true" --es auto_reload "true" `
    --es rest_api_enabled "true" --es rest_api_port "8080" --es rest_api_key "{{API_KEY}}" --es rest_api_allow_control "true" `
    --es screensaver_enabled "true" --es screensaver_delay "1800000" --es screensaver_brightness "0.01" `
    --es screensaver_wake_on_content_change "true" --es screensaver_inactivity_enabled "true" --es screensaver_type "dim" `
    --es allow_power_button "false" --es back_button_mode "immediate" --es test_mode "false" `
    --es home_button_enabled "true" --es home_button_x_percent "95" --es home_button_y_percent "17" `
    --es webview_back_button_enabled "true" --es webview_back_button_x_percent "5" --es webview_back_button_y_percent "17" `
    --es banner_enabled "true" --es banner_text "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert." --es banner_font_size "25" --es banner_show_icon "true" `
    --es url_filter_enabled "true" --es url_filter_mode "whitelist" --es url_filter_list "[\"*informme.info/*\",\"*med-mv.de/*\"]" --es url_filter_show_feedback "true" `
    --es screen_scheduler_enabled "true" --es screen_scheduler_rules "[{\"id\":\"weeknight\",\"name\":\"Mo-Fr Nacht\",\"enabled\":true,\"days\":[1,2,3,4,5],\"sleepTime\":\"22:30\",\"wakeTime\":\"05:30\"},{\"id\":\"weekend\",\"name\":\"Sa-So aus\",\"enabled\":true,\"days\":[0,6],\"sleepTime\":\"00:00\",\"wakeTime\":\"23:59\"}]" --es screen_scheduler_wake_on_touch "true" `
    --es keep_screen_on "true" --es pause_web_media_when_hidden "true" `
    --es default_brightness "1" --es brightness_management_enabled "false" --es auto_brightness_enabled "false" `
    --es status_bar "false" --es status_bar_show_battery "false" --es status_bar_show_wifi "false" --es status_bar_show_time "false" `
    --es pin_mode "numeric" --es pin_max_attempts "3" `
    --es allow_notifications "false" --es allow_system_info "false" `
    --ez kiosk_enabled true --ez auto_start true --activity-clear-task

# 4. WiFi-Lock per REST
Start-Sleep -Seconds 15
.\adb.exe forward tcp:8081 tcp:8080
'{"allowed_ssids":["office_24","WiFi","TabConnect"]}' | Out-File "$env:TEMP\wl.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/wifi/lock" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\wl.json"
Remove-Item "$env:TEMP\wl.json"

# 5. Banner per REST
'{"banner_enabled":true,"banner_text":"Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.","banner_font_size":25,"banner_show_icon":true}' | Out-File "$env:TEMP\banner.json" -Encoding ascii -NoNewline
curl.exe -s -X POST "http://localhost:8081/api/banner" -H "X-Api-Key: {{API_KEY}}" -H "Content-Type: application/json" -d "@$env:TEMP\banner.json"
Remove-Item "$env:TEMP\banner.json"

# 6. System-Settings per ADB
.\adb.exe shell settings put global stay_on_while_plugged_in 3
.\adb.exe shell settings put system accelerometer_rotation 0
.\adb.exe shell settings put system user_rotation 0
.\adb.exe shell settings put global screen_off_timeout 2147483647
.\adb.exe shell settings put global policy_control immersive.full=com.freekiosk
.\adb.exe shell pm disable-user --user 0 com.zui.freeform.sidebar
.\adb.exe shell pm disable-user --user 0 com.zui.launcher
.\adb.exe shell settings put secure enable_temp_zuifreeformbar 0
.\adb.exe shell settings put secure pen_floating_all 0
.\adb.exe shell settings put global wifi_randomization_enabled 0
# ZUI Battery-Optimierung AUS — PAT-Tablets brauchen vollen Akku (werden herumgereicht)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization off"

# 7. Verifikation
.\adb.exe shell "dumpsys activity activities | grep mLockTaskModeState"
curl.exe -s "http://localhost:8081/api/status" -H "X-Api-Key: {{API_KEY}}" | ConvertFrom-Json | Select-Object -ExpandProperty data | Select-Object -ExpandProperty webview
```

> **Hinweis:** QR-Tablets verwenden `screensaver_delay "120000"` (2 Min) und
> **ZUI Battery-Optimierung AN** (siehe unten).
> PAT-Tablets verwenden `screensaver_delay "1800000"` (30 Min) und
> keine Battery-Optimierung (brauchen vollen Akku, werden herumgereicht).
> Ansonsten sind die Config-Intents identisch.

### ZUI Battery-Optimierung (Lenovo TB336FU)

Die Lenovo ZUI Settings-App bietet drei Battery-Optimierungs-Features, die über
den `zuibattery` HAL-Service (`android.hardware.battery.IZuiBatteryManager`)
gesteuert werden:

1. **Schutz beim Aufladen** (`setBatteryProtectedStatus`) — lädt nur zwischen
   40% und 60%, um die Akkulebensdauer zu schonen
2. **Ladungsoptimierung** (`setBatteryMaintenanceV1Status`) — Battery Maintenance V1
3. **Maximale Ladegrenze 80%** (`setStopChargingStatus` + `setStopChargingLevel(80)`) —
   stoppt das Laden bei 80%

Diese Features sind **nicht** über `settings put global` steuerbar. Das alte
`battery_protection_mode` Global-Setting hat keine Auswirkung auf diese ZUI-Features.

Stattdessen wird ein Java-Helper (`BatteryOptimization.dex`) verwendet, der über
`app_process` auf dem Tablet läuft und die Methoden per Reflection auf dem
`ZuiBatteryManager.getService()` aufruft:

```powershell
# Battery-Optimierung aktivieren (alle 3 Settings)
.\adb.exe push bat_opt_dex\classes.dex /data/local/tmp/BatteryOptimization.dex
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization on"

# Status prüfen
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization status"

# Deaktivieren
.\adb.exe shell "CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization off"

# Verifikation via dumpsys
.\adb.exe shell dumpsys zuibattery | Select-String "Battery protected|Battery maintenance|Battery StopCharging"
```

**Konfiguration:**
- **QR-01, QR-02, QR-03**: Battery-Optimierung **AN** (alle 3 Settings)
- **PAT-01, PAT-02, PAT-03**: Battery-Optimierung **AUS** (werden herumgereicht, brauchen vollen Akku)

> **Hinweis:** `setBatteryProtectedStatus(true)` wirft eine harmlose `ERROR: null`
> Exception via Reflection, der Status wird trotzdem korrekt gesetzt.
