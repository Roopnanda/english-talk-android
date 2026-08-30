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
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
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
    private var hasShownWarning = false

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

    // Stopwatch Elapsed Timer (Counts UP: 00:00 -> 15:00)
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsedSec = CallService.getElapsedSeconds()
            val totalMaxSec = CallService.getTotalDurationSeconds()

            if (elapsedSec >= totalMaxSec) {
                endCallSession()
                return
            }

            val mins = elapsedSec / 60
            val secs = elapsedSec % 60
            tvCallTimer.text = String.format("%02d:%02d", mins, secs)

            // 14-minute warning dialog at 840s
            if (elapsedSec >= 840L && !hasShownWarning) {
                hasShownWarning = true
                showExtendDialog()
            }

            timerHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews()
        initSensors()
        setupListeners()
        loadBannerAd()

        // Initialize WebRTC engine once at startup
        WebRtcAudioClient.init(applicationContext)

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
        } catch (e: Throwable) {
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

        AppLogger.init(tvConsoleLogs)
        updateLevelDashboardUI()
    }

    private fun setupListeners() {
        btnBeginner.setOnClickListener {
            if (hasAudioPermission()) {
                activeCallLevel = "Beginner"
                startMatchingSearch("Beginner")
            } else {
                checkPermissions()
            }
        }

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
            AppLogger.log("UI", "Search cancelled by user")
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
                AppLogger.log("Reconnect", "Requesting reconnect with peer: $targetPeer")
                SignalingClient.requestReconnect(targetPeer, activeCallLevel)
            }
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            WebRtcAudioClient.setMicrophoneMute(isMuted)
            btnMute.text = if (isMuted) "🔇" else "🎤"
            AppLogger.log("Audio", "Mute toggled: $isMuted")
        }

        btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            audioManager.isSpeakerphoneOn = isSpeakerOn
            btnSpeaker.text = if (isSpeakerOn) "🔊" else "🔈"
            AppLogger.log("Audio", "Speaker toggled: $isSpeakerOn")
        }

        btnEndCall.setOnClickListener {
            AppLogger.log("UI", "End call clicked")
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
        AppLogger.log("Queue", "Joined $level search queue")

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
                tvCallTimer.text = "00:00"
                hasShownWarning = false

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
                } catch (e: Throwable) {
                    AppLogger.log("Service-ERR", "FGS start note: ${e.message}")
                }

                timerHandler.post(timerRunnable)

                try {
                    proximityWakeLock?.let {
                        if (!it.isHeld) it.acquire(10 * 60 * 1000L)
                    }
                } catch (e: Throwable) {
                    AppLogger.log("WakeLock-ERR", "WakeLock acquire note: ${e.message}")
                }

                AppLogger.log("CallView", "Live call view active at 00:00")
            } catch (e: Throwable) {
                AppLogger.log("CallView-ERR", "View transition error: ${e.message}")
            }
        }
    }

    private fun endCallSession() {
        mainUiHandler.post {
            try {
                timerHandler.removeCallbacks(timerRunnable)

                val elapsed = CallService.getElapsedSeconds()
                val isAlreadyUnlocked = prefs.getBoolean(PREF_ADVANCED_UNLOCKED, false)

                // Beginner milestone qualification: 4+ minutes (240s) in Beginner queue
                if (!isAlreadyUnlocked && activeCallLevel == "Beginner" && elapsed >= 240L) {
                    val current = prefs.getInt(PREF_QUALIFIED_CALLS, 0) + 1
                    prefs.edit().putInt(PREF_QUALIFIED_CALLS, current).apply()
                    AppLogger.log("Progress", "Beginner milestone updated: $current/20")
                    if (current >= 20) {
                        prefs.edit().putBoolean(PREF_ADVANCED_UNLOCKED, true).apply()
                        AppLogger.log("Progress", "Advanced UNLOCKED")
                    }
                }

                SignalingClient.endCall()
                WebRtcAudioClient.close()

                try {
                    stopService(Intent(this, CallService::class.java))
                } catch (e: Throwable) {
                    // Safe cleanup
                }

                try {
                    proximityWakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                } catch (e: Throwable) {
                    // Safe cleanup
                }

                showDashboardView()
            } catch (e: Throwable) {
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
            .setMessage("14 minutes reached! Would you like to extend this call by 5 minutes?")
            .setPositiveButton("Extend +5 Mins") { _, _ ->
                CallService.extendTime(300L)
                AppLogger.log("Timer", "Call extended by 5 minutes")
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // --- Signaling Listener Handlers ---

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean) {
        AppLogger.log("Signaling", "Match: $roomId, Initiator: $isInitiator")
        if (isReconnect) {
            canReconnect = false
            lastPeerId = null
        } else {
            lastPeerId = peerId
            canReconnect = true
        }

        // Start session on the pre-initialized engine
        WebRtcAudioClient.startSession(object : WebRtcAudioClient.RtcListener {
            override fun onLocalOfferCreated(sdp: SessionDescription) {
                AppLogger.log("WebRTC", "Local offer SDP ready")
                SignalingClient.sendOffer(sdp)
            }

            override fun onLocalAnswerCreated(sdp: SessionDescription) {
                AppLogger.log("WebRTC", "Local answer SDP ready")
                SignalingClient.sendAnswer(sdp)
            }

            override fun onIceCandidateGenerated(candidate: IceCandidate) {
                SignalingClient.sendIceCandidate(candidate)
            }

            override fun onAudioConnected() {
                AppLogger.log("WebRTC", "Two-way live audio pipeline connected!")
                startCallView()
            }

            override fun onDisconnected() {
                AppLogger.log("WebRTC", "Audio pipeline disconnected")
                endCallSession()
            }
        })

        if (isInitiator) {
            AppLogger.log("WebRTC", "Generating initial SDP Offer...")
            WebRtcAudioClient.createOffer()
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        AppLogger.log("Signaling", "Remote SDP Offer received")
        WebRtcAudioClient.handleRemoteOffer(sdp)
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        AppLogger.log("Signaling", "Remote SDP Answer received")
        WebRtcAudioClient.handleRemoteAnswer(sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        WebRtcAudioClient.addIceCandidate(candidate)
    }

    override fun onCallEnded() {
        AppLogger.log("Signaling", "Remote peer ended call")
        endCallSession()
    }

    override fun onReconnectWaiting() {
        mainUiHandler.post {
            tvSearchingStatus.text = "Waiting for partner to accept reconnect..."
            AppLogger.log("Reconnect", "Waiting for partner...")
        }
    }

    override fun onReconnectFailed(reason: String) {
        mainUiHandler.post {
            canReconnect = false
            AppLogger.log("Reconnect", "Reconnect failed: $reason")
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
            AppLogger.log("AutoMute", "Microphone unmuted upon app foreground")
        }
        try {
            sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    override fun onPause() {
        super.onPause()
        if (layoutCall.visibility == View.VISIBLE) {
            wasMutedBeforeBackground = !isMuted
            backgroundHandler.postDelayed(autoMuteRunnable, 30000L)
            AppLogger.log("AutoMute", "App backgrounded: 30s auto-mute timer started")
        }
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {}

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun loadBannerAd() {
        try {
            val adView = AdView(this)
            adView.adUnitId = "ca-app-pub-3940256099942544/6300978111"
            adView.setAdSize(AdSize.BANNER)
            layoutBannerAd.removeAllViews()
            layoutBannerAd.addView(adView)
            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Throwable) {
            AppLogger.log("AdMob", "Banner ad load note: ${e.message}")
        }
    }

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
