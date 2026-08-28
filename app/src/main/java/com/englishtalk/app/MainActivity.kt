package com.englishtalk.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.utils.AppLogger
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class MainActivity : Activity(), SignalingClient.SignalingListener {

    companion object {
        private const val PERMISSION_REQ_CODE = 2001
    }

    private lateinit var rootLayout: RelativeLayout
    private lateinit var layoutDashboard: LinearLayout
    private lateinit var layoutSearching: LinearLayout
    private lateinit var layoutConnected: LinearLayout

    private lateinit var btnTalkNow: Button
    private lateinit var btnCancelSearch: Button
    private lateinit var btnEndCall: ImageView
    private lateinit var btnMute: ImageView
    private lateinit var btnSpeaker: ImageView
    private lateinit var btnGoVip: Button
    private lateinit var switchFemaleOnly: Switch
    private lateinit var tvTimer: TextView
    private lateinit var tvPartnerLevel: TextView
    private lateinit var tvDiagnostics: TextView

    private lateinit var btnBeginner: Button
    private lateinit var btnIntermediate: Button
    private lateinit var btnAdvanced: Button

    private var selectedLevel = "Intermediate"
    private var isVip = false
    private var userGender = "Male"

    private var warningPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            AppLogger.log("FATAL", "${throwable.javaClass.simpleName}: ${throwable.message}")
        }

        try {
            MobileAds.initialize(this) {}
        } catch (e: Exception) {
            AppLogger.log("AdMob", "Init error: ${e.message}")
        }

        buildProgrammaticUI()
        setupListeners()
        setupDiagnosticsLogger()
        setupCallServiceCallbacks()

        SignalingClient.setListener(this)
        SignalingClient.connect()
    }

    private fun buildProgrammaticUI() {
        rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }

        // --- TOP BAR ---
        val topBar = RelativeLayout(this).apply {
            id = View.generateViewId()
            setPadding(40, 40, 40, 20)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
        }

        val appTitle = TextView(this).apply {
            text = "English Talk"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        btnGoVip = Button(this).apply {
            text = "👑 GO VIP"
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F59E0B"))
                cornerRadius = 20f
            }
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                110
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        topBar.addView(appTitle)
        topBar.addView(btnGoVip)
        rootLayout.addView(topBar)

        // --- BOTTOM CONTAINER ---
        val bottomContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        tvDiagnostics = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#050B14"))
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                260
            )
        }

        val adView = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = "ca-app-pub-3940256099942544/6300978111"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        bottomContainer.addView(tvDiagnostics)
        bottomContainer.addView(adView)
        rootLayout.addView(bottomContainer)

        try {
            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            AppLogger.log("AdView", "Ad load error: ${e.message}")
        }

        // --- DASHBOARD VIEW ---
        layoutDashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            visibility = View.VISIBLE
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.BELOW, topBar.id)
                addRule(RelativeLayout.ABOVE, bottomContainer.id)
            }
        }

        val tvLevelHeading = TextView(this).apply {
            text = "Choose Your English Level"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 50
            }
        }

        btnBeginner = createLevelButton("Beginner")
        btnIntermediate = createLevelButton("Intermediate")
        btnAdvanced = createLevelButton("Advanced")

        levelRow.addView(btnBeginner)
        levelRow.addView(btnIntermediate)
        levelRow.addView(btnAdvanced)

        val vipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 25, 30, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 60
            }
        }

        val tvFemaleVip = TextView(this).apply {
            text = "👩 Talk to Female Gender 👑 VIP"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        switchFemaleOnly = Switch(this)
        vipRow.addView(tvFemaleVip)
        vipRow.addView(switchFemaleOnly)

        btnTalkNow = Button(this).apply {
            text = "📞\n\nTALK NOW"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2563EB"))
            }
            layoutParams = LinearLayout.LayoutParams(400, 400)
        }

        layoutDashboard.addView(tvLevelHeading)
        layoutDashboard.addView(levelRow)
        layoutDashboard.addView(vipRow)
        layoutDashboard.addView(btnTalkNow)
        rootLayout.addView(layoutDashboard)

        // --- SEARCHING VIEW ---
        layoutSearching = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            visibility = View.GONE
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.BELOW, topBar.id)
                addRule(RelativeLayout.ABOVE, bottomContainer.id)
            }
        }

        val progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { bottomMargin = 40 }
        }

        val tvSearching = TextView(this).apply {
            text = "Searching for a conversation partner..."
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 60
            }
        }

        btnCancelSearch = Button(this).apply {
            text = "Cancel Search"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layoutSearching.addView(progress)
        layoutSearching.addView(tvSearching)
        layoutSearching.addView(btnCancelSearch)
        rootLayout.addView(layoutSearching)

        // --- CONNECTED VIEW ---
        layoutConnected = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            visibility = View.GONE
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.BELOW, topBar.id)
                addRule(RelativeLayout.ABOVE, bottomContainer.id)
            }
        }

        val ivUser = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_call)
            layoutParams = LinearLayout.LayoutParams(180, 180).apply { bottomMargin = 30 }
        }

        tvPartnerLevel = TextView(this).apply {
            text = "Connected (Intermediate Partner)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        tvTimer = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
                bottomMargin = 40
            }
        }

        val callControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnMute = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#334155"))
            }
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(130, 130).apply { rightMargin = 40 }
        }

        btnEndCall = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444"))
            }
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(160, 160).apply { rightMargin = 40 }
        }

        btnSpeaker = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#334155"))
            }
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(130, 130)
        }

        callControlRow.addView(btnMute)
        callControlRow.addView(btnEndCall)
        callControlRow.addView(btnSpeaker)

        layoutConnected.addView(ivUser)
        layoutConnected.addView(tvPartnerLevel)
        layoutConnected.addView(tvTimer)
        layoutConnected.addView(callControlRow)
        rootLayout.addView(layoutConnected)

        setContentView(rootLayout)
        selectLevel("Intermediate")
    }

    private fun createLevelButton(levelName: String): Button {
        return Button(this).apply {
            text = levelName
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 8
                rightMargin = 8
            }
        }
    }

    private fun setupListeners() {
        btnBeginner.setOnClickListener { selectLevel("Beginner") }
        btnIntermediate.setOnClickListener { selectLevel("Intermediate") }
        btnAdvanced.setOnClickListener { selectLevel("Advanced") }

        btnGoVip.setOnClickListener {
            isVip = true
            Toast.makeText(this, "VIP Access Granted! Unlimited calls & unlocked filters.", Toast.LENGTH_SHORT).show()
            AppLogger.log("VIP", "VIP status active")
        }

        switchFemaleOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isVip) {
                switchFemaleOnly.isChecked = false
                showVipRequiredDialog("Talk to Female Gender is a VIP feature.")
            }
        }

        btnTalkNow.setOnClickListener {
            checkPermissionsAndStart()
        }

        btnCancelSearch.setOnClickListener {
            SignalingClient.leaveQueue()
            showDashboard()
        }

        btnEndCall.setOnClickListener {
            endCurrentCall()
        }

        btnMute.setOnClickListener {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_TOGGLE_MUTE
            }
            startService(intent)
        }

        btnSpeaker.setOnClickListener {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_TOGGLE_SPEAKER
            }
            startService(intent)
        }
    }

    private fun selectLevel(level: String) {
        selectedLevel = level
        val activeColor = Color.parseColor("#2563EB")
        val idleColor = Color.parseColor("#1E293B")

        (btnBeginner.background as? GradientDrawable)?.setColor(if (level == "Beginner") activeColor else idleColor)
        (btnIntermediate.background as? GradientDrawable)?.setColor(if (level == "Intermediate") activeColor else idleColor)
        (btnAdvanced.background as? GradientDrawable)?.setColor(if (level == "Advanced") activeColor else idleColor)
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startSearchFlow()
        } else {
            requestPermissions(missingPermissions.toTypedArray(), PERMISSION_REQ_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSearchFlow()
            } else {
                Toast.makeText(this, "Microphone permission is required to talk", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSearchFlow() {
        showSearching()
        SignalingClient.joinQueue(
            level = selectedLevel,
            userGender = userGender,
            talkToFemaleOnly = switchFemaleOnly.isChecked,
            isVip = isVip
        )
    }

    private fun endCurrentCall() {
        val intent = Intent(this, CallService::class.java).apply {
            action = CallService.ACTION_END_CALL
        }
        startService(intent)
        showDashboard()
    }

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String) {
        mainHandler.post {
            showConnected(peerLevel)
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_START_CALL
                putExtra(CallService.EXTRA_ROOM_ID, roomId)
                putExtra(CallService.EXTRA_IS_INITIATOR, isInitiator)
                putExtra(CallService.EXTRA_PEER_LEVEL, peerLevel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        mainHandler.post {
            CallService.handleRemoteOffer(sdp)
        }
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        mainHandler.post {
            CallService.handleRemoteAnswer(sdp)
        }
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        mainHandler.post {
            CallService.handleRemoteIceCandidate(candidate)
        }
    }

    override fun onCallEnded() {
        mainHandler.post { showDashboard() }
    }

    private fun setupCallServiceCallbacks() {
        CallService.onAudioStateChanged = { muted, speaker ->
            mainHandler.post {
                btnMute.alpha = if (muted) 0.4f else 1.0f
                btnSpeaker.alpha = if (speaker) 1.0f else 0.4f
            }
        }

        CallService.onCallEndedByRemote = {
            mainHandler.post {
                Toast.makeText(this, "Call ended by partner", Toast.LENGTH_SHORT).show()
                showDashboard()
            }
        }

        CallService.onWarningChime = {
            mainHandler.post {
                playWarningChime()
                showExtendCallDialog()
            }
        }

        CallService.onCallExpired = {
            mainHandler.post {
                Toast.makeText(this, "Call limit reached", Toast.LENGTH_SHORT).show()
                showDashboard()
            }
        }

        mainHandler.post(object : Runnable {
            override fun run() {
                if (CallService.isCallActive) {
                    val sec = CallService.getElapsedSeconds()
                    val m = sec / 60
                    val s = sec % 60
                    tvTimer.text = String.format("%02d:%02d", m, s)
                }
                mainHandler.postDelayed(this, 500L)
            }
        })
    }

    private fun showDashboard() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        layoutDashboard.visibility = View.VISIBLE
        layoutSearching.visibility = View.GONE
        layoutConnected.visibility = View.GONE
    }

    private fun showSearching() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        layoutDashboard.visibility = View.GONE
        layoutSearching.visibility = View.VISIBLE
        layoutConnected.visibility = View.GONE
    }

    private fun showConnected(peerLevel: String) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        layoutDashboard.visibility = View.GONE
        layoutSearching.visibility = View.GONE
        layoutConnected.visibility = View.VISIBLE
        tvPartnerLevel.text = "Connected ($peerLevel Partner)"
    }

    private fun playWarningChime() {
        try {
            warningPlayer?.release()
            warningPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            warningPlayer?.start()
        } catch (e: Exception) {
            AppLogger.log("MainActivity-ERR", "Chime error: ${e.message}")
        }
    }

    private fun showExtendCallDialog() {
        AlertDialog.Builder(this)
            .setTitle("1 Minute Remaining")
            .setMessage("Would you like to extend your conversation by 5 minutes?")
            .setPositiveButton("Extend +5 Mins") { _, _ ->
                val intent = Intent(this, CallService::class.java).apply {
                    action = CallService.ACTION_EXTEND
                }
                startService(intent)
                Toast.makeText(this, "Call extended +5 minutes!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun showVipRequiredDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("VIP Feature")
            .setMessage(message)
            .setPositiveButton("Unlock VIP") { _, _ ->
                isVip = true
                switchFemaleOnly.isChecked = true
                Toast.makeText(this, "VIP Unlocked!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupDiagnosticsLogger() {
        AppLogger.onLogUpdated = { logs ->
            mainHandler.post {
                tvDiagnostics.text = logs
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (CallService.isCallActive) {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_APP_FOREGROUNDED
            }
            startService(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        if (CallService.isCallActive) {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_APP_BACKGROUNDED
            }
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        warningPlayer?.release()
        warningPlayer = null
    }
}
