package com.pendulum.format.time

/**
 * The scaling law for wall-clock time, and nothing else.
 *
 * A night lasts eight hours; a test must last a few minutes. Both ends of the product carry real
 * delays — chunk rotation at 5 min, watchdog at 15 min, Health Connect retry ladder out to
 * T+32 h — which make a complete night unobservable on a bench.
 *
 * ### The tempting bad idea, explicitly rejected
 *
 * **Do not touch the emulator system clock.** `adb shell date` simultaneously drifts Play
 * Services certificate validation, WorkManager windows and the Data Layer exponential backoffs.
 * The symptoms are plausible failures unrelated to the cause — days are lost to it. It is named
 * here so that nobody retries it believing they invented it.
 *
 * ### An integer divisor, and not a floating-point factor
 *
 * `nominal / d` is exact and monotonic; `nominal * f` with `f = 1.0 / 600` is not, and two
 * durations that are ordered nominally can come out equal after rounding. Since the order of
 * those durations **is** the behaviour under test — the burst leaves before the watchdog expires,
 * the T+1 h rung falls before the T+2 h rung — a rounding that makes them equal does not break a
 * value, it breaks a scenario, and it breaks it intermittently.
 *
 * `divisor = 1` is real time. It is the value of the release variant, and the only one it knows
 * how to produce: see the `TimeScaling` twins in `:wear` and `:phone`.
 *
 * ### What never goes through here
 *
 *  - **Volumes.** `WireProtocol.CHUNK_ROTATION_BYTES` is the hard guard rail of rotation: it is
 *    what checks that the memory buffers do not overflow and that the payload stays under the
 *    100 KB of a `DataItem`. Compressing duration **without** compressing volume is the intended
 *    effect: we want to see the same bytes go past, faster.
 *  - **Sensor time.** The `GapMonitor` windows, the 30 s epoch of `WakeDetector` and the
 *    one-second bucket of `PreviewEnvelope` are expressed in `SensorEvent` nanoseconds. The
 *    bench's synthetic source keeps the nominal period in sensor time while running fast in wall
 *    time: compressing those windows would measure something other than what they measure. The
 *    naming convention carries the rule — a `...Ms` is wall time and is scaled, a `...Ns` or
 *    `...Us` is sensor or hardware time and is left alone.
 *  - **Remote call timeouts.** `Tasks.await(..., 60 s)` waits on a real layer whose latency does
 *    not compress. Dividing them would produce outright timeouts on an otherwise healthy bench —
 *    a bench that fails for the wrong reason is worse than no bench at all.
 */
object TimeScale {

    /** Real time. The only value the release variant knows how to produce. */
    const val REAL_TIME_DIVISOR = 1L

    /**
     * Scales a **wall-clock** duration.
     *
     * Floored at 1 ms: a non-zero nominal duration must never become zero. A zero delay would turn
     * a rotation condition of "every five minutes" into "on every block", that is, into an
     * entirely different behaviour, and the bench would measure that behaviour while believing it
     * was measuring the other one.
     *
     * A nominal duration that is zero or negative passes through unchanged: it expresses "right
     * now" or "already past", two notions that have no scale.
     */
    fun ms(nominalMs: Long, divisor: Long): Long {
        require(divisor >= 1L) { "invalid time divisor: $divisor" }
        if (nominalMs <= 0L) return nominalMs
        return (nominalMs / divisor).coerceAtLeast(1L)
    }

    /** [ms] applied element by element. The Health Connect retry ladder is one such array. */
    fun ms(nominalMs: LongArray, divisor: Long): LongArray =
        LongArray(nominalMs.size) { ms(nominalMs[it], divisor) }
}
