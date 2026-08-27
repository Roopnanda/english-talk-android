package com.englishtalk.app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.englishtalk.app.utils.AppLogger
import org.webrtc.*
import java.util.concurrent.Executors

class WebRtcAudioClient(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onRemoteStreamActive: () -> Unit
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var isDisposed = false

    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private val rtcConfig = PeerConnection.RTCConfiguration(
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private fun ensureFactoryInitialized() {
        if (peerConnectionFactory != null) return
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()

            AppLogger.log("WebRTC", "Factory initialized on dedicated thread")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Factory init failed: ${e.message}")
        }
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferReady: (SessionDescription) -> Unit) {
        executor.execute {
            if (isDisposed) return@execute
            try {
                ensureFactoryInitialized()

                val factory = peerConnectionFactory ?: run {
                    AppLogger.log("WebRTC-ERR", "Factory is null, aborting setup")
                    return@execute
                }

                peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            AppLogger.log("WebRTC", "Local candidate generated")
                            mainHandler.post { onIceCandidateGenerated(it) }
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                        AppLogger.log("WebRTC", "Signaling: $newState")
                    }
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        AppLogger.log("WebRTC", "ICE: $newState")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}

                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        AppLogger.log("WebRTC", "Remote track active")
                        mainHandler.post { onRemoteStreamActive() }
                    }
                })

                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
                }

                localAudioSource = factory.createAudioSource(audioConstraints)
                localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)?.apply {
                    setEnabled(true)
                }

                peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
                AppLogger.log("WebRTC", "AudioTrack created and enabled")

                if (isInitiator) {
                    val sdpConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    }

                    peerConnection?.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let { localSdp ->
                                peerConnection?.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        AppLogger.log("WebRTC", "Local Offer SDP ready")
                                        mainHandler.post { onOfferReady(localSdp) }
                                    }
                                    override fun onCreateFailure(err: String?) {}
                                    override fun onSetFailure(err: String?) {
                                        AppLogger.log("WebRTC-ERR", "Set local fail: $err")
                                    }
                                }, localSdp)
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(err: String?) {
                            AppLogger.log("WebRTC-ERR", "Create offer fail: $err")
                        }
                        override fun onSetFailure(err: String?) {}
                    }, sdpConstraints)
                }
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "InitPeerConn fail: ${e.message}")
            }
        }
    }

    fun onRemoteOfferReceived(sdp: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        executor.execute {
            if (isDisposed) return@execute
            try {
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        AppLogger.log("WebRTC", "Remote Offer set")
                        drainPendingCandidates()

                        val sdpConstraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                        }

                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answerSdp: SessionDescription?) {
                                answerSdp?.let { answer ->
                                    peerConnection?.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(p0: SessionDescription?) {}
                                        override fun onSetSuccess() {
                                            AppLogger.log("WebRTC", "Local Answer ready")
                                            mainHandler.post { onAnswerReady(answer) }
                                        }
                                        override fun onCreateFailure(p0: String?) {}
                                        override fun onSetFailure(err: String?) {
                                            AppLogger.log("WebRTC-ERR", "Set local answer fail: $err")
                                        }
                                    }, answer)
                                }
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(err: String?) {
                                AppLogger.log("WebRTC-ERR", "Create answer fail: $err")
                            }
                            override fun onSetFailure(err: String?) {}
                        }, sdpConstraints)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(err: String?) {
                        AppLogger.log("WebRTC-ERR", "Set remote offer fail: $err")
                    }
                }, sdp)
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Remote offer fail: ${e.message}")
            }
        }
    }

    fun onRemoteAnswerReceived(sdp: SessionDescription) {
        executor.execute {
            if (isDisposed) return@execute
            try {
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        AppLogger.log("WebRTC", "Remote Answer set")
                        drainPendingCandidates()
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(err: String?) {
                        AppLogger.log("WebRTC-ERR", "Set remote answer fail: $err")
                    }
                }, sdp)
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Remote answer fail: ${e.message}")
            }
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (isDisposed) return@execute
            try {
                if (peerConnection?.remoteDescription != null) {
                    peerConnection?.addIceCandidate(candidate)
                } else {
                    pendingIceCandidates.add(candidate)
                }
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Add ICE fail: ${e.message}")
            }
        }
    }

    private fun drainPendingCandidates() {
        try {
            for (cand in pendingIceCandidates) {
                peerConnection?.addIceCandidate(cand)
            }
            pendingIceCandidates.clear()
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Drain candidates fail: ${e.message}")
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        executor.execute {
            try {
                localAudioTrack?.setEnabled(enabled)
                AppLogger.log("WebRTC", "Mic enabled: $enabled")
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Toggle mic fail: ${e.message}")
            }
        }
    }

    fun disconnect() {
        isDisposed = true
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

                peerConnectionFactory?.dispose()
                peerConnectionFactory = null

                AppLogger.log("WebRTC", "Resources disposed cleanly")
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Disconnect error: ${e.message}")
            }
        }
    }
}
