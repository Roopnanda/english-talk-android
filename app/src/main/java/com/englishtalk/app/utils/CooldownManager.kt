package com.englishtalk.app.utils

import android.content.Context
import android.content.SharedPreferences

object CooldownManager {

    private const val PREFS_NAME = "CooldownPrefs"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_WINDOW_START_TIME = "window_start_time"
    private const val KEY_SHORT_CALLS_WINDOW_COUNT = "short_calls_window_count"
    private const val KEY_CONSECUTIVE_SUB5_COUNT = "consecutive_sub5_count"

    private const val TWO_MINUTES_MS = 2 * 60 * 1000L
    private const val THREE_MINUTES_MS = 3 * 60 * 1000L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if user is currently locked under a 3-minute cooldown.
     * Evaluates window expiration if no 60s+ call was completed during the 2-minute window.
     */
    fun isUnderCooldown(context: Context): Boolean {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // 1. Check existing active cooldown
        val cooldownUntil = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
        if (now < cooldownUntil) {
            return true
        }

        // 2. Check if active 2-minute window expired without a qualifying call
        val windowStart = prefs.getLong(KEY_WINDOW_START_TIME, 0L)
        if (windowStart > 0L) {
            if (now - windowStart >= TWO_MINUTES_MS) {
                // Window elapsed without completing a 60s+ call -> Trigger 3-min cooldown
                triggerThreeMinuteCooldown(context)
                return true
            }
        }

        return false
    }

    fun getRemainingCooldownSeconds(context: Context): Long {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val cooldownUntil = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
        return if (cooldownUntil > now) (cooldownUntil - now) / 1000L else 0L
    }

    /**
     * Called whenever a call concludes with its active talk duration (in seconds).
     */
    fun onCallFinished(context: Context, durationSec: Long) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // Bypass rule: Any call lasting >= 60s cancels the active 2-minute evaluation window
        if (durationSec >= 60) {
            AppLogger.log("Cooldown", "60s+ call achieved ($durationSec s). Resetting evaluation window.")
            resetEvaluationWindow(context)
            return
        }

        val windowStart = prefs.getLong(KEY_WINDOW_START_TIME, 0L)

        // If active 2-minute window is currently running
        if (windowStart > 0L && (now - windowStart < TWO_MINUTES_MS)) {
            if (durationSec < 10) {
                val shortCount = prefs.getInt(KEY_SHORT_CALLS_WINDOW_COUNT, 0) + 1
                prefs.edit().putInt(KEY_SHORT_CALLS_WINDOW_COUNT, shortCount).apply()
                AppLogger.log("Cooldown", "Short call (<10s) count in window: $shortCount / 10")

                // Sub-Criteria: 10 calls under 10 seconds during window -> Immediate 3-min break
                if (shortCount >= 10) {
                    AppLogger.log("Cooldown", "Sub-criteria triggered: 10 rapid drops in window.")
                    triggerThreeMinuteCooldown(context)
                    return
                }
            }
        } else {
            // Window is not active. Check trigger for Criteria 1: 3 consecutive calls < 5 seconds
            if (durationSec < 5) {
                val sub5Count = prefs.getInt(KEY_CONSECUTIVE_SUB5_COUNT, 0) + 1
                prefs.edit().putInt(KEY_CONSECUTIVE_SUB5_COUNT, sub5Count).apply()
                AppLogger.log("Cooldown", "Consecutive sub-5s hang-up count: $sub5Count / 3")

                if (sub5Count >= 3) {
                    AppLogger.log("Cooldown", "3 consecutive <5s hang-ups detected. Starting 2-minute evaluation window.")
                    prefs.edit()
                        .putLong(KEY_WINDOW_START_TIME, now)
                        .putInt(KEY_SHORT_CALLS_WINDOW_COUNT, 0)
                        .putInt(KEY_CONSECUTIVE_SUB5_COUNT, 0)
                        .apply()
                }
            } else {
                // Call lasted between 5s and 59s; resets the consecutive sub-5s counter
                prefs.edit().putInt(KEY_CONSECUTIVE_SUB5_COUNT, 0).apply()
            }
        }
    }

    fun triggerThreeMinuteCooldown(context: Context) {
        val now = System.currentTimeMillis()
        val until = now + THREE_MINUTES_MS
        getPrefs(context).edit()
            .putLong(KEY_COOLDOWN_UNTIL, until)
            .putLong(KEY_WINDOW_START_TIME, 0L)
            .putInt(KEY_SHORT_CALLS_WINDOW_COUNT, 0)
            .putInt(KEY_CONSECUTIVE_SUB5_COUNT, 0)
            .apply()
        AppLogger.log("Cooldown", "User locked under 3-minute cooldown until: $until")
    }

    private fun resetEvaluationWindow(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_WINDOW_START_TIME, 0L)
            .putInt(KEY_SHORT_CALLS_WINDOW_COUNT, 0)
            .putInt(KEY_CONSECUTIVE_SUB5_COUNT, 0)
            .apply()
    }
}
