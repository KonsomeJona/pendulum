package com.pendulum.wear.record

import com.pendulum.format.wire.PreviewEnvelopeCodec
import kotlin.math.sqrt

/**
 * RMS envelope decimated to 1 Hz over the last fifteen minutes, quantised as a logarithmic u8:
 * 900 bytes riding along in a burst already paid for, for about +0.3 % of its volume and **zero
 * extra radio wake-up**.
 *
 * **This preview is never used for computation.** Every published figure is recomputed from the
 * raw chunks, on the phone side. Here: proof of life and the shape of the signal, nothing else.
 *
 * The high-pass filter is a plain subtraction of an exponential moving average: gravity is very
 * nearly constant, movement is not. `:algo` is not reused: its functions work on materialised
 * signals, whereas here everything is streamed, sample by sample, on a computation budget that
 * must stay invisible in the power draw.
 */
class PreviewEnvelope(
    rateHz: Int,
    /** Called on every closed second. The wake detector hooks in here: there is no reason to
     *  compute a second time an RMS that has just been produced. */
    private val onSecond: (rms: Double, tsNs: Long) -> Unit = { _, _ -> },
) {

    /** Time constant of the gravity removal, in seconds. */
    private val tauSec = 0.5

    private var alpha = 1.0 / (tauSec * rateHz + 1.0)
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var primed = false

    private var bucketStartNs = 0L
    private var sumSq = 0.0
    private var n = 0

    /** Ring buffer of the last 900 seconds. */
    private val ring = DoubleArray(PreviewEnvelopeCodec.LENGTH)
    private var head = 0
    private var filled = 0

    fun onRateChanged(rateHz: Int) {
        alpha = 1.0 / (tauSec * rateHz + 1.0)
    }

    fun onSample(x: Float, y: Float, z: Float, tsNs: Long) {
        if (!primed) {
            gx = x.toDouble(); gy = y.toDouble(); gz = z.toDouble()
            primed = true
            bucketStartNs = tsNs
        }
        gx += alpha * (x - gx)
        gy += alpha * (y - gy)
        gz += alpha * (z - gz)
        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        sumSq += dx * dx + dy * dy + dz * dz
        n++

        if (tsNs - bucketStartNs >= 1_000_000_000L) {
            val rms = if (n > 0) sqrt(sumSq / n) else 0.0
            push(rms)
            onSecond(rms, tsNs)
            sumSq = 0.0
            n = 0
            bucketStartNs = tsNs
        }
    }

    private fun push(rms: Double) {
        ring[head] = rms
        head = (head + 1) % ring.size
        if (filled < ring.size) filled++
    }

    /**
     * Window of 900 bytes, the most recent byte at the tail. A night that has only just begun is
     * padded with zeros **at the head**, which spares the phone from having to know how long it
     * has been running.
     */
    fun snapshot(): ByteArray {
        val values = DoubleArray(filled)
        val start = (head - filled + ring.size) % ring.size
        for (i in 0 until filled) values[i] = ring[(start + i) % ring.size]
        return PreviewEnvelopeCodec.encode(values)
    }
}
