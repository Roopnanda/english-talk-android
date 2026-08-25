package com.englishtalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.englishtalk.app.ads.AdManager
import com.englishtalk.app.billing.BillingManager
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.utils.SoundHelper
import com.englishtalk.app.webrtc.WebRtcAudioClient
import kotlinx.coroutines.delay
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

enum class AppCallState {
    IDLE, SEARCHING, CONNECTED
}

class MainActivity : ComponentActivity(), SignalingClient.SignalingListener {

    private lateinit var signalingClient: SignalingClient
    private var webRtcClient: WebRtcAudioClient? = null
    private lateinit var audioManager: AudioManager

    private val callState = mutableStateOf(AppCallState.IDLE)
    private val selectedLevel = mutableStateOf("Intermediate")
    private val callDurationSeconds = mutableLongStateOf(0L)
    private val isMuted = mutableStateOf(false)
    private val isSpeakerOn = mutableStateOf(false)
    private val matchedPeerLevel = mutableStateOf("")

    // Your live Render WebSocket backend URL
    private val serverUrl = "wss://english-talk-server-5pm7.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        signalingClient = SignalingClient(serverUrl, this)
        signalingClient.connect()

        setContent {
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

            LaunchedEffect(callState.value) {
                if (callState.value == AppCallState.CONNECTED) {
                    callDurationSeconds.longValue = 0L
                    while (callState.value == AppCallState.CONNECTED) {
                        delay(1000L)
                        callDurationSeconds.longValue += 1L
                        if (callDurationSeconds.longValue == 840L) {
                            SoundHelper.playWarningChime(this@MainActivity)
                        }
                        if (callDurationSeconds.longValue >= 900L && !BillingManager.isSubscribed.value) {
                            hangUpCall()
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0F172A)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    TopHeaderBar()

                    when (callState.value) {
                        AppCallState.IDLE -> IdleDashboard(
                            selectedLevel = selectedLevel.value,
                            onLevelSelected = { selectedLevel.value = it },
                            onStartClick = {
                                if (hasAudioPermission) startSearching()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                    }

                    AdManager.BannerAdView(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    private fun startSearching() {
        callState.value = AppCallState.SEARCHING
        signalingClient.joinQueue(
            level = selectedLevel.value,
            isVip = BillingManager.isSubscribed.value
        )
    }

    private fun toggleMute() {
        isMuted.value = !isMuted.value
        audioManager.isMicrophoneMute = isMuted.value
    }

    private fun toggleSpeaker() {
        isSpeakerOn.value = !isSpeakerOn.value
        audioManager.isSpeakerphoneOn = isSpeakerOn.value
    }

    private fun hangUpCall() {
        val duration = callDurationSeconds.longValue
        signalingClient.endCall()
        cleanupCall()
        AdManager.showPostCallInterstitial(this, duration) {}
    }

    private fun cleanupCall() {
        webRtcClient?.disconnect()
        webRtcClient = null
        callState.value = AppCallState.IDLE
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false
    }

    override fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String) {
        runOnUiThread {
            matchedPeerLevel.value = peerLevel
            callState.value = AppCallState.CONNECTED

            webRtcClient = WebRtcAudioClient(
                context = this,
                onIceCandidateGenerated = { candidate ->
                    signalingClient.sendIceCandidate(candidate)
                },
                onRemoteStreamActive = {}
            )

            webRtcClient?.initPeerConnection(isInitiator) { offer ->
                signalingClient.sendOffer(offer)
            }
        }
    }

    override fun onOfferReceived(sdp: SessionDescription) {
        runOnUiThread {
            webRtcClient?.onRemoteOfferReceived(sdp) { answer ->
                signalingClient.sendAnswer(answer)
            }
        }
    }

    override fun onAnswerReceived(sdp: SessionDescription) {
        runOnUiThread {
            webRtcClient?.onRemoteAnswerReceived(sdp)
        }
    }

    override fun onIceCandidateReceived(candidate: IceCandidate) {
        runOnUiThread {
            webRtcClient?.addRemoteIceCandidate(candidate)
        }
    }

    override fun onCallEnded() {
        runOnUiThread {
            cleanupCall()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupCall()
    }
}

@Composable
fun TopHeaderBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "English Talk",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "P2P Audio",
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun IdleDashboard(
    selectedLevel: String,
    onLevelSelected: (String) -> Unit,
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
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 48.dp)
        ) {
            levels.forEach { level ->
                val isSelected = level == selectedLevel
                FilterChip(
                    selected = isSelected,
                    onClick = { onLevelSelected(level) },
                    label = { Text(level) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2563EB),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color(0xFF94A3B8)
                    )
                )
            }
        }

        Button(
            onClick = onStartClick,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Start Call",
                    modifier = Modifier.size(42.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TALK NOW",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
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
