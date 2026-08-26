package com.englishtalk.app.network

import android.os.Handler
import android.os.Looper
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
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun connect() {
        if (isConnected && webSocket != null) return

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                ws.close(1000, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                webSocket = null
                // Reconnect after 3 seconds
                mainHandler.postDelayed({ connect() }, 3000L)
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
                    val peerLevel = json.optString("peerLevel", "Intermediate")
                    mainHandler.post {
                        listener.onMatchFound(roomId, isInitiator, peerLevel)
                    }
                }
                "offer" -> {
                    val sdp = json.optString("sdp")
                    mainHandler.post {
                        listener.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER, sdp))
                    }
                }
                "answer" -> {
                    val sdp = json.optString("sdp")
                    mainHandler.post {
                        listener.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                }
                "ice_candidate" -> {
                    val candidate = json.optJSONObject("candidate")
                    if (candidate != null) {
                        val ice = IceCandidate(
                            candidate.optString("sdpMid"),
                            candidate.optInt("sdpMLineIndex"),
                            candidate.optString("candidate")
                        )
                        mainHandler.post {
                            listener.onIceCandidateReceived(ice)
                        }
                    }
                }
                "call_ended" -> {
                    mainHandler.post {
                        listener.onCallEnded()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun joinQueue(level: String, userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        // Ensure connected before sending
        if (!isConnected || webSocket == null) {
            connect()
            mainHandler.postDelayed({
                joinQueue(level, userGender, talkToFemaleOnly, isVip)
            }, 1000L)
            return
        }

        val json = JSONObject().apply {
            put("action", "join_queue")
            put("level", level)
            put("userGender", if (userGender.isNotEmpty()) userGender else "Male")
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
