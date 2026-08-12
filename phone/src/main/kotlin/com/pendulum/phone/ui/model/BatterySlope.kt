package com.pendulum.phone.ui.model

import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.TelemetryPointEntity

/**
 * The battery at eight hours, **extrapolated on a slope** and not read off a last percentage.
 *
 * ### The problem this file solves
 *
 * The second criterion of P1 is "battery above 20 % at eight hours". Until now the database carried
 * only `night_session.batteryPctLast`, the last reported level, and nothing more: neither the
 * starting level nor the series. Only three readings were exact, and [P1Gate] still makes them — an
 * eight hour night states its level at eight hours, a short night already below the threshold has
 * failed, a short night still above says nothing.
 *
 * The third is the most frequent, and it is the one that was blocking. On 3 August 2026 the first
 * real overnight run lasted 32 minutes with `level: 100` at all six measurement steps and
 * `Discharge: 0 mAh` in `batterystats` (`docs/workings/BENCH-LOG.md` §12.4): the percentage is too
 * coarsely quantised to move over half an hour. The only way considered for quantifying it was to
 * keep an ADB link through the night, that is, to leave the watch on its dock — hence to falsify
 * the quantity being measured, since a watch on its dock is a watch on charge.
 *
 * ### What we regress, and why that one
 *
 * `batteryChargeUah` — `BATTERY_PROPERTY_CHARGE_COUNTER`, the coulomb counter. It counts
 * microampere-hours, not steps of one per cent: it moves while a percentage does not, and a slope
 * can be extrapolated. It is that field which makes the battery criterion decidable **without
 * keeping the watch on its dock**, and that is the reason it is in the telemetry block.
 *
 * ### What the extrapolation assumes — and it has to be said, because nothing checks it
 *
 *  1. **That the discharge is affine over the eight hours.** It is not exactly: a lithium-ion
 *     battery discharges a little faster near the ceiling and near the floor, and a night does not
 *     have a constant background load — a waking, a Bluetooth burst, a service restart consume in
 *     jolts. What the slope measures is therefore the **average consumption of the observed
 *     window**, projected as is. Over a short window taken at the start of the night, that is
 *     optimistic in both directions and we do not know which.
 *  2. **That the full capacity is the one estimated here**, by the median of
 *     `charge x 100 / percentage` over the points where both are readable. The device's nominal
 *     capacity is nowhere in the protocol, and estimating it from the two transmitted quantities
 *     avoids making it a constant that would age with the battery. The price is that the
 *     extrapolated percentage inherits the quantisation of the percentage: to within 1 %, that is,
 *     quite enough for a threshold at 20 %, not enough for a reading to the tenth.
 *  3. **That the points under charge say nothing about runtime**, hence that they drop out. It is
 *     the only point where the exclusion is certain rather than reasoned: a counter that goes back
 *     up during a charge would invert the sign of the slope, and §12.4 is precisely a measurement
 *     made with the watch on its dock.
 *
 * ### On how many points it refuses to conclude
 *
 * [MIN_POINTS] points kept, and [MIN_DURATION_H] of observed span between the first and the last.
 * At one point per minute, both conditions describe the same half hour: thirty-one consecutive
 * points cover thirty one-minute intervals, and that is where the odd number comes from.
 *
 * The threshold is not round by taste. Below half an hour, two things dominate the slope and
 * neither is night-time consumption: the counter's own step, which advances in jumps of several
 * tens of microampere-hours, and the start-up transient — the screen has just been switched off,
 * the service has just registered, the sensor has just filled its first FIFO. **A slope on three
 * points is not a slope**, it is a line through three points.
 *
 * The refusal is a `null` and never a cautious value. An extrapolation we do not have the means to
 * make must return "not decidable", which [P1Gate] translates into `UNDETERMINED` — the third state
 * exists for that.
 *
 * Everything is pure: no `Context`, no clock, no I/O.
 */
object BatterySlope {

    /**
     * Minimum points kept.
     *
     * Thirty-one and not thirty: at one point per minute, thirty-one points cover thirty intervals,
     * that is, exactly the half hour [MIN_DURATION_H] requires. The two conditions would sit badly
     * together at thirty — the count would pass, the span would not, and the refusal would be
     * attributed to the wrong cause when reading the code.
     *
     * See the object's KDoc for what this threshold rules out.
     */
    const val MIN_POINTS = 31

    /**
     * Minimum span between the first and the last point kept, in hours.
     *
     * It is not redundant with [MIN_POINTS], even though the two coincide at the current rate: the
     * count says **how many measurements** carry the line, the span says **over what duration** —
     * and it is the span, and it alone, that bounds what an extrapolation to eight hours can be
     * worth. The day the watch published a point every ten seconds, thirty-one points would only be
     * five minutes, and the count would let through a line fitted on the start-up transient. The
     * condition that protects that case is this one.
     */
    const val MIN_DURATION_H = 0.5

    /**
     * A successful extrapolation.
     *
     * @param pointsKept points that actually entered the regression, after removing the points
     *   under charge and the missing counter readings.
     * @param pointsCharging points removed **because they were under charge**. They are counted and
     *   returned: a night half of whose points drop out for that reason is a night spent on its
     *   dock, and that fact is worth being readable next to the number.
     * @param observedSpanH duration between the first and the last point kept.
     * @param slopeUahPerH slope of the least squares line, in microampere-hours per hour. Negative
     *   while discharging, and that is the only sign accepted — see [of].
     * @param capacityUah estimated full capacity, the median of `charge x 100 / percentage`.
     * @param pctAt8h percentage remaining, extrapolated to eight hours **from the start of the
     *   night**, and not from the first point kept: the origin of time is the first telemetry
     *   point, including if it was under charge. It may be negative — a battery that would be flat
     *   before eight hours must say so, and not stop politely at zero.
     */
    data class Extrapolation(
        val pointsKept: Int,
        val pointsCharging: Int,
        val observedSpanH: Double,
        val slopeUahPerH: Double,
        val capacityUah: Double,
        val pctAt8h: Double,
    ) {
        /** Average consumption over the window, in percentage points per hour. Positive. */
        val pctPerHour: Double get() = -slopeUahPerH * 100.0 / capacityUah
    }

    /**
     * @param points a night's telemetry, in any order — the regression does not depend on it.
     * @param targetDurationH the extrapolation horizon. [P1Gate.TARGET_DURATION_H] in practice; it
     *   is a parameter so that the test can check the line on a horizon where the arithmetic can be
     *   done in one's head.
     * @return `null` as soon as one of the refusal conditions is met. All four are explicit in the
     *   body, and none returns a fallback value.
     */
    fun of(points: List<TelemetryPointEntity>, targetDurationH: Double): Extrapolation? {
        if (points.isEmpty()) return null

        // The origin of time is the first point of the night, charge included: the criterion talks
        // about eight hours **of night**, not eight hours of discharge. Taking the first point kept
        // as the origin would shift the horizon by as many minutes as the watch spent on its dock,
        // and the discrepancy would show up nowhere.
        val originNs = points.minOf { it.elapsedRealtimeNs }

        val charging = points.count { it.charging }
        val kept = points.filter {
            !it.charging && it.batteryChargeUah != TelemetryPoint.CHARGE_UNKNOWN
        }
        if (kept.size < MIN_POINTS) return null

        val hoursOf = { p: TelemetryPointEntity ->
            (p.elapsedRealtimeNs - originNs) / 3_600_000_000_000.0
        }
        val span = kept.maxOf(hoursOf) - kept.minOf(hoursOf)
        if (span < MIN_DURATION_H) return null

        // Full capacity, estimated on the points alone where both quantities are readable. A
        // percentage of zero is excluded: it would make a division by zero, and a device at 0 %
        // still recording is not a reading to be believed anyway.
        val capacities = kept
            .filter { it.batteryPct in 1..100 }
            .map { it.batteryChargeUah * 100.0 / it.batteryPct }
        val capacity = median(capacities) ?: return null
        if (capacity <= 0.0) return null

        // Ordinary least squares. Three sums, no library: the line is the only estimator whose
        // assumptions can be written out in full, which is precisely what this object's KDoc has to
        // do.
        val n = kept.size
        val tMean = kept.sumOf(hoursOf) / n
        val yMean = kept.sumOf { it.batteryChargeUah.toDouble() } / n
        var num = 0.0
        var den = 0.0
        for (p in kept) {
            val dt = hoursOf(p) - tMean
            num += dt * (p.batteryChargeUah - yMean)
            den += dt * dt
        }
        if (den <= 0.0) return null
        val slope = num / den

        // A null or positive slope off charge is not good news, it is a reading we do not know how
        // to interpret: either the counter has not moved off its quantisation step, or it counts
        // something other than what we think. Extrapolating on it would announce an infinite
        // runtime, which is the most expensive false green possible here.
        if (slope >= 0.0) return null

        val intercept = yMean - slope * tMean
        val targetCharge = intercept + slope * targetDurationH
        return Extrapolation(
            pointsKept = n,
            pointsCharging = charging,
            observedSpanH = span,
            slopeUahPerH = slope,
            capacityUah = capacity,
            pctAt8h = targetCharge * 100.0 / capacity,
        )
    }

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val t = v.sorted()
        val m = t.size / 2
        return if (t.size % 2 == 1) t[m] else (t[m - 1] + t[m]) / 2.0
    }
}
