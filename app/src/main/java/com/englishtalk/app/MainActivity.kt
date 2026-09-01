package com.englishtalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.utils.AppLogger
import com.englishtalk.app.utils.CooldownManager
import com.englishtalk.app.webrtc.WebRtcManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SignalingClient.SignalingListener, SensorEventListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // Layout Containers
    private lateinit var layoutDashboard: View
    private lateinit var layoutLanguages: View
    private lateinit var layoutSearching: View
    private lateinit var layoutCall: View

    // Dashboard UI
    private lateinit var tvTalkCoins: TextView
    private lateinit var tvStreak: TextView
    private lateinit var tvPracticeTime: TextView
    private lateinit var tvTotalCalls: TextView
    private lateinit var btnBeginner: Button
    private lateinit var btnAdvanced: Button
    private lateinit var btnOtherLanguages: Button
    private lateinit var btnReconnect: Button
    private lateinit var btnShare: Button
    private lateinit var btnReportUser: Button
    private lateinit var switchFemaleOnly: Switch
    private lateinit var tvFemaleOnlyLabel: TextView

    // Searching UI
    private lateinit var tvSearchStatus: TextView
    private lateinit var btnCancelSearch: Button

    // In-Call UI
    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnEndCall: ImageButton

    // Languages UI
    private lateinit var btnWatchAdCoins: Button
    private lateinit var btnBackFromLanguages: ImageButton
    private lateinit var gridLanguages: GridLayout

    // AdMob
    private lateinit var bannerAdView: AdView
    private var rewardedAd: RewardedAd? = null

    // State Variables
    private var currentLevel = "Beginner"
    private var currentLanguage = "ENGLISH"
    private var lastCallerPeerId = ""
    private var isCallInProgress = false
    private var callStartTimeMs = 0L
    private var isCallTimerExtended = false
    private var warningDialogShown = false

    // Timing & Mute Handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundAutoMuteRunnable: Runnable? = null
    private var isAppInBackground = false

    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (isCallInProgress && callStartTimeMs > 0L) {
                val elapsedSec = (System.currentTimeMillis() - callStartTimeMs) / 1000L
                val mins = elapsedSec / 60
                val secs = elapsedSec % 60
                tvCallDuration.text = String.format(Locale.US, "%02d:%02d", mins, secs)

                // 14-Minute Warning Dialog (840s)
                if (elapsedSec >= 840 && !warningDialogShown && !isCallTimerExtended) {
                    warningDialogShown = true
                    showCallExtensionDialog()
                }

                // 15-Minute Hard Limit (or 20-min if extended)
                val maxLimitSec = if (isCallTimerExtended) 1200L else 900L
                if (elapsedSec >= maxLimitSec) {
                    Toast.makeText(this@MainActivity, "Call reached time limit", Toast.LENGTH_SHORT).show()
                    endActiveCall()
                    return
                }

                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        setupWakeLock()
        initViews()
        setupListeners()
        setupBackPressHandling()

        SignalingClient.setListener(this)
        SignalingClient.connect()
        WebRtcManager.init(applicationContext)

        loadRewardedAd()
        refreshDashboardUI()
        checkPermissions()
    }

    private fun setupWakeLock() {
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            wakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "EnglishTalk:ProximityLock")
        }
    }

    private fun initViews() {
        layoutDashboard = findViewById(R.id.layoutDashboard)
        layoutLanguages = findViewById(R.id.layoutLanguages)
        layoutSearching = findViewById(R.id.layoutSearching)
        layoutCall = findViewById(R.id.layoutCall)

        tvTalkCoins = findViewById(R.id.tvTalkCoins)
        tvStreak = findViewById(R.id.tvStreak)
        tvPracticeTime = findViewById(R.id.tvPracticeTime)
        tvTotalCalls = findViewById(R.id.tvTotalCalls)
        btnBeginner = findViewById(R.id.btnBeginner)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        btnOtherLanguages = findViewById(R.id.btnOtherLanguages)
        btnReconnect = findViewById(R.id.btnReconnect)
        btnShare = findViewById(R.id.btnShare)
        btnReportUser = findViewById(R.id.btnReportUser)
        switchFemaleOnly = findViewById(R.id.switchFemaleOnly)
        tvFemaleOnlyLabel = findViewById(R.id.tvFemaleOnlyLabel)

        tvSearchStatus = findViewById(R.id.tvSearchStatus)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)

        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnEndCall = findViewById(R.id.btnEndCall)

        btnWatchAdCoins = findViewById(R.id.btnWatchAdCoins)
        btnBackFromLanguages = findViewById(R.id.btnBackFromLanguages)
        gridLanguages = findViewById(R.id.gridLanguages)
        bannerAdView = findViewById(R.id.bannerAdView)

        bannerAdView.loadAd(AdRequest.Builder().build())
    }

    private fun setupListeners() {
        btnBeginner.setOnClickListener {
            currentLevel = "Beginner"
            currentLanguage = "ENGLISH"
            startSearchingFlow()
        }

        btnAdvanced.setOnClickListener {
            val qualifiedCalls = prefs.getInt("beginner_qualified_calls", 0)
            if (qualifiedCalls >= 20) {
                currentLevel = "Advanced"
                currentLanguage = "ENGLISH"
                startSearchingFlow()
            } else {
                Toast.makeText(
                    this,
                    "Complete 20 calls (4+ mins each in Beginner) to unlock Advanced. Progress: $qualifiedCalls / 20",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnOtherLanguages.setOnClickListener {
            showLayout(layoutLanguages)
        }

        btnBackFromLanguages.setOnClickListener {
            showLayout(layoutDashboard)
        }

        btnCancelSearch.setOnClickListener {
            cancelSearchAndReturn()
        }

        btnEndCall.setOnClickListener {
            endActiveCall()
        }

        btnMute.setOnClickListener {
            val newMuteState = !WebRtcManager.isMuted
            WebRtcManager.setMuted(newMuteState)
            btnMute.setImageResource(if (newMuteState) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
            AppLogger.log("Audio", "Mute toggled: $newMuteState")
        }

        btnSpeaker.setOnClickListener {
            val newSpeakerState = !audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = newSpeakerState
            btnSpeaker.setImageResource(if (newSpeakerState) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off)
            AppLogger.log("Audio", "Speaker toggled: $newSpeakerState")
        }

        btnReconnect.setOnClickListener {
            if (lastCallerPeerId.isNotEmpty()) {
                initiateReconnectFlow()
            }
        }

        btnShare.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Practice English with me on English Talk! Download here: https://play.google.com/store/apps/details?id=$packageName")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share App"))
        }

        btnReportUser.setOnClickListener {
            showReportUserDialog()
        }

        btnWatchAdCoins.setOnClickListener {
            showRewardedAd()
        }

        setupRegionalLanguageButtons()
    }

    private fun setupRegionalLanguageButtons() {
        val languages = listOf("HINDI", "PUNJABI", "MARATHI", "BENGALI", "BHOJPURI", "GUJARATI", "KANNADA", "MALAYALAM", "TAMIL", "TELUGU", "URDU", "ARABIC")
        for (i in 0 until gridLanguages.childCount) {
            val view = gridLanguages.getChildAt(i)
            if (view is Button && i < languages.size) {
                val langName = languages[i]
                view.setOnClickListener {
                    currentLevel = "Native"
                    currentLanguage = langName
                    startRegionalSearchFlow(langName)
                }
            }
        }
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    layoutSearching.visibility == View.VISIBLE || layoutCall.visibility == View.VISIBLE -> {
                        AppLogger.log("UI", "Back gesture intercepted - remaining on active screen")
                        // Invariant Rule 13: Completely ignore back presses in Search & Call screens
                    }
                    layoutLanguages.visibility == View.VISIBLE -> {
                        showLayout(layoutDashboard)
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun startSearchingFlow() {
        // Cooldown Invariant Verification
        if (CooldownManager.isUnderCooldown(this)) {
            val remSec = CooldownManager.getRemainingCooldownSeconds(this)
            val mins = remSec / 60
            val secs = remSec % 60
            Toast.makeText(
                this,
                "You are on a 3-minute break for frequent early hang-ups. Please wait ${mins}m ${secs}s before searching again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        tvSearchStatus.text = "Searching for practice partner..."
        showLayout(layoutSearching)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isFemaleOnly = switchFemaleOnly.isChecked
        val isVip = prefs.getBoolean("is_vip", false)
        val userGender = prefs.getString("user_gender", "Unknown") ?: "Unknown"

        SignalingClient.joinQueue(currentLevel, currentLanguage, userGender, isFemaleOnly, isVip)
        AppLogger.log("Queue", "Joined $currentLevel search queue in language: $currentLanguage")
    }

    private fun startRegionalSearchFlow(lang: String) {
        if (CooldownManager.isUnderCooldown(this)) {
            val remSec = CooldownManager.getRemainingCooldownSeconds(this)
            Toast.makeText(this, "You are on a 3-minute break. Please wait ${remSec}s.", Toast.LENGTH_SHORT).show()
            return
        }

        val coins = prefs.getInt("talk_coins", 0)
        if (coins < 1) {
            showZeroCoinsDialog()
            return
        }

        // Deduct 1 Talk Coin for Regional Pool
        prefs.edit().putInt("talk_coins", coins - 1).apply()
        refreshDashboardUI()
        AppLogger.log("Coins", "1 Talk Coin deducted for $lang pool")

        startSearchingFlow()
    }

    private fun cancelSearchAndReturn() {
        SignalingClient.leaveQueue()
        SignalingClient.cancelReconnect()

        // Refund Talk Coin if searching inside a regional pool
        if (currentLanguage != "ENGLISH") {
            val coins = prefs.getInt("talk_coins", 0)
            prefs.edit().putInt("talk_coins", coins + 1).apply()
            AppLogger.log("Coins", "1 Talk Coin refunded after search cancellation")
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showLayout(if (currentLanguage == "ENGLISH") layoutDashboard else layoutLanguages)
        refreshDashboardUI()
        AppLogger.log("UI", "Search cancelled by user")
    }

    private fun initiateReconnectFlow() {
        if (CooldownManager.isUnderCooldown(this)) {
            Toast.makeText(this, "Under cooldown. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        tvSearchStatus.text = "Reconnecting to last caller..."
        showLayout(layoutSearching)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        SignalingClient.requestReconnect(lastCallerPeerId, currentLevel)
        AppLogger.log("Reconnect", "Requesting reconnect with peer: $lastCallerPeerId")
    }

    private fun showReportUserDialog() {
        if (lastCallerPeerId.isEmpty()) {
            Toast.makeText(this, "No recent caller available to report.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Report Last Caller")
            .setMessage("Are you sure you want to report your last conversation partner for inappropriate behavior or abusive language?")
            .setPositiveButton("Report") { _, _ ->
                SignalingClient.reportUser(lastCallerPeerId)
                Toast.makeText(this, "Report submitted. Thank you for keeping our community safe.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCallExtensionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Call Limit Warning")
            .setMessage("This call will reach the 15-minute limit soon. Would you like to extend for +5 minutes?")
            .setPositiveButton("Extend +5 Mins") { _, _ ->
                isCallTimerExtended = true
                Toast.makeText(this, "Call extended by 5 minutes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .setCancelable(false)
            .show()
    }

    private fun showZeroCoinsDialog() {
        AlertDialog.Builder(this)
            .setTitle("🪙 0 Talk Coins")
            .setMessage("Joining Regional Language pools requires at least 1 Talk Coin. You can earn coins by practicing English for 1+ minute or watching a quick video ad.")
            .setPositiveButton("Watch Ad (+2 Coins)") { _, _ -> showRewardedAd() }
            .setNegativeButton("Practice English", null)
            .show()
    }

    // ----------------------------------------------------
    // SIGNALING LISTENER IMPLEMENTATION
    // ----------------------------------------------------

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean) {
        runOnUiThread {
            lastCallerPeerId = peerId
            isCallInProgress = true
            callStartTimeMs = System.currentTimeMillis()
            warningDialogShown = false
            isCallTimerExtended = false

            showLayout(layoutCall)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Audio Baseline Invariant: Earpiece Mode ON, Mic ON
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            WebRtcManager.setMuted(false)
            btnMute.setImageResource(R.drawable.ic_mic_on)
            btnSpeaker.setImageResource(R.drawable.ic_speaker_off)

            tvCallStatus.text = "Connected"
            tvCallDuration.text = "00:00"

            startService(Intent(this, CallService::class.java))
            proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

            mainHandler.post(callTimerRunnable)
            WebRtcManager.startPeerConnection(roomId, isInitiator, this)
            AppLogger.log("CallView", "Live call view active at 00:00")
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        WebRtcManager.handleRemoteOffer(sdp)
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        WebRtcManager.handleRemoteAnswer(sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        WebRtcManager.handleRemoteIceCandidate(candidate)
    }

    override fun onCallEnded() {
        runOnUiThread {
            AppLogger.log("Signaling", "Remote peer ended call")
            teardownCallSession(isRemoteDisconnect = true)
        }
    }

    override fun onReconnectWaiting() {
        runOnUiThread {
            tvSearchStatus.text = "Waiting for partner to accept reconnect..."
            AppLogger.log("Reconnect", "Waiting for partner...")
        }
    }

    override fun onReconnectFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Partner is unavailable for reconnect.", Toast.LENGTH_SHORT).show()
            cancelSearchAndReturn()
        }
    }

    override fun onServerCooldown(remainingSeconds: Long) {
        runOnUiThread {
            CooldownManager.triggerThreeMinuteCooldown(this)
            cancelSearchAndReturn()
            Toast.makeText(
                this,
                "You are on a 3-minute break due to community reports. Please take a short pause before searching again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun endActiveCall() {
        SignalingClient.endCall()
        teardownCallSession(isRemoteDisconnect = false)
    }

    private fun teardownCallSession(isRemoteDisconnect: Boolean) {
        if (!isCallInProgress) return
        isCallInProgress = false

        mainHandler.removeCallbacks(callTimerRunnable)
        stopService(Intent(this, CallService::class.java))
        sensorManager.unregisterListener(this)
        if (wakeLock?.isHeld == true) wakeLock?.release()

        val callDurationSec = if (callStartTimeMs > 0L) (System.currentTimeMillis() - callStartTimeMs) / 1000L else 0L

        // Record Call Duration for Cooldown Engine
        CooldownManager.onCallFinished(this, callDurationSec)

        // Atomic Statistics & Coins Evaluation (Rule 1, 5, 7, 11)
        updateSessionStats(callDurationSec)

        WebRtcManager.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Route clean post-call destination (Rule 2 & 4)
        if (btnReconnect.visibility == View.VISIBLE && !isRemoteDisconnect) {
            btnReconnect.visibility = View.GONE
        } else if (lastCallerPeerId.isNotEmpty()) {
            btnReconnect.visibility = View.VISIBLE
        }

        showLayout(if (currentLanguage == "ENGLISH") layoutDashboard else layoutLanguages)
        refreshDashboardUI()
        AppLogger.log("WebRTC", "Session cleaned up. Talk time: $callDurationSec s")
    }

    private fun updateSessionStats(durationSec: Long) {
        val totalSec = prefs.getLong("total_practice_seconds", 0L) + durationSec
        val totalCalls = prefs.getInt("total_calls_count", 0) + 1
        val editor = prefs.edit()

        editor.putLong("total_practice_seconds", totalSec)
        editor.putInt("total_calls_count", totalCalls)

        // Rule 5: English calls >= 60s earn +1 Talk Coin
        if (currentLanguage == "ENGLISH" && durationSec >= 60) {
            val currentCoins = prefs.getInt("talk_coins", 0) + 1
            editor.putInt("talk_coins", currentCoins)
            AppLogger.log("Coins", "Earned 1 Talk Coin! Total: $currentCoins")
        }

        // Rule 11: Beginner calls >= 240s count toward Advanced unlock
        if (currentLanguage == "ENGLISH" && currentLevel == "Beginner" && durationSec >= 240) {
            val qualified = prefs.getInt("beginner_qualified_calls", 0) + 1
            editor.putInt("beginner_qualified_calls", qualified)
            AppLogger.log("Progression", "Advanced unlock progression: $qualified / 20")
        }

        // Rule 7: Daily Streak calculation
        if (durationSec >= 60) {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val lastActiveDate = prefs.getString("last_active_date", "") ?: ""
            val currentStreak = prefs.getInt("daily_streak", 0)

            if (lastActiveDate != todayStr) {
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

                if (lastActiveDate == yesterdayStr) {
                    editor.putInt("daily_streak", currentStreak + 1)
                } else {
                    editor.putInt("daily_streak", 1)
                }
                editor.putString("last_active_date", todayStr)
            }
        }

        editor.apply()
    }

    private fun refreshDashboardUI() {
        val coins = prefs.getInt("talk_coins", 0)
        val streak = prefs.getInt("daily_streak", 0)
        val practiceMins = prefs.getLong("total_practice_seconds", 0L) / 60
        val totalCalls = prefs.getInt("total_calls_count", 0)
        val qualifiedCalls = prefs.getInt("beginner_qualified_calls", 0)

        tvTalkCoins.text = "🪙 Talk Coins: $coins"
        tvStreak.text = "$streak"
        tvPracticeTime.text = "${practiceMins}m"
        tvTotalCalls.text = "$totalCalls"

        if (qualifiedCalls >= 20) {
            btnAdvanced.text = "ADVANCED (TAP TO CALL)"
        } else {
            btnAdvanced.text = "🔒 ADVANCED ($qualifiedCalls/20)"
        }
    }

    private fun showLayout(activeLayout: View) {
        layoutDashboard.visibility = if (activeLayout == layoutDashboard) View.VISIBLE else View.GONE
        layoutLanguages.visibility = if (activeLayout == layoutLanguages) View.VISIBLE else View.GONE
        layoutSearching.visibility = if (activeLayout == layoutSearching) View.VISIBLE else View.GONE
        layoutCall.visibility = if (activeLayout == layoutCall) View.VISIBLE else View.GONE
    }

    // ----------------------------------------------------
    // BACKGROUND AUTO-MUTE & POWER BUTTON MANAGEMENT
    // ----------------------------------------------------

    override fun onPause() {
        super.onPause()
        if (isCallInProgress) {
            val isScreenOn = powerManager.isInteractive
            if (isScreenOn) {
                // Rule 15: Background with Screen ON -> 30s Auto-Mute countdown
                isAppInBackground = true
                backgroundAutoMuteRunnable = Runnable {
                    if (isAppInBackground && isCallInProgress) {
                        WebRtcManager.setMuted(true)
                        AppLogger.log("AutoMute", "Hard-muted microphone after 30s in background")
                    }
                }
                backgroundAutoMuteRunnable?.let { mainHandler.postDelayed(it, 30000L) }
                AppLogger.log("AutoMute", "App minimized to background: 30s auto-mute timer started")
            } else {
                // Rule 14: Screen Locked via Power Button -> Unmuted indefinitely
                AppLogger.log("PowerKey", "Screen locked via power button - keeping microphone active")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        backgroundAutoMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        if (isCallInProgress && WebRtcManager.isMuted) {
            WebRtcManager.setMuted(false)
            btnMute.setImageResource(R.drawable.ic_mic_on)
            AppLogger.log("AutoMute", "Microphone unmuted upon app foreground")
        }
        refreshDashboardUI()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY && isCallInProgress) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            if (distance < maxRange) {
                if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ----------------------------------------------------
    // ADMOB REWARDED VIDEO
    // ----------------------------------------------------

    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(this, "ca-app-pub-3940256099942544/5224354917", adRequest, object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                AppLogger.log("AdMob", "Rewarded ad loaded and ready")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                rewardedAd = null
                AppLogger.log("AdMob", "Rewarded ad failed: ${error.message}")
            }
        })
    }

    private fun showRewardedAd() {
        if (rewardedAd != null) {
            rewardedAd?.show(this) { _ ->
                val currentCoins = prefs.getInt("talk_coins", 0) + 2
                prefs.edit().putInt("talk_coins", currentCoins).apply()
                refreshDashboardUI()
                Toast.makeText(this, "+2 Talk Coins added!", Toast.LENGTH_SHORT).show()
                AppLogger.log("Coins", "Rewarded ad completed: +2 Talk Coins added")
                loadRewardedAd()
            }
        } else {
            Toast.makeText(this, "Ad is loading, please try in a moment...", Toast.LENGTH_SHORT).show()
            loadRewardedAd()
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }
}
