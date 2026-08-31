package com.astrolexis.pyblock.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The "feel it in your hand" layer for Android — an arcade haptics vocabulary
 *  built on VibrationEffect (coin / power-up / stage-clear waveforms). Global
 *  toggle via [setEnabled]. Every call is fail-safe. Init once in MainActivity. */
object Haptics {
    private var vibrator: Vibrator? = null
    var enabled: Boolean = true; private set
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        prefs = app.getSharedPreferences("pyblock_haptics", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("enabled", true) ?: true
    }

    fun setEnabled(v: Boolean) { enabled = v; prefs?.edit()?.putBoolean("enabled", v)?.apply() }

    // semantic vocabulary
    fun tap() = oneShot(14, 90)
    fun thock() = oneShot(22, 170)
    fun select() = oneShot(9, 60)
    fun success() = waveform(longArrayOf(0, 12, 40, 22), intArrayOf(0, 120, 0, 200))
    fun warning() = waveform(longArrayOf(0, 22, 60, 22), intArrayOf(0, 180, 0, 180))
    fun error() = waveform(longArrayOf(0, 30, 40, 30, 40, 30), intArrayOf(0, 220, 0, 220, 0, 220))
    fun coin() = waveform(longArrayOf(0, 10, 35, 10), intArrayOf(0, 200, 0, 160))        // two bright clicks
    fun powerUp() = waveform(longArrayOf(0, 40, 0, 40, 0, 18), intArrayOf(0, 90, 0, 160, 0, 255)) // rising into a pop
    fun stageClear() = waveform(longArrayOf(0, 14, 40, 16, 40, 22), intArrayOf(0, 150, 0, 190, 0, 255))
    fun blip() = oneShot(8, 50)
    fun received() = powerUp()
    fun sent() = success()

    private fun oneShot(ms: Long, amp: Int) {
        if (!enabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    private fun waveform(timings: LongArray, amps: IntArray) {
        if (!enabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            else @Suppress("DEPRECATION") v.vibrate(timings, -1)
        }
    }
}
