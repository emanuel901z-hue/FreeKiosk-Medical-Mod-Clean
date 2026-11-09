package com.freekiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.facebook.react.bridge.*

/**
 * React Native bridge module for AccessibilityService status and management.
 * Exposes methods to check if the service is enabled and to open settings.
 */
class AccessibilityModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AccessibilityModule"
        const val NAME = "AccessibilityModule"
    }

    override fun getName(): String = NAME

    /**
     * Check if FreeKiosk's AccessibilityService is currently enabled in system settings.
     */
    @ReactMethod
    fun isAccessibilityServiceEnabled(promise: Promise) {
        try {
            val enabled = isServiceEnabled()
            promise.resolve(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility service: ${e.message}")
            promise.reject("ERROR", "Failed to check accessibility service: ${e.message}")
        }
    }

    /**
     * Check if the AccessibilityService is actually running (connected).
     */
    @ReactMethod
    fun isAccessibilityServiceRunning(promise: Promise) {
        try {
            promise.resolve(FreeKioskAccessibilityService.isRunning())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    /**
     * Open Android's Accessibility Settings page so the user can enable the service.
     */
    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            reactContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
            promise.reject("ERROR", "Failed to open accessibility settings: ${e.message}")
        }
    }

    /**
     * In Device Owner mode, programmatically enable the AccessibilityService.
     * This avoids requiring manual user intervention.
     * Also permits accessibility services for managed app packages.
     */
    @ReactMethod
    fun enableViaDeviceOwner(promise: Promise) {
        try {
            val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(reactContext, DeviceAdminReceiver::class.java)
            
            if (!dpm.isDeviceOwnerApp(reactContext.packageName)) {
                promise.reject("NOT_DEVICE_OWNER", "This feature requires Device Owner mode")
                return
            }

            // Build permitted list: FreeKiosk + managed apps with allowAccessibility=true
            val permitted = mutableListOf(reactContext.packageName)
            permitted.addAll(getManagedAppsWithAccessibility())
            
            // Allow FreeKiosk's accessibility service + managed apps
            dpm.setPermittedAccessibilityServices(adminComponent, permitted.distinct())
            Log.d(TAG, "Permitted accessibility services set: $permitted")
            
            // Enable FreeKiosk's own accessibility service via secure settings
            val serviceComponent = ComponentName(reactContext, FreeKioskAccessibilityService::class.java)
            val serviceName = "${reactContext.packageName}/${serviceComponent.className}"
            val currentServices = Settings.Secure.getString(
                reactContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            if (!currentServices.contains(serviceName)) {
                val newServices = if (currentServices.isEmpty()) serviceName 
                                  else "$currentServices:$serviceName"
                try {
                    Settings.Secure.putString(
                        reactContext.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newServices
                    )
                    Settings.Secure.putString(
                        reactContext.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        "1"
                    )
                    Log.d(TAG, "Accessibility service enabled via Device Owner: $serviceName")
                } catch (se: SecurityException) {
                    Log.w(TAG, "WRITE_SECURE_SETTINGS not granted, cannot auto-enable: ${se.message}")
                    promise.reject("WRITE_SECURE_SETTINGS_REQUIRED",
                        "The WRITE_SECURE_SETTINGS permission is required to auto-enable the accessibility service.\n\n" +
                        "Grant it via ADB:\nadb shell pm grant ${reactContext.packageName} android.permission.WRITE_SECURE_SETTINGS\n\n" +
                        "This only needs to be done once. Alternatively, enable the service manually in Android Accessibility Settings.")
                    return
                }
            }
            
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable via Device Owner: ${e.message}")
            promise.reject("ERROR", "Failed to enable via Device Owner: ${e.message}")
        }
    }

    /**
     * Update the permitted accessibility services list (Device Owner only).
     * Called when managed apps configuration changes.
     * @param packageNames JSON array of package names to permit (in addition to FreeKiosk)
     */
    @ReactMethod
    fun setPermittedAccessibilityPackages(packageNames: com.facebook.react.bridge.ReadableArray, promise: Promise) {
        try {
            val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(reactContext, DeviceAdminReceiver::class.java)
            
            if (!dpm.isDeviceOwnerApp(reactContext.packageName)) {
                promise.reject("NOT_DEVICE_OWNER", "This feature requires Device Owner mode")
                return
            }

            val permitted = mutableListOf(reactContext.packageName)
            for (i in 0 until packageNames.size()) {
                val pkg = packageNames.getString(i)
                if (!pkg.isNullOrEmpty()) {
                    permitted.add(pkg)
                }
            }
            
            dpm.setPermittedAccessibilityServices(adminComponent, permitted.distinct())
            Log.d(TAG, "Updated permitted accessibility services: $permitted")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set permitted accessibility packages: ${e.message}")
            promise.reject("ERROR", "Failed to set permitted accessibility packages: ${e.message}")
        }
    }

    /**
     * Read managed apps from AsyncStorage and return those with allowAccessibility=true.
     */
    private fun getManagedAppsWithAccessibility(): List<String> {
        return try {
            val dbPath = reactContext.getDatabasePath("RKStorage").absolutePath
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT value FROM catalystLocalStorage WHERE key = ?",
                arrayOf("@kiosk_managed_apps")
            )
            val result = if (cursor.moveToFirst()) {
                val json = cursor.getString(0) ?: "[]"
                val apps = org.json.JSONArray(json)
                val packages = mutableListOf<String>()
                for (i in 0 until apps.length()) {
                    val app = apps.getJSONObject(i)
                    if (app.optBoolean("allowAccessibility", false)) {
                        packages.add(app.getString("packageName"))
                    }
                }
                packages
            } else {
                emptyList()
            }
            cursor.close()
            db.close()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Could not read managed apps: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if the accessibility service is listed in the enabled services setting.
     */
    private fun isServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(reactContext, FreeKioskAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            reactContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }
}
