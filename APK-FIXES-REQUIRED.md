# APK-Fixes: Was an der FreeKiosk-APK angepasst werden muss

> **Stand:** 07.09.2026
> **APK-Version:** `1.2.20-beta.3-patched6` (aktuell; Bug-Fixes 1-4, 7, 9-13, 15-17
> sind seit `patched3` enthalten, Patches 24-26 kamen danach dazu)
> **Getestet auf:** Lenovo TB336FU, Android 16, RN 0.82
> **TabControl-Workarounds aktiv:** restart-ui-Patch (kein activity.recreate)
> **Überfluessig gewordene Workarounds:** BrightnessEnforcer (Bug 1+2),
> Front-Kamera-Pre-Init-Workaround (Bug 7), Error-Toleranz für reboot/shutdown
> (Bug 10), PowerShell-JSON-Workaround (Bug 9)

Diese Datei listet alle bekannten APK-Probleme auf. Bug 1-4, 7, 9-13, 15-17
sind in `v1.2.20-beta.3-patched3` (20.07.2026) fixiert und auf {{SERIAL_PAT_03}}
verifiziert. Bug 5, 6, 8 sind durch bestehende Patches/Workarounds kompensiert.
Die danach ergaenzten Patches 24 (Device Identity), 25 (Keyguard-Fix) und
26 (DHCP Auto-Recovery) sind in `AGENTS.md` und `REST-API.md` dokumentiert.

---

## Prioritaet 1 — Screensaver & Brightness (3 Bugs)

Diese Bugs werden aktuell durch den **BrightnessEnforcer** (TabControl Backend)
kompensiert. Der Enforcer pollt alle 15s `/api/screen` und setzt manuell
`/api/brightness`. Das ist fehleranfaellig (Netzwerk-Ausfaelle, Timing-Luecken)
und sollte in der APK selbst behoben werden.

### Bug 1: Screensaver dimmt die Helligkeit nicht

**Symptom:** Der Screensaver aktiviert nach `screensaver_delay` korrekt den
Overlay (`screensaverActive: true` in `/api/screen`), aber die Helligkeit wird
**nicht** auf `screensaver_brightness` gesenkt — das Display bleibt bei 100%.

**Erwartetes Verhalten:** Wenn `screensaverActive=true` und
`brightness_management_enabled=true`, sollte die Helligkeit automatisch auf
`screensaver_brightness` (z.B. 0.01 = 1%) sinken.

**Ursache (vermutet):** Die Brightness-Management-Logik in `KioskScreen.tsx`
reagiert nicht auf den Screensaver-Statuswechsel, oder der
`setBrightness()`-Aufruf wird durch einen konkurrierenden AsyncStorage-Writer
(Bug 2) sofort revertiert.

**Aktueller Workaround:** TabControl BrightnessEnforcer pollt `/api/screen`
und sendet bei `screensaverActive=true` manuell `POST /api/brightness` mit dem
Wert aus `screensaver_brightness`.

**Fix in APK:**

- In `KioskScreen.tsx`: Beim Screensaver-Activate-Event (`screensaverActive`
  wechselt false→true) explizit `setBrightness(screensaver_brightness)` aufrufen.
- Beim Screensaver-Deactivate-Event (`screensaverActive` wechselt true→false)
  explizit `setBrightness(default_brightness)` aufrufen.
- Sicherstellen, dass diese Aufrufe nicht durch konkurrierende
  AsyncStorage-Writer (Bug 2) revertiert werden.

### Bug 2: default_brightness wird kontinuierlich revertet

**Symptom:** `default_brightness` in AsyncStorage wird kontinuierlich auf
`screensaver_brightness` (0.01) zurueckgesetzt, selbst wenn der Screensaver
inaktiv oder deaktiviert ist. Ein `setConfig({default_brightness: '1'})` +
`restart-ui` wird nach ~15s revertet. `GET /api/config` zeigt kurzfristig `1`,
dann wieder `0.01`.

**Erwartetes Verhalten:** `default_brightness` sollte stabil bleiben und nur
durch explizite Config-Aenderungen (`POST /api/config` + `restart-ui`)
geaendert werden.

**Ursache (vermutet):** Ein Background-Task oder Effect in der APK schreibt
zunaechst `screensaver_brightness` in `default_brightness`, bevor er den
Screensaver-Status prueft. Race Condition zwischen Brightness-Management-Init
und AsyncStorage-Writer.

**Aktueller Workaround:** TabControl BrightnessEnforcer ignoriert
`default_brightness` komplett und setzt bei inaktivem Screensaver fest 100%
(`BRIGHTNESS_ENFORCER_DEFAULT`). Dadurch kann `default_brightness` nicht
zuverlaessig als "Helligkeit bei aktivem Display" verwendet werden.

**Fix in APK:**

- Den AsyncStorage-Writer fuer `default_brightness` entfernen oder so
  einschraenken, dass er nur beim tatsaechlichen Screensaver-Statuswechsel
  (nicht kontinuierlich) feuert.
- `default_brightness` sollte nur durch `POST /api/config` geaendert werden,
  nicht durch interne Background-Logik.

### Bug 3: screensaver/off beschädigt den Inaktivitaets-Timer dauerhaft

**Symptom:** Ein `POST /api/screensaver/off` API-Aufruf beschädigt den internen
Inaktivitaets-Timer der APK. Nach dem Aufwecken des Screensavers
(wake/touch/content-change) startet der Timer nicht mehr automatisch neu — der
Screensaver aktiviert sich nie wieder von selbst. Nur ein Reboot stellt den
Normalzustand wieder her.

**Erwartetes Verhalten:** `screensaver/off` sollte das Screensaver-Feature
sauber deaktivieren (Overlay aus, Helligkeit zurueck) und bei einem
nachfolgenden `screensaver/on` sollte der Inaktivitaets-Timer neu bewaffnet
werden.

**Ursache (vermutet):** `screensaver/off` setzt einen internen Flag
(`screensaverEnabled=false`), aber `screensaver/on` bewaffnet den
Inaktivitaets-Timer nicht neu — es setzt nur den Flag zurueck auf `true`.
Der Timer-Reset fehlt.

**Aktueller Workaround:** TabControl ruft **niemals** `screensaver/off` auf.
Der Inaktivitaets-Timer wird in normalem Betrieb durch Content-Changes
(z.B. neuer QR-Code per Webhook → DOM-Update im WebView) re-bewaffnet.
`wake` allein re-bewaffnet den Timer nicht.

**Fix in APK:**

- In `screensaver/on` Handler: Explizit den Inaktivitaets-Timer neu bewaffnen
  (z.B. `inactivityTimer.reset()` oder aequivalent).
- In `screensaver/off` Handler: Timer sauber stoppen (nicht nur Flag setzen).
- Test-Szenario: `screensaver/off` → `wake` → `screensaver/on` → 2 Min warten
  → Screensaver muss von selbst aktivieren.

---

## Prioritaet 2 — Inaktivitaets-Timer Re-Arm (1 Bug)

### Bug 4: wake re-bewaffnet den Inaktivitaets-Timer nicht

**Symptom:** Nach dem Aufwecken des Screensavers per `POST /api/wake` startet
der Inaktivitaets-Timer nicht automatisch neu. Der Screensaver aktiviert sich
nie wieder von selbst (ausser durch Content-Change im WebView).

**Erwartetes Verhalten:** `wake` sollte den Screensaver beenden und den
Inaktivitaets-Timer neu bewaffnen, sodass der Screensaver nach
`screensaver_delay` Inaktivitaet wieder aktiviert wird.

**Ursache (vermutet):** `wake` beendet nur den aktiven Screensaver-Overlay,
resettet aber den Inaktivitaets-Timer nicht. Nur Content-Changes (MutationObserver)
feuern den Timer-Reset.

**Aktueller Workaround:** Keiner (TabControl vermeidet `wake` fuer
Screensaver-Tests). In Produktion nicht kritisch, da QR-Codes per Webhook
Content-Changes ausloesen, die den Timer re-bewaffnen. Problematisch nur bei
manuellem Aufwecken per API ohne nachfolgenden Content-Change.

**Fix in APK:**

- In `wake` Handler: Nach Beendigung des Screensaver-Overlays den
  Inaktivitaets-Timer explizit neu bewaffnen (`inactivityTimer.reset()`).
- Alternativ: Ein generisches `onUserActivity` Event einfuehren, das sowohl
  bei touch, wake als auch bei content-change den Timer resettet.

---

## Prioritaet 3 — Stabilitaet & Crashes (2 Bugs)

### Bug 5: activity.recreate() crashen auf Android 16 / RN 0.82

**Symptom:** `activity.recreate()` und `ReactHost.reload()` crashen die App
auf Android 16 / RN 0.82 mit WebView-Ref-Invalidierung waehrend `pauseMedia`.

**Erwartetes Verhalten:** Config-Aenderungen sollten ohne App-Neustart
angewendet werden koennen.

**Ursache:** RN 0.82 WebView-Ref-Invalidierung durante `onResume` nach
`onNewIntent`. `findNodeHandle(wv)` crasht mit
`JavascriptException: Argument appears to not be a ReactComponent`.

**Aktueller Workaround:** Patch 6 — `restart-ui` schreibt Config-Keys direkt
in AsyncStorage und sendet `freekiosk_config_changed` JS-Event, ohne
`activity.recreate()`. Die App laeuft ununterbrochen weiter.

**Fix in APK:** Workaround bereits als Patch 6 implementiert. Langfristig
sollte RN auf eine Version aktualisiert werden, die Android 16 vollstaendig
unterstuetzt (RN 0.83+ oder RN-New-Architecture stable).

### Bug 6: pauseMedia/resumeMedia crash mit findNodeHandle

**Symptom:** `findNodeHandle(wv)` in `pauseMedia`/`resumeMedia` crasht mit
`JavascriptException: Argument appears to not be a ReactComponent` beim
`onResume` nach `onNewIntent`.

**Ursache:** WebView-Ref ist nach `onNewIntent` invalidiert.

**Aktueller Workaround:** `findNodeHandle(wv)` entfernt. JS-seitige
Media-Pause via `injectJavaScript` funktioniert weiterhin.

**Fix in APK:** Workaround bereits implementiert. Langfristig RN-Update
(siehe Bug 5).

---

## Prioritaet 4 — Hardware-Spezifisch (2 Bugs)

### Bug 7: Front-Kamera Init-Fehler (CameraManager)

**Symptom:** `cameraPhoto('front')` schlaegt fehl, wenn der CameraManager
nicht initialisiert ist. Erst nach Oeffnen der Rueck-Kamera funktioniert die
Front-Kamera.

**Erwartetes Verhalten:** Front-Kamera sollte direkt ohne Pre-Init
funktionieren.

**Ursache:** CameraManager-Init-Bug auf Lenovo TB336FU / Android 16. Die
Front-Kamera wird erst nach Initialisierung durch einen anderen Kamera-Aufruf
verfuegbar.

**Aktueller Workaround:** TabControl `cameraPhoto('front')` oeffnet zuerst
die Rueck-Kamera (quality=10, 5s Timeout) um den CameraManager zu
initialisieren, dann das eigentliche Front-Foto.

**Fix in APK:**

- In `KioskModule.kt` `cameraPhoto()`: Vor dem Front-Kamera-Aufruf
  `cameraManager.openCamera(BACK_CAMERA_ID, ...)` mit sofortigem Close
  ausfuehren, um den CameraManager zu initialisieren.
- Oder: CameraManager beim App-Start initialisieren (Lazy-Init im
  Application-Scope).

### Bug 8: Master-Mute nach lockNow() (Lenovo TB336FU / Android 16)

**Symptom:** `lockNow()` aktiviert Master-Mute auf AudioFlinger-Ebene.
`DISALLOW_ADJUST_VOLUME` blockiert Unmute. Der Tablet ist stumm, auch nach
Unlock.

**Erwartetes Verhalten:** `lockNow()` sollte die Audio-Ausgabe nicht
beeintraechtigen.

**Ursache:** Device Owner Restriction `DISALLOW_ADJUST_VOLUME` + AudioFlinger
Master-Mute auf Lenovo TB336FU / Android 16.

**Aktueller Workaround:** TabControl/FreeKiosk ruft nach `lockNow()`:
`dpm.clearUserRestriction(DISALLOW_ADJUST_VOLUME)` +
`dpm.setMasterVolumeMuted(false)` + `AudioManager.ADJUST_UNMUTE` auf allen
Streams auf. Verifikation: `adb shell dumpsys media.audio_flinger | grep
"Master mute"` → muss `off` sein.

**Fix in APK:** Workaround bereits in `AlarmService.kt` implementiert.
Langfristig sollte `lockNow()` den Master-Mute nicht aktivieren (Device Owner
Policy-Anpassung oder Vendor-Fix).

---

## Prioritaet 5 — REST-API Robustheit (2 Bugs)

### Bug 9: /api/url Body-Parsing empfindlich

**Symptom:** PowerShell-escaped JSON mit Backslashes (`{\"url\":...}`) wird
vom FreeKiosk-Server abgelehnt (`JSONException: Expected literal value at
character 1 of {\`).

**Erwartetes Verhalten:** JSON-Body sollte robust geparst werden, auch mit
escapten Zeichen.

**Ursache:** JSON-Parser im KioskHttpServer ist strikt und akzeptiert keine
zusaetzlichen Escape-Sequenzen.

**Aktueller Workaround:** TabControl sendet JSON direkt via Axios (keine
PowerShell-Escaping-Probleme). Fuer manuelle Tests: JSON in Datei schreiben
und mit `-d "@body.json"` uebergeben.

**Fix in APK:** JSON-Parser toleranter machen oder Body als Raw-String lesen
und selbst parsen (mit Fehler-Toleranz fuer fuehrende/trailing Whitespaces).

### Bug 10: reboot/shutdown Connection-Reset

**Symptom:** `POST /api/reboot` und `POST /api/shutdown` fuehren zu
ECONNABORTED/ECONNRESET/ECONNREFUSED auf Client-Seite, da der HTTP-Server
waehrend der Response herunterfaehrt.

**Erwartetes Verhalten:** Der Server sollte eine 200-Response senden, bevor
er heruntergefahren wird.

**Ursache:** Reboot/Shutdown wird sofort ausgefuehrt, bevor die HTTP-Response
flushed ist.

**Aktueller Workaround:** TabControl `TabletClient.reboot()`/`shutdown()`
wertet ECONNABORTED/ECONNRESET/ECONNREFUSED/ETIMEDOUT/ERR_NETWORK als
erwarteten Erfolg (nicht als Fehler).

**Fix in APK:**

- In `KioskHttpServer.kt`: Response senden + flush, dann erst nach 500ms
  Delay reboot/shutdown ausfuehren (`Handler.postDelayed`).
- Oder: Response mit `Content-Length: 0` und `Connection: close` senden,
  dann Client-Connection sauber schliessen vor Reboot.

---

## Zusammenfassung: Workaround-Status

| Bug | Prioritaet | Status | Fix in APK | Workaround |
| --- | ---------- | ------ | ---------- | ---------- |
| 1 — Screensaver dimmt nicht | P1 | ✅ Fixiert (patched2) | KioskScreen.tsx: Brightness-Transition-Effect | BrightnessEnforcer jetzt überflüssig |
| 2 — default_brightness revert | P1 | ✅ Fixiert (patched2) | KioskScreen.tsx: onSetBrightness schreibt nicht mehr in default_brightness | BrightnessEnforcer jetzt überflüssig |
| 3 — screensaver/off Timer kaputt | P1 | ✅ Fixiert (patched2) | KioskScreen.tsx: resetTimerRef in API-Callbacks | Vermeidung nicht mehr nötig |
| 4 — wake re-armt Timer nicht | P2 | ✅ Fixiert (patched2) | KioskScreen.tsx: resetTimerRef.current() in onWake | — |
| 5 — activity.recreate() crash | P3 | ⚙️ Kompensiert | Patch 6 (restart-ui ohne recreate) | Aktiver Patch, RN-Update langfristig |
| 6 — findNodeHandle crash | P3 | ⚙️ Kompensiert | Patch (findNodeHandle entfernt) | Aktiver Patch, RN-Update langfristig |
| 7 — Front-Kamera Init | P4 | ✅ Fixiert (patched2) | CameraPhotoModule.kt: Pre-Init Back-Kamera | TabControl-Workaround überflüssig |
| 8 — Master-Mute nach lockNow | P4 | ⚙️ Kompensiert | AlarmService.kt: Unmute-Sequence | Aktiv, langfristig Vendor-Fix |
| 9 — /api/url JSON-Parsing | P5 | ✅ Fixiert (patched2) | KioskHttpServer.kt: toleranter parseBody-Fallback | Axios-Workaround überflüssig |
| 10 — reboot Connection-Reset | P5 | ✅ Fixiert (patched2) | KioskHttpServer.kt: Response-Delay per Handler.postDelayed | Error-Toleranz im Client überflüssig |
| 11 — SQLiteDatabase Leak restartUi | P2 | ✅ Fixiert (patched3) | HttpServerModule.kt: try/finally | — |
| 12 — SQLiteDatabase Leak savePin | P2 | ✅ Fixiert (patched3) | HttpServerModule.kt: try/finally | — |
| 13 — SQLiteDatabase Leak getConfig | P2 | ✅ Fixiert (patched3) | HttpServerModule.kt: try/finally | — |
| 14 — setTimeout Leak focus listener | P3 | ✅ Fixiert (patched3) | KioskScreen.tsx: blockingOverlayFocusTimeoutRef | — |
| 15 — Camera Surface Leak | P3 | ✅ Fixiert (patched3) | CameraPhotoModule.kt: dummy surface try/finally | — |
| 16 — Screen Scheduler Performance | P3 | ✅ Fixiert (patched3) | KioskScreen.tsx: enterScheduledSleepRef/exitScheduledSleepRef | — |
| 17 — WifiLock Handler Leak | P2 | ✅ Fixiert (patched3) | WifiLockModule.kt: removeCallbacksAndMessages(null) | — |
| 18 — handleAudioPlay Control-Check | — | ❌ False Positive | — | — (checkControlAllowed ist vorhanden) |
| 19 — Volume-Keys ohne API, stuck nach Alarm-Crash | P2 | ✅ Fixiert (patched4) | Patch 23: 3 neue REST-Endpunkte + Config-Key + enableKioskRestrictions/BootLock re-apply | Recovery per `POST /api/volume/keys/unblock` |

## Fix-Historie

- **v1.2.20-beta.3-patched** (09.07.2026): Patches 1-22 (Home-Button, WiFi-Lock,
  Anti-Theft, REST-Endpunkte, GET /api/config, restart-ui ohne recreate, etc.)
- **v1.2.20-beta.3-patched2** (20.07.2026): APK-Fixes Bug 1, 2, 3, 4, 7, 9, 10.
  Getestet auf {{SERIAL_PAT_03}} (TB336FU, Android 16). Alle 7 Fixes verifiziert.
- **v1.2.20-beta.3-patched3** (20.07.2026): APK-Fixes Bug 11-17 (Gap-Analyse).
  SQLiteDatabase-Leaks, Memory Leaks, Camera Resource Leak, Screen Scheduler
  Performance. Sanity-checked auf {{SERIAL_PAT_03}}.
- **v1.2.20-beta.3-patched4** (21.07.2026): Patch 23 — Volume-Keys Block/Unblock
  via REST-API. Neue Endpunkte `GET /api/volume/keys`,
  `POST /api/volume/keys/block`, `POST /api/volume/keys/unblock`. Fix für
  steckengebliebene `DISALLOW_ADJUST_VOLUME`-Restriction nach
  AntiTheftAlarm-Crash. Getestet auf QR-02 ({{SERIAL_QR_02}}): Block/Unblock/Status,
  Persistenz über restart-ui, Lock-Task bleibt LOCKED.

---

## Gap-Analyse (20.07.2026) — Bug 11-17

Diese Bugs wurden durch eine Code-Audit-Gap-Analyse gefunden (nicht durch
Produktions-Ausfälle). Alle 7 sind in `v1.2.20-beta.3-patched3` fixiert.

### Bug 11: SQLiteDatabase Leak in restartUi (P2)

**Symptom:** Bei Exceptions im Config-Apply-Loop (z.B. DB-Lock, corrupt table)
wird die SQLiteDatabase nicht geschlossen → potenziell
`SQLiteDatabaseLockedException` bei Folgezugriffen.

**Fix:** `db.close()` in `try/finally` Block gepackt. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\android\app\src\main\java\com\freekiosk\api\HttpServerModule.kt"
lines="1372-1395" />

### Bug 12: SQLiteDatabase Leak in savePinToAsyncStorage (P2)

**Symptom:** Gleiche Pattern wie Bug 11 — bei Exceptions zwischen
`openOrCreateDatabase` und `db.close()` bleibt die DB offen.

**Fix:** `db.close()` in `try/finally`. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\android\app\src\main\java\com\freekiosk\api\HttpServerModule.kt"
lines="636-660" />

### Bug 13: SQLiteDatabase Leak in getAsyncStorageValueOrNull (P2)

**Symptom:** Gleiche Pattern — bei Exceptions in `db.query()` oder
`cursor.getString()` bleibt die DB offen. Betrifft jeden `GET /api/config/:key`
und `GET /api/config` Aufruf.

**Fix:** `db.close()` und `cursor.close()` in `try/finally`. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\android\app\src\main\java\com\freekiosk\api\HttpServerModule.kt"
lines="668-692" />

### Bug 14: setTimeout nicht gecleart in KioskScreen focus listener (P3)

**Symptom:** Bei schnellem Unmount (z.B. sofortige Navigation zu PIN nach
focus) feuert der 300ms `setTimeout` für `BlockingOverlayModule.updateOverlays()`
trotzdem auf einem unmounted Screen.

**Fix:** `blockingOverlayFocusTimeoutRef` angelegt, `clearTimeout` im
useEffect-cleanup. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\src\screens\KioskScreen.tsx"
lines="89-91" />

### Bug 15: Camera Resource Leak bei Exception (P3)

**Symptom:** `dummySurface`/`dummyTexture` in `CameraPhotoModule.capturePhoto()`
werden nur im normalen Cleanup-Pfad released, nicht bei Exceptions (z.B.
`createCaptureSession` schlägt fehl). Graphics Memory Leak.

**Fix:** dummy surface/texture in `try/finally` gepackt. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\android\app\src\main\java\com\freekiosk\CameraPhotoModule.kt"
lines="177-280" />

### Bug 16: Screen Scheduler triggert unnötig oft (P3, Performance)

**Symptom:** `enterScheduledSleep`/`exitScheduledSleep` sind `useCallback`s
deren Dependencies (`autoBrightnessEnabled`, `defaultBrightness`, ...) sich
während App-Lifetime ändern. Jede Änderung retriggerte den Scheduler-useEffect
→ 30s Interval wurde resettet, `checkScreenSchedule()` sofort neu ausgeführt.

**Fix:** `enterScheduledSleepRef`/`exitScheduledSleepRef` als Refs, nur
`screenSchedulerEnabled` + `screenSchedulerRules` als useEffect-Dependencies.
Betrifft auch den native alarm event listener. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\src\screens\KioskScreen.tsx"
lines="1441-1456" />

### Bug 17: WifiLockModule Handler Callbacks nicht gecleart (P2)

**Symptom:** `onCatalystInstanceDestroy` ruft nur `stopMonitoring()` auf, aber
`mainHandler.removeCallbacksAndMessages(null)` fehlt. Pending
`postDelayed(checkNetwork, 3000)` aus `restoreWifiLockIfNeeded()` kann nach
Instance-Destroy feuern → stale `reactContext` Zugriff.

**Fix:** `mainHandler.removeCallbacksAndMessages(null)` in
`onCatalystInstanceDestroy` hinzugefügt. <ref_snippet
file="D:\Projekte\KIOSK-TABS\freekiosk-main\android\app\src\main\java\com\freekiosk\WifiLockModule.kt"
lines="90-96" />

### Bug 18: handleAudioPlay ohne Control-Check — FALSE POSITIVE

Der Subagent hat `checkControlAllowed()` übersehen — es ist in Zeile 756
vorhanden. Kein Bug, kein Fix nötig.

### Bug 19: Volume-Keys ohne API-Ansteuerung, "stuck" nach Alarm-Crash (P2) — FIXED in patched4

**Symptom:** Die Lautstärketasten (Hardware) konnten bisher nur vom
`AntiTheftAlarmModule` intern blockiert werden (`DISALLOW_ADJUST_VOLUME`
User-Restriction via `lockVolumeKeys()` in `AntiTheftAlarmModule.kt:344`).
Wenn der Alarm-Stop-Pfad fehlschlug (App-Crash, Exception während
`stopAlarm()`), blieb die Restriction aktiv — die Tasten waren dauerhaft
blockiert, ohne Möglichkeit das per API zu beheben. Ein betroffenes Tablet
musste physisch resettet werden.

**Ursache:** Kein Config-Key, keine AsyncStorage-Persistenz, kein REST-Endpunkt
für Volume-Keys. Die Restriction wurde nur transient im Alarm-Flow gesetzt
und cleared — ohne Recovery-Pfad bei Crash. Im Gegensatz zum Power-Button
(der über `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` + Config-Key
`@kiosk_allow_power_button` + `enableKioskRestrictions()` sauber
durchgereicht wird), gab es für Volume-Keys keine analoge Infrastruktur.

**Fix (Patch 23):** Drei neue REST-Endpunkte, analog zum Power-Button-Pattern:
- `GET /api/volume/keys` — liest den **tatsächlichen DPM-Restriction-State**
  (`allowed`, `restrictionActive`, `configAllowed`), nicht nur die Config.
  Wichtig um "stuck"-States zu erkennen (Restriction aktiv obwohl Config
  `true` sagt).
- `POST /api/volume/keys/block` — setzt `DISALLOW_ADJUST_VOLUME` sofort via
  `DevicePolicyManager.addUserRestriction()` und persistiert
  `@kiosk_allow_volume_buttons=false` in AsyncStorage.
- `POST /api/volume/keys/unblock` — cleared die Restriction und persistiert
  `@kiosk_allow_volume_buttons=true`. **Recovery-Pfad** für steckengebliebene
  Tasten nach Alarm-Crash.

Die Restriction wird zusätzlich in `MainActivity.enableKioskRestrictions()`
und in `BootLockActivity` (beim Boot) automatisch re-applied/re-cleared,
basierend auf dem Config-Wert. Damit ist der Zustand nach Reboot/restart-ui
determiniert — kein "stuck" mehr möglich.

Neue React-Methods: `setVolumeKeysBlocked(blocked, promise)`,
`getVolumeKeysBlocked(promise)`. Config-Key `allow_volume_buttons` in
`keyMapping` (MainActivity) und `configKeyMapping` (HttpServerModule)
aufgenommen. Default: `true` (Tasten erlaubt) — bestehende Tablets ohne
expliziten Config-Wert bleiben unverändert.

**Verifikation:** Auf QR-02 ({{SERIAL_QR_02}}, TB336FU, Android 16) getestet:
1. Status lesen → `allowed:true, restrictionActive:false` ✅
2. Block → `blocked:true` ✅
3. Status nach Block → `allowed:false, restrictionActive:true,
   configAllowed:false` ✅
4. Unblock → `blocked:false` ✅
5. Status nach Unblock → `allowed:true, restrictionActive:false,
   configAllowed:true` ✅
6. Block + restart-ui → Restriction wird re-applied (`restrictionActive:true`) ✅
7. Unblock + restart-ui → Restriction wird gecleared
   (`restrictionActive:false`) ✅
8. Lock-Task durchgehend `LOCKED` ✅

**Dateien:** `HttpServerModule.kt`, `MainActivity.kt`, `BootLockActivity.kt`,
`KioskHttpServer.kt`, `src/utils/storage.ts`. Siehe Patch 23 in `AGENTS.md`
und `REST-API.md` für Endpunkt-Doku.

## Verbleibende Aufgaben

- **Bug 5 + 6** (P3) — RN-Update. Grosser Aufwand, aber langfristig noetig
  fuer Android 16-Stabilitaet. Aktuelle Patches funktionieren.
- **Bug 8** (P4) — Master-Mute. Workaround in APK bereits aktiv, nur
  langfristig durch Vendor-Fix oder Policy-Anpassung behebbar.
- **Ausrollen auf PAT-01…03, QR-01…03**: `adb install -r
  freekiosk-v1.2.20-beta.3-patched6.apk` (gleiche Debug-Signatur → Update
  möglich). Anschließend TabControl-Workarounds (BrightnessEnforcer,
  Front-Kamera-Pre-Init, Error-Toleranz reboot/shutdown) deaktivieren.
