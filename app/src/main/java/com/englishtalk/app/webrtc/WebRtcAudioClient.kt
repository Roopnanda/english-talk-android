package com.englishtalk.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.englishtalk.app.utils.AppLogger
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
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            AppLogger.log("WebRTC", "PeerConnectionFactory initialized")

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioRecordErrorCallback = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    AppLogger.log("WebRTC-ERR", "AudioRecord Init Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                    AppLogger.log("WebRTC-ERR", "AudioRecord Start Error: $errorCode - $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    AppLogger.log("WebRTC-ERR", "AudioRecord Error: $errorMessage")
                }
            }

            val audioTrackErrorCallback = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    AppLogger.log("WebRTC-ERR", "AudioTrack Init Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                    AppLogger.log("WebRTC-ERR", "AudioTrack Start Error: $errorCode - $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    AppLogger.log("WebRTC-ERR", "AudioTrack Error: $errorMessage")
                }
            }

            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setAudioAttributes(audioAttributes)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioRecordErrorCallback(audioRecordErrorCallback)
                .setAudioTrackErrorCallback(audioTrackErrorCallback)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()
            AppLogger.log("WebRTC", "ADM & Factory created successfully")
        } catch (e: Exception) {
            AppLogger.log("WebRTC-ERR", "Factory Setup Exception: ${e.message}")
        }
    }

    fun initPeerConnection(isInitiator: Boolean, onOfferCreated: (SessionDescription) -> Unit) {
        executor.execute {
            val factory = peerConnectionFactory ?: run {
                AppLogger.log("WebRTC-ERR", "PeerConnectionFactory is null in initPeerConnection")
                return@execute
            }

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
                        AppLogger.log("WebRTC", "Local ICE candidate generated: ${it.sdpMid}")
                        mainHandler.post { onIceCandidateGenerated(it) }
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    if (transceiver?.receiver?.track() is AudioTrack) {
                        AppLogger.log("WebRTC", "Remote Audio Track received & active")
                        mainHandler.post { onRemoteStreamActive() }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    AppLogger.log("WebRTC", "Signaling state: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    AppLogger.log("WebRTC", "ICE connection state: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    AppLogger.log("WebRTC", "ICE receiving: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    AppLogger.log("WebRTC", "ICE gathering: $state")
                }

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
            AppLogger.log("WebRTC", "Local AudioTrack created and enabled")

            peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))

            if (isInitiator) {
                val sdpConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                peerConnection?.createOffer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            AppLogger.log("WebRTC", "Local Offer SDP created")
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
            AppLogger.log("WebRTC", "Microphone enabled state changed -> $enabled")
        }
    }

    fun onRemoteOfferReceived(sdp: SessionDescription, onAnswerCreated: (SessionDescription) -> Unit) {
        executor.execute {
            AppLogger.log("WebRTC", "Setting remote offer SDP")
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    AppLogger.log("WebRTC", "Remote offer set success. Creating Answer...")
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }
                    peerConnection?.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(answerSdp: SessionDescription?) {
                            answerSdp?.let {
                                AppLogger.log("WebRTC", "Answer SDP created")
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
            AppLogger.log("WebRTC", "Setting remote answer SDP")
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
                AppLogger.log("WebRTC", "WebRTC resources fully disposed")
            } catch (e: Exception) {
                AppLogger.log("WebRTC-ERR", "Disconnect error: ${e.message}")
            }
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            AppLogger.log("WebRTC-ERR", "SDP Create Failure: $error")
        }
        override fun onSetFailure(error: String?) {
            AppLogger.log("WebRTC-ERR", "SDP Set Failure: $error")
        }
    }
}
