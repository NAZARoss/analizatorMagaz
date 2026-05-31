package com.example.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object VibrationHelper {
    fun vibrateTripleShort(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 3 short, sharp vibrations with silent gaps
                // timings: start immediately (0ms wait), vibrate 80ms, silent 100ms, vibrate 80ms, silent 100ms, vibrate 80ms
                val timings = longArrayOf(0, 80, 100, 80, 100, 80)
                // Amplitude 255 means maximum acceleration (sharp force)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 80, 100, 80, 100, 80), -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
