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

    private const val WS_URL = "wss://english-talk-server-5pm7.onrender.com"

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
    }

    private var listener: SignalingListener? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    var currentRoomId: String? = null
    var isConnected = false
        private set

    fun setListener(listener: SignalingListener?) {
        this.listener = listener
    }

    fun connect() {
        if (isConnected || webSocket != null) return
        AppLogger.log("Signaling", "Connecting to WebSocket server...")

        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                AppLogger.log("Signaling", "WebSocket Connected (Heartbeat Active)")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "match_found" -> {
                            val roomId = json.getString("roomId")
                            val isInitiator = json.getBoolean("isInitiator")
                            val peerLevel = json.optString("peerLevel", "Intermediate")
                            currentRoomId = roomId
                            AppLogger.log("Signaling", "Match confirmed: room=$roomId initiator=$isInitiator")
                            handler.post { listener?.onMatchFound(roomId, isInitiator, peerLevel) }
                        }
                        "offer" -> {
                            val sdpString = json.getString("sdp")
                            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
                            handler.post { listener?.onOfferReceived(sdp) }
                        }
                        "answer" -> {
                            val sdpString = json.getString("sdp")
                            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
                            handler.post { listener?.onAnswerReceived(sdp) }
                        }
                        "ice_candidate" -> {
                            val candJson = json.getJSONObject("candidate")
                            val candidate = IceCandidate(
                                candJson.getString("sdpMid"),
                                candJson.getInt("sdpMLineIndex"),
                                candJson.getString("sdp")
                            )
                            handler.post { listener?.onIceCandidateReceived(candidate) }
                        }
                        "call_ended" -> {
                            AppLogger.log("Signaling", "Remote end-call received")
                            handler.post { listener?.onCallEnded() }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.log("Signaling-ERR", "Msg parse fail: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                webSocket = null
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                webSocket = null
                AppLogger.log("Signaling-ERR", "WebSocket dropped: ${t.message}")
                handler.postDelayed({ connect() }, 3000L)
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
        send(payload.toString())
        AppLogger.log("Signaling", "Sending join_queue (level: $level)...")
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("type", "leave_queue")
        }
        send(payload.toString())
        AppLogger.log("Signaling", "Sent leave_queue")
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("type", "offer")
            put("roomId", currentRoomId)
            put("sdp", sdp.description)
        }
        send(payload.toString())
        AppLogger.log("Signaling", "Offer sent to peer")
    }

    fun sendAnswer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("type", "answer")
            put("roomId", currentRoomId)
            put("sdp", sdp.description)
        }
        send(payload.toString())
        AppLogger.log("Signaling", "Answer sent to peer")
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val candJson = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("sdp", candidate.sdp)
        }
        val payload = JSONObject().apply {
            put("type", "ice_candidate")
            put("roomId", currentRoomId)
            put("candidate", candJson)
        }
        send(payload.toString())
    }

    fun endCall() {
        val payload = JSONObject().apply {
            put("type", "end_call")
            put("roomId", currentRoomId)
        }
        send(payload.toString())
        AppLogger.log("Signaling", "Sent end_call")
        currentRoomId = null
    }

    private fun send(message: String) {
        try {
            webSocket?.send(message)
        } catch (e: Exception) {
            AppLogger.log("Signaling-ERR", "Send error: ${e.message}")
        }
    }
}
