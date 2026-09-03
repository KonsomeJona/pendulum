package com.pendulum.wear.record

import com.pendulum.format.wire.StopReason
import com.pendulum.wear.time.Bench
import com.pendulum.wear.time.Durations

/**
 * Automatic stop conditions. **Pure**: the state is passed in, nothing is read from the system
 * here, so the six branches and their debounces are testable on the JVM.
 *
 * The first condition to present itself wins, and its `stopReason` goes into the sidecar and into
 * `/pendulum/session`. Each one closes the night **cleanly**: the current file is closed, the
 * final burst is urgent. That is the difference between "the watch died at 3 a.m." and "the watch
 * closed at 3 a.m. and sent everything".
 *
 * **Off-body never stops anything** and therefore does not appear here. Off-body detection relies
 * on the PPG and on wrist capacitance; at the ankle its behaviour is undocumented and it very
 * probably reads "not worn" permanently. Trusting it would cut every night at its first minute.
 * It is logged (sidecar, `FLAG_OFF_BODY`) and never acted upon. The real "watch removed" detector
 * is [wakeRatio], which measures locomotion — that is, the event that actually matters ("the
 * person has got up"), not an invalid proxy.
 */
class StopConditions(
    private val startWallMs: Long,
    /** Minutes since local midnight. Default 10:00. */
    private val stopAtLocalMinutes: Int,
    /**
     * The three durations, as parameters rather than read from the catalogue deep inside
     * [evaluate].
     *
     * This class is pure and so are its tests: they assert bounds to the millisecond ("59,999 ms
     * of charging carries on, 60,000 stops"). Making them depend on the compiled variant's
     * divisor would make a business-logic test fail because a bench has been configured
     * differently — that is, for a reason that has nothing to do with what it checks.
     */
    private val chargingDebounceMs: Long = CHARGING_DEBOUNCE_MS,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val minDelayBeforeCutoffMs: Long = MIN_DELAY_BEFORE_CUTOFF_MS,
    /**
     * Bench override: do not stop the night when the watch is on the charger. See
     * [com.pendulum.wear.time.Bench.IGNORE_CHARGER] — `false` in release, where the constant is
     * known at compile time and the branch disappears.
     *
     * A parameter and not a direct read, like the three durations above and for the same reason:
     * this class's tests assert bounds to the millisecond and must not depend on the compiled
     * variant.
     */
    private val ignoreCharger: Boolean = Bench.IGNORE_CHARGER,
) {

    companion object {
        /** A magnetic charger produces brief false contacts: 60 s of sustained charging. */
        val CHARGING_DEBOUNCE_MS = Durations.ACTIVE.chargingDebounceMs

        val MAX_DURATION_MS = Durations.ACTIVE.sessionMaxDurationMs

        /** See [evaluate]: the cut-off time only counts past this delay since the start. */
        val MIN_DELAY_BEFORE_CUTOFF_MS = Durations.ACTIVE.minDelayBeforeCutoffMs

        /** A clean close *before* the system kills anything. */
        const val LOW_BATTERY_PCT = 5

        const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }

    private var chargingSinceMs = 0L

    /**
     * @param localMinutes minutes since midnight, in local time.
     * @param wakeRatio fraction of the 30 s epochs above the locomotion threshold over the last
     *   ten minutes, within `0..1`.
     */
    fun evaluate(
        nowMs: Long,
        isCharging: Boolean,
        batteryPct: Int,
        freeBytes: Long,
        localMinutes: Int,
        wakeRatio: Double,
    ): StopReason? {
        // The bench override applies **here only**: the debounce counter keeps running, only the
        // stop is withheld. A bench that did not count charging would no longer be testing the
        // same code as a real night.
        if (isCharging) {
            if (chargingSinceMs == 0L) chargingSinceMs = nowMs
            if (nowMs - chargingSinceMs >= chargingDebounceMs && !ignoreCharger) {
                return StopReason.CHARGING
            }
        } else {
            chargingSinceMs = 0L
        }

        if (batteryPct in 0..LOW_BATTERY_PCT) return StopReason.LOW_BATTERY
        if (nowMs - startWallMs >= maxDurationMs) return StopReason.MAX_DURATION
        // The cut-off time only counts if the night started before it: a recording begun at
        // 11 a.m. must not stop at the very next millisecond.
        if (localMinutes >= stopAtLocalMinutes && nowMs - startWallMs > minDelayBeforeCutoffMs) {
            return StopReason.TIME_LIMIT
        }
        if (wakeRatio > 0.80) return StopReason.WAKE_DETECTED
        if (freeBytes in 0 until MIN_FREE_BYTES) return StopReason.DISK_FULL
        return null
    }
}

/**
 * Waking detection: more than 80 % of the 30 s epochs above the locomotion threshold over a
 * sliding ten minutes. **Pure**, fed by the RMS envelope already computed for the preview — no
 * extra computation in the sensor loop.
 */
class WakeDetector {

    private companion object {
        /** 20 epochs of 30 s = the ten-minute sliding window. */
        const val WINDOW_EPOCHS = 20
    }

    /** RMS beyond which an epoch counts as locomotion, in m/s^2. */
    private val locomotionThreshold = 1.5

    private val epochs = ArrayDeque<Boolean>()
    private var epochStartNs = 0L
    private var above = 0
    private var total = 0

    /** Fraction of active epochs over the window, within `0..1`. Stays at zero until the window
     *  holds its twenty epochs. */
    var ratio: Double = 0.0
        private set

    fun onSecond(rms: Double, tsNs: Long) {
        if (epochStartNs == 0L) epochStartNs = tsNs
        total++
        if (rms > locomotionThreshold) above++
        if (tsNs - epochStartNs >= 30_000_000_000L) {
            epochs.addLast(above > total / 2)
            if (epochs.size > WINDOW_EPOCHS) epochs.removeFirst()
            // No verdict on a partial window. The ratio used to divide by the number of epochs
            // closed *so far*, bounded above by twenty but never below: the very first active
            // epoch read 1.0, and the first minute tick after START — pressed on the watch, then
            // the walk to the bed at several m/s^2 at the ankle — closed the night as
            // WAKE_DETECTED after one minute, cleanly, with nobody awake to see it. The same held
            // at 2.5 min for five active epochs (a trip to the bathroom right after START). The
            // window is sliding only once it is full: before that, the "80 % of ten minutes" the
            // KDoc promises does not exist, and neither does the verdict.
            ratio = if (epochs.size < WINDOW_EPOCHS) 0.0
            else epochs.count { it }.toDouble() / WINDOW_EPOCHS
            epochStartNs = tsNs
            above = 0
            total = 0
        }
    }
}
