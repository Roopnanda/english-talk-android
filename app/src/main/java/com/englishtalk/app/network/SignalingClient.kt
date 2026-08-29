package com.englishtalk.app.network

import com.englishtalk.app.utils.AppLogger
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

object SignalingClient {

    private const val SERVER_URL = "https://your-signaling-server.onrender.com"
    private var socket: Socket? = null
    var currentRoomId: String? = null

    interface SignalingListener {
        fun onMatchFound(roomId: String, isInitiator: Boolean, peerLevel: String, peerId: String, isReconnect: Boolean)
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallEnded()
        fun onReconnectWaiting() {}
        fun onReconnectFailed(reason: String) {}
    }

    private var listener: SignalingListener? = null

    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }

    fun connect() {
        if (socket != null && socket!!.connected()) return

        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = 10
                reconnectionDelay = 2000
                timeout = 10000
            }
            socket = IO.socket(SERVER_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                AppLogger.log("Signaling", "Connected: ${socket?.id()}")
            }

            socket?.on("match_found") { args ->
                if (args.isNotEmpty()) {
                    val data = args[0] as JSONObject
                    val roomId = data.optString("roomId")
                    val isInitiator = data.optBoolean("isInitiator", false)
                    val peerLevel = data.optString("peerLevel", "Intermediate")
                    val peerId = data.optString("peerId", "")
                    val isReconnect = data.optBoolean("isReconnect", false)

                    currentRoomId = roomId
                    AppLogger.log("Signaling", "Match found! Room: $roomId, Reconnect: $isReconnect")
                    listener?.onMatchFound(roomId, isInitiator, peerLevel, peerId, isReconnect)
                }
            }

            socket?.on("reconnect_waiting") {
                AppLogger.log("Signaling", "Waiting for partner to click reconnect...")
                listener?.onReconnectWaiting()
            }

            socket?.on("reconnect_failed") { args ->
                val reason = if (args.isNotEmpty()) (args[0] as JSONObject).optString("reason", "Unavailable") else "Unavailable"
                AppLogger.log("Signaling", "Reconnect failed: $reason")
                listener?.onReconnectFailed(reason)
            }

            socket?.on("offer") { args ->
                if (args.isNotEmpty()) {
                    val descObj = args[0] as JSONObject
                    val sdp = SessionDescription(
                        SessionDescription.Type.OFFER,
                        descObj.getString("description")
                    )
                    listener?.onOfferReceived(sdp)
                }
            }

            socket?.on("answer") { args ->
                if (args.isNotEmpty()) {
                    val descObj = args[0] as JSONObject
                    val sdp = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        descObj.getString("description")
                    )
                    listener?.onAnswerReceived(sdp)
                }
            }

            socket?.on("ice_candidate") { args ->
                if (args.isNotEmpty()) {
                    val data = args[0] as JSONObject
                    val candidate = IceCandidate(
                        data.getString("sdpMid"),
                        data.getInt("sdpMLineIndex"),
                        data.getString("sdp")
                    )
                    listener?.onIceCandidateReceived(candidate)
                }
            }

            socket?.on("call_ended") {
                AppLogger.log("Signaling", "Remote peer ended the call")
                currentRoomId = null
                listener?.onCallEnded()
            }

            socket?.connect()
        } catch (e: Exception) {
            AppLogger.log("Signaling-ERR", "Connect fail: ${e.message}")
        }
    }

    fun joinQueue(level: String, userGender: String, talkToFemaleOnly: Boolean, isVip: Boolean) {
        val payload = JSONObject().apply {
            put("level", level)
            put("userGender", userGender)
            put("talkToFemaleOnly", talkToFemaleOnly)
            put("isVip", isVip)
        }
        socket?.emit("join_queue", payload)
        AppLogger.log("Signaling", "Joined queue: $level")
    }

    fun leaveQueue() {
        socket?.emit("leave_queue")
        AppLogger.log("Signaling", "Left queue")
    }

    fun requestReconnect(targetPeerId: String, myLevel: String) {
        val payload = JSONObject().apply {
            put("targetPeerId", targetPeerId)
            put("myLevel", myLevel)
        }
        socket?.emit("request_reconnect", payload)
        AppLogger.log("Signaling", "Requested mutual reconnect to: $targetPeerId")
    }

    fun cancelReconnect() {
        socket?.emit("cancel_reconnect")
        AppLogger.log("Signaling", "Cancelled reconnect request")
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("roomId", currentRoomId)
            put("sdp", JSONObject().apply {
                put("type", "offer")
                put("description", sdp.description)
            })
        }
        socket?.emit("offer", payload)
    }

    fun sendAnswer(sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("roomId", currentRoomId)
            put("sdp", JSONObject().apply {
                put("type", "answer")
                put("description", sdp.description)
            })
        }
        socket?.emit("answer", payload)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val payload = JSONObject().apply {
            put("roomId", currentRoomId)
            put("candidate", JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            })
        }
        socket?.emit("ice_candidate", payload)
    }

    fun endCall() {
        if (currentRoomId != null) {
            val payload = JSONObject().apply {
                put("roomId", currentRoomId)
            }
            socket?.emit("end_call", payload)
            currentRoomId = null
        }
    }
}
