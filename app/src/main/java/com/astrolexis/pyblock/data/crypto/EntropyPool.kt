package com.astrolexis.pyblock.data.crypto

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.sqrt

/**
 * Collects USER entropy (device motion + on-screen touch + timing jitter) to
 * harden key generation. This is additive — the actual key material is always a
 * full CSPRNG draw ([VanityCrypto.hardenedRandom32]); the pool only mixes in.
 * Mirrors the iOS `EntropyPool`. Compose-observable progress.
 */
class EntropyPool(private val sensorManager: SensorManager?) {
    private val rng = SecureRandom()

    // Rolling 32-byte accumulators (SHA-256 chained).
    private var motionAcc = ByteArray(32)
    private var touchAcc = ByteArray(32)
    private var patternAcc = ByteArray(32)

    var motionSamples by mutableIntStateOf(0); private set
    var touchSamples by mutableIntStateOf(0); private set

    val motionProgress: Float get() = (motionSamples.toFloat() / MOTION_TARGET).coerceIn(0f, 1f)
    val touchProgress: Float get() = (touchSamples.toFloat() / TOUCH_TARGET).coerceIn(0f, 1f)
    val motionReady: Boolean get() = motionSamples >= MOTION_TARGET
    val touchReady: Boolean get() = touchSamples >= TOUCH_TARGET

    private fun sha256(x: ByteArray) = MessageDigest.getInstance("SHA-256").digest(x)
    private fun nano() = ByteArray(8).also { val t = System.nanoTime(); for (i in 0..7) it[i] = ((t ushr (i * 8)) and 0xff).toByte() }
    private fun floatBytes(v: Float): ByteArray {
        val b = java.lang.Float.floatToRawIntBits(v)
        return ByteArray(4) { ((b ushr (it * 8)) and 0xff).toByte() }
    }

    // MARK: Motion

    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
    private var hasLast = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values.getOrElse(0) { 0f }; val y = e.values.getOrElse(1) { 0f }; val z = e.values.getOrElse(2) { 0f }
            // Count DELTA between samples, so ambient sensor noise / gravity can't
            // fill the bar by itself — it needs a real, deliberate shake. Works with
            // both linear-accel and raw accelerometer (gravity cancels in the delta).
            if (hasLast) {
                val dx = x - lastX; val dy = y - lastY; val dz = z - lastZ
                val move = sqrt(dx * dx + dy * dy + dz * dz)
                if (move > MOVE_THRESHOLD) {
                    motionAcc = sha256(motionAcc + floatBytes(x) + floatBytes(y) + floatBytes(z) + nano())
                    if (motionSamples < MOTION_TARGET) motionSamples++
                }
            }
            lastX = x; lastY = y; lastZ = z; hasLast = true
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startMotion() {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null) sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stopMotion() { sensorManager?.unregisterListener(listener) }

    // MARK: Touch + pattern

    fun feedTouch(x: Float, y: Float) {
        touchAcc = sha256(touchAcc + floatBytes(x) + floatBytes(y) + nano())
        if (touchSamples < TOUCH_TARGET) touchSamples++
    }

    fun feedPattern(s: String) {
        patternAcc = sha256(patternAcc + s.toByteArray() + nano())
    }

    /** Final mixed entropy: SHA256(motion ‖ touch ‖ pattern ‖ CSPRNG-salt). */
    fun pool(): ByteArray {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        return sha256(motionAcc + touchAcc + patternAcc + salt)
    }

    /** Wipe all gathered entropy + progress — fail-closed on an air-gap breach. */
    fun reset() {
        stopMotion()
        motionAcc.fill(0); touchAcc.fill(0); patternAcc.fill(0)
        motionAcc = ByteArray(32); touchAcc = ByteArray(32); patternAcc = ByteArray(32)
        motionSamples = 0; touchSamples = 0
        hasLast = false; lastX = 0f; lastY = 0f; lastZ = 0f
    }

    companion object {
        const val MOTION_TARGET = 70
        const val TOUCH_TARGET = 70
        const val MOVE_THRESHOLD = 1.8f   // m/s² of per-sample change → requires a real shake
    }
}
