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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.englishtalk.app.MainActivity

class CallService : Service() {

    companion object {
        private const val CHANNEL_ID = "CallServiceChannel"
        private const val NOTIFICATION_ID = 1001

        private var callStartTimeMs: Long = 0L
        private var totalDurationSeconds: Long = 900L // 15 minutes default limit

        fun getElapsedSeconds(): Long {
            if (callStartTimeMs == 0L) return 0L
            return (System.currentTimeMillis() - callStartTimeMs) / 1000L
        }

        fun getTotalDurationSeconds(): Long {
            return totalDurationSeconds
        }

        fun extendTime(extraSeconds: Long) {
            totalDurationSeconds += extraSeconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        callStartTimeMs = System.currentTimeMillis()
        totalDurationSeconds = 900L
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("English Talk Call Active")
            .setContentText("Live voice conversation in progress...")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        callStartTimeMs = 0L
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ongoing Call Notification Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
