package com.pendulum.algo.dsp

import com.pendulum.algo.model.FsEstimate
import com.pendulum.algo.model.Gap
import com.pendulum.algo.model.GapKind
import com.pendulum.algo.model.IntegrityConfig
import com.pendulum.algo.model.IntegrityReport
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Timeline
import com.pendulum.algo.model.TriAxial
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Estimation of the real sampling frequency and of the clock drift (§3.4).
 *
 * Rule number one: **never trust `nominalRateHz`**. The field exists in the header for
 * traceability, not for computation. `fs` is recomputed from `(tFirstNs, tLastNs, N)` of each
 * block — after revalidation by step −1, since those fields are not covered by the CRC.
 *
 * The stake in figures: an `fs` wrong by 5.2 % (50 -> 52.6 Hz) only moves the high-pass corner
 * from 0.500 to 0.526 Hz, with no audible effect — but it falsifies **all the durations by
 * 5.2 %**. A CLM measured at 10.0 s really lasts 9.5 s, an IMI of 90 s is worth 85.5 s: the events
 * at class boundaries switch en masse.
 */
object Rate {

    /**
     * @param outlierTol rejection tolerance around the median (§6.1, `fsOutlierTol = 0.05`).
     *   An aberrant block `fs` is a **symptom of corrupted timestamps**, not of drift: the drift of
     *   a quartz is measured in ppm, not in percent.
     */
    fun estimate(blocks: List<SampleBlock>, nominalHz: Double, outlierTol: Double = 0.05): FsEstimate {
        val fsList = ArrayList<Double>(blocks.size)
        val wList = ArrayList<Double>(blocks.size)
        for (b in blocks) {
            val n = b.x.size
            if (n < 2) continue
            val span = (b.tLastNs - b.tFirstNs).toDouble()
            if (span <= 0.0) continue
            fsList.add((n - 1) * 1e9 / span)
            wList.add(n.toDouble())
        }
        if (fsList.isEmpty()) {
            return FsEstimate(nominalHz, nominalHz, 0, 0.0, false)
        }
        val fsArr = fsList.toDoubleArray()
        val wArr = wList.toDoubleArray()
        // First median weighted by N: a block of 512 samples constrains `fs` much better than a
        // block of 30, and the weighting prevents a burst of short blocks at the end of the
        // session from pulling the estimate.
        val med0 = Numeric.weightedMedian(fsArr, wArr)
        var rejected = 0
        val keptFs = ArrayList<Double>(fsArr.size)
        val keptW = ArrayList<Double>(fsArr.size)
        for (i in fsArr.indices) {
            if (med0 > 0.0 && abs(fsArr[i] - med0) / med0 > outlierTol) {
                rejected++
            } else {
                keptFs.add(fsArr[i]); keptW.add(wArr[i])
            }
        }
        val fsSession = if (keptFs.isEmpty()) med0
        else Numeric.weightedMedian(keptFs.toDoubleArray(), keptW.toDoubleArray())
        return FsEstimate(
            fsSessionHz = fsSession,
            fsNominalHz = nominalHz,
            blocksRejected = rejected,
            clockDriftPpm = 0.0,
            clockDriftSuspect = false,
        )
    }

    /**
     * Drift between the `SensorEvent.timestamp` scale and the wall clock, in **ppm** (§3.4).
     *
     * Some OEMs exclude suspend time from `SensorEvent.timestamp`: the two scales then diverge
     * slowly. The consequence is not cosmetic — the merge with the Health Connect hypnogram
     * shifts, which moves CLMs from one stage to another and falsifies the PLMS / PLMW split.
     *
     * @param wallMs `startWallMs` of each chunk.
     * @param eventNs `firstEventTimestampNs` of the same chunk.
     * @return `(slope - 1) x 1e6`. `NaN` if fewer than two usable anchors.
     */
    fun clockDrift(wallMs: LongArray, eventNs: LongArray): Double {
        require(wallMs.size == eventNs.size) { "different sizes" }
        if (wallMs.size < 2) return Double.NaN
        val x = DoubleArray(wallMs.size) { (eventNs[it] - eventNs[0]).toDouble() }
        val y = DoubleArray(wallMs.size) { (wallMs[it] - wallMs[0]).toDouble() * 1e6 } // ms -> ns
        val slope = Numeric.slope(x, y)
        return if (slope.isNaN()) Double.NaN else (slope - 1.0) * 1e6
    }

    /**
     * Threshold of §3.4: a slope departing from 1 by more than 1e-4 (i.e. more than 2.9 s over
     * 8 h) must raise `CLOCK_DRIFT`.
     */
    fun driftSuspect(ppm: Double): Boolean = !ppm.isNaN() && abs(ppm) > 100.0
}

/**
 * Step 0 parameters. Default values = table §6.1, to the unit.
 *
 * The last three fields (off-body detection) appear in no table of the specification: §5.2 only
 * describes test scenario T8 ("watch on the table for 10 min, constant gravity + noise only,
 * exclusion from the numerator **and** from the denominator"). The values retained transcribe that
 * scenario and are flagged as **interpretation**.
 */
data class TimelineConfig(
    val targetFsHz: Double = 50.0,
    val gapMicroSec: Double = 0.10,
    val gapSegmentSec: Double = 2.0,
    val settleSec: Double = 2.0,
    val warmupSec: Double = 5.0,
    val fsOutlierTol: Double = 0.05,
    val integrity: IntegrityConfig = IntegrityConfig(),
    /** INTERPRETATION — analysis window for absolute immobility for the off-body. */
    val offBodyWinSec: Double = 60.0,
    /** INTERPRETATION — below this standard deviation on the three axes, nothing moves at all. */
    val offBodySdG: Float = 0.005f,
    /** INTERPRETATION — minimum duration of an off-body stretch, modelled on test T8. */
    val offBodyMinSec: Double = 600.0,
)

/**
 * Step 0 — timeline reconstruction.
 *
 * **This is the trickiest function of the preliminary stage.** Everything that follows (CLM
 * durations, IMI, PLMI denominator) is read as indices of the grid produced here: an offset of
 * half a hole falsifies the entire night without ever raising an exception.
 *
 * Structuring choice (§3.4, second point): we **resample onto a fixed grid** at `targetFsHz`
 * rather than adapting the filter coefficients to a varying `fs`. Adapting the filters would force
 * recomputing the biquads mid-session, which produces a transient at each recomputation — we would
 * be replacing a 5 % bias by localised artefacts, that is to say by false positives. Linear
 * interpolation costs **at most 1.8 %** of point-to-point attenuation at 3 Hz and makes everything
 * else exact.
 *
 * This comment long announced "0.2 % for a resampling ratio below 1.06". The specification
 * formally withdrew that sentence on 2026-07-31 (`ALGO-v2.md` §2 step 0, correction box): it is
 * not the resampling ratio that governs the attenuation but the **source sampling period**, and
 * even averaged over a uniform phase — the only reading that could have justified 0.2 % — it stays
 * around 1.1 %. The figure was therefore wrong by an order of magnitude, and it survived here past
 * its own retraction.
 */
object TimelineBuilder {

    /**
     * @param sessionClosedCleanly INTERPRETATION — [SampleBlock] carries no end-of-session marker
     *   (the format is append-only, there is no header patch on close). The caller — that is to
     *   say the `:phone` adapter, which has seen the file — is the only one that knows whether the
     *   night ended properly. By default we assume it did, so that a synthetic night is not
     *   wrongly flagged as truncated; `:phone` must pass `false` as soon as the last chunk is
     *   incomplete or the session has been killed.
     */
    fun build(
        blocks: List<SampleBlock>,
        nominalHz: Double,
        cfg: TimelineConfig = TimelineConfig(),
        sessionClosedCleanly: Boolean = true,
    ): Timeline {
        val (accepted, integrity) = Integrity.check(blocks, nominalHz, cfg.integrity)
        val fsEst = Rate.estimate(accepted, nominalHz, cfg.fsOutlierTol)

        if (accepted.isEmpty()) return emptyTimeline(cfg, fsEst, integrity)

        val fsTarget = cfg.targetFsHz
        // Reference rate for measuring a hole: the measured session `fs`, never the nominal one.
        // If either of the two is absurd we fall back on the nominal, for want of better.
        val fsSrc = if (fsEst.fsSessionHz.isFinite() && fsEst.fsSessionHz > 1.0) fsEst.fsSessionHz else nominalHz
        val stepSrcNs = 1e9 / fsSrc

        val t0Ns = sampleTimeNs(accepted[0], 0)
        val lastBlock = accepted.last()
        val tEndNs = sampleTimeNs(lastBlock, lastBlock.x.size - 1)
        val n = (((tEndNs - t0Ns).toDouble() * fsTarget / 1e9).toInt() + 1).coerceAtLeast(1)

        val gx = FloatArray(n); val gy = FloatArray(n); val gz = FloatArray(n)

        val microGapNs = cfg.gapMicroSec * 1e9
        val segmentGapNs = cfg.gapSegmentSec * 1e9

        val gaps = ArrayList<Gap>()

        // --- Filling the grid with a two-pointer cursor ----------------------------------
        // We never materialise the sequence of source samples: `(b, i)` suffices, and the
        // monotonicity guaranteed by step −1 means the cursor only moves forward.
        var cb = 0
        var ci = 0
        var nanRunStart = -1
        var nanRunPairB = -1
        var nanRunPairI = -1
        var nanRunExcessNs = 0.0

        fun closeNanRun(endExclusive: Int) {
            if (nanRunStart < 0) return
            val excessSec = nanRunExcessNs / 1e9
            val kind = if (nanRunExcessNs > segmentGapNs) GapKind.SEGMENT_BREAK else GapKind.BLIND
            gaps.add(Gap(nanRunStart, endExclusive, kind, excessSec))
            nanRunStart = -1
        }

        for (k in 0 until n) {
            val tk = t0Ns + Math.round(k * 1e9 / fsTarget)
            // Advance as long as the NEXT sample is still <= tk.
            while (true) {
                val nb: Int
                val ni: Int
                if (ci + 1 < accepted[cb].x.size) { nb = cb; ni = ci + 1 }
                else if (cb + 1 < accepted.size) { nb = cb + 1; ni = 0 }
                else break
                if (sampleTimeNs(accepted[nb], ni) <= tk) { cb = nb; ci = ni } else break
            }
            val hasNext: Boolean
            val nb: Int
            val ni: Int
            if (ci + 1 < accepted[cb].x.size) { hasNext = true; nb = cb; ni = ci + 1 }
            else if (cb + 1 < accepted.size) { hasNext = true; nb = cb + 1; ni = 0 }
            else { hasNext = false; nb = -1; ni = -1 }

            val tCur = sampleTimeNs(accepted[cb], ci)
            if (!hasNext) {
                // Last sample of the session: the grid stops on it by construction.
                if (nanRunStart >= 0) closeNanRun(k)
                gx[k] = accepted[cb].x[ci]; gy[k] = accepted[cb].y[ci]; gz[k] = accepted[cb].z[ci]
                continue
            }
            val tNext = sampleTimeNs(accepted[nb], ni)
            val excessNs = (tNext - tCur).toDouble() - stepSrcNs

            if (excessNs <= microGapNs) {
                // Micro hole (< 0.10 s, i.e. 5 samples): silent linear interpolation. Shorter than
                // the fastest characteristic of a CLM (T_rise >= 0.15 s), therefore no detectable
                // artefact — it is the only case where we manufacture data.
                if (nanRunStart >= 0) closeNanRun(k)
                val span = (tNext - tCur).toDouble()
                val u = if (span > 0.0) ((tk - tCur).toDouble() / span).coerceIn(0.0, 1.0) else 0.0
                val a = accepted[cb]; val bnx = accepted[nb]
                gx[k] = lerp(a.x[ci], bnx.x[ni], u)
                gy[k] = lerp(a.y[ci], bnx.y[ni], u)
                gz[k] = lerp(a.z[ci], bnx.z[ni], u)
            } else if ((tk - tCur).toDouble() * 2.0 <= stepSrcNs) {
                // Grid point situated less than half a sample from the last valid sample BEFORE
                // the hole: we copy that sample instead of losing it. Without this case, the hole
                // would eat a real sample at its left edge and the segment boundary would fall one
                // notch too early.
                if (nanRunStart >= 0) closeNanRun(k)
                gx[k] = accepted[cb].x[ci]; gy[k] = accepted[cb].y[ci]; gz[k] = accepted[cb].z[ci]
            } else {
                // Real hole: NaN. The movement channel will treat them as zeros, the gravity
                // channel will hold the last value (step 1); the denominator removes them.
                if (nanRunStart >= 0 && (nanRunPairB != cb || nanRunPairI != ci)) closeNanRun(k)
                if (nanRunStart < 0) {
                    nanRunStart = k
                    nanRunPairB = cb; nanRunPairI = ci
                    nanRunExcessNs = excessNs
                }
                gx[k] = Float.NaN; gy[k] = Float.NaN; gz[k] = Float.NaN
            }
        }
        closeNanRun(n)

        // The MICRO holes are reported for diagnostics but exclude nothing: their index bounds are
        // computed by the direct formula, to within +/-1 sample. The BLIND and SEGMENT_BREAK
        // holes, on the other hand, come from the NaN stretches actually written, so are exact —
        // it is on them alone that segments, blind zones and the denominator rest.
        collectMicroGaps(accepted, t0Ns, fsTarget, stepSrcNs, microGapNs, n, gaps)
        gaps.sortBy { it.fromIdx }

        val signal = TriAxial(fsTarget, t0Ns, gx, gy, gz)

        // --- Segments -------------------------------------------------------------------
        val segments = Segment.between(gaps, n)

        // --- Blind zones -----------------------------------------------------------------
        // Two origins gathered in the same list, because they have exactly the same downstream
        // effect (no CLM can begin or end there, and the time is removed from the denominator):
        //  a) the BLIND holes, widened by `settleSec` on each side — the filter rings as much
        //     after a hole as after a start;
        //  b) the first `warmupSec` seconds of EVERY segment. §2 step 1 excludes them from the
        //     analysis and from the denominator; filing them here avoids duplicating that rule in
        //     the detector, the mask and the index computation — three places where forgetting it
        //     would be silent.
        val settleSamples = Numeric.samples(cfg.settleSec, fsTarget)
        val warmupSamples = Numeric.samples(cfg.warmupSec, fsTarget)
        val blind = ArrayList<Segment>()
        for (seg in segments) {
            val head = min(seg.toIdx, seg.fromIdx + warmupSamples)
            if (head > seg.fromIdx) blind.add(Segment(seg.fromIdx, head))
        }
        for (g in gaps) {
            if (g.kind != GapKind.BLIND) continue
            val seg = segments.firstOrNull { g.fromIdx >= it.fromIdx && g.fromIdx < it.toIdx } ?: continue
            val lo = max(seg.fromIdx, g.fromIdx - settleSamples)
            val hi = min(seg.toIdx, g.toIdx + settleSamples)
            if (hi > lo) blind.add(Segment(lo, hi))
        }
        val blindZones = mergeSegments(blind)

        // --- Off-body ---------------------------------------------------------------------
        val offBody = detectOffBody(signal, accepted, t0Ns, segments, cfg)

        // --- Analysable time ---------------------------------------------------------------
        val analysable = BooleanArray(n)
        for (seg in segments) for (i in seg.fromIdx until seg.toIdx) analysable[i] = true
        for (z in blindZones) for (i in z.fromIdx until z.toIdx) analysable[i] = false
        for (z in offBody) for (i in z.fromIdx until z.toIdx) analysable[i] = false
        for (i in 0 until n) if (gx[i].isNaN()) analysable[i] = false
        var analysableCount = 0
        for (i in 0 until n) if (analysable[i]) analysableCount++

        // A night whose last blocks were rejected ends on a hole: it is truncated in the sense of
        // §3.7.2, even if the file was closed properly.
        val endsOnGap = gaps.lastOrNull()?.let { it.toIdx >= n } ?: false
        val truncated = !sessionClosedCleanly || endsOnGap

        return Timeline(
            signal = signal,
            gaps = gaps,
            segments = segments,
            blindZones = blindZones,
            fs = fsEst,
            offBody = offBody,
            integrity = integrity,
            analysableSec = analysableCount / fsTarget,
            truncated = truncated,
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Instant of sample `i` of the block, by exact interpolation of the header:
     * `t = tFirst + round(i x (tLast - tFirst) / (N - 1))`.
     *
     * This is the formula of §2 step 0, and **not** `tFirst + i/fs`: the hardware FIFO samples
     * uniformly between the two bounds of the block, but the real rate of a block can differ from
     * the session rate by a few ppm. Using `1/fs` would accumulate that difference until the end
     * of the block.
     */
    private fun sampleTimeNs(b: SampleBlock, i: Int): Long {
        val n = b.x.size
        if (n <= 1) return b.tFirstNs
        return b.tFirstNs + Math.round(i.toDouble() * (b.tLastNs - b.tFirstNs).toDouble() / (n - 1))
    }

    private fun lerp(a: Float, b: Float, u: Double): Float =
        (a.toDouble() + u * (b.toDouble() - a.toDouble())).toFloat()

    private fun collectMicroGaps(
        accepted: List<SampleBlock>,
        t0Ns: Long,
        fsTarget: Double,
        stepSrcNs: Double,
        microGapNs: Double,
        n: Int,
        out: MutableList<Gap>,
    ) {
        var prevT = Long.MIN_VALUE
        for (b in accepted) {
            val cnt = b.x.size
            if (cnt < 1) continue
            val tFirst = sampleTimeNs(b, 0)
            if (prevT != Long.MIN_VALUE) {
                val excess = (tFirst - prevT).toDouble() - stepSrcNs
                // Strictly positive: a perfect chaining is not a hole.
                if (excess > stepSrcNs * 0.5 && excess <= microGapNs) {
                    val from = ((prevT - t0Ns).toDouble() * fsTarget / 1e9).toInt() + 1
                    val to = ((tFirst - t0Ns).toDouble() * fsTarget / 1e9).toInt() + 1
                    if (to > from) {
                        out.add(Gap(from.coerceIn(0, n), to.coerceIn(0, n), GapKind.MICRO, excess / 1e9))
                    }
                }
            }
            prevT = sampleTimeNs(b, cnt - 1)
        }
    }

    /** Merges and sorts a list of intervals, absorbing the overlaps. */
    internal fun mergeSegments(src: List<Segment>): List<Segment> {
        if (src.isEmpty()) return emptyList()
        val sorted = src.sortedWith(compareBy({ it.fromIdx }, { it.toIdx }))
        val out = ArrayList<Segment>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val s = sorted[i]
            cur = if (s.fromIdx <= cur.toIdx) Segment(cur.fromIdx, max(cur.toIdx, s.toIdx)) else {
                out.add(cur); s
            }
        }
        out.add(cur)
        return out
    }

    /**
     * Off-body — INTERPRETATION.
     *
     * The specification only defines off-body by its test scenario (T8: "watch on the table for
     * 10 min, constant gravity + noise only"). Two sources are therefore combined:
     *
     *  1. the block's `FLAG_OFF_BODY` flag, when the watch set it itself;
     *  2. an **absolute immobility** detection: over windows of `offBodyWinSec`, the standard
     *     deviation of the three axes is below `offBodySdG`, and this lasts at least
     *     `offBodyMinSec`.
     *
     * The threshold is deliberately far lower than that of the immobility mask (§3.6): a sleeping
     * ankle is never totally immobile — breathing, micro-adjustments, tone — whereas a watch left
     * on a table produces only the MEMS noise. Confusing the two would cost dearly in both
     * directions: counting table time as sleep inflates the denominator and deflates the PLMI;
     * excluding genuinely quiet sleep raises it.
     */
    private fun detectOffBody(
        signal: TriAxial,
        accepted: List<SampleBlock>,
        t0Ns: Long,
        segments: List<Segment>,
        cfg: TimelineConfig,
    ): List<Segment> {
        val n = signal.n
        val fs = signal.fsHz
        val win = Numeric.samples(cfg.offBodyWinSec, fs)
        val minSamples = Numeric.samples(cfg.offBodyMinSec, fs)
        val candidates = ArrayList<Segment>()

        for (seg in segments) {
            var runStart = -1
            var w = seg.fromIdx
            while (w < seg.toIdx) {
                val hi = min(seg.toIdx, w + win)
                val still = isStill(signal, w, hi, cfg.offBodySdG)
                if (still) {
                    if (runStart < 0) runStart = w
                } else {
                    if (runStart >= 0 && w - runStart >= minSamples) candidates.add(Segment(runStart, w))
                    runStart = -1
                }
                w = hi
            }
            if (runStart >= 0 && seg.toIdx - runStart >= minSamples) candidates.add(Segment(runStart, seg.toIdx))
        }

        // Hardware flag: we trust the watch to say "not worn", never to say "worn" (the absence of
        // a flag proves nothing, the off-body sensor is optional).
        for (b in accepted) {
            if ((b.flags and BlockFlags.OFF_BODY) == 0) continue
            val from = (((b.tFirstNs - t0Ns).toDouble() * fs / 1e9).toInt()).coerceIn(0, n)
            val to = (((b.tLastNs - t0Ns).toDouble() * fs / 1e9).toInt() + 1).coerceIn(0, n)
            if (to > from) candidates.add(Segment(from, to))
        }
        return mergeSegments(candidates)
    }

    private fun isStill(s: TriAxial, from: Int, to: Int, sdLimit: Float): Boolean {
        if (to - from < 2) return false
        val axes = arrayOf(s.x, s.y, s.z)
        for (a in axes) {
            var sum = 0.0; var sum2 = 0.0; var cnt = 0
            for (i in from until to) {
                val v = a[i]
                if (v.isNaN()) continue
                sum += v; sum2 += v.toDouble() * v; cnt++
            }
            if (cnt < 2) return false
            val mean = sum / cnt
            val varr = max(0.0, sum2 / cnt - mean * mean)
            if (sqrt(varr) >= sdLimit) return false
        }
        return true
    }

    private fun emptyTimeline(cfg: TimelineConfig, fs: FsEstimate, integrity: IntegrityReport) = Timeline(
        signal = TriAxial(cfg.targetFsHz, 0L, FloatArray(0), FloatArray(0), FloatArray(0)),
        gaps = emptyList(),
        segments = emptyList(),
        blindZones = emptyList(),
        fs = fs,
        offBody = emptyList(),
        integrity = integrity,
        analysableSec = 0.0,
        truncated = true,
    )
}
