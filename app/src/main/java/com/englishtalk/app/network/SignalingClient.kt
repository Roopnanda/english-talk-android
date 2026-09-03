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
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var listener: SignalingListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting()
        fun onReconnectFailed(reason: String)
        fun onServerCooldown(remainingSeconds: Long)
    }

    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }

    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                AppLogger.log("Signaling", "WebSocket connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                mainHandler.post { handleMessage(text) }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                AppLogger.log("Signaling", "WebSocket closing: $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                AppLogger.log("Signaling", "WebSocket closed")
                webSocket = null
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                AppLogger.log("Signaling-ERR", "WebSocket failure: ${t.message}")
                webSocket = null
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "match_found" -> {
                    val roomId = json.optString("roomId")
                    val isInitiator = json.optBoolean("isInitiator")
                    val peerLevel = json.optString("peerLevel")
                    val peerId = json.optString("peerId")
                    val isReconnect = json.optBoolean("isReconnect", false)
                    listener?.onMatchFound(roomId, isInitiator, peerLevel, peerId, isReconnect)
                }
                "offer" -> {
                    val sdpStr = json.optString("sdp")
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
                    listener?.onOfferReceived(sdp)
                }
                "answer" -> {
                    val sdpStr = json.optString("sdp")
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                    listener?.onAnswerReceived(sdp)
                }
                "ice_candidate" -> {
                    val sdpMid = json.optString("sdpMid")
                    val sdpMLineIndex = json.optInt("sdpMLineIndex")
                    val sdp = json.optString("candidate")
                    val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                    listener?.onIceCandidateReceived(candidate)
                }
                "call_ended" -> {
                    listener?.onCallEnded()
                }
                "reconnect_waiting" -> {
                    listener?.onReconnectWaiting()
                }
                "reconnect_failed" -> {
                    val reason = json.optString("reason", "unavailable")
                    listener?.onReconnectFailed(reason)
                }
                "server_cooldown" -> {
                    val remaining = json.optLong("remainingSeconds", 180L)
                    listener?.onServerCooldown(remaining)
                }
            }
        } catch (e: Throwable) {
            AppLogger.log("Signaling-ERR", "Parse error: ${e.message}")
        }
    }

    fun joinQueue(level: String, language: String, userGender: String, isFemaleOnly: Boolean, isVip: Boolean, hasFemalePass: Boolean = false) {
        val json = JSONObject().apply {
            put("action", "join_queue")
            put("level", level)
            put("language", language)
            put("gender", userGender)
            put("femaleOnly", isFemaleOnly)
            put("isVip", isVip || hasFemalePass)
            put("hasFemalePass", hasFemalePass)
        }
        send(json.toString())
    }

    fun leaveQueue() {
        val json = JSONObject().apply { put("action", "leave_queue") }
        send(json.toString())
    }

    fun requestReconnect(peerId: String, level: String) {
        val json = JSONObject().apply {
            put("action", "request_reconnect")
            put("targetPeerId", peerId)
            put("level", level)
        }
        send(json.toString())
    }

    fun cancelReconnect() {
        val json = JSONObject().apply { put("action", "cancel_reconnect") }
        send(json.toString())
    }

    fun sendOffer(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("action", "send_offer")
            put("sdp", sdp.description)
        }
        send(json.toString())
    }

    fun sendAnswer(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("action", "send_answer")
            put("sdp", sdp.description)
        }
        send(json.toString())
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("action", "send_ice")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        send(json.toString())
    }

    fun endCall() {
        val json = JSONObject().apply { put("action", "end_call") }
        send(json.toString())
    }

    fun reportUser(peerId: String) {
        val json = JSONObject().apply {
            put("action", "report_user")
            put("reportedPeerId", peerId)
            put("reason", "harassment")
        }
        send(json.toString())
    }

    fun reportGenderMismatch(peerId: String) {
        val json = JSONObject().apply {
            put("action", "report_user")
            put("reportedPeerId", peerId)
            put("reason", "not_female")
        }
        send(json.toString())
    }

    private fun send(message: String) {
        webSocket?.send(message)
    }
}
