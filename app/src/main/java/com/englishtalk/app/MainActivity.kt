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
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.utils.AppLogger
import com.englishtalk.app.utils.CooldownManager
import com.englishtalk.app.webrtc.WebRtcAudioClient
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
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
    private var tvTalkCoinsBadge: TextView? = null
    private var tvStreakVal: TextView? = null
    private var tvTotalMinutesVal: TextView? = null
    private var tvTotalCallsVal: TextView? = null
    private var tvLockProgressPopup: TextView? = null
    private var btnBeginner: Button? = null
    private var btnAdvanced: Button? = null
    private var btnOtherLanguages: Button? = null
    private var btnShareApp: Button? = null
    private var btnReconnectLast: Button? = null
    private var btnDashboardReportLast: Button? = null
    private var btnVip: Button? = null
    private var switchFemaleFilter: Switch? = null
    private var tvConsoleLogs: TextView? = null

    // Searching UI
    private var tvSearchingStatus: TextView? = null
    private var btnCancelSearch: Button? = null

    // In-Call UI
    private var tvCallPartnerName: TextView? = null
    private var tvCallTimer: TextView? = null
    private var btnMute: Button? = null
    private var btnSpeaker: Button? = null
    private var btnEndCall: Button? = null
    private var btnInCallReport: Button? = null

    // Languages UI
    private var btnBackFromLanguages: Button? = null
    private var btnWatchAdReward: Button? = null

    // AdMob
    private var layoutBannerAd: FrameLayout? = null
    private var bannerAdView: AdView? = null
    private var rewardedAd: RewardedAd? = null

    // State Variables
    private var currentLevel = "Beginner"
    private var currentLanguage = "ENGLISH"
    private var lastCallerPeerId = ""
    private var lastCallerLanguage = "ENGLISH"
    private var isCallInProgress = false
    private var callStartTimeMs = 0L
    private var isCallTimerExtended = false
    private var warningDialogShown = false

    // Strict Single-Use Reconnect Guards (Rule 12)
    private var isCurrentSessionReconnect = false
    private var reconnectConsumed = false

    // Reporting & Female Filter Context State (Rule 35)
    private var wasSearchingWithFemalePass = false
    private var isCurrentCallFemaleFiltered = false
    private var lastCallerWasFemaleFiltered = false
    private var hasReportedLastCaller = false

    // Rule 37: Cooldown & Quest Initiation Gate
    private var callStartedAfterCooldownExpired = false

    // Persistent Diagnostic Logs (Rule 27 & 33)
    private val diagnosticLogs = Collections.synchronizedList(mutableListOf<String>())

    // Timing & Mute Handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundAutoMuteRunnable: Runnable? = null
    private var isAppInBackground = false
    private var isUpdatingToggleProgrammatically = false

    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (isCallInProgress && callStartTimeMs > 0L) {
                val elapsedSec = (System.currentTimeMillis() - callStartTimeMs) / 1000L
                val mins = elapsedSec / 60
                val secs = elapsedSec % 60
                val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)

                runOnUiThread {
                    tvCallTimer?.text = formatted
                }

                if (elapsedSec >= 840 && !warningDialogShown && !isCallTimerExtended) {
                    warningDialogShown = true
                    runOnUiThread { showCallExtensionDialog() }
                }

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

    private fun logEvent(tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time][$tag] $message"
        diagnosticLogs.add(entry)
        if (diagnosticLogs.size > 250) {
            diagnosticLogs.removeAt(0)
        }
        
        try {
            prefs.edit().putString("saved_persistent_logs", diagnosticLogs.joinToString("\n")).apply()
        } catch (e: Throwable) {}

        runOnUiThread {
            tvConsoleLogs?.text = entry
        }
        AppLogger.log(tag, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            logEvent("CRASH-TRAP", "Uncaught: ${e.message}")
        }

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val savedLogs = prefs.getString("saved_persistent_logs", "") ?: ""
        if (savedLogs.isNotEmpty()) {
            val lines = savedLogs.split("\n")
            diagnosticLogs.clear()
            diagnosticLogs.addAll(lines.takeLast(250))
        }

        try {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        } catch (e: Throwable) {}

        setupWakeLock()
        initViews()
        setupListeners()
        setupRegionalLanguageButtons()

        MobileAds.initialize(this) {
            runOnUiThread {
                setupBannerAd()
                loadRewardedAd()
            }
        }

        try {
            SignalingClient.setListener(this)
            SignalingClient.connect()
            WebRtcAudioClient.init(applicationContext)
        } catch (e: Throwable) {
            logEvent("Init-ERR", "Signaling/WebRTC init: ${e.message}")
        }

        checkAndEnforceGenderSelection()
        refreshDashboardUI()
        checkPermissions()
        logEvent("SYS", "English Talk initialized successfully")
    }

    private fun checkAndEnforceGenderSelection() {
        val savedGender = prefs.getString("user_gender", "NOT_SET") ?: "NOT_SET"
        if (savedGender == "NOT_SET") {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 20)
            }

            val descText = TextView(this).apply {
                text = "To ensure fair matchmaking, please select your gender. This selection cannot be changed later."
                textSize = 14f
                setPadding(0, 0, 0, 30)
            }
            container.addView(descText)

            val radioGroup = RadioGroup(this)
            val rbMale = RadioButton(this).apply { text = "Male"; id = View.generateViewId() }
            val rbFemale = RadioButton(this).apply { text = "Female"; id = View.generateViewId() }
            val rbOther = RadioButton(this).apply { text = "Other"; id = View.generateViewId() }

            radioGroup.addView(rbMale)
            radioGroup.addView(rbFemale)
            radioGroup.addView(rbOther)
            rbMale.isChecked = true
            container.addView(radioGroup)

            AlertDialog.Builder(this)
                .setTitle("Select Your Gender")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("Confirm") { dialog, _ ->
                    val chosenGender = when (radioGroup.checkedRadioButtonId) {
                        rbFemale.id -> "FEMALE"
                        rbOther.id -> "OTHER"
                        else -> "MALE"
                    }
                    prefs.edit().putString("user_gender", chosenGender).apply()
                    logEvent("Profile", "Gender permanently locked as $chosenGender")
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupWakeLock() {
        try {
            if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                wakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "EnglishTalk:ProximityLock")
            }
        } catch (e: Throwable) {}
    }

    private fun initViews() {
        layoutDashboard = findViewById(R.id.layoutDashboard)
        layoutLanguages = findViewById(R.id.layoutLanguages)
        layoutSearching = findViewById(R.id.layoutSearching)
        layoutCall = findViewById(R.id.layoutCall)

        tvTalkCoinsBadge = findViewById(R.id.tvTalkCoinsBadge)
        tvStreakVal = findViewById(R.id.tvStreakVal)
        tvTotalMinutesVal = findViewById(R.id.tvTotalMinutesVal)
        tvTotalCallsVal = findViewById(R.id.tvTotalCallsVal)
        tvLockProgressPopup = findViewById(R.id.tvLockProgressPopup)
        btnBeginner = findViewById(R.id.btnBeginner)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        btnOtherLanguages = findViewById(R.id.btnOtherLanguages)
        btnShareApp = findViewById(R.id.btnShareApp)
        btnReconnectLast = findViewById(R.id.btnReconnectLast)
        btnDashboardReportLast = findViewById(R.id.btnDashboardReportLast)
        btnVip = findViewById(R.id.btnVip)
        switchFemaleFilter = findViewById(R.id.switchFemaleFilter)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)

        tvSearchingStatus = findViewById(R.id.tvSearchingStatus)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)

        tvCallPartnerName = findViewById(R.id.tvCallPartnerName)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnInCallReport = findViewById(R.id.btnInCallReport)

        btnBackFromLanguages = findViewById(R.id.btnBackFromLanguages)
        btnWatchAdReward = findViewById(R.id.btnWatchAdReward)
        layoutBannerAd = findViewById(R.id.layoutBannerAd)

        if (diagnosticLogs.isNotEmpty()) {
            tvConsoleLogs?.text = diagnosticLogs.last()
        }
    }

    private fun setupBannerAd() {
        try {
            layoutBannerAd?.let { container ->
                container.removeAllViews()
                bannerAdView = AdView(this).apply {
                    adUnitId = "ca-app-pub-3940256099942544/6300978111"
                    setAdSize(AdSize.BANNER)
                }
                container.addView(bannerAdView)
                bannerAdView?.loadAd(AdRequest.Builder().build())
            }
        } catch (e: Throwable) {}
    }

    private fun setupListeners() {
        btnBeginner?.setOnClickListener {
            currentLevel = "Beginner"
            currentLanguage = "ENGLISH"
            isCurrentSessionReconnect = false
            startSearchingFlow()
        }

        btnAdvanced?.setOnClickListener {
            val qualifiedCalls = prefs.getInt("beginner_qualified_calls", 0)
            if (qualifiedCalls >= 20) {
                currentLevel = "Advanced"
                currentLanguage = "ENGLISH"
                isCurrentSessionReconnect = false
                startSearchingFlow()
            } else {
                tvLockProgressPopup?.text = "Complete 20 calls (4+ mins each in Beginner) to unlock Advanced. Progress: $qualifiedCalls/20"
                tvLockProgressPopup?.visibility = View.VISIBLE
                mainHandler.postDelayed({ tvLockProgressPopup?.visibility = View.GONE }, 4000L)
            }
        }

        btnOtherLanguages?.setOnClickListener {
            showLayout(layoutLanguages)
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
            btnMute?.text = if (newMuteState) "🔇" else "🎤"
        }

        btnSpeaker?.setOnClickListener {
            val newSpeakerState = !audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = newSpeakerState
            btnSpeaker?.text = if (newSpeakerState) "🔊" else "🔈"
        }

        btnReconnectLast?.setOnClickListener {
            if (lastCallerPeerId.isNotEmpty() && !reconnectConsumed) {
                initiateReconnectFlow()
            } else {
                btnReconnectLast?.visibility = View.GONE
            }
        }

        btnDashboardReportLast?.setOnClickListener {
            showReportUserDialog(isInCall = false)
        }

        btnInCallReport?.setOnClickListener {
            showReportUserDialog(isInCall = true)
        }

        btnShareApp?.setOnClickListener {
            triggerGeneralAppShare()
        }

        btnWatchAdReward?.setOnClickListener {
            showRewardedAd()
        }

        btnVip?.setOnClickListener {
            val hasPass = prefs.getBoolean("has_female_pass", false)
            val isQuestActive = prefs.getBoolean("is_quest_active", false)

            if (hasPass) {
                showActivePassDialog()
            } else if (isQuestActive) {
                showPracticeQuestProgressDialog()
            } else {
                showPureVipDialog()
            }
        }

        switchFemaleFilter?.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingToggleProgrammatically) return@setOnCheckedChangeListener

            val hasPass = prefs.getBoolean("has_female_pass", false)
            val isVip = prefs.getBoolean("is_vip", false)

            if (isChecked && !hasPass && !isVip) {
                isUpdatingToggleProgrammatically = true
                switchFemaleFilter?.isChecked = false
                isUpdatingToggleProgrammatically = false
                showFemaleFilterLockedDialog()
            } else {
                logEvent("Filter", "Talk to Female filter set to: $isChecked")
            }
        }

        tvConsoleLogs?.setOnClickListener {
            showDiagnosticLogsDialog()
        }
    }

    private fun showFemaleFilterLockedDialog() {
        AlertDialog.Builder(this)
            .setTitle("VIP Feature")
            .setMessage("Talk to Female is a VIP feature. Get a VIP membership to enable this feature.")
            .setPositiveButton("Get VIP") { _, _ -> showPureVipDialog() }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showPureVipDialog() {
        AlertDialog.Builder(this)
            .setTitle("👑 English Talk VIP")
            .setMessage("Upgrade to VIP for unlimited Talk to Female filtering, priority matching, and an ad-free conversation experience.")
            .setPositiveButton("Subscribe Now", null)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun triggerGeneralAppShare() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Practice English speaking with real people on English Talk! Download here: https://play.google.com/store/apps/details?id=$packageName")
            type = "text/plain"
        }
        logEvent("Share", "General App Share launched from dashboard")
        startActivity(Intent.createChooser(sendIntent, "Share English Talk"))
    }

    private fun triggerQuestShareIntent() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Practice English speaking with real people on English Talk! Download here: https://play.google.com/store/apps/details?id=$packageName")
            type = "text/plain"
        }
        prefs.edit().putBoolean("has_shared_app", true).apply()
        logEvent("Share", "Quest share verified - share flag saved")
        startActivity(Intent.createChooser(sendIntent, "Share English Talk"))
        checkAndRewardFemalePass()
    }

    private fun checkAndRewardFemalePass() {
        val hasPass = prefs.getBoolean("has_female_pass", false)
        val isQuestActive = prefs.getBoolean("is_quest_active", false)
        if (hasPass || !isQuestActive) return

        val shared = prefs.getBoolean("has_shared_app", false)
        val qualifiedCalls = prefs.getInt("female_pass_qualified_calls", 0)

        if (shared && qualifiedCalls >= 5) {
            prefs.edit()
                .putBoolean("has_female_pass", true)
                .putBoolean("is_quest_active", false)
                .putInt("female_pass_qualified_calls", 0)
                .putBoolean("has_shared_app", false)
                .apply()

            runOnUiThread {
                isUpdatingToggleProgrammatically = true
                switchFemaleFilter?.isChecked = true
                isUpdatingToggleProgrammatically = false
                refreshDashboardUI()
                AlertDialog.Builder(this)
                    .setTitle("🎉 Quest Complete!")
                    .setMessage("You've earned 1 Free Female Match Pass! The female filter has been enabled for your next English practice call.")
                    .setPositiveButton("Awesome!", null)
                    .show()
                logEvent("Quest", "Granted 1 Female Match Pass to user")
            }
        }
    }

    private fun showActivePassDialog() {
        AlertDialog.Builder(this)
            .setTitle("✨ Pass Active")
            .setMessage("Your Free Female Match Pass is ready! Turn on the 'Talk to Female Only' switch and tap Beginner or Advanced to connect with a female partner.")
            .setPositiveButton("Got it!", null)
            .show()
    }

    private fun showMilestoneQuestOfferDialog() {
        AlertDialog.Builder(this)
            .setTitle("🏆 10-Minute Speaking Milestone!")
            .setMessage("Amazing dedication! You just completed a 10+ minute English conversation.\n\nWould you like to accept the Community Practice Challenge to earn 1 Free Female Match Pass?")
            .setPositiveButton("Accept Challenge") { _, _ ->
                prefs.edit().putBoolean("is_quest_active", true).putInt("female_pass_qualified_calls", 0).apply()
                refreshDashboardUI()
                logEvent("Quest", "User accepted Community Practice Quest")
                showPracticeQuestProgressDialog()
            }
            .setNegativeButton("Maybe Later", null)
            .show()
    }

    private fun showPracticeQuestProgressDialog() {
        val shared = prefs.getBoolean("has_shared_app", false)
        val calls = prefs.getInt("female_pass_qualified_calls", 0)

        val shareStatus = if (shared) "✅ Completed" else "⏳ Tap below to Share"
        val callsStatus = if (calls >= 5) "✅ 5/5 Completed" else "⏳ $calls/5 Calls (min 2 mins each in English)"

        val message = "Complete these practice goals to unlock 1 Free Female Match Pass:\n\n" +
                "1. Share English Talk: $shareStatus\n" +
                "2. Complete 5 English Calls: $callsStatus\n\n" +
                "Proves serious practice intent and protects community learners."

        AlertDialog.Builder(this)
            .setTitle("🎯 Community Practice Quest")
            .setMessage(message)
            .setPositiveButton(if (!shared) "Share App" else "Keep Practicing") { _, _ ->
                if (!shared) triggerQuestShareIntent()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupRegionalLanguageButtons() {
        val languageButtonMap = mapOf(
            R.id.btnLangHindi to "HINDI",
            R.id.btnLangPunjabi to "PUNJABI",
            R.id.btnLangMarathi to "MARATHI",
            R.id.btnLangBengali to "BENGALI",
            R.id.btnLangBhojpuri to "BHOJPURI",
            R.id.btnLangGujarati to "GUJARATI",
            R.id.btnLangKannada to "KANNADA",
            R.id.btnLangMalayalam to "MALAYALAM",
            R.id.btnLangTamil to "TAMIL",
            R.id.btnLangTelugu to "TELUGU",
            R.id.btnLangUrdu to "URDU",
            R.id.btnLangArabic to "ARABIC"
        )

        for ((btnId, langName) in languageButtonMap) {
            findViewById<Button>(btnId)?.setOnClickListener {
                currentLevel = "Native"
                currentLanguage = langName
                isCurrentSessionReconnect = false
                startRegionalSearchFlow(langName)
            }
        }
    }

    private fun showDiagnosticLogsDialog() {
        val logContent = if (diagnosticLogs.isEmpty()) {
            "No diagnostic logs recorded yet."
        } else {
            diagnosticLogs.joinToString("\n")
        }

        val scroll = ScrollView(this)
        val text = TextView(this).apply {
            setText(logContent)
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#050811"))
            setPadding(24, 24, 24, 24)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle("Diagnostic Logs")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear Logs") { _, _ ->
                diagnosticLogs.clear()
                prefs.edit().remove("saved_persistent_logs").apply()
                tvConsoleLogs?.text = "Tap to view saved logs / diagnostic trace..."
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            layoutSearching?.visibility == View.VISIBLE || layoutCall?.visibility == View.VISIBLE -> {
                logEvent("UI", "Back gesture intercepted - remaining on active screen")
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

        val lastFemaleCallEndTime = prefs.getLong("last_female_call_end_time_ms", 0L)
        val cooldownRemainingMs = 1800000L - (System.currentTimeMillis() - lastFemaleCallEndTime)
        callStartedAfterCooldownExpired = cooldownRemainingMs <= 0L

        val isFemaleOnly = switchFemaleFilter?.isChecked == true
        tvSearchingStatus?.text = if (isFemaleOnly) "Searching for a female partner..." else "Searching for a conversation partner..."
        showLayout(layoutSearching)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isVip = prefs.getBoolean("is_vip", false)
        val hasFemalePass = prefs.getBoolean("has_female_pass", false)
        val userGender = prefs.getString("user_gender", "MALE") ?: "MALE"

        wasSearchingWithFemalePass = isFemaleOnly && hasFemalePass
        isCurrentCallFemaleFiltered = isFemaleOnly

        SignalingClient.joinQueue(
            level = currentLevel,
            language = currentLanguage,
            userGender = userGender,
            isFemaleOnly = isFemaleOnly,
            isVip = isVip,
            hasFemalePass = hasFemalePass
        )
        logEvent("Queue", "Joined $currentLevel queue [$currentLanguage] (Gender: $userGender, FemaleFilter: $isFemaleOnly, Pass: $hasFemalePass)")
    }

    private fun startRegionalSearchFlow(lang: String) {
        if (CooldownManager.isUnderCooldown(this)) {
            val remSec = CooldownManager.getRemainingCooldownSeconds(this)
            val mins = remSec / 60
            val secs = remSec % 60
            Toast.makeText(this, "You are on a 3-minute break. Please wait ${mins}m ${secs}s.", Toast.LENGTH_SHORT).show()
            return
        }

        val coins = prefs.getInt("talk_coins", 0)
        if (coins < 1) {
            showZeroCoinsDialog()
            return
        }

        prefs.edit().putInt("talk_coins", coins - 1).apply()
        refreshDashboardUI()
        logEvent("Coins", "-1 Talk Coin for $lang pool")

        startSearchingFlow()
    }

    private fun cancelSearchAndReturn() {
        SignalingClient.leaveQueue()
        SignalingClient.cancelReconnect()

        val wasReconnectAttempt = isCurrentSessionReconnect
        if (wasReconnectAttempt) {
            isCurrentSessionReconnect = false
        }

        if (currentLanguage != "ENGLISH" && !wasReconnectAttempt) {
            val coins = prefs.getInt("talk_coins", 0)
            prefs.edit().putInt("talk_coins", coins + 1).apply()
            logEvent("Coins", "+1 Talk Coin refunded")
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (wasReconnectAttempt || currentLanguage == "ENGLISH") {
            showLayout(layoutDashboard)
        } else {
            showLayout(layoutLanguages)
        }

        refreshDashboardUI()
        logEvent("UI", "Search cancelled by user")
    }

    private fun initiateReconnectFlow() {
        if (CooldownManager.isUnderCooldown(this)) {
            val remSec = CooldownManager.getRemainingCooldownSeconds(this)
            val mins = remSec / 60
            val secs = remSec % 60
            Toast.makeText(this, "You are on a 3-minute break. Please wait ${mins}m ${secs}s.", Toast.LENGTH_SHORT).show()
            return
        }

        val lastFemaleCallEndTime = prefs.getLong("last_female_call_end_time_ms", 0L)
        val cooldownRemainingMs = 1800000L - (System.currentTimeMillis() - lastFemaleCallEndTime)
        callStartedAfterCooldownExpired = cooldownRemainingMs <= 0L

        isCurrentSessionReconnect = true
        tvSearchingStatus?.text = "Reconnecting to last caller..."
        showLayout(layoutSearching)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val reconnectLevel = if (lastCallerLanguage == "ENGLISH") currentLevel else "Native"
        SignalingClient.requestReconnect(lastCallerPeerId, reconnectLevel)
        logEvent("Reconnect", "Calling peer: $lastCallerPeerId in pool: $lastCallerLanguage")
    }

    private fun showReportUserDialog(isInCall: Boolean) {
        val targetPeerId = lastCallerPeerId
        if (targetPeerId.isEmpty()) {
            Toast.makeText(this, "No caller available to report.", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasReportedLastCaller) {
            Toast.makeText(this, "You have already submitted a report for this caller.", Toast.LENGTH_SHORT).show()
            return
        }

        val isFemaleSession = if (isInCall) isCurrentCallFemaleFiltered else lastCallerWasFemaleFiltered

        val reportOptions = if (isFemaleSession) {
            arrayOf("Partner is Not Female (Wrong Gender)", "Harassment / Abusive Behavior", "Spam / Commercial Ads")
        } else {
            arrayOf("Harassment / Abusive Behavior", "Spam / Commercial Ads", "Inappropriate Speech")
        }

        var selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle("Report Caller")
            .setSingleChoiceItems(reportOptions, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Submit Report") { _, _ ->
                val selectedText = reportOptions[selectedIndex]
                if (selectedText.contains("Not Female")) {
                    SignalingClient.reportGenderMismatch(targetPeerId)
                    logEvent("Report", "Reported gender mismatch for: $targetPeerId")
                } else {
                    SignalingClient.reportUser(targetPeerId)
                    logEvent("Report", "Reported violation ($selectedText) for: $targetPeerId")
                }

                hasReportedLastCaller = true
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
                logEvent("Timer", "Extended +5 mins")
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
            isCurrentSessionReconnect = isReconnect || isCurrentSessionReconnect

            if (isCurrentSessionReconnect) {
                lastCallerPeerId = ""
                reconnectConsumed = true
                btnReconnectLast?.visibility = View.GONE
                btnDashboardReportLast?.visibility = View.GONE
            } else {
                lastCallerPeerId = peerId
                lastCallerLanguage = currentLanguage
                lastCallerWasFemaleFiltered = isCurrentCallFemaleFiltered
                hasReportedLastCaller = false
                reconnectConsumed = false
            }

            if (switchFemaleFilter?.isChecked == true && wasSearchingWithFemalePass) {
                prefs.edit().putBoolean("has_female_pass", false).apply()
                isUpdatingToggleProgrammatically = true
                switchFemaleFilter?.isChecked = false
                isUpdatingToggleProgrammatically = false
                wasSearchingWithFemalePass = false
                logEvent("FemalePass", "Female Match Pass consumed for this live call")
            }

            isCallInProgress = true
            callStartTimeMs = System.currentTimeMillis()
            warningDialogShown = false
            isCallTimerExtended = false

            showLayout(layoutCall)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            btnInCallReport?.visibility = if (isCurrentCallFemaleFiltered) View.VISIBLE else View.GONE

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            WebRtcAudioClient.setMuted(false)
            btnMute?.text = "🎤"
            btnSpeaker?.text = "🔈"

            tvCallPartnerName?.text = "Connected"
            tvCallTimer?.text = "00:00"

            try {
                startService(Intent(this, CallService::class.java))
            } catch (e: Throwable) {}

            proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

            mainHandler.removeCallbacks(callTimerRunnable)
            mainHandler.post(callTimerRunnable)
            WebRtcAudioClient.startPeerConnection(roomId, isInitiator, this)
            logEvent("CallView", "Live call connected at 00:00 (Pool: $currentLanguage, Reconnect: $isCurrentSessionReconnect, FemaleFilter: $isCurrentCallFemaleFiltered)")
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
            logEvent("Signaling", "Remote peer ended call")
            teardownCallSession(isRemoteDisconnect = true)
        }
    }

    override fun onReconnectWaiting() {
        runOnUiThread {
            tvSearchingStatus?.text = "Waiting for partner to accept reconnect..."
            logEvent("Reconnect", "Waiting for partner...")
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

    override fun onVipSearchExpanding() {
        runOnUiThread {
            tvSearchingStatus?.text = "High demand right now. Expanding search..."
            logEvent("Queue", "Expanded VIP female priority search")
        }
    }

    override fun onVipQueueTimeout() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Female Queue Busy")
                .setMessage("No female partner is immediately available. Would you like to wait a bit longer or connect with anyone now without losing your pass?")
                .setPositiveButton("Wait +30s") { dialog, _ ->
                    SignalingClient.extendVipWait()
                    tvSearchingStatus?.text = "Waiting for next available female partner..."
                    dialog.dismiss()
                }
                .setNegativeButton("Connect with Anyone") { dialog, _ ->
                    isUpdatingToggleProgrammatically = true
                    switchFemaleFilter?.isChecked = false
                    isUpdatingToggleProgrammatically = false
                    wasSearchingWithFemalePass = false
                    isCurrentCallFemaleFiltered = false

                    SignalingClient.fallbackToGeneral()
                    tvSearchingStatus?.text = "Connecting with next available peer..."
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
            logEvent("Queue", "Triggered 35s VIP fallback prompt")
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

        val abuseResult = CooldownManager.onCallFinished(
            context = this,
            durationSec = callDurationSec,
            isLocalInitiatorHangup = !isRemoteDisconnect
        )

        val wasFemaleSession = isCurrentCallFemaleFiltered

        updateSessionStats(callDurationSec)
        WebRtcAudioClient.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val completedReconnectSession = isCurrentSessionReconnect

        if (completedReconnectSession) {
            lastCallerPeerId = ""
            lastCallerLanguage = ""
            isCurrentSessionReconnect = false
            reconnectConsumed = true
            btnReconnectLast?.visibility = View.GONE
            btnDashboardReportLast?.visibility = View.GONE
            logEvent("Reconnect", "Single-use reconnect session completed. Button locked and destroyed.")
        } else if (lastCallerPeerId.isNotEmpty() && !reconnectConsumed) {
            btnReconnectLast?.visibility = View.VISIBLE
            btnReconnectLast?.bringToFront()
            btnDashboardReportLast?.visibility = View.VISIBLE
            btnDashboardReportLast?.bringToFront()
            logEvent("Reconnect", "Single-use reconnect token armed for pool: $lastCallerLanguage (Peer: $lastCallerPeerId)")
        } else {
            btnReconnectLast?.visibility = View.GONE
            btnDashboardReportLast?.visibility = View.GONE
        }

        if (wasFemaleSession) {
            val teardownTime = System.currentTimeMillis()
            prefs.edit().putLong("last_female_call_end_time_ms", teardownTime).apply()
            logEvent("QuestCooldown", "Female pass session ended. 30-minute post-pass cooldown started.")
        }

        if (completedReconnectSession || currentLanguage == "ENGLISH") {
            showLayout(layoutDashboard)
        } else {
            showLayout(layoutLanguages)
        }

        refreshDashboardUI()
        logEvent("WebRTC", "Session ended. Talk time: ${callDurationSec}s")

        if (currentLanguage == "ENGLISH" && callDurationSec >= 600) {
            val hasPass = prefs.getBoolean("has_female_pass", false)
            val isQuestActive = prefs.getBoolean("is_quest_active", false)

            if (!hasPass && !isQuestActive && callStartedAfterCooldownExpired) {
                showMilestoneQuestOfferDialog()
            } else {
                logEvent("QuestMilestone", "10m milestone reached but disqualified (In cooldown, pass active, or already in quest)")
            }
        }

        when (abuseResult) {
            CooldownManager.AbuseActionResult.TIER1_WARNING -> {
                Toast.makeText(
                    this,
                    "Great conversations take a moment to start. Try speaking for 1 minute before skipping!",
                    Toast.LENGTH_LONG
                ).show()
            }
            CooldownManager.AbuseActionResult.TIER2_COOLDOWN -> {
                val remSec = CooldownManager.getRemainingCooldownSeconds(this)
                val mins = remSec / 60
                val secs = remSec % 60
                Toast.makeText(
                    this,
                    "You are on a 3-minute break for frequent early hang-ups. Please wait ${mins}m ${secs}s before searching again.",
                    Toast.LENGTH_LONG
                ).show()
            }
            CooldownManager.AbuseActionResult.NONE -> {}
        }
    }

    private fun updateSessionStats(durationSec: Long) {
        val totalSec = prefs.getLong("total_practice_seconds", 0L) + durationSec
        val totalCalls = prefs.getInt("total_calls_count", 0) + 1
        val editor = prefs.edit()

        editor.putLong("total_practice_seconds", totalSec)
        editor.putInt("total_calls_count", totalCalls)

        if (currentLanguage == "ENGLISH" && durationSec >= 60) {
            val currentCoins = prefs.getInt("talk_coins", 0) + 1
            editor.putInt("talk_coins", currentCoins)
            logEvent("Coins", "+1 Talk Coin earned (Total: $currentCoins)")
        }

        if (currentLanguage == "ENGLISH" && currentLevel == "Beginner" && durationSec >= 240) {
            val qualified = prefs.getInt("beginner_qualified_calls", 0) + 1
            editor.putInt("beginner_qualified_calls", qualified)
            logEvent("Progression", "Advanced unlock: $qualified / 20")
        }

        val isQuestActive = prefs.getBoolean("is_quest_active", false)
        if (isQuestActive && currentLanguage == "ENGLISH" && durationSec >= 120) {
            val passCalls = prefs.getInt("female_pass_qualified_calls", 0)
            if (passCalls < 5) {
                editor.putInt("female_pass_qualified_calls", passCalls + 1)
                logEvent("FemalePassQuest", "Qualifying quest call recorded: ${passCalls + 1}/5")
            }
        }

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
        checkAndRewardFemalePass()
    }

    private fun refreshDashboardUI() {
        val coins = prefs.getInt("talk_coins", 0)
        val streak = prefs.getInt("daily_streak", 0)
        val practiceMins = prefs.getLong("total_practice_seconds", 0L) / 60
        val totalCalls = prefs.getInt("total_calls_count", 0)
        val qualifiedCalls = prefs.getInt("beginner_qualified_calls", 0)
        val hasPass = prefs.getBoolean("has_female_pass", false)
        val isQuestActive = prefs.getBoolean("is_quest_active", false)
        val questCalls = prefs.getInt("female_pass_qualified_calls", 0)

        tvTalkCoinsBadge?.text = "🪙 Talk Coins: $coins"
        tvStreakVal?.text = "🔥 $streak"
        tvTotalMinutesVal?.text = "⏱️ ${practiceMins}m"
        tvTotalCallsVal?.text = "📞 $totalCalls"

        if (hasPass) {
            btnVip?.text = "PASS ACTIVE"
            btnVip?.setBackgroundColor(Color.parseColor("#EAB308"))
            btnVip?.setTextColor(Color.BLACK)
        } else if (isQuestActive) {
            btnVip?.text = "QUEST ($questCalls/5)"
            btnVip?.setBackgroundColor(Color.parseColor("#3B82F6"))
            btnVip?.setTextColor(Color.WHITE)
        } else {
            btnVip?.text = "👑 GO VIP"
            btnVip?.setBackgroundColor(Color.parseColor("#CA8A04"))
            btnVip?.setTextColor(Color.BLACK)
        }

        if (qualifiedCalls >= 20) {
            btnAdvanced?.text = "ADVANCED (TAP TO CALL)"
            btnAdvanced?.setBackgroundColor(Color.parseColor("#16A34A"))
            btnAdvanced?.setTextColor(Color.WHITE)
        } else {
            btnAdvanced?.text = "🔒 ADVANCED ($qualifiedCalls/20)"
        }

        if (lastCallerPeerId.isNotEmpty() && !reconnectConsumed) {
            btnReconnectLast?.visibility = View.VISIBLE
            btnReconnectLast?.bringToFront()
            btnDashboardReportLast?.visibility = View.VISIBLE
            btnDashboardReportLast?.bringToFront()
        } else {
            btnReconnectLast?.visibility = View.GONE
            btnDashboardReportLast?.visibility = View.GONE
        }
    }

    private fun showLayout(activeLayout: View?) {
        val dashboardScroll = layoutDashboard?.parent as? View
        dashboardScroll?.visibility = if (activeLayout == layoutDashboard) View.VISIBLE else View.GONE
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
                        logEvent("AutoMute", "Microphone muted after 30s in background")
                    }
                }
                backgroundAutoMuteRunnable?.let { mainHandler.postDelayed(it, 30000L) }
                logEvent("AutoMute", "30s auto-mute timer started")
            } else {
                logEvent("PowerKey", "Screen off via power button - keeping microphone active")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        backgroundAutoMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        if (isCallInProgress && WebRtcAudioClient.isMuted) {
            WebRtcAudioClient.setMuted(false)
            btnMute?.text = "🎤"
            logEvent("AutoMute", "Microphone unmuted upon app foreground")
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
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                }
            })
        } catch (e: Throwable) {}
    }

    private fun showRewardedAd() {
        if (rewardedAd != null) {
            rewardedAd?.show(this) { _ ->
                val currentCoins = prefs.getInt("talk_coins", 0) + 2
                prefs.edit().putInt("talk_coins", currentCoins).apply()
                refreshDashboardUI()
                Toast.makeText(this, "+2 Talk Coins added!", Toast.LENGTH_SHORT).show()
                logEvent("Coins", "+2 Coins added via Rewarded Ad")
                loadRewardedAd()
            }
        } else {
            Toast.makeText(this, "Ad is loading, please try in a moment...", Toast.LENGTH_SHORT).show()
            loadRewardedAd()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

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
