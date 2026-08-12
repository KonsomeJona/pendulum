package com.pendulum.algo.synth

import com.pendulum.algo.dsp.Filters
import com.pendulum.algo.dsp.Numeric
import kotlin.math.abs
import kotlin.math.sqrt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The **movement model** of `docs/03-algorithm.md` §7.5 (`docs/workings/ALGO-v2.md` §5.1), tested for itself
 * and not through the full chain.
 *
 * Two things are checked here, and they are of a different nature:
 *
 *  1. the 30 / 184 / 985 mg calibration table does come out of the model — it is the strongest
 *     validity argument the generator has, and it was asserted nowhere;
 *  2. the hold phase is not accelerometrically mute. That was the root cause of the T6
 *     fragmentation: an immobile hold of ~3.6 s produced ~2.3 s of silence, and the detector cut
 *     every movement into pieces by applying the AASM 0.50 s offset rule literally.
 */
class MovementModelTest {

    private val fs = 50.0

    /** Published duration (Sforza 2005) and published range of `T_rise` (§5.1). */
    private val totalSec = 4.2
    private val tRisesSec = doubleArrayOf(0.15, 0.30, 0.50)

    /**
     * **§5.1 — the calibration table.** These four values are cited throughout the documentation
     * as the proof that the model "settles by itself on the published figures". They quantify the
     * **tangential** term `r . theta''` alone, not the peak of the rendered magnitude: it is
     * [MovementKinematics.peakTangentialG] that computes them, and that is the reason this
     * function exists.
     */
    @Test
    @DisplayName("§5.1 — the 30 / 184 / 985 mg table comes out of the model with no tuning")
    fun peakTangentialMatchesTheCalibrationTable() {
        fun peak(deg: Double, tRiseSec: Double, radiusM: Double): Double =
            MovementKinematics(Math.toRadians(deg), tRiseSec, 0.0, tRiseSec).peakTangentialG(radiusM)

        assertThat(peak(4.0, 0.45, 0.15)).`as`("small CLM").isCloseTo(0.030, within(1e-3))
        assertThat(peak(10.0, 0.35, 0.22)).`as`("medium CLM").isCloseTo(0.184, within(1e-3))
        assertThat(peak(20.0, 0.25, 0.30)).`as`("large CLM").isCloseTo(0.985, within(2e-3))
        assertThat(peak(15.0, 0.30, 0.02)).`as`("ankle only").isCloseTo(0.034, within(1e-3))
    }

    /**
     * **The hold sustains at least 0.40 times the ballistic peak.**
     *
     * The model constant is `holdActivityRatio = 0.50`, read off Sforza's PAM-RL: decay threshold
     * 100 mg against entry threshold 200 mg. The assertion is on the **angular acceleration**, the
     * quantity where that constant is defined, without the 0.5 s RMS window that would mix the
     * shape of the hold with the shape of the flexion.
     *
     * What is achieved is not exactly 0.50: the number of troughs is an integer, so the period of
     * a trough is `T_hold / round(T_hold / 2.T_rise)` and not exactly `2 . T_rise`. The largest
     * departure over the published range of `T_rise` is 12 % (0.439 at `T_rise = 0.50 s`), hence
     * the bound at 0.40.
     */
    @Test
    @DisplayName("§5.1 — the hold sustains >= 0.40 times the ballistic peak (PAM-RL 100/200 mg)")
    fun theActiveHoldSustainsTheSforzaRatio() {
        for (tRise in tRisesSec) {
            val kin = kinematics(tRise, holdCycles = cyclesFor(tRise), holdRatio = 0.50)
            val ballistic = peakAbs(kin, 0.0, tRise)
            val hold = peakAbs(kin, tRise + 0.05, tRise + kin.tHoldSec - 0.05)
            assertThat(hold / ballistic)
                .`as`("hold peak / ballistic peak, T_rise = %.2f s", tRise)
                .isGreaterThanOrEqualTo(0.40)
        }
    }

    /**
     * **No interior silence lasts half a second.**
     *
     * The duration threshold is the rule itself: `offHoldSec = 0.50 s`, the length of continuous
     * release that, per AASM/WASM, ends a movement. A model that produces a silence longer than
     * that asserts something the very rule it serves to test declares impossible.
     *
     * The reference level is the detector's hysteresis ratio, `k_off / k_on = 2.5 / 8.0 = 0.3125`:
     * below it, an event whose peak only just reaches `Theta_on` would be released.
     */
    @Test
    @DisplayName("§5.1 — a 4.2 s movement contains no half-second silence")
    fun theActiveHoldNeverGoesQuietForHalfASecond() {
        for (tRise in tRisesSec) {
            assertThat(longestQuietRunSec(cyclesFor(tRise), tRise))
                .`as`("longest interior silence, T_rise = %.2f s", tRise)
                .isLessThan(0.50)
        }
    }

    /**
     * **Inverted assertion, and that is the point** — the same measurement on the original
     * immobile plateau.
     *
     * It has the same function as T11: showing that the correction corrects something. Were this
     * test to fall below 0.50 s, the active hold would serve no purpose and should be removed
     * rather than kept out of caution. It also quantifies the defect: more than two seconds of
     * silence, hence every movement being cut into pieces of about 1.1 s measured on the nominal
     * night (`07-validation.md` §5.5).
     */
    @Test
    @DisplayName("§5.1 — the original static plateau, by contrast, stays mute over 2 s (inverted)")
    fun theStaticPlateauIsSilentForOverTwoSeconds() {
        assertThat(longestQuietRunSec(holdCycles = 0, tRiseSec = 0.30))
            .`as`("longest silence of the immobile plateau")
            .isGreaterThan(2.0)
    }

    // ---------------------------------------------------------------------------------------

    /** Number of hold troughs: one per `2 . T_rise`, as `NightSynth` does. */
    private fun cyclesFor(tRiseSec: Double): Int =
        Math.round((totalSec - 2.0 * tRiseSec) / (2.0 * tRiseSec)).toInt().coerceAtLeast(1)

    private fun kinematics(tRiseSec: Double, holdCycles: Int, holdRatio: Double): MovementKinematics {
        val theta = Math.toRadians(12.0)
        return MovementKinematics(
            theta, tRiseSec, totalSec - 2.0 * tRiseSec, tRiseSec, holdCycles, holdRatio * theta,
        )
    }

    /** Peak of `|theta''|` over `[fromSec, toSec)`, finely sampled. */
    private fun peakAbs(kin: MovementKinematics, fromSec: Double, toSec: Double): Double {
        var best = 0.0
        var t = fromSec
        while (t < toSec) {
            val v = abs(kin.thetaDDot(t))
            if (v > best) best = v
            t += 1e-3
        }
        return best
    }

    /**
     * Longest run, **inside the movement**, where the 0.5 s RMS envelope of the band-passed signal
     * stays below `k_off / k_on` times its peak. This is the quantity a hysteresis accelerometric
     * detector sees.
     */
    private fun longestQuietRunSec(holdCycles: Int, tRiseSec: Double): Double {
        val kin = kinematics(tRiseSec, holdCycles, holdRatio = 0.50)
        val n = Math.round(totalSec * fs).toInt() + 1
        // Gravity along the long axis of the tibia: the reprojection of g contributes, as in a
        // real night. It is the dominant contribution at large angles (§5.1).
        val r = renderMovement(kin, 0.22, 1.0, 1.0, 0.0, 0.0, fs, n)

        val env = FloatArray(n)
        Numeric.movingRms(bandPassedMagnitude(r, n), 0, n, Math.round(0.5 * fs).toInt(), env)

        var peak = 0f
        for (v in env) if (v.isFinite() && v > peak) peak = v
        val level = (2.5f / 8.0f) * peak

        // We measure the **interior** silence: the low runs at the start and at the end are the
        // edges of the movement, not a trough.
        var first = -1
        var last = -1
        for (i in 0 until n) if (env[i] >= level) { if (first < 0) first = i; last = i }
        if (first < 0) return totalSec

        var longest = 0
        var run = 0
        for (i in first..last) {
            if (env[i] < level) { run++; if (run > longest) longest = run } else run = 0
        }
        return longest / fs
    }

    /** L2 norm of the render after the 0.5-8 Hz band-pass of step 1, axis by axis. */
    private fun bandPassedMagnitude(r: MovementRender, n: Int): FloatArray {
        val acc = DoubleArray(n)
        for (a in arrayOf(r.dx, r.dy, r.dz)) {
            val bp = Filters.butterBandpass(fs, 0.50, 8.0, 2)
            bp.resetToDc(0f)
            for (i in 0 until n) {
                val v = bp.step(a[i].toFloat()).toDouble()
                acc[i] += v * v
            }
        }
        return FloatArray(n) { sqrt(acc[it]).toFloat() }
    }
}
