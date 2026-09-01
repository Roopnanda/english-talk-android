package com.englishtalk.app.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.englishtalk.app.MainActivity
import com.englishtalk.app.R
import com.englishtalk.app.WebRtcManager
import com.englishtalk.app.utils.AppLogger

class CallService : Service(), AudioManager.OnAudioFocusChangeListener {

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasMutedBeforeFocusLoss = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        AppLogger.log("AudioRoute", "Bluetooth headset connected. Starting SCO...")
                        startBluetoothScoRouting()
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        AppLogger.log("AudioRoute", "Bluetooth headset disconnected. Stopping SCO...")
                        stopBluetoothScoRouting()
                    }
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    AppLogger.log("AudioRoute", "SCO Audio State updated: $scoState")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        requestAudioFocus()
        startBluetoothScoRouting()

        return START_NOT_STICKY
    }

    private fun requestAudioFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                AppLogger.log("AudioRoute", "GSM call interruption (AudioFocus Loss Transient) -> Auto-muting WebRTC mic")
                wasMutedBeforeFocusLoss = WebRtcManager.isMuted
                WebRtcManager.setMuted(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                AppLogger.log("AudioRoute", "GSM call ended (AudioFocus GAIN) -> Restoring WebRTC mic")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!wasMutedBeforeFocusLoss) {
                    WebRtcManager.setMuted(false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLogger.log("AudioRoute", "Permanent audio focus loss")
            }
        }
    }

    private fun startBluetoothScoRouting() {
        try {
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } catch (e: Throwable) {
            AppLogger.log("AudioRoute", "SCO Routing Error: ${e.message}")
        }
    }

    private fun stopBluetoothScoRouting() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        } catch (e: Throwable) {
            AppLogger.log("AudioRoute", "Stop SCO Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothScoRouting()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Throwable) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }

        audioManager.mode = AudioManager.MODE_NORMAL
        AppLogger.log("CallService", "CallService destroyed & AudioFocus released")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Voice Call Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing active English Practice audio call"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notifyIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("English Talk Call Active")
            .setContentText("Speaking with your practice partner...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "english_talk_call_channel"
        const val NOTIFICATION_ID = 1001
    }
}
