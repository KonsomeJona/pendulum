package com.pendulum.algo.dsp

import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.sqrt

/**
 * Step 2 — magnitude and two-scale envelope.
 *
 * **What the L2 magnitude does, and what it does not do.** `m = sqrt(ax^2 + ay^2 + az^2)` is
 * invariant under **constant rotation** of the strap: the case can be turned around the ankle from
 * one night to the next without changing the measured amplitude one iota. That is what makes the
 * detector independent of the mounting orientation, with no axis calibration at all. It is on the
 * other hand invariant neither under a change of **gain** (strap tightness: that is part B of
 * §3.3), nor under *variable* rotation during the event.
 *
 * **Statistical point to know and above all not to "fix"** (§2, step 2): on isotropic Gaussian
 * noise, `m` follows a Maxwell distribution of mean `1.596 sigma` and coefficient of variation
 * 42 %. **The L2 magnitude does not have a zero mean, it has a pedestal.** This is not a problem
 * as long as the floor is estimated **on the same quantity** — which is what step 3 does — because
 * the `k_on` ratio then becomes self-consistent. One simply has to know that "8 times the floor"
 * means 8 times a Maxwell mean, and not 8 times a per-axis standard deviation (which would be
 * 12.8 sigma).
 */
object Envelope {

    /** `m(t) = ||a_lin(t)||`. `NaN` propagates: a hole stays a hole. */
    fun magnitudeL2(t: TriAxial): Signal1D {
        val n = t.n
        val v = FloatArray(n)
        for (i in 0 until n) {
            val x = t.x[i]; val y = t.y[i]; val z = t.z[i]
            v[i] = if (x.isNaN() || y.isNaN() || z.isNaN()) Float.NaN
            else sqrt((x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)).toFloat()
        }
        return Signal1D(t.fsHz, t.t0Ns, v)
    }

    /**
     * Centred moving RMS, computed **segment by segment**.
     *
     * The windows are truncated over the first and last `W/2` seconds of each segment and
     * normalised by the number of valid samples (§2, step 2, edge behaviour). Those zones fall
     * into the already excluded `warmup` anyway: the truncation serves to avoid manufacturing a
     * discontinuity, not to make the edges usable.
     *
     * Never let a window cross a segment boundary: on either side, the mechanical coupling and the
     * filter states have nothing in common any more.
     */
    fun rms(s: Signal1D, winSec: Double, segments: List<Segment>): Signal1D {
        val out = FloatArray(s.n) { Float.NaN }
        val win = Numeric.samples(winSec, s.fsHz)
        for (seg in segments) {
            if (seg.length <= 0) continue
            Numeric.movingRms(s.v, seg.fromIdx, seg.toIdx, win, out)
        }
        return Signal1D(s.fsHz, s.t0Ns, out)
    }

    /**
     * Two-scale envelope.
     *
     * **Why two, and why the coarse one carries the decision.** v1 detected on a 0.15 s window.
     * That was a bug, not a setting (§0-b): at 0.15 s the window does not even average half a
     * period of the spectral content of a CLM (whose peak is around 2 Hz, i.e. 0.25 s of half
     * period). The envelope therefore keeps the `2f` ripple of the rectified signal, and that
     * ripple crosses the threshold several times during a single movement: **a CLM is fragmented
     * into three or four short events**, each too brief to survive the minimum duration criterion
     * of 0.5 s. The movement is lost AND counting noise is manufactured. At 0.50 s (>= one full
     * period at 2 Hz) the ripple is cancelled.
     *
     * The fine one (0.15 s) is kept **only** for realigning the edges of an already detected event
     * (step 5.3) and for the WASM 3.2.1-d morphology criterion: where one wants temporal
     * resolution and not decision stability.
     */
    fun dual(
        m: Signal1D,
        segments: List<Segment>,
        coarseSec: Double = 0.50,
        fineSec: Double = 0.15,
    ): DualEnvelope = DualEnvelope(
        coarse = rms(m, coarseSec, segments),
        fine = rms(m, fineSec, segments),
        coarseWinSec = coarseSec,
        fineWinSec = fineSec,
    )
}
