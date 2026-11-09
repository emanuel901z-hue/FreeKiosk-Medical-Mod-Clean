# KIOSK-TABS REST API — Vollständige Dokumentation

**Stand:** 07.09.2026 (v1.2.20-beta.3-patched6)
**Base URL:** `http://{{TABLET_IP}}:8080`
**Auth:** `X-Api-Key: {{API_KEY}}` (Header)
**Format:** JSON (`Content-Type: application/json`)

> **Platzhalter:** Installationsspezifische Werte sind durch Platzhalter ersetzt
> (`{{API_KEY}}`, `{{PIN}}`, `{{TABLET_IP}}`, `{{SUBNET}}`, `{{IP_*}}`, `{{MAC_*}}`,
> `{{SERIAL_*}}`, `{{TABLET_LINK_ID_*}}`, `{{WLAN_PASSWORT_*}}`). Die echten Werte stehen
> in der lokalen, nicht versionierten `PRIVATE-CREDENTIALS.md`.
>
> Diese Dokumentation erweitert die [Upstream-Doku](freekiosk-main/docs/rest-api.md)
> um alle projekt-spezifischen Patches (Patch 3-26) und APK-Fixes (Bug 1-17).
>
> **v1.2.20-beta.3-patched2** (20.07.2026): APK-Fixes fuer Bug 1, 2, 3, 4, 7, 9, 10
> (siehe `APK-FIXES-REQUIRED.md`). Keine neuen Endpoints, aber Behavior-Aenderungen
> bei `/api/brightness` (transient), `/api/reboot`+`/api/shutdown` (deferred +
> `pending:true`), `/api/screensaver/on`+`/api/wake` (Timer re-arm), und
> tolerantes JSON-Parsing fuer alle POST-Endpoints.
>
> **v1.2.20-beta.3-patched3** (20.07.2026): APK-Fixes fuer Bug 11-17 (Gap-Analyse).
> Interne Resource-Leak-Fixes (SQLiteDatabase try/finally, Camera Surface finally,
> WifiLock Handler cleanup, setTimeout cleanup, Screen Scheduler Refs). **Keine
> API-Behavior-Aenderungen** — alle Endpoints verhalten sich wie in patched2.
>
> **v1.2.20-beta.3-patched4** (22.07.2026): Patch 24 — Device Identity Config-Keys
> (`device_serial`, `device_mac`). Neue Config-Keys zum Speichern der Hardware-
> Seriennummer und MAC-Adresse beim Provisioning (per ADB ausgelesen, da Android 10+
> `Build.SERIAL` verbirgt). Abrufbar per `GET /api/config/device_serial` und
> `GET /api/config/device_mac` — fuer externe REST-Apps als eindeutiger
> Tablet-Identifikator. Keine neuen Endpoints, nur neue Config-Keys.
>
> **v1.2.20-beta.3-patched5** (22.07.2026): Patch 25 — Scheduled Wake Keyguard Fix.
> Behebt das Problem, dass nach langem Screen-Off (Stunden, `lockNow()`) die
> FreeKiosk-Aktivitaet in den STOPPED-Zustand ging und `requestDismissKeyguard()`
> beim geplanten Aufwecken (Screen Scheduler / `/api/screen/on` / JS `turnScreenOn`)
> wirkungslos blieb — der Keyguard war sichtbar und erforderte eine Wischgeste.
> Fix: `ActivityManager.moveTaskToFront()` bringt die FreeKiosk-Task vor dem
> `requestDismissKeyguard()` nach vorne (Device Owner hat `REORDER_TASKS`).
> **Keine neuen Endpoints**, aber `/api/screen/on` und der Screen Scheduler
> wecken jetzt zuverlaessig ohne Keyguard auf.
>
> **v1.2.20-beta.3-patched6** (07.09.2026): Patch 26 — DHCP Auto-Recovery im
> WiFi-Lock. Nach einem Reboot kann sich das Tablet auf L2-Ebene mit dem WLAN
> verbinden (SSID korrekt), ohne per DHCP eine IP zu bekommen
> (`CMD_IP_CONFIGURATION_LOST`, `IP: null`) — das Tablet ist dann trotz laufendem
> FreeKiosk nicht per REST erreichbar. `checkNetwork()` prueft jetzt zusaetzlich
> eine gueltige IP (`hasValidIpAddress()`); bleibt sie nach 15 s aus, laeuft
> `attemptDhcpRecovery()`: `DISALLOW_CONFIG_WIFI` temporaer entfernen, WiFi
> aus/ein, 12 s warten, Restriction wieder setzen — max. 3 Versuche.
> **Keine neuen Endpoints**; `GET /api/wifi/lock-status` zeigt den Zustand
> waehrend der Recovery unveraendert an.

---

## Inhaltsverzeichnis

- [Standard-Response-Format](#standard-response-format)
- [Error-Responses](#error-responses)
- [Status & Info (GET)](#status--info-get)
- [Config-Verwaltung (GET/POST)](#config-verwaltung-getpost)
- [WiFi-Lock / Anti-Theft (POST/GET)](#wifi-lock--anti-theft-postget)
- [Display & Screensaver (GET/POST)](#display--screensaver-getpost)
- [Power-Control (GET/POST)](#power-control-getpost)
- [WebView-Navigation (GET/POST)](#webview-navigation-getpost)
- [Banner / Anti-Theft (POST/GET)](#banner--anti-theft-postget)
- [PIN-Verwaltung (POST)](#pin-verwaltung-post)
- [Audio (POST/GET)](#audio-postget)
- [Remote Control & Keyboard (GET/POST)](#remote-control--keyboard-getpost)
- [Code-Injection (POST)](#code-injection-post)
- [URL-Rotation (GET/POST)](#url-rotation-getpost)
- [Sensoren, Storage, Memory (GET)](#sensoren-storage-memory-get)
- [Kamera & Screenshot (GET)](#kamera--screenshot-get)
- [GPS (GET)](#gps-get)
- [Tabelle: Alle Endpoints](#tabelle-alle-endpoints)
- [Webinterface-Empfehlungen](#webinterface-empfehlungen)

---

## Standard-Response-Format

Alle JSON-Responses haben dieses Grundformat:

**Erfolg:**
```json
{
  "success": true,
  "data": { ... },
  "timestamp": 1783535199
}
```

**Fehler:**
```json
{
  "success": false,
  "error": "Error message",
  "timestamp": 1783535199
}
```

`timestamp` = Unix-Zeit in Sekunden.

---

## Error-Responses

| HTTP Status | Bedeutung |
|---|---|
| `200 OK` | Erfolg |
| `400 Bad Request` | Fehlende/ungueltige Parameter |
| `401 Unauthorized` | API-Key fehlt oder falsch |
| `403 Forbidden` | Control-Befehle deaktiviert (`rest_api_allow_control=false`) |
| `404 Not Found` | Unbekannter Endpoint |
| `405 Method Not Allowed` | POST-only Endpoint mit GET aufgerufen |
| `500 Internal Error` | Server-Fehler |
| `503 Service Unavailable` | Feature nicht verfuegbar (z.B. Kamera, WiFi-Lock-Handler) |

---

## Status & Info (GET)

### `GET /api/status`

Vollstaendiger Geraetestatus in einem Call. **Primaerer Endpoint fuer das Webinterface.**

**Response:**
```json
{
  "success": true,
  "data": {
    "battery": {
      "level": 85,
      "charging": true,
      "plugged": "ac",
      "temperature": 25.5,
      "voltage": 4.2,
      "health": "good",
      "technology": "Li-ion"
    },
    "screen": {
      "on": true,
      "brightness": 100,
      "screensaverActive": false
    },
    "webview": {
      "currentUrl": "https://med.informme.info/tablet3?tabletLinkId=...",
      "canGoBack": false,
      "loading": false
    },
    "device": {
      "model": "TB336FU",
      "manufacturer": "Lenovo",
      "android": "14"
    },
    "wifi": {
      "connected": true,
      "ssid": "office_24",
      "rssi": -45,
      "ip": "{{IP_QR_01}}"
    },
    "sensors": {
      "light": 150.5,
      "proximity": 5,
      "accelerometer": { "x": 0.1, "y": 0.2, "z": 9.8 }
    },
    "autoBrightness": {
      "enabled": false,
      "min": 10,
      "max": 100,
      "currentLightLevel": 150.5
    },
    "kiosk": {
      "enabled": true,
      "pinEnabled": true
    },
    "audio": { "volume": 50 },
    "storage": {
      "totalMB": 32000,
      "availableMB": 15000,
      "usedMB": 17000,
      "usedPercent": 53
    },
    "memory": {
      "totalMB": 4096,
      "availableMB": 2048,
      "usedMB": 2048,
      "usedPercent": 50,
      "lowMemory": false
    }
  },
  "timestamp": 1783535199
}
```

### `GET /api/battery`

```json
{
  "success": true,
  "data": {
    "level": 85,
    "charging": true,
    "plugged": "ac",
    "temperature": 25.5,
    "voltage": 4.2,
    "health": "good",
    "technology": "Li-ion"
  }
}
```

| Field | Range/Type | Description |
|---|---|---|
| `level` | 0-100 | Battery percentage |
| `charging` | boolean | Laedt gerade |
| `plugged` | `usb`/`ac`/`wireless`/`none` | Stromquelle |
| `temperature` | number | Grad Celsius |
| `voltage` | number | Volt |
| `health` | string | `good`/`overheat`/`dead`/`over_voltage`/`failure`/`cold`/`unknown` |
| `technology` | string | Akku-Chemie |

### `GET /api/screen`

```json
{
  "success": true,
  "data": {
    "on": true,
    "brightness": 100,
    "screensaverActive": false
  }
}
```

| Field | Description |
|---|---|
| `on` | Physischer Display-Status (`PowerManager.isInteractive`) |
| `brightness` | 0-100 |
| `screensaverActive` | Screensaver-Overlay aktiv (Display an, aber gedimmt) |

**Kombinierte Zustaende:**
- `on: true, screensaverActive: false` = Display an, Inhalt sichtbar
- `on: true, screensaverActive: true` = Display an, Screensaver gedimmt
- `on: false, screensaverActive: false` = Display aus (Power Button oder lockNow)

### `GET /api/brightness`

```json
{ "success": true, "data": { "brightness": 100 } }
```

### `GET /api/wifi`

```json
{
  "success": true,
  "data": {
    "connected": true,
    "ssid": "office_24",
    "rssi": -45,
    "ip": "{{IP_QR_01}}"
  }
}
```

### `GET /api/info`

```json
{
  "success": true,
  "data": {
    "ip": "{{IP_QR_01}}",
    "hostname": "freekiosk",
    "version": "1.2.20-beta.3",
    "isDeviceOwner": true,
    "kioskMode": true
  }
}
```

| Field | Description |
|---|---|
| `isDeviceOwner` | Device Owner aktiv (required fuer reboot, shutdown, lock, screen off) |
| `kioskMode` | Lock Task Mode aktiv |

### `GET /api/health`

```json
{ "success": true, "data": { "status": "ok" } }
```

### `GET /api/volume`

```json
{ "success": true, "data": { "level": 80, "maxLevel": 100 } }
```

### `GET /api/volume/keys` — Volume-Hardwaretasten Status

Liest den **tatsächlichen DPM-Restriction-State** (nicht nur die gespeicherte Config).
Wichtig um festzustellen, ob die Lautstärketasten wirklich blockiert sind — z.B.
nach einem Crash von `AntiTheftAlarmModule` beim Alarm-Stop, der die Restriction
`DISALLOW_ADJUST_VOLUME` aktiviert lassen kann.

```json
{
  "success": true,
  "data": {
    "executed": true,
    "command": "getVolumeKeysStatus",
    "allowed": true,
    "restrictionActive": false,
    "configAllowed": true
  }
}
```

| Feld | Bedeutung |
|---|---|
| `allowed` | `true` = Lautstärketasten funktionieren (Restriction NICHT aktiv) |
| `restrictionActive` | `true` = `DISALLOW_ADJUST_VOLUME` ist gesetzt (Tasten blockiert) |
| `configAllowed` | Gespeicherter Config-Wert `@kiosk_allow_volume_buttons` (`true`/`false`) |

Wenn `allowed=false` aber `configAllowed=true` → Restriction ist "stuck" (z.B. nach
Alarm-Crash). Mit `POST /api/volume/keys/unblock` behebbar.

### `POST /api/volume/keys/block` — Lautstärketasten blockieren

Setzt sofort die `DISALLOW_ADJUST_VOLUME` User-Restriction via DevicePolicyManager
und persistiert `@kiosk_allow_volume_buttons=false` in AsyncStorage. Die Sperre
wird bei jedem `enableKioskRestrictions()` (App-Start, Reboot, `restart-ui`)
automatisch re-applied.

Kein Body nötig.

```json
{ "success": true, "data": { "executed": true, "command": "volumeKeysBlock", "blocked": true } }
```

### `POST /api/volume/keys/unblock` — Lautstärketasten entsperren

Cleared die `DISALLOW_ADJUST_VOLUME` Restriction und persistiert
`@kiosk_allow_volume_buttons=true`. Dies ist der **Recovery-Pfad** für Tablets,
deren Lautstärketasten nach einem Anti-Theft-Alarm-Crash blockiert geblieben sind.

Kein Body nötig.

```json
{ "success": true, "data": { "executed": true, "command": "volumeKeysUnblock", "blocked": false } }
```

### `GET /api/rotation`

URL-Rotation-Status.

```json
{
  "success": true,
  "data": {
    "enabled": false,
    "urls": [],
    "interval": 30,
    "currentIndex": 0
  }
}
```

---

## Config-Verwaltung (GET/POST)

### `GET /api/config` *(Patch 21)*

Liest die aktuell in AsyncStorage gespeicherte Config aus. Nur Keys, die tatsaechlich
gesetzt sind, werden zurueckgegeben. Die Key-Namen entsprechen denen von `POST /api/config`
und den ADB-Intent-Extras.

**Response:**
```json
{
  "success": true,
  "data": {
    "url": "https://med.informme.info/tablet3?tabletLinkId=...",
    "home_button_enabled": "true",
    "home_button_x_percent": "95",
    "screen_scheduler_enabled": "true",
    "screen_scheduler_rules": "[{\"id\":\"weeknight\",...}]",
    "...": "..."
  },
  "timestamp": 1783535199
}
```

### `GET /api/config/:key` *(Patch 21)*

Liest einen einzelnen Config-Key.

**Beispiel:** `GET /api/config/home_button_enabled`

**Response:**
```json
{
  "success": true,
  "data": { "key": "home_button_enabled", "value": "true" },
  "timestamp": 1783535199
}
```

**Unbekannter Key:**
```json
{ "success": false, "error": "Unknown config key: unknown_key", "timestamp": 1783535199 }
```

### `POST /api/config` *(Patch 3)*

Setzt Config-Keys via JSON. Keys werden in `FreeKioskPendingConfig` SharedPreferences
geschrieben (`has_pending_config=true`). Angewendet wird die Config beim naechsten
`POST /api/restart-ui` - die Keys werden direkt in AsyncStorage (RKStorage SQLite)
geschrieben und ein `freekiosk_config_changed` JS-Event feuert.

**Request:**
```json
{
  "screensaver_enabled": "true",
  "screensaver_delay": "120000",
  "auto_brightness_enabled": "false"
}
```

Alle Values sind **Strings** (auch booleans/zahlen). Unbekannte Keys werden ignoriert.

**Response (Erfolg):**
```json
{
  "success": true,
  "data": {
    "executed": true,
    "applied": 3,
    "appliedKeys": ["screensaver_enabled", "screensaver_delay", "auto_brightness_enabled"]
  },
  "timestamp": 1783535199
}
```

**Response (mit unbekannten Keys):**
```json
{
  "success": true,
  "data": {
    "executed": true,
    "applied": 2,
    "appliedKeys": ["screensaver_enabled", "screensaver_delay"],
    "unknownKeys": ["foo_bar"]
  }
}
```

**Unterstützte Config-Keys:**

| Key | AsyncStorage-Key | Beschreibung |
|---|---|---|
| `url` | `@kiosk_url` | WebView-URL |
| `pin` | `@kiosk_pin` | Settings-PIN |
| `auto_launch` | `@kiosk_auto_launch` | Auto-Launch bei Boot |
| `auto_relaunch` | `@kiosk_auto_relaunch` | Auto-Relaunch bei Crash |
| `auto_reload` | `@kiosk_auto_reload` | Reload on Error |
| `kiosk_enabled` | `@kiosk_enabled` | Kiosk-Modus an/aus |
| `screensaver_enabled` | `@screensaver_enabled` | Screensaver an/aus |
| `screensaver_delay` | `@screensaver_inactivity_delay` | Inaktivitaets-Timeout in ms |
| `screensaver_brightness` | `@screensaver_brightness` | 0-1 Skala (0.01 = 1%) |
| `screensaver_wake_on_content_change` | `@screensaver_wake_on_content_change` | DOM-Aenderung weckt auf |
| `screensaver_inactivity_enabled` | `@screensaver_inactivity_enabled` | Inactivity-basierte Aktivierung |
| `screensaver_type` | `@screensaver_type` | `dim` oder `url` |
| `allow_power_button` | `@kiosk_allow_power_button` | Power-Menu erlauben (false = blockiert) |
| `back_button_mode` | `@kiosk_back_button_mode` | `immediate`/`test`/`timer` |
| `webview_back_button_enabled` | `@kiosk_webview_back_button_enabled` | WebView Back-Button |
| `webview_back_button_x_percent` | `@kiosk_webview_back_button_x_percent` | X-Position % |
| `webview_back_button_y_percent` | `@kiosk_webview_back_button_y_percent` | Y-Position % |
| `home_button_enabled` | `@kiosk_home_button_enabled` | Home-Button anzeigen |
| `home_button_x_percent` | `@kiosk_home_button_x_percent` | X-Position % (Default: 95) |
| `home_button_y_percent` | `@kiosk_home_button_y_percent` | Y-Position % (Default: 17) |
| `keep_screen_on` | `@kiosk_keep_screen_on` | Display dauerhaft an |
| `pause_web_media_when_hidden` | `@kiosk_pause_web_media_when_hidden` | Media pausieren wenn verdeckt |
| `default_brightness` | `@default_brightness` | Standard-Helligkeit (0-1, 1 = 100%) |
| `brightness_management_enabled` | `@brightness_management_enabled` | App verwaltet Helligkeit (false = System) |
| `auto_brightness_enabled` | `@auto_brightness_enabled` | Auto-Helligkeit (false = fest) |
| `screen_scheduler_enabled` | `@kiosk_screen_scheduler_enabled` | Sleep Scheduler an/aus |
| `screen_scheduler_rules` | `@kiosk_screen_scheduler_rules` | JSON-Array mit Scheduler-Rules |
| `screen_scheduler_wake_on_touch` | `@kiosk_screen_scheduler_wake_on_touch` | Touch weckt im Sleep-Fenster |
| `url_filter_enabled` | `@kiosk_url_filter_enabled` | URL-Filter an/aus |
| `url_filter_mode` | `@kiosk_url_filter_mode` | `whitelist`/`blacklist` |
| `url_filter_list` | `@kiosk_url_filter_list` | JSON-Array mit URL-Patterns |
| `url_filter_show_feedback` | `@kiosk_url_filter_show_feedback` | Feedback bei geblockter URL |
| `rest_api_enabled` | `@kiosk_rest_api_enabled` | REST-API an/aus |
| `rest_api_port` | `@kiosk_rest_api_port` | REST-API-Port |
| `rest_api_key` | `@kiosk_rest_api_key` | REST-API-Key |
| `rest_api_allow_control` | `@kiosk_rest_api_allow_control` | Control-Befehle erlauben |
| `pin_mode` | `@kiosk_pin_mode` | `numeric`/`alphanumeric` |
| `pin_max_attempts` | `@kiosk_pin_max_attempts` | Max. PIN-Versuche |
| `allow_notifications` | `@kiosk_allow_notifications` | System-Benachrichtigungen |
| `allow_system_info` | `@kiosk_allow_system_info` | System-Info Zugriff |
| `banner_enabled` | `@kiosk_banner_enabled` | Anti-Theft Banner |
| `banner_text` | `@kiosk_banner_text` | Banner-Text |
| `banner_font_size` | `@kiosk_banner_font_size` | Schriftgroesse in sp |
| `banner_show_icon` | `@kiosk_banner_show_icon` | Dieb-Symbol anzeigen |
| `status_bar` | `@kiosk_status_bar` | Status-Bar anzeigen |
| `status_bar_show_battery` | `@kiosk_status_bar_show_battery` | Akku in Status-Bar |
| `status_bar_show_wifi` | `@kiosk_status_bar_show_wifi` | WiFi in Status-Bar |
| `status_bar_show_time` | `@kiosk_status_bar_show_time` | Uhrzeit in Status-Bar |
| `auto_wake_on_screen_off` | `@kiosk_auto_wake_on_screen_off` | Auto-Wake bei screen off |
| `device_serial` | `@kiosk_device_serial` | Hardware-Seriennummer (Patch 24, nur beim Provisioning setzen) |
| `device_mac` | `@kiosk_device_mac` | Hardware-MAC-Adresse (Patch 24, nur beim Provisioning setzen) |

#### Device Identity (Patch 24)

Die Seriennummer und MAC-Adresse lassen sich aus einer Android-App seit Android 10
nicht mehr nativ auslesen (`Build.SERIAL`="unknown", `Build.getSerial()` braucht
`READ_PRIVILEGED_PHONE_STATE`). Daher werden beide Werte beim Provisioning per ADB
ausgelesen und als Config-Keys gespeichert:

```powershell
# Beim Provisioning (vor WiFi-Lock):
$serial = (.adb.exe shell getprop ro.serialno).Trim()
$mac = (.adb.exe shell "dumpsys wifi | grep mWifiInfo" | Select-String 'MAC: \S+').Matches.Value
# In den Full-Config-JSON-Block aufnehmen:
#   "device_serial":"{{SERIAL_QR_02}}","device_mac":"{{MAC_QR_02}}"
```

Abruf per REST (für externe Apps als eindeutiger Identifikator):
```powershell
curl.exe -s "http://TABLET_IP:8080/api/config/device_serial" -H "X-Api-Key: {{API_KEY}}"
# {"success":true,"data":{"key":"device_serial","value":"{{SERIAL_QR_02}}"},"timestamp":...}

curl.exe -s "http://TABLET_IP:8080/api/config/device_mac" -H "X-Api-Key: {{API_KEY}}"
# {"success":true,"data":{"key":"device_mac","value":"{{MAC_QR_02}}"},"timestamp":...}
```

> Geht bei Factory Reset verloren — beim Re-Provisioning neu setzen.
> Die Keys sind read-only für externe Apps (nur beim Provisioning per ADB/REST setzen).

### `POST /api/restart-ui` *(Patch 6)*

Wendet pending Config an (schreibt Keys in AsyncStorage, sendet `freekiosk_config_changed`
JS-Event). Die App laeuft ununterbrochen weiter - kein `activity.recreate()` oder
`ReactHost.reload()` (die auf Android 16 / RN 0.82 crashen).

**Request:** Kein Body noetig.

**Response:**
```json
{
  "success": true,
  "data": { "executed": true },
  "timestamp": 1783535199
}
```

---

## WiFi-Lock / Anti-Theft (POST/GET)

### `POST /api/wifi/lock` *(Patch 19)*

Aktiviert WiFi-Lock: Blockiert WiFi-Config (`DISALLOW_CONFIG_WIFI`), startet
WiFi-Monitoring. Bei Verbindung mit nicht-erlaubter SSID -> Device Lock + Alarm.

**Request:**
```json
{
  "allowed_ssids": ["office_24", "WiFi", "TabConnect"]
}
```

`allowed_ssids` kann ein JSON-Array oder ein komma-separierter String sein.

> **DHCP Auto-Recovery (Patch 26):** Der WiFi-Monitor prueft neben der SSID auch
> eine gueltige IP-Adresse. Fehlt sie 15 s nach dem Verbindungsaufbau, wird die
> WLAN-Verbindung automatisch neu aufgebaut (max. 3 Versuche, siehe
> Versionshinweis oben). Damit ist das Tablet nach einem Reboot auch dann wieder
> per REST erreichbar, wenn der DHCP-Lease abgelaufen war.

**Response:**
```json
{
  "success": true,
  "data": {
    "success": true,
    "action": "lock",
    "allowed_ssids": ["office_24", "WiFi", "TabConnect"],
    "disallow_config_wifi": true
  },
  "timestamp": 1783535199
}
```

**Error (kein Device Owner):**
```json
{
  "success": false,
  "data": {
    "success": false,
    "error": "WiFi Lock requires Device Owner mode"
  }
}
```

### `GET|POST /api/wifi/unlock`

Deaktiviert WiFi-Lock: Entfernt `DISALLOW_CONFIG_WIFI`, stoppt Monitoring,
stellt Lock-Task-Features wieder her (Power-Button), loescht persisted State.

**Response:**
```json
{
  "success": true,
  "data": {
    "success": true,
    "action": "unlock",
    "disallow_config_wifi": false
  }
}
```

### `POST /api/wifi/allow`

Fuegt eine SSID zur erlaubten Liste hinzu (ohne Lock neu zu starten).

**Request:**
```json
{ "ssid": "NewNetwork" }
```

**Response:**
```json
{
  "success": true,
  "data": {
    "success": true,
    "action": "allow",
    "ssid": "NewNetwork",
    "allowed_ssids": ["office_24", "WiFi", "TabConnect", "NewNetwork"]
  }
}
```

### `GET /api/wifi/lock-status`

Aktueller WiFi-Lock-Status. **Wichtig fuer das Webinterface zur Diebstahl-Erkennung.**

**Response:**
```json
{
  "success": true,
  "data": {
    "lockActive": true,
    "disallowConfigWifi": true,
    "currentSsid": "office_24",
    "isConnected": true,
    "isOnAllowedNetwork": true,
    "allowed_ssids": ["TabConnect", "office_24", "WiFi"]
  },
  "timestamp": 1783535199
}
```

| Field | Type | Description |
|---|---|---|
| `lockActive` | boolean | WiFi-Lock aktiviert |
| `disallowConfigWifi` | boolean | WiFi-Config blockiert (`no_config_wifi`) |
| `currentSsid` | string\|null | Aktuelle SSID (null wenn nicht verbunden) |
| `isConnected` | boolean | WiFi verbunden |
| `isOnAllowedNetwork` | boolean | Aktuelle SSID in allowed_ssids |
| `allowed_ssids` | array | Erlaubte SSIDs |

---

## Display & Screensaver (GET/POST)

### `GET|POST /api/screen/on`

Display anschalten / Geraet aufwecken.

> **Patch 25 Fix (v1.2.20-beta.3-patched5):** Nach langem Screen-Off (Stunden)
> ging die FreeKiosk-Aktivitaet in den STOPPED-Zustand, wodurch
> `requestDismissKeyguard()` beim Aufwecken wirkungslos blieb und der Keyguard
> sichtbar war (Wischgeste noetig). Der Fix ruft zusaetzlich
> `ActivityManager.moveTaskToFront()` auf, um die FreeKiosk-Task nach vorne zu
> bringen, bevor der Keyguard dismissed wird. Device Owner hat die noetige
> `REORDER_TASKS`-Permission. Gilt auch fuer den JS-seitigen `turnScreenOn()`
> und den Screen Scheduler (`ScreenSchedulerReceiver.handleWake()`).

### `GET|POST /api/screen/off`

Display ausschalten. Mit Device Owner: `lockNow()` (echtes Screen-Off).

### `GET|POST /api/screensaver/on`

Screensaver-Setting aktivieren. Aktiviert sich nach Inaktivitaets-Timeout.

> **Bug 3 Fix (v1.2.20-beta.3-patched2):** Bewaffnet den Inaktivitaets-Timer
> explizit neu. Nach einem vorherigen `screensaver/off` → `screensaver/on`
> laeuft der Timer wieder zuverlaessig. Zuvor wurde nur der Flag zurueckgesetzt
> und der Timer blieb entwaffnet (Screensaver aktivierte sich nie wieder).

### `GET|POST /api/screensaver/off`

Screensaver-Setting deaktivieren. Aktiver Screensaver wird sofort beendet.

> **Bug 3 Fix (v1.2.20-beta.3-patched2):** Stoppt den Inaktivitaets-Timer sauber
> (nicht nur Flag-Reset). Ein nachfolgendes `screensaver/on` bewaffnet den Timer
> neu — kein Reboot mehr noetig.

### `GET|POST /api/screensaver/wake-content/on`

Screensaver bei DOM-Aenderung aufwecken.

### `GET|POST /api/screensaver/wake-content/off`

DOM-Aenderung weckt Screensaver nicht mehr auf.

### `GET|POST /api/wake`

Aus Screensaver aufwecken (sofort, ohne Screen-On).

> **Bug 4 Fix (v1.2.20-beta.3-patched2):** Bewaffnet den Inaktivitaets-Timer
> neu, sodass der Screensaver nach `screensaver_delay` Inaktivitaet wieder
> selbststaendig aktiviert wird. Zuvor wurde nur der Overlay ausgeblendet und
> der Timer blieb entwaffnet (Screensaver re-aktivierte sich nur nach
> Content-Change im WebView, nie nach bloßem `wake`).

### `POST /api/brightness`

Helligkeit setzen (0-100). Deaktiviert Auto-Brightness (falls aktiv).

> **Bug 2 Fix (v1.2.20-beta.3-patched2):** Dieser Endpoint setzt **nur die
> aktuelle Helligkeit transient**, er schreibt **nicht** in `default_brightness`.
> Zuvor wurde `default_brightness` mit dem uebergebenen Wert ueberschrieben,
> was dazu fuehrte, dass BrightnessEnforcer-Workarounds (die bei aktivem
> Screensaver `screensaver_brightness` per `/api/brightness` setzten) die
> `default_brightness` dauerhaft auf 0.01 zuruecksetzten — das Display blieb
> nach Screensaver-Deaktivierung dunkel.
>
> Fuer **permanente** Helligkeits-Aenderungen `POST /api/config` mit
> `default_brightness` verwenden (schreibt in AsyncStorage via Pending-Config +
> `restart-ui`).

**Request:**
```json
{ "value": 75 }
```

**Response:**
```json
{
  "success": true,
  "data": { "executed": true, "command": "setBrightness" }
}
```

### `POST /api/autoBrightness/enable`

```json
{ "min": 10, "max": 100, "offset": 10 }
```

### `POST /api/autoBrightness/disable`

### `GET /api/autoBrightness`

```json
{
  "success": true,
  "data": {
    "enabled": true,
    "min": 10,
    "max": 100,
    "currentLightLevel": 250.5
  }
}
```

---

## Power-Control (GET/POST)

### `GET|POST /api/reboot`

Geraet neu starten. **Device Owner erforderlich.** Nutzt `DPM.reboot()`.

> **Bug 10 Fix (v1.2.20-beta.3-patched2):** Die Response wird gesendet **bevor**
> der Reboot ausgefuehrt wird. Der eigentliche `DPM.reboot()`-Aufruf wird per
> `Handler.postDelayed(500ms)` deferred, damit der HTTP-Server die Response
> sauber flushen und die Connection schliessen kann. Zuvor wurde `DPM.reboot()`
> synchron ausgefuehrt und der Client sah `ECONNABORTED`/`ECONNRESET`/
> `ECONNREFUSED` statt einer sauberen `200 OK`.

**Response:**
```json
{
  "success": true,
  "data": { "executed": true, "command": "reboot", "pending": true }
}
```

Das `pending: true` Feld signalisiert, dass der Reboot asynchron nach 500ms
ausgefuehrt wird. Clientseitig koennen `ECONNABORTED`/`ECONNRESET`/
`ECONNREFUSED`/`ETIMEDOUT`/`ERR_NETWORK` als **erwarteter Erfolg** gewertet
werden (falls die Response nicht rechtzeitig ankommt).

### `GET|POST /api/shutdown` *(Patch 22)*

Geraet herunterfahren (Power Off). **Device Owner + Accessibility Service erforderlich.**

**Implementierung (Patch 22 — Accessibility Service Ansatz):**

Auf Android 16 Production-Builds blockiert SELinux den direkten Aufruf von
`IPowerManager.shutdown()` fuer `untrusted_app` (auch als Device Owner).
`dpm.reboot()` funktioniert (laeuft im `system_server`), aber es gibt kein
`dpm.shutdown()`. Daher wird der Shutdown ueber den Accessibility Service ausgefuehrt:

1. **Lock-Task stoppen** per `activity.stopLockTask()` (sonst wird der Power-Dialog blockiert).
2. **Power-Dialog oeffnen** per `performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)`.
3. **"Ausschalten" Button finden** im Accessibility Tree (mehrsprachig:
   "power off", "shut down", "ausschalten", "herunterfahren", etc.) und per
   `ACTION_CLICK` klicken.
4. **Bestaetigung klicken** falls ein Bestaetigungs-Dialog erscheint ("OK", "ja",
   "confirm", "bestaetigen", etc.).

Das System-UI laeuft als `system_app` mit `REBOOT`-Permission und fuehrt den
eigentlichen Shutdown aus.

**Fallbacks** (fuer aeltere Android-Versionen, schlagen auf Android 16 fehl):
- Raw `IBinder.transact(54)` auf `IPowerManager` (Transaktion 54 = `shutdown()`)
- `reboot -p` Shell-Kommando

Tablet faehrt komplett herunter und startet **nicht** automatisch neu.
Beim naechsten manuellen Boot (Power-Button druecken) startet FreeKiosk automatisch
(Device Owner + Auto-Launch) und geht selbststaendig in den Lock-Task-Modus.
Der Anti-Theft-Alarm ist waehrend des Shutdowns nicht aktiv.

**Getestet auf:** QR-01 ({{SERIAL_QR_01}}, TB336FU, Android 16) — funktioniert im
Kiosk-Modus (Lock-Task LOCKED) und ohne Kiosk-Modus.

**Response (Erfolg):**
```json
{
  "success": true,
  "data": { "executed": true, "command": "shutdown", "pending": true }
}
```

> **Bug 10 Fix (v1.2.20-beta.3-patched2):** Wie bei `/api/reboot` wird die
> Response gesendet **bevor** der Shutdown ausgefuehrt wird. Der eigentliche
> Shutdown wird per `Handler.postDelayed(500ms)` deferred. Das `pending: true`
> Feld signalisiert die asynchrone Ausfuehrung. Clientseitig koennen
> `ECONNABORTED`/`ECONNRESET`/`ECONNREFUSED` als **erwarteter Erfolg** gewertet
> werden.

**Response (kein Device Owner):**
```json
{
  "success": true,
  "data": {
    "executed": false,
    "command": "shutdown",
    "error": "Shutdown requires Device Owner mode"
  }
}
```

**Response (alle Methoden fehlgeschlagen):**
```json
{
  "success": true,
  "data": {
    "executed": false,
    "command": "shutdown",
    "error": "All shutdown methods failed"
  }
}
```

### `GET|POST /api/lock`

Display sperren via `DPM.lockNow()` (Device Owner) oder
`GLOBAL_ACTION_LOCK_SCREEN` (AccessibilityService).

### `GET|POST /api/clearCache`

WebView Cache, Cookies, localStorage, Form Data loeschen + Full WebView remount.

---

## WebView-Navigation (GET/POST)

### `POST /api/url`

Zu neuer URL navigieren.

> **Bug 9 Fix (v1.2.20-beta.3-patched2):** Der JSON-Body-Parser ist jetzt
> tolerant gegenueber PowerShell-escaped JSON (`{\"url\":\"...\"}` mit
> Backslash-Escapes). Bei einem strikten Parse-Fehler wird automatisch ein
> Fallback versucht, der Backslash-Escapes entfernt und neu parst. Auch
> fuehrende/trailing Whitespaces und BOM werden toleriert. Betrifft alle
> POST-Endpoints mit JSON-Body, nicht nur `/api/url`.

**Request:**
```json
{ "url": "https://example.com" }
```

**PowerShell-Beispiel (funktioniert jetzt direkt):**
```powershell
curl.exe -X POST "http://localhost:8081/api/url" `
  -H "X-Api-Key: {{API_KEY}}" `
  -H "Content-Type: application/json" `
  -d '{\"url\":\"https://example.com\"}'
```

### `POST /api/navigate`

Alias fuer `/api/url`.

### `GET|POST /api/reload`

Aktuelle WebView-Seite neu laden.

### `GET|POST /api/webview/back`

WebView Zurueck.

### `GET|POST /api/webview/forward`

WebView Vorwaerts.

### `GET|POST /api/webview/scroll-top`

Zum Anfang der Seite scrollen.

### `GET|POST /api/home`

Zur Start-URL navigieren (Home-Button Funktion).

### `POST /api/mode`

Anzeigemodus zur Laufzeit wechseln.

**WebView-Modus:**
```json
{ "mode": "webview", "url": "https://example.com" }
```
`url` ist optional (ohne = aktuelle URL behalten).

**External App-Modus:**
```json
{ "mode": "external_app", "package": "com.example.app" }
```

---

## Banner / Anti-Theft (POST/GET)

### `POST /api/banner` *(Patch)*

Banner-Settings setzen. Schreibt Config-Keys (via `configWriter`) **und** sendet
sofortiges JS-Command fuer sofortige Anwendung (ohne `restart-ui`).

**Request:**
```json
{
  "banner_enabled": true,
  "banner_text": "Diebstahl zwecklos. Das Tablet funktioniert nur in der Praxis. Tablet ist alarmgesichert.",
  "banner_font_size": 25,
  "banner_show_icon": true
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "executed": true,
    "applied": 4,
    "appliedKeys": ["banner_enabled", "banner_text", "banner_font_size", "banner_show_icon"]
  }
}
```

### `GET|POST /api/banner/hide`

Banner sofort ausblenden (JS-Command, nicht persistent).

---

## PIN-Verwaltung (POST)

### `POST /api/pin` *(Patch 18)*

PIN aendern. Wenn bereits ein PIN gesetzt ist, muss `old_pin` verifiziert werden -
ausser mit `force: true` (API-Key als Autorisierung).

**Request (mit alter PIN-Verifikation):**
```json
{ "old_pin": "1234", "new_pin": "{{PIN}}" }
```

**Request (mit force, ohne old_pin):**
```json
{ "new_pin": "{{PIN}}", "force": true }
```

**Response (Erfolg):**
```json
{
  "success": true,
  "data": {
    "success": true,
    "message": "PIN changed successfully"
  }
}
```

**Response (Fehler - old_pin falsch):**
```json
{
  "success": false,
  "error": "old_pin is incorrect"
}
```

**Response (Fehler - old_pin fehlt):**
```json
{
  "success": false,
  "error": "old_pin is required to change an existing PIN"
}
```

---

## Audio (POST/GET)

### `POST /api/audio/play`

```json
{ "url": "https://example.com/sound.mp3", "loop": false, "volume": 50 }
```

### `GET|POST /api/audio/stop`

### `GET|POST /api/audio/beep`

### `POST /api/volume`

```json
{ "value": 50 }
```

### `POST /api/tts`

Text-to-Speech via Android native TTS.

```json
{ "text": "Hello World", "language": "en-US" }
```

`language` ist optional (BCP 47 Tag). Ohne wird auto-detektiert.

---

## Remote Control & Keyboard (GET/POST)

### D-Pad / Android TV

| Endpoint | Key |
|---|---|
| `GET\|POST /api/remote/up` | D-pad Up |
| `GET\|POST /api/remote/down` | D-pad Down |
| `GET\|POST /api/remote/left` | D-pad Left |
| `GET\|POST /api/remote/right` | D-pad Right |
| `GET\|POST /api/remote/select` | Select/Enter |
| `GET\|POST /api/remote/back` | Back |
| `GET\|POST /api/remote/home` | Home |
| `GET\|POST /api/remote/menu` | Menu |
| `GET\|POST /api/remote/playpause` | Play/Pause |

**Response (Beispiel):**
```json
{
  "success": true,
  "data": { "executed": true, "command": "remoteKey" }
}
```

### `GET|POST /api/remote/keyboard/{key}`

Einzelne Tastatur-Taste druecken.

```bash
curl http://TABLET_IP:8080/api/remote/keyboard/enter
curl http://TABLET_IP:8080/api/remote/keyboard/f5
```

**Supported keys:** a-z, 0-9, f1-f12, up/down/left/right/home/end/pageup/pagedown,
enter/return, space, tab, backspace, delete/del, insert, escape/esc, capslock,
numlock, scrolllock, shift, ctrl/control, alt, meta/win/cmd, playpause, play, pause,
stop, next/nexttrack, previous/prevtrack, volumeup, volumedown, mute, back, menu,
search, power, select/center, androidhome, period/dot, comma, minus/dash, plus,
equals, semicolon, apostrophe/quote, slash, backslash, leftbracket, rightbracket,
grave/backtick, at, pound/hash, star/asterisk, oder jedes einzelne Zeichen.

### `GET|POST /api/remote/keyboard?map={combo}`

Tastatur-Shortcut mit Modifier.

```bash
curl "http://TABLET_IP:8080/api/remote/keyboard?map=ctrl+c"
curl "http://TABLET_IP:8080/api/remote/keyboard?map=ctrl+shift+a"
```

**Response:**
```json
{
  "success": true,
  "data": {
    "executed": true,
    "command": "keyboardCombo",
    "map": "ctrl+c",
    "key": "c",
    "keyCode": 31,
    "modifiers": ["ctrl"],
    "metaState": 28672
  }
}
```

### `POST /api/remote/text`

Text in fokussiertes Eingabefeld tippen.

```json
{ "text": "Hello World!" }
```

**Response:**
```json
{
  "success": true,
  "data": {
    "executed": true,
    "command": "keyboardText",
    "textLength": 12
  }
}
```

---

## Code-Injection (POST)

### `POST /api/js`

JavaScript in WebView ausfuehren.

```json
{ "code": "alert('Hello!')" }
```

### `POST /api/inject-css` *(Patch)*

CSS in WebView injizieren.

```json
{ "css": "body { background-color: red; }" }
```

### `POST /api/inject-js` *(Patch)*

JS in WebView injizieren (persistent optional).

```json
{ "code": "console.log('injected')", "persistent": false }
```

| Field | Type | Default | Description |
|---|---|---|---|
| `code` | string | - | JS-Code (required) |
| `persistent` | boolean | false | Bei true: wird bei Reload/Navigation neu injiziert |

---

## URL-Rotation (GET/POST)

### `GET /api/rotation`

URL-Rotation-Status.

### `GET|POST /api/rotation/start`

URL-Rotation starten.

### `GET|POST /api/rotation/stop`

URL-Rotation stoppen.

---

## Sensoren, Storage, Memory (GET)

### `GET /api/sensors`

```json
{
  "success": true,
  "data": {
    "light": 150.5,
    "proximity": 5,
    "accelerometer": { "x": 0.1, "y": 0.2, "z": 9.8 }
  }
}
```

### `GET /api/storage`

```json
{
  "success": true,
  "data": {
    "totalMB": 32000,
    "availableMB": 15000,
    "usedMB": 17000,
    "usedPercent": 53
  }
}
```

### `GET /api/memory`

```json
{
  "success": true,
  "data": {
    "totalMB": 4096,
    "availableMB": 2048,
    "usedMB": 2048,
    "usedPercent": 50,
    "lowMemory": false
  }
}
```

---

## Kamera & Screenshot (GET)

### `GET /api/screenshot`

Screenshot als `image/png` (binuer).

```bash
curl http://TABLET_IP:8080/api/screenshot -o screenshot.png
```

### `GET /api/camera/photo?camera={front|back}&quality={1-100}`

Foto mit Geraetekamera als `image/jpeg` (binuer).

> **Bug 7 Fix (v1.2.20-beta.3-patched2):** Bei `camera=front` wird vor dem
> Front-Kamera-Aufruf automatisch kurz die Back-Kamera geoeffnet und sofort
> wieder geschlossen (Pre-Init), um den `CameraManager` auf Lenovo TB336FU /
> Android 16 zu initialisieren. Ohne diesen Pre-Init schlug `camera=front`
> fehl, wenn nicht zuvor ein anderer Kamera-Aufruf (z.B. `camera=back`) den
> CameraManager initialisiert hatte. Der Pre-Init adds ~100ms Latenz und ist
> transparent fuer den Client.

### `GET /api/camera/list`

```json
{
  "success": true,
  "data": {
    "cameras": [
      { "id": "0", "facing": "back", "maxWidth": 4032, "maxHeight": 3024 },
      { "id": "1", "facing": "front", "maxWidth": 2560, "maxHeight": 1920 }
    ]
  }
}
```

---

## GPS (GET)

### `GET /api/location`

```json
{
  "success": true,
  "data": {
    "executed": true,
    "command": "getLocation",
    "available": true,
    "latitude": 48.8566,
    "longitude": 2.3522,
    "accuracy": 15.0,
    "altitude": 35.0,
    "speed": 0.0,
    "bearing": 0.0,
    "provider": "gps",
    "time": 1704672000000,
    "providers": ["gps", "network", "passive"]
  }
}
```

Wenn kein GPS verfuegbar:
```json
{
  "success": true,
  "data": {
    "executed": true,
    "command": "getLocation",
    "available": false,
    "error": "No location available. Ensure GPS is enabled and location permission is granted.",
    "providers": ["network", "passive"]
  }
}
```

---

## Tabelle: Alle Endpoints

### GET (Read-only)

| Endpoint | Beschreibung | Patch |
|---|---|---|
| `/api/status` | Vollstaendiger Geraetestatus | - |
| `/api/battery` | Akku-Status | - |
| `/api/brightness` | Aktuelle Helligkeit | - |
| `/api/screen` | Display-Status (on, brightness, screensaver) | - |
| `/api/wifi` | WiFi-Status | - |
| `/api/info` | Geraete-Info (IP, Version, DeviceOwner, KioskMode) | - |
| `/api/health` | Health check | - |
| `/api/volume` | Aktuelle Lautstaerke | - |
| `/api/volume/keys` | Volume-Hardwaretasten Status (DPM-Restriction + Config) | - |
| `/api/rotation` | URL-Rotation-Status | - |
| `/api/sensors` | Licht, Naeherung, Beschleunigung | - |
| `/api/storage` | Speicher-Info | - |
| `/api/memory` | RAM-Info | - |
| `/api/screenshot` | Screenshot (PNG) | - |
| `/api/camera/photo` | Foto (JPEG) | - |
| `/api/camera/list` | Verfuegbare Kameras | - |
| `/api/location` | GPS-Koordinaten | - |
| `/api/autoBrightness` | Auto-Brightness-Status | - |
| `/api/wifi/lock-status` | WiFi-Lock-Status | Patch 19 |
| `/api/config` | Aktuelle Config aus AsyncStorage lesen | Patch 21 |
| `/api/config/:key` | Einzelnen Config-Key lesen (inkl. `device_serial`, `device_mac`) | Patch 21/24 |

### GET|POST (Control - kein Body noetig)

| Endpoint | Beschreibung | Patch |
|---|---|---|
| `/api/screen/on` | Display an | - |
| `/api/screen/off` | Display aus | - |
| `/api/screensaver/on` | Screensaver aktivieren | - |
| `/api/screensaver/off` | Screensaver deaktivieren | - |
| `/api/screensaver/wake-content/on` | Wake on content change an | - |
| `/api/screensaver/wake-content/off` | Wake on content change aus | - |
| `/api/reload` | WebView neu laden | - |
| `/api/wake` | Aus Screensaver aufwecken | - |
| `/api/reboot` | Neustart (Device Owner) | - |
| `/api/shutdown` | Herunterfahren / Power Off (Device Owner + A11y) | Patch 22 |
| `/api/clearCache` | WebView Cache loeschen | - |
| `/api/lock` | Display sperren | - |
| `/api/restart-ui` | UI neustarten + pending Config anwenden | Patch 6 |
| `/api/audio/stop` | Audio stoppen | - |
| `/api/audio/beep` | Beep abspielen | - |
| `/api/webview/back` | WebView Zurueck | - |
| `/api/webview/forward` | WebView Vorwaerts | - |
| `/api/webview/scroll-top` | Zum Anfang scrollen | - |
| `/api/home` | Zur Start-URL | - |
| `/api/rotation/start` | URL-Rotation starten | - |
| `/api/rotation/stop` | URL-Rotation stoppen | - |
| `/api/wifi/unlock` | WiFi-Lock deaktivieren | Patch 19 |
| `/api/banner/hide` | Banner ausblenden | Patch |
| `/api/remote/{key}` | D-Pad / Android TV Keys | - |
| `/api/remote/keyboard/{key}` | Einzelne Tastatur-Taste | - |
| `/api/remote/keyboard?map=` | Tastatur-Shortcut | - |

### POST (mit JSON Body)

| Endpoint | Body | Beschreibung | Patch |
|---|---|---|---|
| `/api/brightness` | `{"value": 75}` | Helligkeit setzen | - |
| `/api/volume` | `{"value": 50}` | Lautstaerke setzen | - |
| `/api/volume/keys/block` | - | Volume-Hardwaretasten blockieren (DISALLOW_ADJUST_VOLUME) | - |
| `/api/volume/keys/unblock` | - | Volume-Hardwaretasten entsperren (Recovery nach Alarm-Crash) | - |
| `/api/url` | `{"url": "https://..."}` | URL navigieren | - |
| `/api/navigate` | `{"url": "https://..."}` | Alias fuer /api/url | - |
| `/api/mode` | `{"mode": "webview", "url": "..."}` | Anzeigemodus wechseln | - |
| `/api/tts` | `{"text": "...", "language": "..."}` | Text-to-Speech | - |
| `/api/toast` | `{"text": "..."}` | Toast anzeigen | - |
| `/api/app/launch` | `{"package": "..."}` | App starten | - |
| `/api/js` | `{"code": "..."}` | JS ausfuehren | - |
| `/api/audio/play` | `{"url": "...", "loop": false, "volume": 50}` | Audio abspielen | - |
| `/api/remote/text` | `{"text": "..."}` | Text tippen | - |
| `/api/autoBrightness/enable` | `{"min": 10, "max": 100, "offset": 10}` | Auto-Brightness an | - |
| `/api/autoBrightness/disable` | - | Auto-Brightness aus | - |
| `/api/config` | `{"key": "value", ...}` | Config-Keys setzen | Patch 3 |
| `/api/inject-css` | `{"css": "..."}` | CSS injizieren | Patch |
| `/api/inject-js` | `{"code": "...", "persistent": false}` | JS injizieren | Patch |
| `/api/wifi/lock` | `{"allowed_ssids": ["SSID1", "SSID2"]}` | WiFi-Lock aktivieren | Patch 19 |
| `/api/wifi/allow` | `{"ssid": "..."}` | SSID erlauben | Patch 19 |
| `/api/pin` | `{"old_pin": "...", "new_pin": "..."}` oder `{"new_pin": "...", "force": true}` | PIN aendern | Patch 18 |
| `/api/banner` | `{"banner_enabled": true, "banner_text": "...", ...}` | Banner setzen | Patch |

---

## Webinterface-Empfehlungen

Fuer ein Verwaltungs-Webinterface sind diese Endpoints am wichtigsten:

**Discovery & Monitoring:**
- `GET /api/status` - Alles in einem Call (Battery, Screen, WiFi, Kiosk, Storage, Memory)
- `GET /api/wifi/lock-status` - WiFi-Lock & Diebstahl-Erkennung
- `GET /api/info` - Device Owner, KioskMode, IP, Version
- `GET /api/health` - Quick alive-check

**Verwaltung:**
- `GET /api/config` - Aktuelle Config auslesen (live, ohne Cache)
- `POST /api/config` + `POST /api/restart-ui` - Config aendern und anwenden
- `POST /api/wifi/lock` - WiFi-Lock mit erlaubten SSIDs setzen
- `POST /api/banner` - Banner-Text setzen
- `POST /api/pin` - PIN aendern

**Power-Control:**
- `POST /api/reboot` - Neustart
- `POST /api/shutdown` - Herunterfahren (fuer Transport)
- `POST /api/screen/on` / `POST /api/screen/off` - Display an/aus
- `POST /api/lock` - Display sperren

**WebView:**
- `POST /api/url` - URL wechseln
- `GET|POST /api/reload` - Seite neu laden
- `GET /api/screenshot` - Screenshot fuer Live-Vorschau

**Polling-Strategie:**
- `GET /api/health` alle 5-10 Sekunden (alive-check, schnell)
- `GET /api/status` alle 30-60 Sekunden (vollstaendiger Status)
- `GET /api/wifi/lock-status` alle 30 Sekunden (Diebstahl-Erkennung)

**Tablet-IPs:**

| Tablet | Serial | IP | Rolle | APK | Battery-Optimierung |
|---|---|---|---|---|---|
| QR-01 | {{SERIAL_QR_01}} | {{IP_QR_01}} | QR-Display | patched5 | AN (Schutz 40-60% + Ladungsoptimierung + 80% Limit) |
| QR-02 | {{SERIAL_QR_02}} | {{IP_QR_02}} | QR-Display | patched5 | AN (Schutz 40-60% + Ladungsoptimierung + 80% Limit) |
| QR-03 | {{SERIAL_QR_03}} | {{IP_QR_03}} | QR-Display | patched5 | AN (Schutz 40-60% + Ladungsoptimierung + 80% Limit) |
| PAT-01 | {{SERIAL_PAT_01}} | {{IP_PAT_01}} | Patienten-Tablet | patched5 | AUS |
| PAT-02 | {{SERIAL_PAT_02}} | {{IP_PAT_02}} | Patienten-Tablet | patched5 | AUS |
| PAT-03 | {{SERIAL_PAT_03}} | {{IP_PAT_03}} | Patienten-Tablet | patched5 | AUS |
