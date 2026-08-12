package com.pendulum.algo.detect

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.ShortImiPolicy
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage

/**
 * One clinical rule set, transcribed **literally** from table §6.5 of `ALGO-v2.md`.
 *
 * These values are not settings: the legitimate variation is test T14, which compares the two
 * sets, not T12 which sweeps the processing parameters. Use [aasmV3] and [wasm2016].
 *
 * Major correction of v2 over v1 (§1.5-ii): v1 believed the two sets differ only by the lower IMI
 * bound (5 s versus 10 s). **That is the less important of the two points.** Ferri et al. (Sleep
 * Med 2015, 107 RLS + 63 controls) isolated the two effects: raising the lower bound alone
 * ("Alt1") changes almost nothing, "only the Alt2 algorithm" — lower bound **plus** series break
 * on short IMI — "provided significantly different results". The structuring difference is
 * therefore [shortImiPolicy], seconded by [breakOnLongLm] and [requirePortionInSleep].
 */
data class SeriesConfig(
    val rule: SeriesRule,
    val imiMinSec: Double,
    val imiMaxSec: Double = 90.0,
    val minClmPerSeries: Int = 4,
    val shortImiPolicy: ShortImiPolicy,
    val breakOnLongLm: Boolean,
    val requirePortionInSleep: Boolean,
) {
    companion object {
        /**
         * AASM v3. Onset-to-onset IMI in [5, 90] s; >= 4 CLM; at least part of each CLM must fall
         * inside a sleep epoch (new in v3). The AASM is **silent** on the short IMI: the WASM 2006
         * convention ("the later movement is ignored and the period is computed up to the next
         * candidate") is taken as the default and labelled as an interpretation, not as a
         * published rule.
         */
        fun aasmV3() = SeriesConfig(
            rule = SeriesRule.AASM_V3,
            imiMinSec = 5.0,
            imiMaxSec = 90.0,
            minClmPerSeries = 4,
            shortImiPolicy = ShortImiPolicy.SKIP_LATER,
            breakOnLongLm = false,
            requirePortionInSleep = true,
        )

        /**
         * WASM 2016. IMI in [10, 90] s; >= 4 CLM (= 3 IMI); an out-of-bounds IMI **breaks** the
         * series (3.3.6); an LM > 10 s breaks the series (3.2.1: "LM now have no maximum length.
         * A LM > 10 s now ends a PLM sequence."); a series may **cross** a wake/sleep transition
         * (2.4.4), hence `requirePortionInSleep = false`.
         */
        fun wasm2016() = SeriesConfig(
            rule = SeriesRule.WASM_2016,
            imiMinSec = 10.0,
            imiMaxSec = 90.0,
            minClmPerSeries = 4,
            shortImiPolicy = ShortImiPolicy.BREAK_SERIES,
            breakOnLongLm = true,
            requirePortionInSleep = false,
        )
    }
}

/**
 * Built series, plus the count of series dropped at the edges of the night.
 *
 * @param truncatedSeriesDropped series truncated by a recording edge that did not reach
 *   `minClmPerSeries` and are therefore dropped. **To be reported**: it is a measurable downward
 *   bias that grows on an interrupted night, and it does not offset the upward bias of a truncated
 *   night (§3.7.2 point 5) — one must not pretend otherwise.
 */
data class SeriesBuildResult(val series: List<PlmSeries>, val truncatedSeriesDropped: Int)

/**
 * Construction of the PLM series. `docs/workings/ALGO-v2.md` §2 step 6, rules §6.5.
 *
 * **What this class does not do, and it is deliberate**: a missed movement does not cut a series.
 * In the typical regime (IMI ~21 s), missing one CLM doubles the interval to ~42 s, which stays
 * inside the [5, 90] s window: the series survives and only the count drops. A break only happens
 * if the **merged** interval exceeds `imiMaxSec`. No "protection" heuristic is added on top of the
 * rule — that would be inventing a clinical rule.
 */
object SeriesBuilder {

    private val SLEEP_STAGES = setOf(Stage.SLEEP, Stage.LIGHT, Stage.DEEP, Stage.REM)

    /**
     * @param events **all** the events produced by [ClmDetector], rejected ones included: the
     *   `LM_LONG` and the `TRUNCATED` ones are needed to break the series at the right place.
     * @param mask sleep mask; used for `requirePortionInSleep`, for `duringSleepFraction`, and to
     *   bound the night for the detection of truncated series.
     * @param fsHz grid rate, explicit: the IMI are computed on the **indices**, never on the
     *   millisecond fields, which are rounded.
     * @return the series of at least `minClmPerSeries` CLM. `PlmSeries.clmIndices` indexes the
     *   `events` list **as supplied**, so that `events[i]` is always valid.
     */
    fun build(events: List<Clm>, mask: SleepMask, fsHz: Double, cfg: SeriesConfig): List<PlmSeries> =
        buildDetailed(events, mask, fsHz, cfg).series

    fun buildDetailed(
        events: List<Clm>,
        mask: SleepMask,
        fsHz: Double,
        cfg: SeriesConfig,
    ): SeriesBuildResult {
        require(fsHz > 0.0) { "fsHz must be > 0" }
        val order = events.indices.sortedBy { events[it].onsetIdx }
        val imiMaxMs = (cfg.imiMaxSec * 1000.0).toLong()
        val nightStartMs = mask.windows.minOfOrNull { it.startMsRel } ?: 0L
        val nightEndMs = mask.windows.maxOfOrNull { it.endMsRel }
            ?: events.maxOfOrNull { it.onsetMsRel + it.durationMs } ?: 0L

        val out = ArrayList<PlmSeries>()
        var dropped = 0
        val open = ArrayList<Int>()
        val imis = ArrayList<Float>()
        var openTruncStart = false
        var afterHardBreak = false
        var sawAnyClm = false

        fun close(truncatedAtEnd: Boolean) {
            if (open.isEmpty()) return
            if (open.size >= cfg.minClmPerSeries) {
                val inSleep = open.count { overlapsSleep(events[it], mask) }
                out += PlmSeries(
                    rule = cfg.rule,
                    clmIndices = open.toIntArray(),
                    imiSec = FloatArray(imis.size) { imis[it] },
                    truncatedAtStart = openTruncStart,
                    truncatedAtEnd = truncatedAtEnd,
                    duringSleepFraction = inSleep.toFloat() / open.size,
                )
            } else if (openTruncStart || truncatedAtEnd) {
                // Truncated and incomplete: dropped, but counted (§2 step 6, edge behaviour).
                dropped++
            }
            open.clear()
            imis.clear()
            openTruncStart = false
        }

        fun openWith(idx: Int, hardBreak: Boolean) {
            open += idx
            openTruncStart = when {
                hardBreak -> true
                !sawAnyClm -> events[idx].onsetMsRel - nightStartMs < imiMaxMs
                else -> false
            }
            sawAnyClm = true
        }

        for (idx in order) {
            val e = events[idx]

            // Hard breaks. An LM > 10 s breaks the series under WASM (3.3.6); an event truncated by
            // a segment edge always breaks it — its duration is unknown, this is the conservative
            // behaviour, and it is already WASM rule 3.3.3 for a recording restart.
            val truncatedEvent = (e.flags and ClmFlags.TRUNCATED) != 0
            val longLm = (e.flags and ClmFlags.LM_LONG) != 0
            if (truncatedEvent || (cfg.breakOnLongLm && longLm)) {
                close(truncatedAtEnd = truncatedEvent)
                afterHardBreak = truncatedEvent
                continue
            }
            if (!e.isClm) continue
            if (cfg.requirePortionInSleep && !overlapsSleep(e, mask)) continue

            if (open.isEmpty()) {
                openWith(idx, afterHardBreak)
                afterHardBreak = false
                continue
            }
            sawAnyClm = true
            val imiSec = (e.onsetIdx - events[open.last()].onsetIdx) / fsHz
            when {
                imiSec > cfg.imiMaxSec -> {
                    close(truncatedAtEnd = false)
                    openWith(idx, false)
                }

                imiSec >= cfg.imiMinSec -> {
                    open += idx
                    imis += imiSec.toFloat()
                }

                // Short IMI: **the** parameter that separates the two rule sets.
                cfg.shortImiPolicy == ShortImiPolicy.BREAK_SERIES -> {
                    close(truncatedAtEnd = false)
                    openWith(idx, false)
                }

                // SKIP_LATER: the later movement is ignored, the reference does not move, the
                // period is measured up to the next candidate.
                else -> Unit
            }
        }
        val lastOnsetMs = open.lastOrNull()?.let { events[it].onsetMsRel } ?: 0L
        close(truncatedAtEnd = nightEndMs - lastOnsetMs < imiMaxMs)
        return SeriesBuildResult(out, dropped)
    }

    /** At least part of the CLM falls inside a sleep epoch (AASM v3 rule). */
    private fun overlapsSleep(e: Clm, mask: SleepMask): Boolean {
        val from = e.onsetMsRel
        val to = e.onsetMsRel + e.durationMs
        return mask.windows.any {
            it.stage in SLEEP_STAGES && from < it.endMsRel && to > it.startMsRel
        }
    }
}
