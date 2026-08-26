package com.englishtalk.app.network

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
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {}

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                try {
                    Thread.sleep(2000)
                    connect()
                } catch (_: Exception) {}
            }
        })
    }

    private fun handleIncomingMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "match_found" -> {
                    val roomId = json.optString("roomId")
                    val isInitiator = json.optBoolean("isInitiator", false)
                    val peerLevel = json.optString("peerLevel", "General")
                    listener.onMatchFound(roomId, isInitiator, peerLevel)
                }
                "offer" -> {
                    val sdp = json.optString("sdp")
                    listener.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER, sdp))
                }
                "answer" -> {
                    val sdp = json.optString("sdp")
                    listener.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                "ice_candidate" -> {
                    val candidate = json.optJSONObject("candidate")
                    if (candidate != null) {
                        listener.onIceCandidateReceived(
                            IceCandidate(
                                candidate.optString("sdpMid"),
                                candidate.optInt("sdpMLineIndex"),
                                candidate.optString("candidate")
                            )
                        )
                    }
                }
                "call_ended" -> {
                    listener.onCallEnded()
                }
            }
        } catch (_: Exception) {}
    }

    fun joinQueue(level: String, userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        val json = JSONObject().apply {
            put("action", "join_queue")
            put("level", level)
            put("userGender", userGender)
            put("preferredGender", if (talkToFemaleOnly && isVip) "Female" else "Any")
            put("isVip", isVip)
        }
        webSocket?.send(json.toString())
    }

    fun leaveQueue() {
        val json = JSONObject().apply { put("action", "leave_queue") }
        webSocket?.send(json.toString())
    }

    fun sendOffer(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("action", "send_offer")
            put("sdp", sdp.description)
        }
        webSocket?.send(json.toString())
    }

    fun sendAnswer(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("action", "send_answer")
            put("sdp", sdp.description)
        }
        webSocket?.send(json.toString())
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val candidateJson = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        val json = JSONObject().apply {
            put("action", "send_ice_candidate")
            put("candidate", candidateJson)
        }
        webSocket?.send(json.toString())
    }

    fun endCall() {
        val json = JSONObject().apply { put("action", "end_call") }
        webSocket?.send(json.toString())
    }
}
