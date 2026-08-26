package com.englishtalk.app.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRtcAudioClient(
    private val context: Context,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onRemoteStreamActive: () -> Unit
) {

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferCreated: (SessionDescription) -> Unit) {
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

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (transceiver?.receiver?.track() is AudioTrack) {
                    onRemoteStreamActive()
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        // Setup Local Audio Track
        val audioConstraints = MediaConstraints()
        localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", localAudioSource)
        localAudioTrack?.setEnabled(true)

        val mediaStreamLabels = listOf("ARDAMS")
        peerConnection?.addTrack(localAudioTrack, mediaStreamLabels)

        if (isInitiator) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            peerConnection?.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)
                        onOfferCreated(it)
                    }
                }
            }, constraints)
        }
    }

    fun onRemoteOfferReceived(sdp: SessionDescription, onAnswerCreated: (SessionDescription) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        answerSdp?.let {
                            peerConnection?.setLocalDescription(SdpObserverAdapter(), it)
                            onAnswerCreated(it)
                        }
                    }
                }, constraints)
            }
        }, sdp)
    }

    fun onRemoteAnswerReceived(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun disconnect() {
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioSource?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.e("WebRtcAudioClient", "Error during disconnect: ${e.message}")
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
