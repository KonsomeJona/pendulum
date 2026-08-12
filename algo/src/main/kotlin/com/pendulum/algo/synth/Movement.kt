package com.pendulum.algo.synth

import com.pendulum.algo.dsp.Filters
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Acceleration due to gravity, in m/s^2. Standard value, the same one as §5.1. */
internal const val G_MS2: Double = 9.80665

/**
 * **Fifth-order minimum-jerk** angular profile, the standard ballistic gesture in biomechanics
 * (`ALGO-v2.md` §5.1).
 *
 * ```
 * s(u)   = 10u^3 - 15u^4 + 6u^5
 * s'(u)  = 30u^2 - 60u^3 + 30u^4
 * s''(u) = 60u - 180u^2 + 120u^3          BIPOLAR pulse
 * ```
 *
 * The extrema of `s''` are at `u = (3 +/- sqrt(3))/6`, of exact value `+/- 10/sqrt(3)`, that is
 * `+/- 5.773502691896258`. It is that constant which fixes the calibration table of §5.1
 * (30 / 184 / 985 mg) and which anchors the model on the literature.
 */
internal object MinJerk {
    const val PEAK_ACCEL_COEFF: Double = 5.773502691896258 // 10 / sqrt(3)

    fun s(u: Double): Double = u * u * u * (10.0 + u * (-15.0 + 6.0 * u))

    fun sDot(u: Double): Double = u * u * (30.0 + u * (-60.0 + 30.0 * u))

    fun sDDot(u: Double): Double = u * (60.0 + u * (-180.0 + 120.0 * u))
}

/**
 * Kinematics of a complete movement: flexion (`tRise`), hold (`tHold`), return (`tFall`).
 *
 * The accelerometric signature is **quadripolar**: one bipolar pair at the flexion, one bipolar pair
 * at the return, separated by the hold phase (§5.1).
 *
 * ### The hold phase is not a motionless plateau
 *
 * The original model held `theta = theta_max` during `tHold`. With the published duration of 4.2 s
 * and a `tRise` of 0.15 to 0.50 s, that gave **~3.6 s of strictly constant angle**, hence
 * `theta' = theta'' = 0` and a gravity term reduced to a continuous plateau: after the high-pass at
 * 0.5 Hz, **nothing left**. The detector saw two bursts separated by ~2.3 s of silence and emitted
 * — correctly, applying the AASM offset rule at 0.50 s — two movements for one.
 *
 * **The source of the duration contradicts that shape.** Sforza et al. 2005 (§2.4) measures its
 * 4.2 s with the PAM-RL: entry threshold 200 mg, decay threshold 100 mg, and above all a **drop-out
 * time of 1 s** — a "kick" only ends after a full second below 100 mg. An event containing 2.3 s of
 * accelerometric silence would therefore have been cut in two by the PAM-RL itself, and the
 * published mean duration would have been of the order of half that. The same reasoning holds if the
 * 4.2 s are read as an EMG burst duration (Coleman criteria, 0.5 to 10 s): a 4.2 s EMG burst is
 * 4.2 s of **active contraction**, not a passive hold.
 *
 * The rule corpus says as much itself: the offset rule at 0.50 s only exists because a leg movement
 * is a **train of activations** separated by less than 0.5 s. A model that draws 2.3 s of silence in
 * the middle of a movement contradicts the very rule the detector applies.
 *
 * **The model retained**: during the hold, the flexion is sustained by continuous, non-smooth muscle
 * activity (the classic clonic / tremulous component of PLMS). The angle oscillates around
 * `theta_max` over `holdCycles` successive dips, each half-dip being a minimum-jerk profile just
 * like the flexion itself. Two intended consequences:
 *
 *  - **No new constant is tuned on the test.** The period of a dip is set on `2 x tRise`, the
 *    movement's own ballistic scale: the spectral peak of a half-dip is `0.8 / tRise`, exactly the
 *    1.6-5.3 Hz band of §5.1, and the repetition frequency `1/(2.tRise)` falls in 1.0-3.3 Hz, the
 *    published clonic band.
 *  - **The relative depth comes from Sforza as well**: `holdDepthRad = 0.50 x theta_max` gives a
 *    hold acceleration peak equal to **0.50 x** the ballistic peak, that is to say the decay
 *    threshold / entry threshold ratio of the PAM-RL (100 mg / 200 mg). It is the minimum an event
 *    must sustain for that device to have counted it as a single 4.2 s kick.
 *
 * The junctions are **C2**: `s'(0) = s'(1) = 0` and `s''(0) = s''(1) = 0`, hence neither a velocity
 * jump nor an acceleration jump between flexion, successive dips and return. The calibration table
 * of §5.1 (30 / 184 / 985 mg) depends only on `theta_max`, `tRise` and `r`: it is unchanged.
 *
 * `holdCycles = 0` keeps the original motionless plateau; it is the default, kept for the
 * distractors (gross body movements, calibration ritual) whose hold phase really is a passive hold.
 */
internal class MovementKinematics(
    val thetaMaxRad: Double,
    val tRiseSec: Double,
    val tHoldSec: Double,
    val tFallSec: Double,
    val holdCycles: Int = 0,
    val holdDepthRad: Double = 0.0,
) {
    val totalSec: Double get() = tRiseSec + tHoldSec + tFallSec

    /** Duration of one hold dip, in seconds. `0` when the hold is a motionless plateau. */
    private val holdCycleSec: Double =
        if (holdCycles > 0 && tHoldSec > 0.0) tHoldSec / holdCycles else 0.0

    /**
     * Parameter `v` of the current half-dip, at time `tIn` counted from the start of the hold.
     * `v` runs over [0, 1] on the way out as on the way back; `dv/dt` is `+2/tc` then `-2/tc`. It is
     * that symmetry which makes the junctions exact: `theta''` reads `-depth . s''(v) . (dv/dt)^2`
     * in both half-dips, and vanishes at both ends since `s''(0) = s''(1) = 0`.
     */
    private fun holdV(tIn: Double): Double {
        val u = (tIn % holdCycleSec) / holdCycleSec
        return if (u < 0.5) 2.0 * u else 2.0 - 2.0 * u
    }

    /** Angle, in radians, at time `t` counted from the onset. */
    fun theta(t: Double): Double = when {
        t < 0.0 -> 0.0
        t < tRiseSec -> thetaMaxRad * MinJerk.s(t / tRiseSec)
        t < tRiseSec + tHoldSec ->
            if (holdCycleSec <= 0.0) thetaMaxRad
            else thetaMaxRad - holdDepthRad * MinJerk.s(holdV(t - tRiseSec))
        t < totalSec -> thetaMaxRad * (1.0 - MinJerk.s((t - tRiseSec - tHoldSec) / tFallSec))
        else -> 0.0
    }

    /** Angular velocity, in rad/s. */
    fun thetaDot(t: Double): Double = when {
        t < 0.0 -> 0.0
        t < tRiseSec -> thetaMaxRad * MinJerk.sDot(t / tRiseSec) / tRiseSec
        t < tRiseSec + tHoldSec -> {
            if (holdCycleSec <= 0.0) {
                0.0
            } else {
                val tIn = t - tRiseSec
                val sign = if ((tIn % holdCycleSec) / holdCycleSec < 0.5) 1.0 else -1.0
                -holdDepthRad * MinJerk.sDot(holdV(tIn)) * sign * 2.0 / holdCycleSec
            }
        }
        t < totalSec -> -thetaMaxRad * MinJerk.sDot((t - tRiseSec - tHoldSec) / tFallSec) / tFallSec
        else -> 0.0
    }

    /** Angular acceleration, in rad/s^2. */
    fun thetaDDot(t: Double): Double = when {
        t < 0.0 -> 0.0
        t < tRiseSec -> thetaMaxRad * MinJerk.sDDot(t / tRiseSec) / (tRiseSec * tRiseSec)
        t < tRiseSec + tHoldSec -> {
            if (holdCycleSec <= 0.0) {
                0.0
            } else {
                val a = 2.0 / holdCycleSec
                -holdDepthRad * MinJerk.sDDot(holdV(t - tRiseSec)) * a * a
            }
        }
        t < totalSec -> -thetaMaxRad * MinJerk.sDDot((t - tRiseSec - tHoldSec) / tFallSec) /
            (tFallSec * tFallSec)
        else -> 0.0
    }

    /** Theoretical peak of the tangential term, in g. The "tangential peak" column of §5.1. */
    fun peakTangentialG(radiusM: Double): Double =
        MinJerk.PEAK_ACCEL_COEFF * radiusM * thetaMaxRad / (tRiseSec * tRiseSec) / G_MS2
}

/**
 * Tri-axial signal of one movement, sampled, **static gravity excluded**.
 *
 * Frame convention, sensor at the ankle:
 *  - `x` = long axis of the tibia, pointing towards the foot; it is also the lever arm `r`;
 *  - `y` = anterior; it is the tangential direction, `y = z x x`;
 *  - `z` = medio-lateral; it is the flexion axis of the knee and of the ankle.
 *
 * Three contributions, all from §5.1:
 *  1. `a_tang = r.theta''` along `+y`;
 *  2. `a_cent = r.theta'^2` along `-x` (towards the centre of rotation, proximal);
 *  3. the **rotation of the gravity vector** as seen in the sensor frame. At large angles it is the
 *     one that dominates: at 20 degrees, `sin(20) = 0.342 g` spread over ~0.25 s. Both must be
 *     modelled — and that is exactly what makes `tiltExcursionDeg` informative on the detector side.
 *
 * The `coupling` factor (strap tightness, §3.3) attenuates **everything** the limb transmits to the
 * case: the effective lever arm as much as the angle the sensor actually undergoes. This is
 * physically coherent — a loose strap lets the case follow the limb only partly — and it is what
 * makes `gainCal` measure exactly the variable that destroys night-to-night comparability.
 */
internal class MovementRender(val n: Int) {
    val dx = DoubleArray(n)
    val dy = DoubleArray(n)
    val dz = DoubleArray(n)

    /** Peak of the norm of the movement signal. */
    var peakG: Double = 0.0
        private set

    fun computePeak() {
        var p = 0.0
        for (i in 0 until n) {
            val m = sqrt(dx[i] * dx[i] + dy[i] * dy[i] + dz[i] * dz[i])
            if (m > p) p = m
        }
        peakG = p
    }
}

/**
 * Renders one movement over `n` samples at `fs`, starting from orientation `g0` (unit gravity vector
 * in the sensor frame at the onset).
 */
internal fun renderMovement(
    kin: MovementKinematics,
    radiusM: Double,
    coupling: Double,
    g0x: Double,
    g0y: Double,
    g0z: Double,
    fs: Double,
    n: Int,
): MovementRender {
    val out = MovementRender(n)
    val rEff = radiusM * coupling
    for (i in 0 until n) {
        val t = i / fs
        val th = kin.theta(t) * coupling
        val thd = kin.thetaDot(t) * coupling
        val thdd = kin.thetaDDot(t) * coupling

        // 1 + 2: inertial terms, in the sagittal plane.
        val aTan = rEff * thdd / G_MS2
        val aCen = rEff * thd * thd / G_MS2

        // 3: rotation of g about z by -theta (turning the sensor by +theta turns the apparent
        // gravity by -theta). g0 is subtracted: only the CHANGE comes from the movement.
        val c = cos(th)
        val s = sin(th)
        val gxr = g0x * c + g0y * s
        val gyr = -g0x * s + g0y * c

        out.dx[i] = -aCen + (gxr - g0x)
        out.dy[i] = aTan + (gyr - g0y)
        out.dz[i] = 0.0
    }
    out.computePeak()
    return out
}

/**
 * Peak of `env_c` — the **centred** RMS envelope of width `win` of the norm of the render, **after
 * the movement-channel band-pass of step 1**.
 *
 * The band-pass is not a refinement: it is what makes the [AmplitudeScale.COARSE_ENVELOPE] scale
 * conform to its definition — "exactly the quantity the detector compares to `Theta_on`". The
 * detector never sees the raw render: it sees `RMS_0.5s(||ButterBP(0.5-8 Hz)(a)||)`. Measuring the
 * amplitude on the unfiltered render overestimates the movements whose energy lives below 0.5 Hz —
 * first among them the **gravity rotation** term, which is a near-continuous plateau during the hold
 * phase. On the calibration ritual (25 degrees held for 0.4 s) the discrepancy reaches a factor of
 * 3: `gainCal` was overestimated by that much, and the third term of the threshold along with it.
 *
 * Step 1 filters each axis separately **then** takes the L2 norm (§2, steps 1 and 2): that is the
 * order reproduced here. Filtering is linear, so passing the render on its own is exact: the
 * contribution of the movement to `a_lin` really is `ButterBP(render)`, whatever background it is
 * added on top of.
 *
 * The render is extended with zeros on the right over `tailSec` so that the tail of the filter
 * response is counted — an isolated movement starts and ends at rest, but the filter is still
 * ringing. The window convention is that of `com.pendulum.algo.dsp.Numeric.movingRms`
 * (`halfLeft = (win-1)/2`), to within half a sample, without which the edges would be shifted.
 */
internal fun coarseEnvelopePeak(
    r: MovementRender,
    win: Int,
    fsHz: Double,
    fcHpHz: Double = 0.50,
    fcLpHz: Double = 8.0,
    hpOrder: Int = 2,
    tailSec: Double = 2.0,
): Double {
    val n = r.n
    if (n == 0) return 0.0
    val tail = Math.round(tailSec * fsHz).toInt().coerceAtLeast(0)
    val m = n + tail
    val mag2 = DoubleArray(m)
    val axes = arrayOf(r.dx, r.dy, r.dz)
    for (a in axes) {
        val bp = Filters.butterBandpass(fsHz, fcHpHz, fcLpHz, hpOrder)
        bp.resetToDc(0f)
        for (i in 0 until m) {
            val v = bp.step(if (i < n) a[i].toFloat() else 0f).toDouble()
            mag2[i] += v * v
        }
    }
    val w = win.coerceAtLeast(1)
    val halfLeft = (w - 1) / 2
    // Samples outside the buffer are 0 (rest before the movement, silence after the tail), but the
    // divisor stays `w`: that is what the sliding RMS of step 2 does in the middle of a segment.
    var best = 0.0
    for (c in 0 until m) {
        val from = c - halfLeft
        var acc = 0.0
        for (k in from until from + w) {
            if (k in 0 until m) acc += mag2[k]
        }
        val v = sqrt(acc / w)
        if (v > best) best = v
    }
    return best
}

/**
 * Tunes `thetaMax` so that the render reaches the requested amplitude on the requested scale.
 *
 * Multiplicative fixed-point iteration: the tangential term is exactly linear in `thetaMax`, the
 * gravity term is linear to first order and the centripetal term is quadratic; three rounds are
 * enough to converge below one part per thousand over the whole physiological range. `thetaMax` is
 * then clamped to `[0.2 ; 60] degrees`: beyond that, it is no longer a triple-flexion response.
 *
 * **The tuning is always done at coupling 1**, and the real coupling is applied to the render only.
 * The other way round would cancel out the strap tightness by amplifying the angle to recover the
 * requested amplitude — and test T11 would no longer measure anything at all. That is the reason
 * this function takes no `coupling` parameter.
 *
 * `holdDepthRatio` is the depth of the hold dips **as a fraction of `thetaMax`**: it therefore
 * follows the angle at each round of the iteration, which keeps the multiplicative fixed point
 * valid.
 */
internal fun calibrateThetaMax(
    targetG: Double,
    tRiseSec: Double,
    tHoldSec: Double,
    tFallSec: Double,
    radiusM: Double,
    g0x: Double,
    g0y: Double,
    g0z: Double,
    fs: Double,
    n: Int,
    scale: AmplitudeScale,
    envWin: Int,
    holdCycles: Int = 0,
    holdDepthRatio: Double = 0.0,
): Double {
    var theta = 0.10 // rad, starting point ~5.7 degrees
    val minTheta = Math.toRadians(0.2)
    val maxTheta = Math.toRadians(60.0)
    repeat(4) {
        val kin = MovementKinematics(
            theta, tRiseSec, tHoldSec, tFallSec, holdCycles, holdDepthRatio * theta,
        )
        val r = renderMovement(kin, radiusM, 1.0, g0x, g0y, g0z, fs, n)
        val got = when (scale) {
            AmplitudeScale.PEAK -> r.peakG
            AmplitudeScale.COARSE_ENVELOPE -> coarseEnvelopePeak(r, envWin, fs)
        }
        if (got <= 1e-12) return maxTheta
        theta = (theta * targetG / got).coerceIn(minTheta, maxTheta)
    }
    return theta
}
