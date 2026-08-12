package com.pendulum.wear.record

/**
 * Monitoring of the acquisition's timing integrity, and the auto-degradation decision.
 * **Pure**: nothing but nanosecond `Long`s as input, so entirely testable on the JVM.
 *
 * > **A gap is measured on `SensorEvent.timestamp` differences. Never on arrival time.**
 *
 * This is the one rule in this file that is not negotiable. In batched mode the samples arrive
 * in bursts: thirty seconds of silence then 1,500 events delivered at once is not a gap, it is
 * nominal operation. A rule founded on delivery time — the `elapsedRealtimeNanos` between two
 * `onSensorChanged` calls — therefore fires **permanently** in the nominal case. And since the
 * escalation answers gaps by taking a `PARTIAL_WAKE_LOCK`, such a rule does not merely produce a
 * false positive in a log: it silently turns every night into a night under wake lock, spends
 * 65 % of the battery, and invalidates the battery-life measurement that the first test phase
 * exists precisely to obtain. The failure then looks like "batching does not work on this
 * device" when what does not work is the monitor.
 *
 * Two admissible signals, both computed on sensor timestamps:
 *  - **intra-batch**: interval between consecutive samples greater than 3 times the nominal
 *    period;
 *  - **window**: sample-count deficit over a sliding 60 s of sensor time
 *    (`received < 0.95 x expected`), which catches a gap falling between two batches without
 *    ever looking at the time at which the batches arrived.
 */
class GapMonitor(rateHz: Int) {

    private var periodNs: Long = 1_000_000_000L / rateHz

    /** A gap counts towards the escalation beyond 3 s: below that, the signal stays usable. */
    private val bigGapNs = 3_000_000_000L

    private val escalationWindowNs = 600_000_000_000L // 10 min of sensor time
    private val measureWindowNs = 60_000_000_000L

    private var lastTsNs = 0L
    private var windowStartNs = 0L
    private var windowCount = 0

    /**
     * Missing samples **already attributed** by the intra-batch signal in the current window.
     *
     * Without this counter, one and the same physical gap was counted twice: once on arrival by
     * the intra-batch signal, a second time when the window closed, because the samples it
     * carried away show up as a deficit. Measured consequence: `gapCount` and `gapTotalMs`
     * doubled on those gaps, and above all **two** physical gaps within a single window were
     * enough to climb one step where the rule announces three.
     *
     * This was not a cosmetic imprecision. Every step takes a `PARTIAL_WAKE_LOCK`: escalating one
     * and a half times too fast means spending the night under wake lock, spending 65 % of the
     * battery, and invalidating the battery-life measurement that phase P1 exists to obtain — the
     * exact cost that this file's KDoc says it wants to avoid.
     *
     * The window stays what its documentation announces: **a catch-up for what the intra-batch
     * signal cannot see** — a gap falling between two batches — and not an amplifier of what it
     * has already seen.
     */
    private var missingAlreadyCounted = 0
    private val bigGapTimestamps = ArrayDeque<Long>()

    /**
     * Dispersion accumulators, in **microseconds** and not in nanoseconds.
     *
     * The choice of unit is not cosmetic: variance is computed as a sum of squares, and a 3 s gap
     * is worth 3e9 ns, whose square is 9e18 — within a factor of 1.03 of the largest `Long`. A
     * single big gap was therefore enough to overflow the accumulator and to put a negative value
     * under the square root of the standard deviation. In microseconds the same gap is worth
     * 9e12, and a whole window of gaps does not come close.
     */
    private var sumDtUs = 0L
    private var sumDtUs2 = 0L
    private var nbDt = 0
    private var maxDtUs = 0L

    /** Number of gaps detected, all signals combined. */
    var gapCount: Int = 0
        private set

    /** Cumulative missing duration, in milliseconds. */
    var gapTotalMs: Long = 0
        private set

    /** Degradation step reached, from 0 to 3. Never goes back down. */
    var step: Int = 0
        private set

    /** `fs` actually delivered over the last 60 s window. 0 as long as none has closed. */
    var measuredRateHz: Double = 0.0
        private set

    /**
     * Standard deviation of the inter-sample intervals over the last closed window, in
     * microseconds. 0 as long as no window has closed.
     *
     * **The mean does not say what one believes it says.** [measuredRateHz] answers "how many
     * samples per second", and a rate that alternates 10 ms and 30 ms yields exactly 50 Hz — so a
     * perfect `fs`, so `rateDeviates` false, so no signal anywhere. Yet the format has no
     * per-sample timestamp: it **interpolates linearly** between `tFirstNs` and `tLastNs`, and
     * that interpolation is wrong in proportion to how dispersed the intervals are. So it is
     * regularity, and not the mean, that decides how a movement is dated.
     *
     * A measurement and not an action: nothing here triggers an escalation. The dispersion goes
     * to the phone in the telemetry, where it explains a dating, and that is all we know how to
     * do with it today.
     */
    var jitterStdUs: Double = 0.0
        private set

    /**
     * Worst interval between two consecutive samples in the last closed window, in microseconds.
     * It is the bound on the dating error within that window, where [jitterStdUs] only gives its
     * order of magnitude.
     */
    var maxIntervalUs: Long = 0
        private set

    /** Last `SensorEvent.timestamp` seen. 0 before the first sample and after a sensor
     *  re-registration. It anchors the telemetry on the samples' time base. */
    val lastTimestampNs: Long get() = lastTsNs

    /**
     * True when the measured `fs` deviates by more than 5 % from the nominal. The requested rate
     * is not the delivered rate — 50 Hz commonly comes out at 50.3 or 52.6 Hz — and another
     * application starting an exercise session in the middle of the night can change it. A wrong
     * `fs` shifts every filter and every movement duration of the analysis chain.
     */
    var rateDeviates: Boolean = false
        private set

    /** Step to apply, consumed by the service. `null` as long as there is nothing new. */
    var pendingStep: Int? = null
        private set

    /**
     * @return true if a gap precedes this sample, in which case the block starting here must
     *   carry `FLAG_GAP_BEFORE`.
     */
    fun onSample(tsNs: Long): Boolean {
        if (lastTsNs == 0L) {
            lastTsNs = tsNs
            windowStartNs = tsNs
            windowCount = 1
            return false
        }

        // A timestamp going backwards. Android's sensor layers do it sometimes in batched mode,
        // and letting it through corrupts everything that follows: `spanNs` becomes negative when
        // the window closes, so `expected` does too, so the deficit comparison never fires again
        // — and `windowStartNs` restarts in the past, which the next window does not recover
        // from. The sample is ignored rather than corrected: we do not know where it should go.
        if (tsNs <= lastTsNs) return false

        val dt = tsNs - lastTsNs
        lastTsNs = tsNs
        windowCount++

        val dtUs = dt / 1_000
        sumDtUs += dtUs
        sumDtUs2 += dtUs * dtUs
        nbDt++
        if (dtUs > maxDtUs) maxDtUs = dtUs

        var gapHere = false
        if (dt > 3 * periodNs) {
            gapHere = true
            gapCount++
            gapTotalMs += (dt - periodNs) / 1_000_000
            missingAlreadyCounted += ((dt - periodNs) / periodNs).toInt()
            if (dt >= bigGapNs) recordBigGap(tsNs)
        }

        if (tsNs - windowStartNs >= measureWindowNs) {
            closeWindow(tsNs)
        }
        return gapHere
    }

    /** After a sensor re-registration at a different rate. */
    fun onRateChanged(rateHz: Int) {
        periodNs = 1_000_000_000L / rateHz
        // The re-registration breaks time continuity: we restart cleanly rather than count a gap
        // that we caused ourselves.
        lastTsNs = 0L
        windowStartNs = 0L
        windowCount = 0
        missingAlreadyCounted = 0
        reset()
    }

    fun consumePendingStep(): Int? = pendingStep.also { pendingStep = null }

    private fun closeWindow(tsNs: Long) {
        val spanNs = tsNs - windowStartNs
        measuredRateHz = windowCount * 1e9 / spanNs
        if (nbDt > 0) {
            val mean = sumDtUs.toDouble() / nbDt
            // `coerceAtLeast(0.0)`: the variance computed as a difference of moments can come out
            // slightly negative through cancellation when all the intervals are identical, and a
            // square root of a negative would give NaN — that is, an unreadable standard
            // deviation in precisely the healthiest case there is.
            val variance = (sumDtUs2.toDouble() / nbDt - mean * mean).coerceAtLeast(0.0)
            jitterStdUs = Math.sqrt(variance)
            maxIntervalUs = maxDtUs
        }
        val nominal = 1e9 / periodNs
        rateDeviates = Math.abs(measuredRateHz - nominal) / nominal > 0.05

        val expected = (spanNs / periodNs).toInt()

        // The **unexplained** deficit: what the window observes, minus what the intra-batch signal
        // has already attributed. `coerceAtLeast(0)` is not a stylistic precaution — without it, a
        // sensor delivering slightly faster than its nominal rate (51 Hz delivered for 50
        // requested) yields `windowCount > expected`, so a negative `missing`, so a `gapTotalMs`
        // that **decreases**. The lost-time counter started winning time back.
        val missingUnseen = (expected - windowCount - missingAlreadyCounted).coerceAtLeast(0)
        if (missingUnseen > 0.05 * expected) {
            val missingNs = missingUnseen * periodNs
            gapCount++
            gapTotalMs += missingNs / 1_000_000
            // A window deficit greater than 3 s is worth a big gap: it is simply spread
            // differently over time, no less real.
            if (missingNs >= bigGapNs) recordBigGap(tsNs)
        }

        windowStartNs = tsNs
        windowCount = 0
        missingAlreadyCounted = 0
        reset()
    }

    /** Empties the dispersion accumulators. The next window must owe nothing to the previous. */
    private fun reset() {
        sumDtUs = 0
        sumDtUs2 = 0
        nbDt = 0
        maxDtUs = 0
    }

    /** Three big gaps within a sliding 10 min window climb one step. */
    private fun recordBigGap(tsNs: Long) {
        bigGapTimestamps.addLast(tsNs)
        while (bigGapTimestamps.isNotEmpty() && tsNs - bigGapTimestamps.first() > escalationWindowNs) {
            bigGapTimestamps.removeFirst()
        }
        if (bigGapTimestamps.size >= 3 && step < 3) {
            step++
            pendingStep = step
            // The counter restarts at zero: the next step is earned over the ten minutes that
            // follow, otherwise the same gaps would trigger all three steps in a row.
            //
            // Known objection, and rejected: after the escalation three **new** gaps are needed,
            // which slows the climb when the hardware fails outright — four big gaps in five
            // minutes only produce one step. That is accepted. Every step takes one more wake
            // lock, and the cost of escalating too fast is a whole night of battery plus an
            // invalidated battery-life measurement; the cost of escalating too slowly is a few
            // more gaps in a signal that the analysis already knows how to flag. The two are not
            // equivalent. If a real campaign shows the opposite, this is where to come back — with
            // the measurement, not with intuition.
            bigGapTimestamps.clear()
        }
    }
}
