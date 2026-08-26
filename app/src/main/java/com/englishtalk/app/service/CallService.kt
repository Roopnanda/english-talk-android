package com.englishtalk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.englishtalk.app.MainActivity

class CallService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_EXTEND = "ACTION_EXTEND"
        private const val CHANNEL_ID = "english_talk_call_channel"
        private const val NOTIFICATION_ID = 1001

        private const val BASE_CALL_LIMIT_SECONDS = 15 * 60L // 15 Minutes
        private const val EXTENSION_SECONDS = 5 * 60L // +5 Minutes

        @Volatile
        private var elapsedSeconds = 0L

        @Volatile
        private var callLimitSeconds = BASE_CALL_LIMIT_SECONDS

        var onWarningChime: (() -> Unit)? = null
        var onCallExpired: (() -> Unit)? = null

        fun getElapsedSeconds(): Long = elapsedSeconds
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false
    private var hasFiredWarning = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++

            // 1 Minute Warning Trigger (at 14 minutes or limit - 60s)
            if (!hasFiredWarning && elapsedSeconds >= (callLimitSeconds - 60L)) {
                hasFiredWarning = true
                onWarningChime?.invoke()
            }

            // Call Expiration Trigger
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Call in progress..."))
                resetAndStartTimer()
            }
            ACTION_EXTEND -> {
                callLimitSeconds += EXTENSION_SECONDS
                hasFiredWarning = false
                updateNotification()
            }
            ACTION_STOP -> {
                stopTimer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("English Talk Call Active"))
            }
        }
        return START_NOT_STICKY
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
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeFormatted = String.format("%02d:%02d", minutes, seconds)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification("Call Duration: $timeFormatted"))
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }
}
