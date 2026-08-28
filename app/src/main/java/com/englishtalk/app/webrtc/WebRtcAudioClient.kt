package com.englishtalk.app.webrtc

import android.content.Context
import android.media.MediaRecorder
import com.englishtalk.app.utils.AppLogger
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

class WebRtcAudioClient(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onRemoteStreamActive: () -> Unit
) {

    private val executor = Executors.newSingleThreadExecutor()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    init {
        executor.execute {
            initializeFactory()
        }
    }

    private fun initializeFactory() {
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            // Re-enable hardware AEC and Noise Suppression with VOICE_COMMUNICATION DSP source
            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        AppLogger.log("WebRTC-Audio", "Record Init Error: $errorMessage")
                    }
                    override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                        AppLogger.log("WebRTC-Audio", "Record Start Error: $errorMessage")
                    }
                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        AppLogger.log("WebRTC-Audio", "Record Error: $errorMessage")
                    }
                })
                .createAudioDeviceModule()

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()

            createLocalAudioTrack()
            AppLogger.log("WebRTC", "Factory initialized with Hardware DSP AEC & NS")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Factory init failed: ${e.message}")
        }
    }

    private fun createLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.setEnabled(true)
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferReady: (SessionDescription) -> Unit) {
        executor.execute {
            try {
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
                )

                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }

                peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            onIceCandidateGenerated(it)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        AppLogger.log("WebRTC", "ICE state: $state")
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                    override fun onAddStream(stream: MediaStream?) {}

                    override fun onRemoveStream(stream: MediaStream?) {}

                    override fun onDataChannel(channel: DataChannel?) {}

                    override fun onRenegotiationNeeded() {}

                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        AppLogger.log("WebRTC", "Remote audio track active")
                        onRemoteStreamActive()
                    }
                })

                localAudioTrack?.let {
                    peerConnection?.addTrack(it, listOf("ARDAMS"))
                }

                if (isInitiator) {
                    createOffer(onOfferReady)
                }
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "PeerConnection init fail: ${e.message}")
            }
        }
    }

    private fun createOffer(onOfferReady: (SessionDescription) -> Unit) {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let { offer ->
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            onOfferReady(offer)
                        }
                    }, offer)
                }
            }
        }, sdpConstraints)
    }

    fun onRemoteOfferReceived(sdp: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        executor.execute {
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    drainPendingCandidates()
                    createAnswer(onAnswerReady)
                }
            }, sdp)
        }
    }

    private fun createAnswer(onAnswerReady: (SessionDescription) -> Unit) {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let { answer ->
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            onAnswerReady(answer)
                        }
                    }, answer)
                }
            }
        }, sdpConstraints)
    }

    fun onRemoteAnswerReceived(sdp: SessionDescription) {
        executor.execute {
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    drainPendingCandidates()
                }
            }, sdp)
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (isRemoteDescriptionSet) {
                peerConnection?.addIceCandidate(candidate)
            } else {
                pendingRemoteCandidates.add(candidate)
            }
        }
    }

    private fun drainPendingCandidates() {
        for (candidate in pendingRemoteCandidates) {
            peerConnection?.addIceCandidate(candidate)
        }
        pendingRemoteCandidates.clear()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        executor.execute {
            localAudioTrack?.setEnabled(enabled)
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                localAudioTrack?.setEnabled(false)
                localAudioTrack?.dispose()
                localAudioTrack = null

                localAudioSource?.dispose()
                localAudioSource = null

                peerConnection?.close()
                peerConnection?.dispose()
                peerConnection = null

                audioDeviceModule?.release()
                audioDeviceModule = null

                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Disconnect error: ${e.message}")
            }
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {
            AppLogger.log("WebRTC-ERR", "SDP create fail: $p0")
        }
        override fun onSetFailure(p0: String?) {
            AppLogger.log("WebRTC-ERR", "SDP set fail: $p0")
        }
    }
}
