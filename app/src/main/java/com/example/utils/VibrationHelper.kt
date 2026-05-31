package com.example.utils

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object VibrationHelper {
    fun vibrateTripleShort(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use standard duration-only waveform which is compatible with all vibrators (independent of amplitude control support)
                val timings = longArrayOf(0, 80, 100, 80, 100, 80)
                val effect = VibrationEffect.createWaveform(timings, -1)
                
                // Add AudioAttributes to ensure the hardware fires regardless of sound/vibe profiles
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
                
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 80, 100, 80, 100, 80), -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Ultimate fallback to one single buzz in case waveforms are not supported at all
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val fallbackEffect = VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                    val attributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                    vibrator.vibrate(fallbackEffect, attributes)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(120)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}
