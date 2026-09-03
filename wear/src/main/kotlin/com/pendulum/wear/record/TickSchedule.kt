package com.pendulum.wear.record

/**
 * Which of the service's periodic jobs are due, decided on `elapsedRealtime` — the one clock that
 * advances while the SoC is suspended. **Pure**: the clock is passed in, so the property at stake
 * is testable on the JVM.
 *
 * ### The defect this replaces
 *
 * The three periods used to be counted in ticks of a `Handler.postDelayed`, which runs on
 * `uptimeMillis`: "time spent in deep sleep will add an additional delay to execution". In the
 * nominal `WAKEUP 30 s` mode the SoC sleeps between two FIFO bursts and is awake a few per cent of
 * the time, so sixty seconds of *uptime* took on the order of half an hour of wall clock. Every
 * consumer of the minute inherited that stretch: the six stop conditions were evaluated every
 * half hour — a `LOW_BATTERY` seen thirty minutes late is a watch the system switches off before
 * the clean close and the final burst; the 10:00 cut-off landed at 10:30 —, the telemetry points
 * the phone counts as "one per minute" came out one per half hour, and the battery series of the
 * sidecar with them. The claim that "60 s divides the 300 s of the chunk rotation, so a complete
 * chunk carries five points" only held while the SoC never slept: the rotation runs on
 * `elapsedRealtime`, the tick did not. The bench never saw it — it runs on the dock with adb
 * attached, which keeps the processor awake — and neither did the degraded modes, whose wake lock
 * is exactly what makes the two clocks agree.
 *
 * The three periods stay counted multiples of the tick: `DurationsTest.the telemetry rate is the
 * one of the minute tick` locks the six-tick minute to the period announced to the phone.
 *
 * @param tickMs the service tick, `fsync` period.
 * @param startElapsedMs `SystemClock.elapsedRealtime()` at the start of the session: every period
 *   is counted from there, so the first minute is a minute of night and not a minute of uptime.
 */
class TickSchedule(private val tickMs: Long, startElapsedMs: Long) {

    private var lastSyncMs = startElapsedMs
    private var lastUiMs = startElapsedMs
    private var lastMinuteMs = startElapsedMs

    /**
     * What is due at [nowElapsedMs], and marks it as done: the caller must run everything it gets.
     *
     * [Due.minuteMs] is the **real** elapsed time since the previous minute job, or zero when it
     * is not due. It is what the off-body counter has to add: a minute job that runs late must
     * account for the time it covers, not for a nominal sixty seconds.
     */
    fun due(nowElapsedMs: Long): Due {
        val sync = nowElapsedMs - lastSyncMs >= tickMs
        if (sync) lastSyncMs = nowElapsedMs
        val ui = nowElapsedMs - lastUiMs >= 3 * tickMs
        if (ui) lastUiMs = nowElapsedMs
        val sinceMinute = nowElapsedMs - lastMinuteMs
        val minuteMs = if (sinceMinute >= 6 * tickMs) sinceMinute else 0L
        if (minuteMs > 0) lastMinuteMs = nowElapsedMs
        return Due(sync = sync, ui = ui, minuteMs = minuteMs)
    }

    data class Due(val sync: Boolean, val ui: Boolean, val minuteMs: Long) {
        val any: Boolean get() = sync || ui || minuteMs > 0
    }
}
