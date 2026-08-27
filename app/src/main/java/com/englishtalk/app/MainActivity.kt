package com.englishtalk.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.englishtalk.app.ads.AdManager
import com.englishtalk.app.billing.BillingManager
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.service.CallService
import com.englishtalk.app.webrtc.WebRtcAudioClient
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

enum class AppCallState {
    ONBOARDING_GENDER, IDLE, SEARCHING, CONNECTED
}

class MainActivity : ComponentActivity(), SignalingClient.SignalingListener {

    private lateinit var signalingClient: SignalingClient
    private var webRtcClient: WebRtcAudioClient? = null
    private lateinit var audioManager: AudioManager

    private val callState = mutableStateOf(AppCallState.IDLE)
    private val selectedLevel = mutableStateOf("Intermediate")
    private val userGender = mutableStateOf("")
    private val talkToFemaleOnly = mutableStateOf(false)
    private val callDurationSeconds = mutableLongStateOf(0L)
    private val showExtendCallDialog = mutableStateOf(false)
    private val showVipDialog = mutableStateOf(false)
    private val isMuted = mutableStateOf(false)
    private val isSpeakerOn = mutableStateOf(false)
    private val matchedPeerLevel = mutableStateOf("Intermediate")

    private val serverUrl = "wss://english-talk-server-5pm7.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedGender = prefs.getString("user_gender", "") ?: ""

        if (savedGender.isEmpty()) {
            callState.value = AppCallState.ONBOARDING_GENDER
        } else {
            userGender.value = savedGender
            if (CallService.isCallActive) {
                callState.value = AppCallState.CONNECTED
            } else {
                callState.value = AppCallState.IDLE
            }
        }

        signalingClient = SignalingClient(serverUrl, this)
        signalingClient.connect()

        CallService.onWarningChime = {
            runOnUiThread { showExtendCallDialog.value = true }
        }

        CallService.onCallExpired = {
            runOnUiThread { hangUpCall() }
        }

        CallService.onAutoMuteTriggered = { autoMuted ->
            runOnUiThread {
                if (autoMuted) {
                    webRtcClient?.setMicrophoneEnabled(false)
                } else {
                    webRtcClient?.setMicrophoneEnabled(!isMuted.value)
                }
            }
        }

        setContent {
            val isSubscribed by BillingManager.isSubscribed.collectAsState()

            var hasAudioPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasAudioPermission = isGranted
                if (isGranted) startSearching()
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                    }
                }
            }

            BackHandler(enabled = callState.value != AppCallState.IDLE && callState.value != AppCallState.ONBOARDING_GENDER) {
                if (callState.value == AppCallState.SEARCHING) {
                    signalingClient.leaveQueue()
                    callState.value = AppCallState.IDLE
                }
            }

            LaunchedEffect(callState.value) {
                if (callState.value == AppCallState.CONNECTED) {
                    while (callState.value == AppCallState.CONNECTED) {
                        delay(500L)
                        callDurationSeconds.longValue = CallService.getElapsedSeconds()
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0F172A)
            ) {
                if (callState.value == AppCallState.ONBOARDING_GENDER) {
                    GenderSelectionOnboardingScreen { chosenGender ->
                        userGender.value = chosenGender
                        prefs.edit().putString("user_gender", chosenGender).apply()
                        callState.value = AppCallState.IDLE
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        TopHeaderBar(
                            isVip = isSubscribed,
                            onVipClick = { showVipDialog.value = true }
                        )

                        when (callState.value) {
                            AppCallState.IDLE -> IdleDashboard(
                                selectedLevel = selectedLevel.value,
                                talkToFemaleOnly = talkToFemaleOnly.value,
                                isVip = isSubscribed,
                                onLevelSelected = { selectedLevel.value = it },
                                onToggleTalkToFemale = {
                                    if (isSubscribed) {
                                        talkToFemaleOnly.value = !talkToFemaleOnly.value
                                    } else {
                                        showVipDialog.value = true
                                    }
                                },
                                onStartClick = {
                                    if (CallService.isCallActive) {
                                        callState.value = AppCallState.CONNECTED
                                    } else if (hasAudioPermission) {
                                        startSearching()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            )

                            AppCallState.SEARCHING -> SearchingDashboard(
                                onCancelClick = {
                                    signalingClient.leaveQueue()
                                    callState.value = AppCallState.IDLE
                                }
                            )

                            AppCallState.CONNECTED -> ActiveCallDashboard(
                                durationSeconds = callDurationSeconds.longValue,
                                peerLevel = matchedPeerLevel.value,
                                isMuted = isMuted.value,
                                isSpeakerOn = isSpeakerOn.value,
                                onToggleMute = { toggleMute() },
                                onToggleSpeaker = { toggleSpeaker() },
                                onEndCall = { hangUpCall() }
                            )

                            else -> {}
                        }

                        AdManager.BannerAdView(modifier = Modifier.padding(top = 8.dp))
                    }
                }

                if (showExtendCallDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showExtendCallDialog.value = false },
                        containerColor = Color(0xFF1E293B),
                        title = {
                            Text("1 Minute Remaining!", color = Color.White, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Text(
                                "Your free 15-minute call is ending soon. Watch an ad to add +5 extra minutes, or upgrade to VIP for unlimited talk time.",
                                color = Color(0xFFCBD5E1)
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showExtendCallDialog.value = false
                                    AdManager.showRewardedAd(this@MainActivity) {
                                        val extendIntent = Intent(this@MainActivity, CallService::class.java).apply {
                                            action = CallService.ACTION_EXTEND
                                        }
                                        startService(extendIntent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                            ) {
                                Text("Watch Ad (+5 Min)")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExtendCallDialog.value = false }) {
                                Text("Dismiss", color = Color(0xFF94A3B8))
                            }
                        }
                    )
                }

                if (showVipDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showVipDialog.value = false },
                        containerColor = Color(0xFF1E293B),
                        title = {
                            Text("✨ English Talk VIP", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("👩 Talk to Female Gender (Exclusive Match)", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                                Text("⏱ Unlimited Call Duration (No 15-Min Cutoff)", color = Color.White)
                                Text("🚫 100% Ad-Free Experience", color = Color.White)
                                Text("⚡ Priority Fast-Track Matchmaking", color = Color.White)
                                Text("🎯 Strict Proficiency Level Lock", color = Color.White)
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    BillingManager.launchPurchaseFlow(this@MainActivity)
                                    showVipDialog.value = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                            ) {
                                Text("Upgrade Now", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showVipDialog.value = false }) {
                                Text("Close", color = Color(0xFF94A3B8))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (CallService.isCallActive) {
            callState.value = AppCallState.CONNECTED
        }
    }

    override fun onPause() {
        super.onPause()
        if (callState.value == AppCallState.CONNECTED) {
            val pauseIntent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_APP_PAUSED
            }
            startService(pauseIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (CallService.isCallActive) {
            callState.value = AppCallState.CONNECTED
            val resumeIntent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_APP_RESUMED
            }
            startService(resumeIntent)
        }
    }

    private fun startSearching() {
        if (CallService.isCallActive) {
            callState.value = AppCallState.CONNECTED
            return
        }
        callState.value = AppCallState.SEARCHING
        signalingClient.joinQueue(
            level = selectedLevel.value,
            userGender = userGender.value,
            talkToFemaleOnly = talkToFemaleOnly.value,
            isVip = BillingManager.isSubscribed.value
        )
    }

    private fun toggleMute() {
        isMuted.value = !isMuted.value
        webRtcClient?.setMicrophoneEnabled(!isMuted.value)
    }

    private fun toggleSpeaker() {
        isSpeakerOn.value = !isSpeakerOn.value
        audioManager.isSpeakerphoneOn = isSpeakerOn.value
    }

    private fun hangUpCall() {
        val duration = CallService.getElapsedSeconds()
        signalingClient.endCall()
        cleanupCall()
        AdManager.showPostCallInterstitial(this, duration) {}
    }

    private fun cleanupCall() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val serviceIntent = Intent(this, CallService::class.java).apply {
            action = CallService.ACTION_STOP
        }
        startService(serviceIntent)

        webRtcClient?.disconnect()
        webRtcClient = null
        callState.value = AppCallState.IDLE
        showExtendCallDialog.value = false
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false
    }

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String) {
        runOnUiThread {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                matchedPeerLevel.value = peerLevel
                callState.value = AppCallState.CONNECTED

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                val serviceIntent = Intent(this, CallService::class.java).apply {
                    action = CallService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                webRtcClient = WebRtcAudioClient(
                    context = applicationContext,
                    onIceCandidateGenerated = { candidate ->
                        signalingClient.sendIceCandidate(candidate)
                    },
                    onRemoteStreamActive = {}
                )

                webRtcClient?.initPeerConnection(isInitiator) { offer ->
                    signalingClient.sendOffer(offer)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Match setup error: ${e.message}")
            }
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        webRtcClient?.onRemoteOfferReceived(sdp) { answer ->
            signalingClient.sendAnswer(answer)
        }
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        webRtcClient?.onRemoteAnswerReceived(sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        webRtcClient?.addRemoteIceCandidate(candidate)
    }

    override fun onCallEnded() {
        runOnUiThread { cleanupCall() }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cleanupCall()
    }
}

@Composable
fun GenderSelectionOnboardingScreen(onGenderSelected: (String) -> Unit) {
    var selected by remember { mutableStateOf("Male") }
    val options = listOf("Male", "Female", "Other")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to English Talk!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Please select your gender to personalize your profile. You will only be asked this once.",
            fontSize = 14.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        options.forEach { option ->
            val isOptionSelected = option == selected
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isOptionSelected) Color(0xFF2563EB) else Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { selected = option }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = option,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    RadioButton(
                        selected = isOptionSelected,
                        onClick = { selected = option },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color.White,
                            unselectedColor = Color(0xFF64748B)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = { onGenderSelected(selected) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
        ) {
            Text(
                text = "Continue to App",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun TopHeaderBar(isVip: Boolean, onVipClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "English Talk",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Button(
            onClick = onVipClick,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isVip) Color(0xFF10B981) else Color(0xFFF59E0B)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (isVip) "VIP ACTIVE" else "👑 GO VIP",
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun IdleDashboard(
    selectedLevel: String,
    talkToFemaleOnly: Boolean,
    isVip: Boolean,
    onLevelSelected: (String) -> Unit,
    onToggleTalkToFemale: () -> Unit,
    onStartClick: () -> Unit
) {
    val levels = listOf("Beginner", "Intermediate", "Advanced")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Choose Your English Level",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            levels.forEach { level ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = level == selectedLevel,
                    onClick = { onLevelSelected(level) },
                    label = {
                        Text(
                            text = level,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2563EB),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color(0xFF94A3B8)
                    )
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (talkToFemaleOnly && isVip) Color(0xFF1E3A5F) else Color(0xFF1E293B)
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp)
                .clickable { onToggleTalkToFemale() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "👩 Talk to Female Gender",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!isVip) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "👑 VIP",
                            color = Color(0xFFF59E0B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Switch(
                    checked = talkToFemaleOnly && isVip,
                    onCheckedChange = { onToggleTalkToFemale() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFF59E0B),
                        uncheckedThumbColor = Color(0xFF94A3B8),
                        uncheckedTrackColor = Color(0xFF0F172A)
                    )
                )
            }
        }

        Button(
            onClick = onStartClick,
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Start Call",
                    modifier = Modifier.size(38.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TALK NOW",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun SearchingDashboard(onCancelClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            color = Color(0xFF38BDF8),
            strokeWidth = 4.dp,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Searching for a conversation partner...",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onCancelClick,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Cancel Search", color = Color(0xFFEF4444))
        }
    }
}

@Composable
fun ActiveCallDashboard(
    durationSeconds: Long,
    peerLevel: String,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF2563EB), Color(0xFF1E293B))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Peer Avatar",
                tint = Color.White,
                modifier = Modifier.size(54.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connected ($peerLevel Partner)",
            color = Color(0xFF38BDF8),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = timeFormatted,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(48.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (isMuted) Color(0xFFEF4444) else Color(0xFF1E293B),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mute",
                    tint = Color.White
                )
            }

            IconButton(
                onClick = onEndCall,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFFDC2626), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(
                onClick = onToggleSpeaker,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (isSpeakerOn) Color(0xFF2563EB) else Color(0xFF1E293B),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    contentDescription = "Speaker",
                    tint = Color.White
                )
            }
        }
    }
}
