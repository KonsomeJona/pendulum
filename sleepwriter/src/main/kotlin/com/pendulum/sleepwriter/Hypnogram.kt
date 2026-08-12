package com.pendulum.sleepwriter

/**
 * Fabrication of a plausible hypnogram, without randomness and without any Android dependency.
 *
 * ### Why this is not a randomly drawn sequence of stages
 *
 * The hypnogram is the denominator of the measurement, and it does not only supply a duration:
 * Pendulum's chain **superimposes** it on the accelerometer signal, excludes waking, and checks the
 * biological plausibility of what it sees. A randomly drawn sequence of stages would pass through
 * the `Hypnogram.toWindows` conversion without exercising any of that, and the bench would say "it
 * works" without having tested what matters.
 *
 * The rules followed here are the ones a sleeper obeys:
 *  - **one never goes from waking straight into deep sleep.** Entry into deep sleep goes through
 *    light sleep, always;
 *  - **REM does not immediately follow deep sleep**: the climb back up goes through light sleep;
 *  - **cycles of about 90 minutes**, with an end-of-cycle micro-waking, which is the form in which
 *    wakings really appear in a normal night;
 *  - **deep sleep concentrated in the first half of the night** and decreasing, **REM increasing**
 *    in the second. It is the only asymmetry the eye recognises immediately on a hypnogram, and it
 *    is also the one that makes a time offset in the superposition visible.
 *
 * ### The stages Health Connect can name, which are not those of the AASM
 *
 * The AASM distinguishes N1, N2 and N3. Health Connect only knows `LIGHT` and `DEEP`: N1 and N2
 * both fall into `LIGHT`, N3 into `DEEP`. This is not a loss for Pendulum — the index does not
 * weight by stage, the stages serve the plausibility check (`SleepReader` KDoc) — but it is
 * something to know before looking for an N2 that will never exist in the data read back.
 *
 * Likewise, `AWAKE_IN_BED` (7) and `AWAKE` (1) are two distinct stages: the first for intra-night
 * wakings, the second for the final waking. Both count as waking for `Hypnogram.stageOf`, but
 * writing them interchangeably would produce a hypnogram that no real device could ever have
 * written.
 *
 * ### Contiguity
 *
 * The stages produced cover `[startMs, endMs]` **with no hole and no overlap**. This is
 * deliberately the easy case: the hole policy of `Hypnogram.fillHole` changes the denominator, so
 * the index, and deserves a scenario of its own rather than being mixed into the nominal scenario.
 */
object Hypnogram {

    // Constants from `SleepSessionRecord`, copied here so this file stays pure JVM and therefore
    // testable without an emulator — same choice as `phone/health/Hypnogram.kt`, which copies them
    // too.
    const val WAKE = 1
    const val SLEEP = 2
    const val LIGHT = 4
    const val DEEP = 5
    const val REM = 6
    const val AWAKE_IN_BED = 7

    /** One stage, in UTC wall-clock milliseconds. */
    data class Stage(val startMs: Long, val endMs: Long, val type: Int)

    /** Sleep-onset latency: the time spent in bed, awake, before the first stage. */
    const val LATENCY_MIN = 8L

    /** Final waking, before the end of the session. */
    const val FINAL_WAKE_MIN = 3L

    /** End-of-cycle micro-waking. */
    private const val MICRO_WAKE_MIN = 2L

    private const val CYCLE_MIN = 90L

    /**
     * A full night with stages.
     *
     * Returns an empty list if the window is too short to carry anything besides the latency and
     * the final waking — writing a ten-minute "night" made of two wakings would be a false
     * hypnogram, and it is better for the caller to see zero stages and say so.
     */
    fun fullNight(startMs: Long, endMs: Long): List<Stage> {
        val durationMin = (endMs - startMs) / 60_000L
        if (durationMin <= LATENCY_MIN + FINAL_WAKE_MIN) return emptyList()

        val segments = ArrayList<Pair<Int, Long>>()
        segments += AWAKE_IN_BED to LATENCY_MIN

        var remaining = durationMin - LATENCY_MIN - FINAL_WAKE_MIN
        var cycle = 0
        while (remaining > 0) {
            for ((type, minutes) in cycle(cycle)) {
                if (remaining <= 0) break
                val duration = minOf(minutes, remaining)
                segments += type to duration
                remaining -= duration
            }
            cycle++
        }
        segments += WAKE to FINAL_WAKE_MIN

        // The last segment absorbs the rounding to the minute, so that the last stage ends exactly
        // at `endMs`. Without that, a session of 8 h 00 min 30 s would leave thirty seconds
        // uncovered, `verdictOf` would see a holed hypnogram, and the bench would report a fault in
        // the chain where there is only an integer division.
        val stages = ArrayList<Stage>(segments.size)
        var cursor = startMs
        for ((index, segment) in segments.withIndex()) {
            val end = if (index == segments.lastIndex) endMs else cursor + segment.second * 60_000L
            if (end > cursor) stages += Stage(cursor, end, segment.first)
            cursor = end
        }
        return merge(stages)
    }

    /**
     * One cycle, as consecutive segments.
     *
     * Deep sleep decreases from 30 minutes to zero, REM increases from 8 to 36: both profiles are
     * those of a real night, and their sum leaves light sleep half of the night, which is the right
     * proportion. Beyond the fifth cycle the profile of the fifth is repeated — a ten-hour night
     * does not start producing deep sleep again.
     */
    private fun cycle(index: Int): List<Pair<Int, Long>> {
        val i = index.coerceAtMost(4)
        val deep = (30L - 9L * i).coerceAtLeast(0L)
        val rem = 8L + 7L * i
        val light = CYCLE_MIN - deep - rem - MICRO_WAKE_MIN

        return buildList {
            if (deep > 0) {
                // 60 / 40: falling asleep within a cycle takes longer than the climb back up
                // towards REM. The order light -> deep -> light -> REM is the point of this file;
                // do not flatten it into a proportional split.
                val before = light * 6 / 10
                add(LIGHT to before)
                add(DEEP to deep)
                add(LIGHT to (light - before))
            } else {
                add(LIGHT to light)
            }
            add(REM to rem)
            add(AWAKE_IN_BED to MICRO_WAKE_MIN)
        }
    }

    /**
     * Merges two consecutive identical stages.
     *
     * Splitting into cycles can produce an end-of-cycle `LIGHT` followed by a `LIGHT` starting the
     * next one when deep sleep is zero. Two adjoining records of the same type are not wrong, but
     * no real device writes any, and both `distinctStageTypes` and `stageCoverageMs` of
     * `SleepSourceSelector` read more easily without them.
     */
    private fun merge(stages: List<Stage>): List<Stage> {
        val out = ArrayList<Stage>(stages.size)
        for (s in stages) {
            val last = out.lastOrNull()
            if (last != null && last.type == s.type && last.endMs == s.startMs) {
                out[out.lastIndex] = last.copy(endMs = s.endMs)
            } else {
                out += s
            }
        }
        return out
    }
}
