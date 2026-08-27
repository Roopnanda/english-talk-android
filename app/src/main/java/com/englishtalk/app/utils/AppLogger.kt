package com.englishtalk.app.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {

    private const val TAG = "EnglishTalk"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logBuffer = LinkedList<String>()
    private const val MAX_LOGS = 25

    var onLogUpdated: ((String) -> Unit)? = null

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        
        Log.d(TAG, entry)

        synchronized(logBuffer) {
            logBuffer.addFirst(entry)
            if (logBuffer.size > MAX_LOGS) {
                logBuffer.removeLast()
            }
            val aggregated = logBuffer.joinToString("\n")
            onLogUpdated?.invoke(aggregated)
        }
    }
}
