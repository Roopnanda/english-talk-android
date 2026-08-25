package com.englishtalk.app.webrtc

import android.content.Context
import org.webrtc.*

class WebRtcAudioClient(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onRemoteStreamActive: () -> Unit
) {
    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        createLocalAudioTrack()
    }

    private fun createLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", localAudioSource)
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferReady: ((SessionDescription) -> Unit)? = null) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onIceCandidateGenerated(it) }
            }
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                onRemoteStreamActive()
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(stream: MediaStream?) {}
        })

        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("media_stream"))
        }

        if (isInitiator) {
            val sdpConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            peerConnection?.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let { offer ->
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), offer)
                        onOfferReady?.invoke(offer)
                    }
                }
            }, sdpConstraints)
        }
    }

    fun onRemoteOfferReceived(offer: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                val sdpConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let { answer ->
                            peerConnection?.setLocalDescription(SdpObserverAdapter(), answer)
                            onAnswerReady(answer)
                        }
                    }
                }, sdpConstraints)
            }
        }, offer)
    }

    fun onRemoteAnswerReceived(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun disconnect() {
        try {
            peerConnection?.close()
            peerConnection = null
            localAudioSource?.dispose()
            localAudioTrack?.dispose()
        } catch (_: Exception) {}
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
