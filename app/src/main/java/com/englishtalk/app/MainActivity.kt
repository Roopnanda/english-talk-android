package com.englishtalk.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.utils.AppLogger
import com.englishtalk.app.webrtc.WebRtcAudioClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class MainActivity : Activity(), SignalingClient.SignalingListener, SensorEventListener {

    private lateinit var layoutDashboard: LinearLayout
    private lateinit var layoutSearching: LinearLayout
    private lateinit var layoutCall: LinearLayout
    private lateinit var btnBeginner: Button
    private lateinit var btnAdvanced: Button
    private lateinit var btnCancelSearch: Button
    private lateinit var btnReconnectLast: Button
    private lateinit var tvLockProgressPopup: TextView
    private lateinit var switchFemaleFilter: Switch
    private lateinit var btnVip: Button
    private lateinit var tvSearchingStatus: TextView
    private lateinit var tvCallPartnerName: TextView
    private lateinit var tvCallTimer: TextView
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnEndCall: Button
    private lateinit var tvConsoleLogs: TextView
    private lateinit var layoutBannerAd: FrameLayout

    private lateinit var prefs: SharedPreferences
    private var activeCallLevel = "Beginner"
    private var isMuted = false
    private var isSpeakerOn = false
    private var isVip = false

    // Single-use reconnect properties
    private var lastPeerId: String? = null
    private var canReconnect = false

    // Progress unlock keys
    private val PREF_QUALIFIED_CALLS = "pref_qualified_calls"
    private val PREF_ADVANCED_UNLOCKED = "pref_advanced_unlocked"

    // Sensors & Audio
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager

    private val mainUiHandler = Handler(Looper.getMainLooper())

    // Background Auto-Mute Handler
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var wasMutedBeforeBackground = false
    private val autoMuteRunnable = Runnable {
        if (!isMuted) {
            isMuted = true
            WebRtcAudioClient.setMicrophoneMute(true)
            btnMute.text = "🔇"
            AppLogger.log("AutoMute", "Hard-muted microphone after 30s in background")
        }
    }

    // Lock Progress Pop-up Vanish Handler
    private val popupHandler = Handler(Looper.getMainLooper())
    private val hidePopupRunnable = Runnable {
        tvLockProgressPopup.visibility = View.GONE
    }

    // Call Timer & Check Tracking
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsedSec = CallService.getElapsedSeconds()
            val totalSec = CallService.getTotalDurationSeconds()
            val remainingSec = totalSec - elapsedSec

            if (remainingSec <= 0) {
                tvCallTimer.text = "00:00"
                endCallSession()
                return
            }

            val mins = remainingSec / 60
            val secs = remainingSec % 60
            tvCallTimer.text = String.format("%02d:%02d", mins, secs)

            if (remainingSec == 60L) {
                showExtendDialog()
            }

            timerHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install Crash Trapper to prevent silent closures and show dialog
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            mainUiHandler.post {
                try {
                    AlertDialog.Builder(this)
                        .setTitle("Application Diagnostic")
                        .setMessage("Error encountered:\n${throwable.localizedMessage}\n\nCause: ${throwable.cause}")
                        .setPositiveButton("Dismiss", null)
                        .show()
                } catch (e: Exception) {
                    // Fallback
                }
            }
        }

        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initSensors()
        initViews()
        setupListeners()

        SignalingClient.setListener(this)
        SignalingClient.connect()

        checkPermissions()
    }

    private fun initSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "EnglishTalk:ProximityLock"
                )
            }
        } catch (e: Exception) {
            AppLogger.log("Sensors", "Sensor init note: ${e.message}")
        }
    }

    private fun initViews() {
        layoutDashboard = findViewById(R.id.layoutDashboard)
        layoutSearching = findViewById(R.id.layoutSearching)
        layoutCall = findViewById(R.id.layoutCall)
        btnBeginner = findViewById(R.id.btnBeginner)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)
        btnReconnectLast = findViewById(R.id.btnReconnectLast)
        tvLockProgressPopup = findViewById(R.id.tvLockProgressPopup)
        switchFemaleFilter = findViewById(R.id.switchFemaleFilter)
        btnVip = findViewById(R.id.btnVip)
        tvSearchingStatus = findViewById(R.id.tvSearchingStatus)
        tvCallPartnerName = findViewById(R.id.tvCallPartnerName)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnEndCall = findViewById(R.id.btnEndCall)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)
        layoutBannerAd = findViewById(R.id.layoutBannerAd)

        updateLevelDashboardUI()
    }

    private fun setupListeners() {
        // Direct 1-Tap Calling for Beginner
        btnBeginner.setOnClickListener {
            if (hasAudioPermission()) {
                activeCallLevel = "Beginner"
                startMatchingSearch("Beginner")
            } else {
                checkPermissions()
            }
        }

        // Direct 1-Tap Calling for Advanced (or Unlock Pop-up if Locked)
        btnAdvanced.setOnClickListener {
            val isUnlocked = prefs.getBoolean(PREF_ADVANCED_UNLOCKED, false)
            if (isUnlocked) {
                if (hasAudioPermission()) {
                    activeCallLevel = "Advanced"
                    startMatchingSearch("Advanced")
                } else {
                    checkPermissions()
                }
            } else {
                val count = prefs.getInt(PREF_QUALIFIED_CALLS, 0)
                showLockProgressPopup(count)
            }
        }

        btnCancelSearch.setOnClickListener {
            SignalingClient.leaveQueue()
            SignalingClient.cancelReconnect()
            showDashboardView()
        }

        btnReconnectLast.setOnClickListener {
            val targetPeer = lastPeerId
            if (targetPeer != null && canReconnect) {
                layoutDashboard.visibility = View.GONE
                layoutSearching.visibility = View.VISIBLE
                tvSearchingStatus.text = "Reconnecting to the last caller..."
                SignalingClient.requestReconnect(targetPeer, activeCallLevel)
            }
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            WebRtcAudioClient.setMicrophoneMute(isMuted)
            btnMute.text = if (isMuted) "🔇" else "🎤"
        }

        btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            audioManager.isSpeakerphoneOn = isSpeakerOn
            btnSpeaker.text = if (isSpeakerOn) "🔊" else "🔈"
        }

        btnEndCall.setOnClickListener {
            endCallSession()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun showLockProgressPopup(currentCount: Int) {
        popupHandler.removeCallbacks(hidePopupRunnable)
        tvLockProgressPopup.text = "Complete 20 calls (4+ mins each in Beginner) to unlock Advanced.\nProgress: $currentCount / 20"
        tvLockProgressPopup.visibility = View.VISIBLE
        popupHandler.postDelayed(hidePopupRunnable, 3000L)
    }

    private fun updateLevelDashboardUI() {
        val isAdvancedUnlocked = prefs.getBoolean(PREF_ADVANCED_UNLOCKED, false)

        if (isAdvancedUnlocked) {
            btnAdvanced.text = "ADVANCED (TAP TO CALL)"
            btnAdvanced.setBackgroundColor(Color.parseColor("#2563EB"))
            btnAdvanced.setTextColor(Color.WHITE)
        } else {
            btnAdvanced.text = "🔒 ADVANCED"
            btnAdvanced.setBackgroundColor(Color.parseColor("#1E293B"))
            btnAdvanced.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun startMatchingSearch(level: String) {
        layoutDashboard.visibility = View.GONE
        layoutSearching.visibility = View.VISIBLE
        tvSearchingStatus.text = "Searching for a $level conversation partner..."

        SignalingClient.joinQueue(
            level = level,
            userGender = "Unknown",
            talkToFemaleOnly = switchFemaleFilter.isChecked,
            isVip = isVip
        )
    }

    private fun startCallView() {
        mainUiHandler.post {
            try {
                layoutSearching.visibility = View.GONE
                layoutDashboard.visibility = View.GONE
                layoutCall.visibility = View.VISIBLE

                tvCallPartnerName.text = "Connected"
                tvCallTimer.text = "15:00"

                isMuted = false
                isSpeakerOn = false
                audioManager.isSpeakerphoneOn = false
                btnMute.text = "🎤"
                btnSpeaker.text = "🔈"

                try {
                    val serviceIntent = Intent(this, CallService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    AppLogger.log("Service", "FGS Start note: ${e.message}")
                }

                timerHandler.post(timerRunnable)

                try {
                    proximityWakeLock?.let {
                        if (!it.isHeld) it.acquire(10 * 60 * 1000L /* 10 mins */)
                    }
                } catch (e: Exception) {
                    AppLogger.log("WakeLock", "Acquire note: ${e.message}")
                }
            } catch (e: Exception) {
                AppLogger.log("CallView-ERR", "Error entering call view: ${e.message}")
            }
        }
    }

    private fun endCallSession() {
        mainUiHandler.post {
            try {
                timerHandler.removeCallbacks(timerRunnable)

                val elapsed = CallService.getElapsedSeconds()
                val isAlreadyUnlocked = prefs.getBoolean(PREF_ADVANCED_UNLOCKED, false)

                if (!isAlreadyUnlocked && activeCallLevel == "Beginner" && elapsed >= 240L) {
                    val current = prefs.getInt(PREF_QUALIFIED_CALLS, 0) + 1
                    prefs.edit().putInt(PREF_QUALIFIED_CALLS, current).apply()
                    if (current >= 20) {
                        prefs.edit().putBoolean(PREF_ADVANCED_UNLOCKED, true).apply()
                    }
                }

                SignalingClient.endCall()
                WebRtcAudioClient.close()

                try {
                    stopService(Intent(this, CallService::class.java))
                } catch (e: Exception) {
                    // Ignore
                }

                try {
                    proximityWakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                } catch (e: Exception) {
                    // Ignore
                }

                showDashboardView()
            } catch (e: Exception) {
                AppLogger.log("EndCall-ERR", "Error ending call: ${e.message}")
            }
        }
    }

    private fun showDashboardView() {
        mainUiHandler.post {
            layoutSearching.visibility = View.GONE
            layoutCall.visibility = View.GONE
            layoutDashboard.visibility = View.VISIBLE
            updateLevelDashboardUI()

            if (canReconnect && lastPeerId != null) {
                btnReconnectLast.visibility = View.VISIBLE
            } else {
                btnReconnectLast.visibility = View.GONE
            }
        }
    }

    private fun showExtendDialog() {
        AlertDialog.Builder(this)
            .setTitle("Time Warning")
            .setMessage("1 minute remaining! Would you like to extend this call by 5 minutes?")
            .setPositiveButton("Extend +5 Mins") { _, _ ->
                CallService.extendTime(300L)
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // --- Signaling Listener Handlers ---

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean) {
        if (isReconnect) {
            canReconnect = false
            lastPeerId = null
        } else {
            lastPeerId = peerId
            canReconnect = true
        }

        WebRtcAudioClient.init(applicationContext, object : WebRtcAudioClient.RtcListener {
            override fun onLocalOfferCreated(sdp: SessionDescription) {
                SignalingClient.sendOffer(sdp)
            }

            override fun onLocalAnswerCreated(sdp: SessionDescription) {
                SignalingClient.sendAnswer(sdp)
            }

            override fun onIceCandidateGenerated(candidate: IceCandidate) {
                SignalingClient.sendIceCandidate(candidate)
            }

            override fun onAudioConnected() {
                startCallView()
            }

            override fun onDisconnected() {
                endCallSession()
            }
        })

        if (isInitiator) {
            WebRtcAudioClient.createOffer()
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        WebRtcAudioClient.handleRemoteOffer(sdp)
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        WebRtcAudioClient.handleRemoteAnswer(sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        WebRtcAudioClient.addIceCandidate(candidate)
    }

    override fun onCallEnded() {
        endCallSession()
    }

    override fun onReconnectWaiting() {
        mainUiHandler.post {
            tvSearchingStatus.text = "Waiting for partner to accept reconnect..."
        }
    }

    override fun onReconnectFailed(reason: String) {
        mainUiHandler.post {
            canReconnect = false
            showDashboardView()
        }
    }

    // --- Lifecycle & Background Auto-Mute ---

    override fun onResume() {
        super.onResume()
        backgroundHandler.removeCallbacks(autoMuteRunnable)
        if (wasMutedBeforeBackground && isMuted) {
            isMuted = false
            WebRtcAudioClient.setMicrophoneMute(false)
            btnMute.text = "🎤"
            wasMutedBeforeBackground = false
        }
        try {
            sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onPause() {
        super.onPause()
        if (layoutCall.visibility == View.VISIBLE) {
            wasMutedBeforeBackground = !isMuted
            backgroundHandler.postDelayed(autoMuteRunnable, 30000L)
        }
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {}

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }
}
