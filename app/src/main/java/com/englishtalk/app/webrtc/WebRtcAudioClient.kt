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
    private var pendingRemoteOffer: SessionDescription? = null
    private var pendingAnswerCallback: ((SessionDescription) -> Unit)? = null

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

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()

            AppLogger.log("WebRTC", "Factory initialized")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Factory init fail: ${e.message}")
        }
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferReady: ((SessionDescription) -> Unit)? = null) {
        executor.execute {
            if (isDisposed) return@execute
            try {
                ensureFactoryInitialized()

                val factory = peerConnectionFactory ?: run {
                    AppLogger.log("WebRTC-ERR", "Factory is null")
                    return@execute
                }

                peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            AppLogger.log("WebRTC", "Local ICE candidate: ${it.sdpMLineIndex}")
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
                        AppLogger.log("WebRTC", "Remote audio active")
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
                AppLogger.log("WebRTC", "Local track added")

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
                                        AppLogger.log("WebRTC", "Local Offer ready")
                                        mainHandler.post { onOfferReady?.invoke(localSdp) }
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
                } else if (pendingRemoteOffer != null && pendingAnswerCallback != null) {
                    // Process cached offer if it arrived while PC was creating
                    val offer = pendingRemoteOffer!!
                    val cb = pendingAnswerCallback!!
                    pendingRemoteOffer = null
                    pendingAnswerCallback = null
                    processRemoteOfferInternal(offer, cb)
                }
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "initPeerConnection error: ${e.message}")
            }
        }
    }

    fun onRemoteOfferReceived(sdp: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        executor.execute {
            if (isDisposed) return@execute
            if (peerConnection == null) {
                // Buffer the offer until peer connection finishes initialization
                pendingRemoteOffer = sdp
                pendingAnswerCallback = onAnswerReady
                return@execute
            }
            processRemoteOfferInternal(sdp, onAnswerReady)
        }
    }

    private fun processRemoteOfferInternal(sdp: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        try {
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    AppLogger.log("WebRTC", "Remote Offer set successfully")
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
                                        AppLogger.log("WebRTC-ERR", "Set answer fail: $err")
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
            AppLogger.log("WebRTC-ERR", "processRemoteOffer error: ${e.message}")
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
                        AppLogger.log("WebRTC-ERR", "Set answer fail: $err")
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
            AppLogger.log("WebRTC-ERR", "Drain fail: ${e.message}")
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

                AppLogger.log("WebRTC", "Cleanly disposed")
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Disconnect fail: ${e.message}")
            }
        }
    }
}
