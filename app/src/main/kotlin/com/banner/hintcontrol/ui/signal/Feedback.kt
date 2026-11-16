package com.banner.hintcontrol.ui.signal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/** Short tone and buzz whose pitch/length follow the signal score (placement helper). */
class PlacementFeedback(private val context: Context) {
    fun beep(score: Int) {
        runCatching {
            val rate = 22_050
            val ms = 90
            val freq = 380.0 + score.coerceIn(0, 100) * 11.0
            val n = rate * ms / 1000
            val pcm = ShortArray(n) { i ->
                val env = when {
                    i < n / 10 -> i / (n / 10.0)
                    i > n * 8 / 10 -> (n - i) / (n * 2 / 10.0)
                    else -> 1.0
                }
                (sin(2 * PI * freq * i / rate) * env * Short.MAX_VALUE * 0.35).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
            track.write(pcm, 0, n)
            track.setNotificationMarkerPosition(n)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }
    }

    fun buzz(score: Int) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            val ms = (15 + score.coerceIn(0, 100) * 0.6).toLong()
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
