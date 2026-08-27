package com.englishtalk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
        const val ACTION_APP_BACKGROUNDED = "ACTION_APP_BACKGROUNDED"
        const val ACTION_APP_FOREGROUNDED = "ACTION_APP_FOREGROUNDED"

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
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishTalk:CallWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        when (intent?.action) {
            ACTION_START -> {
                isCallActive = true
                requestCallAudioFocus()
                acquireWakeLock()
                safeStartForeground("Call in progress...")
                resetAndStartTimer()
            }
            ACTION_EXTEND -> {
                callLimitSeconds += EXTENSION_SECONDS
                hasFiredWarning = false
                updateNotification()
            }
            ACTION_APP_BACKGROUNDED -> {
                // If screen is ON (user minimized app), start 30s timer
                if (isCallActive && powerManager.isInteractive) {
                    cancelAutoMuteTimer()
                    autoMuteRunnable = Runnable {
                        if (isCallActive) {
                            isAutoMuted = true
                            onAutoMuteTriggered?.invoke(true)
                            Log.d("CallService", "30-sec background auto-mute applied")
                        }
                    }
                    handler.postDelayed(autoMuteRunnable!!, 30_000L)
                }
            }
            ACTION_APP_FOREGROUNDED -> {
                cancelAutoMuteTimer()
                if (isAutoMuted) {
                    isAutoMuted = false
                    onAutoMuteTriggered?.invoke(false)
                    Log.d("CallService", "Foreground restored, auto-mute removed")
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

    private fun requestCallAudioFocus() {
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener {}
                    .build()

                audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            Log.e("CallService", "Audio focus request error: ${e.message}")
        }
    }

    private fun abandonCallAudioFocus() {
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("CallService", "Abandon audio focus error: ${e.message}")
        }
    }

    private fun cancelAutoMuteTimer() {
        autoMuteRunnable?.let { handler.removeCallbacks(it) }
        autoMuteRunnable = null
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(35 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("CallService", "WakeLock error: ${e.message}")
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
            Log.e("CallService", "Foreground start failed: ${e.message}")
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
        abandonCallAudioFocus()

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("CallService", "Stop error: ${e.message}")
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        terminateService()
    }
}
