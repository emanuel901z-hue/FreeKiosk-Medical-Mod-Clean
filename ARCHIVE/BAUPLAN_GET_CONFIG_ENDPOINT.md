# Patch 21: `GET /api/config` Endpoint (implementiert)

## Zusammenfassung

FreeKiosk hatte keinen Endpoint, um die aktuell in AsyncStorage gespeicherte Config auszulesen. `POST /api/config` konnte nur schreiben, `GET /api/status` lieferte nur Runtime-Daten.

Dieser Patch fuegt einen lesenden `GET /api/config` Endpoint (und optional `GET /api/config/:key`) hinzu. Damit kann TabControl die Config live vom Tablet abrufen und muss sie nicht mehr in einer eigenen DB spiegeln.

**Status:** Implementiert, gebaut und auf Test-Tablet `{{SERIAL_PAT_03}}` verifiziert.

---

## Geanderte Dateien

| Datei | Aenderung |
|---|---|
| `freekiosk-main/android/app/src/main/java/com/freekiosk/api/KioskHttpServer.kt` | Neue Routen `GET /api/config` und `GET /api/config/:key`; `configReader`/`configKeyReader` Konstruktorparameter; API-Wurzel-Doku aktualisiert. |
| `freekiosk-main/android/app/src/main/java/com/freekiosk/api/HttpServerModule.kt` | Neue Methoden `handleGetConfig()` und `handleGetConfigByKey()`; `getAsyncStorageValueOrNull()` Hilfsmethode; `KioskHttpServer`-Instanziierung erweitert. |
| `freekiosk-v1.2.20-beta.3-patched6.apk` | Neu gebaute, installierbare APK. |

---

## Implementierungsdetails

### `HttpServerModule.kt`

- `getAsyncStorageValueOrNull(key: String): String?` liest direkt aus der React-Native-AsyncStorage-SQLite-DB (`RKStorage`, Tabelle `catalystLocalStorage`).
- `handleGetConfig()` iteriert ueber das bestehende `configKeyMapping` und baut ein JSON-Objekt mit allen gesetzten Config-Keys. Nicht gesetzte Keys (value == null) werden ausgelassen.
- `handleGetConfigByKey(key: String): JSONObject?` liefert fuer einen einzelnen externen Key `{ "key": "...", "value": "..." }` oder `null` wenn der Key unbekannt ist.

### `KioskHttpServer.kt`

- `configReader` und `configKeyReader` werden als Lambda-Funktionen uebergeben und von `handleGetConfig()` bzw. `handleGetConfigKey()` aufgerufen.
- Die Routen sind im `when`-Block eingefuegt:
  - `GET /api/config` -> `handleGetConfig()`
  - `GET /api/config/:key` -> `handleGetConfigKey(uri)`
- `/api/config` wurde aus `postOnlyUris` entfernt, damit `GET` nicht mehr mit `405 Method Not Allowed` abgelehnt wird.
- Fehlerhafte oder unbekannte einzelne Keys liefern `400 Bad Request` mit `{"success":false,"error":"Unknown config key: ..."}`.

### Response-Format

**`GET /api/config`:**

```json
{
  "success": true,
  "data": {
    "url": "https://www.med-mv.de/qr-code-scanner/",
    "home_button_enabled": "true",
    "home_button_x_percent": "95",
    "screen_scheduler_enabled": "true",
    "screen_scheduler_rules": "[...]",
    "...": "..."
  },
  "timestamp": 1783603200
}
```

**`GET /api/config/home_button_enabled`:**

```json
{
  "success": true,
  "data": { "key": "home_button_enabled", "value": "true" },
  "timestamp": 1783603217
}
```

**Falsches API-Key:**

```json
{ "success": false, "error": "Invalid or missing API key", "timestamp": 1783603217 }
```

**Unbekannter Key:**

```json
{ "success": false, "error": "Unknown config key: unknown_key", "timestamp": 1783603217 }
```

---

## Verifikation (durchgefuehrt)

Auf dem Test-Tablet `{{SERIAL_PAT_03}}` (09.07.2026):

```powershell
# APK installieren und App neu starten
.\adb.exe install -r "freekiosk-v1.2.20-beta.3-patched6.apk"
.\adb.exe shell am force-stop com.freekiosk
Start-Sleep -Seconds 3
.\adb.exe shell am start -n com.freekiosk/.MainActivity --activity-clear-task

# ADB-Forward + Tests
.\adb.exe forward tcp:8081 tcp:8080

# Gesamte Config lesen
curl.exe -s "http://localhost:8081/api/config" -H "X-Api-Key: {{API_KEY}}"

# Einzelnen Key lesen
curl.exe -s "http://localhost:8081/api/config/home_button_enabled" -H "X-Api-Key: {{API_KEY}}"

# Unbekannter Key
curl.exe -s "http://localhost:8081/api/config/unknown_key" -H "X-Api-Key: {{API_KEY}}"

# Falsches API-Key
curl.exe -s "http://localhost:8081/api/config" -H "X-Api-Key: WRONG_KEY"
```

Alle vier Tests erfolgreich. Response-Format entspricht der Spezifikation.

---

## Build & Deployment

```powershell
cd D:\Projekte\KIOSK-TABS\freekiosk-main\android
$env:JAVA_HOME = "C:\Users\Manu\AppData\Local\jdk17\jdk-17.0.13+11"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleRelease --max-workers=1

Copy-Item "app\build\outputs\apk\release\app-release.apk" "..\..\freekiosk-v1.2.20-beta.3-patched6.apk" -Force
```

---

## Hinweis fuer TabControl

Sobald `GET /api/config` auf allen Tablets verfuegbar ist, kann TabControl:

- `GET /tablets/:id/config` live vom Tablet abfragen.
- `lastConfig` als Cache/Fallback fuer Offline-Tablets behalten.
- `GET /api/config` regelmaessig im Polling-Service abfragen und in `lastConfig` cachen.

Siehe urspruengliche Planung im Abschnitt "Was TabControl danach aendert" (weiterhin gueltig).
