package com.englishtalk.app.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object SignalingClient {

    private const val SERVER_URL = "wss://english-talk-server-5pm7.onrender.com"
    private var webSocket: WebSocket? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var listener: SignalingListener? = null
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReconnecting = false
    private var pendingQueueAction: (() -> Unit)? = null
    private var activeQueuePayload: JSONObject? = null
    var cachedDeviceId: String = ""
        private set

    // Rule 44: Active Call Room ID tracking
    var activeCallRoomId: String = ""
        private set

    private val backgroundExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Signaling-Ping-Thread").apply { isDaemon = true }
    }
    private var pingTask: ScheduledFuture<*>? = null

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

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("EnglishTalkPrefs", Context.MODE_PRIVATE)
        var devId = prefs.getString("unique_device_id", "") ?: ""
        if (devId.isEmpty()) {
            devId = "dev_" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("unique_device_id", devId).apply()
        }
        cachedDeviceId = devId
    }

    private fun startBackgroundPing() {
        stopBackgroundPing()
        pingTask = backgroundExecutor.scheduleWithFixedDelay({
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
        }, 3, 5, TimeUnit.SECONDS)
    }

    private fun stopBackgroundPing() {
        pingTask?.cancel(true)
        pingTask = null
    }

    fun ensureActiveConnection() {
        if (webSocket == null || !isConnected) {
            forceReconnect()
            return
        }

        backgroundExecutor.execute {
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
    }

    fun forceReconnect() {
        stopBackgroundPing()
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
                    startBackgroundPing()

                    // Rule 44: Sync active call session on reconnect
                    if (activeCallRoomId.isNotEmpty()) {
                        try {
                            val syncPayload = JSONObject().apply {
                                put("action", "sync_active_call")
                                put("roomId", activeCallRoomId)
                                put("deviceId", cachedDeviceId)
                            }
                            ws.send(syncPayload.toString())
                        } catch (e: Throwable) {}
                    }

                    if (pendingQueueAction != null) {
                        val act = pendingQueueAction
                        pendingQueueAction = null
                        act?.invoke()
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
                    stopBackgroundPing()
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    stopBackgroundPing()
                    scheduleReconnect()
                }
            })
        } catch (e: Throwable) {
            isConnected = false
            stopBackgroundPing()
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        backgroundExecutor.schedule({
            isReconnecting = false
            connect()
        }, 1000L, TimeUnit.MILLISECONDS)
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
                        activeCallRoomId = roomId
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
                    "call_ended" -> {
                        activeCallRoomId = ""
                        listener?.onCallEnded()
                    }
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
            put("deviceId", cachedDeviceId)
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
            put("deviceId", cachedDeviceId)
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
        val targetRoom = activeCallRoomId
        activeQueuePayload = null
        activeCallRoomId = ""
        try {
            val json = JSONObject().apply {
                put("action", "end_call")
                put("roomId", targetRoom)
                put("deviceId", cachedDeviceId)
            }
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
