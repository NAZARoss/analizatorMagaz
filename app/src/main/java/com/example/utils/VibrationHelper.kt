package com.example.utils

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object VibrationHelper {

    private const val TAG = "VibrationHelper"

    fun vibrateTripleShort(context: Context) {
        val vibrator = getVibrator(context)

        if (vibrator == null) {
            Log.e(TAG, "Vibrator is null")
            return
        }

        if (!vibrator.hasVibrator()) {
            Log.e(TAG, "Device has no vibrator")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(
                    0,
                    140,
                    90,
                    140,
                    90,
                    140
                )

                val amplitudes = if (vibrator.hasAmplitudeControl()) {
                    intArrayOf(
                        0,
                        255,
                        0,
                        255,
                        0,
                        255
                    )
                } else {
                    intArrayOf(
                        0,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                        0,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                        0,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                }

                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                vibrator.vibrate(effect, audioAttributes)

                Log.d(TAG, "Triple vibration started")
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 140, 90, 140, 90, 140), -1)

                Log.d(TAG, "Legacy triple vibration started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Triple vibration failed: ${e.message}", e)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            500,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }

                Log.d(TAG, "Fallback vibration started")
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback vibration failed: ${ex.message}", ex)
            }
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
