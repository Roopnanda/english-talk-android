package com.englishtalk.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "EnglishTalk"
    private const val PREF_NAME = "EnglishTalkCrashLogs"
    private const val KEY_LOG_BUFFER = "saved_crash_log_buffer"

    private var consoleTextView: TextView? = null
    private var prefs: SharedPreferences? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, textView: TextView?) {
        this.consoleTextView = textView
        this.prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Capture uncaught fatal JVM crashes directly to persistent disk storage
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = throwable.stackTrace.take(8).joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            val fatalMsg = "FATAL EXCEPTION on [${thread.name}]: ${throwable::class.java.simpleName}: ${throwable.message}\n$stack"
            log("CRASH-TRAP", fatalMsg)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        log("System", "Diagnostic Logger ready with persistent disk storage")
    }

    fun log(tag: String, message: String) {
        Log.d("$TAG:$tag", message)

        try {
            val timestamp = timeFormat.format(Date())
            val logLine = "[$timestamp][$tag] $message\n"

            // 1. Write to Persistent Disk Storage
            prefs?.let { p ->
                val current = p.getString(KEY_LOG_BUFFER, "") ?: ""
                // Keep latest 10,000 characters to prevent disk overflow
                val trimmed = if (current.length > 8000) current.takeLast(6000) else current
                p.edit().putString(KEY_LOG_BUFFER, trimmed + logLine).apply()
            }

            // 2. Write to on-screen live TextView if active
            consoleTextView?.let { tv ->
                uiHandler.post {
                    try {
                        tv.append(logLine)
                    } catch (e: Throwable) {
                        // Safe UI fallback
                    }
                }
            }
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    fun getSavedLogs(): String {
        return prefs?.getString(KEY_LOG_BUFFER, "No crash logs recorded yet.") ?: "No logs."
    }

    fun clearLogs() {
        prefs?.edit()?.remove(KEY_LOG_BUFFER)?.apply()
    }
}
