package com.pendulum.algo.detect

import com.pendulum.algo.dsp.Gravity
import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.TriAxial

/**
 * Parameters of the posture detector (`docs/workings/ALGO-v2.md` §6.4). None is marked "fixed".
 *
 * @param tauSec half-window for comparing `g` (range 1.0-3.0).
 * @param postureDeg persistent rotation threshold, in degrees (range 12-30).
 * @param stableDeg post-transition stability cone (range 6-15).
 * @param stableSec hold duration inside the cone (range 5-20).
 * @param guardSec CLM exclusion window around the transition (range 1.5-4.0).
 */
data class PostureConfig(
    val tauSec: Double = 2.0,
    val postureDeg: Double = 20.0,
    val stableDeg: Double = 10.0,
    val stableSec: Double = 10.0,
    val guardSec: Double = 2.5,
)

/**
 * Detection of posture changes. Transcription of `docs/workings/ALGO-v2.md` §3.1, parameters §6.4.
 *
 * Why this detector exists: a turn-over changes the projection of gravity on an axis by up to
 * **1 g** in 0.5-3 s. Passed through the 0.5 Hz high-pass, that step produces a transient with a
 * time constant of 0.32 s, noticeable over ~2 s. A typical CLM is 30-200 mg: the posture artefact
 * is therefore **5 to 30 times larger than a real CLM** and lasts exactly the right duration to be
 * counted. It is, by far, the leading source of false positives, and it is indistinguishable from
 * a leg movement on the envelope channel alone. The only information that separates them is
 * carried by gravity: a posture **durably reorients** the segment, a CLM returns to its position.
 *
 * The detector therefore never operates on the envelope, only on `g`.
 */
object PostureDetector {

    /**
     * @param gravity `g` estimated (step 1), on the uniform grid. No `NaN` expected: the gravity
     *   channel holds the last value across the gaps (step 0).
     * @param segments continuous analysable intervals. A window never crosses a boundary.
     * @return one [PostureChange] per transition, in chronological order.
     */
    fun detect(
        gravity: TriAxial,
        segments: List<Segment>,
        cfg: PostureConfig = PostureConfig(),
    ): List<PostureChange> {
        val fs = gravity.fsHz
        require(fs > 0.0) { "fsHz must be > 0" }
        val tau = samplesOf(cfg.tauSec, fs).coerceAtLeast(1)
        val stableLen = samplesOf(cfg.stableSec, fs).coerceAtLeast(1)
        val out = ArrayList<PostureChange>()

        for (seg in segments) {
            val first = seg.fromIdx + tau
            val last = seg.toIdx - tau // excluded
            var i = first
            while (i < last) {
                val d = Gravity.angleDeg(gravity, i - tau, i + tau)
                if (d.isNaN() || d <= cfg.postureDeg) {
                    i++
                    continue
                }
                // Maximal range where the rotation exceeds the threshold: this is the whole
                // transition, from its start to its end. We do not cut at the first stable index,
                // otherwise the net angle would only measure the end of the rotation.
                var end = i
                var peakIdx = i
                var peakDeg = d
                while (end + 1 < last) {
                    val dn = Gravity.angleDeg(gravity, end + 1 - tau, end + 1 + tau)
                    if (dn.isNaN() || dn <= cfg.postureDeg) break
                    end++
                    if (dn > peakDeg) {
                        peakDeg = dn
                        peakIdx = end
                    }
                }
                // Settling condition: at least one instant of the transition must link two
                // orientations held within a `stableDeg` cone for `stableSec`.
                if (isSettled(gravity, i, end, tau, seg, stableLen, cfg.stableDeg)) {
                    val fromIdx = i - tau
                    val toIdx = (end + tau).coerceAtMost(seg.toIdx - 1)
                    val net = Gravity.angleDeg(gravity, fromIdx, toIdx)
                    out += PostureChange(
                        atIdx = peakIdx,
                        atMsRel = Math.round(peakIdx * 1000.0 / fs),
                        deltaDeg = if (net.isNaN()) peakDeg else net,
                        settleMs = Math.round((toIdx - fromIdx) * 1000.0 / fs).toInt(),
                    )
                }
                i = end + 1
            }
        }
        return out
    }

    /**
     * True if one instant of the transition links two **held** orientations.
     *
     * Specification §3.1 only writes the arrival condition ("g_u stays within a `stableDeg` cone
     * for `stableSec`"). The departure condition is added here, symmetrically, and this must be
     * flagged: without it, the rule fires on any large movement that **returns** to its position.
     * With `tau = 2 s`, there is then always an instant `t` where `g_u(t - tau)` is taken at the
     * peak of the movement and `g_u(t + tau)` after its return — the angle exceeds the threshold
     * and the arrival is perfectly stable since it is the original orientation. A plain large leg
     * movement would therefore manufacture a false posture change, which would wrongly exclude the
     * neighbouring CLM through the guard window and cut the floor estimation windows. A posture
     * change is a transition **between two persistent orientations**; that is already what table
     * §6.4 says of `stableSec` ("distinguishes a durable change from a movement").
     */
    private fun isSettled(
        g: TriAxial,
        from: Int,
        to: Int,
        tau: Int,
        seg: Segment,
        stableLen: Int,
        stableDeg: Double,
    ): Boolean {
        var k = from
        while (k <= to) {
            val after = k + tau
            val before = k - tau
            if (after < seg.toIdx && before >= seg.fromIdx &&
                holdsCone(g, before, (before - stableLen).coerceAtLeast(seg.fromIdx), before, stableDeg) &&
                holdsCone(g, after, after + 1, (after + stableLen).coerceAtMost(seg.toIdx), stableDeg)
            ) {
                return true
            }
            k++
        }
        return false
    }

    /** Does `g_u` stay within the `stableDeg` cone around `g_u(ref)` over `[from, to)`? */
    private fun holdsCone(g: TriAxial, ref: Int, from: Int, to: Int, stableDeg: Double): Boolean {
        var j = from
        while (j < to) {
            val a = Gravity.angleDeg(g, ref, j)
            if (a.isNaN() || a > stableDeg) return false
            j++
        }
        return true
    }
}

// --- Geometric helpers shared by the detect package ---

/**
 * Number of samples covering `sec` at `fs`. Delegates to `com.pendulum.algo.dsp.Numeric.samples` so
 * that a duration in seconds gives **the same** number of samples everywhere in the chain: a
 * 0.50 s morphology window and a 0.50 s envelope window must count the same.
 */
internal fun samplesOf(sec: Double, fs: Double): Int = Numeric.samples(sec, fs)

// The angle between two orientations comes from `Gravity.angleDeg` (`dsp`), which computes it as
// `atan2(||a x b||, a.b)` and not as `acos`. That detail matters here: we compare `g` with itself
// shifted by 2 s, hence two almost collinear vectors, where `acos` would lose half the significant
// digits — right in the one-degree region where `minExcursionDeg` (1.5 deg) lives.
