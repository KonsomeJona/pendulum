package com.pendulum.algo.dsp

import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.Signal1D

/**
 * Step 4 parameters. Default values = table §6.3.
 *
 * This class carries the same four values as `com.pendulum.algo.detect.ThresholdConfig`, from
 * which it is deliberately distinct: `dsp` computes the threshold curves, `detect` decides. The
 * detector builds its [ThresholdParams] from its own configuration; this avoids a dependency from
 * `dsp` towards `detect` — the reverse of the chain's direction.
 */
data class ThresholdParams(
    val kOn: Double = 8.0,
    val kOff: Double = 2.5,
    val absFloorG: Float = 0.020f,
    val calFraction: Double = 0.12,
) {
    /**
     * Hysteresis ratio, `k_off / k_on` = 0.3125 with the default values (the specification writes
     * it "0.31").
     *
     * It is **computed** and not hard-coded, because that is what guarantees the announced
     * property: the hysteresis is 3.2 **whatever the dominant term**. Freezing 0.31 while leaving
     * `kOn`/`kOff` adjustable would break that invariance as soon as either of the two moved, and
     * the detector would start releasing too early or too late depending on the night's regime.
     */
    val hysteresisRatio: Double get() = kOff / kOn
}

/**
 * The two threshold curves, plus the **traceability of the dominant term** sample by sample
 * (`ClmFlags.ABS_FLOOR_LIMITED` / `CAL_FLOOR_LIMITED`, 0 if it is the measured floor that
 * commands).
 *
 * This traceability is not a diagnostic luxury: it is what says whether the detector operated in
 * relative regime (sensitivity driven by the night's noise) or in floor regime (sensitivity
 * capped). Two nights that are not in the same regime are not comparable, and it is comparability
 * that makes all the value of a screening over 5 to 7 nights.
 */
class ThresholdCurves(
    val on: Signal1D,
    val off: Signal1D,
    val dominance: IntArray,
) {
    /** Fraction of the time where the threshold was capped by a non-adaptive term. */
    fun limitedFraction(): Double {
        if (dominance.isEmpty()) return 0.0
        var c = 0
        for (d in dominance) if (d != 0) c++
        return c.toDouble() / dominance.size
    }
}

/**
 * Step 4 — thresholds.
 *
 * ```
 * Theta_on(t)  = max( k_on  x floor(t),  Theta_abs,         f_cal x gainCal )
 * Theta_off(t) = max( k_off x floor(t),  Theta_abs x r,     f_cal x gainCal x r )   r = k_off/k_on
 * ```
 *
 * **Why three terms and not one.**
 *  - `k_on x floor`: the adaptive term. It follows the night's real noise. `k_on = 8` is **not**
 *    dictated by thermal noise — a factor of 4.8 would already suffice to guarantee fewer than
 *    0.01 thermal false positives per night. The 8 is entirely an **anti-artefact budget**.
 *    Practical consequence: never set `k_on` by looking at noise, only at real nights (§2 step 4,
 *    test T11).
 *  - `Theta_abs = 20 mg`: the absolute guard rail. On a very quiet night, the relative term alone
 *    would drop to 7 mg and the detector would count micro-vibrations (§1.1).
 *  - `f_cal x gainCal`: the calibration term. A CLM is declared if it reaches 12 % of the
 *    amplitude of a comfortable voluntary dorsiflexion **of that particular night** (§3.3,
 *    part B). It is the only one of the three that compensates for the strap tightness, that is to
 *    say the only variable that destroys inter-night comparability.
 */
object Thresholds {

    /** Trigger threshold for a given floor. */
    fun onAt(floorG: Float, gainCalG: Float, p: ThresholdParams = ThresholdParams()): Float {
        if (floorG.isNaN()) return Float.NaN
        val rel = (p.kOn * floorG).toFloat()
        val cal = calTerm(gainCalG, p)
        return maxOf(rel, p.absFloorG, cal)
    }

    /** Release threshold. The hysteresis ratio is preserved term by term. */
    fun offAt(floorG: Float, gainCalG: Float, p: ThresholdParams = ThresholdParams()): Float {
        if (floorG.isNaN()) return Float.NaN
        val r = p.hysteresisRatio
        val rel = (p.kOff * floorG).toFloat()
        val abs = (p.absFloorG * r).toFloat()
        val cal = (calTerm(gainCalG, p) * r).toFloat()
        return maxOf(rel, abs, cal)
    }

    /**
     * Which of the three terms commands the trigger threshold.
     * @return 0 (adaptive floor), [ClmFlags.ABS_FLOOR_LIMITED] or [ClmFlags.CAL_FLOOR_LIMITED].
     *   In case of exact equality, priority goes to the least adaptive term, which is the most
     *   informative to report: calibration, then absolute floor.
     */
    fun dominanceAt(floorG: Float, gainCalG: Float, p: ThresholdParams = ThresholdParams()): Int {
        if (floorG.isNaN()) return 0
        val rel = (p.kOn * floorG).toFloat()
        val cal = calTerm(gainCalG, p)
        val abs = p.absFloorG
        return when {
            cal >= rel && cal >= abs && cal > 0f -> ClmFlags.CAL_FLOOR_LIMITED
            abs >= rel -> ClmFlags.ABS_FLOOR_LIMITED
            else -> 0
        }
    }

    /**
     * Complete curves over the whole night.
     *
     * @param gainCalG the night's calibration gain; `0` or `NaN` disables the third term (the
     *   `GainSource.NONE` case, where no gross body movement was
     *   observed). The threshold then falls back on `max(k_on x floor, Theta_abs)`, which stays
     *   correct — but the night is no longer comparable to the calibrated nights, and it is
     *   `NightCalibration.gainSource` that must accompany the published result.
     */
    fun compute(
        floor: Signal1D,
        gainCalG: Float,
        p: ThresholdParams = ThresholdParams(),
    ): ThresholdCurves {
        val n = floor.n
        val on = FloatArray(n)
        val off = FloatArray(n)
        val dom = IntArray(n)
        for (i in 0 until n) {
            val f = floor.v[i]
            on[i] = onAt(f, gainCalG, p)
            off[i] = offAt(f, gainCalG, p)
            dom[i] = dominanceAt(f, gainCalG, p)
        }
        return ThresholdCurves(
            Signal1D(floor.fsHz, floor.t0Ns, on),
            Signal1D(floor.fsHz, floor.t0Ns, off),
            dom,
        )
    }

    fun compute(floor: Signal1D, cal: NightCalibration, p: ThresholdParams = ThresholdParams()): ThresholdCurves =
        compute(floor, cal.gainCalG, p)

    private fun calTerm(gainCalG: Float, p: ThresholdParams): Float =
        if (gainCalG.isNaN() || gainCalG <= 0f) 0f else (p.calFraction * gainCalG).toFloat()
}
