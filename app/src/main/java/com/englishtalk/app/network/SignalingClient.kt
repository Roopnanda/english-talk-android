package com.englishtalk.app.network

import com.englishtalk.app.utils.AppLogger
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

object SignalingClient {

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
    }

    private var activeListener: SignalingListener? = null
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private const val SERVER_URL = "wss://english-talk-server-5pm7.onrender.com"
    var currentRoomId: String? = null

    fun setListener(listener: SignalingListener?) {
        this.activeListener = listener
    }

    fun connect(onReady: (() -> Unit)? = null) {
        if (isConnected && webSocket != null) {
            onReady?.invoke()
            return
        }

        AppLogger.log("Signaling", "Connecting to WebSocket server...")
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                AppLogger.log("Signaling", "WebSocket Connected Successfully!")
                onReady?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "match_found" -> {
                            val roomId = json.getString("roomId")
                            val isInitiator = json.getBoolean("isInitiator")
                            val peerLevel = json.optString("peerLevel", "Intermediate")
                            currentRoomId = roomId
                            AppLogger.log("Signaling", "Match found! Room: $roomId, Initiator: $isInitiator")
                            activeListener?.onMatchFound(roomId, isInitiator, peerLevel)
                        }
                        "offer" -> {
                            val sdpObj = json.getJSONObject("sdp")
                            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpObj.getString("sdp"))
                            activeListener?.onOfferReceived(sdp)
                        }
                        "answer" -> {
                            val sdpObj = json.getJSONObject("sdp")
                            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpObj.getString("sdp"))
                            activeListener?.onAnswerReceived(sdp)
                        }
                        "ice_candidate" -> {
                            val candidateObj = json.getJSONObject("candidate")
                            val candidate = IceCandidate(
                                candidateObj.getString("sdpMid"),
                                candidateObj.getInt("sdpMLineIndex"),
                                candidateObj.getString("sdp")
                            )
                            activeListener?.onIceCandidateReceived(candidate)
                        }
                        "call_ended", "peer_disconnected" -> {
                            AppLogger.log("Signaling", "Remote peer hung up")
                            currentRoomId = null
                            activeListener?.onCallEnded()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.log("Signaling-ERR", "Parse Error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                this@SignalingClient.webSocket = null
                AppLogger.log("Signaling-ERR", "WebSocket Error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                this@SignalingClient.webSocket = null
                AppLogger.log("Signaling", "WebSocket Closed: $reason")
            }
        })
    }

    fun joinQueue(level: String, userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        val payload = JSONObject().apply {
            put("type", "join_queue")
            put("level", level)
            put("gender", userGender)
            put("talkToFemaleOnly", talkToFemaleOnly)
            put("isVip", isVip)
        }

        if (webSocket == null || !isConnected) {
            AppLogger.log("Signaling", "Socket not ready, reconnecting first...")
            connect {
                AppLogger.log("Signaling", "Sending join_queue (level: $level)...")
                webSocket?.send(payload.toString())
            }
        } else {
            AppLogger.log("Signaling", "Sending join_queue (level: $level)...")
            webSocket?.send(payload.toString())
        }
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("type", "leave_queue")
        }
        webSocket?.send(payload.toString())
        AppLogger.log("Signaling", "Sent leave_queue")
    }

    fun sendOffer(sdp: SessionDescription) {
        val sdpJson = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }
        val payload = JSONObject().apply {
            put("type", "offer")
            put("roomId", currentRoomId)
            put("sdp", sdpJson)
        }
        webSocket?.send(payload.toString())
        AppLogger.log("Signaling", "Offer sent to peer")
    }

    fun sendAnswer(sdp: SessionDescription) {
        val sdpJson = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }
        val payload = JSONObject().apply {
            put("type", "answer")
            put("roomId", currentRoomId)
            put("sdp", sdpJson)
        }
        webSocket?.send(payload.toString())
        AppLogger.log("Signaling", "Answer sent to peer")
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val candidateJson = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("sdp", candidate.sdp)
        }
        val payload = JSONObject().apply {
            put("type", "ice_candidate")
            put("roomId", currentRoomId)
            put("candidate", candidateJson)
        }
        webSocket?.send(payload.toString())
    }

    fun endCall() {
        val payload = JSONObject().apply {
            put("type", "end_call")
            put("roomId", currentRoomId)
        }
        webSocket?.send(payload.toString())
        currentRoomId = null
        AppLogger.log("Signaling", "Sent end_call")
    }
}
