package com.englishtalk.app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.englishtalk.app.utils.AppLogger
import org.webrtc.*

object WebRtcAudioClient {

    interface RtcListener {
        fun onLocalOfferCreated(sdp: SessionDescription)
        fun onLocalAnswerCreated(sdp: SessionDescription)
        fun onIceCandidateGenerated(candidate: IceCandidate)
        fun onAudioConnected()
        fun onDisconnected()
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var rtcListener: RtcListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    private var pendingRemoteOffer: SessionDescription? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    fun init(context: Context) {
        if (isInitialized) return

        try {
            val appContext = context.applicationContext ?: context
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()

            isInitialized = true
            AppLogger.log("WebRTC", "Native Factory initialized")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Factory init failure: ${e.message}")
        }
    }

    fun startSession(listener: RtcListener) {
        this.rtcListener = listener
        pendingRemoteOffer = null
        pendingIceCandidates.clear()

        try {
            val factory = peerConnectionFactory
            if (factory == null) {
                AppLogger.log("WebRTC-ERR", "Factory is null in startSession")
                return
            }

            // Create Audio Source and Track cleanly per session
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }

            localAudioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)
            localAudioTrack?.setEnabled(true)

            createPeerConnection()
            AppLogger.log("WebRTC", "PeerConnection & Audio tracks ready")

            pendingRemoteOffer?.let {
                AppLogger.log("WebRTC", "Processing queued remote offer")
                handleRemoteOffer(it)
                pendingRemoteOffer = null
            }
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Session start failure: ${e.message}")
        }
    }

    private fun createPeerConnection() {
        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Throwable) {
            // Ignore cleanup
        }

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
                candidate?.let { cand ->
                    mainHandler.post {
                        rtcListener?.onIceCandidateGenerated(cand)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                mainHandler.post {
                    AppLogger.log("WebRTC", "ICE State: $newState")
                    if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                        rtcListener?.onAudioConnected()
                    } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                               newState == PeerConnection.IceConnectionState.FAILED ||
                               newState == PeerConnection.IceConnectionState.CLOSED) {
                        rtcListener?.onDisconnected()
                    }
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        localAudioTrack?.let { track ->
            try {
                peerConnection?.addTrack(track, listOf("ARDAMS"))
            } catch (e: Throwable) {
                AppLogger.log("WebRTC-ERR", "Track attach note: ${e.message}")
            }
        }
    }

    fun createOffer() {
        val pc = peerConnection
        if (pc == null) {
            AppLogger.log("WebRTC-ERR", "Cannot create offer: PeerConnection is null")
            return
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val mungedSdp = mungeOpusDtx(it.description)
                    val sdp = SessionDescription(it.type, mungedSdp)
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            mainHandler.post {
                                rtcListener?.onLocalOfferCreated(sdp)
                            }
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Offer creation failed: $p0")
            }
            override fun onSetFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Offer set failed: $p0")
            }
        }, constraints)
    }

    fun handleRemoteOffer(sdp: SessionDescription) {
        val pc = peerConnection
        if (pc == null) {
            AppLogger.log("WebRTC", "PeerConnection not ready, buffering offer")
            pendingRemoteOffer = sdp
            return
        }

        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                mainHandler.post {
                    createAnswer()
                    drainPendingIceCandidates()
                }
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Remote offer set failed: $p0")
            }
        }, sdp)
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val mungedSdp = mungeOpusDtx(it.description)
                    val sdp = SessionDescription(it.type, mungedSdp)
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            mainHandler.post {
                                rtcListener?.onLocalAnswerCreated(sdp)
                            }
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Answer create failed: $p0")
            }
            override fun onSetFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Answer set failed: $p0")
            }
        }, constraints)
    }

    fun handleRemoteAnswer(sdp: SessionDescription) {
        val pc = peerConnection ?: return

        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                mainHandler.post {
                    AppLogger.log("WebRTC", "Remote answer set successfully")
                    drainPendingIceCandidates()
                }
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Remote answer set failed: $p0")
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc != null && pc.remoteDescription != null) {
            pc.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    private fun drainPendingIceCandidates() {
        val pc = peerConnection ?: return
        for (cand in pendingIceCandidates) {
            pc.addIceCandidate(cand)
        }
        pendingIceCandidates.clear()
    }

    fun setMicrophoneMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    private fun mungeOpusDtx(sdp: String): String {
        return sdp.replace(
            "useinbandfec=1",
            "useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=16000"
        )
    }

    fun close() {
        try {
            peerConnection?.close()
            peerConnection = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            localAudioSource?.dispose()
            localAudioSource = null
            pendingRemoteOffer = null
            pendingIceCandidates.clear()
            AppLogger.log("WebRTC", "Session cleaned up")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Close cleanup error: ${e.message}")
        }
    }
}
