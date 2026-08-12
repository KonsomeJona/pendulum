package com.pendulum.phone.health

import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.phone.ingest.TimeAnchor
import com.pendulum.phone.work.AnalysisParams

/**
 * Translation of a Health Connect hypnogram into what `:algo` can read.
 *
 * Two operations, and each of them is a documented trap:
 *  - the **change of time reference** (UTC wall clock -> milliseconds relative to the sensor
 *    timeline), which goes through [TimeAnchor] and never through a subtraction of wall clocks;
 *  - the handling of the **gaps**, which the API explicitly allows between two stages.
 */
object Hypnogram {

    // Constants from SleepSessionRecord, copied so that this file stays readable without the API.
    const val STAGE_UNKNOWN = 0
    const val STAGE_AWAKE = 1
    const val STAGE_SLEEPING = 2
    const val STAGE_OUT_OF_BED = 3
    const val STAGE_LIGHT = 4
    const val STAGE_DEEP = 5
    const val STAGE_REM = 6
    const val STAGE_AWAKE_IN_BED = 7

    /** `startMs:endMs:type;...`, UTC wall clock. Format of `hc_snapshot.selectedStagesCsv`. */
    fun encodeCsv(stages: List<SleepSourceSelector.StageSpan>): String =
        stages.joinToString(";") { "${it.startMs}:${it.endMs}:${it.stageType}" }

    fun decodeCsv(csv: String): List<SleepSourceSelector.StageSpan> {
        if (csv.isBlank()) return emptyList()
        return csv.split(';').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 3) return@mapNotNull null
            val a = f[0].toLongOrNull() ?: return@mapNotNull null
            val b = f[1].toLongOrNull() ?: return@mapNotNull null
            val t = f[2].toIntOrNull() ?: return@mapNotNull null
            SleepSourceSelector.StageSpan(a, b, t)
        }
    }

    /**
     * Conversion into windows usable by `:algo`.
     *
     * ### The gaps
     *
     * The API allows gaps between two stages. What we choose to do with them **changes the
     * denominator, hence the index, hence potentially a decision**: counting a gap as sleep
     * inflates the TST and deflates the index; counting it as wake does the opposite. That is why
     * this is not a hard-coded value but a parameter traced in the hash
     * ([AnalysisParams.hypnogramHolePolicy]), with `EXCLUDE` by default — a gap is not a piece of
     * information, and filling it amounts to inventing one.
     *
     * ### The "duration only" case
     *
     * A source can write a session without any stage (trap no. 1: technically compliant, useless
     * for the plausibility check). We then produce a single [Stage.SLEEP] window covering the
     * session: that is exactly the information available, no more — fabricating a
     * light/deep/REM breakdown out of nowhere would be worse than nothing.
     */
    fun toWindows(
        stages: List<SleepSourceSelector.StageSpan>,
        sessionStartMs: Long,
        sessionEndMs: Long,
        anchor: TimeAnchor,
        holePolicy: AnalysisParams.HolePolicy,
    ): List<SleepWindow> {
        if (stages.isEmpty()) {
            return listOf(
                SleepWindow(anchor.toMsRel(sessionStartMs), anchor.toMsRel(sessionEndMs), Stage.SLEEP)
            )
        }

        val sorted = stages.sortedBy { it.startMs }
        val out = ArrayList<SleepWindow>(sorted.size * 2)
        var cursor = sessionStartMs

        for (s in sorted) {
            if (s.startMs > cursor) fillHole(out, cursor, s.startMs, anchor, holePolicy)
            out += SleepWindow(anchor.toMsRel(s.startMs), anchor.toMsRel(s.endMs), stageOf(s.stageType))
            cursor = maxOf(cursor, s.endMs)
        }
        if (cursor < sessionEndMs) fillHole(out, cursor, sessionEndMs, anchor, holePolicy)
        return out
    }

    private fun fillHole(
        out: MutableList<SleepWindow>,
        fromMs: Long,
        toMs: Long,
        anchor: TimeAnchor,
        policy: AnalysisParams.HolePolicy,
    ) {
        val stage = when (policy) {
            // EXCLUDE: we still emit a window, marked UNKNOWN. Emitting nothing at all would let
            // `:algo` interpolate between the two neighbouring stages, which would amount to
            // silently choosing exactly what we are refusing to choose.
            AnalysisParams.HolePolicy.EXCLUDE -> Stage.UNKNOWN
            AnalysisParams.HolePolicy.AS_SLEEP -> Stage.SLEEP
            AnalysisParams.HolePolicy.AS_WAKE -> Stage.WAKE
        }
        out += SleepWindow(anchor.toMsRel(fromMs), anchor.toMsRel(toMs), stage)
    }

    fun stageOf(hcStage: Int): Stage = when (hcStage) {
        STAGE_AWAKE -> Stage.WAKE
        STAGE_SLEEPING -> Stage.SLEEP
        STAGE_OUT_OF_BED -> Stage.OUT_OF_BED
        STAGE_LIGHT -> Stage.LIGHT
        STAGE_DEEP -> Stage.DEEP
        STAGE_REM -> Stage.REM
        STAGE_AWAKE_IN_BED -> Stage.AWAKE_IN_BED
        else -> Stage.UNKNOWN
    }
}
