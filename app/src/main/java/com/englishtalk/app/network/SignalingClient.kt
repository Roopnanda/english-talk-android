package com.englishtalk.app.network

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

object SignalingClient {

    private const val SERVER_URL = "wss://english-talk-server-5pm7.onrender.com"
    private var webSocket: WebSocket? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var listener: SignalingListener? = null
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReconnecting = false
    private var pendingQueueAction: (() -> Unit)? = null
    private var activeQueuePayload: JSONObject? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected && webSocket != null) {
                try {
                    val ping = JSONObject().put("action", "ping")
                    val sent = webSocket?.send(ping.toString()) ?: false
                    if (!sent) {
                        isConnected = false
                        forceReconnect()
                    }
                } catch (e: Throwable) {
                    isConnected = false
                    forceReconnect()
                }
            }
            mainHandler.postDelayed(this, 10000L)
        }
    }

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting()
        fun onReconnectFailed(reason: String)
        fun onServerCooldown(remainingSeconds: Long)
        fun onVipSearchExpanding()
        fun onVipQueueTimeout()
    }

    fun setListener(l: SignalingListener) {
        this.listener = l
    }

    fun ensureActiveConnection() {
        if (webSocket == null || !isConnected) {
            forceReconnect()
            return
        }

        // Active socket probe to detect silent OS-level freeze
        try {
            val ping = JSONObject().put("action", "ping")
            val active = webSocket?.send(ping.toString()) ?: false
            if (!active) {
                forceReconnect()
            }
        } catch (e: Throwable) {
            forceReconnect()
        }
    }

    fun forceReconnect() {
        try {
            webSocket?.cancel()
        } catch (e: Throwable) {}
        webSocket = null
        isConnected = false
        isReconnecting = false
        connect()
    }

    fun connect() {
        if (isConnected || isReconnecting) return
        isReconnecting = true

        try {
            val request = Request.Builder().url(SERVER_URL).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnected = true
                    isReconnecting = false
                    mainHandler.removeCallbacks(heartbeatRunnable)
                    mainHandler.post(heartbeatRunnable)

                    if (pendingQueueAction != null) {
                        pendingQueueAction?.invoke()
                        pendingQueueAction = null
                    } else if (activeQueuePayload != null) {
                        try {
                            ws.send(activeQueuePayload.toString())
                        } catch (e: Throwable) {}
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    isConnected = false
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    scheduleReconnect()
                }
            })
        } catch (e: Throwable) {
            isConnected = false
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        mainHandler.postDelayed({
            isReconnecting = false
            connect()
        }, 1500L)
    }

    private fun ensureConnected(onReady: () -> Unit) {
        if (isConnected && webSocket != null) {
            onReady()
        } else {
            pendingQueueAction = onReady
            forceReconnect()
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            mainHandler.post {
                when (type) {
                    "match_found" -> {
                        activeQueuePayload = null
                        val roomId = json.getString("roomId")
                        val isInitiator = json.getBoolean("isInitiator")
                        val peerLevel = json.optString("peerLevel", "Beginner")
                        val peerId = json.optString("peerId", "")
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
                        val sdpMid = json.getString("sdpMid")
                        val sdpMLineIndex = json.getInt("sdpMLineIndex")
                        val candidate = json.getString("candidate")
                        listener?.onIceCandidateReceived(IceCandidate(sdpMid, sdpMLineIndex, candidate))
                    }
                    "call_ended" -> listener?.onCallEnded()
                    "reconnect_waiting" -> listener?.onReconnectWaiting()
                    "reconnect_failed" -> {
                        activeQueuePayload = null
                        listener?.onReconnectFailed(json.optString("reason", "unknown"))
                    }
                    "server_cooldown" -> {
                        activeQueuePayload = null
                        listener?.onServerCooldown(json.optLong("remainingSeconds", 180L))
                    }
                    "vip_search_expanding" -> listener?.onVipSearchExpanding()
                    "vip_queue_timeout" -> listener?.onVipQueueTimeout()
                    "pong" -> {
                        isConnected = true
                    }
                }
            }
        } catch (e: Throwable) {}
    }

    fun joinQueue(level: String, language: String, userGender: String, isFemaleOnly: Boolean, isVip: Boolean, hasFemalePass: Boolean) {
        val json = JSONObject().apply {
            put("action", "join_queue")
            put("level", level)
            put("language", language)
            put("gender", userGender)
            put("femaleOnly", isFemaleOnly)
            put("isVip", isVip)
            put("hasFemalePass", hasFemalePass)
        }
        activeQueuePayload = json

        ensureConnected {
            try {
                webSocket?.send(json.toString())
            } catch (e: Throwable) {}
        }
    }

    fun leaveQueue() {
        activeQueuePayload = null
        pendingQueueAction = null
        try {
            val json = JSONObject().put("action", "leave_queue")
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun extendVipWait() {
        ensureConnected {
            try {
                val json = JSONObject().put("action", "extend_vip_wait")
                webSocket?.send(json.toString())
            } catch (e: Throwable) {}
        }
    }

    fun fallbackToGeneral() {
        ensureConnected {
            try {
                val json = JSONObject().put("action", "fallback_to_general")
                webSocket?.send(json.toString())
            } catch (e: Throwable) {}
        }
    }

    fun requestReconnect(targetPeerId: String, level: String) {
        val json = JSONObject().apply {
            put("action", "request_reconnect")
            put("targetPeerId", targetPeerId)
            put("level", level)
        }
        activeQueuePayload = json

        ensureConnected {
            try {
                webSocket?.send(json.toString())
            } catch (e: Throwable) {}
        }
    }

    fun cancelReconnect() {
        activeQueuePayload = null
        pendingQueueAction = null
        try {
            val json = JSONObject().put("action", "cancel_reconnect")
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun sendOffer(sdp: SessionDescription) {
        try {
            val json = JSONObject().apply {
                put("action", "send_offer")
                put("sdp", sdp.description)
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun sendAnswer(sdp: SessionDescription) {
        try {
            val json = JSONObject().apply {
                put("action", "send_answer")
                put("sdp", sdp.description)
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val json = JSONObject().apply {
                put("action", "send_ice")
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun endCall() {
        activeQueuePayload = null
        try {
            val json = JSONObject().put("action", "end_call")
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun reportUser(reportedPeerId: String) {
        try {
            val json = JSONObject().apply {
                put("action", "report_user")
                put("reportedPeerId", reportedPeerId)
                put("reason", "harassment")
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }

    fun reportGenderMismatch(reportedPeerId: String) {
        try {
            val json = JSONObject().apply {
                put("action", "report_user")
                put("reportedPeerId", reportedPeerId)
                put("reason", "not_female")
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {}
    }
}
