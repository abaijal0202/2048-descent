package com.digiboxx.descent2048.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Turns game events into things the player can feel.
 *
 * The engine has always emitted `Merged`, `Landed`, `TrophyEarned` and the rest; nothing
 * consumed them, so a merge and a miss felt identical. Haptics are the cheapest possible
 * fix — no assets, no audio focus, no permission — and on a phone held in one hand they
 * carry more of the feel than sound does.
 *
 * Amplitude is only honoured from API 26 upward; below that the platform gives a plain
 * on/off buzz and the duration is all that distinguishes one cue from another.
 */
class Haptics(context: Context) {

    /** Players who dislike vibration turn this off; every entry point checks it. */
    var enabled: Boolean = true

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private val available: Boolean = vibrator?.hasVibrator() == true

    /**
     * A merge. Deeper links in a cascade hit harder, so a five-chain is something you
     * feel building rather than just a number that moves.
     */
    fun merge(comboDepth: Int) {
        val step = comboDepth.coerceIn(1, 5)
        oneShot(durationMs = 12L + step * 4L, amplitude = 70 + step * 30)
    }

    /** A tile coming to rest. Deliberately the faintest cue — it happens constantly. */
    fun land() = oneShot(durationMs = 8L, amplitude = 45)

    /** A slide that ran into a standing tile. */
    fun blocked() = oneShot(durationMs = 18L, amplitude = 90)

    /** Delete Row or Slow firing. */
    fun power() = oneShot(durationMs = 25L, amplitude = 140)

    /** A speed milestone. Two short taps so it reads as a warning, not a reward. */
    fun speedUp() = waveform(longArrayOf(0, 18, 60, 18), intArrayOf(0, 120, 0, 120))

    /** The big one. A rising three-pulse flourish. */
    fun trophy() =
        waveform(longArrayOf(0, 30, 40, 45, 40, 90), intArrayOf(0, 110, 0, 180, 0, 255))

    /** Game over. One long, flat buzz. */
    fun gameOver() = oneShot(durationMs = 220L, amplitude = 160)

    @Suppress("DEPRECATION")
    private fun oneShot(durationMs: Long, amplitude: Int) {
        if (!enabled || !available) return
        val device = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(
                    VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
                )
            } else {
                device.vibrate(durationMs)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        if (!enabled || !available) return
        val device = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                device.vibrate(timings, -1)
            }
        }
    }
}
