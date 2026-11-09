package com.freekiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule

class AntiTheftAlarmModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AntiTheftAlarm"
        private const val ALARM_GRACE_PERIOD_MS = 5000L // 5s grace before alarm triggers
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var alarmActive = false
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var periodicCheckRunnable: Runnable? = null
    private var graceHandler: Runnable? = null
    private var savedVolume = -1

    override fun getName() = "AntiTheftAlarmModule"

    @ReactMethod
    fun setEnabled(enabled: Boolean, promise: Promise) {
        this.enabled = enabled
        if (enabled) {
            startMonitoring()
            Log.d(TAG, "Anti-theft alarm enabled")
        } else {
            stopMonitoring()
            stopAlarm()
            Log.d(TAG, "Anti-theft alarm disabled")
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun isEnabled(promise: Promise) {
        promise.resolve(enabled)
    }

    @ReactMethod
    fun isAlarmActive(promise: Promise) {
        promise.resolve(alarmActive)
    }

    @ReactMethod
    fun triggerAlarm(promise: Promise) {
        mainHandler.post {
            startAlarm()
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun stopAlarmMethod(promise: Promise) {
        mainHandler.post {
            stopAlarm()
            promise.resolve(true)
        }
    }

    private fun startMonitoring() {
        if (networkCallback != null) return

        val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // NetworkCallback for real-time network changes (Android 7+)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                mainHandler.post { checkConnectivity() }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                mainHandler.post { checkConnectivity() }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mainHandler.post { checkConnectivity() }
            }
        }

        cm.registerNetworkCallback(
            android.net.NetworkRequest.Builder().build(),
            networkCallback!!
        )

        // Periodic fallback check every 10s (in case callback misses events)
        periodicCheckRunnable = object : Runnable {
            override fun run() {
                checkConnectivity()
                if (enabled) {
                    mainHandler.postDelayed(this, 10000)
                }
            }
        }
        mainHandler.postDelayed(periodicCheckRunnable!!, 10000)

        // Initial check
        checkConnectivity()
    }

    private fun stopMonitoring() {
        networkCallback?.let { callback ->
            try {
                val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null

        periodicCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        periodicCheckRunnable = null

        // Cancel any pending grace period
        graceHandler?.let { mainHandler.removeCallbacks(it) }
        graceHandler = null
    }

    private fun getAllowedSsids(): Set<String> {
        val prefs = reactApplicationContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
        return prefs.getStringSet("allowed_ssids", emptySet()) ?: emptySet()
    }

    private fun getCurrentSsid(): String? {
        return try {
            val wifiManager = reactApplicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val connectivityManager = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // API 31+: use transportInfo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wifiInfo = caps.transportInfo as? android.net.wifi.WifiInfo
                    wifiInfo?.ssid?.replace("\"", "")?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                } else {
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager.connectionInfo
                    wifiInfo?.ssid?.replace("\"", "")?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isOnAllowedNetwork(): Boolean {
        val cm = reactApplicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (!hasWifi) return false

        val allowedSsids = getAllowedSsids()
        if (allowedSsids.isEmpty()) {
            // No allowlist configured — any WiFi is allowed
            return true
        }

        // Try to get SSID — on Android 12+ this may return null without location permission
        val currentSsid = getCurrentSsid()
        if (currentSsid == null) {
            // SSID unreadable but WiFi is connected — treat as allowed to avoid false alarms
            Log.w(TAG, "WiFi connected but SSID unreadable — treating as allowed (avoid false alarm)")
            return true
        }
        return allowedSsids.any { it.equals(currentSsid, ignoreCase = true) }
    }

    private fun checkConnectivity() {
        if (!enabled) return

        val onAllowed = isOnAllowedNetwork()

        if (!onAllowed) {
            // Not on any allowed WiFi — start grace period before triggering alarm
            if (graceHandler == null && !alarmActive) {
                Log.d(TAG, "WiFi lost or not on allowed network — starting ${ALARM_GRACE_PERIOD_MS}ms grace period")
                graceHandler = Runnable {
                    // Re-check before triggering
                    if (!isOnAllowedNetwork() && enabled && !alarmActive) {
                        Log.w(TAG, "Still not on allowed network after grace period — TRIGGERING ALARM")
                        startAlarm()
                    }
                    graceHandler = null
                }
                mainHandler.postDelayed(graceHandler!!, ALARM_GRACE_PERIOD_MS)
            }
        } else {
            // On allowed WiFi — cancel grace period and stop alarm
            graceHandler?.let { mainHandler.removeCallbacks(it) }
            graceHandler = null
            if (alarmActive) {
                Log.d(TAG, "Back on allowed WiFi — stopping alarm")
                stopAlarm()
            }
        }
    }

    private fun startAlarm() {
        if (alarmActive) return
        alarmActive = true

        try {
            val audioManager = reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Save current volume to restore later
            savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Lock volume adjustment via Device Owner
            lockVolumeKeys(true)

            // Set volume to max
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            // Also set alarm and ringtone streams to max
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)

            // Start vibration
            startVibration()

            // Start alarm sound — use system alarm sound in loop
            startAlarmSound()

            Log.d(TAG, "Alarm started — volume maxed, vibration active, sound playing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm: ${e.message}")
        }
    }

    private fun startAlarmSound() {
        try {
            // Use Android's default alarm sound
            val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(reactApplicationContext, alarmSound)
                isLooping = true
                // Use alarm stream so it plays even in DND/silent mode
                setAudioStreamType(AudioManager.STREAM_ALARM)
                setOnPreparedListener { mp ->
                    mp.start()
                    Log.d(TAG, "Alarm sound started playing")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    // Fallback: try with STREAM_MUSIC
                    try {
                        reset()
                        setDataSource(reactApplicationContext, alarmSound)
                        isLooping = true
                        setAudioStreamType(AudioManager.STREAM_MUSIC)
                        prepare()
                        start()
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback MediaPlayer also failed: ${e2.message}")
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound: ${e.message}")
        }
    }

    private fun startVibration() {
        try {
            vibrator = reactApplicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 1000, 500) // vibrate 1s, pause 0.5s, repeat
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration not available: ${e.message}")
        }
    }

    private fun stopAlarm() {
        if (!alarmActive) return
        alarmActive = false

        try {
            // Stop sound
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                    it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping MediaPlayer: ${e.message}")
                }
            }
            mediaPlayer = null

            // Stop vibration
            vibrator?.cancel()
            vibrator = null

            // Unlock volume keys
            lockVolumeKeys(false)

            // Restore volume
            if (savedVolume >= 0) {
                val audioManager = reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                savedVolume = -1
            }

            Log.d(TAG, "Alarm stopped — volume restored, keys unlocked")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm: ${e.message}")
        }
    }

    private fun lockVolumeKeys(locked: Boolean) {
        try {
            val dpm = reactApplicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(reactApplicationContext, DeviceAdminReceiver::class.java)

            if (dpm.isDeviceOwnerApp(reactApplicationContext.packageName)) {
                if (locked) {
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME)
                    Log.d(TAG, "Volume keys LOCKED via Device Owner")
                } else {
                    dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME)
                    Log.d(TAG, "Volume keys UNLOCKED via Device Owner")
                }
            } else {
                Log.w(TAG, "Not Device Owner — cannot lock volume keys")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock/unlock volume keys: ${e.message}")
        }
    }
}
