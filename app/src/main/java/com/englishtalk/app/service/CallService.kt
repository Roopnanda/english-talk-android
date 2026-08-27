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
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.webrtc.WebRtcAudioClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class CallService : Service(), SignalingClient.SignalingListener {

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
        var onCallTerminatedByPeer: (() -> Unit)? = null
        var onAudioStateChanged: ((muted: Boolean, speaker: Boolean) -> Unit)? = null

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
    private var isScreenOff = false

    private var webRtcClient: WebRtcAudioClient? = null
    private var signalingClient: SignalingClient? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    cancelAutoMuteTimer()
                    if (isAutoMuted) {
                        isAutoMuted = false
                        webRtcClient?.setMicrophoneEnabled(!isMuted)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOff = false
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
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishTalk:CallWakeLock").apply {
            setReferenceCounted(false)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        when (intent?.action) {
            ACTION_START_CALL -> {
                val isInitiator = intent.getBooleanExtra(EXTRA_IS_INITIATOR, false)
                isCallActive = true
                isMuted = false
                isSpeakerOn = false

                requestCallAudioFocus()
                acquireWakeLock()
                safeStartForeground("Call in progress...")
                resetAndStartTimer()
                startWebRtcCall(isInitiator)
            }
            ACTION_TOGGLE_MUTE -> {
                isMuted = !isMuted
                webRtcClient?.setMicrophoneEnabled(!isMuted)
                onAudioStateChanged?.invoke(isMuted, isSpeakerOn)
            }
            ACTION_TOGGLE_SPEAKER -> {
                isSpeakerOn = !isSpeakerOn
                audioManager?.isSpeakerphoneOn = isSpeakerOn
                onAudioStateChanged?.invoke(isMuted, isSpeakerOn)
            }
            ACTION_EXTEND -> {
                callLimitSeconds += EXTENSION_SECONDS
                hasFiredWarning = false
                updateNotification()
            }
            ACTION_APP_BACKGROUNDED -> {
                if (isCallActive && !isScreenOff && powerManager.isInteractive) {
                    cancelAutoMuteTimer()
                    autoMuteRunnable = Runnable {
                        if (isCallActive && !isScreenOff) {
                            isAutoMuted = true
                            webRtcClient?.setMicrophoneEnabled(false)
                            Log.d("CallService", "30s background mic mute applied")
                        }
                    }
                    handler.postDelayed(autoMuteRunnable!!, 30_000L)
                }
            }
            ACTION_APP_FOREGROUNDED -> {
                cancelAutoMuteTimer()
                if (isAutoMuted) {
                    isAutoMuted = false
                    webRtcClient?.setMicrophoneEnabled(!isMuted)
                }
            }
            ACTION_END_CALL -> {
                terminateService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWebRtcCall(isInitiator: Boolean) {
        val serverUrl = "wss://english-talk-server-5pm7.onrender.com"
        signalingClient = SignalingClient(serverUrl, this).apply {
            connect()
        }

        webRtcClient = WebRtcAudioClient(
            context = applicationContext,
            onIceCandidateGenerated = { candidate ->
                signalingClient?.sendIceCandidate(candidate)
            },
            onRemoteStreamActive = {}
        )

        webRtcClient?.initPeerConnection(isInitiator) { offer ->
            signalingClient?.sendOffer(offer)
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        webRtcClient?.onRemoteOfferReceived(sdp) { answer ->
            signalingClient?.sendAnswer(answer)
        }
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        webRtcClient?.onRemoteAnswerReceived(sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        webRtcClient?.addRemoteIceCandidate(candidate)
    }

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String) {}

    override fun onCallEnded() {
        handler.post {
            onCallTerminatedByPeer?.invoke()
            terminateService()
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
        } catch (e: Exception) {
            Log.e("CallService", "Audio focus request error: ${e.message}")
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
            signalingClient?.endCall()
            webRtcClient?.disconnect()
            webRtcClient = null
            signalingClient = null
        } catch (e: Exception) {
            Log.e("CallService", "Cleanup error: ${e.message}")
        }

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
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e("CallService", "Unregister receiver error: ${e.message}")
        }
        terminateService()
    }
}
