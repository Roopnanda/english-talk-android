package com.englishtalk.app.webrtc

import android.content.Context
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
    private var isFactoryInitialized = false

    fun init(context: Context) {
        if (isFactoryInitialized) return

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

            isFactoryInitialized = true
            AppLogger.log("WebRTC", "Native Factory initialized")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Factory init failure: ${e.message}")
        }
    }

    fun startSession(listener: RtcListener) {
        this.rtcListener = listener

        try {
            val factory = peerConnectionFactory
            if (factory == null) {
                AppLogger.log("WebRTC-ERR", "Factory is null on startSession")
                return
            }

            // Create Audio Source and Track lazily per session
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
            AppLogger.log("WebRTC", "Session audio tracks initialized")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Session start failure: ${e.message}")
        }
    }

    private fun createPeerConnection() {
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
                candidate?.let { rtcListener?.onIceCandidateGenerated(it) }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                AppLogger.log("WebRTC", "ICE State: $newState")
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    rtcListener?.onAudioConnected()
                } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                           newState == PeerConnection.IceConnectionState.FAILED ||
                           newState == PeerConnection.IceConnectionState.CLOSED) {
                    rtcListener?.onDisconnected()
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

        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val mungedSdp = mungeOpusDtx(it.description)
                    val sdp = SessionDescription(it.type, mungedSdp)
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            rtcListener?.onLocalOfferCreated(sdp)
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
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Remote offer set failed: $p0")
            }
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val mungedSdp = mungeOpusDtx(it.description)
                    val sdp = SessionDescription(it.type, mungedSdp)
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            rtcListener?.onLocalAnswerCreated(sdp)
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
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                AppLogger.log("WebRTC", "Remote answer set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                AppLogger.log("WebRTC-ERR", "Remote answer set failed: $p0")
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
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
            AppLogger.log("WebRTC", "Session audio cleaned")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Close cleanup error: ${e.message}")
        }
    }
}
