package com.freekiosk.api

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.json.JSONArray
import android.util.Log

/**
 * FreeKiosk REST API Server
 * Lightweight HTTP server for Home Assistant integration
 */
class KioskHttpServer(
    port: Int,
    private val apiKey: String?,
    private val allowControl: Boolean,
    private val statusProvider: () -> JSONObject,
    private val commandHandler: (String, JSONObject?) -> JSONObject,
    private val screenshotProvider: (() -> java.io.InputStream?)? = null,
    private val cameraPhotoProvider: ((camera: String, quality: Int) -> java.io.InputStream?)? = null,
    private val configWriter: ((JSONObject) -> JSONObject)? = null,
    private val configReader: (() -> JSONObject)? = null,
    private val configKeyReader: ((String) -> JSONObject?)? = null,
    private val wifiLockHandler: ((String, JSONObject?) -> JSONObject)? = null,
    private val pinHandler: ((JSONObject) -> JSONObject)? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "KioskHttpServer"
        private const val MIME_JSON = "application/json"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        Log.d(TAG, "Request: $method $uri")

        // CORS headers for browser access
        val corsHeaders = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, X-Api-Key"
        )

        // Handle OPTIONS preflight
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, "").apply {
                corsHeaders.forEach { (key, value) -> addHeader(key, value) }
            }
        }

        // Check authentication if API key is set
        if (!apiKey.isNullOrEmpty()) {
            val providedKey = session.headers["x-api-key"]
            if (providedKey != apiKey) {
                return jsonError(Response.Status.UNAUTHORIZED, "Invalid or missing API key")
                    .apply { corsHeaders.forEach { (key, value) -> addHeader(key, value) } }
            }
        }

        // Route requests
        val isGetOrPost = method == Method.GET || method == Method.POST

        // POST-only endpoints that require a JSON body (GET on these → 405, not 404)
        val postOnlyUris = setOf(
            "/api/url", "/api/navigate", "/api/tts", "/api/toast",
            "/api/app/launch", "/api/js", "/api/audio/play", "/api/remote/text",
            "/api/inject-css", "/api/inject-js", "/api/banner"
        )

        val response = try {
            when {
                // Read-only endpoints — accept GET or POST (no body needed)
                isGetOrPost && uri == "/api/status" -> handleGetStatus()
                isGetOrPost && uri == "/api/battery" -> handleGetBattery()
                isGetOrPost && uri == "/api/screen" -> handleGetScreen()
                isGetOrPost && uri == "/api/wifi" -> handleGetWifi()
                isGetOrPost && uri == "/api/info" -> handleGetInfo()
                isGetOrPost && uri == "/api/health" -> handleHealth()
                isGetOrPost && uri == "/api/rotation" -> handleGetRotation()
                isGetOrPost && uri == "/api/sensors" -> handleGetSensors()
                isGetOrPost && uri == "/api/storage" -> handleGetStorage()
                isGetOrPost && uri == "/api/memory" -> handleGetMemory()
                isGetOrPost && uri == "/api/screenshot" -> handleScreenshot()
                isGetOrPost && uri == "/api/camera/list" -> handleCameraList()
                isGetOrPost && uri == "/api/location" -> handleGetLocation()
                isGetOrPost && uri == "/" -> handleRoot()

                // Read endpoints that also have a POST variant — POST with body sets, GET/POST without body reads
                isGetOrPost && uri == "/api/brightness" -> {
                    if (method == Method.POST) handleSetBrightness(session) else handleGetBrightness()
                }
                isGetOrPost && uri == "/api/volume" -> {
                    if (method == Method.POST) handleSetVolume(session) else handleGetVolume()
                }

                // Volume hardware keys block/unblock (mirrors power button pattern).
                // GET  /api/volume/keys         → read actual DPM restriction state
                // POST /api/volume/keys/block   → block volume keys (DISALLOW_ADJUST_VOLUME)
                // POST /api/volume/keys/unblock → unblock volume keys (clear restriction)
                isGetOrPost && uri == "/api/volume/keys" -> handleGetVolumeKeysStatus()
                method == Method.POST && uri == "/api/volume/keys/block" -> handleVolumeKeysBlock()
                method == Method.POST && uri == "/api/volume/keys/unblock" -> handleVolumeKeysUnblock()

                // Camera photo: GET or POST (query params drive behavior)
                isGetOrPost && uri == "/api/camera/photo" -> handleCameraPhoto(session)

                // POST-only control endpoints requiring a JSON body
                method == Method.POST && uri == "/api/url" -> handleSetUrl(session)
                method == Method.POST && uri == "/api/navigate" -> handleSetUrl(session)
                method == Method.POST && uri == "/api/tts" -> handleTts(session)
                method == Method.POST && uri == "/api/toast" -> handleToast(session)
                method == Method.POST && uri == "/api/app/launch" -> handleLaunchApp(session)
                method == Method.POST && uri == "/api/js" -> handleExecuteJs(session)
                method == Method.POST && uri == "/api/audio/play" -> handleAudioPlay(session)
                method == Method.POST && uri == "/api/remote/text" -> handleKeyboardText(session)
                method == Method.POST && uri == "/api/config" -> handleSetConfig(session)
                isGetOrPost && uri.startsWith("/api/config/") && uri.length > "/api/config/".length -> handleGetConfigKey(uri)
                isGetOrPost && uri == "/api/config" -> handleGetConfig()
                method == Method.POST && uri == "/api/inject-css" -> handleInjectCss(session)
                method == Method.POST && uri == "/api/inject-js" -> handleInjectJs(session)

                // WebView navigation (accept both GET and POST for convenience)
                isGetOrPost && uri == "/api/webview/back" -> handleWebViewBack()
                isGetOrPost && uri == "/api/webview/forward" -> handleWebViewForward()
                isGetOrPost && uri == "/api/webview/scroll-top" -> handleWebViewScrollTop()
                isGetOrPost && uri == "/api/home" -> handleHome()

                // Control endpoints (accept both GET and POST for convenience)
                isGetOrPost && uri == "/api/screen/on" -> handleScreenOn()
                isGetOrPost && uri == "/api/screen/off" -> handleScreenOff()
                isGetOrPost && uri == "/api/screensaver/on" -> handleScreensaverOn()
                isGetOrPost && uri == "/api/screensaver/off" -> handleScreensaverOff()
                isGetOrPost && uri == "/api/screensaver/wake-content/on" -> handleScreensaverWakeContentOn()
                isGetOrPost && uri == "/api/screensaver/wake-content/off" -> handleScreensaverWakeContentOff()
                isGetOrPost && uri == "/api/reload" -> handleReload()
                isGetOrPost && uri == "/api/wake" -> handleWake()
                isGetOrPost && uri == "/api/reboot" -> handleReboot()
                isGetOrPost && uri == "/api/shutdown" -> handleShutdown()
                isGetOrPost && uri == "/api/clearCache" -> handleClearCache()
                isGetOrPost && uri == "/api/lock" -> handleLockDevice()
                isGetOrPost && uri == "/api/restart-ui" -> handleRestartUi()
                isGetOrPost && uri == "/api/audio/stop" -> handleAudioStop()
                isGetOrPost && uri == "/api/audio/beep" -> handleAudioBeep()

                // Rotation control (accept both GET and POST)
                isGetOrPost && uri == "/api/rotation/start" -> handleRotationStart()
                isGetOrPost && uri == "/api/rotation/stop" -> handleRotationStop()

                // WiFi Lock control (Patch: /api/wifi/lock, /api/wifi/unlock, /api/wifi/allow, /api/wifi/status)
                method == Method.POST && uri == "/api/wifi/lock" -> handleWifiLock(session)
                isGetOrPost && uri == "/api/wifi/unlock" -> handleWifiUnlock()
                method == Method.POST && uri == "/api/wifi/allow" -> handleWifiAllow(session)
                isGetOrPost && uri == "/api/wifi/lock-status" -> handleWifiLockStatus()

                // PIN change (Patch: /api/pin)
                method == Method.POST && uri == "/api/pin" -> handleSetPin(session)

                // Anti-Theft Banner (Patch: /api/banner)
                method == Method.POST && uri == "/api/banner" -> handleSetBanner(session)
                isGetOrPost && uri == "/api/banner/hide" -> handleHideBanner()

                // Remote control - Android TV (accept both GET and POST)
                isGetOrPost && uri == "/api/remote/up" -> handleRemoteKey("up")
                isGetOrPost && uri == "/api/remote/down" -> handleRemoteKey("down")
                isGetOrPost && uri == "/api/remote/left" -> handleRemoteKey("left")
                isGetOrPost && uri == "/api/remote/right" -> handleRemoteKey("right")
                isGetOrPost && uri == "/api/remote/select" -> handleRemoteKey("select")
                isGetOrPost && uri == "/api/remote/back" -> handleRemoteKey("back")
                isGetOrPost && uri == "/api/remote/home" -> handleRemoteKey("home")
                isGetOrPost && uri == "/api/remote/menu" -> handleRemoteKey("menu")
                isGetOrPost && uri == "/api/remote/playpause" -> handleRemoteKey("playpause")

                // Keyboard emulation (accept both GET and POST)
                isGetOrPost && uri == "/api/remote/keyboard" -> handleKeyboardCombo(session)
                isGetOrPost && uri.startsWith("/api/remote/keyboard/") -> handleKeyboardKey(uri)

                // Method Not Allowed: POST-only endpoints called with GET
                method == Method.GET && uri in postOnlyUris ->
                    jsonError(Response.Status.METHOD_NOT_ALLOWED, "This endpoint requires POST with a JSON body")

                // 404 only for truly unknown paths
                else -> jsonError(Response.Status.NOT_FOUND, "Endpoint not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "Internal server error")
        }

        // Add CORS headers to response
        corsHeaders.forEach { (key, value) -> response.addHeader(key, value) }
        return response
    }

    // ==================== GET Handlers ====================

    private fun handleRoot(): Response {
        val info = JSONObject().apply {
            put("name", "FreeKiosk REST API")
            put("version", "1.0")
            put("endpoints", JSONObject().apply {
                put("GET", JSONArray().apply {
                    put("/api/status - Full device status")
                    put("/api/battery - Battery info")
                    put("/api/brightness - Current brightness")
                    put("/api/screen - Screen state")
                    put("/api/wifi - WiFi info")
                    put("/api/info - Device info")
                    put("/api/rotation - URL rotation status")
                    put("/api/sensors - Device sensors (light, proximity)")
                    put("/api/storage - Storage info")
                    put("/api/memory - Memory info")
                    put("/api/health - Health check")
                    put("/api/camera/photo - Take photo (params: camera=front|back, quality=0-100)")
                    put("/api/camera/list - List available cameras")
                    put("/api/volume - Get current volume {level, maxLevel}")
                    put("/api/volume/keys - Get volume hardware keys status {allowed, restrictionActive, configAllowed}")
                    put("/api/location - GPS coordinates (latitude, longitude, accuracy)")
                    put("/api/config - Get all config keys from AsyncStorage")
                    put("/api/config/:key - Get single config key from AsyncStorage")
                })
                put("POST", JSONArray().apply {
                    put("/api/brightness - Set brightness {value: 0-100}")
                    put("/api/url - Navigate to URL {url: string}")
                    put("/api/navigate - Navigate to URL (alias)")
                    put("/api/tts - Text to speech {text: string}")
                    put("/api/toast - Show toast {text: string}")
                    put("/api/volume - Set volume {value: 0-100}")
                    put("/api/volume/keys/block - Block volume hardware keys (DISALLOW_ADJUST_VOLUME)")
                    put("/api/volume/keys/unblock - Unblock volume hardware keys (clear restriction)")
                    put("/api/app/launch - Launch app {package: string}")
                    put("/api/js - Execute JavaScript {code: string}")
                    put("/api/audio/play - Play audio {url: string, loop: bool, volume: 0-100}")
                    put("/api/remote/text - Type text {text: string}")
                    put("/api/config - Set config keys {key: value, ...} (applies after UI restart)")
                    put("/api/inject-css - Inject CSS into current page {css: string}")
                    put("/api/inject-js - Inject JS into current page {code: string, persistent?: bool}")
                    put("/api/banner - Set anti-theft banner {banner_enabled: bool, banner_text: string, banner_font_size: number, banner_show_icon: bool}")
                })
                put("GET or POST", JSONArray().apply {
                    put("/api/screen/on - Turn screen on")
                    put("/api/screen/off - Turn screen off")
                    put("/api/screensaver/on - Activate screensaver")
                    put("/api/screensaver/off - Deactivate screensaver")
                    put("/api/screensaver/wake-content/on - Enable wake on content change")
                    put("/api/screensaver/wake-content/off - Disable wake on content change")
                    put("/api/reload - Reload WebView")
                    put("/api/wake - Wake from screensaver")
                    put("/api/reboot - Reboot device (Device Owner)")
                    put("/api/shutdown - Power off device (Device Owner)")
                    put("/api/clearCache - Clear WebView cache, cookies and storage")
                    put("/api/lock - Lock device (Device Owner)")
                    put("/api/restart-ui - Restart the app UI")
                    put("/api/audio/stop - Stop audio playback")
                    put("/api/audio/beep - Play beep sound")
                    put("/api/rotation/start - Start URL rotation")
                    put("/api/rotation/stop - Stop URL rotation")
                    put("/api/wifi/lock - Enable WiFi Lock {allowed_ssids: [\"SSID1\",\"SSID2\"]}")
                    put("/api/wifi/unlock - Disable WiFi Lock")
                    put("/api/wifi/allow - Add allowed SSID {ssid: \"SSID\"}")
                    put("/api/wifi/lock-status - Get WiFi Lock status")
                    put("/api/webview/back - Navigate WebView back")
                    put("/api/webview/forward - Navigate WebView forward")
                    put("/api/webview/scroll-top - Scroll WebView to top")
                    put("/api/home - Navigate to home/baseUrl (QR scanner page)")
                    put("/api/remote/* - Remote control (up/down/left/right/select/back/home/menu/playpause)")
                    put("/api/remote/keyboard/{key} - Keyboard key emulation (a-z, 0-9, f1-f12, space, enter, etc.)")
                    put("/api/remote/keyboard?map=ctrl+c - Keyboard shortcut with modifiers (ctrl, alt, shift, meta)")
                })
            })
        }
        return jsonSuccess(info)
    }

    private fun handleHealth(): Response {
        return jsonSuccess(JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis() / 1000)
        })
    }

    private fun handleGetStatus(): Response {
        val status = statusProvider()
        return jsonSuccess(status)
    }

    private fun handleGetBattery(): Response {
        val status = statusProvider()
        val battery = status.optJSONObject("battery") ?: JSONObject()
        return jsonSuccess(battery)
    }

    private fun handleGetBrightness(): Response {
        val status = statusProvider()
        val screen = status.optJSONObject("screen") ?: JSONObject()
        return jsonSuccess(JSONObject().apply {
            put("brightness", screen.optInt("brightness", 50))
        })
    }

    private fun handleGetScreen(): Response {
        val status = statusProvider()
        val screen = status.optJSONObject("screen") ?: JSONObject()
        return jsonSuccess(screen)
    }

    private fun handleGetInfo(): Response {
        val status = statusProvider()
        val device = status.optJSONObject("device") ?: JSONObject()
        return jsonSuccess(device)
    }

    private fun handleGetWifi(): Response {
        val status = statusProvider()
        val wifi = status.optJSONObject("wifi") ?: JSONObject()
        return jsonSuccess(wifi)
    }

    private fun handleGetVolume(): Response {
        val status = statusProvider()
        val audio = status.optJSONObject("audio") ?: JSONObject()
        return jsonSuccess(JSONObject().apply {
            put("level", audio.optInt("volume", 50))
            put("maxLevel", 100)
        })
    }

    // ==================== Volume Keys (Hardware) ====================
    // These endpoints control the DISALLOW_ADJUST_VOLUME user restriction, which
    // blocks the physical volume up/down keys. Mirrors the power-button pattern.
    // Useful for anti-theft scenarios and for recovering from a stuck restriction
    // (e.g. AntiTheftAlarmModule crashed during alarm stop and left it active).

    private fun handleGetVolumeKeysStatus(): Response {
        val result = commandHandler("getVolumeKeysStatus", null)
        return jsonSuccess(result)
    }

    private fun handleVolumeKeysBlock(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("volumeKeysBlock", null)
        return jsonSuccess(result)
    }

    private fun handleVolumeKeysUnblock(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("volumeKeysUnblock", null)
        return jsonSuccess(result)
    }

    // ==================== POST Handlers ====================

    private fun checkControlAllowed(): Response? {
        if (!allowControl) {
            return jsonError(Response.Status.FORBIDDEN, "Remote control is disabled")
        }
        return null
    }

    private fun handleSetBrightness(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val value = body?.optInt("value", -1) ?: -1
        
        if (value < 0 || value > 100) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid brightness value (0-100)")
        }

        val result = commandHandler("setBrightness", JSONObject().put("value", value))
        return jsonSuccess(result)
    }

    private fun handleScreenOn(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("screenOn", null)
        return jsonSuccess(result)
    }

    private fun handleScreenOff(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("screenOff", null)
        return jsonSuccess(result)
    }

    private fun handleScreensaverOn(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("screensaverOn", null)
        return jsonSuccess(result)
    }

    private fun handleScreensaverOff(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("screensaverOff", null)
        return jsonSuccess(result)
    }

    private fun handleScreensaverWakeContentOn(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("screensaverWakeContentOn", null)
        return jsonSuccess(result)
    }

    private fun handleScreensaverWakeContentOff(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("screensaverWakeContentOff", null)
        return jsonSuccess(result)
    }

    private fun handleReload(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("reload", null)
        return jsonSuccess(result)
    }

    private fun handleSetUrl(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val url = body?.optString("url", "") ?: ""
        
        if (url.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "URL is required")
        }

        val result = commandHandler("setUrl", JSONObject().put("url", url))
        return jsonSuccess(result)
    }

    private fun handleTts(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val text = body?.optString("text", "") ?: ""
        
        if (text.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Text is required")
        }

        val params = JSONObject().put("text", text)
        val language = body?.optString("language", "") ?: ""
        if (language.isNotEmpty()) {
            params.put("language", language)
        }
        val result = commandHandler("tts", params)
        return jsonSuccess(result)
    }

    private fun handleWake(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("wake", null)
        return jsonSuccess(result)
    }

    private fun handleSetVolume(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val value = body?.optInt("value", -1) ?: -1
        
        if (value < 0 || value > 100) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid volume value (0-100)")
        }

        val result = commandHandler("setVolume", JSONObject().put("value", value))
        return jsonSuccess(result)
    }

    private fun handleGetRotation(): Response {
        val status = statusProvider()
        val rotation = status.optJSONObject("rotation") ?: JSONObject().apply {
            put("enabled", false)
            put("urls", JSONArray())
            put("interval", 30)
            put("currentIndex", 0)
        }
        return jsonSuccess(rotation)
    }

    private fun handleRotationStart(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("rotationStart", null)
        return jsonSuccess(result)
    }

    private fun handleRotationStop(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("rotationStop", null)
        return jsonSuccess(result)
    }

    // ==================== New Handlers ====================

    private fun handleGetSensors(): Response {
        val sensors = statusProvider().optJSONObject("sensors") ?: JSONObject().apply {
            put("light", -1)
            put("proximity", -1)
            put("accelerometer", JSONObject().apply {
                put("x", 0)
                put("y", 0)
                put("z", 0)
            })
        }
        return jsonSuccess(sensors)
    }

    private fun handleGetStorage(): Response {
        val storage = statusProvider().optJSONObject("storage") ?: JSONObject()
        return jsonSuccess(storage)
    }

    private fun handleGetMemory(): Response {
        val memory = statusProvider().optJSONObject("memory") ?: JSONObject()
        return jsonSuccess(memory)
    }

    private fun handleToast(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val text = body?.optString("text", "") ?: ""
        
        if (text.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Text is required")
        }

        val result = commandHandler("toast", JSONObject().put("text", text))
        return jsonSuccess(result)
    }

    private fun handleLaunchApp(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val packageName = body?.optString("package", "") ?: ""
        
        if (packageName.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Package name is required")
        }

        val result = commandHandler("launchApp", JSONObject().put("package", packageName))
        return jsonSuccess(result)
    }

    private fun handleExecuteJs(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        
        val body = parseBody(session)
        val code = body?.optString("code", "") ?: ""
        
        if (code.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "JavaScript code is required")
        }

        val result = commandHandler("executeJs", JSONObject().put("code", code))
        return jsonSuccess(result)
    }

    private fun handleInjectCss(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }

        val body = parseBody(session)
        val css = body?.optString("css", "") ?: ""

        if (css.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "CSS string is required")
        }

        val result = commandHandler("injectCss", JSONObject().put("css", css))
        return jsonSuccess(result)
    }

    private fun handleInjectJs(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }

        val body = parseBody(session)
        val code = body?.optString("code", "") ?: ""
        val persistent = body?.optBoolean("persistent", false) ?: false

        if (code.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "JavaScript code is required")
        }

        val params = JSONObject().apply {
            put("code", code)
            put("persistent", persistent)
        }
        val result = commandHandler("injectJs", params)
        return jsonSuccess(result)
    }

    private fun handleWebViewBack(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("webviewBack", null)
        return jsonSuccess(result)
    }

    private fun handleWebViewForward(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("webviewForward", null)
        return jsonSuccess(result)
    }

    private fun handleWebViewScrollTop(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("webviewScrollTop", null)
        return jsonSuccess(result)
    }

    private fun handleHome(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("goHome", null)
        return jsonSuccess(result)
    }

    private fun handleReboot(): Response {
        checkControlAllowed()?.let { return it }
        // BUG 10 FIX: Build and return the success response BEFORE triggering the reboot.
        // Previously, commandHandler("reboot", null) called dpm.reboot() synchronously,
        // so the device was already rebooting by the time jsonSuccess() built the response
        // — the client saw ECONNABORTED/ECONNRESET instead of a clean 200. Now we schedule
        // the actual reboot 500ms later on a background thread, giving NanoHTTPD time to
        // flush the response and close the connection cleanly.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                commandHandler("reboot", null)
            } catch (e: Exception) {
                Log.e(TAG, "Deferred reboot failed: ${e.message}", e)
            }
        }, 500)
        return jsonSuccess(JSONObject().apply {
            put("executed", true)
            put("command", "reboot")
            put("pending", true)
        })
    }

    private fun handleShutdown(): Response {
        checkControlAllowed()?.let { return it }
        // BUG 10 FIX: Same as handleReboot — send response first, defer shutdown by 500ms.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                commandHandler("shutdown", null)
            } catch (e: Exception) {
                Log.e(TAG, "Deferred shutdown failed: ${e.message}", e)
            }
        }, 500)
        return jsonSuccess(JSONObject().apply {
            put("executed", true)
            put("command", "shutdown")
            put("pending", true)
        })
    }

    private fun handleClearCache(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("clearCache", null)
        return jsonSuccess(result)
    }

    private fun handleLockDevice(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("lockDevice", null)
        return jsonSuccess(result)
    }

    private fun handleRestartUi(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("restartUi", null)
        return jsonSuccess(result)
    }

    private fun handleRemoteKey(key: String): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("remoteKey", JSONObject().put("key", key))
        return jsonSuccess(result)
    }

    // ==================== Keyboard Emulation Handlers ====================

    private fun handleKeyboardKey(uri: String): Response {
        checkControlAllowed()?.let { return it }
        val key = uri.removePrefix("/api/remote/keyboard/")
        if (key.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Key name is required in URL path, e.g. /api/remote/keyboard/a")
        }
        val result = commandHandler("keyboardKey", JSONObject().put("key", key))
        if (result.optBoolean("executed", false)) {
            return jsonSuccess(result)
        }
        return jsonError(Response.Status.BAD_REQUEST, result.optString("error", "Unknown error"))
    }

    private fun handleKeyboardCombo(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        val map = session.parms?.get("map")
        if (map.isNullOrEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Query parameter 'map' is required, e.g. /api/remote/keyboard?map=ctrl+c")
        }
        val result = commandHandler("keyboardCombo", JSONObject().put("map", map))
        if (result.optBoolean("executed", false)) {
            return jsonSuccess(result)
        }
        return jsonError(Response.Status.BAD_REQUEST, result.optString("error", "Unknown error"))
    }

    private fun handleKeyboardText(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        val body = parseBody(session)
        val text = body?.optString("text", "")
        if (text.isNullOrEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "JSON body with 'text' field is required, e.g. {\"text\": \"hello world\"}")
        }
        val result = commandHandler("keyboardText", JSONObject().put("text", text))
        return jsonSuccess(result)
    }

    // ==================== PIN Handler (Patch) ====================

    private fun handleSetPin(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        val body = parseBody(session)
            ?: return jsonError(Response.Status.BAD_REQUEST, "JSON body required")
        val handler = pinHandler
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "PIN handler not available")
        val result = handler(body)
        return if (result.optBoolean("success", false)) {
            jsonSuccess(result)
        } else {
            jsonError(Response.Status.BAD_REQUEST, result.optString("error", "Failed to set PIN"))
        }
    }

    // ==================== Config Handler ====================

    private fun handleSetConfig(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        val body = parseBody(session)
            ?: return jsonError(Response.Status.BAD_REQUEST, "JSON body required")
        if (body.length() == 0) {
            return jsonError(Response.Status.BAD_REQUEST, "JSON body with config keys is required, e.g. {\"screen_scheduler_enabled\": \"true\"}")
        }
        val writer = configWriter
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Config writer not available")
        val result = writer(body)
        return jsonSuccess(result)
    }

    private fun handleGetConfig(): Response {
        val reader = configReader
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Config reader not available")
        val result = reader()
        return jsonSuccess(result)
    }

    private fun handleGetConfigKey(uri: String): Response {
        val reader = configKeyReader
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Config key reader not available")
        val key = uri.removePrefix("/api/config/")
        if (key.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Config key is required")
        }
        val result = reader(key)
            ?: return jsonError(Response.Status.BAD_REQUEST, "Unknown config key: $key")
        return jsonSuccess(result)
    }

    // ==================== Location Handler ====================

    private fun handleGetLocation(): Response {
        val result = commandHandler("getLocation", null)
        return jsonSuccess(result)
    }

    private fun handleScreenshot(): Response {
        // Get screenshot from module
        val screenshotData = screenshotProvider?.invoke()
        return if (screenshotData != null) {
            // Return as image/png - need to get available bytes for content length
            val bytes = screenshotData.readBytes()
            newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        } else {
            jsonError(Response.Status.SERVICE_UNAVAILABLE, "Screenshot not available")
        }
    }

    private fun handleAudioPlay(session: IHTTPSession): Response {
        checkControlAllowed()?.let { return it }
        val body = parseBody(session)
        val url = body?.optString("url", "")
        val loop = body?.optBoolean("loop", false) ?: false
        val volume = body?.optInt("volume", 50) ?: 50
        val result = commandHandler("audioPlay", JSONObject().apply {
            put("url", url)
            put("loop", loop)
            put("volume", volume)
        })
        return jsonSuccess(result)
    }

    private fun handleAudioStop(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("audioStop", null)
        return jsonSuccess(result)
    }

    private fun handleAudioBeep(): Response {
        checkControlAllowed()?.let { return it }
        val result = commandHandler("audioBeep", null)
        return jsonSuccess(result)
    }

    // ==================== Camera Handlers ====================

    private fun handleCameraPhoto(session: IHTTPSession): Response {
        val params = session.parms ?: emptyMap()
        val camera = params["camera"] ?: "back"
        val quality = (params["quality"]?.toIntOrNull() ?: 80).coerceIn(1, 100)

        Log.d(TAG, "Camera photo request: camera=$camera, quality=$quality")

        val photoData = cameraPhotoProvider?.invoke(camera, quality)
        return if (photoData != null) {
            val bytes = photoData.readBytes()
            newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
            )
        } else {
            jsonError(Response.Status.SERVICE_UNAVAILABLE, "Camera not available. Check camera permission and hardware.")
        }
    }

    private fun handleCameraList(): Response {
        val result = commandHandler("cameraList", null)
        return jsonSuccess(result)
    }

    // ==================== WiFi Lock Handlers (Patch) ====================

    private fun handleWifiLock(session: IHTTPSession): Response {
        if (wifiLockHandler == null) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "WiFi Lock handler not available")
        }
        val body = parseBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON body")
        val result = wifiLockHandler("lock", body)
        return if (result.optBoolean("success", false)) {
            jsonSuccess(result)
        } else {
            jsonError(Response.Status.INTERNAL_ERROR, result.optString("error", "WiFi Lock failed"))
        }
    }

    private fun handleWifiUnlock(): Response {
        if (wifiLockHandler == null) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "WiFi Lock handler not available")
        }
        val result = wifiLockHandler("unlock", null)
        return if (result.optBoolean("success", false)) {
            jsonSuccess(result)
        } else {
            jsonError(Response.Status.INTERNAL_ERROR, result.optString("error", "WiFi Unlock failed"))
        }
    }

    private fun handleWifiAllow(session: IHTTPSession): Response {
        if (wifiLockHandler == null) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "WiFi Lock handler not available")
        }
        val body = parseBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON body")
        val result = wifiLockHandler("allow", body)
        return if (result.optBoolean("success", false)) {
            jsonSuccess(result)
        } else {
            jsonError(Response.Status.INTERNAL_ERROR, result.optString("error", "Add SSID failed"))
        }
    }

    private fun handleWifiLockStatus(): Response {
        if (wifiLockHandler == null) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "WiFi Lock handler not available")
        }
        val result = wifiLockHandler("status", null)
        return jsonSuccess(result)
    }

    // ==================== Anti-Theft Banner Handlers (Patch) ====================

    private fun handleSetBanner(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON body")
        // Use configWriter to persist banner settings, then send command for immediate apply
        val writer = configWriter
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Config writer not available")

        // Write config keys (banner_enabled, banner_text, banner_font_size, banner_show_icon)
        val result = writer(body)

        // Also send a command to JS for immediate banner update (no restart-ui needed)
        commandHandler("set_banner", body)

        return jsonSuccess(result)
    }

    private fun handleHideBanner(): Response {
        // Send command to JS to hide banner immediately
        val result = commandHandler("hide_banner", null)
        return jsonSuccess(result)
    }

    // ==================== Helpers ====================

    private fun parseBody(session: IHTTPSession): JSONObject? {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return null
            try {
                JSONObject(postData)
            } catch (firstEx: Exception) {
                // BUG 9 FIX: Tolerant fallback for PowerShell-escaped JSON.
                // PowerShell often sends bodies like {\"url\":\"http://...\"} where the
                // backslashes are shell escaping (not JSON escaping). The strict JSONObject
                // parser rejects this with "Expected literal value at character 1 of {\".
                // Try stripping backslash-escapes and re-parsing. Also trim leading/trailing
                // whitespace and BOM.
                Log.w(TAG, "Strict JSON parse failed, trying tolerant fallback: ${firstEx.message}")
                try {
                    val cleaned = postData
                        .trim()
                        .removePrefix("\uFEFF")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    JSONObject(cleaned)
                } catch (secondEx: Exception) {
                    Log.w(TAG, "Tolerant JSON parse also failed: ${secondEx.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse body", e)
            null
        }
    }

    private fun jsonSuccess(data: JSONObject): Response {
        val response = JSONObject().apply {
            put("success", true)
            put("data", data)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, response.toString())
    }

    private fun jsonError(status: Response.Status, message: String): Response {
        val response = JSONObject().apply {
            put("success", false)
            put("error", message)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        return newFixedLengthResponse(status, MIME_JSON, response.toString())
    }
}
