package com.englishtalk.app.utils

import android.content.Context
import android.media.RingtoneManager

object SoundHelper {
    fun playWarningChime(context: Context) {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone?.play()
        } catch (_: Exception) {}
    }
}
