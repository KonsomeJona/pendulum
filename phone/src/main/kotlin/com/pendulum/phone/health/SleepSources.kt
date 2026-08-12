package com.pendulum.phone.health

import com.pendulum.format.wire.WirePaths
import java.time.ZoneId

/**
 * What the last seven days of Health Connect say about the available sources.
 *
 * Onboarding used to show two hard-coded names here, "Samsung Health" and "Sleep as Android",
 * along with their coverage. On a phone that has neither, the screen was therefore asserting the
 * existence of sources that do not exist, and the user picked one — the setting was written
 * nowhere, and `SleepFetchWorker` fell back on the heuristic of [SleepSourceSelector] without
 * anything saying so.
 *
 * ### Why count nights and not sessions
 *
 * An application that republishes the same night three times is not a source that covers three
 * nights. The count therefore goes through the **night key** — the same one as the evening
 * context, with its noon rollover — and not through the number of records returned. Counting
 * sessions would make a chatty source pass for a regular one, which is exactly the opposite of
 * what we are after.
 */
object SleepSources {

    /** Onboarding observation window. Seven days: `06-interface.md` §2.1, step 4. */
    const val OBSERVED_DAYS = 7

    /**
     * A source as onboarding presents it.
     *
     * @param hasStages true if at least one night carries two distinct stage types. A source that
     *   only returns a duration stays usable — the denominator is all the index needs — but it
     *   does not allow movements to be broken down by stage, and the screen must say so.
     */
    data class Observed(
        val packageName: String,
        val nights: Int,
        val hasStages: Boolean,
    )

    /**
     * Groups sessions by writing package.
     *
     * The order is total and deterministic — nights covered, then presence of stages, then package
     * name — so that two successive openings of onboarding offer the same list in the same order.
     * A list that reorders itself between two displays makes the wrong row get picked.
     */
    fun summarise(
        candidates: List<SleepSourceSelector.Candidate>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Observed> = candidates
        .groupBy { it.packageName }
        .map { (packageName, sessions) ->
            Observed(
                packageName = packageName,
                nights = sessions.map { WirePaths.nightKey(it.startMs, zone) }.distinct().size,
                hasStages = sessions.any { it.distinctStageTypes >= 2 },
            )
        }
        .sortedWith(
            compareByDescending<Observed> { it.nights }
                .thenByDescending { it.hasStages }
                .thenBy { it.packageName },
        )
}
