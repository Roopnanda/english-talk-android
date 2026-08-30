package com.englishtalk.app.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "EnglishTalk"
    private var consoleTextView: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(textView: TextView?) {
        this.consoleTextView = textView
        log("System", "Ready")
    }

    fun log(tag: String, message: String) {
        Log.d("$TAG:$tag", message)

        try {
            consoleTextView?.let { tv ->
                val timestamp = timeFormat.format(Date())
                val logLine = "[$timestamp][$tag] $message\n"

                uiHandler.post {
                    try {
                        tv.append(logLine)
                    } catch (e: Throwable) {
                        // Prevent UI logging crashes
                    }
                }
            }
        } catch (e: Throwable) {
            // Safe fallback
        }
    }
}
