package com.englishtalk.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.englishtalk.app.MainActivity
import com.englishtalk.app.R

class CallService : Service() {

    private val CHANNEL_ID = "EnglishTalkCallChannel"
    private val NOTIFICATION_ID = 1001
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireCpuWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START_CALL"
        val isSearching = action == "START_SEARCHING"

        val title = if (isSearching) "Searching for Partner..." else "Active Conversation"
        val message = if (isSearching) "English Talk is searching for a partner in background" else "Call in progress"

        val notification = buildNotification(title, message)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun acquireCpuWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishTalk:ServiceWakeLock").apply {
                acquire(30 * 60 * 1000L) // 30 min safety guard
            }
        } catch (e: Throwable) {}
    }

    private fun releaseCpuWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Throwable) {}
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "English Talk Call Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice connection and partner search active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseCpuWakeLock()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
