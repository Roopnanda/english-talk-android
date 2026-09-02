package com.englishtalk.app.utils

import android.content.Context
import android.content.SharedPreferences

object CooldownManager {

    private const val PREFS_NAME = "EnglishTalkCooldownPrefs"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until_timestamp"
    private const val KEY_CONSECUTIVE_SKIPS = "consecutive_skips_count"
    private const val KEY_PROBATION_START = "probation_start_timestamp"
    private const val KEY_PROBATION_SKIPS = "probation_skips_count"

    enum class AbuseActionResult {
        NONE,
        TIER1_WARNING,
        TIER2_COOLDOWN
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isUnderCooldown(context: Context): Boolean {
        val until = getPrefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)
        return System.currentTimeMillis() < until
    }

    fun getRemainingCooldownSeconds(context: Context): Long {
        val until = getPrefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0L) diff / 1000L else 0L
    }

    fun triggerThreeMinuteCooldown(context: Context) {
        val cooldownEnd = System.currentTimeMillis() + (3 * 60 * 1000L)
        getPrefs(context).edit()
            .putLong(KEY_COOLDOWN_UNTIL, cooldownEnd)
            .putInt(KEY_CONSECUTIVE_SKIPS, 0)
            .putInt(KEY_PROBATION_SKIPS, 0)
            .putLong(KEY_PROBATION_START, 0L)
            .apply()
        AppLogger.log("CooldownManager", "3-minute hard cooldown triggered")
    }

    fun onCallFinished(
        context: Context,
        durationSec: Long,
        isLocalInitiatorHangup: Boolean
    ): AbuseActionResult {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // Ghost call protection: under 6s due to transport/remote hangup carries zero penalty
        if (durationSec < 6L && !isLocalInitiatorHangup) {
            AppLogger.log("CooldownManager", "Transport / remote drop under 6s. Zero strikes attributed.")
            return AbuseActionResult.NONE
        }

        // Redemption Reset: completed call >= 60s wipes strikes and probation
        if (durationSec >= 60L) {
            prefs.edit()
                .putInt(KEY_CONSECUTIVE_SKIPS, 0)
                .putInt(KEY_PROBATION_SKIPS, 0)
                .putLong(KEY_PROBATION_START, 0L)
                .apply()
            AppLogger.log("CooldownManager", "Call duration >= 60s. Penalty counters fully reset.")
            return AbuseActionResult.NONE
        }

        // Only attribute strikes if the local user pressed End Call
        if (!isLocalInitiatorHangup) {
            AppLogger.log("CooldownManager", "Remote peer ended call. Local user exempt from penalty.")
            return AbuseActionResult.NONE
        }

        // From here on, call ended under 60s by local user action
        if (durationSec < 10L) {
            val probationStart = prefs.getLong(KEY_PROBATION_START, 0L)
            val isInProbation = (now - probationStart) < (10 * 60 * 1000L) && probationStart > 0L

            if (isInProbation) {
                // Inside 10-minute probation: track additional skips towards Tier 2
                val probationSkips = prefs.getInt(KEY_PROBATION_SKIPS, 0) + 1
                if (probationSkips >= 2) {
                    triggerThreeMinuteCooldown(context)
                    return AbuseActionResult.TIER2_COOLDOWN
                } else {
                    prefs.edit().putInt(KEY_PROBATION_SKIPS, probationSkips).apply()
                    AppLogger.log("CooldownManager", "Probation skip recorded: $probationSkips/2")
                    return AbuseActionResult.NONE
                }
            } else {
                // Normal evaluation towards Tier 1
                val consecutiveSkips = prefs.getInt(KEY_CONSECUTIVE_SKIPS, 0) + 1
                if (consecutiveSkips >= 3) {
                    // Enter 10-minute probation window
                    prefs.edit()
                        .putInt(KEY_CONSECUTIVE_SKIPS, 0)
                        .putLong(KEY_PROBATION_START, now)
                        .putInt(KEY_PROBATION_SKIPS, 0)
                        .apply()
                    AppLogger.log("CooldownManager", "Tier 1 warning reached. 10-minute probation window opened.")
                    return AbuseActionResult.TIER1_WARNING
                } else {
                    prefs.edit().putInt(KEY_CONSECUTIVE_SKIPS, consecutiveSkips).apply()
                    AppLogger.log("CooldownManager", "Early skip recorded: $consecutiveSkips/3")
                    return AbuseActionResult.NONE
                }
            }
        }

        return AbuseActionResult.NONE
    }
}
