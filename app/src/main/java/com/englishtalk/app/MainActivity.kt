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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.utils.AppLogger
import com.englishtalk.app.utils.CooldownManager
import com.englishtalk.app.webrtc.WebRtcAudioClient
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity(), SignalingClient.SignalingListener, SensorEventListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // Layout Containers
    private var layoutDashboard: View? = null
    private var layoutLanguages: View? = null
    private var layoutSearching: View? = null
    private var layoutCall: View? = null

    // Dashboard UI
    private var tvTalkCoins: TextView? = null
    private var tvStreak: TextView? = null
    private var tvPracticeTime: TextView? = null
    private var tvTotalCalls: TextView? = null
    private var btnBeginner: Button? = null
    private var btnAdvanced: Button? = null
    private var btnOtherLanguages: Button? = null
    private var btnReconnect: Button? = null
    private var btnShare: Button? = null
    private var btnReportUser: View? = null
    private var switchFemaleOnly: Switch? = null
    private var tvFemaleOnlyLabel: TextView? = null

    // Searching UI
    private var tvSearchStatus: TextView? = null
    private var btnCancelSearch: Button? = null

    // In-Call UI (Polymorphic View to support Button or ImageButton)
    private var tvCallStatus: TextView? = null
    private var tvCallDuration: TextView? = null
    private var btnMute: View? = null
    private var btnSpeaker: View? = null
    private var btnEndCall: View? = null

    // Languages UI
    private var btnWatchAdCoins: View? = null
    private var btnBackFromLanguages: View? = null
    private var gridLanguages: GridLayout? = null

    // AdMob
    private var bannerAdView: AdView? = null
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
                val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)

                runOnUiThread {
                    tvCallDuration?.text = formatted
                }

                // 14-Minute Warning Dialog (840s)
                if (elapsedSec >= 840 && !warningDialogShown && !isCallTimerExtended) {
                    warningDialogShown = true
                    runOnUiThread { showCallExtensionDialog() }
                }

                // 15-Minute Hard Limit (or 20-min if extended)
                val maxLimitSec = if (isCallTimerExtended) 1200L else 900L
                if (elapsedSec >= maxLimitSec) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Call reached time limit", Toast.LENGTH_SHORT).show()
                        endActiveCall()
                    }
                    return
                }

                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            AppLogger.log("CRASH-TRAP", "Uncaught exception: ${e.message}")
        }

        try {
            val layoutId = resources.getIdentifier("activity_main", "layout", packageName)
            if (layoutId != 0) {
                setContentView(layoutId)
            }
        } catch (e: Throwable) {
            AppLogger.log("Layout-ERR", "setContentView error: ${e.message}")
        }

        prefs = getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        try {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        } catch (e: Throwable) {}

        setupWakeLock()
        initViews()
        setupListeners()

        // Asynchronous AdMob init
        MobileAds.initialize(this) {
            runOnUiThread {
                try {
                    bannerAdView?.loadAd(AdRequest.Builder().build())
                } catch (e: Throwable) {
                    AppLogger.log("AdMob-ERR", "Banner load: ${e.message}")
                }
                loadRewardedAd()
            }
        }

        try {
            SignalingClient.setListener(this)
            SignalingClient.connect()
            WebRtcAudioClient.init(applicationContext)
        } catch (e: Throwable) {
            AppLogger.log("Init-ERR", "Signaling/WebRTC init: ${e.message}")
        }

        refreshDashboardUI()
        checkPermissions()
    }

    private fun setupWakeLock() {
        try {
            if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                wakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "EnglishTalk:ProximityLock")
            }
        } catch (e: Throwable) {
            AppLogger.log("WakeLock-ERR", "WakeLock init: ${e.message}")
        }
    }

    private fun <T : View> resolveView(resName: String): T? {
        val id = resources.getIdentifier(resName, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun initViews() {
        layoutDashboard = resolveView("layoutDashboard")
        layoutLanguages = resolveView("layoutLanguages")
        layoutSearching = resolveView("layoutSearching")
        layoutCall = resolveView("layoutCall")

        tvTalkCoins = resolveView("tvTalkCoins")
        tvStreak = resolveView("tvStreak")
        tvPracticeTime = resolveView("tvPracticeTime")
        tvTotalCalls = resolveView("tvTotalCalls")
        btnBeginner = resolveView("btnBeginner")
        btnAdvanced = resolveView("btnAdvanced")
        btnOtherLanguages = resolveView("btnOtherLanguages")
        btnReconnect = resolveView("btnReconnect")
        btnShare = resolveView("btnShare")
        btnReportUser = resolveView("btnReportUser")
        switchFemaleOnly = resolveView("switchFemaleOnly")
        tvFemaleOnlyLabel = resolveView("tvFemaleOnlyLabel")

        tvSearchStatus = resolveView("tvSearchStatus")
        btnCancelSearch = resolveView("btnCancelSearch")

        tvCallStatus = resolveView("tvCallStatus")
        tvCallDuration = resolveView("tvCallDuration") ?: resolveView("tvTimer")
        btnMute = resolveView("btnMute")
        btnSpeaker = resolveView("btnSpeaker")
        btnEndCall = resolveView("btnEndCall")

        btnWatchAdCoins = resolveView("btnWatchAdCoins") ?: resolveView("btnWatchAd")
        btnBackFromLanguages = resolveView("btnBackFromLanguages") ?: resolveView("btnBackLanguages")
        gridLanguages = resolveView("gridLanguages")
        bannerAdView = resolveView("bannerAdView")
    }

    private fun updateButtonVisual(buttonView: View?, resId: Int, textFallback: String) {
        when (buttonView) {
            is ImageButton -> buttonView.setImageResource(resId)
            is Button -> buttonView.text = textFallback
        }
    }

    private fun setupListeners() {
        btnBeginner?.setOnClickListener {
            currentLevel = "Beginner"
            currentLanguage = "ENGLISH"
            startSearchingFlow()
        }

        btnAdvanced?.setOnClickListener {
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

        btnOtherLanguages?.setOnClickListener {
            showLayout(layoutLanguages)
            setupRegionalLanguageButtons()
        }

        btnBackFromLanguages?.setOnClickListener {
            showLayout(layoutDashboard)
        }

        btnCancelSearch?.setOnClickListener {
            cancelSearchAndReturn()
        }

        btnEndCall?.setOnClickListener {
            endActiveCall()
        }

        btnMute?.setOnClickListener {
            val newMuteState = !WebRtcAudioClient.isMuted
            WebRtcAudioClient.setMuted(newMuteState)
            updateButtonVisual(
                btnMute,
                if (newMuteState) android.R.drawable.stat_notify_call_mute else android.R.drawable.ic_btn_speak_now,
                if (newMuteState) "UNMUTE" else "MUTE"
            )
            AppLogger.log("Audio", "Mute toggled: $newMuteState")
        }

        btnSpeaker?.setOnClickListener {
            val newSpeakerState = !audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = newSpeakerState
            updateButtonVisual(
                btnSpeaker,
                if (newSpeakerState) android.R.drawable.stat_sys_speakerphone else android.R.drawable.stat_notify_call_mute,
                if (newSpeakerState) "EARPIECE" else "SPEAKER"
            )
            AppLogger.log("Audio", "Speaker toggled: $newSpeakerState")
        }

        btnReconnect?.setOnClickListener {
            if (lastCallerPeerId.isNotEmpty()) {
                initiateReconnectFlow()
            }
        }

        btnShare?.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Practice English with me on English Talk! Download here: https://play.google.com/store/apps/details?id=$packageName")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share App"))
        }

        btnReportUser?.setOnClickListener {
            showReportUserDialog()
        }

        btnWatchAdCoins?.setOnClickListener {
            showRewardedAd()
        }

        // Diagnostic Log Viewer Trigger (Long press on Streak / Practice Time)
        tvStreak?.setOnLongClickListener {
            showDiagnosticLogsDialog()
            true
        }
        tvPracticeTime?.setOnLongClickListener {
            showDiagnosticLogsDialog()
            true
        }

        setupRegionalLanguageButtons()
    }

    private fun setupRegionalLanguageButtons() {
        val languages = listOf("HINDI", "PUNJABI", "MARATHI", "BENGALI", "BHOJPURI", "GUJARATI", "KANNADA", "MALAYALAM", "TAMIL", "TELUGU", "URDU", "ARABIC")

        // Direct ID lookup binding
        for (lang in languages) {
            val view = resolveView<Button>("btn${lang.lowercase().replaceFirstChar { it.uppercase() }}")
            view?.setOnClickListener {
                currentLevel = "Native"
                currentLanguage = lang
                startRegionalSearchFlow(lang)
            }
        }

        // GridLayout Children Binding
        gridLanguages?.let { grid ->
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i)
                if (child is Button) {
                    val buttonText = child.text.toString().trim().uppercase()
                    val matchedLang = languages.find { buttonText.contains(it) } ?: if (i < languages.size) languages[i] else null
                    if (matchedLang != null) {
                        child.setOnClickListener {
                            currentLevel = "Native"
                            currentLanguage = matchedLang
                            startRegionalSearchFlow(matchedLang)
                        }
                    }
                }
            }
        }
    }

    private fun showDiagnosticLogsDialog() {
        val logs = try {
            AppLogger.getLogs()
        } catch (e: Throwable) {
            "No diagnostic logs recorded yet."
        }

        val scroll = ScrollView(this)
        val text = TextView(this).apply {
            setText(logs)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
            textSize = 12f
        }
        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle("Diagnostic Logs")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear Logs") { _, _ ->
                AppLogger.clearLogs()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            layoutSearching?.visibility == View.VISIBLE || layoutCall?.visibility == View.VISIBLE -> {
                AppLogger.log("UI", "Back gesture intercepted - remaining on active screen")
            }
            layoutLanguages?.visibility == View.VISIBLE -> {
                showLayout(layoutDashboard)
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun startSearchingFlow() {
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

        tvSearchStatus?.text = "Searching for practice partner..."
        showLayout(layoutSearching)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isFemaleOnly = switchFemaleOnly?.isChecked == true
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

        prefs.edit().putInt("talk_coins", coins - 1).apply()
        refreshDashboardUI()
        AppLogger.log("Coins", "1 Talk Coin deducted for $lang pool")

        startSearchingFlow()
    }

    private fun cancelSearchAndReturn() {
        SignalingClient.leaveQueue()
        SignalingClient.cancelReconnect()

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

        tvSearchStatus?.text = "Reconnecting to last caller..."
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

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean) {
        runOnUiThread {
            lastCallerPeerId = peerId
            isCallInProgress = true
            callStartTimeMs = System.currentTimeMillis()
            warningDialogShown = false
            isCallTimerExtended = false

            showLayout(layoutCall)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            WebRtcAudioClient.setMuted(false)
            updateButtonVisual(btnMute, android.R.drawable.ic_btn_speak_now, "MUTE")
            updateButtonVisual(btnSpeaker, android.R.drawable.stat_notify_call_mute, "SPEAKER")

            tvCallStatus?.text = "Connected"
            tvCallDuration?.text = "00:00"

            try {
                startService(Intent(this, CallService::class.java))
            } catch (e: Throwable) {
                AppLogger.log("Service-ERR", "CallService start: ${e.message}")
            }

            proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

            mainHandler.removeCallbacks(callTimerRunnable)
            mainHandler.post(callTimerRunnable)
            WebRtcAudioClient.startPeerConnection(roomId, isInitiator, this)
            AppLogger.log("CallView", "Live call view active at 00:00")
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        WebRtcAudioClient.handleRemoteOffer(sdp)
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        WebRtcAudioClient.handleRemoteAnswer(sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        WebRtcAudioClient.handleRemoteIceCandidate(candidate)
    }

    override fun onCallEnded() {
        runOnUiThread {
            AppLogger.log("Signaling", "Remote peer ended call")
            teardownCallSession(isRemoteDisconnect = true)
        }
    }

    override fun onReconnectWaiting() {
        runOnUiThread {
            tvSearchStatus?.text = "Waiting for partner to accept reconnect..."
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

        val callDurationSec = if (callStartTimeMs > 0L) (System.currentTimeMillis() - callStartTimeMs) / 1000L else 0L

        isCallInProgress = false
        mainHandler.removeCallbacks(callTimerRunnable)

        try {
            stopService(Intent(this, CallService::class.java))
        } catch (e: Throwable) {}

        sensorManager.unregisterListener(this)
        if (wakeLock?.isHeld == true) wakeLock?.release()

        CooldownManager.onCallFinished(this, callDurationSec)
        updateSessionStats(callDurationSec)

        WebRtcAudioClient.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Show Reconnect and Report buttons whenever a peer ID was established
        if (lastCallerPeerId.isNotEmpty()) {
            btnReconnect?.visibility = View.VISIBLE
            btnReportUser?.visibility = View.VISIBLE
        }

        // Reconnect session destination invariance: always return to Dashboard
        if (currentLanguage == "ENGLISH" || lastCallerPeerId.isNotEmpty()) {
            showLayout(layoutDashboard)
        } else {
            showLayout(layoutLanguages)
        }

        refreshDashboardUI()
        AppLogger.log("WebRTC", "Session cleaned up. Talk time: $callDurationSec s")
    }

    private fun updateSessionStats(durationSec: Long) {
        val totalSec = prefs.getLong("total_practice_seconds", 0L) + durationSec
        val totalCalls = prefs.getInt("total_calls_count", 0) + 1
        val editor = prefs.edit()

        editor.putLong("total_practice_seconds", totalSec)
        editor.putInt("total_calls_count", totalCalls)

        // Award Talk Coin for English calls >= 60 seconds
        if (currentLanguage == "ENGLISH" && durationSec >= 60) {
            val currentCoins = prefs.getInt("talk_coins", 0) + 1
            editor.putInt("talk_coins", currentCoins)
            AppLogger.log("Coins", "Earned 1 Talk Coin! Total: $currentCoins")
        }

        // Beginner to Advanced Unlock Progression: calls >= 240 seconds (4 mins)
        if (currentLanguage == "ENGLISH" && currentLevel == "Beginner" && durationSec >= 240) {
            val qualified = prefs.getInt("beginner_qualified_calls", 0) + 1
            editor.putInt("beginner_qualified_calls", qualified)
            AppLogger.log("Progression", "Advanced unlock progression: $qualified / 20")
        }

        // Daily Streak update for calls >= 60 seconds
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

        tvTalkCoins?.text = "🪙 Talk Coins: $coins"
        tvStreak?.text = "$streak"
        tvPracticeTime?.text = "${practiceMins}m"
        tvTotalCalls?.text = "$totalCalls"

        if (qualifiedCalls >= 20) {
            btnAdvanced?.text = "ADVANCED (TAP TO CALL)"
        } else {
            btnAdvanced?.text = "🔒 ADVANCED ($qualifiedCalls/20)"
        }

        if (lastCallerPeerId.isNotEmpty()) {
            btnReconnect?.visibility = View.VISIBLE
            btnReportUser?.visibility = View.VISIBLE
        }
    }

    private fun showLayout(activeLayout: View?) {
        layoutDashboard?.visibility = if (activeLayout == layoutDashboard) View.VISIBLE else View.GONE
        layoutLanguages?.visibility = if (activeLayout == layoutLanguages) View.VISIBLE else View.GONE
        layoutSearching?.visibility = if (activeLayout == layoutSearching) View.VISIBLE else View.GONE
        layoutCall?.visibility = if (activeLayout == layoutCall) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        if (isCallInProgress) {
            val isScreenOn = powerManager.isInteractive
            if (isScreenOn) {
                isAppInBackground = true
                backgroundAutoMuteRunnable = Runnable {
                    if (isAppInBackground && isCallInProgress) {
                        WebRtcAudioClient.setMuted(true)
                        AppLogger.log("AutoMute", "Hard-muted microphone after 30s in background")
                    }
                }
                backgroundAutoMuteRunnable?.let { mainHandler.postDelayed(it, 30000L) }
                AppLogger.log("AutoMute", "App minimized to background: 30s auto-mute timer started")
            } else {
                AppLogger.log("PowerKey", "Screen locked via power button - keeping microphone active")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        backgroundAutoMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        if (isCallInProgress && WebRtcAudioClient.isMuted) {
            WebRtcAudioClient.setMuted(false)
            updateButtonVisual(btnMute, android.R.drawable.ic_btn_speak_now, "MUTE")
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

    private fun loadRewardedAd() {
        try {
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
        } catch (e: Throwable) {
            AppLogger.log("AdMob-ERR", "Load rewarded ad: ${e.message}")
        }
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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }
}
