package com.englishtalk.app.network

import android.os.Handler
import android.os.Looper
import com.englishtalk.app.utils.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

object SignalingClient {

    private const val WS_SERVER_URL = "wss://english-talk-server-5pm7.onrender.com"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    var currentRoomId: String? = null

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting()
        fun onReconnectFailed(reason: String)
    }

    private var listener: SignalingListener? = null

    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }

    fun connect() {
        if (webSocket != null) return

        try {
            val request = Request.Builder()
                .url(WS_SERVER_URL)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    AppLogger.log("Signaling", "WebSocket connection opened")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val event = json.optString("event")
                        val data = json.optJSONObject("data") ?: JSONObject()

                        when (event) {
                            "match_found" -> {
                                val roomId = data.optString("roomId")
                                val isInitiator = data.optBoolean("isInitiator", false)
                                val peerLevel = data.optString("peerLevel", "Beginner")
                                val peerId = data.optString("peerId", "")
                                val isReconnect = data.optBoolean("isReconnect", false)

                                currentRoomId = roomId
                                AppLogger.log("Signaling", "Match found! Room: $roomId, Reconnect: $isReconnect")
                                mainHandler.post {
                                    listener?.onMatchFound(roomId, isInitiator, peerLevel, peerId, isReconnect)
                                }
                            }
                            "reconnect_waiting" -> {
                                AppLogger.log("Signaling", "Waiting for partner to click reconnect...")
                                mainHandler.post {
                                    listener?.onReconnectWaiting()
                                }
                            }
                            "reconnect_failed" -> {
                                val reason = data.optString("reason", "Unavailable")
                                AppLogger.log("Signaling", "Reconnect failed: $reason")
                                mainHandler.post {
                                    listener?.onReconnectFailed(reason)
                                }
                            }
                            "offer" -> {
                                val sdpStr = data.optString("sdp")
                                val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
                                mainHandler.post {
                                    listener?.onOfferReceived(sdp)
                                }
                            }
                            "answer" -> {
                                val sdpStr = data.optString("sdp")
                                val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                                mainHandler.post {
                                    listener?.onAnswerReceived(sdp)
                                }
                            }
                            "ice_candidate" -> {
                                val candidate = IceCandidate(
                                    data.getString("sdpMid"),
                                    data.getInt("sdpMLineIndex"),
                                    data.getString("sdp")
                                )
                                mainHandler.post {
                                    listener?.onIceCandidateReceived(candidate)
                                }
                            }
                            "call_ended" -> {
                                AppLogger.log("Signaling", "Remote peer ended the call")
                                currentRoomId = null
                                mainHandler.post {
                                    listener?.onCallEnded()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.log("Signaling-ERR", "Msg parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    AppLogger.log("Signaling-ERR", "WebSocket failure: ${t.message}")
                    SignalingClient.webSocket = null
                    mainHandler.postDelayed({ connect() }, 3000L)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    AppLogger.log("Signaling", "WebSocket closed: $reason")
                    SignalingClient.webSocket = null
                }
            })
        } catch (e: Exception) {
            AppLogger.log("Signaling-ERR", "Connect error: ${e.message}")
        }
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
        AppLogger.log("Signaling", "Joined queue: $level")
    }

    fun leaveQueue() {
        val payload = JSONObject().apply {
            put("event", "leave_queue")
        }
        sendJson(payload)
        AppLogger.log("Signaling", "Left queue")
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
        AppLogger.log("Signaling", "Requested mutual reconnect to: $targetPeerId")
    }

    fun cancelReconnect() {
        val payload = JSONObject().apply {
            put("event", "cancel_reconnect")
        }
        sendJson(payload)
        AppLogger.log("Signaling", "Cancelled reconnect request")
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("event", "offer")
            put("data", JSONObject().apply {
                put("roomId", currentRoomId)
                put("sdp", sdp.description)
            })
        }
        sendJson(payload)
    }

    fun sendAnswer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("event", "answer")
            put("data", JSONObject().apply {
                put("roomId", currentRoomId)
                put("sdp", sdp.description)
            })
        }
        sendJson(payload)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val payload = JSONObject().apply {
            put("event", "ice_candidate")
            put("data", JSONObject().apply {
                put("roomId", currentRoomId)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            })
        }
        sendJson(payload)
    }

    fun endCall() {
        if (currentRoomId != null) {
            val payload = JSONObject().apply {
                put("event", "end_call")
                put("data", JSONObject().apply {
                    put("roomId", currentRoomId)
                })
            }
            sendJson(payload)
            currentRoomId = null
        }
    }

    private fun sendJson(json: JSONObject) {
        webSocket?.send(json.toString())
    }
}
