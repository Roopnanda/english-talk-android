package com.englishtalk.app.network

import android.os.Handler
import android.os.Looper
import com.englishtalk.app.utils.AppLogger
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

object SignalingClient {

    private const val SERVER_URL = "wss://english-talk-server-5pm7.onrender.com"

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting()
        fun onReconnectFailed(reason: String)
    }

    private var webSocket: WebSocket? = null
    private var listener: SignalingListener? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isConnected = false

    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }

    fun connect() {
        if (isConnected) return

        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                AppLogger.log("Signaling", "WebSocket connection opened")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                AppLogger.log("Signaling", "WebSocket closed: $reason ($code)")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                AppLogger.log("Signaling-ERR", "WebSocket failure: ${t.message}")
                mainHandler.postDelayed({ connect() }, 3000L)
            }
        })
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")

            mainHandler.post {
                when (event) {
                    "match_found" -> {
                        val roomId = json.getString("roomId")
                        val isInitiator = json.getBoolean("isInitiator")
                        val peerLevel = json.optString("peerLevel", "Beginner")
                        val peerId = json.getString("peerId")
                        val isReconnect = json.optBoolean("isReconnect", false)
                        listener?.onMatchFound(roomId, isInitiator, peerLevel, peerId, isReconnect)
                    }
                    "offer" -> {
                        val sdp = json.getString("sdp")
                        listener?.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER, sdp))
                    }
                    "answer" -> {
                        val sdp = json.getString("sdp")
                        listener?.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                    "ice_candidate" -> {
                        val candJson = json.getJSONObject("candidate")
                        val candidate = IceCandidate(
                            candJson.getString("sdpMid"),
                            candJson.getInt("sdpMLineIndex"),
                            candJson.getString("sdp")
                        )
                        listener?.onIceCandidateReceived(candidate)
                    }
                    "call_ended" -> {
                        listener?.onCallEnded()
                    }
                    "reconnect_waiting" -> {
                        listener?.onReconnectWaiting()
                    }
                    "reconnect_failed" -> {
                        val reason = json.optString("reason", "Unavailable")
                        listener?.onReconnectFailed(reason)
                    }
                }
            }
        } catch (e: Throwable) {
            AppLogger.log("Signaling-ERR", "Error parsing message: ${e.message}")
        }
    }

    fun joinQueue(level: String, language: String = "ENGLISH", userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        val payload = JSONObject().apply {
            put("event", "join_queue")
            put("level", level)
            put("language", language)
            put("userGender", userGender)
            put("talkToFemaleOnly", talkToFemaleOnly)
            put("isVip", isVip)
        }
        send(payload.toString())
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("event", "leave_queue")
        }
        send(payload.toString())
    }

    fun requestReconnect(targetPeerId: String, level: String) {
        val payload = JSONObject().apply {
            put("event", "request_reconnect")
            put("targetPeerId", targetPeerId)
            put("level", level)
        }
        send(payload.toString())
    }

    fun cancelReconnect() {
        val payload = JSONObject().apply {
            put("event", "cancel_reconnect")
        }
        send(payload.toString())
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("event", "offer")
            put("sdp", sdp.description)
        }
        send(payload.toString())
    }

    fun sendAnswer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("event", "answer")
            put("sdp", sdp.description)
        }
        send(payload.toString())
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val candJson = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("sdp", candidate.sdp)
        }
        val payload = JSONObject().apply {
            put("event", "ice_candidate")
            put("candidate", candJson)
        }
        send(payload.toString())
    }

    fun endCall() {
        val payload = JSONObject().apply {
            put("event", "end_call")
        }
        send(payload.toString())
    }

    private fun send(message: String) {
        if (isConnected && webSocket != null) {
            webSocket?.send(message)
        } else {
            AppLogger.log("Signaling-ERR", "Cannot send message: WebSocket disconnected")
        }
    }
}
