package com.freekiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

class WifiLockModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "WifiLockModule"
        private const val DISALLOW_CONFIG_WIFI = "no_config_wifi"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenOnReceiver: BroadcastReceiver? = null
    private var allowedSsids: MutableList<String> = mutableListOf()
    private var lockActive = false
    private var isLocked = false
    private var originalLockTaskFeatures: Int = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    // Anti-theft alarm
    private var alarmActive = false
    private var savedVolume = -1
    // DHCP auto-recovery
    private var dhcpRecoveryAttempts = 0
    private var dhcpRecoveryInProgress = false
    private var noIpSinceTimestamp = 0L
    private val MAX_DHCP_RECOVERY_ATTEMPTS = 3
    private val DHCP_GRACE_PERIOD_MS = 15000L  // Wait 15s before attempting recovery
    private val DHCP_RECOVERY_OFF_MS = 3000L   // WiFi off duration
    private val DHCP_RECOVERY_SETTLE_MS = 12000L // Wait after re-enable before re-adding restriction

    override fun getName(): String = "WifiLockModule"

    override fun initialize() {
        super.initialize()
        // Restore WiFi-Lock if it was active before app restart
        restoreWifiLockIfNeeded()
    }

    private fun restoreWifiLockIfNeeded() {
        try {
            val prefs = reactContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
            val active = prefs.getBoolean("wifi_lock_active", false)
            val ssids = prefs.getStringSet("allowed_ssids", emptySet()) ?: emptySet()
            if (active && ssids.isNotEmpty()) {
                Log.d(TAG, "Restoring WiFi-Lock with SSIDs: $ssids")
                val dpm = getDpm()
                if (dpm != null && dpm.isDeviceOwnerApp(reactContext.packageName)) {
                    // Re-enable WiFi-Lock with saved SSIDs
                    allowedSsids.clear()
                    allowedSsids.addAll(ssids)
                    lockActive = true
                    isLocked = false

                    // Re-register network callback + screen-on receiver
                    startMonitoring()

                    // Check current network state
                    mainHandler.postDelayed({ checkNetwork() }, 3000)
                    Log.d(TAG, "WiFi-Lock restored with ${ssids.size} SSIDs")
                } else {
                    Log.w(TAG, "Cannot restore WiFi-Lock: not Device Owner")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore WiFi-Lock: ${e.message}")
        }
    }

    override fun onCatalystInstanceDestroy() {
        stopMonitoring()
        // BUG 17 FIX: Remove any pending Handler callbacks (e.g. the postDelayed(checkNetwork, 3000)
        // from restoreWifiLockIfNeeded). Without this, a callback could fire after the catalyst
        // instance is destroyed and touch a stale reactContext, leading to crashes or leaks.
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun getDpm(): DevicePolicyManager? {
        val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(reactContext, DeviceAdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(reactContext.packageName)) {
            Log.w(TAG, "Not Device Owner — WiFi Lock requires Device Owner mode")
            return null
        }
        return dpm
    }

    private fun getAdmin(): ComponentName = ComponentName(reactContext, DeviceAdminReceiver::class.java)

    private fun getCurrentSsid(): String? {
        return try {
            val wifiManager = reactContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectivityManager = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // Try transportInfo first (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wifiInfo = caps.transportInfo as? WifiInfo
                    val ssid = wifiInfo?.ssid?.removeSurrounding("\"")
                    if (ssid != null && ssid != "<unknown ssid>") return ssid
                }
                // Fallback: deprecated API
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                @Suppress("DEPRECATION")
                val ssid = wifiInfo?.ssid?.removeSurrounding("\"")
                if (ssid != null && ssid != "<unknown ssid>") return ssid
                // WiFi connected but SSID not readable — return marker so checkNetwork doesn't lock
                return "<unknown ssid>"
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentSsid error: ${e.message}")
            null
        }
    }

    @ReactMethod
    fun enableWifiLock(allowedSsidsJson: String, promise: Promise) {
        try {
            val dpm = getDpm()
            if (dpm == null) {
                promise.reject("NOT_DEVICE_OWNER", "WiFi Lock requires Device Owner mode")
                return
            }

            // Parse allowed SSIDs
            val ssids = mutableListOf<String>()
            try {
                val arr = JSONArray(allowedSsidsJson)
                for (i in 0 until arr.length()) {
                    ssids.add(arr.getString(i))
                }
            } catch (e: Exception) {
                // Maybe it's a comma-separated string
                allowedSsidsJson.split(",").forEach { s ->
                    val trimmed = s.trim()
                    if (trimmed.isNotEmpty()) ssids.add(trimmed)
                }
            }

            if (ssids.isEmpty()) {
                promise.reject("NO_SSIDS", "At least one allowed SSID is required")
                return
            }

            allowedSsids.clear()
            allowedSsids.addAll(ssids)
            lockActive = true
            isLocked = false
            dhcpRecoveryAttempts = 0
            dhcpRecoveryInProgress = false
            noIpSinceTimestamp = 0L

            // Stop any active alarm from a previous lock session
            if (alarmActive) {
                Log.d(TAG, "Stopping active alarm on new enableWifiLock call")
                stopAlarm(dpm)
            }
            // Always send STOP intent to AlarmService as safety net
            try {
                val stopIntent = Intent(reactContext, AlarmService::class.java).apply {
                    action = AlarmService.ACTION_STOP
                }
                reactContext.startService(stopIntent)
            } catch (e: Exception) {}

            // 1. Set DISALLOW_CONFIG_WIFI user restriction
            try {
                dpm.addUserRestriction(getAdmin(), DISALLOW_CONFIG_WIFI)
                Log.d(TAG, "Added user restriction: $DISALLOW_CONFIG_WIFI")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add $DISALLOW_CONFIG_WIFI: ${e.message}")
            }

            // 2. Set permitted access points (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val wifiManager = reactContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    // setPermittedAccessPoints is not available via WifiManager DPM API directly
                    // We use DevicePolicyManager.setPermittedAccessPoints on API 29+
                    // Actually this is a method on DevicePolicyManager for Device Owner
                    // dpm.setPermittedAccessPoints(admin, ssids) — but this restricts which APs the user can connect to
                    // However, the method signature requires package name for profile owner, for device owner it's different
                    // Let's try it
                    // Actually: setPermittedAccessPoints is on DevicePolicyManager since API 29
                    // but it's for profile owner, not device owner. For device owner, we need
                    // to use a different approach. Let's skip this for now and rely on
                    // DISALLOW_CONFIG_WIFI + monitoring.
                    Log.d(TAG, "setPermittedAccessPoints not used — relying on DISALLOW_CONFIG_WIFI + monitoring")
                } catch (e: Exception) {
                    Log.w(TAG, "setPermittedAccessPoints failed: ${e.message}")
                }
            }

            // 3. Start monitoring
            startMonitoring()

            // 4. Check immediately if we're on an allowed network
            val currentSsid = getCurrentSsid()
            if (currentSsid == null) {
                Log.w(TAG, "No WiFi connection — locking device + starting alarm")
                lockDevice(dpm)
            } else if (currentSsid != "<unknown ssid>" && !isSsidAllowed(currentSsid)) {
                Log.w(TAG, "Connected to non-allowed SSID '$currentSsid' — locking device + starting alarm")
                lockDevice(dpm)
            }

            Log.d(TAG, "WiFi Lock enabled with allowed SSIDs: $ssids")
            promise.resolve(Arguments.createMap().apply {
                putBoolean("success", true)
                putString("allowedSsids", JSONArray(ssids).toString())
                putString("currentSsid", currentSsid)
                putBoolean("isLocked", isLocked)
            })
        } catch (e: Exception) {
            Log.e(TAG, "enableWifiLock error: ${e.message}", e)
            promise.reject("ENABLE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun disableWifiLock(promise: Promise) {
        try {
            val dpm = getDpm()

            // Stop monitoring
            stopMonitoring()

            // Remove DISALLOW_CONFIG_WIFI + restore power button
            if (dpm != null) {
                try {
                    dpm.clearUserRestriction(getAdmin(), DISALLOW_CONFIG_WIFI)
                    Log.d(TAG, "Removed user restriction: $DISALLOW_CONFIG_WIFI")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove $DISALLOW_CONFIG_WIFI: ${e.message}")
                }
                // Restore original lock task features (re-enable power button if it was disabled)
                if (isLocked) {
                    try {
                        dpm.setLockTaskFeatures(getAdmin(), originalLockTaskFeatures)
                        Log.d(TAG, "Power button restored on disable (features: $originalLockTaskFeatures)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not restore lock task features: ${e.message}")
                    }
                }
            }

            lockActive = false
            isLocked = false
            allowedSsids.clear()
            dhcpRecoveryAttempts = 0
            dhcpRecoveryInProgress = false
            noIpSinceTimestamp = 0L

            // Clear persisted state so it's not restored on reboot
            val prefs = reactContext.getSharedPreferences("FreeKioskWifiLock", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()

            Log.d(TAG, "WiFi Lock disabled")
            promise.resolve(Arguments.createMap().apply {
                putBoolean("success", true)
            })
        } catch (e: Exception) {
            Log.e(TAG, "disableWifiLock error: ${e.message}", e)
            promise.reject("DISABLE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun addAllowedSsid(ssid: String, promise: Promise) {
        try {
            if (!lockActive) {
                promise.reject("LOCK_NOT_ACTIVE", "WiFi Lock is not active")
                return
            }

            val trimmed = ssid.trim()
            if (trimmed.isEmpty()) {
                promise.reject("EMPTY_SSID", "SSID cannot be empty")
                return
            }

            if (!allowedSsids.contains(trimmed)) {
                allowedSsids.add(trimmed)
                Log.d(TAG, "Added allowed SSID: $trimmed (now: $allowedSsids)")
            }

            // If device was locked and we're now on an allowed network, unlock
            val currentSsid = getCurrentSsid()
            if (isLocked && currentSsid != null && isSsidAllowed(currentSsid)) {
                Log.d(TAG, "Now on allowed SSID '$currentSsid' — unlocking")
                isLocked = false
                sendEvent("wifiLockUnlocked", null)
            }

            promise.resolve(Arguments.createMap().apply {
                putBoolean("success", true)
                putString("allowedSsids", JSONArray(allowedSsids).toString())
            })
        } catch (e: Exception) {
            promise.reject("ADD_SSID_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun removeAllowedSsid(ssid: String, promise: Promise) {
        try {
            if (!lockActive) {
                promise.reject("LOCK_NOT_ACTIVE", "WiFi Lock is not active")
                return
            }

            allowedSsids.remove(ssid.trim())
            Log.d(TAG, "Removed SSID: $ssid (remaining: $allowedSsids)")

            // Check if current SSID is still allowed
            val currentSsid = getCurrentSsid()
            if (currentSsid != null && !isSsidAllowed(currentSsid)) {
                val dpm = getDpm()
                if (dpm != null) {
                    Log.w(TAG, "Current SSID '$currentSsid' no longer allowed — locking")
                    lockDevice(dpm)
                }
            }

            promise.resolve(Arguments.createMap().apply {
                putBoolean("success", true)
                putString("allowedSsids", JSONArray(allowedSsids).toString())
            })
        } catch (e: Exception) {
            promise.reject("REMOVE_SSID_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getWifiLockStatus(promise: Promise) {
        try {
            val currentSsid = getCurrentSsid()
            val dpm = getDpm()
            var restrictionActive = false
            if (dpm != null) {
                try {
                    // Check if DISALLOW_CONFIG_WIFI is active
                    val restrictions = dpm.getUserRestrictions(getAdmin())
                    restrictionActive = restrictions?.getBoolean(DISALLOW_CONFIG_WIFI) == true
                } catch (e: Exception) {
                    // Fallback: check via UserManager
                    val um = reactContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager
                    restrictionActive = um.hasUserRestriction(DISALLOW_CONFIG_WIFI)
                }
            }

            promise.resolve(Arguments.createMap().apply {
                putBoolean("lockActive", lockActive)
                putBoolean("isLocked", isLocked)
                putBoolean("disallowConfigWifi", restrictionActive)
                putString("currentSsid", currentSsid)
                putBoolean("isOnAllowedNetwork", currentSsid != null && isSsidAllowed(currentSsid))
                putArray("allowedSsids", Arguments.fromList(allowedSsids))
            })
        } catch (e: Exception) {
            promise.reject("STATUS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun isDeviceOwner(promise: Promise) {
        try {
            val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            promise.resolve(dpm.isDeviceOwnerApp(reactContext.packageName))
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    private fun isSsidAllowed(ssid: String): Boolean {
        val clean = ssid.removeSurrounding("\"")
        return allowedSsids.any { it.equals(clean, ignoreCase = true) }
    }

    /**
     * Check if we have a valid (non-link-local, non-loopback) IP address on WiFi.
     * Returns false if DHCP hasn't assigned an IP yet (or failed).
     */
    private fun hasValidIpAddress(): Boolean {
        return try {
            val connectivityManager = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = connectivityManager.activeNetwork
            val linkProps = connectivityManager.getLinkProperties(net)
            if (linkProps != null) {
                linkProps.linkAddresses.any { addr ->
                    val ip = addr.address
                    !ip.isLoopbackAddress && !ip.isLinkLocalAddress && ip.hostAddress != "0.0.0.0"
                }
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "hasValidIpAddress error: ${e.message}")
            false
        }
    }

    /**
     * Auto-recovery for DHCP failures: toggles WiFi off/on to force a fresh DHCP request.
     * Temporarily clears DISALLOW_CONFIG_WIFI (which blocks setWifiEnabled), then re-adds it.
     */
    private fun attemptDhcpRecovery() {
        if (dhcpRecoveryInProgress) {
            Log.d(TAG, "DHCP recovery already in progress — skipping")
            return
        }
        if (dhcpRecoveryAttempts >= MAX_DHCP_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Max DHCP recovery attempts reached ($dhcpRecoveryAttempts) — giving up. " +
                    "Tablet will need manual intervention (reboot or ADB wifi reconnect).")
            return
        }

        dhcpRecoveryInProgress = true
        dhcpRecoveryAttempts++
        Log.w(TAG, "Starting DHCP recovery attempt $dhcpRecoveryAttempts/$MAX_DHCP_RECOVERY_ATTEMPTS")

        val dpm = getDpm()

        // Temporarily clear DISALLOW_CONFIG_WIFI to allow WiFi toggle
        if (dpm != null) {
            try {
                dpm.clearUserRestriction(getAdmin(), DISALLOW_CONFIG_WIFI)
                Log.d(TAG, "Temporarily cleared DISALLOW_CONFIG_WIFI for DHCP recovery")
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear DISALLOW_CONFIG_WIFI: ${e.message}")
            }
        }

        val wifiManager = reactContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Toggle WiFi off
        try {
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(false)
            Log.d(TAG, "WiFi disabled for DHCP recovery")
        } catch (e: Exception) {
            Log.e(TAG, "WiFi disable failed: ${e.message}")
            // Re-add restriction and abort
            if (dpm != null) {
                try { dpm.addUserRestriction(getAdmin(), DISALLOW_CONFIG_WIFI) } catch (_: Exception) {}
            }
            dhcpRecoveryInProgress = false
            return
        }

        // Toggle WiFi on after delay
        mainHandler.postDelayed({
            try {
                @Suppress("DEPRECATION")
                wifiManager.setWifiEnabled(true)
                Log.d(TAG, "WiFi re-enabled for DHCP recovery")
            } catch (e: Exception) {
                Log.e(TAG, "WiFi re-enable failed: ${e.message}")
            }

            // Re-add DISALLOW_CONFIG_WIFI after WiFi has time to connect + get DHCP
            mainHandler.postDelayed({
                if (dpm != null) {
                    try {
                        dpm.addUserRestriction(getAdmin(), DISALLOW_CONFIG_WIFI)
                        Log.d(TAG, "DISALLOW_CONFIG_WIFI re-added after DHCP recovery")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not re-add DISALLOW_CONFIG_WIFI: ${e.message}")
                    }
                }
                dhcpRecoveryInProgress = false

                // Check if recovery was successful
                mainHandler.postDelayed({
                    val hasIp = hasValidIpAddress()
                    val ssid = getCurrentSsid()
                    Log.d(TAG, "DHCP recovery check: ssid=$ssid, hasIp=$hasIp, attempt=$dhcpRecoveryAttempts")
                    if (hasIp) {
                        Log.d(TAG, "DHCP recovery SUCCESSFUL — IP address obtained")
                        dhcpRecoveryAttempts = 0
                        noIpSinceTimestamp = 0L
                    } else if (dhcpRecoveryAttempts < MAX_DHCP_RECOVERY_ATTEMPTS) {
                        Log.w(TAG, "DHCP recovery did not succeed — will retry")
                        mainHandler.postDelayed({ checkNetwork() }, 5000)
                    }
                }, 5000)
            }, DHCP_RECOVERY_SETTLE_MS)
        }, DHCP_RECOVERY_OFF_MS)
    }

    private fun startMonitoring() {
        stopMonitoring()

        // Save original lock task features so we can restore them on unlock
        val dpm = getDpm()
        if (dpm != null) {
            try {
                originalLockTaskFeatures = dpm.getLockTaskFeatures(getAdmin())
                Log.d(TAG, "Saved original lock task features: $originalLockTaskFeatures")
            } catch (e: Exception) {
                Log.w(TAG, "Could not read lock task features: ${e.message}")
            }
        }

        val connectivityManager = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available (WiFi)")
                checkNetwork()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost (WiFi)")
                mainHandler.postDelayed({
                    checkNetwork()
                }, 3000)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    checkNetwork()
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        networkCallback = callback

        // Register SCREEN_ON receiver — re-lock immediately if someone wakes the display
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!lockActive || !isLocked) return
                Log.d(TAG, "Screen on (${intent.action}) while WiFi-Locked — re-locking + screen off")
                // 1. Immediately dim screen to 0 brightness (fastest visual response)
                try {
                    val activity = reactContext.currentActivity
                    if (activity != null) {
                        UiThreadUtil.runOnUiThread {
                            try {
                                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                val lp = activity.window.attributes
                                lp.screenBrightness = 0f
                                activity.window.attributes = lp
                            } catch (e: Exception) {
                                Log.w(TAG, "dimScreen failed: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not dim screen: ${e.message}")
                }
                // 2. Lock via DPM
                val dpm = getDpm()
                if (dpm != null) {
                    try {
                        dpm.lockNow()
                        Log.d(TAG, "Re-locked via dpm.lockNow() after screen on")
                    } catch (e: Exception) {
                        Log.e(TAG, "Re-lock failed: ${e.message}")
                    }
                }
                // 3. Also use ScreenController for full screen-off (WakeLock release + lockNow)
                try {
                    ScreenController.turnScreenOff(reactContext)
                } catch (e: Exception) {
                    Log.w(TAG, "ScreenController.turnScreenOff failed: ${e.message}")
                }
            }
        }
        reactContext.registerReceiver(screenOnReceiver, screenFilter)

        Log.d(TAG, "WiFi monitoring + screen-on re-lock started")
    }

    private fun stopMonitoring() {
        networkCallback?.let {
            try {
                val connectivityManager = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
        }
        networkCallback = null

        screenOnReceiver?.let {
            try {
                reactContext.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering screen receiver: ${e.message}")
            }
        }
        screenOnReceiver = null

        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun checkNetwork() {
        if (!lockActive) return

        val currentSsid = getCurrentSsid()
        Log.d(TAG, "checkNetwork: currentSsid=$currentSsid, allowed=$allowedSsids, isLocked=$isLocked")

        if (currentSsid == null) {
            // No WiFi connection at all
            if (!isLocked) {
                val dpm = getDpm()
                if (dpm != null) {
                    Log.w(TAG, "No WiFi connection — locking device")
                    lockDevice(dpm)
                }
            }
        } else if (currentSsid == "<unknown ssid>") {
            // WiFi connected but SSID not readable — don't lock (avoid false alarms)
            Log.d(TAG, "WiFi connected but SSID unreadable — not locking")
            // If alarm is active, retry in 2s — SSID may become readable shortly
            if (alarmActive || isLocked) {
                mainHandler.postDelayed({ checkNetwork() }, 2000)
            }
        } else if (!isSsidAllowed(currentSsid)) {
            // Connected to non-allowed SSID
            if (!isLocked) {
                val dpm = getDpm()
                if (dpm != null) {
                    Log.w(TAG, "Connected to non-allowed SSID '$currentSsid' — locking device")
                    lockDevice(dpm)
                }
            }
        } else {
            // On allowed network — check if DHCP assigned a valid IP
            if (!hasValidIpAddress()) {
                // L2 connected but no IP (DHCP failure)
                if (noIpSinceTimestamp == 0L) {
                    noIpSinceTimestamp = System.currentTimeMillis()
                    Log.w(TAG, "On allowed SSID '$currentSsid' but no valid IP (DHCP pending) — waiting ${DHCP_GRACE_PERIOD_MS}ms")
                    // Re-check after grace period
                    mainHandler.postDelayed({ checkNetwork() }, DHCP_GRACE_PERIOD_MS)
                    return
                }
                val elapsed = System.currentTimeMillis() - noIpSinceTimestamp
                if (elapsed >= DHCP_GRACE_PERIOD_MS && !dhcpRecoveryInProgress) {
                    Log.w(TAG, "On allowed SSID '$currentSsid' but no valid IP after ${elapsed}ms — DHCP failure, attempting recovery")
                    attemptDhcpRecovery()
                    // Re-check after recovery
                    mainHandler.postDelayed({ checkNetwork() }, DHCP_RECOVERY_OFF_MS + DHCP_RECOVERY_SETTLE_MS + 5000)
                } else {
                    // Still within grace period or recovery in progress — retry
                    mainHandler.postDelayed({ checkNetwork() }, 10000)
                }
                return
            }
            // Has valid IP — reset DHCP tracking
            if (noIpSinceTimestamp != 0L) {
                Log.d(TAG, "Valid IP obtained — DHCP OK on '$currentSsid'")
                noIpSinceTimestamp = 0L
                dhcpRecoveryAttempts = 0
            }
            if (isLocked) {
                Log.d(TAG, "Back on allowed SSID '$currentSsid' — unlocking + restoring power button")
                unlockDevice(dpm = getDpm())
            } else if (alarmActive) {
                // Not locked but alarm still active — stop it
                Log.d(TAG, "On allowed SSID '$currentSsid' — stopping active alarm")
                stopAlarm(getDpm())
            }
            // Safety net: always send STOP intent to AlarmService when on allowed network
            try {
                val stopIntent = Intent(reactContext, AlarmService::class.java).apply {
                    action = AlarmService.ACTION_STOP
                }
                reactContext.startService(stopIntent)
            } catch (e: Exception) {}
        }
    }

    private fun lockDevice(dpm: DevicePolicyManager) {
        try {
            // 1. Start anti-theft alarm BEFORE lockNow() so audio initializes while screen is on
            startAlarm(dpm)

            // 2. Disable power button via setLockTaskFeatures (remove GLOBAL_ACTIONS)
            try {
                val currentFeatures = dpm.getLockTaskFeatures(getAdmin())
                val lockedFeatures = currentFeatures and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS.inv()
                dpm.setLockTaskFeatures(getAdmin(), lockedFeatures)
                Log.d(TAG, "Power button disabled (LOCK_TASK_FEATURE_GLOBAL_ACTIONS removed): $currentFeatures -> $lockedFeatures")
            } catch (e: Exception) {
                Log.w(TAG, "Could not disable power button via setLockTaskFeatures: ${e.message}")
            }

            // 3. Lock screen after a short delay — gives AlarmService time to start audio first.
            // lockNow() may re-enable master mute, but AlarmService clears it again.
            isLocked = true
            mainHandler.postDelayed({
                try {
                    dpm.lockNow()
                    Log.d(TAG, "Device locked via lockNow()")
                    // Re-clear master mute after lockNow() — it may re-mute on this device
                    try {
                        dpm.clearUserRestriction(getAdmin(), UserManager.DISALLOW_ADJUST_VOLUME)
                        dpm.setMasterVolumeMuted(getAdmin(), false)
                        val am = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                        am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                        val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                        Log.d(TAG, "[Alarm] Master mute re-cleared after lockNow()")
                    } catch (e: Exception) {
                        Log.w(TAG, "[Alarm] Could not re-clear master mute: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "lockNow() failed: ${e.message}")
                }
            }, 1500)
            Log.d(TAG, "Device lock scheduled in 1.5s (after alarm audio starts)")

            sendEvent("wifiLockTriggered", JSONObject().apply {
                put("reason", "wifi_disconnect")
                put("allowedSsids", JSONArray(allowedSsids))
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "lockDevice failed: ${e.message}", e)
        }
    }

    private fun unlockDevice(dpm: DevicePolicyManager?) {
        if (dpm == null) return
        try {
            // Restore original lock task features (re-enable power button)
            try {
                dpm.setLockTaskFeatures(getAdmin(), originalLockTaskFeatures)
                Log.d(TAG, "Power button restored (lock task features: $originalLockTaskFeatures)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore lock task features: ${e.message}")
            }
            isLocked = false

            // Stop anti-theft alarm
            stopAlarm(dpm)

            // Restore screen brightness — lockNow() may dim it to 0
            try {
                val resolver = reactContext.contentResolver
                android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 255)
                Log.d(TAG, "Screen brightness restored after unlock")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore brightness: ${e.message}")
            }

            sendEvent("wifiLockUnlocked", null)
        } catch (e: Exception) {
            Log.e(TAG, "unlockDevice failed: ${e.message}", e)
        }
    }

    private fun startAlarm(dpm: DevicePolicyManager) {
        if (alarmActive) return
        alarmActive = true
        try {
            // Clear DISALLOW_ADJUST_VOLUME — it blocks all volume changes including unmute
            try {
                dpm.clearUserRestriction(getAdmin(), UserManager.DISALLOW_ADJUST_VOLUME)
                Log.d(TAG, "[Alarm] Cleared DISALLOW_ADJUST_VOLUME")
            } catch (e: Exception) {
                Log.w(TAG, "[Alarm] Could not clear DISALLOW_ADJUST_VOLUME: ${e.message}")
            }
            // Max volume + unmute master BEFORE starting alarm
            val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.mode = AudioManager.MODE_NORMAL
            // Unmute master output — this device has master mute ON by default
            try {
                dpm.setMasterVolumeMuted(getAdmin(), false)
                Log.d(TAG, "[Alarm] Master volume unmuted via DPM")
            } catch (e: Exception) {
                Log.w(TAG, "[Alarm] DPM setMasterVolumeMuted failed: ${e.message}")
            }
            try {
                val method = AudioManager::class.java.getMethod("setMasterMute", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                method.invoke(audioManager, false, 0)
                Log.d(TAG, "[Alarm] Master mute cleared via reflection")
            } catch (e: Exception) {
                Log.w(TAG, "[Alarm] Reflection setMasterMute failed: ${e.message}")
            }
            val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM, AudioManager.STREAM_RING)
            for (stream in streams) {
                try { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) {}
            }
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
            Log.d(TAG, "[Alarm] Volume set to max: music=$maxMusic alarm=$maxAlarm ring=$maxRing")

            // NOTE: Do NOT add DISALLOW_ADJUST_VOLUME — on this Lenovo device it mutes audio output.
            // Lock task already prevents volume key changes via hardware keys.

            // Start alarm via Foreground Service — survives Activity lifecycle / lockNow()
            val intent = Intent(reactContext, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }

            Log.d(TAG, "[Alarm] Anti-theft alarm STARTED via service")
        } catch (e: Exception) {
            Log.e(TAG, "[Alarm] Failed to start alarm: ${e.message}")
        }
    }

    private fun stopAlarm(dpm: DevicePolicyManager?) {
        if (!alarmActive) return
        alarmActive = false
        try {
            // Stop alarm service
            val intent = Intent(reactContext, AlarmService::class.java).apply {
                action = AlarmService.ACTION_STOP
            }
            reactContext.startService(intent)

            // Restore volume
            if (savedVolume >= 0) {
                val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                savedVolume = -1
            }

            Log.d(TAG, "[Alarm] Anti-theft alarm STOPPED")
        } catch (e: Exception) {
            Log.e(TAG, "[Alarm] Error stopping alarm: ${e.message}")
        }
    }

    private fun sendEvent(eventName: String, params: String?) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        } catch (e: Exception) {
            Log.w(TAG, "sendEvent error: ${e.message}")
        }
    }
}
