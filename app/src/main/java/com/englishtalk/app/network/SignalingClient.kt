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

    private var webSocket: WebSocket? = null
    private var listener: SignalingListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting()
        fun onReconnectFailed(reason: String)
    }

    fun setListener(l: SignalingListener) {
        this.listener = l
    }

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                AppLogger.log("Signaling", "WebSocket connection opened")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                AppLogger.log("Signaling", "WebSocket closing: $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                AppLogger.log("Signaling", "WebSocket closed: $reason")
                webSocket = null
                reconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                AppLogger.log("Signaling-ERR", "WebSocket failure: ${t.message}")
                webSocket = null
                reconnect()
            }
        })
    }

    private fun reconnect() {
        mainHandler.postDelayed({
            if (webSocket == null) {
                connect()
            }
        }, 3000L)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            mainHandler.post {
                when (type) {
                    "match_found" -> {
                        val roomId = json.optString("roomId")
                        val isInitiator = json.optBoolean("isInitiator")
                        val peerLevel = json.optString("peerLevel", "Peer")
                        val peerId = json.optString("peerId", "")
                        val isReconnect = json.optBoolean("isReconnect", false)
                        AppLogger.log("Signaling", "Match confirmed: $roomId (Initiator: $isInitiator, Peer: $peerId)")
                        listener?.onMatchFound(roomId, isInitiator, peerLevel, peerId, isReconnect)
                    }
                    "offer" -> {
                        val sdp = json.optString("sdp")
                        listener?.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER, sdp))
                    }
                    "answer" -> {
                        val sdp = json.optString("sdp")
                        listener?.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                    "ice_candidate", "candidate" -> {
                        val sdpMid = json.optString("sdpMid")
                        val sdpMLineIndex = json.optInt("sdpMLineIndex")
                        val sdp = json.optString("candidate")
                        listener?.onIceCandidateReceived(IceCandidate(sdpMid, sdpMLineIndex, sdp))
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
            AppLogger.log("Signaling-ERR", "Parse error: ${e.message}")
        }
    }

    fun joinQueue(level: String, language: String, userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        if (webSocket == null) connect()
        try {
            val json = JSONObject().apply {
                put("type", "join_queue")
                put("level", level)
                put("language", language)
                put("userGender", userGender)
                put("talkToFemaleOnly", talkToFemaleOnly)
                put("isVip", isVip)
            }
            webSocket?.send(json.toString())
            AppLogger.log("Queue-OUT", "Dispatched join_queue for $level in $language")
        } catch (e: Throwable) {
            AppLogger.log("Signaling-ERR", "Send error: ${e.message}")
        }
    }

    fun leaveQueue() {
        try {
            val json = JSONObject().apply {
                put("type", "leave_queue")
            }
            webSocket?.send(json.toString())
            AppLogger.log("Queue-OUT", "Dispatched leave_queue")
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    fun requestReconnect(targetPeerId: String, level: String) {
        try {
            val json = JSONObject().apply {
                put("type", "request_reconnect")
                put("targetPeerId", targetPeerId)
                put("level", level)
            }
            webSocket?.send(json.toString())
            AppLogger.log("Reconnect-OUT", "Dispatched reconnect request to $targetPeerId")
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    fun cancelReconnect() {
        try {
            val json = JSONObject().apply {
                put("type", "cancel_reconnect")
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    fun sendOffer(sdp: SessionDescription) {
        try {
            val json = JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp.description)
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {
            AppLogger.log("Signaling-ERR", "Send offer error: ${e.message}")
        }
    }

    fun sendAnswer(sdp: SessionDescription) {
        try {
            val json = JSONObject().apply {
                put("type", "answer")
                put("sdp", sdp.description)
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {
            AppLogger.log("Signaling-ERR", "Send answer error: ${e.message}")
        }
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val json = JSONObject().apply {
                put("type", "ice_candidate")
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {
            AppLogger.log("Signaling-ERR", "Send ICE error: ${e.message}")
        }
    }

    fun endCall() {
        try {
            val json = JSONObject().apply {
                put("type", "end_call")
            }
            webSocket?.send(json.toString())
        } catch (e: Throwable) {
            // Safe fallback
        }
    }
}
