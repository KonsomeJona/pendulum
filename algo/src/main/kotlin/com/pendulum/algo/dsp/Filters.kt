package com.pendulum.algo.dsp

import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.TriAxial
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Biquad in **transposed direct form II**, `Double` internally, `Float` at the interface.
 *
 * Two non-negotiable points, both stemming from pitfall no. 3 of v1:
 *
 *  - **The object is `stateful` and the filtering is streaming.** Filtering block by block,
 *    restarting from a null state at each block, injects a settling transient every ~10 s (the
 *    size of a 512-sample block at 50 Hz). That transient is **periodic**: it produces in the
 *    envelope a regular modulation that step 6 reads as a perfect PLM series. It is the most
 *    insidious false positive of the whole chain, because it looks exactly like the signal being
 *    looked for. A segment is filtered in **a single pass**, from the first to the last sample,
 *    whatever the block fragmentation upstream.
 *  - **Transposed DFII and not DFI**: it minimises the excursion of the state variables, which
 *    matters here because the gravity low-pass (fc/fs = 0.15/50 = 0.003) has its poles very close
 *    to the unit circle.
 *
 * Recurrence: `y = b0*x + s1 ; s1' = b1*x - a1*y + s2 ; s2' = b2*x - a2*y`.
 */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    private var s1 = 0.0
    private var s2 = 0.0

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }

    /**
     * Initialises the state to the **steady-state regime of a constant input** [dcValue].
     *
     * Without this, the gravity low-pass starts at 0 while its input is ~1 g: it produces a
     * transient the size of gravity, i.e. 1 000 mg, when a CLM is 30 to 200.
     *
     * Steady state: `y = x*(b0+b1+b2)/(1+a1+a2)`, hence `s1 = (b1+b2)*x - (a1+a2)*y` and
     * `s2 = b2*x - a2*y`.
     */
    fun resetToDc(dcValue: Float) {
        val x = dcValue.toDouble()
        val den = 1.0 + a1 + a2
        val y = if (abs(den) < 1e-12) 0.0 else x * (b0 + b1 + b2) / den
        s1 = (b1 + b2) * x - (a1 + a2) * y
        s2 = b2 * x - a2 * y
    }

    /** Gain at zero frequency. Equals 1 for a normalised low-pass, 0 for a high-pass. */
    fun dcGain(): Double {
        val den = 1.0 + a1 + a2
        return if (abs(den) < 1e-12) 0.0 else (b0 + b1 + b2) / den
    }

    fun step(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + s1
        s1 = b1 * xd - a1 * y + s2
        s2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray {
        for (i in src.indices) dst[i] = step(src[i])
        return dst
    }

    /** Opaque state for the incremental mode: `[s1, s2]`. */
    fun snapshot(): DoubleArray = doubleArrayOf(s1, s2)

    fun restore(state: DoubleArray) {
        require(state.size == 2) { "biquad state of size ${state.size}" }
        s1 = state[0]
        s2 = state[1]
    }

    /**
     * Modulus of the slowest pole. `1 - r` measures the filter's rate of forgetting; that is what
     * sets the settling time, hence the value of `settleSec`.
     */
    internal fun poleRadius(): Double {
        val disc = a1 * a1 - 4.0 * a2
        return if (disc >= 0.0) {
            max(abs((-a1 + sqrt(disc)) / 2.0), abs((-a1 - sqrt(disc)) / 2.0))
        } else {
            sqrt(abs(a2)) // complex conjugate poles: modulus = sqrt(a2)
        }
    }
}

/** Cascade of biquad sections. A filter of order N > 2 only exists in this form. */
class BiquadCascade(internal val stages: List<Biquad>, val fsHz: Double) {

    fun reset() = stages.forEach { it.reset() }

    /**
     * Initialises all the sections to the steady-state regime of [dcValue]. The DC value
     * propagated to the next section is the one the current section produces in steady state: for
     * a high-pass cascade, it drops to 0 from the very first section, which is exactly the
     * intended behaviour (the movement channel starts out "gravity-free").
     */
    fun resetToDc(dcValue: Float) {
        var dc = dcValue
        for (s in stages) {
            s.resetToDc(dc)
            dc = (dc * s.dcGain()).toFloat()
        }
    }

    fun step(x: Float): Float {
        var v = x
        for (s in stages) v = s.step(v)
        return v
    }

    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray {
        for (i in src.indices) dst[i] = step(src[i])
        return dst
    }

    fun snapshot(): Array<DoubleArray> = Array(stages.size) { stages[it].snapshot() }

    fun restore(state: Array<DoubleArray>) {
        require(state.size == stages.size) { "cascade of different size" }
        for (i in stages.indices) stages[i].restore(state[i])
    }

    val stageCount: Int get() = stages.size

    /**
     * Settling time to 1 % (5 time constants) of the slowest pole.
     *
     * Serves to **verify** `settleSec` / `warmupSec`, not to replace them: the specification
     * freezes those two durations (§6.1) so that the PLMI denominator does not move when a filter
     * corner is adjusted. A cascade whose `settlingTimeSec` exceeds `warmupSec` is a settings
     * error, not a reason to silently lengthen the warmup.
     */
    val settlingTimeSec: Double
        get() {
            var slowest = 0.0
            for (s in stages) {
                val r = s.poleRadius()
                if (r > 0.0 && r < 1.0) slowest = max(slowest, -1.0 / ln(r))
            }
            return 5.0 * slowest / fsHz
        }
}

/**
 * Butterworth filter design by bilinear transform with **prewarping** of the corner.
 *
 * The coefficients are ALWAYS computed from the real `fs` passed as an argument, never from the
 * header's `nominalRateHz` (§3.4, first point). In practice step 0 brings the signal back onto a
 * grid at exactly 50.000 Hz, so the real `fs` equals `targetFsHz` — but the dependency stays
 * explicit in the signature: the day we chose not to resample, the compiler would force us to
 * supply the right value.
 */
object Filters {

    /**
     * Normalised quadratic factors of the Butterworth polynomial of order [order]:
     * `s^2 + alpha_k*s + 1`, with `alpha_k = 2*cos((2k+1)*pi/(2N))`.
     * Order 2 -> {sqrt(2)} ; order 4 -> {1.8478 ; 0.7654}.
     */
    private fun alphas(order: Int): DoubleArray {
        require(order >= 2 && order % 2 == 0) { "even order >= 2 expected, got $order" }
        val n = order / 2
        return DoubleArray(n) { k -> 2.0 * cos(PI * (2 * k + 1) / (2.0 * order)) }
    }

    private fun prewarp(fsHz: Double, fcHz: Double): Double {
        require(fsHz > 0.0) { "fsHz must be > 0" }
        require(fcHz > 0.0 && fcHz < fsHz / 2.0) { "fc=$fcHz outside the usable band for fs=$fsHz" }
        return tan(PI * fcHz / fsHz)
    }

    fun butterLowpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade {
        val k = prewarp(fsHz, fcHz)
        val k2 = k * k
        val stages = alphas(order).map { a ->
            val norm = 1.0 / (1.0 + a * k + k2)
            Biquad(
                b0 = k2 * norm, b1 = 2.0 * k2 * norm, b2 = k2 * norm,
                a1 = 2.0 * (k2 - 1.0) * norm, a2 = (1.0 - a * k + k2) * norm,
            )
        }
        return BiquadCascade(stages, fsHz)
    }

    fun butterHighpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade {
        val k = prewarp(fsHz, fcHz)
        val k2 = k * k
        val stages = alphas(order).map { a ->
            val norm = 1.0 / (1.0 + a * k + k2)
            Biquad(
                b0 = norm, b1 = -2.0 * norm, b2 = norm,
                a1 = 2.0 * (k2 - 1.0) * norm, a2 = (1.0 - a * k + k2) * norm,
            )
        }
        return BiquadCascade(stages, fsHz)
    }

    /**
     * Bandpass = high-pass of order [order] **followed by** the low-pass of order [order], and not
     * a bandpass transformation of order 2N.
     *
     * This is not an approximation: the specification describes the movement channel as
     * "ButterBP(0.5 - 8.0 Hz, order 2 **per section**)" (§2, step 1) and sets the two corners
     * independently (§6.1). With a ratio fHigh/fLow = 16 the two corners do not interact; the
     * cascaded form on the other hand allows setting the order of the high-pass alone (`hpOrder`,
     * 2 or 4), which is exactly the trade-off of §1.2: +19 dB of rejection at 0.25 Hz against a
     * lying-posture ringing of 2 s to 4 s.
     */
    fun butterBandpass(fsHz: Double, fLowHz: Double, fHighHz: Double, order: Int = 2): BiquadCascade {
        require(fLowHz < fHighHz) { "fLow must be < fHigh" }
        val hp = butterHighpass(fsHz, fLowHz, order)
        val lp = butterLowpass(fsHz, fHighHz, order)
        return BiquadCascade(hp.stages + lp.stages, fsHz)
    }

    /**
     * Magnitude of the cascade's frequency response, on a linear scale. Intended only for tests
     * and diagnostics: do not call it in the chain.
     */
    fun magnitudeAt(c: BiquadCascade, fHz: Double): Double {
        val w = 2.0 * PI * fHz / c.fsHz
        var re = 1.0
        var im = 0.0
        for (s in c.stages) {
            // H(e^jw) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
            val c1 = cos(-w); val s1 = kotlin.math.sin(-w)
            val c2 = cos(-2 * w); val s2 = kotlin.math.sin(-2 * w)
            val nr = s.b0 + s.b1 * c1 + s.b2 * c2
            val ni = s.b1 * s1 + s.b2 * s2
            val dr = 1.0 + s.a1 * c1 + s.a2 * c2
            val di = s.a1 * s1 + s.a2 * s2
            val den = dr * dr + di * di
            val hr = (nr * dr + ni * di) / den
            val hi = (ni * dr - nr * di) / den
            val newRe = re * hr - im * hi
            val newIm = re * hi + im * hr
            re = newRe; im = newIm
        }
        return sqrt(re * re + im * im)
    }
}

/** Output of step 1: the two parallel paths. */
data class GravitySplit(val gravity: TriAxial, val linear: TriAxial)

/**
 * Step 1 — gravity / movement separation by **two parallel paths**, never by subtraction.
 * `a - LP(a)` would mathematically be a valid high-pass, but the point is not mathematical:
 * `g_hat` becomes a first-class signal, consumed by the posture detector (§3.1), the `tilt`
 * characteristic of each event (step 5), the immobility mask (§3.6) and the autocalibration
 * (§3.3).
 */
object Gravity {

    /**
     * @param raw uniform grid from step 0 (`NaN` in the holes).
     * @param segments continuous segments; the filters are reinitialised to the steady state at
     *   each segment boundary, and **never** between two blocks inside a segment.
     * @param settleSec duration of the priming average (§6.1, `settleSec = 2.0 s`).
     *
     * Handling of `NaN` inside a segment (BLIND holes of 0.10 to 2.0 s):
     *  - **movement channel: 0 is injected**. A hole is not a movement; injecting the last value
     *    there would create a plateau that the high-pass would read as a step.
     *  - **gravity channel: the last value is held**. Gravity is persistent; injecting 0 would
     *    make `g_hat` plunge towards the origin and manufacture a false 90-degree posture change
     *    at every micro-hole.
     *
     * The output stays defined (non-`NaN`) inside the hole: this is deliberate. Exclusion is done
     * through the **blind zones** of the timeline, not by propagating `NaN`s that would destroy
     * the filter state for the whole rest of the segment.
     */
    fun split(
        raw: TriAxial,
        segments: List<Segment>,
        fcGravityHz: Double = 0.15,
        fcHpHz: Double = 0.50,
        fcLpHz: Double = 8.0,
        hpOrder: Int = 2,
        settleSec: Double = 2.0,
    ): GravitySplit {
        val fs = raw.fsHz
        val n = raw.n
        val gx = FloatArray(n) { Float.NaN }
        val gy = FloatArray(n) { Float.NaN }
        val gz = FloatArray(n) { Float.NaN }
        val lx = FloatArray(n) { Float.NaN }
        val ly = FloatArray(n) { Float.NaN }
        val lz = FloatArray(n) { Float.NaN }

        val axesIn = arrayOf(raw.x, raw.y, raw.z)
        val axesG = arrayOf(gx, gy, gz)
        val axesL = arrayOf(lx, ly, lz)
        val settle = Numeric.samples(settleSec, fs)

        for (seg in segments) {
            if (seg.length <= 0) continue
            for (a in 0..2) {
                val src = axesIn[a]
                // Mean of the segment's first `settleSec` seconds, NaN excluded: that is the DC
                // value the two filters start from.
                var sum = 0.0
                var cnt = 0
                val settleEnd = minOf(seg.toIdx, seg.fromIdx + settle)
                for (i in seg.fromIdx until settleEnd) {
                    val v = src[i]
                    if (!v.isNaN()) { sum += v; cnt++ }
                }
                val dc = if (cnt > 0) (sum / cnt).toFloat() else 0f

                val lp = Filters.butterLowpass(fs, fcGravityHz, 2)
                lp.resetToDc(dc)
                val bp = Filters.butterBandpass(fs, fcHpHz, fcLpHz, hpOrder)
                bp.resetToDc(dc)

                val gOut = axesG[a]
                val lOut = axesL[a]
                var hold = dc
                for (k in seg.fromIdx until seg.toIdx) {
                    val v = src[k]
                    val vg: Float
                    val vl: Float
                    if (v.isNaN()) {
                        vg = hold
                        vl = 0f
                    } else {
                        hold = v
                        vg = v
                        vl = v
                    }
                    gOut[k] = lp.step(vg)
                    lOut[k] = bp.step(vl)
                }
            }
        }
        return GravitySplit(
            gravity = TriAxial(fs, raw.t0Ns, gx, gy, gz),
            linear = TriAxial(fs, raw.t0Ns, lx, ly, lz),
        )
    }

    /** `g_hat / ||g_hat||`. `NaN` wherever the norm is zero or undefined. */
    fun unitVectors(gravity: TriAxial): TriAxial {
        val n = gravity.n
        val ux = FloatArray(n); val uy = FloatArray(n); val uz = FloatArray(n)
        for (i in 0 until n) {
            val x = gravity.x[i]; val y = gravity.y[i]; val z = gravity.z[i]
            val norm = sqrt((x.toDouble() * x + y.toDouble() * y + z.toDouble() * z))
            if (norm > 1e-9 && !norm.isNaN()) {
                ux[i] = (x / norm).toFloat(); uy[i] = (y / norm).toFloat(); uz[i] = (z / norm).toFloat()
            } else {
                ux[i] = Float.NaN; uy[i] = Float.NaN; uz[i] = Float.NaN
            }
        }
        return TriAxial(gravity.fsHz, gravity.t0Ns, ux, uy, uz)
    }

    /**
     * Angle between two vectors, in degrees.
     *
     * Computed by `atan2(||a x b||, a.b)` and not by `acos(a.b/(|a||b|))`: for two nearly
     * collinear vectors — the dominant case, since we compare `g_hat` with itself shifted by
     * 2 s — the argument of the `acos` is `1 - epsilon` and catastrophic cancellation loses half
     * the significant digits. And `minExcursionDeg` is 1.5 degree: it is exactly in that zone that
     * the precision has to hold.
     */
    fun angleDeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val axd = ax.toDouble(); val ayd = ay.toDouble(); val azd = az.toDouble()
        val bxd = bx.toDouble(); val byd = by.toDouble(); val bzd = bz.toDouble()
        val cx = ayd * bzd - azd * byd
        val cy = azd * bxd - axd * bzd
        val cz = axd * byd - ayd * bxd
        val cross = sqrt(cx * cx + cy * cy + cz * cz)
        val dot = axd * bxd + ayd * byd + azd * bzd
        if (cross.isNaN() || dot.isNaN()) return Float.NaN
        if (cross == 0.0 && dot == 0.0) return Float.NaN
        return Math.toDegrees(atan2(cross, dot)).toFloat()
    }

    /** Convenience variant: angle between samples `i` and `j` of the same gravity field. */
    fun angleDeg(g: TriAxial, i: Int, j: Int): Float =
        angleDeg(g.x[i], g.y[i], g.z[i], g.x[j], g.y[j], g.z[j])
}
