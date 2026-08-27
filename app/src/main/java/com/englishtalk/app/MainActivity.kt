package com.englishtalk.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.utils.AppLogger
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity(), SignalingClient.SignalingListener {

    private lateinit var layoutDashboard: View
    private lateinit var layoutSearching: View
    private lateinit var layoutConnected: View

    private lateinit var btnTalkNow: View
    private lateinit var btnCancelSearch: Button
    private lateinit var btnEndCall: View
    private lateinit var btnMute: ImageView
    private lateinit var btnSpeaker: ImageView
    private lateinit var btnGoVip: View
    private lateinit var switchFemaleOnly: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvTimer: TextView
    private lateinit var tvPartnerLevel: TextView
    private lateinit var tvDiagnostics: TextView
    private lateinit var adView: AdView

    private lateinit var btnBeginner: Button
    private lateinit var btnIntermediate: Button
    private lateinit var btnAdvanced: Button

    private var selectedLevel = "Intermediate"
    private var isVip = false
    private var userGender = "Male"

    private var warningPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            startSearchFlow()
        } else {
            Toast.makeText(this, "Microphone permission is required to talk", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            AppLogger.log("FATAL", "${throwable.javaClass.simpleName}: ${throwable.message}")
        }

        MobileAds.initialize(this) {}

        bindViews()
        setupListeners()
        setupDiagnosticsLogger()
        setupCallServiceCallbacks()

        SignalingClient.setListener(this)
        SignalingClient.connect()
    }

    private fun bindViews() {
        layoutDashboard = findViewById(R.id.layout_dashboard)
        layoutSearching = findViewById(R.id.layout_searching)
        layoutConnected = findViewById(R.id.layout_connected)

        btnTalkNow = findViewById(R.id.btn_talk_now)
        btnCancelSearch = findViewById(R.id.btn_cancel_search)
        btnEndCall = findViewById(R.id.btn_end_call)
        btnMute = findViewById(R.id.btn_mute)
        btnSpeaker = findViewById(R.id.btn_speaker)
        btnGoVip = findViewById(R.id.btn_go_vip)
        switchFemaleOnly = findViewById(R.id.switch_female_only)

        tvTimer = findViewById(R.id.tv_timer)
        tvPartnerLevel = findViewById(R.id.tv_partner_level)
        tvDiagnostics = findViewById(R.id.tv_diagnostics)
        adView = findViewById(R.id.adView)

        btnBeginner = findViewById(R.id.btn_beginner)
        btnIntermediate = findViewById(R.id.btn_intermediate)
        btnAdvanced = findViewById(R.id.btn_advanced)

        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
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
        val defaultBg = ContextCompat.getDrawable(this, R.drawable.bg_level_button)
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_level_button_selected)

        btnBeginner.background = if (level == "Beginner") selectedBg else defaultBg
        btnIntermediate.background = if (level == "Intermediate") selectedBg else defaultBg
        btnAdvanced.background = if (level == "Advanced") selectedBg else defaultBg
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startSearchFlow()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
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
            ContextCompat.startForegroundService(this, intent)
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
        layoutDashboard.visibility = View.VISIBLE
        layoutSearching.visibility = View.GONE
        layoutConnected.visibility = View.GONE
    }

    private fun showSearching() {
        layoutDashboard.visibility = View.GONE
        layoutSearching.visibility = View.VISIBLE
        layoutConnected.visibility = View.GONE
    }

    private fun showConnected(peerLevel: String) {
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

    override fun onResume() {
        super.onResume()
        if (CallService.isCallActive) {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_APP_FOREGROUNDED
            }
            startService(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (CallService.isCallActive) {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_APP_BACKGROUNDED
            }
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        warningPlayer?.release()
        warningPlayer = null
    }
}
