package com.pendulum.algo.mask

import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic factories of the `mask` package.
 *
 * `fs` deliberately at 10 Hz and not at 50: the mask works in epochs of 5 s, none of its criteria
 * has content above 1 Hz, and an 8 h night at 10 Hz fits in 288 000 samples. This checks in
 * passing that nothing is hard-coded at 50 Hz.
 *
 * No randomness, no clock: the tests must be reproducible to the bit.
 */
internal const val FS = 10.0

/** Constant noise floor, and envelope at rest. Ratio 1: rest is below every threshold. */
internal const val FLOOR_G = 0.004f

internal fun samples(sec: Double): Int = Math.round(sec * FS).toInt()

/**
 * Synthetic night: gravity of controlled orientation, envelope at rest, constant floor. The
 * orientation is parameterised by a single pitch angle `theta` around `x`, which is enough: the
 * criterion under test is an angle between two directions, not an absolute orientation.
 */
internal class Night(val durSec: Double) {
    val n: Int = samples(durSec)
    private val theta = DoubleArray(n)
    private val envV = FloatArray(n) { FLOOR_G }
    private val floorV = FloatArray(n) { FLOOR_G }

    /** Burst of constant amplitude. Returns the interval, ready to serve as `ignoreIntervals`. */
    fun burst(startSec: Double, durSec: Double, ampG: Float): Segment {
        val from = samples(startSec).coerceIn(0, n)
        val to = samples(startSec + durSec).coerceIn(0, n)
        for (i in from until to) envV[i] = ampG
        return Segment(from, to)
    }

    /** Periodic series of identical bursts: the case that breaks the 5 min rule. */
    fun periodic(startSec: Double, imiSec: Double, durSec: Double, ampG: Float, count: Int): List<Segment> =
        (0 until count).map { burst(startSec + it * imiSec, durSec, ampG) }

    /**
     * **Persistent** reorientation of `deg` degrees, as a linear ramp over `rampSec`: this is the
     * signature of a posture change or of a gross body movement, as opposed to a jolt that returns
     * to its starting position.
     */
    fun tilt(atSec: Double, deg: Double, rampSec: Double = 2.0) {
        val from = samples(atSec).coerceIn(0, n)
        val to = samples(atSec + rampSec).coerceIn(from, n)
        val rad = deg * PI / 180.0
        for (i in from until to) theta[i] += rad * (i - from + 1).toDouble() / (to - from)
        for (i in to until n) theta[i] += rad
    }

    fun gravity(): TriAxial {
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        val gz = FloatArray(n)
        for (i in 0 until n) {
            gx[i] = 0f
            gy[i] = sin(theta[i]).toFloat()
            gz[i] = cos(theta[i]).toFloat()
        }
        return TriAxial(FS, 0L, gx, gy, gz)
    }

    fun env(): Signal1D = Signal1D(FS, 0L, envV.copyOf())

    fun floor(): Signal1D = Signal1D(FS, 0L, floorV.copyOf())

    fun segments(): List<Segment> = listOf(Segment(0, n))
}

/**
 * Constant rotation of the case, by Rodrigues' formula. Applies the **same** rotation to every
 * sample: this is exactly what a strap put back the other way round from one night to the next
 * produces, and the mask must not notice it.
 */
internal fun rotate(g: TriAxial, ax: Double, ay: Double, az: Double, angleDeg: Double): TriAxial {
    val norm = sqrt(ax * ax + ay * ay + az * az)
    val kx = ax / norm
    val ky = ay / norm
    val kz = az / norm
    val c = cos(angleDeg * PI / 180.0)
    val s = sin(angleDeg * PI / 180.0)
    val x = FloatArray(g.n)
    val y = FloatArray(g.n)
    val z = FloatArray(g.n)
    for (i in 0 until g.n) {
        val vx = g.x[i].toDouble()
        val vy = g.y[i].toDouble()
        val vz = g.z[i].toDouble()
        val cx = ky * vz - kz * vy
        val cy = kz * vx - kx * vz
        val cz = kx * vy - ky * vx
        val dot = kx * vx + ky * vy + kz * vz
        x[i] = (vx * c + cx * s + kx * dot * (1 - c)).toFloat()
        y[i] = (vy * c + cy * s + ky * dot * (1 - c)).toFloat()
        z[i] = (vz * c + cz * s + kz * dot * (1 - c)).toFloat()
    }
    return TriAxial(g.fsHz, g.t0Ns, x, y, z)
}
