package com.freekiosk.api

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.os.StatFs
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.facebook.react.bridge.*
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.freekiosk.DeviceAdminReceiver
import com.freekiosk.CameraPhotoModule
import com.freekiosk.FreeKioskAccessibilityService
import com.freekiosk.ScreenController
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * React Native Module for HTTP Server management
 */
class HttpServerModule(private val reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext), SensorEventListener {

    companion object {
        private const val TAG = "HttpServerModule"
        private const val NAME = "HttpServerModule"
    }

    private var server: KioskHttpServer? = null
    private var statusCallback: (() -> JSONObject)? = null
    private var commandCallback: ((String, JSONObject?) -> JSONObject)? = null
    
    // Callbacks set from JS
    private var jsStatusCallback: Callback? = null
    
    // Status data from JS side (updated via updateStatus method)
    private var jsCurrentUrl: String = ""
    private var jsCanGoBack: Boolean = false
    private var jsLoading: Boolean = false
    private var jsBrightness: Int = 50
    private var jsScreensaverActive: Boolean = false
    private var jsKioskMode: Boolean = false
    private var jsRotationEnabled: Boolean = false
    private var jsRotationUrls: List<String> = emptyList()
    private var jsRotationInterval: Int = 30
    private var jsRotationCurrentIndex: Int = 0
    
    // Auto-brightness status (updated via updateStatus method)
    private var jsAutoBrightnessEnabled: Boolean = false
    private var jsAutoBrightnessMin: Int = 10
    private var jsAutoBrightnessMax: Int = 100
    
    // Sensor data
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var lightValue: Float = -1f
    private var proximityValue: Float = -1f
    private var accelerometerX: Float = 0f
    private var accelerometerY: Float = 0f
    private var accelerometerZ: Float = 0f
    
    // Audio playback
    private var mediaPlayer: MediaPlayer? = null
    
    private var toneGenerator: ToneGenerator? = null
    
    // Server lifecycle management
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    
    // Camera
    private var cameraPhotoModule: CameraPhotoModule? = null
    
    // Text-to-Speech
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var ttsVoicesCache: List<Map<String, String>> = emptyList()

    init {
        initSensors()
        initTts()
    }

    private fun initTts() {
        try {
            // Use the system's preferred TTS engine so that installed language packs
            // (e.g. Chinese, Japanese) are available — the no-arg constructor may pick
            // a different, more limited engine than what the user configured in Settings.
            val preferredEngine = android.provider.Settings.Secure.getString(
                reactContext.contentResolver,
                "tts_default_engine"
            )
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    Log.d(TAG, "TextToSpeech initialized (engine=$preferredEngine)")
                    // Cache available voices so WebView polyfill can enumerate them
                    cacheTtsVoices()
                } else {
                    Log.e(TAG, "TextToSpeech initialization failed: $status")
                }
            }
            tts = if (!preferredEngine.isNullOrEmpty()) {
                TextToSpeech(reactContext.applicationContext, listener, preferredEngine)
            } else {
                TextToSpeech(reactContext.applicationContext, listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}")
        }
    }

    /**
     * Cache available TTS voices for the WebView speechSynthesis polyfill.
     * Exposes Google TTS voices (and any other engine voices) so web apps
     * can enumerate and select them via speechSynthesis.getVoices().
     */
    private fun cacheTtsVoices() {
        try {
            val ttsEngine = tts ?: return
            val voices = mutableListOf<Map<String, String>>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (voice in ttsEngine.voices) {
                    voices.add(mapOf(
                        "name" to voice.name,
                        "lang" to voice.locale.toLanguageTag(),
                        "voiceUri" to voice.name,
                        "localService" to (!voice.isNetworkConnectionRequired).toString(),
                        "default" to (voices.isEmpty()).toString()  // first voice is default
                    ))
                }
            }
            // Fallback: if no voices via API, create voice from default engine info
            if (voices.isEmpty()) {
                val defaultLocale = ttsEngine.defaultVoice?.locale ?: ttsEngine.language ?: Locale.getDefault()
                voices.add(mapOf(
                    "name" to "Default TTS",
                    "lang" to defaultLocale.toLanguageTag(),
                    "voiceUri" to "default",
                    "localService" to "true",
                    "default" to "true"
                ))
            }
            ttsVoicesCache = voices
            Log.d(TAG, "TTS voices cached: ${voices.size} voices")
            for (v in voices) {
                Log.d(TAG, "  Voice: ${v["name"]} (${v["lang"]})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache TTS voices: ${e.message}")
        }
    }

    private fun initSensors() {
        try {
            sensorManager = reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            proximitySensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            accelerometerSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            
            Log.d(TAG, "Sensors initialized: light=${lightSensor != null}, proximity=${proximitySensor != null}, accelerometer=${accelerometerSensor != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize sensors", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lightValue = event.values[0]
            Sensor.TYPE_PROXIMITY -> proximityValue = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerX = event.values[0]
                accelerometerY = event.values[1]
                accelerometerZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun startServer(port: Int, apiKey: String?, allowControl: Boolean, promise: Promise) {
        try {
            if (server != null) {
                promise.reject("ALREADY_RUNNING", "Server is already running")
                return
            }

            // Acquire locks to keep server running even when screen is off
            acquireServerLocks()

            // Initialize camera module
            if (cameraPhotoModule == null) {
                cameraPhotoModule = CameraPhotoModule(reactContext.applicationContext)
            }

            server = KioskHttpServer(
                port = port,
                apiKey = if (apiKey.isNullOrEmpty()) null else apiKey,
                allowControl = allowControl,
                statusProvider = { getDeviceStatus() },
                commandHandler = { command, params -> handleCommand(command, params) },
                screenshotProvider = { captureScreenshot() },
                cameraPhotoProvider = { camera, quality -> cameraPhotoModule?.capturePhoto(camera, quality) },
                configWriter = { configJson -> handleSetConfig(configJson) },
                configReader = { handleGetConfig() },
                configKeyReader = { key -> handleGetConfigByKey(key) },
                wifiLockHandler = { action, params -> handleWifiLockCommand(action, params) },
                pinHandler = { body -> handleSetPin(body) }
            )

            server?.start()
            
            Log.i(TAG, "HTTP Server started on port $port with locks acquired")
            
            val result = Arguments.createMap().apply {
                putBoolean("success", true)
                putInt("port", port)
                putString("ip", getLocalIpAddress())
            }
            promise.resolve(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            promise.reject("START_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopServer(promise: Promise) {
        try {
            server?.stop()
            server = null
            releaseServerLocks()
            Log.i(TAG, "HTTP Server stopped and locks released")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server", e)
            promise.reject("STOP_ERROR", e.message)
        }
    }

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(server?.isAlive == true)
    }

    @ReactMethod
    fun getServerInfo(promise: Promise) {
        val result = Arguments.createMap().apply {
            putBoolean("running", server?.isAlive == true)
            putString("ip", getLocalIpAddress())
        }
        promise.resolve(result)
    }

    /**
     * Acquire WifiLock and CPU WakeLock to keep server running when screen is off
     */
    private fun acquireServerLocks() {
        try {
            // WiFi Lock - prevents WiFi from going to sleep
            val wifiManager = reactContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FreeKiosk:HttpServer")
            wifiLock?.acquire()
            Log.d(TAG, "WifiLock acquired for HTTP Server")
            
            // CPU Partial Wake Lock - keeps CPU running for background processing
            val powerManager = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FreeKiosk:HttpServerCPU"
            )
            cpuWakeLock?.acquire()
            Log.d(TAG, "CPU WakeLock acquired for HTTP Server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire server locks: ${e.message}")
        }
    }
    
    /**
     * Release WifiLock and CPU WakeLock when server stops
     */
    private fun releaseServerLocks() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiLock released")
                }
            }
            wifiLock = null
            
            cpuWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "CPU WakeLock released")
                }
            }
            cpuWakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release server locks: ${e.message}")
        }
    }

    @ReactMethod
    fun getLocalIp(promise: Promise) {
        promise.resolve(getLocalIpAddress())
    }

    /**
     * Get available cameras via Camera2 API directly.
     * This bypasses CameraX/ProcessCameraProvider which can fail on certain devices
     * (e.g. MediaTek LEGACY front-only cameras where CameraValidator rejects the device).
     * Used as a fallback when react-native-vision-camera reports no cameras.
     */
    @ReactMethod
    fun getCamera2Devices(promise: Promise) {
        try {
            if (cameraPhotoModule == null) {
                cameraPhotoModule = CameraPhotoModule(reactContext.applicationContext)
            }
            val cameras = cameraPhotoModule?.getAvailableCameras() ?: emptyList()
            val result = Arguments.createArray()
            cameras.forEach { cam ->
                val cameraMap = Arguments.createMap().apply {
                    putString("id", cam["id"].toString())
                    putString("position", when (cam["facing"]) {
                        "front" -> "front"
                        "back" -> "back"
                        else -> cam["facing"].toString()
                    })
                    putInt("maxWidth", (cam["maxWidth"] as? Int) ?: 0)
                    putInt("maxHeight", (cam["maxHeight"] as? Int) ?: 0)
                }
                result.pushMap(cameraMap)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Camera2 devices: ${e.message}")
            promise.reject("CAMERA2_ERROR", "Failed to enumerate cameras: ${e.message}")
        }
    }

    /**
     * Capture a photo via Camera2 API directly and save to a temp file.
     * Used as a fallback for motion detection on devices where vision-camera/CameraX
     * cannot access the camera (e.g. MediaTek LEGACY front-only devices).
     * @param cameraFacing "front" or "back"
     * @param quality JPEG quality 0-100
     * @return Promise resolving to the temp file path
     */
    @ReactMethod
    fun captureCamera2Photo(cameraFacing: String, quality: Int, promise: Promise) {
        Thread {
            try {
                if (cameraPhotoModule == null) {
                    cameraPhotoModule = CameraPhotoModule(reactContext.applicationContext)
                }
                val inputStream = cameraPhotoModule?.capturePhoto(cameraFacing, quality)
                if (inputStream == null) {
                    promise.reject("CAPTURE_FAILED", "Camera2 photo capture returned null")
                    return@Thread
                }
                // Write to temp file
                val tempFile = java.io.File.createTempFile("motion_cam2_", ".jpg", reactContext.cacheDir)
                tempFile.outputStream().use { out ->
                    inputStream.copyTo(out)
                }
                inputStream.close()
                promise.resolve(tempFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Camera2 photo capture failed: ${e.message}")
                promise.reject("CAPTURE_ERROR", "Camera2 capture failed: ${e.message}")
            }
        }.start()
    }

    // ==================== Config Writer (REST /api/config) ====================

    /**
     * JSON config key → AsyncStorage key mapping.
     * Must be kept in sync with MainActivity.applyJsonConfigToPrefs().
     */
    private val configKeyMapping = mapOf(
        "url" to "@kiosk_url",
        "auto_reload" to "@kiosk_auto_reload",
        "lock_package" to "@kiosk_external_app_package",
        "display_mode" to "@kiosk_display_mode",
        "kiosk_enabled" to "@kiosk_enabled",
        "auto_launch" to "@kiosk_auto_launch",
        "auto_relaunch" to "@kiosk_auto_relaunch_app",
        "screensaver_enabled" to "@screensaver_enabled",
        "screensaver_delay" to "@screensaver_inactivity_delay",
        "screensaver_brightness" to "@screensaver_brightness",
        "screensaver_wake_on_content_change" to "@screensaver_wake_on_content_change",
        "status_bar_enabled" to "@kiosk_status_bar_enabled",
        "status_bar_show_battery" to "@kiosk_status_bar_show_battery",
        "status_bar_show_wifi" to "@kiosk_status_bar_show_wifi",
        "status_bar_show_time" to "@kiosk_status_bar_show_time",
        "rest_api_enabled" to "@kiosk_rest_api_enabled",
        "rest_api_port" to "@kiosk_rest_api_port",
        "rest_api_key" to "@kiosk_rest_api_key",
        "rest_api_allow_control" to "@kiosk_rest_api_allow_control",
        "allow_power_button" to "@kiosk_allow_power_button",
        "allow_volume_buttons" to "@kiosk_allow_volume_buttons",
        "antitheft_alarm_enabled" to "@kiosk_antitheft_alarm_enabled",
        "back_button_mode" to "@kiosk_back_button_mode",
        "default_brightness" to "@default_brightness",
        "pin_mode" to "@kiosk_pin_mode",
        // MQTT
        "mqtt_enabled" to "@kiosk_mqtt_enabled",
        "mqtt_broker_url" to "@kiosk_mqtt_broker_url",
        "mqtt_port" to "@kiosk_mqtt_port",
        "mqtt_username" to "@kiosk_mqtt_username",
        "mqtt_client_id" to "@kiosk_mqtt_client_id",
        "mqtt_base_topic" to "@kiosk_mqtt_base_topic",
        "mqtt_discovery_prefix" to "@kiosk_mqtt_discovery_prefix",
        "mqtt_status_interval" to "@kiosk_mqtt_status_interval",
        "mqtt_allow_control" to "@kiosk_mqtt_allow_control",
        "mqtt_device_name" to "@kiosk_mqtt_device_name",
        // Multi-app
        "external_app_mode" to "@kiosk_external_app_mode",
        // Screen Sleep Scheduler
        "screen_scheduler_enabled" to "@kiosk_screen_scheduler_enabled",
        "screen_scheduler_rules" to "@kiosk_screen_scheduler_rules",
        "screen_scheduler_wake_on_touch" to "@kiosk_screen_scheduler_wake_on_touch",
        // Inactivity Return to Home
        "inactivity_return_enabled" to "@kiosk_inactivity_return_enabled",
        "inactivity_return_delay" to "@kiosk_inactivity_return_delay",
        "inactivity_return_reset_on_nav" to "@kiosk_inactivity_return_reset_on_nav",
        "inactivity_return_clear_cache" to "@kiosk_inactivity_return_clear_cache",
        "inactivity_return_scroll_top" to "@kiosk_inactivity_return_scroll_top",
        // URL Filter
        "url_filter_enabled" to "@kiosk_url_filter_enabled",
        "url_filter_mode" to "@kiosk_url_filter_mode",
        "url_filter_list" to "@kiosk_url_filter_list",
        "url_filter_show_feedback" to "@kiosk_url_filter_show_feedback",
        // Auto-Brightness
        "auto_brightness_enabled" to "@kiosk_auto_brightness_enabled",
        "auto_brightness_min" to "@kiosk_auto_brightness_min",
        "auto_brightness_max" to "@kiosk_auto_brightness_max",
        "auto_brightness_offset" to "@kiosk_auto_brightness_offset",
        // Keep Screen On / Auto Wake
        "keep_screen_on" to "@kiosk_keep_screen_on",
        "auto_wake_on_screen_off" to "@kiosk_auto_wake_on_screen_off",
        // WebView Zoom
        "webview_zoom_level" to "@kiosk_webview_zoom_level",
        "webview_zoom_mode" to "@kiosk_webview_zoom_mode",
        "disable_user_zoom" to "@kiosk_disable_user_zoom",
        // Custom User Agent
        "custom_user_agent" to "@kiosk_custom_user_agent",
        // PIN Security
        "pin_max_attempts" to "@kiosk_pin_max_attempts",
        // Volume 5-Tap
        "volume_up_5tap_enabled" to "@kiosk_volume_up_5tap_enabled",
        // Pause Web Media When Hidden
        "pause_web_media_when_hidden" to "@kiosk_pause_web_media_when_hidden",
        // Screensaver Inactivity
        "screensaver_inactivity_enabled" to "@screensaver_inactivity_enabled",
        // Screensaver Type / URL
        "screensaver_type" to "@screensaver_type",
        "screensaver_url" to "@screensaver_url",
        // Screensaver Motion
        "screensaver_motion_enabled" to "@screensaver_motion_enabled",
        "screensaver_motion_sensitivity" to "@screensaver_motion_sensitivity",
        "screensaver_motion_delay" to "@screensaver_motion_delay",
        // Return to Settings
        "return_tap_count" to "@kiosk_return_tap_count",
        "return_tap_timeout" to "@kiosk_return_tap_timeout",
        "return_mode" to "@kiosk_return_mode",
        "return_button_position" to "@kiosk_return_button_position",
        // Notifications / System Info
        "allow_notifications" to "@kiosk_allow_notifications",
        "allow_system_info" to "@kiosk_allow_system_info",
        // WebView Back Button
        "webview_back_button_enabled" to "@kiosk_webview_back_button_enabled",
        "webview_back_button_x_percent" to "@kiosk_webview_back_button_x_percent",
        "webview_back_button_y_percent" to "@kiosk_webview_back_button_y_percent",
        // Home Button
        "home_button_enabled" to "@kiosk_home_button_enabled",
        "home_button_x_percent" to "@kiosk_home_button_x_percent",
        "home_button_y_percent" to "@kiosk_home_button_y_percent",
        // Anti-Theft Banner
        "banner_enabled" to "@kiosk_banner_enabled",
        "banner_text" to "@kiosk_banner_text",
        "banner_font_size" to "@kiosk_banner_font_size",
        "banner_show_icon" to "@kiosk_banner_show_icon",
        // Brightness Management
        "brightness_management_enabled" to "@brightness_management_enabled",
        // Beta Updates
        "beta_updates_enabled" to "@kiosk_beta_updates_enabled",
        // PDF Viewer / Printing
        "pdf_viewer_enabled" to "@kiosk_pdf_viewer_enabled",
        "print_enabled" to "@kiosk_print_enabled",
        "print_paper_size" to "@kiosk_print_paper_size",
        // Blocking Overlays
        "blocking_overlays_enabled" to "@kiosk_blocking_overlays_enabled",
        "blocking_overlays_regions" to "@kiosk_blocking_overlays_regions",
        // URL Rotation
        "url_rotation_enabled" to "@kiosk_url_rotation_enabled",
        "url_rotation_list" to "@kiosk_url_rotation_list",
        "url_rotation_interval" to "@kiosk_url_rotation_interval",
        // URL Planner
        "url_planner_enabled" to "@kiosk_url_planner_enabled",
        "url_planner_events" to "@kiosk_url_planner_events",
        // Device Identity (set during provisioning via ADB, read-only for external apps)
        "device_serial" to "@kiosk_device_serial",
        "device_mac" to "@kiosk_device_mac"
    )

    /**
     * Handle POST /api/pin — change the kiosk PIN remotely.
     * Requires "new_pin" (the new PIN). If a PIN is already set, "old_pin" must
     * be provided for verification — unless "force": true is set (API key serves
     * as authorization). Updates both the ADB PIN hash and AsyncStorage @kiosk_pin.
     */
    private fun handleSetPin(body: JSONObject): JSONObject {
        try {
            val oldPin = body.optString("old_pin", "")
            val newPin = body.optString("new_pin", "")
            val force = body.optBoolean("force", false)

            if (newPin.isEmpty()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "new_pin is required")
                }
            }

            // Verify old PIN if one is already set (unless force=true)
            val adbPrefs = reactContext.getSharedPreferences("FreeKioskAdbConfig", Context.MODE_PRIVATE)
            val storedHash = adbPrefs.getString("pin_hash", null)

            if (storedHash != null && !force) {
                // Existing PIN — verify old_pin
                if (oldPin.isEmpty()) {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "old_pin is required to change an existing PIN")
                    }
                }

                val storedSalt = adbPrefs.getString("pin_salt", null)
                if (storedSalt != null) {
                    val inputHash = hashPinWithSalt(oldPin, storedSalt)
                    if (inputHash != storedHash) {
                        // Check legacy plaintext PIN
                        val legacyPin = getAsyncStorageValue("@kiosk_pin", "")
                        if (legacyPin.isEmpty() || oldPin != legacyPin) {
                            return JSONObject().apply {
                                put("success", false)
                                put("error", "old_pin is incorrect")
                            }
                        }
                        // Legacy PIN matched — migrate to hashed storage below
                    }
                }
            }

            // Save new PIN hash
            val salt = java.util.UUID.randomUUID().toString()
            val hash = hashPinWithSalt(newPin, salt)
            adbPrefs.edit()
                .putString("pin_hash", hash)
                .putString("pin_salt", salt)
                .apply()

            // Save new PIN to AsyncStorage for UI display
            savePinToAsyncStorage(newPin)

            Log.i(TAG, "PIN changed successfully via REST API")

            return JSONObject().apply {
                put("success", true)
                put("message", "PIN changed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set PIN: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
        }
    }

    private fun hashPinWithSalt(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val input = "$pin:$salt"
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun savePinToAsyncStorage(pin: String) {
        saveAsyncStorageValue("@kiosk_pin", pin)
        Log.i(TAG, "PIN saved to AsyncStorage for UI")
    }

    /**
     * Generalized AsyncStorage writer. Writes a key/value pair directly to the
     * RKStorage SQLite database (catalystLocalStorage table). Used by savePinToAsyncStorage
     * and by the volume-keys block/unblock command handlers to persist config changes.
     */
    private fun saveAsyncStorageValue(key: String, value: String) {
        try {
            val dbPath = reactContext.getDatabasePath("RKStorage").absolutePath
            // BUG 12 FIX: try/finally so the DB is always closed, even if execSQL or
            // insertWithOnConflict throws. Previously an exception left the DB handle open.
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null)
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS catalystLocalStorage (
                      `key` TEXT NOT NULL,
                      `value` TEXT,
                      PRIMARY KEY(`key`)
                    )
                """.trimIndent())
                val contentValues = android.content.ContentValues().apply {
                    put("key", key)
                    put("value", value)
                }
                db.insertWithOnConflict("catalystLocalStorage", null, contentValues, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save AsyncStorage value for key $key: ${e.message}")
        }
    }

    private fun getAsyncStorageValue(key: String, default: String): String {
        return getAsyncStorageValueOrNull(key) ?: default
    }

    private fun getAsyncStorageValueOrNull(key: String): String? {
        return try {
            val dbPath = reactContext.getDatabasePath("RKStorage").absolutePath
            // BUG 13 FIX: try/finally so the DB is always closed, even if the query throws
            // (e.g. SQLiteDatabaseLockedException or missing table). Previously an exception
            // between openOrCreateDatabase and db.close() left the DB handle open.
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null)
            try {
                val cursor = db.query("catalystLocalStorage", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
                var value: String? = null
                try {
                    if (cursor.moveToFirst()) {
                        value = cursor.getString(0)
                    }
                } finally {
                    cursor.close()
                }
                value
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read AsyncStorage value for key $key: ${e.message}")
            null
        }
    }

    /**
     * Handle GET /api/config — read all config keys from AsyncStorage.
     * Returns the raw config object; KioskHttpServer wraps it with jsonSuccess().
     */
    private fun handleGetConfig(): JSONObject {
        val config = JSONObject()

        for ((externalKey, asyncStorageKey) in configKeyMapping) {
            val value = getAsyncStorageValueOrNull(asyncStorageKey)
            if (value != null) {
                config.put(externalKey, value)
            }
        }

        return config
    }

    /**
     * Handle GET /api/config/:key — read a single config key from AsyncStorage.
     * Returns the raw key/value object or null if the key is unknown.
     */
    private fun handleGetConfigByKey(key: String): JSONObject? {
        val asyncStorageKey = configKeyMapping[key] ?: return null
        val value = getAsyncStorageValueOrNull(asyncStorageKey)

        return JSONObject().apply {
            put("key", key)
            put("value", value)
        }
    }

    /**
     * Handle POST /api/config — write config keys to FreeKioskPendingConfig
     * SharedPreferences (same mechanism as ADB), then restart the UI to apply.
     */
    private fun handleSetConfig(configJson: JSONObject): JSONObject {
        try {
            val prefs = reactContext.getSharedPreferences("FreeKioskPendingConfig", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            var appliedCount = 0
            val appliedKeys = mutableListOf<String>()
            val unknownKeys = mutableListOf<String>()

            for (key in configJson.keys()) {
                val storageKey = configKeyMapping[key]
                if (storageKey != null) {
                    val value = configJson.get(key)
                    editor.putString(storageKey, value.toString())
                    appliedCount++
                    appliedKeys.add(key)
                } else {
                    unknownKeys.add(key)
                }
            }

            if (appliedCount > 0) {
                editor.putBoolean("has_pending_config", true)
                editor.commit()
                Log.i(TAG, "Config written to pending SharedPreferences: $appliedCount keys ($appliedKeys)")
                // Config is written to FreeKioskPendingConfig with has_pending_config=true.
                // The pending config will be applied on the next app restart
                // (e.g. via POST /api/restart-ui or app process restart).
                // We do NOT call ReactHost.reload() or activity.recreate() here because
                // both cause crashes on Android 16 / RN 0.82 (WebView ref invalidation
                // during pauseMedia → JavascriptException).
            }

            return JSONObject().apply {
                put("executed", true)
                put("applied", appliedCount)
                put("appliedKeys", org.json.JSONArray(appliedKeys))
                if (unknownKeys.isNotEmpty()) {
                    put("unknownKeys", org.json.JSONArray(unknownKeys))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set config: ${e.message}", e)
            return JSONObject().apply {
                put("executed", false)
                put("error", e.message)
            }
        }
    }

    // ==================== Status Provider ====================

    private fun getDeviceStatus(): JSONObject {
        val status = JSONObject()
        
        // Battery
        val batteryStatus = getBatteryInfo()
        status.put("battery", batteryStatus)
        
        // Screen - use values from JS + actual screen state
        val screenStatus = JSONObject().apply {
            // Get actual screen state from PowerManager
            val powerManager = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isInteractive = powerManager.isInteractive
            
            // "on" reflects the PHYSICAL screen state (PowerManager.isInteractive)
            // "screensaverActive" is separate - indicates if screensaver overlay is showing
            // This allows clients to distinguish: screen physically on vs content visible
            put("on", isInteractive)
            put("brightness", jsBrightness)
            put("screensaverActive", jsScreensaverActive)
        }
        status.put("screen", screenStatus)
        
        // Audio - get current volume
        val audioStatus = JSONObject().apply {
            try {
                val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercent = (currentVolume * 100) / maxVolume
                put("volume", volumePercent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get volume for status: ${e.message}")
                put("volume", 50) // Default fallback
            }
        }
        status.put("audio", audioStatus)
        
        // WebView - use values from JS
        val webviewStatus = JSONObject().apply {
            put("currentUrl", jsCurrentUrl)
            put("canGoBack", jsCanGoBack)
            put("loading", jsLoading)
        }
        status.put("webview", webviewStatus)
        
        // Device
        val deviceStatus = JSONObject().apply {
            put("ip", getLocalIpAddress())
            put("hostname", "freekiosk")
            put("version", com.freekiosk.BuildConfig.VERSION_NAME)
            // Check real Device Owner status via DevicePolicyManager
            val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            put("isDeviceOwner", dpm.isDeviceOwnerApp(reactContext.packageName))
            put("kioskMode", jsKioskMode)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("processor", Build.HARDWARE)
            put("deviceName", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("uptime", android.os.SystemClock.elapsedRealtime() / 1000)
        }
        status.put("device", deviceStatus)
        
        // WiFi
        val wifiStatus = getWifiInfo()
        status.put("wifi", wifiStatus)
        
        // URL Rotation
        val rotationStatus = JSONObject().apply {
            put("enabled", jsRotationEnabled)
            put("urls", org.json.JSONArray(jsRotationUrls))
            put("interval", jsRotationInterval)
            put("currentIndex", jsRotationCurrentIndex)
        }
        status.put("rotation", rotationStatus)
        
        // Sensors
        val sensorsStatus = JSONObject().apply {
            put("light", lightValue)
            put("proximity", proximityValue)
            put("accelerometer", JSONObject().apply {
                put("x", accelerometerX)
                put("y", accelerometerY)
                put("z", accelerometerZ)
            })
        }
        status.put("sensors", sensorsStatus)
        
        // Auto-brightness
        val autoBrightnessStatus = JSONObject().apply {
            put("enabled", jsAutoBrightnessEnabled)
            put("min", jsAutoBrightnessMin)
            put("max", jsAutoBrightnessMax)
            put("currentLightLevel", lightValue)
        }
        status.put("autoBrightness", autoBrightnessStatus)
        
        // Storage
        val storageStatus = getStorageInfo()
        status.put("storage", storageStatus)
        
        // Memory
        val memoryStatus = getMemoryInfo()
        status.put("memory", memoryStatus)
        
        return status
    }

    private fun getBatteryInfo(): JSONObject {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = reactContext.registerReceiver(null, intentFilter)
        
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percentage = (level * 100) / scale
        
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val pluggedType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        
        // Additional battery details
        val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
        val technology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"
        val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        
        return JSONObject().apply {
            put("level", percentage)
            put("charging", isCharging)
            put("plugged", pluggedType)
            put("temperature", temperature)
            put("voltage", voltage)
            put("health", healthStr)
            put("technology", technology)
        }
    }

    private fun getWifiInfo(): JSONObject {
        return try {
            val connectivityManager = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.activeNetwork else null
            val capabilities = if (network != null) connectivityManager.getNetworkCapabilities(network) else null

            val isConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
            }

            // API 31+: use transportInfo from NetworkCapabilities — connectionInfo returns <unknown ssid> on Android 12+
            val wifiInfoObj: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities != null) {
                capabilities.transportInfo as? WifiInfo
            } else {
                val wifiManager = reactContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo
            }

            val ssid = getSsidSafe(wifiInfoObj?.ssid)
            val rssi = wifiInfoObj?.rssi ?: -100
            val signalLevel = WifiManager.calculateSignalLevel(rssi, 100)

            JSONObject().apply {
                put("ssid", ssid)
                put("signalStrength", rssi)
                put("signalLevel", signalLevel)
                put("connected", isConnected)
                put("linkSpeed", wifiInfoObj?.linkSpeed ?: 0)
                put("frequency", wifiInfoObj?.frequency ?: 0)
                put("ipAddress", getLocalIpAddress())
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ssid", "")
                put("signalStrength", 0)
                put("signalLevel", 0)
                put("connected", false)
                put("linkSpeed", 0)
                put("frequency", 0)
                put("ipAddress", "0.0.0.0")
            }
        }
    }

    private fun getSsidSafe(rawSsid: String?): String {
        val ssid = rawSsid?.replace("\"", "")?.trim() ?: ""
        if (ssid.isNotEmpty() && ssid != "<unknown ssid>" && ssid != "0x") {
            return ssid
        }
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locationManager = reactContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
             locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
        }
        return when {
            !hasLocationPermission -> "WiFi (no permission)"
            !locationEnabled -> "WiFi (location off)"
            else -> "WiFi"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
        }
        return "0.0.0.0"
    }

    // ==================== Command Handler ====================

    private fun handleCommand(command: String, params: JSONObject?): JSONObject {
        Log.d(TAG, "Handling command: $command")
        
        // Handle audio commands directly (don't need JS)
        when (command) {
            "audioPlay" -> {
                val url = params?.optString("url", "")
                val loop = params?.optBoolean("loop", false) ?: false
                val volume = params?.optInt("volume", 50) ?: 50
                playAudio(url, loop, volume)
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "audioStop" -> {
                stopAudio()
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "audioBeep" -> {
                playBeep()
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "screenOn" -> {
                turnScreenOn()
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "screenOff" -> {
                turnScreenOff()
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "volumeKeysBlock" -> {
                // Block volume keys immediately via DISALLOW_ADJUST_VOLUME user restriction.
                // Also persists to AsyncStorage so enableKioskRestrictions() re-applies it on reboot.
                setVolumeKeysRestriction(true)
                try {
                    val current = getAsyncStorageValueOrNull("@kiosk_allow_volume_buttons")
                    if (current != "false") {
                        saveAsyncStorageValue("@kiosk_allow_volume_buttons", "false")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not persist allow_volume_buttons=false: ${e.message}")
                }
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("blocked", true)
                }
            }
            "volumeKeysUnblock" -> {
                // Unblock volume keys immediately by clearing DISALLOW_ADJUST_VOLUME.
                // Also persists to AsyncStorage so enableKioskRestrictions() doesn't re-block on reboot.
                setVolumeKeysRestriction(false)
                try {
                    val current = getAsyncStorageValueOrNull("@kiosk_allow_volume_buttons")
                    if (current != "true") {
                        saveAsyncStorageValue("@kiosk_allow_volume_buttons", "true")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not persist allow_volume_buttons=true: ${e.message}")
                }
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("blocked", false)
                }
            }
            "getVolumeKeysStatus" -> {
                // Read the ACTUAL DPM restriction state (not just the stored config).
                // This is important because AntiTheftAlarmModule can toggle the restriction
                // independently, and a crash during alarm stop can leave it stuck.
                val restrictionActive = isVolumeKeysRestrictionActive()
                val configAllowed = getAsyncStorageValue("@kiosk_allow_volume_buttons", "true") == "true"
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("allowed", !restrictionActive)
                    put("restrictionActive", restrictionActive)
                    put("configAllowed", configAllowed)
                }
            }
            "wake" -> {
                // Wake must also turn on the physical screen (after lockNow)
                turnScreenOn()
                // Also send to JS to deactivate screensaver overlay
                sendEvent("onApiCommand", Arguments.createMap().apply {
                    putString("command", "wake")
                    putString("params", "{}")
                })
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "autoBrightnessEnable" -> {
                val min = params?.optInt("min", 10) ?: 10
                val max = params?.optInt("max", 100) ?: 100
                // Send to JS for handling
                sendEvent("onApiCommand", Arguments.createMap().apply {
                    putString("command", "autoBrightnessEnable")
                    putString("params", JSONObject().apply {
                        put("min", min)
                        put("max", max)
                    }.toString())
                })
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("min", min)
                    put("max", max)
                }
            }
            "autoBrightnessDisable" -> {
                sendEvent("onApiCommand", Arguments.createMap().apply {
                    putString("command", "autoBrightnessDisable")
                    putString("params", "{}")
                })
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "getAutoBrightness" -> {
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("autoBrightness", JSONObject().apply {
                        put("enabled", jsAutoBrightnessEnabled)
                        put("min", jsAutoBrightnessMin)
                        put("max", jsAutoBrightnessMax)
                        put("currentLightLevel", lightValue)
                    })
                }
            }
            "cameraList" -> {
                val cameras = cameraPhotoModule?.getAvailableCameras() ?: emptyList()
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("cameras", org.json.JSONArray().apply {
                        cameras.forEach { cam ->
                            put(JSONObject().apply {
                                put("id", cam["id"])
                                put("facing", cam["facing"])
                                put("maxWidth", cam["maxWidth"])
                                put("maxHeight", cam["maxHeight"])
                            })
                        }
                    })
                }
            }
            "reboot" -> {
                return try {
                    val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(reactContext, DeviceAdminReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(reactContext.packageName)) {
                        Log.d(TAG, "Rebooting device via Device Owner API")
                        dpm.reboot(adminComponent)
                        JSONObject().apply {
                            put("executed", true)
                            put("command", command)
                        }
                    } else {
                        Log.w(TAG, "Reboot failed: not Device Owner")
                        JSONObject().apply {
                            put("executed", false)
                            put("command", command)
                            put("error", "Reboot requires Device Owner mode")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reboot failed: ${e.message}")
                    JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Reboot failed: ${e.message}")
                    }
                }
            }
            "shutdown" -> {
                return try {
                    val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (!dpm.isDeviceOwnerApp(reactContext.packageName)) {
                        Log.w(TAG, "Shutdown failed: not Device Owner")
                        JSONObject().apply {
                            put("executed", false)
                            put("command", command)
                            put("error", "Shutdown requires Device Owner mode")
                        }
                    } else {
                        Log.d(TAG, "Shutting down device via Accessibility Service")
                        var shutdownSuccess = false
                        var lastError: String? = null

                        // Approach 1: Accessibility Service — opens power dialog and clicks "Power off"
                        // This bypasses the REBOOT permission requirement because the system UI
                        // (which renders the power dialog) runs as system_app with full permissions.
                        // Requires the FreeKioskAccessibilityService to be enabled.
                        try {
                            if (FreeKioskAccessibilityService.isRunning()) {
                                // Stop Lock-Task mode first — otherwise the power dialog is blocked
                                try {
                                    val am = reactContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                    if (am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                                        Log.d(TAG, "Stopping Lock-Task mode before shutdown")
                                        val activity = reactContext.currentActivity
                                        if (activity != null) {
                                            val latch = java.util.concurrent.CountDownLatch(1)
                                            activity.runOnUiThread {
                                                try {
                                                    activity.stopLockTask()
                                                    Log.d(TAG, "stopLockTask() called")
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "stopLockTask() failed: ${e.message}")
                                                }
                                                latch.countDown()
                                            }
                                            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                                        }
                                        // Wait for the lock task to fully stop
                                        Thread.sleep(500)
                                    }
                                } catch (ltEx: Exception) {
                                    Log.w(TAG, "Lock-Task stop failed: ${ltEx.message}")
                                }

                                shutdownSuccess = FreeKioskAccessibilityService.shutdownViaAccessibility()
                                if (shutdownSuccess) {
                                    Log.d(TAG, "Shutdown via Accessibility Service initiated")
                                } else {
                                    lastError = "Accessibility: power dialog could not be opened"
                                }
                            } else {
                                lastError = "Accessibility Service not running"
                                Log.w(TAG, "Accessibility Service not running, trying fallbacks")
                            }
                        } catch (a11yEx: Exception) {
                            lastError = "Accessibility: ${a11yEx.message}"
                            Log.w(TAG, "Shutdown via Accessibility failed: ${a11yEx.message}")
                        }

                        // Approach 2: Raw IBinder transact() on IPowerManager (requires REBOOT permission)
                        if (!shutdownSuccess) {
                            try {
                                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                                val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
                                val powerBinder = getServiceMethod.invoke(null, "power") as android.os.IBinder

                                val data = android.os.Parcel.obtain()
                                val reply = android.os.Parcel.obtain()
                                try {
                                    data.writeInterfaceToken("android.os.IPowerManager")
                                    data.writeBoolean(false)       // confirm
                                    data.writeString("userrequested") // reason
                                    data.writeBoolean(false)       // wait

                                    // shutdown() is IPowerManager method #54 (FIRST_CALL_TRANSACTION + 53)
                                    val SHUTDOWN_TRANSACTION = android.os.IBinder.FIRST_CALL_TRANSACTION + 53
                                    powerBinder.transact(SHUTDOWN_TRANSACTION, data, reply, 0)
                                    reply.readException()
                                    shutdownSuccess = true
                                    Log.d(TAG, "Raw IBinder transact(SHUTDOWN) succeeded")
                                } finally {
                                    data.recycle()
                                    reply.recycle()
                                }
                            } catch (ipmEx: Exception) {
                                lastError = "IPowerManager: ${ipmEx.message}"
                                Log.w(TAG, "IPowerManager.shutdown() failed: ${ipmEx.message}")
                            }
                        }

                        // Approach 3: reboot -p (legacy fallback — usually fails without root)
                        if (!shutdownSuccess) {
                            try {
                                val proc = Runtime.getRuntime().exec(arrayOf("reboot", "-p"))
                                proc.waitFor()
                                shutdownSuccess = true
                                Log.d(TAG, "reboot -p fallback succeeded")
                            } catch (execEx: Exception) {
                                lastError = "reboot -p: ${execEx.message}"
                                Log.e(TAG, "reboot -p fallback failed: ${execEx.message}")
                            }
                        }

                        JSONObject().apply {
                            put("executed", shutdownSuccess)
                            put("command", command)
                            if (!shutdownSuccess) {
                                put("error", "All shutdown methods failed. Last: $lastError")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shutdown failed: ${e.message}")
                    JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Shutdown failed: ${e.message}")
                    }
                }
            }
            "tts" -> {
                val text = params?.optString("text", "") ?: ""
                val language = params?.optString("language", "") ?: ""
                val voice = params?.optString("voice", "") ?: ""
                if (text.isNotEmpty()) {
                    speakText(text, language.ifEmpty { null }, voice.ifEmpty { null })
                    return JSONObject().apply {
                        put("executed", true)
                        put("command", command)
                    }
                } else {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Text is required")
                    }
                }
            }
            "clearCache" -> {
                clearWebViewCache()
                // Also send to JS to reload the WebView
                sendEvent("onApiCommand", Arguments.createMap().apply {
                    putString("command", "clearCache")
                    putString("params", "{}")
                })
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "lockDevice" -> {
                return try {
                    val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val adminComp = android.content.ComponentName(reactContext, DeviceAdminReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(reactContext.packageName) || dpm.isAdminActive(adminComp)) {
                        dpm.lockNow()
                        val method = if (dpm.isDeviceOwnerApp(reactContext.packageName)) "DeviceOwner" else "DeviceAdmin"
                        Log.d(TAG, "Device locked via $method lockNow()")
                        JSONObject().apply {
                            put("executed", true)
                            put("command", command)
                            put("method", method)
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && FreeKioskAccessibilityService.isRunning()) {
                        // AccessibilityService fallback (API 28+)
                        val ok = FreeKioskAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        if (ok) {
                            Log.d(TAG, "Device locked via AccessibilityService GLOBAL_ACTION_LOCK_SCREEN")
                            JSONObject().apply {
                                put("executed", true)
                                put("command", command)
                                put("method", "AccessibilityService")
                            }
                        } else {
                            JSONObject().apply {
                                put("executed", false)
                                put("command", command)
                                put("error", "GLOBAL_ACTION_LOCK_SCREEN failed")
                            }
                        }
                    } else {
                        Log.w(TAG, "Lock device failed: not Device Owner and no AccessibilityService")
                        JSONObject().apply {
                            put("executed", false)
                            put("command", command)
                            put("error", "Lock device requires Device Owner mode or AccessibilityService (API 28+)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lock device failed: ${e.message}")
                    JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Lock device failed: ${e.message}")
                    }
                }
            }
            "restartUi" -> {
                // Restart the app by starting a new MainActivity instance and finishing
                // the current one. We are Device Owner so startActivity works in Lock-Task.
                // Using activity.recreate() crashes on Android 16 / RN 0.82 because
                // pauseMedia() accesses an invalid WebView ref during teardown.
                // Process kill doesn't work because no watchdog service is running.
                // AlarmManager is blocked by BAL (Background Activity Launch) on Android 16.
                Log.d(TAG, "Restarting UI via startActivity + finishAffinity")
                // Apply pending config directly from SharedPreferences to AsyncStorage,
                // then emit a JS event to reload settings. No activity restart needed.
                try {
                    val prefs = reactContext.getSharedPreferences("FreeKioskPendingConfig", android.content.Context.MODE_PRIVATE)
                    if (prefs.getBoolean("has_pending_config", false)) {
                        val dbPath = reactContext.getDatabasePath("RKStorage").absolutePath
                        // BUG 11 FIX: Wrap DB operations in try/finally so the database is always
                        // closed, even if insertWithOnConflict throws (e.g. SQLiteDatabaseLockedException
                        // or corrupt table). Previously, an exception inside the loop left the DB
                        // handle open, which could cause lock exhaustion on subsequent calls.
                        val db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                        try {
                            var count = 0
                            for ((key, value) in prefs.all) {
                                if (key == "has_pending_config") continue
                                val cv = android.content.ContentValues()
                                cv.put("key", key)
                                cv.put("value", value.toString())
                                db.insertWithOnConflict("catalystLocalStorage", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                                count++
                                Log.d(TAG, "Applied pending config: $key=${value}")
                            }
                            prefs.edit().putBoolean("has_pending_config", false).apply()
                            Log.i(TAG, "Applied $count pending config keys")

                            // Emit event to JS to reload settings
                            reactContext.getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                                ?.emit("freekiosk_config_changed", null)
                        } finally {
                            db.close()
                        }
                    } else {
                        Log.d(TAG, "No pending config to apply")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply pending config: ${e.message}")
                }
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                }
            }
            "remoteKey" -> {
                val key = params?.optString("key", "") ?: ""
                if (key.isEmpty()) {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Key name is required")
                    }
                }
                val keyCode = when (key.lowercase()) {
                    "up" -> KeyEvent.KEYCODE_DPAD_UP
                    "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                    "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    "select", "center", "enter" -> KeyEvent.KEYCODE_DPAD_CENTER
                    "back" -> KeyEvent.KEYCODE_BACK
                    "home" -> KeyEvent.KEYCODE_HOME
                    "menu" -> KeyEvent.KEYCODE_MENU
                    "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "volumeup" -> KeyEvent.KEYCODE_VOLUME_UP
                    "volumedown" -> KeyEvent.KEYCODE_VOLUME_DOWN
                    "mute" -> KeyEvent.KEYCODE_VOLUME_MUTE
                    else -> null
                }
                if (keyCode == null) {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Unknown remote key: $key. Use up, down, left, right, select, back, home, menu, playpause, play, pause, stop, next, previous, volumeup, volumedown, mute")
                    }
                }
                dispatchKeyDown(keyCode)
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("key", key)
                    put("keyCode", keyCode)
                }
            }
            "keyboardKey" -> {
                val key = params?.optString("key", "") ?: ""
                if (key.isEmpty()) {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Key name is required")
                    }
                }
                val keyCode = mapKeyNameToKeyCode(key.lowercase())
                if (keyCode == null) {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Unknown key: $key. Use a-z, 0-9, f1-f12, space, tab, enter, escape, backspace, delete, etc.")
                    }
                }
                dispatchKeyDown(keyCode)
                return JSONObject().apply {
                    put("executed", true)
                    put("command", command)
                    put("key", key)
                    put("keyCode", keyCode)
                }
            }
            "keyboardCombo" -> {
                val map = params?.optString("map", "") ?: ""
                if (map.isEmpty()) {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Key combination (map) is required, e.g. ctrl+c, alt+f4, ctrl+shift+a")
                    }
                }
                return sendKeyboardCombo(map)
            }
            "keyboardText" -> {
                val text = params?.optString("text", "") ?: ""
                if (text.isEmpty()) {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", command)
                        put("error", "Text is required")
                    }
                }
                return sendKeyboardText(text)
            }
            "getLocation" -> {
                return getLocationInfo()
            }
        }
        
        // Send other commands to JS side
        sendEvent("onApiCommand", Arguments.createMap().apply {
            putString("command", command)
            putString("params", params?.toString() ?: "{}")
        })
        
        return JSONObject().apply {
            put("executed", true)
            put("command", command)
        }
    }

    // ==================== WiFi Lock Handler (Patch) ====================

    private fun handleWifiLockCommand(action: String, params: JSONObject?): JSONObject {
        Log.d(TAG, "handleWifiLockCommand: action=$action params=$params")
        return try {
            val wifiLockModule = com.freekiosk.WifiLockModule(reactContext)
            when (action) {
                "lock" -> {
                    val allowedSsids = params?.optString("allowed_ssids", "")
                        ?: params?.optJSONArray("allowed_ssids")?.let { arr ->
                            val list = mutableListOf<String>()
                            for (i in 0 until arr.length()) list.add(arr.getString(i))
                            org.json.JSONArray(list).toString()
                        } ?: ""
                    if (allowedSsids.isEmpty()) {
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "allowed_ssids is required (array or comma-separated string)")
                        }
                    }
                    // Use synchronous approach via DPM directly
                    val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val admin = android.content.ComponentName(reactContext, com.freekiosk.DeviceAdminReceiver::class.java)
                    if (!dpm.isDeviceOwnerApp(reactContext.packageName)) {
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "WiFi Lock requires Device Owner mode")
                        }
                    }

                    // Parse SSIDs
                    val ssids = mutableListOf<String>()
                    try {
                        val arr = org.json.JSONArray(allowedSsids)
                        for (i in 0 until arr.length()) ssids.add(arr.getString(i))
                    } catch (e: Exception) {
                        allowedSsids.split(",").forEach { s ->
                            val t = s.trim()
                            if (t.isNotEmpty()) ssids.add(t)
                        }
                    }
                    if (ssids.isEmpty()) {
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "No valid SSIDs provided")
                        }
                    }

                    // Add DISALLOW_CONFIG_WIFI
                    try {
                        dpm.addUserRestriction(admin, "no_config_wifi")
                    } catch (e: Exception) {
                        Log.w(TAG, "addUserRestriction failed: ${e.message}")
                    }

                    // Store allowed SSIDs in SharedPreferences for persistence
                    val prefs = reactContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("wifi_lock_active", true)
                        .putStringSet("allowed_ssids", ssids.toSet())
                        .apply()

                    // Start monitoring via the module — use a no-op Promise
                    val module = com.freekiosk.WifiLockModule(reactContext)
                    val noopPromise = object : com.facebook.react.bridge.Promise {
                        override fun resolve(value: Any?) { Log.d(TAG, "enableWifiLock resolved: $value") }
                        override fun reject(code: String, message: String?) { Log.w(TAG, "enableWifiLock rejected: $code $message") }
                        override fun reject(code: String, throwable: Throwable?) { Log.w(TAG, "enableWifiLock rejected: $code", throwable) }
                        override fun reject(code: String, message: String?, throwable: Throwable?) { Log.w(TAG, "enableWifiLock rejected: $code $message", throwable) }
                        override fun reject(throwable: Throwable) { Log.w(TAG, "enableWifiLock rejected", throwable) }
                        override fun reject(throwable: Throwable, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String, message: String?, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap?) {}
                        override fun reject(message: String) { Log.w(TAG, "enableWifiLock rejected: $message") }
                    }
                    module.enableWifiLock(org.json.JSONArray(ssids).toString(), noopPromise)

                    JSONObject().apply {
                        put("success", true)
                        put("action", "lock")
                        put("allowed_ssids", org.json.JSONArray(ssids))
                        put("disallow_config_wifi", true)
                    }
                }
                "unlock" -> {
                    val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val admin = android.content.ComponentName(reactContext, com.freekiosk.DeviceAdminReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(reactContext.packageName)) {
                        try {
                            dpm.clearUserRestriction(admin, "no_config_wifi")
                        } catch (e: Exception) {
                            Log.w(TAG, "clearUserRestriction failed: ${e.message}")
                        }
                        // Restore lock task features (re-enable power button)
                        try {
                            val currentFeatures = dpm.getLockTaskFeatures(admin)
                            val restoredFeatures = currentFeatures or android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                            dpm.setLockTaskFeatures(admin, restoredFeatures)
                            Log.d(TAG, "Power button restored on unlock: $currentFeatures -> $restoredFeatures")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not restore lock task features: ${e.message}")
                        }
                    }
                    // Also call disableWifiLock on the module to stop monitoring + cleanup
                    val module = com.freekiosk.WifiLockModule(reactContext)
                    val noopPromise = object : com.facebook.react.bridge.Promise {
                        override fun resolve(value: Any?) { Log.d(TAG, "disableWifiLock resolved: $value") }
                        override fun reject(code: String, message: String?) { Log.w(TAG, "disableWifiLock rejected: $code $message") }
                        override fun reject(code: String, throwable: Throwable?) { Log.w(TAG, "disableWifiLock rejected: $code", throwable) }
                        override fun reject(code: String, message: String?, throwable: Throwable?) { Log.w(TAG, "disableWifiLock rejected: $code $message", throwable) }
                        override fun reject(throwable: Throwable) { Log.w(TAG, "disableWifiLock rejected", throwable) }
                        override fun reject(throwable: Throwable, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String, message: String?, userInfo: com.facebook.react.bridge.WritableMap) {}
                        override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap?) {}
                        override fun reject(message: String) { Log.w(TAG, "disableWifiLock rejected: $message") }
                    }
                    module.disableWifiLock(noopPromise)

                    val prefs = reactContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()

                    JSONObject().apply {
                        put("success", true)
                        put("action", "unlock")
                        put("disallow_config_wifi", false)
                    }
                }
                "allow" -> {
                    val ssid = params?.optString("ssid", "") ?: ""
                    if (ssid.isEmpty()) {
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "ssid is required")
                        }
                    }
                    val prefs = reactContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
                    val current = prefs.getStringSet("allowed_ssids", emptySet()) ?: emptySet()
                    val updated = current + ssid.trim()
                    prefs.edit().putStringSet("allowed_ssids", updated).apply()

                    JSONObject().apply {
                        put("success", true)
                        put("action", "allow")
                        put("ssid", ssid.trim())
                        put("allowed_ssids", org.json.JSONArray(updated))
                    }
                }
                "status" -> {
                    val prefs = reactContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
                    val active = prefs.getBoolean("wifi_lock_active", false)
                    val ssids = prefs.getStringSet("allowed_ssids", emptySet()) ?: emptySet()

                    // Check current WiFi
                    val wifiManager = reactContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val connectivityManager = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val net = connectivityManager.activeNetwork
                    val caps = connectivityManager.getNetworkCapabilities(net)
                    var currentSsid: String? = null
                    var isConnected = false
                    if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        isConnected = true
                        @Suppress("DEPRECATION")
                        val wifiInfo = wifiManager.connectionInfo
                        @Suppress("DEPRECATION")
                        currentSsid = wifiInfo.ssid?.removeSurrounding("\"")
                    }

                    // Check DPM restriction
                    val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val um = reactContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager
                    val disallowConfigWifi = um.hasUserRestriction("no_config_wifi")

                    JSONObject().apply {
                        put("lockActive", active)
                        put("disallowConfigWifi", disallowConfigWifi)
                        put("currentSsid", currentSsid ?: JSONObject.NULL)
                        put("isConnected", isConnected)
                        put("isOnAllowedNetwork", currentSsid != null && ssids.any { it.equals(currentSsid, ignoreCase = true) })
                        put("allowed_ssids", org.json.JSONArray(ssids))
                    }
                }
                else -> JSONObject().apply {
                    put("success", false)
                    put("error", "Unknown WiFi Lock action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleWifiLockCommand error: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "WiFi Lock command failed")
            }
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    // ==================== JS Interface for Status Updates ====================

    @ReactMethod
    fun updateStatus(statusJson: String) {
        // Parse status from JS and update local variables
        try {
            val status = JSONObject(statusJson)
            if (status.has("currentUrl")) jsCurrentUrl = status.getString("currentUrl")
            if (status.has("canGoBack")) jsCanGoBack = status.getBoolean("canGoBack")
            if (status.has("loading")) jsLoading = status.getBoolean("loading")
            if (status.has("brightness")) jsBrightness = status.getInt("brightness")
            if (status.has("screensaverActive")) jsScreensaverActive = status.getBoolean("screensaverActive")
            if (status.has("kioskMode")) jsKioskMode = status.getBoolean("kioskMode")
            if (status.has("rotationEnabled")) jsRotationEnabled = status.getBoolean("rotationEnabled")
            if (status.has("rotationInterval")) jsRotationInterval = status.getInt("rotationInterval")
            if (status.has("rotationCurrentIndex")) jsRotationCurrentIndex = status.getInt("rotationCurrentIndex")
            if (status.has("rotationUrls")) {
                val urlsArray = status.getJSONArray("rotationUrls")
                jsRotationUrls = (0 until urlsArray.length()).map { urlsArray.getString(it) }
            }
            // Auto-brightness status
            if (status.has("autoBrightnessEnabled")) jsAutoBrightnessEnabled = status.getBoolean("autoBrightnessEnabled")
            if (status.has("autoBrightnessMin")) jsAutoBrightnessMin = status.getInt("autoBrightnessMin")
            if (status.has("autoBrightnessMax")) jsAutoBrightnessMax = status.getInt("autoBrightnessMax")
            Log.d(TAG, "Status updated: url=$jsCurrentUrl, screensaver=$jsScreensaverActive, rotation=$jsRotationEnabled, autoBrightness=$jsAutoBrightnessEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse status update from JS", e)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN event emitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN event emitter
    }

    @ReactMethod
    fun setVolume(value: Int, promise: Promise) {
        try {
            val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (value * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            Log.d(TAG, "Volume set to $value% (raw: $targetVolume/$maxVolume)")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            promise.reject("VOLUME_ERROR", e.message)
        }
    }
    @ReactMethod
    fun getVolume(promise: Promise) {
        try {
            val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumePercent = (currentVolume * 100) / maxVolume
            Log.d(TAG, "Current volume: $volumePercent% (raw: $currentVolume/$maxVolume)")
            promise.resolve(volumePercent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get volume: ${e.message}")
            promise.reject("ERROR", "Failed to get volume: ${e.message}")
        }
    }

    // ===== Volume Keys Block/Unblock (DISALLOW_ADJUST_VOLUME) =====
    // These mirror the power-button blocking pattern but for volume hardware keys.
    // Used by /api/volume/keys REST endpoints and by enableKioskRestrictions() on boot.

    /**
     * Add or clear the DISALLOW_ADJUST_VOLUME user restriction via DevicePolicyManager.
     * When blocked=true, hardware volume keys stop adjusting volume.
     * Requires Device Owner mode.
     */
    private fun setVolumeKeysRestriction(blocked: Boolean) {
        try {
            val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(reactContext, DeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(reactContext.packageName)) {
                if (blocked) {
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME)
                    Log.d(TAG, "Volume keys LOCKED via Device Owner (DISALLOW_ADJUST_VOLUME)")
                } else {
                    dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME)
                    Log.d(TAG, "Volume keys UNLOCKED via Device Owner (DISALLOW_ADJUST_VOLUME cleared)")
                }
            } else {
                Log.w(TAG, "Not Device Owner — cannot toggle volume keys restriction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume keys restriction (blocked=$blocked): ${e.message}")
        }
    }

    /**
     * Read the ACTUAL DPM restriction state. This reflects the real hardware state,
     * not just the stored config. Important for detecting stuck states after
     * AntiTheftAlarmModule crashes.
     */
    private fun isVolumeKeysRestrictionActive(): Boolean {
        return try {
            val um = reactContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            um.hasUserRestriction(android.os.UserManager.DISALLOW_ADJUST_VOLUME)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read volume keys restriction: ${e.message}")
            false
        }
    }

    @ReactMethod
    fun setVolumeKeysBlocked(blocked: Boolean, promise: Promise) {
        try {
            setVolumeKeysRestriction(blocked)
            // Also persist to AsyncStorage so enableKioskRestrictions() re-applies on reboot
            saveAsyncStorageValue("@kiosk_allow_volume_buttons", if (blocked) "false" else "true")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume keys blocked: ${e.message}")
            promise.reject("VOLUME_KEYS_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getVolumeKeysBlocked(promise: Promise) {
        try {
            val restrictionActive = isVolumeKeysRestrictionActive()
            promise.resolve(restrictionActive)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get volume keys blocked: ${e.message}")
            promise.reject("ERROR", "Failed to get volume keys blocked: ${e.message}")
        }
    }

    @ReactMethod
    fun showToast(message: String, promise: Promise) {
        try {
            UiThreadUtil.runOnUiThread {
                Toast.makeText(reactContext, message, Toast.LENGTH_LONG).show()
            }
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show toast", e)
            promise.reject("TOAST_ERROR", e.message)
        }
    }

    /**
     * Return the list of available TTS voices as a JSON array.
     * Called by the WebView speechSynthesis polyfill to populate getVoices().
     */
    @ReactMethod
    fun getTtsVoices(promise: Promise) {
        try {
            if (!ttsReady || ttsVoicesCache.isEmpty()) {
                // Try to re-cache if not ready
                cacheTtsVoices()
            }
            val voices = Arguments.createArray()
            for (voice in ttsVoicesCache) {
                val v = Arguments.createMap()
                v.putString("name", voice["name"] ?: "")
                v.putString("lang", voice["lang"] ?: "")
                v.putString("voiceUri", voice["voiceUri"] ?: "")
                v.putBoolean("localService", voice["localService"] == "true")
                v.putBoolean("default", voice["default"] == "true")
                voices.pushMap(v)
            }
            promise.resolve(voices)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TTS voices: ${e.message}")
            promise.reject("TTS_ERROR", e.message)
        }
    }

    /**
     * Speak text using the native Android TTS engine.
     * Exposed as a @ReactMethod so the WebView speechSynthesis polyfill can call it
     * via NativeModules.HttpServerModule.speak() from React Native.
     * @param voiceUri optional voice name to use (from getTtsVoices)
     */
    @ReactMethod
    fun speak(text: String, language: String, voiceUri: String, promise: Promise) {
        try {
            val lang = if (language.isNotEmpty()) language else null
            val voice = if (voiceUri.isNotEmpty() && voiceUri != "default") voiceUri else null
            speakText(text, lang, voice)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to speak text", e)
            promise.reject("TTS_ERROR", e.message)
        }
    }

    /**
     * Stop any ongoing TTS playback.
     * Used by the WebView speechSynthesis polyfill for cancel().
     */
    @ReactMethod
    fun stopSpeaking(promise: Promise) {
        try {
            tts?.stop()
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop speaking", e)
            promise.reject("TTS_ERROR", e.message)
        }
    }

    private fun getStorageInfo(): JSONObject {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            
            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - availableBytes
            
            JSONObject().apply {
                put("totalMB", totalBytes / (1024 * 1024))
                put("availableMB", availableBytes / (1024 * 1024))
                put("usedMB", usedBytes / (1024 * 1024))
                put("usedPercent", ((usedBytes.toDouble() / totalBytes) * 100).toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage info", e)
            JSONObject().apply {
                put("totalMB", 0)
                put("availableMB", 0)
                put("usedMB", 0)
                put("usedPercent", 0)
            }
        }
    }

    private fun getMemoryInfo(): JSONObject {
        return try {
            val activityManager = reactContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val totalMB = memInfo.totalMem / (1024 * 1024)
            val availableMB = memInfo.availMem / (1024 * 1024)
            val usedMB = totalMB - availableMB
            
            JSONObject().apply {
                put("totalMB", totalMB)
                put("availableMB", availableMB)
                put("usedMB", usedMB)
                put("usedPercent", ((usedMB.toDouble() / totalMB) * 100).toInt())
                put("lowMemory", memInfo.lowMemory)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get memory info", e)
            JSONObject().apply {
                put("totalMB", 0)
                put("availableMB", 0)
                put("usedMB", 0)
                put("usedPercent", 0)
                put("lowMemory", false)
            }
        }
    }

    // ==================== Audio Methods ====================

    private fun playAudio(url: String?, loop: Boolean, volume: Int) {
        try {
            stopAudio()
            
            if (url.isNullOrEmpty()) {
                Log.w(TAG, "No URL provided for audio playback")
                return
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                isLooping = loop
                setVolume(volume / 100f, volume / 100f)
                prepareAsync()
                setOnPreparedListener { start() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    true
                }
            }
            Log.d(TAG, "Playing audio: $url, loop=$loop, volume=$volume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
            Log.d(TAG, "Audio stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio", e)
        }
    }

    private fun playBeep() {
        Thread {
            try {
                // Generate a 440Hz beep (note A) for 200ms on MUSIC stream
                val sampleRate = 44100
                val durationMs = 200
                val numSamples = sampleRate * durationMs / 1000
                val samples = ShortArray(numSamples)
                val freqHz = 440.0 // Note A4
                
                // Generate sine wave
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i * freqHz / sampleRate
                    samples[i] = (Math.sin(angle) * Short.MAX_VALUE * 0.3).toInt().toShort() // 30% volume
                }
                
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                
                audioTrack.write(samples, 0, samples.size)
                audioTrack.play()
                
                // Wait for playback to finish then release
                Thread.sleep(durationMs.toLong() + 50)
                audioTrack.stop()
                audioTrack.release()
                
                Log.d(TAG, "Beep played (440Hz tone)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play beep", e)
            }
        }.start()
    }

    // ==================== Screen Control Methods ====================

    private fun turnScreenOn() = ScreenController.turnScreenOn(reactContext)

    private fun turnScreenOff() = ScreenController.turnScreenOff(reactContext)

    // ==================== Text-to-Speech ====================

    /**
     * Detect the best locale for the given text based on Unicode script analysis.
     * Checks the dominant script in the text and returns the appropriate Locale.
     */
    private fun detectLocaleForText(text: String): Locale {
        var cjkCount = 0
        var koreanCount = 0
        var japaneseCount = 0
        var arabicCount = 0
        var thaiCount = 0
        var devanagariCount = 0
        var cyrillicCount = 0
        var latinCount = 0
        var totalLetters = 0

        for (char in text) {
            if (!Character.isLetterOrDigit(char)) continue
            totalLetters++
            when {
                // Korean Hangul
                char in '\uAC00'..'\uD7AF' || char in '\u1100'..'\u11FF' || char in '\u3130'..'\u318F' -> koreanCount++
                // Japanese Hiragana + Katakana
                char in '\u3040'..'\u309F' || char in '\u30A0'..'\u30FF' -> japaneseCount++
                // CJK Unified Ideographs (Chinese/Japanese Kanji)
                char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF' || char in '\uF900'..'\uFAFF' -> cjkCount++
                // Arabic
                char in '\u0600'..'\u06FF' || char in '\u0750'..'\u077F' -> arabicCount++
                // Thai
                char in '\u0E00'..'\u0E7F' -> thaiCount++
                // Devanagari (Hindi)
                char in '\u0900'..'\u097F' -> devanagariCount++
                // Cyrillic
                char in '\u0400'..'\u04FF' -> cyrillicCount++
                // Latin
                char in 'A'..'Z' || char in 'a'..'z' || char in '\u00C0'..'\u024F' -> latinCount++
            }
        }

        if (totalLetters == 0) return Locale.getDefault()

        // Find the dominant non-Latin script
        val scriptCounts = mapOf(
            "korean" to koreanCount,
            "japanese" to japaneseCount,
            "cjk" to cjkCount,
            "arabic" to arabicCount,
            "thai" to thaiCount,
            "devanagari" to devanagariCount,
            "cyrillic" to cyrillicCount
        )
        val dominant = scriptCounts.maxByOrNull { it.value }

        // If any non-Latin script is present, use that language
        if (dominant != null && dominant.value > 0) {
            return when (dominant.key) {
                "korean" -> Locale.KOREAN
                "japanese" -> Locale.JAPANESE
                "cjk" -> Locale.SIMPLIFIED_CHINESE
                "arabic" -> Locale("ar")
                "thai" -> Locale("th")
                "devanagari" -> Locale("hi")
                "cyrillic" -> Locale("ru")
                else -> Locale.getDefault()
            }
        }

        // All Latin — use device default locale
        return Locale.getDefault()
    }

    /**
     * Parse a language string (e.g. "zh-CN", "en", "fr-FR") into a Locale.
     * Uses Locale.forLanguageTag() for proper BCP 47 handling, with a manual
     * split fallback for older Android versions or non-standard tags.
     */
    private fun parseLocale(language: String): Locale {
        val tag = language.replace("_", "-")
        return try {
            val locale = Locale.forLanguageTag(tag)
            // forLanguageTag returns und (undetermined) for unknown tags — fall back in that case
            if (locale.language.isNotEmpty() && locale.language != "und") locale
            else {
                val parts = tag.split("-")
                when (parts.size) {
                    1 -> Locale(parts[0])
                    2 -> Locale(parts[0], parts[1])
                    else -> Locale(parts[0], parts[1], parts[2])
                }
            }
        } catch (e: Exception) {
            val parts = tag.split("-")
            when (parts.size) {
                1 -> Locale(parts[0])
                2 -> Locale(parts[0], parts[1])
                else -> Locale(parts[0], parts[1], parts[2])
            }
        }
    }

    private fun speakText(text: String, language: String? = null, voiceName: String? = null) {
        try {
            if (tts != null && ttsReady) {
                // If a specific voice name is requested, try to find and set it
                if (!voiceName.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val matchingVoice = tts?.voices?.find { it.name == voiceName }
                    if (matchingVoice != null) {
                        val setResult = tts?.setVoice(matchingVoice)
                        Log.d(TAG, "TTS set voice: $voiceName (result=$setResult)")
                    } else {
                        Log.w(TAG, "TTS voice not found: $voiceName, using locale-based selection")
                        // Fall through to locale-based selection
                    }
                }

                // If no voice was set (or pre-Lollipop), use locale-based selection
                if (voiceName.isNullOrEmpty() || tts?.voice == null) {
                    val targetLocale = if (!language.isNullOrEmpty()) {
                        parseLocale(language)
                    } else {
                        detectLocaleForText(text)
                    }

                    val result = tts?.setLanguage(targetLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "TTS language not supported: $targetLocale, falling back to default")
                        tts?.setLanguage(Locale.getDefault())
                    } else {
                        Log.d(TAG, "TTS language set to: $targetLocale")
                    }
                }

                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "freekiosk_tts_${System.currentTimeMillis()}")
                Log.d(TAG, "TTS speaking: $text (voice=$voiceName)")
            } else {
                // TTS not ready, try to reinitialize
                Log.w(TAG, "TTS not ready, reinitializing...")
                initTts()
                // Retry after a short delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (ttsReady) {
                        speakText(text, language, voiceName)
                    }
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed: ${e.message}")
        }
    }

    // ==================== Key Dispatch Helpers ====================

    /**
     * Dispatch a simple key press (DOWN + UP) via the current Activity.
     * Works on all ROMs including e/OS, LineageOS, CalyxOS, GrapheneOS.
     * No Instrumentation / INJECT_EVENTS permission needed.
     */
    private fun dispatchKeyDown(keyCode: Int) {
        // Try AccessibilityService first (works cross-app on all ROMs)
        if (FreeKioskAccessibilityService.isRunning()) {
            FreeKioskAccessibilityService.sendKey(keyCode)
            return
        }
        // Fallback to Activity dispatchKeyEvent (works only in FreeKiosk's own Activity)
        UiThreadUtil.runOnUiThread {
            try {
                val activity = reactContext.currentActivity
                if (activity != null) {
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    Log.d(TAG, "Dispatched key via activity: $keyCode")
                } else {
                    Log.e(TAG, "Cannot dispatch key: no activity and AccessibilityService not running")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch key: ${e.message}")
            }
        }
    }

    /**
     * Dispatch a key press with modifier meta state (e.g., Ctrl+C, Alt+F4).
     * Constructs full KeyEvent with meta flags and dispatches via Activity.
     */
    private fun dispatchKeyWithMeta(keyCode: Int, metaState: Int) {
        // Try AccessibilityService first (works cross-app on all ROMs)
        if (FreeKioskAccessibilityService.isRunning()) {
            FreeKioskAccessibilityService.sendKeyWithMeta(keyCode, metaState)
            return
        }
        // Fallback to Activity dispatchKeyEvent (works only in FreeKiosk's own Activity)
        UiThreadUtil.runOnUiThread {
            try {
                val activity = reactContext.currentActivity
                if (activity != null) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
                    val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)
                    activity.dispatchKeyEvent(downEvent)
                    activity.dispatchKeyEvent(upEvent)
                    Log.d(TAG, "Dispatched key combo via activity: keyCode=$keyCode, metaState=$metaState")
                } else {
                    Log.e(TAG, "Cannot dispatch key combo: no activity and AccessibilityService not running")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch key combo: ${e.message}")
            }
        }
    }

    // ==================== Keyboard Key Mapping ====================

    /**
     * Map a key name (string) to an Android KeyEvent keycode.
     * Supports: single characters (a-z, 0-9, symbols), named keys (space, tab, enter, etc.),
     * function keys (f1-f12), navigation, media keys, and modifiers.
     */
    private fun mapKeyNameToKeyCode(key: String): Int? {
        // Single character keys
        if (key.length == 1) {
            val char = key[0]
            return when {
                char in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
                char in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
                char == ' ' -> KeyEvent.KEYCODE_SPACE
                char == '.' -> KeyEvent.KEYCODE_PERIOD
                char == ',' -> KeyEvent.KEYCODE_COMMA
                char == '-' -> KeyEvent.KEYCODE_MINUS
                char == '=' -> KeyEvent.KEYCODE_EQUALS
                char == '+' -> KeyEvent.KEYCODE_PLUS
                char == '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
                char == ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
                char == '\\' -> KeyEvent.KEYCODE_BACKSLASH
                char == '/' -> KeyEvent.KEYCODE_SLASH
                char == ';' -> KeyEvent.KEYCODE_SEMICOLON
                char == '\'' -> KeyEvent.KEYCODE_APOSTROPHE
                char == '`' -> KeyEvent.KEYCODE_GRAVE
                char == '@' -> KeyEvent.KEYCODE_AT
                char == '#' -> KeyEvent.KEYCODE_POUND
                char == '*' -> KeyEvent.KEYCODE_STAR
                char == '\t' -> KeyEvent.KEYCODE_TAB
                char == '\n' -> KeyEvent.KEYCODE_ENTER
                else -> null
            }
        }

        // Named keys
        return when (key) {
            // Whitespace / editing
            "space" -> KeyEvent.KEYCODE_SPACE
            "tab" -> KeyEvent.KEYCODE_TAB
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "backspace" -> KeyEvent.KEYCODE_DEL
            "delete", "del" -> KeyEvent.KEYCODE_FORWARD_DEL
            "insert", "ins" -> KeyEvent.KEYCODE_INSERT

            // Cursor / navigation (keyboard Home/End, not Android Home button)
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN

            // Function keys
            "f1" -> KeyEvent.KEYCODE_F1
            "f2" -> KeyEvent.KEYCODE_F2
            "f3" -> KeyEvent.KEYCODE_F3
            "f4" -> KeyEvent.KEYCODE_F4
            "f5" -> KeyEvent.KEYCODE_F5
            "f6" -> KeyEvent.KEYCODE_F6
            "f7" -> KeyEvent.KEYCODE_F7
            "f8" -> KeyEvent.KEYCODE_F8
            "f9" -> KeyEvent.KEYCODE_F9
            "f10" -> KeyEvent.KEYCODE_F10
            "f11" -> KeyEvent.KEYCODE_F11
            "f12" -> KeyEvent.KEYCODE_F12

            // Toggle keys
            "capslock" -> KeyEvent.KEYCODE_CAPS_LOCK
            "numlock" -> KeyEvent.KEYCODE_NUM_LOCK
            "scrolllock" -> KeyEvent.KEYCODE_SCROLL_LOCK

            // Modifier keys (as standalone presses)
            "shift" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "ctrl", "control" -> KeyEvent.KEYCODE_CTRL_LEFT
            "alt" -> KeyEvent.KEYCODE_ALT_LEFT
            "meta", "win", "cmd", "command" -> KeyEvent.KEYCODE_META_LEFT

            // Media keys
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "next", "nexttrack" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prevtrack" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volumeup" -> KeyEvent.KEYCODE_VOLUME_UP
            "volumedown" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "mute" -> KeyEvent.KEYCODE_VOLUME_MUTE

            // Android system keys
            "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU
            "search" -> KeyEvent.KEYCODE_SEARCH
            "power" -> KeyEvent.KEYCODE_POWER
            "select", "center", "dpadcenter" -> KeyEvent.KEYCODE_DPAD_CENTER
            "androidhome" -> KeyEvent.KEYCODE_HOME

            // Symbol names
            "period", "dot" -> KeyEvent.KEYCODE_PERIOD
            "comma" -> KeyEvent.KEYCODE_COMMA
            "minus", "dash" -> KeyEvent.KEYCODE_MINUS
            "plus" -> KeyEvent.KEYCODE_PLUS
            "equals" -> KeyEvent.KEYCODE_EQUALS
            "semicolon" -> KeyEvent.KEYCODE_SEMICOLON
            "apostrophe", "quote" -> KeyEvent.KEYCODE_APOSTROPHE
            "slash" -> KeyEvent.KEYCODE_SLASH
            "backslash" -> KeyEvent.KEYCODE_BACKSLASH
            "leftbracket" -> KeyEvent.KEYCODE_LEFT_BRACKET
            "rightbracket" -> KeyEvent.KEYCODE_RIGHT_BRACKET
            "grave", "backtick" -> KeyEvent.KEYCODE_GRAVE
            "at" -> KeyEvent.KEYCODE_AT
            "pound", "hash" -> KeyEvent.KEYCODE_POUND
            "star", "asterisk" -> KeyEvent.KEYCODE_STAR

            else -> null
        }
    }

    /**
     * Send a keyboard shortcut combination (e.g., "ctrl+c", "alt+f4", "ctrl+shift+a").
     * The last part is the key, preceding parts are modifiers (ctrl, alt, shift, meta).
     */
    private fun sendKeyboardCombo(map: String): JSONObject {
        // NanoHTTPD decodes '+' in query params as space, so split on both '+' and ' '
        val parts = map.lowercase().split(Regex("[+ ]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return JSONObject().apply {
                put("executed", false)
                put("command", "keyboardCombo")
                put("error", "Empty key combination")
            }
        }

        // Last part is the key, everything before is a modifier
        val keyName = parts.last()
        val modifiers = parts.dropLast(1)

        val keyCode = mapKeyNameToKeyCode(keyName)
        if (keyCode == null) {
            return JSONObject().apply {
                put("executed", false)
                put("command", "keyboardCombo")
                put("error", "Unknown key in combination: $keyName")
            }
        }

        // Build meta state from modifiers
        var metaState = 0
        for (mod in modifiers) {
            metaState = metaState or when (mod) {
                "ctrl", "control" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                "alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                "shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                "meta", "win", "cmd", "command" -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                else -> {
                    return JSONObject().apply {
                        put("executed", false)
                        put("command", "keyboardCombo")
                        put("error", "Unknown modifier: $mod. Valid modifiers: ctrl, alt, shift, meta")
                    }
                }
            }
        }

        val finalMetaState = metaState
        dispatchKeyWithMeta(keyCode, finalMetaState)

        return JSONObject().apply {
            put("executed", true)
            put("command", "keyboardCombo")
            put("map", map)
            put("key", keyName)
            put("keyCode", keyCode)
            put("modifiers", org.json.JSONArray(modifiers))
            put("metaState", finalMetaState)
        }
    }

    /**
     * Type a text string by dispatching key events for each character.
     * Uses KeyCharacterMap.getEvents() to convert chars to KeyEvent sequences,
     * then dispatches via activity.dispatchKeyEvent() (no Instrumentation needed).
     */
    private fun sendKeyboardText(text: String): JSONObject {
        // Try AccessibilityService first (works cross-app on all ROMs)
        if (FreeKioskAccessibilityService.isRunning()) {
            FreeKioskAccessibilityService.sendText(text)
            return JSONObject().apply {
                put("executed", true)
                put("command", "keyboardText")
                put("textLength", text.length)
                put("method", "accessibilityService")
            }
        }
        // Fallback to Activity dispatchKeyEvent (works only in FreeKiosk's own Activity)
        UiThreadUtil.runOnUiThread {
            try {
                val activity = reactContext.currentActivity
                if (activity != null) {
                    val kcm = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                    val events = kcm.getEvents(text.toCharArray())
                    if (events != null) {
                        for (event in events) {
                            activity.dispatchKeyEvent(event)
                        }
                    }
                    Log.d(TAG, "Keyboard text sent via activity: ${text.take(50)}${if (text.length > 50) "..." else ""}")
                } else {
                    Log.e(TAG, "Cannot send keyboard text: no activity and AccessibilityService not running")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send keyboard text: ${e.message}")
            }
        }

        return JSONObject().apply {
            put("executed", true)
            put("command", "keyboardText")
            put("textLength", text.length)
        }
    }

    // ==================== GPS Location ====================

    /**
     * Get the device's last known GPS location.
     * Tries GPS, Network, and Passive providers, returns the most accurate.
     * Requires ACCESS_FINE_LOCATION permission (already declared in manifest).
     */
    private fun getLocationInfo(): JSONObject {
        return try {
            val locationManager = reactContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try to get last known location from various providers (best accuracy wins)
            var bestLocation: Location? = null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (provider in providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        @Suppress("MissingPermission")
                        val location = locationManager.getLastKnownLocation(provider)
                        if (location != null) {
                            if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                                bestLocation = location
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No location permission for provider: $provider")
                }
            }

            // Check which providers are enabled
            val enabledProviders = providers.filter {
                try { locationManager.isProviderEnabled(it) } catch (e: Exception) { false }
            }

            JSONObject().apply {
                put("executed", true)
                put("command", "getLocation")
                put("providers", org.json.JSONArray(enabledProviders))
                if (bestLocation != null) {
                    put("available", true)
                    put("latitude", bestLocation!!.latitude)
                    put("longitude", bestLocation!!.longitude)
                    put("accuracy", bestLocation!!.accuracy.toDouble())
                    put("altitude", bestLocation!!.altitude)
                    put("speed", bestLocation!!.speed.toDouble())
                    put("bearing", bestLocation!!.bearing.toDouble())
                    put("provider", bestLocation!!.provider ?: "unknown")
                    put("time", bestLocation!!.time)
                } else {
                    put("available", false)
                    put("error", "No location available. Ensure GPS is enabled and location permission is granted.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location: ${e.message}")
            JSONObject().apply {
                put("executed", false)
                put("command", "getLocation")
                put("error", "Failed to get location: ${e.message}")
            }
        }
    }

    // ==================== WebView Cache Clearing ====================

    private fun clearWebViewCache() {
        UiThreadUtil.runOnUiThread {
            try {
                // Clear WebView cache and data
                android.webkit.WebView(reactContext.applicationContext).apply {
                    clearCache(true)
                    clearFormData()
                    clearHistory()
                    destroy()
                }
                // Also clear cookies
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                // Clear WebStorage
                android.webkit.WebStorage.getInstance().deleteAllData()
                Log.d(TAG, "WebView cache, cookies, and storage cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear WebView cache: ${e.message}")
            }
        }
    }

    // ==================== Screenshot Method ====================

    private fun captureScreenshot(): java.io.InputStream? {
        return try {
            var screenshot: ByteArrayInputStream? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            
            UiThreadUtil.runOnUiThread {
                try {
                    val activity = reactContext.currentActivity
                    val rootView = activity?.window?.decorView?.rootView
                    
                    if (rootView != null) {
                        rootView.isDrawingCacheEnabled = true
                        val bitmap = Bitmap.createBitmap(rootView.drawingCache)
                        rootView.isDrawingCacheEnabled = false
                        
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
                        screenshot = ByteArrayInputStream(outputStream.toByteArray())
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to capture screenshot on UI thread", e)
                } finally {
                    latch.countDown()
                }
            }
            
            // Wait for UI thread to complete (max 5 seconds)
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            screenshot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screenshot", e)
            null
        }
    }
    
    /**
     * Clean up resources when module is destroyed
     */
    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        try {
            server?.stop()
            server = null
            releaseServerLocks()
            mediaPlayer?.release()
            mediaPlayer = null
            toneGenerator?.release()
            toneGenerator = null
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
            sensorManager?.unregisterListener(this)
            cameraPhotoModule = null
            Log.d(TAG, "HttpServerModule cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }
}
