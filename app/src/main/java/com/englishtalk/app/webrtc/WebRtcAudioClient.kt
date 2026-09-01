package com.englishtalk.app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.englishtalk.app.network.SignalingClient
import com.englishtalk.app.utils.AppLogger
import org.webrtc.*

object WebRtcAudioClient {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isMuted: Boolean = false
        private set

    fun init(context: Context) {
        if (peerConnectionFactory != null) return

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        AppLogger.log("WebRTC", "Native Factory initialized")
    }

    fun startPeerConnection(roomId: String, isInitiator: Boolean, context: Context) {
        init(context.applicationContext)

        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        isMuted = false

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    mainHandler.post {
                        SignalingClient.sendIceCandidate(it)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                AppLogger.log("WebRTC", "Signaling State: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLogger.log("WebRTC", "ICE State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    AppLogger.log("WebRTC", "Two-way live audio pipeline connected!")
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }

        AppLogger.log("WebRTC", "PeerConnection & Audio tracks ready")

        if (isInitiator) {
            createOffer()
        }
    }

    private fun createOffer() {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { originalSdp ->
                    val mungedSdp = mungeOpusDtx(originalSdp)
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            mainHandler.post {
                                SignalingClient.sendOffer(mungedSdp)
                                AppLogger.log("WebRTC", "Local offer SDP ready")
                            }
                        }
                        override fun onCreateFailure(err: String?) {}
                        override fun onSetFailure(err: String?) {
                            AppLogger.log("WebRTC-ERR", "Set local desc failure: $err")
                        }
                    }, mungedSdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                AppLogger.log("WebRTC-ERR", "Create offer failure: $err")
            }
            override fun onSetFailure(err: String?) {}
        }, sdpConstraints)
    }

    fun handleRemoteOffer(sdp: SessionDescription) {
        val mungedSdp = mungeOpusDtx(sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                AppLogger.log("WebRTC-ERR", "Set remote offer failure: $err")
            }
        }, mungedSdp)
    }

    private fun createAnswer() {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { originalSdp ->
                    val mungedSdp = mungeOpusDtx(originalSdp)
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            mainHandler.post {
                                SignalingClient.sendAnswer(mungedSdp)
                                AppLogger.log("WebRTC", "Local answer SDP ready")
                            }
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(err: String?) {
                            AppLogger.log("WebRTC-ERR", "Set local answer failure: $err")
                        }
                    }, mungedSdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                AppLogger.log("WebRTC-ERR", "Create answer failure: $err")
            }
            override fun onSetFailure(err: String?) {}
        }, sdpConstraints)
    }

    fun handleRemoteAnswer(sdp: SessionDescription) {
        val mungedSdp = mungeOpusDtx(sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                AppLogger.log("WebRTC", "Remote answer set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                AppLogger.log("WebRTC-ERR", "Set remote answer failure: $err")
            }
        }, mungedSdp)
    }

    fun handleRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    fun close() {
        try {
            peerConnection?.dispose()
            peerConnection = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            localAudioSource?.dispose()
            localAudioSource = null
            AppLogger.log("WebRTC", "Audio pipeline disconnected")
        } catch (e: Throwable) {
            AppLogger.log("WebRTC-ERR", "Cleanup error: ${e.message}")
        }
    }

    private fun mungeOpusDtx(sdp: SessionDescription): SessionDescription {
        val description = sdp.description
        val modified = description.replace(
            "useinbandfec=1",
            "useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=16000"
        )
        return SessionDescription(sdp.type, modified)
    }
}
