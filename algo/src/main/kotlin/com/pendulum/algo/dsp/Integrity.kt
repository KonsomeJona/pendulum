package com.pendulum.algo.dsp

import com.pendulum.algo.model.IntegrityConfig
import com.pendulum.algo.model.IntegrityReport
import com.pendulum.algo.model.IntegrityViolation
import com.pendulum.algo.model.SampleBlock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Block format constants **copied** here on purpose.
 *
 * `:algo` does not depend on `:format` (§4): that is what lets it revalidate the timestamps
 * without inheriting the — absent — guarantees of the block CRC, and stay testable without the
 * codec. The price to pay is this duplication of three integers. It must stay in sync with
 * `com.pendulum.format.ChunkFormat`; a consistency test lives on the `:phone` side, where the two
 * modules meet.
 */
object BlockFlags {
    const val FIFO_BOUNDARY = 1 shl 0
    const val GAP_BEFORE = 1 shl 1
    const val OFF_BODY = 1 shl 2

    /** Maximum number of samples per block in the format (`ChunkFormat.MAX_SAMPLES_PER_BLOCK`). */
    const val MAX_SAMPLES_PER_BLOCK = 512

    /** 1 LSB = 1/2048 g: an i16 saturates at 32767 LSB, i.e. 15.9995 g. */
    const val LSB_PER_G = 2048.0

    /** Codec saturation amplitude, in g. A sample at this value is suspect. */
    const val SATURATION_G = 32767.0f / LSB_PER_G.toFloat()
}

/**
 * Step −1 — integrity checks. **The algo trusts nothing.**
 *
 * Rationale, literally (§2, step −1): the format's CRC16 covers **only the payload**. `count`,
 * `tFirstNs`, `tLastNs` and `flags` in the block header are not protected. A corrupted time base
 * therefore crosses the decoder in silence — and the whole v2 chain rests on the timestamps (`fs`
 * estimation, resampling, CLM durations, IMI). A single block with a corrupted timestamp shifts
 * the whole downstream timeline by several seconds, which makes every event at a class boundary
 * switch class.
 *
 * All the checks are O(n) and allocation-free per sample.
 *
 * Two checks from the table of §2 are **not** implementable here and never will be in this module:
 *  - no. 7 (`chunkIndex` strictly increasing, `sessionUuid` constant): those fields live in the
 *    **chunk** header, not in [SampleBlock]. The check belongs to the `:phone` adapter, which must
 *    produce [IntegrityViolation.BAD_CHUNK_INDEX] on its side.
 *  - the "session break" part of no. 6: here we merely count the violation; it is step 0 that
 *    turns the gap into a hard segment boundary.
 */
object Integrity {

    /**
     * @return the **accepted** blocks (in input order) and the report. Any rejected block is
     *   simply absent from the list: step 0 will see it as a hole of its nominal duration and will
     *   apply the hole policy to it, which is exactly what §2 asks for.
     */
    fun check(
        blocks: List<SampleBlock>,
        nominalHz: Double,
        cfg: IntegrityConfig = IntegrityConfig(),
    ): Pair<List<SampleBlock>, IntegrityReport> {
        val counts = LinkedHashMap<IntegrityViolation, Int>()
        for (v in IntegrityViolation.entries) counts[v] = 0
        fun bump(v: IntegrityViolation) {
            counts[v] = (counts[v] ?: 0) + 1
        }

        val accepted = ArrayList<SampleBlock>(blocks.size)
        var rejected = 0
        var longestRejectRun = 0
        var currentRejectRun = 0

        // Collection for check no. 9: median of the norms over the static windows.
        val staticNorms = ArrayList<Double>()

        var prev: SampleBlock? = null

        for (b in blocks) {
            var ok = true

            // The nominal rate is a property of the BLOCK, not of the session. A night can change
            // rate in flight: auto-degradation step 3 re-registers the sensor at 25 Hz and rotates
            // the chunk, so the rate is carried by each chunk header. Measuring every block against
            // the *first* chunk's nominal rejected the whole post-degradation half of the night as
            // `IMPLAUSIBLE_RATE` — a 50 % deviation against a 20 % tolerance — which threw the
            // recording away exactly when the degradation had managed to save it.
            //
            // `nominalHz` (the session-wide argument) remains the fallback: the synthetic
            // generator and every block built by hand leave `b.nominalHz` at 0.
            val nom = if (b.nominalHz > 0.0) b.nominalHz else nominalHz

            // Nominal interval between two samples, used by the overlap check.
            val nominalStepNs = if (nom > 0.0) 1e9 / nom else 2e7
            val minInterBlockNs = (0.5 * nominalStepNs).toLong()

            // --- 1. Size consistency ----------------------------------------------------
            val n = b.x.size
            if (n < 1 || n > BlockFlags.MAX_SAMPLES_PER_BLOCK || b.y.size != n || b.z.size != n) {
                bump(IntegrityViolation.BAD_COUNT)
                ok = false
            }

            // --- 2. Timestamp order and positivity --------------------------------------
            if (ok && (b.tFirstNs <= 0L || b.tLastNs <= 0L || b.tFirstNs > b.tLastNs)) {
                bump(IntegrityViolation.BAD_TIMESTAMP_ORDER)
                ok = false
            }

            // --- 3. Implicit block rate -------------------------------------------------
            // The block carries its own frequency: (N-1) intervals between tFirst and tLast. A
            // block whose implicit rate departs by more than 20 % from the nominal has a corrupted
            // time base — this is not drift, the drift of a quartz is in ppm.
            if (ok && n >= 2) {
                val spanNs = (b.tLastNs - b.tFirstNs).toDouble()
                val fsBlock = if (spanNs > 0.0) (n - 1) * 1e9 / spanNs else Double.POSITIVE_INFINITY
                if (nom > 0.0 && abs(fsBlock - nom) / nom > cfg.maxRateDeviation) {
                    bump(IntegrityViolation.IMPLAUSIBLE_RATE)
                    ok = false
                }
            }

            // --- 4 / 5 / 6. Inter-block checks ------------------------------------------
            // Compared to the last ACCEPTED block, not to the last block seen: otherwise a
            // corrupted block would contaminate the judgement passed on its successor, which is
            // healthy.
            val p = prev
            if (ok && p != null) {
                val delta = b.tFirstNs - p.tLastNs
                if (delta < 0L) {
                    bump(IntegrityViolation.NON_MONOTONIC)
                    ok = false
                } else if (delta < minInterBlockNs) {
                    // Two blocks touching within less than half a sample overlap: the same
                    // instants would be described twice, and the resampling would produce a
                    // discontinuity invisible in the statistics.
                    bump(IntegrityViolation.OVERLAP)
                    ok = false
                } else if (delta > cfg.maxGapNs) {
                    // Beyond 14 h, this is no longer a hole but a clock reset. The block stays
                    // valid: it is the SESSION that is cut, and step 0 will turn the gap into a
                    // hard segment boundary.
                    bump(IntegrityViolation.IMPLAUSIBLE_GAP)
                }
            }

            // --- 8. Impossible jerk ------------------------------------------------------
            // More than 8 g of difference between two consecutive samples at 50 Hz is physically
            // impossible at the ankle (that amounts to 400 g/s). It is the signature of the sign
            // wraparound of a badly implemented saturation: +16 g flipping to -16 g. This check
            // stays useful even after the `toRaw` bug of `:format` is fixed — it detects any byte
            // corruption that would produce a jump, not only that one.
            if (ok && n >= 2) {
                val maxJerk2 = cfg.maxJerkG.toDouble() * cfg.maxJerkG.toDouble()
                var bad = false
                for (i in 1 until n) {
                    val dx = (b.x[i] - b.x[i - 1]).toDouble()
                    val dy = (b.y[i] - b.y[i - 1]).toDouble()
                    val dz = (b.z[i] - b.z[i - 1]).toDouble()
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > maxJerk2) { bad = true; break }
                }
                if (bad) {
                    bump(IntegrityViolation.IMPOSSIBLE_JERK)
                    ok = false
                }
            }

            // --- 10. Saturation ----------------------------------------------------------
            // The ankle does not reach +/-16 g. A block in which more than `saturationFraction` of
            // the samples is stuck against the stop is not saturated, it is corrupted.
            if (ok && n >= 1) {
                var sat = 0
                val lim = BlockFlags.SATURATION_G * (1f - 1e-6f)
                for (i in 0 until n) {
                    if (abs(b.x[i]) >= lim || abs(b.y[i]) >= lim || abs(b.z[i]) >= lim) sat++
                }
                if (sat.toDouble() / n > cfg.saturationFraction) {
                    bump(IntegrityViolation.SATURATED)
                    ok = false
                }
            }

            // --- 11. FLAG_GAP_BEFORE consistency ----------------------------------------
            // Inconsistency => flag, never rejection: the flag is a convenience piece of
            // information produced by the watch, it has no authority over the timestamps.
            if (ok && p != null) {
                val delta = b.tFirstNs - p.tLastNs
                val measuredGap = delta > 1.5 * nominalStepNs
                val declaredGap = (b.flags and BlockFlags.GAP_BEFORE) != 0
                if (measuredGap != declaredGap) bump(IntegrityViolation.FLAG_INCONSISTENT)
            }

            if (ok) {
                accepted.add(b)
                prev = b
                currentRejectRun = 0
                collectStaticNorm(b, staticNorms)
            } else {
                rejected++
                currentRejectRun++
                if (currentRejectRun > longestRejectRun) longestRejectRun = currentRejectRun
            }
        }

        // --- 9. Gravity plausibility, at session level -----------------------------------
        // On the static windows, the measured norm MUST equal gravity. If the median of the static
        // windows leaves [0.80 ; 1.20] g, it is neither noise nor a badly calibrated sensor
        // (autocalibration corrects a few percent at most): it is a wrong scale or a
        // desynchronised decoding. The whole session becomes suspect.
        var gravityBad = false
        if (staticNorms.isNotEmpty()) {
            val med = Numeric.median(staticNorms.toDoubleArray())
            if (med.isNaN() || med < cfg.gravityRangeG.start || med > cfg.gravityRangeG.endInclusive) {
                bump(IntegrityViolation.GRAVITY_IMPLAUSIBLE)
                gravityBad = true
            }
        }

        val total = blocks.size
        val fraction = if (total > 0) rejected.toDouble() / total else 0.0

        // "The pattern of rejections suggests a decoder desynchronisation, not noise."
        // Interpretation adopted, for want of a definition in the specification: noise strikes
        // isolated blocks at random; a desynchronisation carries away a contiguous burst, or makes
        // gravity leave its range. Hence the three criteria below.
        val structural = (counts[IntegrityViolation.IMPOSSIBLE_JERK] ?: 0) +
            (counts[IntegrityViolation.SATURATED] ?: 0) +
            (counts[IntegrityViolation.BAD_COUNT] ?: 0)
        val decodeSuspect = gravityBad ||
            longestRejectRun >= 3 ||
            (total > 0 && structural.toDouble() / total > 0.005)

        val report = IntegrityReport(
            blocksTotal = total,
            blocksRejected = rejected,
            byViolation = counts.filterValues { it > 0 },
            rejectedFraction = fraction,
            decodeSuspect = decodeSuspect,
        )
        return accepted to report
    }

    /**
     * If the block is static (standard deviation < 13 mg on the three axes, the same threshold as
     * the autocalibration of §3.3), adds the median of its norm to [out].
     * A 512-sample block at 50 Hz lasts 10.2 s: that is already the 10 s window of §3.3, no need
     * to re-cut it.
     */
    private fun collectStaticNorm(b: SampleBlock, out: MutableList<Double>) {
        val n = b.x.size
        if (n < 8) return
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var sx2 = 0.0; var sy2 = 0.0; var sz2 = 0.0
        for (i in 0 until n) {
            val x = b.x[i].toDouble(); val y = b.y[i].toDouble(); val z = b.z[i].toDouble()
            sx += x; sy += y; sz += z
            sx2 += x * x; sy2 += y * y; sz2 += z * z
        }
        val vx = sx2 / n - (sx / n) * (sx / n)
        val vy = sy2 / n - (sy / n) * (sy / n)
        val vz = sz2 / n - (sz / n) * (sz / n)
        val sd = 0.013
        if (sqrt(maxOf(0.0, vx)) >= sd || sqrt(maxOf(0.0, vy)) >= sd || sqrt(maxOf(0.0, vz)) >= sd) return
        // Static window: the norm of the mean vector suffices, the dispersion being negligible.
        val mx = sx / n; val my = sy / n; val mz = sz / n
        out.add(sqrt(mx * mx + my * my + mz * mz))
    }
}
