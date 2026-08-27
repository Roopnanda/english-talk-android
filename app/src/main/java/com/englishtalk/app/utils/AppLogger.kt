package com.englishtalk.app.utils

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    val logs = mutableStateListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        android.util.Log.d(tag, message)
        if (logs.size > 200) {
            logs.removeAt(0)
        }
        logs.add(entry)
    }

    fun clear() {
        logs.clear()
    }
}
