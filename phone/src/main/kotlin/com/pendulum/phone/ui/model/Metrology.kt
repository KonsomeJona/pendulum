package com.pendulum.phone.ui.model

import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.ui.chart.BatteryGauge
import com.pendulum.phone.ui.chart.Interval
import com.pendulum.phone.ui.chart.MetrologySpec
import com.pendulum.phone.ui.chart.TimestampLevel
import com.pendulum.phone.ui.chart.TimestampTier
import com.pendulum.phone.ui.chart.WearState
import android.content.res.Resources
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.text.text
import java.util.Locale

/**
 * What a night's telemetry says about the device — aggregated once, read in three places.
 *
 * The metrology band, the "why this number" panel and the battery criterion of the P1 gate all read
 * the same points. Aggregating them three times would give three numbers that would end up
 * differing, and the difference would land on quantities that decide the detection threshold.
 *
 * Everything is pure: no `Context`, no clock, no I/O. The format's sentinels are interpreted **here
 * and only once** — `TelemetryAdapter` lets them through as they are, precisely so that there is
 * only one convention to hold.
 */
object Metrology {

    /**
     * Beyond two milliseconds of standard deviation, a sample's timestamping is no longer fine.
     *
     * At 50 Hz the period is 20 ms: two milliseconds are a tenth of a period, that is, the order of
     * magnitude below which the format's linear interpolation costs nothing. Above it, it starts to
     * cost, and the next threshold is the period itself.
     */
    const val FINE_JITTER_US = 2_000

    /** Ten milliseconds: half a period at 50 Hz. Beyond that, the instant becomes debatable. */
    const val MEDIUM_JITTER_US = 10_000

    /**
     * A write freeze is notable from **one sampling period** upwards.
     *
     * Below that, the processor took back control before the next interrupt arrived and no sample
     * can have been missed because of it. Above, it may have been — it is `fsyncMaxUs` and not
     * `fsyncTotalUs` that answers that question, because it is the worst freeze that explains a
     * missed interrupt at a precise instant, not the total.
     */
    fun freezeThresholdUs(nominalRateHz: Int): Long =
        if (nominalRateHz <= 0) 20_000L else (1_000_000L / nominalRateHz)

    /**
     * The numbers the "why this number" panel shows.
     *
     * @param clippedSamples total of the samples that touched the sensor's dynamic range. This is
     *   **not** the format's saturation at 16 g: a 4 or 8 g sensor clips at half or a quarter of
     *   what the format can encode, and clipping was until now completely invisible on replay.
     * @param medianJitterUs median of the interval standard deviations. Median and not mean: a
     *   single catastrophic window would pull the mean and suggest a whole night was badly
     *   timestamped.
     * @param worstIntervalUs the worst gap between two consecutive samples over the whole night. It
     *   is what bounds the timestamping error; the mean hides it.
     */
    data class Summary(
        val points: Int,
        val clippedPoints: Int,
        val clippedSamples: Int,
        val medianJitterUs: Int,
        val worstIntervalUs: Long,
        val freezes: Int,
        val worstFreezeUs: Long,
    ) {
        val hasSomethingToSay: Boolean
            get() = points > 0
    }

    fun summary(points: List<TelemetryPointEntity>, nominalRateHz: Int): Summary? {
        if (points.isEmpty()) return null
        val freezeThreshold = freezeThresholdUs(nominalRateHz)
        return Summary(
            points = points.size,
            clippedPoints = points.count { it.clippedSamples > 0 },
            clippedSamples = points.sumOf { it.clippedSamples },
            medianJitterUs = integerMedian(points.map { it.jitterStdUs }),
            worstIntervalUs = points.maxOf { it.maxIntervalUs },
            freezes = points.count { it.fsyncMaxUs >= freezeThreshold },
            worstFreezeUs = points.maxOf { it.fsyncMaxUs },
        )
    }

    /**
     * The device state band, on the time axis of the two bands above it.
     *
     * ### The anchoring, and why it goes through `sensorTsNs`
     *
     * Every point is placed at `startMs + (sensorTsNs - t0Ns)`, that is, on the **sample time base**
     * — the only one that timestamps the movements. Going through the wall clock or the monotonic
     * clock would require a conversion which §12.4 of the bench log shows to drift:
     * `SensorEvent.timestamp` is not guaranteed equal to `elapsedRealtimeNanos`, some manufacturers
     * excluding suspend time from it. A shift of a few minutes between the metrology band and the
     * envelope would make a clipping be attributed to a neighbouring movement, which is exactly the
     * error the band exists to prevent.
     *
     * The points whose `sensorTsNs` is 0 — no sample seen at the time of the point, hence the very
     * first ones of a session — are **excluded from the time channels**: we do not know where to put
     * them, and putting them at the start by default would invent a state. They are still counted in
     * [Summary] and in the gauge, which do not depend on the axis.
     *
     * ### An interval covers the minute that *precedes* its point
     *
     * The format's counters count from the previous point. A point's state block therefore extends
     * from the previous point to the current one. Laying the interval down the other way round would
     * shift the whole band by a minute — the order of magnitude of a movement.
     *
     * @param startMs, endMs the bounds of the axis, already shifted to local wall clock time by the
     *   caller, as for the two other bands.
     * @param t0Ns `tFirstNs` of the night's first block: the origin of the sensor time base. `null`
     *   when no chunk is in the database, in which case nothing can be placed and the band returns
     *   its unavailability sentence.
     * @param res the resources, because the spec carries **already resolved** strings.
     *
     * This is the only function in this file that is not pure, and the reason lies downstream: the
     * band is painted by an extension of `DrawScope`, which has neither composition nor `Context`.
     * Making the spec carry a `UiText` would force the drawing to resolve in the middle of a
     * `Canvas`. Everything that **decides** — [summary], [wearState], the tiers, the gauge — stays
     * pure and testable; only the wording goes through here.
     */
    fun spec(
        session: NightSessionEntity,
        points: List<TelemetryPointEntity>,
        startMs: Long,
        endMs: Long,
        t0Ns: Long?,
        res: Resources,
    ): MetrologySpec {
        val gauge = gauge(points, res)
        val placed = if (t0Ns == null) emptyList() else points
            .filter { it.sensorTsNs > 0L }
            .sortedBy { it.sensorTsNs }
            .map { it to startMs + (it.sensorTsNs - t0Ns) / 1_000_000L }

        val freezeThreshold = freezeThresholdUs(session.nominalRateHz)

        // The interval a point covers: from the previous point to itself, clamped to the start of
        // the night for the first one.
        val intervals = placed.mapIndexed { i, (p, ms) ->
            val previous = if (i == 0) startMs else placed[i - 1].second
            p to Interval(previous.coerceAtLeast(startMs), ms)
        }

        return MetrologySpec(
            startMs = startMs,
            endMs = endMs,
            wearState = wearState(points),
            offWrist = merge(
                intervals.filter { it.first.offBody == TelemetryPoint.OFF_BODY_REMOVED }.map { it.second }
            ),
            charging = merge(intervals.filter { it.first.charging }.map { it.second }),
            timestamping = tiers(intervals),
            clipping = intervals.filter { it.first.clippedSamples > 0 }.map { it.second.endMs },
            freezes = intervals.filter { it.first.fsyncMaxUs >= freezeThreshold }.map { it.second.endMs },
            battery = gauge,
            points = points.size,
            unavailableText = res.getString(R.string.chart_metrology_unavailable),
            accessibleDescription = description(res, points, gauge, session.nominalRateHz),
        )
    }

    // -------------------------------------------------------------------------------------
    // The channels, one by one
    // -------------------------------------------------------------------------------------

    /**
     * `NO_SENSOR` as soon as **all** the points announce it: a device does not acquire an off-body
     * detector in the middle of the night. A mixture is therefore a partial reading, and it is
     * treated as such — the segments announced as "removed" are drawn, the rest is not.
     */
    private fun wearState(points: List<TelemetryPointEntity>): WearState = when {
        points.isEmpty() -> WearState.NO_SENSOR
        points.all { it.offBody == TelemetryPoint.OFF_BODY_ABSENT } -> WearState.NO_SENSOR
        points.any { it.offBody == TelemetryPoint.OFF_BODY_REMOVED } -> WearState.REMOVED
        else -> WearState.WORN
    }

    private fun level(jitterStdUs: Int): TimestampLevel = when {
        jitterStdUs <= FINE_JITTER_US -> TimestampLevel.FINE
        jitterStdUs <= MEDIUM_JITTER_US -> TimestampLevel.MEDIUM
        else -> TimestampLevel.COARSE
    }

    /** Timestamping tiers, merged as long as the class does not change. */
    private fun tiers(
        intervals: List<Pair<TelemetryPointEntity, Interval>>,
    ): List<TimestampTier> {
        val out = ArrayList<TimestampTier>()
        for ((p, iv) in intervals) {
            val n = level(p.jitterStdUs)
            val last = out.lastOrNull()
            if (last != null && last.level == n && last.endMs == iv.startMs) {
                out[out.size - 1] = last.copy(endMs = iv.endMs)
            } else {
                out += TimestampTier(iv.startMs, iv.endMs, n)
            }
        }
        return out
    }

    /**
     * Merges contiguous intervals. Without this, an eight hour night would produce four hundred and
     * eighty one-minute rectangles — it is the same pattern as the hypnogram's stillness channel,
     * and for the same reason.
     */
    private fun merge(intervals: List<Interval>): List<Interval> {
        val out = ArrayList<Interval>()
        for (iv in intervals.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && iv.startMs <= last.endMs) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, iv.endMs))
            } else {
                out += iv
            }
        }
        return out
    }

    /**
     * The gauge: the fact — how much was left at the end — and the verdict — is the P1 criterion
     * held.
     *
     * The verdict is **not** recomputed here. It comes from [BatterySlope] when the slope succeeds,
     * from the last percentage otherwise, with the same strict comparator as
     * [Checks.MIN_BATTERY_PCT] and [P1Gate]. Two readings of the same threshold end up diverging,
     * and this one would be the third.
     */
    private fun gauge(points: List<TelemetryPointEntity>, res: Resources): BatteryGauge? {
        val last = points
            .filter { it.batteryPct in 0..100 }
            .maxByOrNull { it.elapsedRealtimeNs }
            ?: return null
        val slope = BatterySlope.of(points, P1Gate.TARGET_DURATION_H)
        val met = if (slope != null) {
            slope.pctAt8h > Checks.MIN_BATTERY_PCT
        } else {
            last.batteryPct > Checks.MIN_BATTERY_PCT
        }
        val endPct = "${last.batteryPct}%"
        return BatteryGauge(
            fraction = last.batteryPct / 100f,
            thresholdMet = met,
            label = if (slope != null) {
                text(
                    R.string.chart_battery_gauge_projected,
                    endPct,
                    "%.0f%%".format(Locale.UK, slope.pctAt8h),
                    P1Gate.TARGET_DURATION_H.toInt(),
                ).resolve(res)
            } else {
                text(R.string.chart_battery_gauge, endPct).resolve(res)
            },
        )
    }

    /**
     * The summary read by a screen reader. It returns what the channels show — how many points, the
     * coarsest timestamping observed, the clipping, the freezes, the battery — and **the
     * non-causality sentence**, which is what the graphical separation says to the eye and which
     * nothing else would say to someone who does not see the band.
     */
    private fun description(
        res: Resources,
        points: List<TelemetryPointEntity>,
        gauge: BatteryGauge?,
        nominalRateHz: Int,
    ): String {
        if (points.isEmpty()) return res.getString(R.string.chart_metrology_unavailable)
        val r = summary(points, nominalRateHz)!!
        return res.getString(
            R.string.chart_metrology_description,
            r.points,
            "%.1f ms".format(Locale.UK, r.medianJitterUs / 1000.0),
            r.clippedSamples,
            r.freezes,
            gauge?.label ?: Mapping.DASH,
        )
    }

    private fun integerMedian(v: List<Int>): Int {
        if (v.isEmpty()) return 0
        val t = v.sorted()
        val m = t.size / 2
        return if (t.size % 2 == 1) t[m] else (t[m - 1] + t[m]) / 2
    }
}
