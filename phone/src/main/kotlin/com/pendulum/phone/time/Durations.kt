package com.pendulum.phone.time

import com.pendulum.format.time.TimeScale
import java.util.concurrent.TimeUnit

/**
 * Every **wall-clock** duration of `:phone`, and the only place they are allowed to live.
 *
 * Twin of `com.pendulum.wear.time.Durations`, same role and same contract: `DurationsTest`
 * compares two catalogues built at 1 and at 600 by reflection, `DurationsInventoryTest` forbids a
 * wall-clock duration from reappearing hard-coded anywhere else in `src/main/`.
 *
 * ### What is not here, and why
 *
 *  - `WakingMachine.ESTIMATED_THROUGHPUT_BYTES_PER_S` and `ESTIMATED_CHUNK_SIZE_BYTES` — the wait
 *    estimated at waking is a **real throughput** of the Data Layer multiplied by a real volume.
 *    Neither of the two compresses, so neither does the time they announce. The bench will see a
 *    correct estimate in front of a genuinely fast transfer, which is the correct behaviour and
 *    not an artefact.
 *  - The chart unit constants (`hourMs` of `NightDrawing`, the day of `TrendScreen`) — conversion
 *    factors for an axis, never delays.
 *  - The five seconds of `SharingStarted.WhileSubscribed` — how long a flow survives after the
 *    last subscriber disappears. It settles a recomposition cost, not an observable behaviour;
 *    compressing it would restart collections during screen rotations without teaching anyone
 *    anything.
 *
 * @param divisor 1 in real time. See the `TimeScaling` twins.
 */
class Durations(divisor: Long) {

    /**
     * The retry ladder of the Health Connect read, counted from the **end** of the night.
     *
     * T+30 min, 1 h, 2 h, 4 h, 8 h, 16 h, 32 h. See `FetchSchedule` for what these values are
     * worth: an estimate to be re-tuned on the latency actually observed, not a measurement.
     */
    val readOffsetsMs: LongArray = TimeScale.ms(
        longArrayOf(
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(4),
            TimeUnit.HOURS.toMillis(8),
            TimeUnit.HOURS.toMillis(16),
            TimeUnit.HOURS.toMillis(32),
        ),
        divisor,
    )

    /** Beyond this, we stop waiting for the hypnogram and the night stays on its accelerometer mask. */
    val readGiveUpMs: Long = TimeScale.ms(TimeUnit.HOURS.toMillis(36), divisor)

    /** Burst guard on opportunistic reads: a cable making a bad contact emits in a loop. */
    val minBetweenOpportunisticMs: Long = TimeScale.ms(TimeUnit.MINUTES.toMillis(10), divisor)

    /**
     * Period of the WorkManager watchdog on the phone side.
     *
     * Same reservation as on the watch: `PeriodicWorkRequest` brings any period under fifteen
     * minutes back up to fifteen minutes. The compressed value expresses the intent; the bench
     * must trigger this work itself if it wants to exercise it.
     */
    val watchdogPeriodMs: Long = TimeScale.ms(TimeUnit.MINUTES.toMillis(30), divisor)

    /**
     * First step of the exponential backoff of the evening context republication.
     *
     * Same reservation as on [watchdogPeriodMs], and for the same reason: WorkManager brings any
     * backoff under ten seconds back up to ten seconds, so at scale 600 this value no longer
     * compresses. It expresses the intent — retry quickly, because the failure it targets is a
     * transient unavailability of Google Play services and the watch is waiting, tonight.
     *
     * Thirty seconds and not five minutes: the user has just sealed their context and is about to
     * go to bed. A first replay at five minutes would make the catch-up unusable in the only case
     * where it counts.
     */
    val contextRepublicationDelayMs: Long = TimeScale.ms(TimeUnit.SECONDS.toMillis(30), divisor)

    /** Silence beyond which an open session goes `STALE`: the watch may yet come back. */
    val silenceBeforeStaleMs: Long = TimeScale.ms(TimeUnit.MINUTES.toMillis(45), divisor)

    /** Age beyond which a night is declared `TRUNCATED` and analysed as it stands. */
    val maxNightAgeMs: Long = TimeScale.ms(TimeUnit.HOURS.toMillis(14), divisor)

    /**
     * How long the feedback of the "start on the watch" button stays under the button.
     *
     * Six seconds and not two: the failure sentence is long — it names the fallback gesture,
     * pressing START on the watch — and it is read at bed time, low light, one hand. Two seconds
     * would make it disappear before the end of the line.
     *
     * It is **here** and not in the screen, unlike the five seconds of
     * `SharingStarted.WhileSubscribed`: those settle a recomposition cost that nobody observes,
     * this one is a display duration that the bench must cross at its own speed. A bench at scale
     * 600 that waited six real seconds under every start would spend most of its simulated night
     * looking at a sentence.
     */
    val startFeedbackMs: Long = TimeScale.ms(TimeUnit.SECONDS.toMillis(6), divisor)

    companion object {

        /** The catalogue of the compiled variant. A single read point, a single divisor. */
        val ACTIVE = Durations(TimeScaling.DIVISOR)
    }
}
