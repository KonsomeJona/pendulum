package com.pendulum.algo.synth

import com.pendulum.algo.detect.SeriesBuilder
import com.pendulum.algo.detect.SeriesConfig
import com.pendulum.algo.indices.Periodicity
import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.indices.Rhythm
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.Gap
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PlmiResult
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.GapKind
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SimpleBlock
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.algo.dsp.BlockFlags
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generator of synthetic nights with injected ground truth. `docs/workings/ALGO-v2.md` §5.
 *
 * Deterministic: same `seed` -> **bit-identical** output (test T13). No clock, no unseeded source of
 * randomness, no object `hashCode` anywhere in the generation path.
 *
 * Two stances that deserve to be read before using the output:
 *
 *  1. **The useful spectral content is below 6 Hz.** The peak of the bipolar pulse is at
 *     `f_peak ~ 0.8 / T_rise`, that is 1.6 to 5.3 Hz for `T_rise` in [0.15 ; 0.50] s. The "10-15 Hz
 *     packets" of v1 are wrong and are not generated: a generator that produced them would validate
 *     the detector against a signal that does not exist.
 *  2. **The distractors are generated with the same care as the useful signal.** A docile generator
 *     produces a detector that only works on it — this is defect F-21 of
 *     `docs/workings/CRITICAL-REVIEW.md`, "circular validation". The twelve families of §5.2 are therefore
 *     all rendered physically, including those that hurt (posture at 120 degrees, mattress ringing
 *     right at the morphology threshold, gain step in the middle of the night).
 */
object NightSynth {

    fun generate(spec: NightSpec, seed: Long): SynthNight = Generator(spec, seed).run()
}

// ---------------------------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------------------------

/** A change of limb orientation: posture (large) or gross body movement (small). */
private class Reorientation(
    val startSec: Double,
    val durSec: Double,
    val ux: Double,
    val uy: Double,
    val uz: Double,
    val deltaRad: Double,
    val isPosture: Boolean,
)

/** A planned leg movement, before rendering. */
private class Planned(
    val onsetSec: Double,
    val kind: TruthKind,
    val seriesId: Int?,
    val ankleOnly: Boolean,
    val durationSec: Double,
    val tRiseSec: Double,
    val radiusM: Double,
    val targetAmpG: Double,
)

private class Interval(val fromSec: Double, val toSec: Double) {
    val length: Double get() = toSec - fromSec
}

private class Generator(private val spec: NightSpec, private val seed: Long) {

    private val fs = spec.fsRealHz
    private val planHorizonSec = spec.durationH * 3600.0
    private val recordedSec = spec.recordedH * 3600.0
    private val n = (recordedSec * fs).toInt().coerceAtLeast(2)

    // Complete signal, in g. Gravity first, dynamic contributions next, noise last.
    private val ax = DoubleArray(n)
    private val ay = DoubleArray(n)
    private val az = DoubleArray(n)

    /** Orientation sampled at 1 Hz: `g0` reference for the rendering of the movements. */
    private val coarseOrientation = Array(3) { DoubleArray(ceil(recordedSec).toInt() + 2) }

    private val envWin = Math.round(0.50 * fs).toInt().coerceAtLeast(1)

    private val events = ArrayList<TruthEvent>()
    private val postureTimes = ArrayList<Long>()

    // Mechanical coupling: strap tightness, with a possible step mid-night (family 12).
    private val gainStepAtSec: Double =
        spec.distractors.gainStepAtFraction.coerceIn(0.0, 1.0) * planHorizonSec

    private fun coupling(tSec: Double): Double {
        val step = spec.distractors.gainStep
        val late = if (step != null && tSec >= gainStepAtSec) step else 1.0
        return spec.gainMultiplier * late
    }

    // --- Structure of the night -------------------------------------------------------------

    private lateinit var sleepIntervals: List<Interval>
    private var offBodyFrom = -1.0
    private var offBodyTo = -1.0

    fun run(): SynthNight {
        val root = SynthRandom(seed)

        buildSleepStructure(root)
        val reorientations = scheduleReorientations(root)
        buildGravityTrack(root, reorientations)
        val planned = schedulePlmMovements(root)
        renderPlmMovements(root, planned)
        renderGrossBodyMovements(root, reorientations)
        val resp = renderRespiratoryArtifact(root)
        renderRrlm(root, resp)
        renderMattress(root)
        renderAlma(root)
        blankOffBody()
        val floorG = addNoise(root)
        val gainCalG = renderRitualGain()

        val holes = scheduleHoles(root)
        val blocks = emitBlocks(holes)
        val truth = assembleTruth(holes, floorG, gainCalG)
        return SynthNight(blocks, truth, seed, spec)
    }

    // -----------------------------------------------------------------------------------------
    // 1. Wake / sleep structure
    // -----------------------------------------------------------------------------------------

    private fun buildSleepStructure(root: SynthRandom) {
        val rnd = root.stream("sleep")
        val sleepStart = spec.sleep.sleepLatencyMin * 60.0
        val sleepEnd = (planHorizonSec - spec.sleep.finalWakeMin * 60.0).coerceAtLeast(sleepStart + 60.0)

        // Intra-SPT awakenings, placed without overlap, sorted.
        val waso = ArrayList<Interval>()
        var attempts = 0
        while (waso.size < spec.sleep.wasoCount && attempts < 200) {
            attempts++
            val dur = rnd.uniform(spec.sleep.wasoMinMin, spec.sleep.wasoMaxMin) * 60.0
            val from = rnd.uniform(sleepStart + 600.0, sleepEnd - dur - 600.0)
            if (from <= sleepStart) continue
            val cand = Interval(from, from + dur)
            if (waso.any { cand.fromSec < it.toSec + 300.0 && it.fromSec < cand.toSec + 300.0 }) continue
            waso.add(cand)
        }
        waso.sortBy { it.fromSec }

        if (spec.distractors.offBody) {
            val dur = spec.distractors.offBodyMin * 60.0
            // Realistic: the watch ends up on the table when the subject gets up. It is therefore
            // laid at the start of an intra-SPT awakening if one is long enough, else at 60 % of
            // the night.
            val host = waso.firstOrNull { it.length >= 120.0 }
            offBodyFrom = host?.fromSec ?: (0.60 * planHorizonSec)
            offBodyTo = (offBodyFrom + dur).coerceAtMost(planHorizonSec)
        }

        // Sleep intervals = [sleepStart, sleepEnd] minus the awakenings and the off-body span.
        val cuts = ArrayList<Interval>()
        cuts.addAll(waso)
        if (offBodyFrom >= 0.0) cuts.add(Interval(offBodyFrom, offBodyTo))
        cuts.sortBy { it.fromSec }

        val out = ArrayList<Interval>()
        var cursor = sleepStart
        for (c in cuts) {
            if (c.fromSec > cursor) out.add(Interval(cursor, c.fromSec.coerceAtMost(sleepEnd)))
            cursor = maxOf(cursor, c.toSec)
        }
        if (cursor < sleepEnd) out.add(Interval(cursor, sleepEnd))
        sleepIntervals = out.filter { it.length > 1.0 }
    }

    private val sleepSeconds: Double get() = sleepIntervals.sumOf { it.length }

    /** Converts an abscissa in "cumulative sleep time" to the corresponding wall-clock instant. */
    private fun sleepTimeToWall(u: Double): Double {
        var rest = u
        for (iv in sleepIntervals) {
            if (rest <= iv.length) return iv.fromSec + rest
            rest -= iv.length
        }
        return sleepIntervals.lastOrNull()?.toSec ?: 0.0
    }

    private fun isAsleep(tSec: Double): Boolean = sleepIntervals.any { tSec >= it.fromSec && tSec < it.toSec }

    private fun isOffBody(tSec: Double): Boolean = offBodyFrom >= 0.0 && tSec >= offBodyFrom && tSec < offBodyTo

    // -----------------------------------------------------------------------------------------
    // 2. Reorientations: postures (family 1) and orientation drift of the GBMs (family 2)
    // -----------------------------------------------------------------------------------------

    private fun scheduleReorientations(root: SynthRandom): List<Reorientation> {
        val d = spec.distractors
        val out = ArrayList<Reorientation>()

        val pr = root.stream("posture")
        val postureCount = pr.nextIntRange(d.postureCountMin, d.postureCountMax)
        for (k in 0 until postureCount) {
            // Spread over equal slots with jitter: two turns never overlap, and the spacing stays
            // physiological (never two within 10 s).
            val slot = planHorizonSec / postureCount.coerceAtLeast(1)
            val start = k * slot + pr.uniform(0.05 * slot, 0.85 * slot)
            val dur = pr.uniform(d.postureDurMinSec, d.postureDurMaxSec)
            val delta = Math.toRadians(pr.uniform(d.postureDegMin, d.postureDegMax))
            val (ux, uy, uz) = randomUnitVector(pr)
            out.add(Reorientation(start, dur, ux, uy, uz, delta, isPosture = true))
        }

        val gr = root.stream("gbm-tilt")
        val gbmCount = gr.nextIntRange(d.grossBodyCountMin, d.grossBodyCountMax)
        for (k in 0 until gbmCount) {
            val slot = planHorizonSec / gbmCount.coerceAtLeast(1)
            val start = k * slot + gr.uniform(0.05 * slot, 0.85 * slot)
            val dur = gr.uniform(d.grossBodyDurMinSec, d.grossBodyDurMaxSec)
            // A GBM reorients little: beyond `postureDeg` (20 degrees) it would be a posture
            // change, and the §5.2 table distinguishes them expressly.
            val delta = Math.toRadians(gr.uniform(0.0, 14.0))
            val (ux, uy, uz) = randomUnitVector(gr)
            out.add(Reorientation(start, dur, ux, uy, uz, delta, isPosture = false))
        }

        out.sortBy { it.startSec }
        return out
    }

    private fun buildGravityTrack(root: SynthRandom, reorientations: List<Reorientation>) {
        val tilt0 = Math.toRadians(spec.initialTiltDeg)
        var gx = sin(tilt0)
        var gy = cos(tilt0)
        var gz = 0.0

        // Slow postural drift: two Ornstein-Uhlenbeck processes sampled at 2 Hz then linearly
        // interpolated. Content below 0.01 Hz: entirely in the gravity channel.
        val wr = root.stream("wander")
        val nodeStep = (fs / 2.0).toInt().coerceAtLeast(1)
        val nodeCount = n / nodeStep + 2
        val wa = DoubleArray(nodeCount)
        val wb = DoubleArray(nodeCount)
        val sd = Math.toRadians(spec.noise.wanderDeg)
        val dt = nodeStep / fs
        val rho = exp(-dt / spec.noise.wanderTauSec)
        val innov = sd * sqrt(1.0 - rho * rho)
        for (k in 1 until nodeCount) {
            wa[k] = rho * wa[k - 1] + innov * wr.nextGaussian()
            wb[k] = rho * wb[k - 1] + innov * wr.nextGaussian()
        }

        var idx = 0
        var coarseFilled = -1
        // Effective axis of each reorientation, computed when it starts: it is the component of the
        // drawn axis orthogonal to `g` at that instant. See [orthogonalToG].
        val axis = arrayOfNulls<DoubleArray>(reorientations.size)
        fun axisOf(k: Int, bx: Double, by: Double, bz: Double): DoubleArray {
            var a = axis[k]
            if (a == null) {
                val r = reorientations[k]
                a = orthogonalToG(r.ux, r.uy, r.uz, bx, by, bz)
                axis[k] = a
            }
            return a
        }
        for (i in 0 until n) {
            val t = i / fs
            while (idx < reorientations.size && t >= reorientations[idx].startSec + reorientations[idx].durSec) {
                val r = reorientations[idx]
                val a = axisOf(idx, gx, gy, gz)
                val v = rotate(gx, gy, gz, a[0], a[1], a[2], r.deltaRad)
                gx = v[0]; gy = v[1]; gz = v[2]
                if (r.isPosture) {
                    postureTimes.add(Math.round((r.startSec + r.durSec / 2.0) * 1000.0))
                }
                idx++
            }
            var vx = gx; var vy = gy; var vz = gz
            if (idx < reorientations.size && t >= reorientations[idx].startSec) {
                val r = reorientations[idx]
                val a = axisOf(idx, gx, gy, gz)
                val u = ((t - r.startSec) / r.durSec).coerceIn(0.0, 1.0)
                val v = rotate(vx, vy, vz, a[0], a[1], a[2], r.deltaRad * MinJerk.s(u))
                vx = v[0]; vy = v[1]; vz = v[2]
            }

            val node = i / nodeStep
            val frac = (i - node * nodeStep).toDouble() / nodeStep
            val angA = wa[node] + frac * (wa[node + 1] - wa[node])
            val angB = wb[node] + frac * (wb[node + 1] - wb[node])
            var w = rotate(vx, vy, vz, 0.0, 0.0, 1.0, angA)
            w = rotate(w[0], w[1], w[2], 1.0, 0.0, 0.0, angB)

            ax[i] = w[0]; ay[i] = w[1]; az[i] = w[2]
            val c = (i / fs).toInt()
            if (c > coarseFilled && c < coarseOrientation[0].size) {
                coarseOrientation[0][c] = w[0]
                coarseOrientation[1][c] = w[1]
                coarseOrientation[2][c] = w[2]
                coarseFilled = c
            }
        }
        // Tail of the coarse table: avoids a null vector beyond the last index filled.
        var c = coarseFilled + 1
        while (c < coarseOrientation[0].size) {
            coarseOrientation[0][c] = gx; coarseOrientation[1][c] = gy; coarseOrientation[2][c] = gz
            c++
        }
        postureTimes.sort()
    }

    private fun orientationAt(tSec: Double): DoubleArray {
        val c = tSec.toInt().coerceIn(0, coarseOrientation[0].size - 1)
        return doubleArrayOf(coarseOrientation[0][c], coarseOrientation[1][c], coarseOrientation[2][c])
    }

    // -----------------------------------------------------------------------------------------
    // 3. Leg movements: true series, isolated ones, non-periodic bursts
    // -----------------------------------------------------------------------------------------

    private fun schedulePlmMovements(root: SynthRandom): List<Planned> {
        val rnd = root.stream("movements")
        val out = ArrayList<Planned>()
        var seriesId = 0
        val totalSleep = sleepSeconds
        if (totalSleep <= 0.0) return out

        // --- True periodic series --------------------------------------------------------
        val totalSeries = spec.trueSeries.sumOf { it.nSeries }.coerceAtLeast(1)
        var slotIndex = 0
        for (pop in spec.trueSeries) {
            val sigma = sigmaLogOfCvPct(pop.imiCvPct)
            for (k in 0 until pop.nSeries) {
                val slot = totalSleep / totalSeries
                val approxLen = pop.imiMeanSec * (pop.clmPerSeries - 1) + 12.0
                val room = (slot - approxLen).coerceAtLeast(0.0)
                var u = slotIndex * slot + rnd.uniform(0.0, room)
                slotIndex++
                seriesId++
                for (j in 0 until pop.clmPerSeries) {
                    val wall = sleepTimeToWall(u)
                    if (!isAsleep(wall)) break
                    out.add(plan(rnd, wall, TruthKind.PLM_IN_SERIES, seriesId))
                    u += rnd.logNormal(pop.imiMeanSec, sigma, 2.0, 120.0)
                }
            }
        }

        // --- Isolated movements: exponential arrivals over sleep time ---------------------
        if (spec.isolatedClmPerHour > 0.0) {
            val rate = spec.isolatedClmPerHour / 3600.0
            var u = 0.0
            while (true) {
                u += -ln(rnd.nextDoublePositive()) / rate
                if (u >= totalSleep) break
                val wall = sleepTimeToWall(u)
                out.add(plan(rnd, wall, TruthKind.ISOLATED, null))
            }
        }

        // --- Non-periodic bursts (family 10) ---------------------------------------------
        val cr = root.stream("clusters")
        val d = spec.distractors
        for (k in 0 until d.clusterCount) {
            val size = cr.nextIntRange(d.clusterSizeMin, d.clusterSizeMax)
            var u = cr.uniform(0.0, (totalSleep - 60.0).coerceAtLeast(1.0))
            for (j in 0 until size) {
                val wall = sleepTimeToWall(u)
                if (!isAsleep(wall)) break
                out.add(plan(cr, wall, TruthKind.ISOLATED, null))
                u += cr.uniform(d.clusterImiMinSec, d.clusterImiMaxSec)
            }
        }

        out.sortBy { it.onsetSec }
        return out.filter { !isOffBody(it.onsetSec) && it.onsetSec + it.durationSec < recordedSec }
    }

    private fun plan(rnd: SynthRandom, onsetSec: Double, kind: TruthKind, seriesId: Int?): Planned {
        val dspec = spec.duration
        val total = rnd.logNormalFromMeanSd(dspec.meanSec, dspec.sdSec, dspec.minSec, dspec.maxSec)
        val tRise = rnd.uniform(dspec.tRiseMinSec, dspec.tRiseMaxSec).coerceAtMost(total / 2.2)
        val radius = rnd.uniform(dspec.radiusMinM, dspec.radiusMaxM)
        val amp = spec.amplitude.fixedG
            ?: rnd.logNormal(
                spec.amplitude.medianG, spec.amplitude.sigmaLog,
                spec.amplitude.minG, spec.amplitude.maxG,
            )
        val ankleOnly = rnd.nextBoolean(spec.ankleOnlyFraction)
        return Planned(onsetSec, kind, seriesId, ankleOnly, total, tRise, radius, amp)
    }

    private fun renderPlmMovements(root: SynthRandom, planned: List<Planned>) {
        for (p in planned) renderOne(p)
    }

    private fun renderOne(p: Planned) {
        val onsetIdx = Math.round(p.onsetSec * fs).toInt()
        if (onsetIdx < 0 || onsetIdx >= n) return
        val len = (Math.round(p.durationSec * fs).toInt() + 1).coerceAtMost(n - onsetIdx)
        if (len < 2) return

        val g0 = orientationAt(p.onsetSec)
        val tHold = (p.durationSec - 2.0 * p.tRiseSec).coerceAtLeast(0.0)
        // Active hold (§5.1): the flexion is sustained by successive re-activations, at the
        // movement's own ballistic rate (one per `2 . tRise`). Without them, a 4.2 s movement would
        // show ~2.3 s of accelerometric silence in its middle and the detector would emit two of
        // them — which Sforza's PAM-RL, with its 1 s drop-out, would have done as well.
        val holdRatio = spec.duration.holdActivityRatio
        val holdCycles = if (tHold > 0.0 && holdRatio > 0.0) {
            Math.round(tHold / (2.0 * p.tRiseSec)).toInt().coerceAtLeast(1)
        } else {
            0
        }
        val theta = calibrateThetaMax(
            targetG = p.targetAmpG,
            tRiseSec = p.tRiseSec, tHoldSec = tHold, tFallSec = p.tRiseSec,
            radiusM = p.radiusM,
            g0x = g0[0], g0y = g0[1], g0z = g0[2],
            fs = fs, n = len, scale = spec.amplitude.scale, envWin = envWin,
            holdCycles = holdCycles, holdDepthRatio = holdRatio,
        )

        val k = coupling(p.onsetSec)
        // Pure ankle rotation: the case is ABOVE the talocrural axis, it barely moves at all
        // (`r_eff ~ 0`) and does not rotate one bit. This is the physical mechanism behind
        // Terrill's miss rate, and it must be represented explicitly — failing which the ground
        // truth overestimates what the sensor can see.
        val radius = if (p.ankleOnly) 0.02 else p.radiusM
        val tiltCoupling = if (p.ankleOnly) 0.0 else k

        val kin = MovementKinematics(
            theta, p.tRiseSec, tHold, p.tRiseSec, holdCycles, holdRatio * theta,
        )
        val r = renderMovementTilted(kin, radius, k, tiltCoupling, g0[0], g0[1], g0[2], fs, len)
        addRender(r, onsetIdx)

        events.add(
            TruthEvent(
                onsetMsRel = msAt(onsetIdx),
                durationMs = Math.round(p.durationSec * 1000.0).toInt(),
                peakG = r.peakG.toFloat(),
                envPeakG = coarseEnvelopePeak(r, envWin, fs).toFloat(),
                thetaMaxDeg = Math.toDegrees(theta).toFloat(),
                tRiseSec = p.tRiseSec.toFloat(),
                radiusM = radius.toFloat(),
                ankleOnly = p.ankleOnly,
                kind = p.kind,
                seriesId = p.seriesId,
            ),
        )
    }

    // -----------------------------------------------------------------------------------------
    // 4. Distractors
    // -----------------------------------------------------------------------------------------

    /** Family 2: gross body movements, 2-20 s, 300-2500 mg. */
    private fun renderGrossBodyMovements(root: SynthRandom, reorientations: List<Reorientation>) {
        val d = spec.distractors
        val rnd = root.stream("gbm")
        for (r in reorientations) {
            if (r.isPosture) continue
            val amp = rnd.logUniform(d.grossBodyAmpMinG, d.grossBodyAmpMaxG)
            val from = Math.round(r.startSec * fs).toInt()
            val len = Math.round(r.durSec * fs).toInt()
            if (from < 0 || from >= n || len < 2) continue
            // Sum of five 0.5-6 Hz sinusoids with independent phases: a turn is not a pulse, it is
            // broadband agitation below 6 Hz. Raised-cosine envelope so as not to manufacture an
            // artificial step at the bounds.
            val freqs = DoubleArray(5) { rnd.uniform(0.5, 6.0) }
            val phases = DoubleArray(5) { rnd.uniform(0.0, 2.0 * Math.PI) }
            val (ux, uy, uz) = randomUnitVector(rnd)
            var peak = 0.0
            val buf = DoubleArray(len)
            for (i in 0 until len) {
                val t = i / fs
                var s = 0.0
                for (h in 0 until 5) s += sin(2.0 * Math.PI * freqs[h] * t + phases[h])
                s /= 5.0
                val env = 0.5 * (1.0 - cos(2.0 * Math.PI * i / len))
                buf[i] = s * env
                if (abs(buf[i]) > peak) peak = abs(buf[i])
            }
            if (peak <= 0.0) continue
            val k = coupling(r.startSec) * amp / peak
            for (i in 0 until len) {
                val idx = from + i
                if (idx >= n) break
                val v = buf[i] * k
                ax[idx] += v * ux; ay[idx] += v * uy; az[idx] += v * uz
            }
            events.add(
                TruthEvent(
                    onsetMsRel = msAt(from),
                    durationMs = Math.round(r.durSec * 1000.0).toInt(),
                    peakG = (amp * coupling(r.startSec)).toFloat(),
                    envPeakG = (0.7 * amp * coupling(r.startSec)).toFloat(),
                    thetaMaxDeg = Math.toDegrees(r.deltaRad).toFloat(),
                    tRiseSec = r.durSec.toFloat(),
                    radiusM = 0.22f,
                    ankleOnly = false,
                    kind = TruthKind.GROSS_BODY,
                    seriesId = null,
                ),
            )
        }
    }

    /** Family 3: respiratory artefact. Returns (frequency, phase) for the coupling of the RRLMs. */
    private fun renderRespiratoryArtifact(root: SynthRandom): DoubleArray {
        val d = spec.distractors
        val rnd = root.stream("resp")
        val f = rnd.uniform(d.respHzMin, d.respHzMax)
        val phase = rnd.uniform(0.0, 2.0 * Math.PI)
        if (!d.respiratory) return doubleArrayOf(f, phase)
        val amp = rnd.uniform(d.respAmpMinG, d.respAmpMaxG)
        val amPeriod = rnd.uniform(d.respAmPeriodMinSec, d.respAmPeriodMaxSec)
        val amPhase = rnd.uniform(0.0, 2.0 * Math.PI)
        val (ux, uy, uz) = randomUnitVector(rnd)
        for (i in 0 until n) {
            val t = i / fs
            val am = 0.6 + 0.4 * sin(2.0 * Math.PI * t / amPeriod + amPhase)
            val v = coupling(t) * amp * am * sin(2.0 * Math.PI * f * t + phase)
            ax[i] += v * ux; ay[i] += v * uy; az[i] += v * uz
        }
        return doubleArrayOf(f, phase)
    }

    /** Family 11: RRLM — series with a 25-45 s IMI **phase-locked** to respiration. */
    private fun renderRrlm(root: SynthRandom, resp: DoubleArray) {
        val d = spec.distractors
        if (d.rrlmSeriesCount <= 0) return
        val rnd = root.stream("rrlm")
        val respPeriod = 1.0 / resp[0]
        val totalSleep = sleepSeconds
        if (totalSleep <= 0.0) return
        for (s in 0 until d.rrlmSeriesCount) {
            // The IMI is an integer multiple of the respiratory cycle: this is what makes the RRLMs
            // periodic, and therefore indistinguishable from PLMS by periodicity alone (§3.5).
            val target = rnd.uniform(d.rrlmImiMinSec, d.rrlmImiMaxSec)
            val cycles = Math.round(target / respPeriod).toInt().coerceAtLeast(1)
            val imi = cycles * respPeriod
            var u = rnd.uniform(0.0, (totalSleep - imi * d.rrlmPerSeries).coerceAtLeast(1.0))
            for (j in 0 until d.rrlmPerSeries) {
                val wall = sleepTimeToWall(u)
                if (!isAsleep(wall) || isOffBody(wall)) break
                renderOne(plan(rnd, wall, TruthKind.RRLM, null))
                u += imi
            }
        }
    }

    /**
     * Family 4: mattress vibration. Short transients, damped 8-20 Hz ringing, **tilt unchanged** —
     * it is that last point which makes them indistinguishable from a CLM on the envelope alone and
     * which justifies the WASM 3.2.1-d morphology criterion.
     */
    private fun renderMattress(root: SynthRandom) {
        val d = spec.distractors
        val rnd = root.stream("mattress")
        val count = rnd.nextIntRange(d.mattressCountMin, d.mattressCountMax)
        for (k in 0 until count) {
            val at = rnd.uniform(0.0, recordedSec)
            if (isOffBody(at)) continue
            val dur = rnd.uniform(d.mattressDurMinSec, d.mattressDurMaxSec)
            val amp = rnd.logUniform(d.mattressAmpMinG, d.mattressAmpMaxG)
            val ring = rnd.uniform(d.mattressRingHzMin, d.mattressRingHzMax)
            val from = Math.round(at * fs).toInt()
            val len = Math.round(dur * fs).toInt()
            if (from < 0 || from + len >= n || len < 2) continue
            val (ux, uy, uz) = randomUnitVector(rnd)
            val decay = 4.0 / dur // the amplitude falls to 1.8 % after `dur`
            val k2 = coupling(at) * amp
            for (i in 0 until len) {
                val t = i / fs
                val v = k2 * exp(-decay * t) * sin(2.0 * Math.PI * ring * t)
                val idx = from + i
                ax[idx] += v * ux; ay[idx] += v * uy; az[idx] += v * uz
            }
            events.add(
                TruthEvent(
                    onsetMsRel = msAt(from),
                    durationMs = Math.round(dur * 1000.0).toInt(),
                    peakG = (k2).toFloat(),
                    envPeakG = (k2 * 0.35).toFloat(),
                    thetaMaxDeg = 0f,
                    tRiseSec = (1.0 / ring).toFloat(),
                    radiusM = 0f,
                    ankleOnly = false,
                    kind = TruthKind.MATTRESS,
                    seriesId = null,
                ),
            )
        }
    }

    /**
     * Family 9: hypnagogic foot tremor / ALMA. A 10-15 s burst at 0.3-4 Hz.
     *
     * Rendered as what it physiologically is — a **train** of small successive activations — and not
     * as a sinusoidal carrier: that is what makes it troublesome, because the coarse 0.5 s envelope
     * can fall back between two slow activations and fragment the burst into events of admissible
     * duration.
     */
    private fun renderAlma(root: SynthRandom) {
        val d = spec.distractors
        val rnd = root.stream("alma")
        val count = rnd.nextIntRange(d.almaCountMin, d.almaCountMax)
        for (k in 0 until count) {
            val at = rnd.uniform(0.0, (recordedSec - 30.0).coerceAtLeast(1.0))
            if (isOffBody(at)) continue
            val dur = rnd.uniform(d.almaDurMinSec, d.almaDurMaxSec)
            val f = rnd.uniform(d.almaHzMin, d.almaHzMax)
            val amp = rnd.logUniform(d.almaAmpMinG, d.almaAmpMaxG)
            val period = 1.0 / f
            val g0 = orientationAt(at)
            val tRise = (period / 3.0).coerceIn(0.08, 0.30)
            var t = at
            var peak = 0.0
            while (t < at + dur) {
                val len = Math.round(2.2 * tRise * fs).toInt() + 1
                val from = Math.round(t * fs).toInt()
                if (from + len >= n) break
                val theta = calibrateThetaMax(
                    targetG = amp, tRiseSec = tRise, tHoldSec = 0.0, tFallSec = tRise,
                    radiusM = 0.20, g0x = g0[0], g0y = g0[1], g0z = g0[2],
                    fs = fs, n = len, scale = AmplitudeScale.PEAK, envWin = envWin,
                )
                val kin = MovementKinematics(theta, tRise, 0.0, tRise)
                val kc = coupling(t)
                val r = renderMovementTilted(kin, 0.20, kc, kc, g0[0], g0[1], g0[2], fs, len)
                addRender(r, from)
                if (r.peakG > peak) peak = r.peakG
                t += period
            }
            events.add(
                TruthEvent(
                    onsetMsRel = msAt(Math.round(at * fs).toInt()),
                    durationMs = Math.round(dur * 1000.0).toInt(),
                    peakG = peak.toFloat(),
                    envPeakG = peak.toFloat() * 0.5f,
                    thetaMaxDeg = 0f,
                    tRiseSec = tRise.toFloat(),
                    radiusM = 0.20f,
                    ankleOnly = false,
                    kind = TruthKind.ALMA,
                    seriesId = null,
                ),
            )
        }
    }

    /** Family 8: off-body. Watch on the table — constant gravity, nothing else at all. */
    private fun blankOffBody() {
        if (offBodyFrom < 0.0) return
        val from = Math.round(offBodyFrom * fs).toInt().coerceIn(0, n)
        val to = Math.round(offBodyTo * fs).toInt().coerceIn(0, n)
        for (i in from until to) {
            ax[i] = 0.0; ay[i] = 0.0; az[i] = 1.0
        }
    }

    /** Family 5: MEMS noise and quantisation. Returns the expected envelope floor. */
    private fun addNoise(root: SynthRandom): Double {
        val rnd = root.stream("noise")
        val density = rnd.uniform(spec.noise.densityMinG, spec.noise.densityMaxG)
        val sigma = density * sqrt(fs / 2.0)
        val lsb = spec.noise.lsbG
        for (i in 0 until n) {
            ax[i] += sigma * rnd.nextGaussian()
            ay[i] += sigma * rnd.nextGaussian()
            az[i] += sigma * rnd.nextGaussian()
        }
        if (lsb > 0.0) {
            for (i in 0 until n) {
                ax[i] = Math.round(ax[i] / lsb) * lsb
                ay[i] = Math.round(ay[i] / lsb) * lsb
                az[i] = Math.round(az[i] / lsb) * lsb
            }
        }
        // RMS envelope of the norm of a tri-axial white noise: sqrt(3 x variance per axis).
        // Analytical estimate, not a measurement: it only serves to normalise a ratio axis.
        val q = if (lsb > 0.0) lsb * lsb / 12.0 else 0.0
        return sqrt(3.0 * (sigma * sigma + q))
    }

    /**
     * Reference calibration gain: the coarse envelope peak of a comfortable voluntary dorsiflexion,
     * coupling of the night included.
     *
     * ### Known gap between this generator and production, to read before interpreting a threshold
     *
     * This render simulates the **guided ritual** — an imposed gesture, of chosen amplitude,
     * performed at bedtime. That ritual was removed from production on 2026-08-05 (see
     * `Calibration.kt`): it had never been wired up. Production therefore derives its `gainCal`
     * from the **body turns**, which are an undergone gesture, more variable and measured in the
     * middle of the night rather than at its start.
     *
     * Conclusion to keep in mind: the whole non-regression suite runs on a calibration gain
     * **cleaner than the one the application actually obtains**. This is not harmless — it is the
     * same shape of defect as the one that had made the series indexing diverge, where the harness
     * measured a wiring that production did not have.
     *
     * It is not corrected here, and deliberately so: rebasing the generator on the turns would move
     * the reference values of T1 to T22 by an amount nobody has measured. The right sequence is to
     * measure the gap first, then decide — not to move twenty thresholds to make an awkward
     * comment go away.
     */
    private fun renderRitualGain(): Float {
        val r = spec.ritual
        val len = Math.round((2.0 * r.tRiseSec + r.holdSec) * fs).toInt() + 1
        val kin = MovementKinematics(Math.toRadians(r.thetaMaxDeg), r.tRiseSec, r.holdSec, r.tRiseSec)
        val tilt0 = Math.toRadians(spec.initialTiltDeg)
        // The ritual is done at bedtime: the coupling is that of the start of the night.
        val k = coupling(0.0)
        val render = renderMovementTilted(kin, r.radiusM, k, k, sin(tilt0), cos(tilt0), 0.0, fs, len)
        return coarseEnvelopePeak(render, envWin, fs).toFloat()
    }

    // -----------------------------------------------------------------------------------------
    // 5. FIFO gaps, blocks, clock
    // -----------------------------------------------------------------------------------------

    /** Family 6: FIFO gaps. Returns the intervals of missing samples, sorted and disjoint. */
    private fun scheduleHoles(root: SynthRandom): List<IntArray> {
        val d = spec.distractors
        val rnd = root.stream("gaps")
        val raw = ArrayList<IntArray>()
        val count = rnd.nextIntRange(d.gapCountMin, d.gapCountMax)
        for (k in 0 until count) {
            val dur = rnd.uniform(d.gapMinSec, d.gapMaxSec)
            val at = rnd.uniform(60.0, (recordedSec - dur - 60.0).coerceAtLeast(61.0))
            val from = Math.round(at * fs).toInt()
            val to = from + Math.round(dur * fs).toInt()
            if (from > 0 && to < n) raw.add(intArrayOf(from, to))
        }
        if (d.longGap) {
            val dur = rnd.uniform(d.longGapMinSec, d.longGapMaxSec)
            val at = rnd.uniform(0.25 * recordedSec, 0.75 * recordedSec)
            val from = Math.round(at * fs).toInt()
            val to = (from + Math.round(dur * fs).toInt()).coerceAtMost(n - 1)
            if (from > 0 && to > from) raw.add(intArrayOf(from, to))
        }
        raw.sortBy { it[0] }
        val merged = ArrayList<IntArray>()
        for (h in raw) {
            val last = merged.lastOrNull()
            if (last != null && h[0] <= last[1]) last[1] = maxOf(last[1], h[1]) else merged.add(h)
        }
        return merged
    }

    /**
     * Sensor clock. `fs` drifts linearly from `fsRealHz` to `fsRealHz x (1 + fsDriftPct/100)` over
     * the recorded duration; the cumulative time is therefore quadratic in `k`.
     */
    private fun tNs(k: Int): Long {
        val drift = spec.fsDriftPct / 100.0
        val u = k.toDouble()
        val sec = if (drift == 0.0) u / fs else (u / fs) * (1.0 - 0.5 * drift * u / n)
        return spec.startNs + Math.round(sec * 1e9)
    }

    private fun msAt(k: Int): Long = Math.round((tNs(k) - spec.startNs) / 1e6)

    private fun emitBlocks(holes: List<IntArray>): List<SampleBlock> {
        // Sample ranges actually transmitted, the complement of the gaps.
        val runs = ArrayList<IntArray>()
        var cursor = 0
        for (h in holes) {
            val hf = h[0].coerceIn(0, n)
            val ht = h[1].coerceIn(0, n)
            if (hf > cursor) runs.add(intArrayOf(cursor, hf))
            cursor = maxOf(cursor, ht)
        }
        if (cursor < n) runs.add(intArrayOf(cursor, n))

        val out = ArrayList<SampleBlock>()
        val nominalStepNs = 1e9 / TARGET_FS_HZ
        var prevLastNs = Long.MIN_VALUE
        for (run in runs) {
            var s = run[0]
            while (s < run[1]) {
                var len = minOf(spec.blockSamples, run[1] - s)
                // A single-sample block carries no rate: it is attached to the preceding one rather
                // than producing a block that step −1 would not know how to judge.
                if (run[1] - s - len == 1) len++
                if (len < 2) break
                val bx = FloatArray(len); val by = FloatArray(len); val bz = FloatArray(len)
                for (i in 0 until len) {
                    bx[i] = ax[s + i].toFloat(); by[i] = ay[s + i].toFloat(); bz[i] = az[s + i].toFloat()
                }
                val tFirst = tNs(s)
                val tLast = tNs(s + len - 1)
                var flags = 0
                if (prevLastNs != Long.MIN_VALUE && (tFirst - prevLastNs) > 1.5 * nominalStepNs) {
                    flags = flags or BlockFlags.GAP_BEFORE
                }
                if (spec.distractors.offBodyHardwareFlag && offBodyFrom >= 0.0) {
                    val t0 = (tFirst - spec.startNs) / 1e9
                    if (t0 >= offBodyFrom && t0 < offBodyTo) flags = flags or BlockFlags.OFF_BODY
                }
                out.add(SimpleBlock(tFirst, tLast, flags, bx, by, bz))
                prevLastNs = tLast
                s += len
            }
        }
        return out
    }

    // -----------------------------------------------------------------------------------------
    // 6. Ground truth
    // -----------------------------------------------------------------------------------------

    private fun truthMask(holes: List<IntArray>): SleepMask {
        val windows = ArrayList<SleepWindow>()
        val endMs = Math.round(recordedSec * 1000.0)
        var cursor = 0L
        for (iv in sleepIntervals) {
            val from = Math.round(iv.fromSec * 1000.0).coerceIn(0L, endMs)
            val to = Math.round(iv.toSec * 1000.0).coerceIn(0L, endMs)
            if (to <= from) continue
            if (from > cursor) windows.add(SleepWindow(cursor, from, stageOf(cursor, from)))
            windows.add(SleepWindow(from, to, Stage.SLEEP))
            cursor = to
        }
        if (cursor < endMs) windows.add(SleepWindow(cursor, endMs, stageOf(cursor, endMs)))

        val tstMin = windows.filter { it.stage == Stage.SLEEP }.sumOf { it.durationMin }
        val sptMin = windows.filter { it.stage != Stage.OUT_OF_BED }.sumOf { it.durationMin }
        val wasoMin = (sptMin - tstMin).coerceAtLeast(0.0)

        // True analysable time: the sleep minus the gaps, widened by the settling time of the
        // filters (blind zones of step 0). It is the same subtraction the mask layer will make;
        // doing it here keeps `expectedPlmi*` on the same denominator.
        var lostMin = 0.0
        for (h in holes) {
            val fromMs = msAt(h[0]) - 2000
            val toMs = msAt(h[1].coerceAtMost(n - 1)) + 2000
            lostMin += sleepOverlapMs(windows, fromMs, toMs) / 60_000.0
        }
        val analysableTst = (tstMin - lostMin).coerceAtLeast(0.0)
        val analysableSpt = (sptMin - lostMin).coerceAtLeast(0.0)

        return SleepMask(
            windows = windows,
            source = MaskSource.DIARY,
            sptMin = sptMin,
            tstMin = tstMin,
            wasoMin = wasoMin,
            analysableTstMin = analysableTst,
            analysableSptMin = analysableSpt,
            corrected = false,
            lagAppliedMs = 0L,
            independence = DenominatorIndependence.INDEPENDENT_DIARY,
            fixedPointConverged = true,
        )
    }

    private fun stageOf(fromMs: Long, toMs: Long): Stage {
        if (offBodyFrom < 0.0) return Stage.AWAKE_IN_BED
        val mid = (fromMs + toMs) / 2.0 / 1000.0
        return if (mid >= offBodyFrom && mid < offBodyTo) Stage.OUT_OF_BED else Stage.AWAKE_IN_BED
    }

    private fun sleepOverlapMs(windows: List<SleepWindow>, fromMs: Long, toMs: Long): Long {
        var acc = 0L
        for (w in windows) {
            if (w.stage != Stage.SLEEP) continue
            val a = maxOf(w.startMsRel, fromMs)
            val b = minOf(w.endMsRel, toMs)
            if (b > a) acc += b - a
        }
        return acc
    }

    private fun assembleTruth(holes: List<IntArray>, floorG: Double, gainCalG: Float): GroundTruth {
        // An event planned during the off-body span was not rendered: the watch was on the table.
        // It can therefore appear in neither of the two scales.
        val emg = events
            .filter { !isOffBody(it.onsetMsRel / 1000.0) }
            .sortedBy { it.onsetMsRel }
        // `accelTruth` = `emgTruth` minus the pure ankle rotations (Terrill) and whatever falls
        // below the physical visibility threshold. It is the ONLY reference for the scores.
        val accel = emg.filter { !it.ankleOnly && it.peakG >= spec.visibilityG }
        val mask = truthMask(holes)

        val gaps = holes.map { h ->
            val fromIdx = Math.round(msAt(h[0]) * TARGET_FS_HZ / 1000.0).toInt()
            val toIdx = Math.round(msAt(h[1].coerceAtMost(n - 1)) * TARGET_FS_HZ / 1000.0).toInt()
            val durSec = (toIdx - fromIdx) / TARGET_FS_HZ
            val kind = when {
                durSec <= 0.10 -> GapKind.MICRO
                durSec <= 2.0 -> GapKind.BLIND
                else -> GapKind.SEGMENT_BREAK
            }
            Gap(fromIdx, toIdx, kind, durSec)
        }

        // The expected indices are obtained by making the ground truth cross the SAME steps 6 and 7
        // as the detection. Any remaining difference is therefore imputable to the detector, which
        // is exactly what T6 claims to measure.
        val truthClms = truthAsClms(accel.filter { it.isLegMovement }, floorG.toFloat())
        val pi = Periodicity.ferriIndex(truthClms, mask, TARGET_FS_HZ)
        val rhythm = Rhythm.fromClms(truthClms, mask)
        val aasm = indexOf(truthClms, mask, SeriesConfig.aasmV3(), SeriesRule.AASM_V3, pi, rhythm)
        val wasm = indexOf(truthClms, mask, SeriesConfig.wasm2016(), SeriesRule.WASM_2016, pi, rhythm)

        return GroundTruth(
            emgTruth = emg,
            accelTruth = accel,
            postures = postureTimes.filter { it < Math.round(recordedSec * 1000.0) },
            gaps = gaps,
            mask = mask,
            expectedPlmiAasm = aasm.plmi,
            expectedPlmiWasm = wasm.plmi,
            expectedPlmw = aasm.plmw,
            expectedPi = pi.periodicityIndex,
            expectedTstMin = mask.tstMin,
            expectedSptMin = mask.sptMin,
            floorG = floorG,
            gainCalG = gainCalG,
            gainMultiplierApplied = spec.gainMultiplier.toFloat(),
            fsRealHz = fs,
            truncatedAtMs = spec.truncateAtH?.let { Math.round(it * 3600.0 * 1000.0) },
        )
    }

    private fun indexOf(
        clms: List<Clm>,
        mask: SleepMask,
        cfg: SeriesConfig,
        rule: SeriesRule,
        pi: PiResult,
        rhythm: RhythmResult,
    ): PlmiResult {
        val built = SeriesBuilder.buildDetailed(clms, mask, TARGET_FS_HZ, cfg)
        return Plmi.compute(
            clms = clms,
            series = built.series,
            mask = mask,
            fsHz = TARGET_FS_HZ,
            rule = rule,
            pi = pi,
            rhythm = rhythm,
            floorMode = FloorMode.BILATERAL,
            truncated = spec.truncateAtH != null,
            truncatedSeriesDropped = built.truncatedSeriesDropped,
            paramsHash = "truth",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------------------------

    private fun addRender(r: MovementRender, from: Int) {
        for (i in 0 until r.n) {
            val idx = from + i
            if (idx >= n) break
            ax[idx] += r.dx[i]; ay[idx] += r.dy[i]; az[idx] += r.dz[i]
        }
    }

    private fun randomUnitVector(rnd: SynthRandom): Triple<Double, Double, Double> {
        // Marsaglia: uniform distribution on the sphere, without bias at the poles.
        val z = rnd.uniform(-1.0, 1.0)
        val phi = rnd.uniform(0.0, 2.0 * Math.PI)
        val r = sqrt((1.0 - z * z).coerceAtLeast(0.0))
        return Triple(r * cos(phi), r * sin(phi), z)
    }
}

/**
 * Component of the axis `u` orthogonal to `g`, renormalised.
 *
 * A rotation of angle `delta` about an arbitrary axis only turns `g` by
 * `2.asin(sin(delta/2).sin(alpha))`, where `alpha` is the angle between the axis and `g` — hence
 * **strictly less than `delta`**, and exactly zero when the axis is collinear with `g`. Yet the
 * §5.2 table specifies family 1 as a "**rotation of ĝ** of 20-120 degrees": what is drawn is the
 * observable reorientation, not the angle of a rotation about an arbitrary axis. Drawing an axis
 * uniform on the sphere manufactured posture changes recorded in the ground truth but physically
 * invisible (measured: 6.7 to 16.1 degrees of real rotation of ĝ for a `delta` drawn in
 * [20 ; 120]), which the detector could not find — a recall ceiling of the order of 0.89, below
 * the 0.95 threshold of T4, for a reason that is not its own.
 *
 * By taking the axis perpendicular to `g`, `delta` is **exactly** the rotation of ĝ. The azimuth of
 * the axis in the orthogonal plane is still drawn at random: it is what carries the variety of the
 * turns, and it has no effect on the magnitude of the reorientation.
 */
private fun orthogonalToG(
    ux: Double, uy: Double, uz: Double,
    gx: Double, gy: Double, gz: Double,
): DoubleArray {
    val gg = gx * gx + gy * gy + gz * gz
    if (gg <= 0.0) return doubleArrayOf(ux, uy, uz)
    val k = (ux * gx + uy * gy + uz * gz) / gg
    var px = ux - k * gx
    var py = uy - k * gy
    var pz = uz - k * gz
    var norm = sqrt(px * px + py * py + pz * pz)
    if (norm < 1e-6) {
        // Drawn axis nearly collinear with `g`: a deterministic perpendicular is taken, obtained by
        // crossing `g` with the basis axis least aligned with it.
        val ax = abs(gx); val ay = abs(gy); val az = abs(gz)
        val bx: Double; val by: Double; val bz: Double
        if (ax <= ay && ax <= az) { bx = 1.0; by = 0.0; bz = 0.0 }
        else if (ay <= az) { bx = 0.0; by = 1.0; bz = 0.0 }
        else { bx = 0.0; by = 0.0; bz = 1.0 }
        px = gy * bz - gz * by
        py = gz * bx - gx * bz
        pz = gx * by - gy * bx
        norm = sqrt(px * px + py * py + pz * pz)
        if (norm <= 0.0) return doubleArrayOf(ux, uy, uz)
    }
    return doubleArrayOf(px / norm, py / norm, pz / norm)
}

/** Rodrigues rotation of a vector about a unit axis. */
private fun rotate(
    vx: Double, vy: Double, vz: Double,
    ux: Double, uy: Double, uz: Double,
    ang: Double,
): DoubleArray {
    if (ang == 0.0) return doubleArrayOf(vx, vy, vz)
    val c = cos(ang)
    val s = sin(ang)
    val dot = ux * vx + uy * vy + uz * vz
    val cx = uy * vz - uz * vy
    val cy = uz * vx - ux * vz
    val cz = ux * vy - uy * vx
    return doubleArrayOf(
        vx * c + cx * s + ux * dot * (1.0 - c),
        vy * c + cy * s + uy * dot * (1.0 - c),
        vz * c + cz * s + uz * dot * (1.0 - c),
    )
}

/**
 * Variant of [renderMovement] with two distinct couplings: `inertialCoupling` for the tangential
 * and centripetal terms, `tiltCoupling` for the rotation of the gravity vector.
 *
 * The two differ in one case only, but a central one: the **pure ankle rotation**, where the case
 * does not rotate at all (`tiltCoupling = 0`) while it still undergoes an inertial residue at a
 * very short lever arm.
 */
internal fun renderMovementTilted(
    kin: MovementKinematics,
    radiusM: Double,
    inertialCoupling: Double,
    tiltCoupling: Double,
    g0x: Double,
    g0y: Double,
    g0z: Double,
    fs: Double,
    n: Int,
): MovementRender {
    val out = MovementRender(n)
    val rEff = radiusM * inertialCoupling
    for (i in 0 until n) {
        val t = i / fs
        val th = kin.theta(t) * tiltCoupling
        val thd = kin.thetaDot(t) * inertialCoupling
        val thdd = kin.thetaDDot(t) * inertialCoupling
        val aTan = rEff * thdd / G_MS2
        val aCen = rEff * thd * thd / G_MS2
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
