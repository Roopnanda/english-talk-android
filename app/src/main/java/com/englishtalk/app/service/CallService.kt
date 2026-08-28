package com.englishtalk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.englishtalk.app.MainActivity
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.utils.AppLogger
import com.englishtalk.app.webrtc.WebRtcAudioClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class CallService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START_CALL = "ACTION_START_CALL"
        const val ACTION_END_CALL = "ACTION_END_CALL"
        const val ACTION_EXTEND = "ACTION_EXTEND"
        const val ACTION_APP_BACKGROUNDED = "ACTION_APP_BACKGROUNDED"
        const val ACTION_APP_FOREGROUNDED = "ACTION_APP_FOREGROUNDED"
        const val ACTION_TOGGLE_MUTE = "ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "ACTION_TOGGLE_SPEAKER"

        const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        const val EXTRA_IS_INITIATOR = "EXTRA_IS_INITIATOR"
        const val EXTRA_PEER_LEVEL = "EXTRA_PEER_LEVEL"

        private const val CHANNEL_ID = "english_talk_call_channel"
        private const val NOTIFICATION_ID = 1001

        private const val BASE_CALL_LIMIT_SECONDS = 15 * 60L
        private const val EXTENSION_SECONDS = 5 * 60L

        @Volatile
        var isCallActive = false
            private set

        @Volatile
        var isMuted = false
            private set

        @Volatile
        var isSpeakerOn = false
            private set

        @Volatile
        private var elapsedSeconds = 0L

        @Volatile
        private var callLimitSeconds = BASE_CALL_LIMIT_SECONDS

        var onWarningChime: (() -> Unit)? = null
        var onCallExpired: (() -> Unit)? = null
        var onCallEndedByRemote: (() -> Unit)? = null
        var onAudioStateChanged: ((muted: Boolean, speaker: Boolean) -> Unit)? = null

        private var activeServiceInstance: CallService? = null

        fun getElapsedSeconds(): Long = elapsedSeconds

        fun handleRemoteOffer(sdp: SessionDescription) {
            activeServiceInstance?.webRtcClient?.onRemoteOfferReceived(sdp) { answer ->
                SignalingClient.sendAnswer(answer)
            }
        }

        fun handleRemoteAnswer(sdp: SessionDescription) {
            activeServiceInstance?.webRtcClient?.onRemoteAnswerReceived(sdp)
        }

        fun handleRemoteIceCandidate(candidate: IceCandidate) {
            activeServiceInstance?.webRtcClient?.addRemoteIceCandidate(candidate)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false
    private var hasFiredWarning = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null

    private var autoMuteRunnable: Runnable? = null
    private var isAutoMuted = false
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNearEar = false

    private var webRtcClient: WebRtcAudioClient? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    cancelAutoMuteTimer()
                }
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++

            if (!hasFiredWarning && elapsedSeconds >= (callLimitSeconds - 60L)) {
                hasFiredWarning = true
                onWarningChime?.invoke()
            }

            if (elapsedSeconds >= callLimitSeconds) {
                onCallExpired?.invoke()
                terminateService()
                return
            }

            updateNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()
        safeStartForeground("Initializing live call...")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishTalk:CallWakeLock").apply {
            setReferenceCounted(false)
        }

        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "EnglishTalk:ProximityWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        safeStartForeground("Voice call connected")

        when (intent?.action) {
            ACTION_START_CALL -> {
                val isInitiator = intent.getBooleanExtra(EXTRA_IS_INITIATOR, false)
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
                SignalingClient.currentRoomId = roomId

                isCallActive = true
                isMuted = false
                isSpeakerOn = false
                isAutoMuted = false

                requestCallAudioFocus()
                enforceAudioHardwareRouting(speaker = false)
                acquireWakeLocks()
                resetAndStartTimer()
                setupCallConnection(isInitiator)
                onAudioStateChanged?.invoke(isMuted, isSpeakerOn)
            }
            ACTION_TOGGLE_MUTE -> {
                isMuted = !isMuted
                webRtcClient?.setMicrophoneEnabled(!isMuted)
                onAudioStateChanged?.invoke(isMuted, isSpeakerOn)
                AppLogger.log("CallService", "Mute toggled: $isMuted")
            }
            ACTION_TOGGLE_SPEAKER -> {
                isSpeakerOn = !isSpeakerOn
                enforceAudioHardwareRouting(isSpeakerOn)

                if (isSpeakerOn) {
                    releaseProximityLock()
                } else {
                    acquireProximityLock()
                }

                onAudioStateChanged?.invoke(isMuted, isSpeakerOn)
                AppLogger.log("CallService", "Speaker toggled: $isSpeakerOn")
            }
            ACTION_EXTEND -> {
                callLimitSeconds += EXTENSION_SECONDS
                hasFiredWarning = false
                updateNotification()
            }
            ACTION_APP_BACKGROUNDED -> {
                if (isCallActive && !isNearEar) {
                    cancelAutoMuteTimer()
                    AppLogger.log("CallService", "Background detected -> 30s auto-mute timer running")
                    autoMuteRunnable = Runnable {
                        if (isCallActive) {
                            isAutoMuted = true
                            AppLogger.log("CallService", "30s passed in background -> Mic auto-muted")
                            webRtcClient?.setMicrophoneEnabled(false)
                        }
                    }
                    handler.postDelayed(autoMuteRunnable!!, 30_000L)
                }
            }
            ACTION_APP_FOREGROUNDED -> {
                cancelAutoMuteTimer()
                if (isAutoMuted) {
                    isAutoMuted = false
                    AppLogger.log("CallService", "App returned to screen -> Restoring mic state")
                    webRtcClient?.setMicrophoneEnabled(!isMuted)
                }
            }
            ACTION_END_CALL -> {
                terminateService()
            }
        }
        return START_NOT_STICKY
    }

    private fun enforceAudioHardwareRouting(speaker: Boolean) {
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = speaker
        } catch (e: Throwable) {
            AppLogger.log("Audio-ERR", "Hardware routing fail: ${e.message}")
        }
    }

    private fun setupCallConnection(isInitiator: Boolean) {
        try {
            webRtcClient = WebRtcAudioClient(
                context = applicationContext,
                onIceCandidateGenerated = { candidate ->
                    SignalingClient.sendIceCandidate(candidate)
                },
                onRemoteStreamActive = {
                    AppLogger.log("CallService", "Remote audio stream is active")
                }
            )

            webRtcClient?.initPeerConnection(isInitiator) { offer ->
                SignalingClient.sendOffer(offer)
            }
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "WebRTC init failed: ${e.message}")
        }
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
        } catch (e: Throwable) {
            AppLogger.log("Audio-ERR", "Focus fail: ${e.message}")
        }
    }

    private fun abandonCallAudioFocus() {
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Throwable) {
            AppLogger.log("Audio-ERR", "Abandon focus error: ${e.message}")
        }
    }

    private fun cancelAutoMuteTimer() {
        autoMuteRunnable?.let { handler.removeCallbacks(it) }
        autoMuteRunnable = null
    }

    private fun acquireWakeLocks() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(35 * 60 * 1000L)
            }
            if (!isSpeakerOn) {
                acquireProximityLock()
            }
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "WakeLock acquire: ${e.message}")
        }
    }

    private fun acquireProximityLock() {
        try {
            if (proximityWakeLock != null && proximityWakeLock?.isHeld == false) {
                proximityWakeLock?.acquire()
            } else if (proximitySensor != null) {
                sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "ProximityLock acquire: ${e.message}")
        }
    }

    private fun releaseProximityLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
            sensorManager?.unregisterListener(this)
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "ProximityLock release: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            releaseProximityLock()
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "Release WakeLock: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            isNearEar = distance < maxRange
            if (isNearEar) {
                cancelAutoMuteTimer()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun safeStartForeground(text: String) {
        try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "Foreground fail: ${e.message}")
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
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "Notif update error: ${e.message}")
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
        isMuted = false
        isSpeakerOn = false
        cancelAutoMuteTimer()
        stopTimer()
        releaseWakeLocks()
        abandonCallAudioFocus()

        try {
            SignalingClient.endCall()
            webRtcClient?.disconnect()
            webRtcClient = null
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "Cleanup error: ${e.message}")
        }

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "Stop error: ${e.message}")
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        activeServiceInstance = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Throwable) {
            AppLogger.log("CallService-ERR", "Unregister receiver: ${e.message}")
        }
        terminateService()
    }
}
