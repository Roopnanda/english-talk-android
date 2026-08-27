package com.englishtalk.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
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
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(audioAttributes)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferCreated: (SessionDescription) -> Unit) {
        executor.execute {
            val factory = peerConnectionFactory ?: return@execute

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        mainHandler.post { onIceCandidateGenerated(it) }
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    if (transceiver?.receiver?.track() is AudioTrack) {
                        mainHandler.post { onRemoteStreamActive() }
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

            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }

            localAudioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)
            localAudioTrack?.setEnabled(true)

            peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))

            if (isInitiator) {
                val sdpConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                peerConnection?.createOffer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            peerConnection?.setLocalDescription(SdpObserverAdapter(), it)
                            mainHandler.post { onOfferCreated(it) }
                        }
                    }
                }, sdpConstraints)
            }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        executor.execute {
            localAudioTrack?.setEnabled(enabled)
            Log.d("WebRtcAudioClient", "Mic track enabled: $enabled")
        }
    }

    fun onRemoteOfferReceived(sdp: SessionDescription, onAnswerCreated: (SessionDescription) -> Unit) {
        executor.execute {
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }
                    peerConnection?.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(answerSdp: SessionDescription?) {
                            answerSdp?.let {
                                peerConnection?.setLocalDescription(SdpObserverAdapter(), it)
                                mainHandler.post { onAnswerCreated(it) }
                            }
                        }
                    }, constraints)
                }
            }, sdp)
        }
    }

    fun onRemoteAnswerReceived(sdp: SessionDescription) {
        executor.execute {
            peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                localAudioTrack?.setEnabled(false)
                localAudioTrack?.dispose()
                localAudioSource?.dispose()
                peerConnection?.close()
                peerConnection?.dispose()
                audioDeviceModule?.release()
            } catch (e: Exception) {
                Log.e("WebRtcAudioClient", "Disconnect error: ${e.message}")
            }
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
