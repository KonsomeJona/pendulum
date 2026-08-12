package com.pendulum.phone.health

/**
 * Deduplication of the Health Connect sleep sessions.
 *
 * ### The counter-intuitive fact everything follows from
 *
 * `readRecords()` **deduplicates nothing**: it returns every record from every source. If Samsung
 * Health and Sleep as Android have both written the night, we get two overlapping sessions.
 * Naively concatenating their stages gives an incoherent hypnogram and a roughly doubled TST —
 * hence an aPLM-i divided by two, **without the slightest warning**. Nothing in the API flags the
 * problem.
 *
 * `aggregate()`, for its part, does deduplicate, according to the application priority set by the
 * user. But all it can return is `SLEEP_DURATION_TOTAL`: **no aggregation returns the stages.** It
 * therefore serves as a cross-check (§6, step 1) and not as a source.
 *
 * ### The golden rule
 *
 * **One source and one only for a given night. We never merge the stages of two sources.** Two
 * hypnograms that contradict each other are not averaged: one of them is chosen. The selection
 * order is the one in `docs/workings/SLEEP-SOURCES.md` §6:
 *
 *  1. the preferred source set by the user, if it covers >= 50 % of the window;
 *  2. otherwise the one with the most **distinct stage types** — a real hypnogram beats a duration
 *     disguised as a hypnogram;
 *  3. on a tie, the longest coverage by the stages.
 *
 * The Health Connect system priority is **not** a safety net: Google states that reading
 * applications remain free to read everything and to merge it their own way. So this is Pendulum's
 * job, not the platform's.
 */
object SleepSourceSelector {

    /** One stage, as Health Connect returns it. Gaps between stages are allowed. */
    data class StageSpan(val startMs: Long, val endMs: Long, val stageType: Int)

    /**
     * A candidate session.
     *
     * @param packageName `metadata.dataOrigin.packageName`. **Recorded systematically**: without
     *   it an abnormal night is undebuggable — we do not even know which application wrote the
     *   hypnogram that was used.
     * @param lastModifiedMs `metadata.lastModifiedTime`. This is the field that gives away a
     *   rewrite after the fact: some providers update an already published session, and a night
     *   read at T+1 h can differ from the same night at T+8 h.
     */
    data class Candidate(
        val recordId: String,
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
        val lastModifiedMs: Long,
        val stages: List<StageSpan>,
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

        /** Distinct stage types, `STAGE_TYPE_UNKNOWN` (0) excluded: it tells us nothing. */
        val distinctStageTypes: Int
            get() = stages.asSequence().map { it.stageType }.filter { it != STAGE_TYPE_UNKNOWN }
                .distinct().count()

        /** Duration covered by stages. Can be far below the duration of the session. */
        val stageCoverageMs: Long
            get() = stages.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
    }

    const val STAGE_TYPE_UNKNOWN = 0

    /** Overlap threshold beyond which the preferred source wins outright. */
    const val PREFERRED_MIN_OVERLAP = 0.50

    data class Selection(
        val chosen: Candidate?,
        val rejected: List<Candidate>,
        val reason: String,
    )

    /**
     * @param windowStartMs,windowEndMs the recording window of the ankle watch. It is the frame of
     *   reference: a 2 pm nap session that does not overlap it does not concern this night,
     *   whatever its quality.
     * @param preferredPackage the source chosen by the user in the settings, or `null`.
     */
    fun select(
        candidates: List<Candidate>,
        windowStartMs: Long,
        windowEndMs: Long,
        preferredPackage: String?,
    ): Selection {
        // Sessions that do not overlap the night at all are excluded from the outset: keeping them
        // would let an afternoon nap into the denominator.
        val overlapping = candidates.filter { overlapMs(it, windowStartMs, windowEndMs) > 0L }
        if (overlapping.isEmpty()) {
            return Selection(null, candidates, "NO_OVERLAPPING_SESSION")
        }

        val windowLen = (windowEndMs - windowStartMs).coerceAtLeast(1L)

        preferredPackage?.let { pref ->
            val best = overlapping
                .filter { it.packageName == pref }
                .maxByOrNull { overlapMs(it, windowStartMs, windowEndMs) }
            if (best != null &&
                overlapMs(best, windowStartMs, windowEndMs).toDouble() / windowLen >= PREFERRED_MIN_OVERLAP
            ) {
                return Selection(best, overlapping - best, "PREFERRED_SOURCE")
            }
        }

        // Deterministic and total order: number of stage types, then coverage, then overlap, then
        // package name. The last three criteria only exist to break ties — without them, two
        // successive reads of the same data could choose different sources depending on the order
        // the API returned them in, and two analyses of the same night would not give the same
        // figure.
        val chosen = overlapping.sortedWith(
            compareByDescending<Candidate> { it.distinctStageTypes }
                .thenByDescending { it.stageCoverageMs }
                .thenByDescending { overlapMs(it, windowStartMs, windowEndMs) }
                .thenBy { it.packageName }
                .thenBy { it.recordId }
        ).first()

        val reason = if (chosen.distinctStageTypes >= 2) "MORE_STAGES" else "DURATION_ONLY"
        return Selection(chosen, overlapping - chosen, reason)
    }

    /** Overlap, in milliseconds, between a session and the recording window. */
    fun overlapMs(c: Candidate, windowStartMs: Long, windowEndMs: Long): Long =
        (minOf(c.endMs, windowEndMs) - maxOf(c.startMs, windowStartMs)).coerceAtLeast(0L)

    fun overlapFraction(c: Candidate, windowStartMs: Long, windowEndMs: Long): Double {
        val len = (windowEndMs - windowStartMs).coerceAtLeast(1L)
        return overlapMs(c, windowStartMs, windowEndMs).toDouble() / len
    }

    /**
     * The switching criterion of `docs/workings/SLEEP-SOURCES.md` §4, applied literally.
     *
     * A source is judged sufficient if it overlaps >= 50 % of the window, contains at least two
     * distinct stage types other than `UNKNOWN`, and if its stages cover >= 80 % of the session. A
     * failure on the overlap alone is a **latency** problem; a failure on the stages is a
     * **source** problem. The two are not repaired the same way, hence two distinct verdicts and
     * not a boolean.
     */
    fun verdictOf(c: Candidate, windowStartMs: Long, windowEndMs: Long): String {
        val overlapOk = overlapFraction(c, windowStartMs, windowEndMs) >= 0.50
        val stagesOk = c.distinctStageTypes >= 2
        val coverageOk = c.durationMs > 0 &&
            c.stageCoverageMs.toDouble() / c.durationMs >= 0.80
        return when {
            !overlapOk -> "LATENCY"
            !stagesOk -> "STAGES_MISSING"
            !coverageOk -> "HYPNOGRAM_GAPPY"
            else -> "OK"
        }
    }
}
