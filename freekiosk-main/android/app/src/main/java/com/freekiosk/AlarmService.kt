package com.freekiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "freekiosk_alarm"
        private const val NOTIFICATION_ID = 2003
        const val ACTION_START = "com.freekiosk.START_ALARM"
        const val ACTION_STOP = "com.freekiosk.STOP_ALARM"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: android.media.ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var alarmPlaying = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground first to prevent ForegroundServiceDidNotStartInTimeException
        // when a STOP intent arrives before START was processed
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Anti-Theft Alarm")
            .setContentText("Device not on allowed network!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START -> startAlarm()
            ACTION_STOP -> {
                stopAlarm()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAlarm() {

        // Max volume + unmute master
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL

        // Unmute master output — this device has master mute ON by default
        // Use DevicePolicyManager.setMasterVolumeMuted (Device Owner API) + ADJUST_UNMUTE
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, com.freekiosk.DeviceAdminReceiver::class.java)
            dpm.setMasterVolumeMuted(admin, false)
            Log.d(TAG, "Master volume unmuted via DPM")
        } catch (e: Exception) {
            Log.w(TAG, "DPM setMasterVolumeMuted failed: ${e.message}")
        }
        // Also try reflection as fallback
        try {
            val method = AudioManager::class.java.getMethod("setMasterMute", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            method.invoke(audioManager, false, 0)
            Log.d(TAG, "Master mute cleared via reflection")
        } catch (e: Exception) {
            Log.w(TAG, "Reflection setMasterMute failed: ${e.message}")
        }
        // Unmute all streams
        val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM, AudioManager.STREAM_RING)
        for (stream in streams) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {}
        }
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
        val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
        Log.d(TAG, "Volume maxed: music=$maxMusic alarm=$maxAlarm ring=$maxRing")

        // Vibration
        try {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 1000, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration not available: ${e.message}")
        }

        // Alarm sound — custom bundled alarm file from res/raw/alarm.ogg
        try {
            val alarmUri = android.net.Uri.parse("android.resource://${packageName}/${com.freekiosk.R.raw.alarm}")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                isLooping = true
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setAudioAttributes(audioAttributes)
                setOnPreparedListener { mp ->
                    mp.start()
                    Log.d(TAG, "MediaPlayer alarm started (custom sound)")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    startToneGeneratorFallback()
                    true
                }
                prepareAsync()
            }
            Log.d(TAG, "MediaPlayer alarm initializing (custom sound)")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed: ${e.message}, falling back to ToneGenerator")
            startToneGeneratorFallback()
        }

        Log.d(TAG, "Alarm service started")
    }

    private fun startToneGeneratorFallback() {
        try {
            toneGenerator = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            alarmPlaying = true
            handler.post(object : Runnable {
                override fun run() {
                    if (!alarmPlaying) return
                    toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 1000)
                    handler.postDelayed(this, 1500)
                }
            })
            Log.d(TAG, "ToneGenerator fallback started")
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator fallback also failed: ${e.message}")
        }
    }

    private fun stopAlarm() {
        alarmPlaying = false
        handler.removeCallbacksAndMessages(null)
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {}
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            vibrator?.cancel()
            vibrator = null
            Log.d(TAG, "Alarm stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm: ${e.message}")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Anti-Theft Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm when device leaves allowed WiFi"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
