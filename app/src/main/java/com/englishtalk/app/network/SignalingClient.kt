package com.englishtalk.app.network

import com.englishtalk.app.utils.AppLogger
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var currentRoomId: String? = null

    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.log("Signaling", "WebSocket connected successfully")
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
                            AppLogger.log("Signaling", "Match confirmed: room=$roomId initiator=$isInitiator")
                            listener.onMatchFound(roomId, isInitiator, peerLevel)
                        }
                        "offer" -> {
                            val sdpObj = json.getJSONObject("sdp")
                            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpObj.getString("sdp"))
                            listener.onOfferReceived(sdp)
                        }
                        "answer" -> {
                            val sdpObj = json.getJSONObject("sdp")
                            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpObj.getString("sdp"))
                            listener.onAnswerReceived(sdp)
                        }
                        "ice_candidate" -> {
                            val candidateObj = json.getJSONObject("candidate")
                            val candidate = IceCandidate(
                                candidateObj.getString("sdpMid"),
                                candidateObj.getInt("sdpMLineIndex"),
                                candidateObj.getString("sdp")
                            )
                            listener.onIceCandidateReceived(candidate)
                        }
                        "call_ended", "peer_disconnected" -> {
                            AppLogger.log("Signaling", "Remote end-call received")
                            currentRoomId = null
                            listener.onCallEnded()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.log("Signaling-ERR", "Message parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLogger.log("Signaling-ERR", "WebSocket failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.log("Signaling", "WebSocket closed: $reason")
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
        webSocket?.send(payload.toString())
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("type", "leave_queue")
        }
        webSocket?.send(payload.toString())
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
    }

    fun setRoomId(roomId: String) {
        this.currentRoomId = roomId
    }
}
