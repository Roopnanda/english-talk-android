package com.englishtalk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.englishtalk.app.MainActivity

class CallService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_EXTEND = "ACTION_EXTEND"
        const val ACTION_APP_PAUSED = "ACTION_APP_PAUSED"
        const val ACTION_APP_RESUMED = "ACTION_APP_RESUMED"

        private const val CHANNEL_ID = "english_talk_call_channel"
        private const val NOTIFICATION_ID = 1001

        private const val BASE_CALL_LIMIT_SECONDS = 15 * 60L
        private const val EXTENSION_SECONDS = 5 * 60L

        @Volatile
        var isCallActive = false
            private set

        @Volatile
        private var elapsedSeconds = 0L

        @Volatile
        private var callLimitSeconds = BASE_CALL_LIMIT_SECONDS

        var onWarningChime: (() -> Unit)? = null
        var onCallExpired: (() -> Unit)? = null
        var onAutoMuteTriggered: ((Boolean) -> Unit)? = null

        fun getElapsedSeconds(): Long = elapsedSeconds
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false
    private var hasFiredWarning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoMuteRunnable: Runnable? = null
    private var isAutoMuted = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++

            if (!hasFiredWarning && elapsedSeconds >= (callLimitSeconds - 60L)) {
                hasFiredWarning = true
                onWarningChime?.invoke()
            }

            if (elapsedSeconds >= callLimitSeconds) {
                onCallExpired?.invoke()
                stopSelf()
                return
            }

            updateNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishTalk:AudioCallWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        when (intent?.action) {
            ACTION_START -> {
                isCallActive = true
                acquireWakeLock()
                safeStartForeground("Call in progress...")
                resetAndStartTimer()
            }
            ACTION_EXTEND -> {
                callLimitSeconds += EXTENSION_SECONDS
                hasFiredWarning = false
                updateNotification()
            }
            ACTION_APP_PAUSED -> {
                // Only start 30s timer when minimized with screen ON
                if (isCallActive && powerManager.isInteractive) {
                    cancelAutoMuteTimer()
                    autoMuteRunnable = Runnable {
                        if (isCallActive) {
                            isAutoMuted = true
                            onAutoMuteTriggered?.invoke(true)
                            Log.d("CallService", "30-second background auto-mute applied")
                        }
                    }
                    handler.postDelayed(autoMuteRunnable!!, 30_000L)
                }
            }
            ACTION_APP_RESUMED -> {
                cancelAutoMuteTimer()
                if (isAutoMuted) {
                    isAutoMuted = false
                    onAutoMuteTriggered?.invoke(false)
                    Log.d("CallService", "App foregrounded, auto-mute removed")
                }
            }
            ACTION_STOP -> {
                terminateService()
            }
            else -> {
                safeStartForeground("English Talk Call Active")
            }
        }
        return START_NOT_STICKY
    }

    private fun cancelAutoMuteTimer() {
        autoMuteRunnable?.let { handler.removeCallbacks(it) }
        autoMuteRunnable = null
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("CallService", "WakeLock acquire error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("CallService", "WakeLock release error: ${e.message}")
        }
    }

    private fun safeStartForeground(text: String) {
        try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("CallService", "Foreground service start failed: ${e.message}")
        }
    }

    private fun resetAndStartTimer() {
        elapsedSeconds = 0L
        callLimitSeconds = BASE_CALL_LIMIT_SECONDS
        hasFiredWarning = false
        if (!isTimerRunning) {
            isTimerRunning = true
            handler.post(timerRunnable)
        }
    }

    private fun stopTimer() {
        isTimerRunning = false
        handler.removeCallbacks(timerRunnable)
        elapsedSeconds = 0L
    }

    private fun updateNotification() {
        try {
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            val timeFormatted = String.format("%02d:%02d", minutes, seconds)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Call Duration: $timeFormatted"))
        } catch (e: Exception) {
            Log.e("CallService", "Notification update error: ${e.message}")
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("English Talk Active Call")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Voice Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live call status and duration"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun terminateService() {
        isCallActive = false
        cancelAutoMuteTimer()
        stopTimer()
        releaseWakeLock()

        try {
            // Dismiss notification from status bar immediately
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("CallService", "Stop notification error: ${e.message}")
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        terminateService()
    }
}
