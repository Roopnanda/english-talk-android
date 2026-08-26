package com.englishtalk.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.englishtalk.app.MainActivity
import com.englishtalk.app.billing.BillingManager
import com.englishtalk.app.utils.SoundHelper
import kotlinx.coroutines.*

class CallService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "english_talk_call_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START_CALL_SESSION"
        const val ACTION_STOP = "ACTION_STOP_CALL_SESSION"
        const val ACTION_EXTEND = "ACTION_EXTEND_TIME"

        var maxDurationSeconds: Long = 900L
        var currentDurationSeconds: Long = 0L

        var onWarningChime: (() -> Unit)? = null
        var onCallExpired: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                maxDurationSeconds = 900L
                currentDurationSeconds = 0L
                startForeground(NOTIFICATION_ID, buildNotification("Call in progress..."))
                startTimer()
            }
            ACTION_EXTEND -> {
                maxDurationSeconds += 300L
            }
            ACTION_STOP -> {
                stopTimerAndService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                currentDurationSeconds++

                val minutes = currentDurationSeconds / 60
                val seconds = currentDurationSeconds % 60
                val timeStr = String.format("%02d:%02d", minutes, seconds)
                updateNotification("Call in progress ($timeStr)")

                if (currentDurationSeconds == 840L && !BillingManager.isSubscribed.value) {
                    SoundHelper.playWarningChime(this@CallService)
                    onWarningChime?.invoke()
                }

                if (currentDurationSeconds >= maxDurationSeconds && !BillingManager.isSubscribed.value) {
                    onCallExpired?.invoke()
                    stopTimerAndService()
                    break
                }
            }
        }
    }

    private fun stopTimerAndService() {
        timerJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Voice Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing voice call"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("English Talk Call")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
