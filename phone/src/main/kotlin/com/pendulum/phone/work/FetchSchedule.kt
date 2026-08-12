package com.pendulum.phone.work

import com.pendulum.phone.time.Durations

/**
 * The rescheduling of the Health Connect read. A pure function, testable without a device.
 *
 * ### The trap this file exists to defuse
 *
 * **The sleep session does not appear on waking.** The watch -> phone transfer is governed by "the
 * battery policy of the watch" (the manufacturer's words), with no guaranteed delay. Once the data
 * is on the phone, the write into Health Connect is immediate — the bottleneck is therefore
 * entirely upstream, and no code on the phone can speed it up.
 *
 * The consequence is that a single read on waking fails most of the time, and fails **silently**:
 * Health Connect does not return an error, it returns an empty list. Without a retry ladder, the
 * application would conclude "no sleep data tonight" when the night arrives two hours later.
 *
 * ### Why the night does not wait for this read
 *
 * `AnalyzeWorker` runs **immediately** with the accelerometer mask: the night is analysable as
 * soon as the transfer is over, and the user sees a result on waking. `SleepFetchWorker` and
 * `RescoreWorker` then add the second, non-circular arm, when the hypnogram arrives. The ladder
 * below is therefore never blocking: at worst, the night keeps its accelerometer mask alone, which
 * is allowed to exist but not to carry the main result.
 *
 * ### The values
 *
 * T+30 min, 1 h, 2 h, 4 h, 8 h — then 16 h and 32 h so as not to leave 28 hours without an attempt
 * before the wall — and giving up at T+36 h.
 *
 * **These figures are an estimate, not a measurement.** The verification procedure
 * (`docs/workings/SLEEP-SOURCES.md` §5, step 1) asks for the exact time at which the night appears
 * to be noted, three mornings in a row, and for this ladder to be re-tuned on the observed value.
 * Until that is done, these seven values are a reasonable bet and nothing more.
 */
object FetchSchedule {

    /** Delays from the **end** of the night, in milliseconds. Values in `Durations`. */
    val OFFSETS_MS: LongArray = Durations.ACTIVE.readOffsetsMs

    /**
     * Beyond this, we stop. Thirty-six hours is not a compromise: it is the point where carrying
     * on trying costs more (wake-ups, a notification left hanging, a night whose state is never
     * final) than the answer would bring in.
     */
    val GIVE_UP_MS: Long = Durations.ACTIVE.readGiveUpMs

    sealed interface Plan {
        /** @param delayMs wait before the next attempt. Zero = immediate catch-up. */
        data class Retry(val delayMs: Long, val attemptIndex: Int) : Plan

        data class GiveUp(val reason: String) : Plan
    }

    /**
     * @param attemptsDone number of reads already attempted for this night. It is read from
     *   `hc_snapshot` (one row per attempt), **never** from a preference or a worker counter:
     *   WorkManager can replay a worker, and a counter that advances at every run would consume
     *   the ladder in a few seconds after a simple restart.
     * @param sessionEndMs end of the night. It is the time origin of the ladder: counting from the
     *   start of the night would fire the first attempt while the person is still asleep.
     *
     * `delayMs = 0` when the offset has already passed — the case of a phone switched off all
     * morning. We then catch the attempts up one by one rather than jumping straight to the last
     * one: each produces an `hc_snapshot` row, and that trace is what will make it possible to
     * re-tune the ladder on the real latency.
     */
    fun plan(
        attemptsDone: Int,
        sessionEndMs: Long,
        nowMs: Long,
        /**
         * The ladder and its wall, as parameters rather than read from deep inside the function.
         *
         * This function is pure and its tests assert values — "the first attempt is at T+30
         * minutes", "we give up at T+36 h". Letting them follow the divisor of the compiled
         * variant would make those assertions fail because a bench was built differently, which
         * teaches nobody anything about the rescheduling.
         */
        offsetsMs: LongArray = OFFSETS_MS,
        giveUpMs: Long = GIVE_UP_MS,
    ): Plan {
        val elapsed = nowMs - sessionEndMs
        if (elapsed >= giveUpMs) {
            // The message is derived from the bound instead of quoting it: on the bench the bound
            // is compressed, and a log announcing "T+36 h" after three minutes would be the first
            // thing to send a reader down a false trail.
            return Plan.GiveUp("giving up: $elapsed ms elapsed, bound $giveUpMs ms")
        }
        if (attemptsDone >= offsetsMs.size) {
            return Plan.GiveUp("ladder exhausted after ${offsetsMs.size} attempts")
        }
        val target = sessionEndMs + offsetsMs[attemptsDone]
        return Plan.Retry(delayMs = (target - nowMs).coerceAtLeast(0L), attemptIndex = attemptsDone)
    }

    // -------------------------------------------------------------------------------------
    // The opportunistic trigger
    // -------------------------------------------------------------------------------------

    /**
     * Two moments where the read has a far better chance of succeeding than the next rung of the
     * ladder.
     *
     * ### Why exponential backoff alone is the wrong model
     *
     * Exponential backoff assumes a **random** event whose date is unknown. The Health Connect
     * synchronisation is not one: it is correlated with usage. The wrist watch pushes when it is
     * on the charger, and the source application writes when it is opened — that is, often a few
     * seconds before Pendulum is opened to look at the night. Waiting for the T+4 h rung when the
     * data arrived at T+2 h 05 costs two hours of perceived latency for nothing.
     *
     * Two free signals, then: plugging in the charger (`ACTION_POWER_CONNECTED`, exempt from the
     * broadcast restrictions since Android 8) and the application returning to the foreground.
     *
     * ### What this function protects
     *
     * An opportunistic read **does not consume the ladder**: it is logged with
     * [OPPORTUNISTIC_INDEX] and `HcSnapshotDao.attemptCount` only counts the scheduled rungs.
     * Without that, plugging and unplugging the phone three times would exhaust the seven rungs in
     * a minute and the application would give up before noon.
     *
     * What remains to be avoided is the burst: a cable making a bad contact can emit the broadcast
     * several times a minute, and every attempt queries a provider. Hence the minimum delay
     * between two opportunistic reads.
     */
    fun opportunisticAllowed(
        sessionEndMs: Long,
        nowMs: Long,
        lastAttemptMs: Long?,
        /** See [plan]: the bounds are parameters so that the tests can name them. */
        giveUpMs: Long = GIVE_UP_MS,
        minBetweenMs: Long = MIN_BETWEEN_OPPORTUNISTIC_MS,
    ): Boolean {
        val elapsed = nowMs - sessionEndMs
        if (elapsed < 0 || elapsed >= giveUpMs) return false
        val last = lastAttemptMs ?: return true
        return nowMs - last >= minBetweenMs
    }

    /** Minimum delay between two opportunistic reads. Burst guard, nothing more. */
    val MIN_BETWEEN_OPPORTUNISTIC_MS: Long = Durations.ACTIVE.minBetweenOpportunisticMs

    /**
     * `attemptIndex` of the `hc_snapshot` rows produced outside the ladder.
     *
     * Negative so that counting the scheduled attempts stays a simple `WHERE attemptIndex >= 0`:
     * one more boolean column would have required a migration, the sign convention requires none
     * and can be read in the query.
     */
    const val OPPORTUNISTIC_INDEX = -1

    /**
     * Should we rescore after this read?
     *
     * We keep reading **even after a success**, because a provider can *rewrite* an already
     * published session: the night read at T+1 h can differ from the same night at T+8 h. But we
     * only rescore if something has changed — otherwise every attempt would restart a full
     * analysis to end up with the same figure.
     *
     * The comparison is on the record identifier, its last modification date and the number of
     * stages: the three fields that move when a source republishes its night.
     */
    fun shouldRescore(
        previousRecordId: String?,
        previousLastModifiedMs: Long?,
        previousStageCount: Int,
        currentRecordId: String?,
        currentLastModifiedMs: Long?,
        currentStageCount: Int,
    ): Boolean {
        if (currentRecordId == null) return false
        if (previousRecordId == null) return true
        return previousRecordId != currentRecordId ||
            previousLastModifiedMs != currentLastModifiedMs ||
            previousStageCount != currentStageCount
    }
}
