package com.pendulum.format.wire

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Preview envelope quantisation: RMS decimated to 1 Hz, one byte per second.
 *
 * **Why logarithmic.** The useful signal spans four decades — from the MEMS noise floor
 * (~1e-3 m/s^2) to an ankle jerk (~40 m/s^2). A linear 8-bit quantisation would give a step of
 * 0.16 m/s^2, that is, two levels for everything that happens during sleep: the preview would be
 * flat all night then saturated on waking. In logarithmic form the error is **relative** and
 * constant (~2 % of a half-step), which is exactly the property wanted for a trace meant for the
 * eye.
 *
 * **This preview is never used for computation.** It is there to show that the recording is
 * alive; every figure is computed on the raw chunks, never on these bytes.
 */
object PreviewEnvelopeCodec {

    /** 900 s = 15 min sliding at 1 Hz, so 900 bytes — well under the ceiling of a `DataItem`. */
    const val LENGTH = 900

    /** Floor: below it, the signal can no longer be told from the sensor's own noise. */
    const val MIN_MS2 = 1e-3

    /** Ceiling: ~4 g RMS over one second, far beyond what an ankle produces. */
    const val MAX_MS2 = 40.0

    /** Reserved for "below the floor": level 0 is not a value, it is a state. */
    private const val LEVELS = 255.0

    private val LOG_SPAN = ln(MAX_MS2 / MIN_MS2)

    /** Quantises an RMS in m/s^2 to `0..255`. 0 means "below [MIN_MS2]", not "exactly zero". */
    fun quantize(rms: Double): Int {
        if (!rms.isFinite() || rms <= MIN_MS2) return 0
        val level = 1 + (LEVELS - 1) * ln(rms / MIN_MS2) / LOG_SPAN
        return level.roundToInt().coerceIn(1, 255)
    }

    /** Inverse of [quantize]. Level 0 yields [MIN_MS2], the upper bound of what it represents. */
    fun dequantize(level: Int): Double {
        require(level in 0..255) { "level out of bounds: $level" }
        if (level == 0) return MIN_MS2
        return MIN_MS2 * exp(LOG_SPAN * (level - 1) / (LEVELS - 1))
    }

    /**
     * Encodes a window of [LENGTH] RMS values. A shorter window (start of night) is zero-padded
     * **at the head**: the most recent byte is always the last one, which spares the phone from
     * having to know how long ago the night started.
     */
    fun encode(rms: DoubleArray): ByteArray {
        require(rms.size <= LENGTH) { "window too long: ${rms.size} > $LENGTH" }
        val out = ByteArray(LENGTH)
        val offset = LENGTH - rms.size
        for (i in rms.indices) out[offset + i] = quantize(rms[i]).toByte()
        return out
    }

    /** Decodes a complete window into m/s^2. */
    fun decode(envU8: ByteArray): DoubleArray {
        require(envU8.size == LENGTH) { "expected a window of $LENGTH bytes, got ${envU8.size}" }
        return DoubleArray(LENGTH) { dequantize(envU8[it].toInt() and 0xFF) }
    }
}
