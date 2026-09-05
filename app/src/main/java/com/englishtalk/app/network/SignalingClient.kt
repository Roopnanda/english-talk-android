package com.englishtalk.app.network

import android.os.Handler
import android.os.Looper
import com.englishtalk.app.utils.AppLogger
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.io.IOException
import java.util.concurrent.TimeUnit

object SignalingClient {

    private const val SERVER_HOST = "english-talk-server-5mn7.onrender.com"
    private const val SERVER_URL = "wss://$SERVER_HOST"
    private const val HEALTH_URL = "https://$SERVER_HOST/health"

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var listener: SignalingListener? = null
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReconnecting = false
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected && webSocket != null) {
                try {
                    val ping = JSONObject().put("action", "ping")
                    webSocket?.send(ping.toString())
                } catch (e: Throwable) {
                    AppLogger.log("WS-PING", "Heartbeat send failed: ${e.message}")
                }
            }
            mainHandler.postDelayed(this, 15000L)
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

    fun connect() {
        if (isConnected || isReconnecting) return
        isReconnecting = true

        AppLogger.log("WS", "Waking up server & initiating handshake...")

        // Step 1: Send an HTTP GET to ensure Render spins up from sleep
        val healthRequest = Request.Builder().url(HEALTH_URL).build()
        client.newCall(healthRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLogger.log("WS-HTTP", "Health check failed: ${e.message}. Proceeding to socket.")
                openSocket()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                AppLogger.log("WS-HTTP", "Server is awake and healthy. Opening socket.")
                openSocket()
            }
        })
    }

    private fun openSocket() {
        mainHandler.post {
            try {
                val request = Request.Builder().url(SERVER_URL).build()
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        isConnected = true
                        isReconnecting = false
                        AppLogger.log("WS", "Connected to Render signaling server successfully")
                        mainHandler.removeCallbacks(heartbeatRunnable)
                        mainHandler.post(heartbeatRunnable)
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        handleIncomingMessage(text)
                    }

                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        isConnected = false
                        AppLogger.log("WS", "Socket closing: code=$code, reason=$reason")
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        isConnected = false
                        AppLogger.log("WS", "Socket closed cleanly. Scheduling reconnect...")
                        scheduleReconnect()
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        isConnected = false
                        AppLogger.log("WS-ERR", "Socket failure: ${t.message}. Response code=${response?.code}")
                        scheduleReconnect()
                    }
                })
            } catch (e: Throwable) {
                isConnected = false
                AppLogger.log("WS-ERR", "Immediate socket exception: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        mainHandler.postDelayed({
            isReconnecting = false
            connect()
        }, 3000L)
    }

    private fun ensureConnected(onReady: () -> Unit) {
        if (isConnected && webSocket != null) {
            onReady()
        } else {
            connect()
            mainHandler.postDelayed({
                if (isConnected && webSocket != null) {
                    onReady()
                } else {
                    AppLogger.log("WS-WARN", "Not connected yet - retrying command on next tick")
                }
            }, 1200L)
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            mainHandler.post {
                when (type) {
                    "match_found" -> {
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
                    "reconnect_failed" -> listener?.onReconnectFailed(json.optString("reason", "unknown"))
                    "server_cooldown" -> listener?.onServerCooldown(json.optLong("remainingSeconds", 180L))
                    "vip_search_expanding" -> listener?.onVipSearchExpanding()
                    "vip_queue_timeout" -> listener?.onVipQueueTimeout()
                    "pong" -> { /* Keepalive response */ }
                }
            }
        } catch (e: Throwable) {
            AppLogger.log("WS-PARSE", "Error parsing message: ${e.message}")
        }
    }

    fun joinQueue(level: String, language: String, userGender: String, isFemaleOnly: Boolean, isVip: Boolean, hasFemalePass: Boolean) {
        ensureConnected {
            try {
                val json = JSONObject().apply {
                    put("action", "join_queue")
                    put("level", level)
                    put("language", language)
                    put("gender", userGender)
                    put("femaleOnly", isFemaleOnly)
                    put("isVip", isVip)
                    put("hasFemalePass", hasFemalePass)
                }
                val sent = webSocket?.send(json.toString()) ?: false
                AppLogger.log("WS", "join_queue dispatched (success=$sent)")
            } catch (e: Throwable) {
                AppLogger.log("WS-ERR", "joinQueue error: ${e.message}")
            }
        }
    }

    fun leaveQueue() {
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
        ensureConnected {
            try {
                val json = JSONObject().apply {
                    put("action", "request_reconnect")
                    put("targetPeerId", targetPeerId)
                    put("level", level)
                }
                webSocket?.send(json.toString())
            } catch (e: Throwable) {}
        }
    }

    fun cancelReconnect() {
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
