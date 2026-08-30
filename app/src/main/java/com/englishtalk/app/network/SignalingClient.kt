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

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting()
        fun onReconnectFailed(reason: String)
    }

    private const val SERVER_URL = "wss://english-talk-server-5pm7.onrender.com"
    private var webSocket: WebSocket? = null
    private var listener: SignalingListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                AppLogger.log("Signaling", "WebSocket connection opened")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")
                    val data = json.optJSONObject("data") ?: JSONObject()

                    mainHandler.post {
                        when (event) {
                            "match_found" -> {
                                val roomId = data.optString("roomId")
                                val isInitiator = data.optBoolean("isInitiator", false)
                                val peerLevel = data.optString("peerLevel", "Beginner")
                                val peerId = data.optString("peerId", "")
                                val isReconnect = data.optBoolean("isReconnect", false)
                                AppLogger.log("Signaling", "Match found! Room: $roomId, Initiator: $isInitiator")
                                listener?.onMatchFound(roomId, isInitiator, peerLevel, peerId, isReconnect)
                            }
                            "offer" -> {
                                val sdpStr = data.optString("sdp")
                                if (sdpStr.isNotEmpty()) {
                                    AppLogger.log("Signaling", "Offer received")
                                    listener?.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER, sdpStr))
                                }
                            }
                            "answer" -> {
                                val sdpStr = data.optString("sdp")
                                if (sdpStr.isNotEmpty()) {
                                    AppLogger.log("Signaling", "Answer received")
                                    listener?.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER, sdpStr))
                                }
                            }
                            "ice_candidate" -> {
                                val sdpMid = data.optString("sdpMid")
                                val sdpMLineIndex = data.optInt("sdpMLineIndex", 0)
                                val candidate = data.optString("candidate")
                                if (candidate.isNotEmpty()) {
                                    listener?.onIceCandidateReceived(IceCandidate(sdpMid, sdpMLineIndex, candidate))
                                }
                            }
                            "call_ended" -> {
                                AppLogger.log("Signaling", "Call ended by peer")
                                listener?.onCallEnded()
                            }
                            "reconnect_waiting" -> {
                                AppLogger.log("Signaling", "Awaiting reconnect partner")
                                listener?.onReconnectWaiting()
                            }
                            "reconnect_failed" -> {
                                val reason = data.optString("reason", "Unknown")
                                AppLogger.log("Signaling", "Reconnect failed: $reason")
                                listener?.onReconnectFailed(reason)
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.log("Signaling-ERR", "Msg parse error: ${e.message}")
                }
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
                mainHandler.postDelayed({ connect() }, 5000L)
            }
        })
    }

    fun joinQueue(level: String, userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        val payload = JSONObject().apply {
            put("event", "join_queue")
            put("data", JSONObject().apply {
                put("level", level)
                put("userGender", userGender)
                put("talkToFemaleOnly", talkToFemaleOnly)
                put("isVip", isVip)
            })
        }
        sendJson(payload)
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("event", "leave_queue")
            put("data", JSONObject())
        }
        sendJson(payload)
    }

    fun requestReconnect(targetPeerId: String, myLevel: String) {
        val payload = JSONObject().apply {
            put("event", "request_reconnect")
            put("data", JSONObject().apply {
                put("targetPeerId", targetPeerId)
                put("myLevel", myLevel)
            })
        }
        sendJson(payload)
    }

    fun cancelReconnect() {
        val payload = JSONObject().apply {
            put("event", "cancel_reconnect")
            put("data", JSONObject())
        }
        sendJson(payload)
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("event", "offer")
            put("data", JSONObject().apply {
                put("sdp", sdp.description)
            })
        }
        sendJson(payload)
    }

    fun sendAnswer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("event", "answer")
            put("data", JSONObject().apply {
                put("sdp", sdp.description)
            })
        }
        sendJson(payload)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val payload = JSONObject().apply {
            put("event", "ice_candidate")
            put("data", JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }
        sendJson(payload)
    }

    fun endCall() {
        val payload = JSONObject().apply {
            put("event", "end_call")
            put("data", JSONObject())
        }
        sendJson(payload)
    }

    private fun sendJson(json: JSONObject) {
        webSocket?.send(json.toString())
    }
}
