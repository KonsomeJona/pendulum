package com.pendulum.wear.record

import com.pendulum.format.wire.StopReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The automatic stop conditions and their bounds.
 *
 * Each condition closes the night cleanly — file closed, final burst urgent. The defect we want
 * to make impossible is twofold: a condition that fires too early truncates a valid night (the
 * false contact of the magnetic charger, the cut-off time on a recording that has only just
 * started), a condition that fires too late lets the system kill the service and the night ends
 * as "the watch died at 3 a.m." instead of "everything was sent".
 */
class StopConditionsTest {

    private companion object {
        const val START = 0L
        const val TWO_HOURS = 2 * 3_600_000L
    }

    /**
     * The durations are passed in **nominal**, never read from `Durations.ACTIVE`.
     *
     * This file asserts bounds to the millisecond — "59,999 ms of charging carries on, 60,000
     * stops". Letting them follow the divisor of the compiled variant would make all these
     * assertions fail as soon as a bench were built with a compressed scale, for a reason that
     * has nothing to do with what they check: the stop logic itself does not depend on the speed
     * at which time passes.
     */
    private fun conditions(stopAt: Int = 600) = StopConditions(
        startWallMs = START,
        stopAtLocalMinutes = stopAt,
        chargingDebounceMs = 60_000L,
        maxDurationMs = 10 * 3_600_000L,
        minDelayBeforeCutoffMs = 3_600_000L,
    )

    /** A healthy night at 3 a.m.: no condition should present itself. */
    private fun StopConditions.eval(
        nowMs: Long = TWO_HOURS,
        isCharging: Boolean = false,
        batteryPct: Int = 50,
        freeBytes: Long = 1L shl 30,
        localMinutes: Int = 180,
        wakeRatio: Double = 0.0,
    ): StopReason? = evaluate(nowMs, isCharging, batteryPct, freeBytes, localMinutes, wakeRatio)

    @Test
    @DisplayName("a healthy night does not stop")
    fun `nominal case`() {
        assertThat(conditions().eval()).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Charger: 60 s of sustained charging
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("charging only stops after 60 sustained seconds, to the millisecond")
    fun `charger debounce`() {
        val c = conditions()

        assertThat(c.eval(nowMs = TWO_HOURS, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 59_999, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 60_000, isCharging = true))
            .isEqualTo(StopReason.CHARGING)
    }

    @Test
    @DisplayName("a false contact of the magnetic charger does not cut the night short")
    fun `a false contact resets the debounce`() {
        val c = conditions()

        // The sleeper rolls onto the charger left on the bedside table: brief contact, break,
        // contact again. Without a reset, the brief contacts would add up and the night would
        // stop at the first one to push the total over — for a watch never really put down.
        assertThat(c.eval(nowMs = TWO_HOURS, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 30_000, isCharging = false)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 40_000, isCharging = true)).isNull()
        // 60 s since the FIRST contact, but 59.999 s since the second: no stop.
        assertThat(c.eval(nowMs = TWO_HOURS + 99_999, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 100_000, isCharging = true))
            .isEqualTo(StopReason.CHARGING)
    }

    // -------------------------------------------------------------------------------------
    // Battery
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("5 % stops, 6 % carries on: the close must come before the system dies")
    fun `low battery bound`() {
        assertThat(conditions().eval(batteryPct = 6)).isNull()
        assertThat(conditions().eval(batteryPct = 5)).isEqualTo(StopReason.LOW_BATTERY)
        assertThat(conditions().eval(batteryPct = 0)).isEqualTo(StopReason.LOW_BATTERY)
    }

    @Test
    @DisplayName("an unknown battery level never stops anything")
    fun `unknown battery`() {
        // BatteryManager answers -1 when the value is not available. Treating "unknown" as
        // "empty" would cut whole nights short on a single failed read.
        assertThat(conditions().eval(batteryPct = -1)).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Maximum duration
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("exactly ten hours stops; one millisecond less carries on")
    fun `maximum duration bound`() {
        val tenHours = 10 * 3_600_000L
        assertThat(conditions().eval(nowMs = START + tenHours - 1)).isNull()
        assertThat(conditions().eval(nowMs = START + tenHours))
            .isEqualTo(StopReason.MAX_DURATION)
    }

    // -------------------------------------------------------------------------------------
    // Cut-off time
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the cut-off time only counts if the night is more than an hour old")
    fun `cut-off time and a night that has only just started`() {
        // Recording started at 11 a.m. with a cut-off at 10 a.m.: local time is past the cut-off
        // from the very first millisecond. Without the one-hour guard rail, the night would stop
        // before it had existed.
        assertThat(conditions().eval(nowMs = START + 1, localMinutes = 660)).isNull()
        // Exactly one hour is not enough: the bound is strictly beyond.
        assertThat(conditions().eval(nowMs = START + 3_600_000, localMinutes = 660)).isNull()
        assertThat(conditions().eval(nowMs = START + 3_600_001, localMinutes = 660))
            .isEqualTo(StopReason.TIME_LIMIT)
    }

    @Test
    @DisplayName("the cut-off minute itself stops; the minute before carries on")
    fun `cut-off time bound`() {
        assertThat(conditions().eval(localMinutes = 599)).isNull()
        assertThat(conditions().eval(localMinutes = 600)).isEqualTo(StopReason.TIME_LIMIT)
    }

    // -------------------------------------------------------------------------------------
    // Waking
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("80 % of active epochs carries on; strictly beyond, the person is up")
    fun `waking ratio bound`() {
        assertThat(conditions().eval(wakeRatio = 0.80)).isNull()
        assertThat(conditions().eval(wakeRatio = 0.801)).isEqualTo(StopReason.WAKE_DETECTED)
    }

    // -------------------------------------------------------------------------------------
    // Disk
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("below 50 MB free, closing now is better than writing until the wall")
    fun `disk space bound`() {
        assertThat(conditions().eval(freeBytes = StopConditions.MIN_FREE_BYTES)).isNull()
        assertThat(conditions().eval(freeBytes = StopConditions.MIN_FREE_BYTES - 1))
            .isEqualTo(StopReason.DISK_FULL)
        assertThat(conditions().eval(freeBytes = 0)).isEqualTo(StopReason.DISK_FULL)
    }

    // -------------------------------------------------------------------------------------
    // Priority: the first condition to present itself wins
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("sustained charging comes before low battery: the reason states the first cause")
    fun `charging priority`() {
        val c = conditions()
        c.eval(nowMs = TWO_HOURS, isCharging = true) // the contact starts the debounce

        // On the charger at 3 %: the night is over because the user put the watch down, not
        // because the battery is empty. The sidecar will tell the wrong story if the order
        // changes — and it is this reason that the phone screen will show on waking.
        assertThat(c.eval(nowMs = TWO_HOURS + 60_000, isCharging = true, batteryPct = 3))
            .isEqualTo(StopReason.CHARGING)
    }

    @Test
    @DisplayName("detected waking comes before a full disk")
    fun `waking takes priority over the disk`() {
        assertThat(conditions().eval(wakeRatio = 0.9, freeBytes = 0))
            .isEqualTo(StopReason.WAKE_DETECTED)
    }
}

/**
 * The real "watch removed" detector: locomotion. The PPG's off-body does not exist here, and that
 * is deliberate — at the ankle it would read "not worn" permanently and would cut every night at
 * its first minute.
 */
class WakeDetectorTest {

    private companion object {
        const val T0 = 1_000_000_000L
        const val ACTIVE_RMS = 3.0 // walking: well beyond the locomotion threshold of 1.5 m/s^2
        const val QUIET_RMS = 0.1 // sleep
    }

    @Test
    @DisplayName("the ratio stays at zero as long as no 30 s epoch has closed")
    fun `no verdict without a complete epoch`() {
        val d = WakeDetector()
        var ts = T0
        repeat(29) {
            d.onSecond(ACTIVE_RMS, ts)
            ts += 1_000_000_000L
        }

        // 29 s of restlessness do not make a getting-up: ruling before the first epoch has closed
        // means stopping the night over a single turn in bed.
        assertThat(d.ratio).isEqualTo(0.0)
    }

    @Test
    @DisplayName("an epoch flips on a strict majority of active seconds, not on a tie")
    fun `majority bound within the epoch`() {
        // The verdict only exists once the window holds its 20 epochs: the first epoch carries
        // the majority under test, the 19 that follow are sleep epochs. 31 calls close the first
        // epoch, then 30 per epoch. 16 active seconds out of 31 = majority.
        val active = WakeDetector()
        feed(active, activeSeconds = 16, totalSeconds = 31 + 19 * 30)
        assertThat(active.ratio).isCloseTo(0.05, within(1e-9)) // 1 active epoch out of 20

        // 15 out of 31: a rounded tie is not enough, the epoch stays a sleep epoch.
        val quiet = WakeDetector()
        feed(quiet, activeSeconds = 15, totalSeconds = 31 + 19 * 30)
        assertThat(quiet.ratio).isEqualTo(0.0)
    }

    @Test
    @DisplayName("the walk to bed just after START never stops the night")
    fun `no verdict before the window is full`() {
        // The defect this pins: the ratio used to divide by the number of epochs closed *so far*,
        // so the very first active epoch read 1.0, and the first minute tick after START —
        // pressed on the watch, then a walk to the bed at several m/s^2 — closed the night as
        // WAKE_DETECTED after one minute. The sleeper only found out in the morning.
        val d = WakeDetector()
        var ts = T0

        // START, then two minutes of walking to the bed: 4 epochs, every one of them active.
        repeat(121) {
            d.onSecond(ACTIVE_RMS, ts)
            ts += 1_000_000_000L
        }
        assertThat(d.ratio).isEqualTo(0.0)

        // Then the person lies down. The window fills with sleep epochs and the ratio never
        // reaches the 0.80 of StopConditions — at its fullest, 4 active epochs out of 20.
        repeat(16 * 30) {
            d.onSecond(QUIET_RMS, ts)
            ts += 1_000_000_000L
            assertThat(d.ratio).isLessThan(0.80)
        }
        assertThat(d.ratio).isCloseTo(0.20, within(1e-9))
    }

    @Test
    @DisplayName("a real getting-up crosses the 0.80 threshold only after sustained locomotion")
    fun `getting-up scenario`() {
        val d = WakeDetector()
        var ts = T0

        // A quiet end of night: 3 sleep epochs...
        repeat(91) { // the 1st epoch counts 31 calls, the following ones 30
            d.onSecond(QUIET_RMS, ts)
            ts += 1_000_000_000L
        }
        // ... then the person gets up and stays up: 17 locomotion epochs.
        repeat(17 * 30) {
            d.onSecond(ACTIVE_RMS, ts)
            ts += 1_000_000_000L
        }

        // 17 active epochs out of the 20 in the 10 min window: 0.85, beyond the 0.80 threshold of
        // StopConditions. A one-minute stir will never get there — that is the whole difference
        // between "went to the toilet" and "up for good".
        assertThat(d.ratio).isCloseTo(0.85, within(1e-9))
    }

    /** Feeds [d] at 1 Hz: first [activeSeconds] active seconds, then quiet. */
    private fun feed(d: WakeDetector, activeSeconds: Int, totalSeconds: Int) {
        var ts = T0
        repeat(totalSeconds) { i ->
            d.onSecond(if (i < activeSeconds) ACTIVE_RMS else QUIET_RMS, ts)
            ts += 1_000_000_000L
        }
    }
}
