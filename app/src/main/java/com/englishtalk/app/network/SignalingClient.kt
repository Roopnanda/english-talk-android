package com.englishtalk.app.network

import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    private val client = OkHttpClient.Builder().build()
    private var webSocket: WebSocket? = null

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
    }

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }
        })
    }

    fun joinQueue(level: String, isVip: Boolean) {
        val payload = JSONObject().apply {
            put("type", "ACTION_JOIN_QUEUE")
            put("level", level)
            put("isVip", isVip)
        }
        webSocket?.send(payload.toString())
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("type", "ACTION_LEAVE_QUEUE")
        }
        webSocket?.send(payload.toString())
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("type", "SIGNAL_OFFER")
            put("sdp", sdp.description)
        }
        webSocket?.send(payload.toString())
    }

    fun sendAnswer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("type", "SIGNAL_ANSWER")
            put("sdp", sdp.description)
        }
        webSocket?.send(payload.toString())
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val payload = JSONObject().apply {
            put("type", "SIGNAL_ICE_CANDIDATE")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("sdp", candidate.sdp)
        }
        webSocket?.send(payload.toString())
    }

    fun endCall() {
        val payload = JSONObject().apply {
            put("type", "ACTION_END_CALL")
        }
        webSocket?.send(payload.toString())
    }

    private fun handleIncomingMessage(text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "EVENT_MATCH_FOUND" -> {
                listener.onMatchFound(
                    json.getString("roomId"),
                    json.getBoolean("isInitiator"),
                    json.optString("peerLevel", "Intermediate")
                )
            }
            "SIGNAL_OFFER" -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
                listener.onOfferReceived(sdp)
            }
            "SIGNAL_ANSWER" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
                listener.onAnswerReceived(sdp)
            }
            "SIGNAL_ICE_CANDIDATE" -> {
                val candidate = IceCandidate(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("sdp")
                )
                listener.onIceCandidateReceived(candidate)
            }
            "EVENT_CALL_ENDED" -> {
                listener.onCallEnded()
            }
        }
    }
}
