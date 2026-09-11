package com.englishtalk.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.cardview.widget.CardView
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

    // Low-Latency Audio Engine via SoundPool (Rule 41)
    private var soundPool: SoundPool? = null
    private var soundRadarId: Int = 0
    private var soundWarningId: Int = 0
    private var activeRadarStreamId: Int = 0
    private var isRadarPulsing = false
    private var isSoundPoolLoaded = false

    private val radarAudioRunnable = object : Runnable {
        override fun run() {
            if (isRadarPulsing) {
                try {
                    if (isSoundPoolLoaded && soundRadarId != 0) {
                        activeRadarStreamId = soundPool?.play(soundRadarId, 0.75f, 0.75f, 1, 0, 1.0f) ?: 0
                    }
                } catch (e: Throwable) {}
                mainHandler.postDelayed(this, 1100L)
            }
        }
    }

    // Layout Containers
    private var layoutGenderOnboarding: View? = null
    private var scrollDashboard: View? = null
    private var layoutDashboard: View? = null
    private var layoutLanguages: View? = null
    private var layoutSearching: View? = null
    private var layoutCall: View? = null

    // Exact Image 1 Card Elements (Rule 34)
    private var btnSelectMale: CardView? = null
    private var btnSelectFemale: CardView? = null
    private var btnSelectOther: CardView? = null
    private var lipMale: CardView? = null
    private var lipFemale: CardView? = null
    private var lipOther: CardView? = null
    private var cardConfirmGender: CardView? = null
    private var btnConfirmGender: Button? = null

    private var tvOnboardingTitle: TextView? = null
    private var tvOnboardingSubtitle: TextView? = null
    private var tvMaleLabel: TextView? = null
    private var tvFemaleLabel: TextView? = null
    private var tvOtherLabel: TextView? = null

    private var tempSelectedGender: String? = null

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
    private var cardReconnectLast: View? = null
    private var btnDashboardReportLast: Button? = null
    private var cardDashboardReportLast: View? = null
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
    private var ivMuteIcon: ImageView? = null
    private var tvMuteLabel: TextView? = null
    private var cardBtnMute: CardView? = null

    private var btnSpeaker: Button? = null
    private var ivSpeakerIcon: ImageView? = null
    private var tvSpeakerLabel: TextView? = null
    private var cardBtnSpeaker: CardView? = null

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

    // Intent-Aware Mute State (Rule 15 Bugfix)
    private var isMutedByUser = false

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
                    runOnUiThread {
                        playFourteenMinuteAlertSound()
                        showCallExtensionDialog()
                    }
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

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
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

        prefs = getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)

        updateWindowAppearanceForCurrentScreen()

        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        initSoundPool()

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
        applyModernTypographyOverride()
        setupListeners()
        setupRegionalLanguageButtons()

        MobileAds.initialize(this) {
            runOnUiThread {
                setupBannerAd()
                loadRewardedAd()
            }
        }

        try {
            SignalingClient.init(applicationContext)
            SignalingClient.setListener(this)
            SignalingClient.connect()
            WebRtcAudioClient.init(applicationContext)
        } catch (e: Throwable) {
            logEvent("Init-ERR", "Signaling/WebRTC init: ${e.message}")
        }

        checkAndEnforceGenderSelection()
        refreshDashboardUI()
        checkPermissions()
        logEvent("SYS", "App initialized successfully with SoundPool engine")
    }

    private fun updateWindowAppearanceForCurrentScreen() {
        val savedGender = prefs.getString("user_gender", "NOT_SET") ?: "NOT_SET"
        val isOnboardingVisible = (savedGender == "NOT_SET")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

            if (isOnboardingVisible) {
                window.statusBarColor = Color.parseColor("#F4F7FB")
            } else {
                window.statusBarColor = Color.parseColor("#F8F9FA")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    private fun applyModernTypographyOverride() {
        try {
            var boldTypeface: Typeface? = null
            var mediumTypeface: Typeface? = null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    boldTypeface = resources.getFont(R.font.roboto_bold)
                    mediumTypeface = resources.getFont(R.font.roboto_medium)
                }
            } catch (e: Throwable) {}

            if (boldTypeface == null) {
                boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            if (mediumTypeface == null) {
                mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            tvOnboardingTitle?.typeface = boldTypeface
            tvOnboardingSubtitle?.typeface = mediumTypeface
            tvMaleLabel?.typeface = boldTypeface
            tvFemaleLabel?.typeface = boldTypeface
            tvOtherLabel?.typeface = boldTypeface
            btnConfirmGender?.typeface = boldTypeface
            logEvent("Typography", "Explicit Typeface bound to onboarding views")
        } catch (e: Throwable) {
            logEvent("Typography-ERR", "Could not apply typeface override: ${e.message}")
        }
    }

    private fun initSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool?.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) {
                    isSoundPoolLoaded = true
                }
            }

            soundRadarId = soundPool?.load(this, R.raw.search_radar, 1) ?: 0
            soundWarningId = soundPool?.load(this, R.raw.call_warning, 1) ?: 0
            logEvent("Audio", "SoundPool initialized with custom resources")
        } catch (e: Throwable) {
            logEvent("Audio-ERR", "SoundPool init failed: ${e.message}")
        }
    }

    private fun startRadarPulseSound() {
        if (!isRadarPulsing) {
            isRadarPulsing = true
            mainHandler.removeCallbacks(radarAudioRunnable)
            mainHandler.post(radarAudioRunnable)
        }
    }

    private fun stopRadarPulseSound() {
        isRadarPulsing = false
        mainHandler.removeCallbacks(radarAudioRunnable)
        if (activeRadarStreamId != 0) {
            try {
                soundPool?.stop(activeRadarStreamId)
            } catch (e: Throwable) {}
            activeRadarStreamId = 0
        }
    }

    private fun playFourteenMinuteAlertSound() {
        try {
            if (isSoundPoolLoaded && soundWarningId != 0) {
                soundPool?.play(soundWarningId, 0.9f, 0.9f, 2, 0, 1.0f)
                logEvent("Audio", "Played custom 14-minute call warning sound")
            }
        } catch (e: Throwable) {}
    }

    private fun checkAndEnforceGenderSelection() {
        val savedGender = prefs.getString("user_gender", "NOT_SET") ?: "NOT_SET"
        updateWindowAppearanceForCurrentScreen()

        if (savedGender == "NOT_SET") {
            layoutGenderOnboarding?.visibility = View.VISIBLE
            scrollDashboard?.visibility = View.GONE
            layoutBannerAd?.visibility = View.GONE

            btnConfirmGender?.isEnabled = false
            btnConfirmGender?.text = "CONTINUE TO DASHBOARD →"
            cardConfirmGender?.setCardBackgroundColor(Color.parseColor("#CBD5E1"))
            btnConfirmGender?.setTextColor(Color.parseColor("#94A3B8"))

            setupOnboardingGenderListeners()
        } else {
            layoutGenderOnboarding?.visibility = View.GONE
            scrollDashboard?.visibility = View.VISIBLE
            layoutBannerAd?.visibility = View.VISIBLE
        }
    }

    private fun setupOnboardingGenderListeners() {
        fun updateGenderCardSelection(selectedGender: String) {
            tempSelectedGender = selectedGender

            // Male Selection
            if (selectedGender == "MALE") {
                btnSelectMale?.setCardBackgroundColor(Color.parseColor("#F0F6FF"))
                lipMale?.setCardBackgroundColor(Color.parseColor("#1D72FE"))
                tvMaleLabel?.setTextColor(Color.parseColor("#1D72FE"))
                btnSelectMale?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.setDuration(120)?.start()
            } else {
                btnSelectMale?.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                lipMale?.setCardBackgroundColor(Color.parseColor("#1D72FE"))
                tvMaleLabel?.setTextColor(Color.parseColor("#1E293B"))
                btnSelectMale?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
            }

            // Female Selection
            if (selectedGender == "FEMALE") {
                btnSelectFemale?.setCardBackgroundColor(Color.parseColor("#FFF0F3"))
                lipFemale?.setCardBackgroundColor(Color.parseColor("#FF6584"))
                tvFemaleLabel?.setTextColor(Color.parseColor("#FF6584"))
                btnSelectFemale?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.setDuration(120)?.start()
            } else {
                btnSelectFemale?.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                lipFemale?.setCardBackgroundColor(Color.parseColor("#FF6584"))
                tvFemaleLabel?.setTextColor(Color.parseColor("#1E293B"))
                btnSelectFemale?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
            }

            // Other Selection
            if (selectedGender == "OTHER") {
                btnSelectOther?.setCardBackgroundColor(Color.parseColor("#F6F3FF"))
                lipOther?.setCardBackgroundColor(Color.parseColor("#9C88FF"))
                tvOtherLabel?.setTextColor(Color.parseColor("#9C88FF"))
                btnSelectOther?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.setDuration(120)?.start()
            } else {
                btnSelectOther?.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                lipOther?.setCardBackgroundColor(Color.parseColor("#9C88FF"))
                tvOtherLabel?.setTextColor(Color.parseColor("#1E293B"))
                btnSelectOther?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
            }

            btnConfirmGender?.isEnabled = true
            cardConfirmGender?.setCardBackgroundColor(Color.parseColor("#00B894"))
            btnConfirmGender?.setTextColor(Color.WHITE)
        }

        btnSelectMale?.setOnClickListener { updateGenderCardSelection("MALE") }
        btnSelectFemale?.setOnClickListener { updateGenderCardSelection("FEMALE") }
        btnSelectOther?.setOnClickListener { updateGenderCardSelection("OTHER") }

        btnConfirmGender?.setOnClickListener {
            val finalChoice = tempSelectedGender ?: return@setOnClickListener
            prefs.edit().putString("user_gender", finalChoice).apply()
            logEvent("Profile", "Gender permanently locked as $finalChoice via 1:1 Image 1 Layout")

            layoutGenderOnboarding?.visibility = View.GONE
            scrollDashboard?.visibility = View.VISIBLE
            layoutBannerAd?.visibility = View.VISIBLE
            updateWindowAppearanceForCurrentScreen()
            refreshDashboardUI()
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
        layoutGenderOnboarding = findViewById(R.id.layoutGenderOnboarding)
        scrollDashboard = findViewById(R.id.scrollDashboard)
        layoutDashboard = findViewById(R.id.layoutDashboard)
        layoutLanguages = findViewById(R.id.layoutLanguages)
        layoutSearching = findViewById(R.id.layoutSearching)
        layoutCall = findViewById(R.id.layoutCall)

        btnSelectMale = findViewById(R.id.btnSelectMale)
        btnSelectFemale = findViewById(R.id.btnSelectFemale)
        btnSelectOther = findViewById(R.id.btnSelectOther)
        lipMale = findViewById(R.id.lipMale)
        lipFemale = findViewById(R.id.lipFemale)
        lipOther = findViewById(R.id.lipOther)
        cardConfirmGender = findViewById(R.id.cardConfirmGender)
        btnConfirmGender = findViewById(R.id.btnConfirmGender)

        tvOnboardingTitle = findViewById(R.id.tvOnboardingTitle)
        tvOnboardingSubtitle = findViewById(R.id.tvOnboardingSubtitle)
        tvMaleLabel = findViewById(R.id.tvMaleLabel)
        tvFemaleLabel = findViewById(R.id.tvFemaleLabel)
        tvOtherLabel = findViewById(R.id.tvOtherLabel)

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
        cardReconnectLast = findViewById(R.id.cardReconnectLast)
        btnDashboardReportLast = findViewById(R.id.btnDashboardReportLast)
        cardDashboardReportLast = findViewById(R.id.cardDashboardReportLast)
        btnVip = findViewById(R.id.btnVip)
        switchFemaleFilter = findViewById(R.id.switchFemaleFilter)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)

        tvSearchingStatus = findViewById(R.id.tvSearchingStatus)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)

        tvCallPartnerName = findViewById(R.id.tvCallPartnerName)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        btnMute = findViewById(R.id.btnMute)
        ivMuteIcon = findViewById(R.id.ivMuteIcon)
        tvMuteLabel = findViewById(R.id.tvMuteLabel)
        cardBtnMute = findViewById(R.id.cardBtnMute)

        btnSpeaker = findViewById(R.id.btnSpeaker)
        ivSpeakerIcon = findViewById(R.id.ivSpeakerIcon)
        tvSpeakerLabel = findViewById(R.id.tvSpeakerLabel)
        cardBtnSpeaker = findViewById(R.id.cardBtnSpeaker)

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
            isMutedByUser = newMuteState
            WebRtcAudioClient.setMuted(newMuteState)
            updateMuteButtonVisual(newMuteState)
            logEvent("Mic", "User manually set mute to: $newMuteState")
        }

        btnSpeaker?.setOnClickListener {
            val newSpeakerState = !audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = newSpeakerState
            updateSpeakerButtonVisual(newSpeakerState)
        }

        btnReconnectLast?.setOnClickListener {
            if (lastCallerPeerId.isNotEmpty() && !reconnectConsumed) {
                initiateReconnectFlow()
            } else {
                cardReconnectLast?.visibility = View.GONE
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

    private fun updateMuteButtonVisual(isMuted: Boolean) {
        runOnUiThread {
            if (isMuted) {
                ivMuteIcon?.setImageResource(R.drawable.ic_mic_off_outline)
                cardBtnMute?.setCardBackgroundColor(Color.parseColor("#FDEDE8"))
                tvMuteLabel?.text = "Muted"
                tvMuteLabel?.setTextColor(Color.parseColor("#E8785A"))
            } else {
                ivMuteIcon?.setImageResource(R.drawable.ic_mic_outline)
                cardBtnMute?.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                tvMuteLabel?.text = "Mute"
                tvMuteLabel?.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun updateSpeakerButtonVisual(isSpeakerOn: Boolean) {
        runOnUiThread {
            if (isSpeakerOn) {
                cardBtnSpeaker?.setCardBackgroundColor(Color.parseColor("#E6F1FB"))
                tvSpeakerLabel?.setTextColor(Color.parseColor("#185FA5"))
            } else {
                cardBtnSpeaker?.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                tvSpeakerLabel?.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    // =========================================================================
    // MODULAR SQUIRCLE DIALOG ENGINE WITH PIXEL-PERFECT VECTOR ASSETS
    // =========================================================================
    private fun showSquircleModalDialog(
        badgeIconRes: Int,
        badgeBgColor: String,
        title: String,
        bodyText: String? = null,
        customContentView: View? = null,
        primaryBtnText: String,
        onPrimaryClick: (() -> Unit)? = null,
        secondaryBtnText: String? = null,
        onSecondaryClick: (() -> Unit)? = null,
        isCancelable: Boolean = true,
        primaryBtnColor: String = "#639922",
        primaryTextColor: String = "#FFFFFF"
    ) {
        runOnUiThread {
            val dialogBuilder = AlertDialog.Builder(this)
            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                val padH = dpToPx(24f)
                val padV = dpToPx(24f)
                setPadding(padH, padV, padH, padV)
            }

            // Top Header: Squircle Badge + Title
            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(16f)
                }
            }

            // Squircle Icon Badge (44dp x 44dp, 14dp radius)
            val badgeCard = CardView(this).apply {
                radius = dpToPx(14f).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor(badgeBgColor))
                val size = dpToPx(44f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(14f)
                }
            }

            val ivBadge = ImageView(this).apply {
                setImageResource(badgeIconRes)
                val iconSize = dpToPx(24f)
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER
                }
            }
            badgeCard.addView(ivBadge)
            headerLayout.addView(badgeCard)

            val tvTitle = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            headerLayout.addView(tvTitle)
            dialogView.addView(headerLayout)

            // Body Text (if provided)
            if (!bodyText.isNullOrEmpty()) {
                val tvBody = TextView(this).apply {
                    text = bodyText
                    setTextColor(Color.parseColor("#475569"))
                    textSize = 14f
                    setLineSpacing(dpToPx(3f).toFloat(), 1.2f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(18f)
                    }
                }
                dialogView.addView(tvBody)
            }

            // Custom Content Area (Checklist rows or Radio options)
            if (customContentView != null) {
                dialogView.addView(customContentView)
            }

            // Action Buttons Row
            val buttonContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(10f)
                }
            }

            var dialog: AlertDialog? = null

            // Secondary Pill Button
            if (secondaryBtnText != null) {
                val cardSecondary = CardView(this).apply {
                    radius = dpToPx(18f).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(Color.parseColor("#F1EFE8"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = dpToPx(10f)
                    }
                }

                val btnSecondary = Button(this).apply {
                    text = secondaryBtnText
                    setTextColor(Color.parseColor("#5F5E5A"))
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundColor(Color.TRANSPARENT)
                    isAllCaps = false
                    val padH = dpToPx(20f)
                    val padV = dpToPx(10f)
                    setPadding(padH, padV, padH, padV)
                    minHeight = 0
                    minWidth = 0
                    setOnClickListener {
                        dialog?.dismiss()
                        onSecondaryClick?.invoke()
                    }
                }
                cardSecondary.addView(btnSecondary)
                buttonContainer.addView(cardSecondary)
            }

            // Primary Pill Button
            val cardPrimary = CardView(this).apply {
                radius = dpToPx(18f).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor(primaryBtnColor))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val btnPrimary = Button(this).apply {
                text = primaryBtnText
                setTextColor(Color.parseColor(primaryTextColor))
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(Color.TRANSPARENT)
                isAllCaps = false
                val padH = dpToPx(22f)
                val padV = dpToPx(10f)
                setPadding(padH, padV, padH, padV)
                minHeight = 0
                minWidth = 0
                setOnClickListener {
                    dialog?.dismiss()
                    onPrimaryClick?.invoke()
                }
            }
            cardPrimary.addView(btnPrimary)
            buttonContainer.addView(cardPrimary)

            dialogView.addView(buttonContainer)

            // Outer Card (22dp corner radius, soft elevation)
            val outerCard = CardView(this).apply {
                radius = dpToPx(22f).toFloat()
                cardElevation = dpToPx(6f).toFloat()
                setCardBackgroundColor(Color.WHITE)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(dialogView)
            }

            dialog = dialogBuilder.setView(outerCard).create().apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setDimAmount(0.40f)
                setCancelable(isCancelable)
                show()
            }
        }
    }

    // 1. VIP Locked Notice (Image 14113 bottom)
    private fun showFemaleFilterLockedDialog() {
        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_lock,
            badgeBgColor = "#FCE7F0",
            title = "VIP feature",
            bodyText = "\"Talk to female only\" is a VIP feature.\nGet a VIP membership to unlock it.",
            primaryBtnText = "Get VIP",
            onPrimaryClick = { showPureVipDialog() },
            secondaryBtnText = "Ok",
            primaryBtnColor = "#FAC775",
            primaryTextColor = "#412402"
        )
    }

    // 2. VIP Membership Screen (Image 14113 top)
    private fun showPureVipDialog() {
        val benefitsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16f)
            }
        }

        val perks = listOf(
            "Unlimited female-only filtering",
            "Priority matching",
            "Ad-free conversations"
        )

        for (perk in perks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8f)
                }
            }

            val tvCheck = TextView(this).apply {
                text = "✓ "
                setTextColor(Color.parseColor("#1D9E75"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(tvCheck)

            val tvPerk = TextView(this).apply {
                text = perk
                setTextColor(Color.parseColor("#475569"))
                textSize = 13f
            }
            row.addView(tvPerk)
            benefitsLayout.addView(row)
        }

        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_crown,
            badgeBgColor = "#FAC775",
            title = "VIP Membership",
            bodyText = "Upgrade to VIP for unlimited Talk to Female filtering, priority matching, and an ad-free conversation experience.",
            customContentView = benefitsLayout,
            primaryBtnText = "Subscribe now",
            onPrimaryClick = {
                Toast.makeText(this, "VIP Store coming soon!", Toast.LENGTH_SHORT).show()
            },
            secondaryBtnText = "Close",
            primaryBtnColor = "#FAC775",
            primaryTextColor = "#412402"
        )
    }

    // 3. Community Practice Quest Progress (Image 14150_2)
    private fun showPracticeQuestProgressDialog() {
        val shared = prefs.getBoolean("has_shared_app", false)
        val calls = prefs.getInt("female_pass_qualified_calls", 0)

        val questLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(14f)
            }
        }

        val tvIntro = TextView(this).apply {
            text = "Complete these goals to unlock 1 free female match pass:"
            setTextColor(Color.parseColor("#475569"))
            textSize = 13.5f
            setLineSpacing(dpToPx(2f).toFloat(), 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12f)
            }
        }
        questLayout.addView(tvIntro)

        // Goal 1: Share the App Card
        val cardGoal1 = CardView(this).apply {
            radius = dpToPx(14f).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#F8F9FA"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8f)
            }
        }

        val row1 = RelativeLayout(this).apply {
            val padH = dpToPx(16f)
            val padV = dpToPx(12f)
            setPadding(padH, padV, padH, padV)
        }

        val tvGoal1Label = TextView(this).apply {
            text = "Share the app"
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val params = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params
        }
        row1.addView(tvGoal1Label)

        val cardStatus1 = CardView(this).apply {
            radius = dpToPx(10f).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(if (shared) Color.parseColor("#EAF3DE") else Color.parseColor("#FAC775"))
            val params = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params
        }

        val tvStatus1 = TextView(this).apply {
            text = if (shared) "Done" else "To do"
            setTextColor(if (shared) Color.parseColor("#173404") else Color.parseColor("#412402"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val padH = dpToPx(12f)
            val padV = dpToPx(4f)
            setPadding(padH, padV, padH, padV)
        }
        cardStatus1.addView(tvStatus1)
        row1.addView(cardStatus1)
        cardGoal1.addView(row1)
        questLayout.addView(cardGoal1)

        // Goal 2: Complete 5 English Calls Card
        val cardGoal2 = CardView(this).apply {
            radius = dpToPx(14f).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#F8F9FA"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12f)
            }
        }

        val row2 = RelativeLayout(this).apply {
            val padH = dpToPx(16f)
            val padV = dpToPx(12f)
            setPadding(padH, padV, padH, padV)
        }

        val tvGoal2Label = TextView(this).apply {
            text = "Complete 5 English calls"
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val params = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params
        }
        row2.addView(tvGoal2Label)

        val tvCallsCount = TextView(this).apply {
            text = "$calls/5"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            val params = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params
        }
        row2.addView(tvCallsCount)
        cardGoal2.addView(row2)
        questLayout.addView(cardGoal2)

        // Footnote
        val tvFootnote = TextView(this).apply {
            text = "Proves serious practice intent and protects community learners."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setLineSpacing(dpToPx(2f).toFloat(), 1.15f)
        }
        questLayout.addView(tvFootnote)

        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_target,
            badgeBgColor = "#FCE7F0",
            title = "Community practice quest",
            customContentView = questLayout,
            primaryBtnText = "Share app",
            onPrimaryClick = {
                triggerQuestShareIntent()
            },
            secondaryBtnText = "Close",
            primaryBtnColor = "#1D9E75",
            primaryTextColor = "#E1F3EC"
        )
    }

    // 4. 10-Minute Milestone Celebration (Image 14151_2)
    private fun showMilestoneQuestOfferDialog() {
        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_trophy,
            badgeBgColor = "#FAC775",
            title = "10-minute milestone!",
            bodyText = "Amazing dedication! You just completed a 10+ minute English conversation.\n\nAccept the Community Practice Challenge to earn 1 free female match pass?",
            primaryBtnText = "Accept challenge",
            onPrimaryClick = {
                prefs.edit().putBoolean("is_quest_active", true).putInt("female_pass_qualified_calls", 0).apply()
                refreshDashboardUI()
                logEvent("Quest", "User accepted Community Practice Quest")
                showPracticeQuestProgressDialog()
            },
            secondaryBtnText = "Maybe later",
            primaryBtnColor = "#639922",
            primaryTextColor = "#EAF3DE"
        )
    }

    // 5. Call Limit Warning with Rewarded Ad Extension (Image 14153_3)
    private fun showCallExtensionDialog() {
        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_clock,
            badgeBgColor = "#FDF1E1",
            title = "Call limit warning",
            bodyText = "This call will reach the 15-minute limit soon. Watch a short ad to extend for +5 minutes?",
            primaryBtnText = "Extend +5 mins",
            onPrimaryClick = {
                if (rewardedAd != null) {
                    rewardedAd?.show(this) { _ ->
                        isCallTimerExtended = true
                        Toast.makeText(this, "Call extended by +5 minutes!", Toast.LENGTH_SHORT).show()
                        logEvent("Timer", "Call extended by 5 minutes via Rewarded Ad")
                        loadRewardedAd()
                    }
                } else {
                    isCallTimerExtended = true
                    Toast.makeText(this, "Call extended by +5 minutes!", Toast.LENGTH_SHORT).show()
                    logEvent("Timer", "Extended by 5m (Ad preloading fallback)")
                    loadRewardedAd()
                }
            },
            secondaryBtnText = "Dismiss",
            isCancelable = false,
            primaryBtnColor = "#639922",
            primaryTextColor = "#EAF3DE"
        )
    }

    // 6. 0 Talk Coins Dialog (Image 14152_2)
    private fun showZeroCoinsDialog() {
        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_coin,
            badgeBgColor = "#FDF1E1",
            title = "0 Talk Coins",
            bodyText = "Joining regional language pools requires at least 1 Talk Coin. Earn coins by practicing English for 1+ minute or watching a quick video ad.",
            primaryBtnText = "Watch ad (+2)",
            onPrimaryClick = { showRewardedAd() },
            secondaryBtnText = "Practice English",
            primaryBtnColor = "#FAC775",
            primaryTextColor = "#412402"
        )
    }

    // 7. Report Caller Dialog with Styled Radio Rows (Image 14157)
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
            arrayOf("Partner is not female (Wrong gender)", "Harassment / Abuse", "Spam / Commercial ads")
        } else {
            arrayOf("Harassment / Abuse", "Spam / Commercial ads", "Inappropriate speech")
        }

        var selectedIndex = 0

        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16f)
            }
        }

        val rowViews = mutableListOf<LinearLayout>()
        val radioDots = mutableListOf<TextView>()

        for (i in reportOptions.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = dpToPx(16f)
                val padV = dpToPx(14f)
                setPadding(padH, padV, padH, padV)
                val bg = GradientDrawable().apply {
                    cornerRadius = dpToPx(14f).toFloat()
                    setColor(Color.parseColor("#F8F9FA"))
                    if (i == 0) {
                        setStroke(dpToPx(2f), Color.parseColor("#5DCAA5"))
                    } else {
                        setStroke(dpToPx(1f), Color.parseColor("#E2E8F0"))
                    }
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(10f)
                }
            }

            val tvRadioDot = TextView(this).apply {
                text = if (i == 0) "◉ " else "○ "
                setTextColor(if (i == 0) Color.parseColor("#5DCAA5") else Color.parseColor("#94A3B8"))
                textSize = 17f
                setPadding(0, 0, dpToPx(10f), 0)
            }
            row.addView(tvRadioDot)
            radioDots.add(tvRadioDot)

            val tvOption = TextView(this).apply {
                text = reportOptions[i]
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(tvOption)

            row.setOnClickListener {
                selectedIndex = i
                for (j in rowViews.indices) {
                    val isSelected = (j == selectedIndex)
                    val rowBg = GradientDrawable().apply {
                        cornerRadius = dpToPx(14f).toFloat()
                        setColor(Color.parseColor("#F8F9FA"))
                        if (isSelected) {
                            setStroke(dpToPx(2f), Color.parseColor("#5DCAA5"))
                        } else {
                            setStroke(dpToPx(1f), Color.parseColor("#E2E8F0"))
                        }
                    }
                    rowViews[j].background = rowBg
                    radioDots[j].text = if (isSelected) "◉ " else "○ "
                    radioDots[j].setTextColor(if (isSelected) Color.parseColor("#5DCAA5") else Color.parseColor("#94A3B8"))
                }
            }

            rowViews.add(row)
            optionsContainer.addView(row)
        }

        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_flag,
            badgeBgColor = "#FCE7F0",
            title = "Report caller",
            customContentView = optionsContainer,
            primaryBtnText = "Submit report",
            onPrimaryClick = {
                val selectedText = reportOptions[selectedIndex]
                if (selectedText.contains("not female", ignoreCase = true)) {
                    SignalingClient.reportGenderMismatch(targetPeerId)
                    logEvent("Report", "Reported gender mismatch for: $targetPeerId")
                } else {
                    SignalingClient.reportUser(targetPeerId)
                    logEvent("Report", "Reported violation ($selectedText) for: $targetPeerId")
                }

                hasReportedLastCaller = true
                Toast.makeText(this, "Report submitted. Thank you for keeping our community safe.", Toast.LENGTH_LONG).show()
            },
            secondaryBtnText = "Cancel",
            primaryBtnColor = "#E8785A",
            primaryTextColor = "#FDEDE8"
        )
    }

    private fun triggerGeneralAppShare() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Practice English speaking with real people! Download here: https://play.google.com/store/apps/details?id=$packageName")
            type = "text/plain"
        }
        logEvent("Share", "General App Share launched from dashboard")
        startActivity(Intent.createChooser(sendIntent, "Share App"))
    }

    private fun triggerQuestShareIntent() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Practice English speaking with real people! Download here: https://play.google.com/store/apps/details?id=$packageName")
            type = "text/plain"
        }
        prefs.edit().putBoolean("has_shared_app", true).apply()
        logEvent("Share", "Quest share verified - share flag saved")
        startActivity(Intent.createChooser(sendIntent, "Share App"))
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
                showSquircleModalDialog(
                    badgeIconRes = R.drawable.ic_dialog_trophy,
                    badgeBgColor = "#EAF3DE",
                    title = "Quest completed!",
                    bodyText = "Congratulations! You earned 1 free female match pass. The female filter is now active for your next English practice call.",
                    primaryBtnText = "Start practice",
                    primaryBtnColor = "#639922",
                    primaryTextColor = "#EAF3DE"
                )
                logEvent("Quest", "Granted 1 Female Match Pass to user")
            }
        }
    }

    private fun showActivePassDialog() {
        showSquircleModalDialog(
            badgeIconRes = R.drawable.ic_dialog_crown,
            badgeBgColor = "#FAC775",
            title = "Match pass active",
            bodyText = "Your free female match pass is ready!\n\nEnsure 'Talk to female only' is switched ON and tap Beginner or Advanced to start matching.",
            primaryBtnText = "Got it",
            primaryBtnColor = "#FAC775",
            primaryTextColor = "#412402"
        )
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
            setTextColor(Color.parseColor("#97C459"))
            setBackgroundColor(Color.parseColor("#2C2C2A"))
            val pad = dpToPx(16f)
            setPadding(pad, pad, pad, pad)
            textSize = 11f
            typeface = Typeface.MONOSPACE
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

    private fun startSearchingForegroundService() {
        try {
            val serviceIntent = Intent(this, CallService::class.java).apply {
                action = "START_SEARCHING"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            logEvent("Service", "Foreground service activated for search screen")
        } catch (e: Throwable) {
            logEvent("Service-ERR", "Could not start search foreground service: ${e.message}")
        }
    }

    private fun stopSearchingForegroundService() {
        try {
            stopService(Intent(this, CallService::class.java))
            logEvent("Service", "Foreground service stopped")
        } catch (e: Throwable) {}
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

        startSearchingForegroundService()
        startRadarPulseSound()

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
        stopRadarPulseSound()
        stopSearchingForegroundService()
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

        startSearchingForegroundService()
        startRadarPulseSound()

        val reconnectLevel = if (lastCallerLanguage == "ENGLISH") currentLevel else "Native"
        SignalingClient.requestReconnect(lastCallerPeerId, reconnectLevel)
        logEvent("Reconnect", "Calling peer: $lastCallerPeerId in pool: $lastCallerLanguage")
    }

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean) {
        runOnUiThread {
            stopRadarPulseSound()
            isCurrentSessionReconnect = isReconnect || isCurrentSessionReconnect

            if (isCurrentSessionReconnect) {
                lastCallerPeerId = ""
                reconnectConsumed = true
                cardReconnectLast?.visibility = View.GONE
                cardDashboardReportLast?.visibility = View.GONE
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

            isMutedByUser = false

            showLayout(layoutCall)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            btnInCallReport?.visibility = if (isCurrentCallFemaleFiltered) View.VISIBLE else View.GONE

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            WebRtcAudioClient.setMuted(false)
            updateMuteButtonVisual(false)
            updateSpeakerButtonVisual(false)

            tvCallPartnerName?.text = "Connected"
            tvCallTimer?.text = "00:00"

            try {
                val serviceIntent = Intent(this, CallService::class.java).apply {
                    action = "START_CALL"
                }
                startService(serviceIntent)
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
            stopRadarPulseSound()
            Toast.makeText(this, "Partner is unavailable for reconnect.", Toast.LENGTH_SHORT).show()
            cancelSearchAndReturn()
        }
    }

    override fun onServerCooldown(remainingSeconds: Long) {
        runOnUiThread {
            stopRadarPulseSound()
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
            showSquircleModalDialog(
                badgeIconRes = R.drawable.ic_dialog_clock,
                badgeBgColor = "#FAC775",
                title = "Female queue busy",
                bodyText = "No female partner is immediately available right now.\n\nWould you like to wait 30 seconds more or connect with anyone now without losing your pass?",
                primaryBtnText = "Wait +30s",
                onPrimaryClick = {
                    SignalingClient.extendVipWait()
                    tvSearchingStatus?.text = "Waiting for next available female partner..."
                },
                secondaryBtnText = "Connect with anyone",
                onSecondaryClick = {
                    isUpdatingToggleProgrammatically = true
                    switchFemaleFilter?.isChecked = false
                    isUpdatingToggleProgrammatically = false
                    wasSearchingWithFemalePass = false
                    isCurrentCallFemaleFiltered = false

                    SignalingClient.fallbackToGeneral()
                    tvSearchingStatus?.text = "Connecting with next available peer..."
                },
                isCancelable = false,
                primaryBtnColor = "#FAC775",
                primaryTextColor = "#412402"
            )
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
        isMutedByUser = false
        mainHandler.removeCallbacks(callTimerRunnable)

        stopSearchingForegroundService()
        stopRadarPulseSound()

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
            cardReconnectLast?.visibility = View.GONE
            cardDashboardReportLast?.visibility = View.GONE
            logEvent("Reconnect", "Single-use reconnect session completed. Button locked and destroyed.")
        } else if (lastCallerPeerId.isNotEmpty() && !reconnectConsumed) {
            cardReconnectLast?.visibility = View.VISIBLE
            cardReconnectLast?.bringToFront()
            cardDashboardReportLast?.visibility = View.VISIBLE
            cardDashboardReportLast?.bringToFront()
            logEvent("Reconnect", "Single-use reconnect token armed for pool: $lastCallerLanguage (Peer: $lastCallerPeerId)")
        } else {
            cardReconnectLast?.visibility = View.GONE
            cardDashboardReportLast?.visibility = View.GONE
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

        tvTalkCoinsBadge?.text = "🪙 $coins"
        tvStreakVal?.text = "$streak"
        tvTotalMinutesVal?.text = "${practiceMins}m"
        tvTotalCallsVal?.text = "$totalCalls"

        if (hasPass) {
            btnVip?.text = "👑 PASS"
        } else if (isQuestActive) {
            btnVip?.text = "👑 $questCalls/5"
        } else {
            btnVip?.text = "👑 VIP"
        }

        if (qualifiedCalls >= 20) {
            btnAdvanced?.text = "Advanced · tap to call"
            btnAdvanced?.setTextColor(Color.parseColor("#1E293B"))
        } else {
            btnAdvanced?.text = "Advanced ($qualifiedCalls/20)"
            btnAdvanced?.setTextColor(Color.parseColor("#5F5E5A"))
        }

        if (lastCallerPeerId.isNotEmpty() && !reconnectConsumed) {
            cardReconnectLast?.visibility = View.VISIBLE
            cardReconnectLast?.bringToFront()
            cardDashboardReportLast?.visibility = View.VISIBLE
            cardDashboardReportLast?.bringToFront()
        } else {
            cardReconnectLast?.visibility = View.GONE
            cardDashboardReportLast?.visibility = View.GONE
        }
    }

    private fun showLayout(activeLayout: View?) {
        scrollDashboard?.visibility = if (activeLayout == layoutDashboard) View.VISIBLE else View.GONE
        layoutLanguages?.visibility = if (activeLayout == layoutLanguages) View.VISIBLE else View.GONE
        layoutSearching?.visibility = if (activeLayout == layoutSearching) View.VISIBLE else View.GONE
        layoutCall?.visibility = if (activeLayout == layoutCall) View.VISIBLE else View.GONE

        layoutBannerAd?.visibility = if (activeLayout == layoutDashboard || activeLayout == layoutLanguages) View.VISIBLE else View.GONE

        updateWindowAppearanceForCurrentScreen()
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
                        updateMuteButtonVisual(true)
                        logEvent("AutoMute", "Microphone auto-muted after 30s in background")
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

        SignalingClient.ensureActiveConnection()
        logEvent("SYS", "onResume: Active connection probe completed")

        if (isCallInProgress) {
            if (!isMutedByUser && WebRtcAudioClient.isMuted) {
                WebRtcAudioClient.setMuted(false)
                updateMuteButtonVisual(false)
                logEvent("AutoMute", "Microphone restored upon returning to foreground")
            } else if (isMutedByUser) {
                WebRtcAudioClient.setMuted(true)
                updateMuteButtonVisual(true)
                logEvent("AutoMute", "Preserving manual mute state on resume")
            }
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

    override fun onDestroy() {
        stopRadarPulseSound()
        try {
            soundPool?.release()
            soundPool = null
        } catch (e: Throwable) {}
        super.onDestroy()
    }
}
