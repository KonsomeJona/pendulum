package com.pendulum.phone.preview

import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.P1Gate
import com.pendulum.phone.ui.settings.P1ReportUi
import com.pendulum.phone.ui.home.HomeMachine
import com.pendulum.phone.ui.home.HomeSession
import com.pendulum.phone.ui.home.HomeSource
import com.pendulum.phone.ui.model.TonightUi
import com.pendulum.phone.ui.model.Flag
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.WakingState
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.model.TrendUiState

import com.pendulum.phone.ui.chart.EnvelopePyramid
import com.pendulum.phone.ui.chart.PointState
import com.pendulum.phone.ui.chart.WearState
import com.pendulum.phone.ui.chart.MarkerKind
import com.pendulum.phone.ui.chart.HypnogramSpec
import com.pendulum.phone.ui.chart.Interval
import com.pendulum.phone.ui.chart.BatteryGauge
import com.pendulum.phone.ui.chart.MetrologySpec
import com.pendulum.phone.ui.chart.TimestampLevel
import com.pendulum.phone.ui.chart.TimestampTier
import com.pendulum.phone.ui.chart.ReferenceLine
import com.pendulum.phone.ui.chart.Marker
import com.pendulum.phone.ui.chart.NightChartSpec
import com.pendulum.phone.ui.chart.AnnotatedPeak
import com.pendulum.phone.ui.chart.NightPoint
import com.pendulum.phone.ui.chart.StageSegment
import com.pendulum.phone.ui.chart.StageUi
import com.pendulum.phone.ui.chart.MedianBand
import com.pendulum.phone.ui.chart.TrendChartSpec
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Realistic data sets for the `@Preview`s.
 *
 * These previews do not only serve development: they are what will produce the screenshots of the
 * documentation and of the review. Plausible data is therefore a requirement, not a comfort — a
 * screenshot with "Lorem ipsum" and three aligned points lets one judge neither the readability of
 * an interval, nor the behaviour of the axis when a night is excluded.
 *
 * The values are set on the expected order of magnitude: fundamental rhythm around 21 s (Skeba
 * 2016: log-normal IMI, mean of the log stable to within 3.6 % from one night to the next), hourly
 * count around 22/h, wide confidence interval because it really is wide over six nights.
 */
object PreviewData {

    private const val DAY = 86_400_000L
    private const val BASE_MS = 1_741_737_600_000L // 12 March, midnight UTC — a stable reference

    /** The time zone of the preview nights. The trend's calendar axis needs it in order to date. */
    private const val ZONE = "Europe/Paris"

    // ---------------------------------------------------------------------------------
    // Trend
    // ---------------------------------------------------------------------------------

    val rhythm = Aggregate.Result(
        quantity = Aggregate.Quantity.RHYTHM_SECONDS,
        median = 21.0,
        ciLow = 18.0,
        ciHigh = 25.0,
        nights = 6,
        dispersion = 2.4,
        mdc95 = Aggregate.mdc95(2.4, 6),
    )

    val count = Aggregate.Result(
        quantity = Aggregate.Quantity.HOURLY_COUNT,
        median = 22.0,
        ciLow = 14.0,
        ciHigh = 31.0,
        nights = 6,
        dispersion = 12.0,
        mdc95 = Aggregate.mdc95(12.0, 6),
    )

    /**
     * Seven nights, one of them excluded — drawn all the same, as a hollow circle, at its value.
     * Hiding the failed night would give a cleaner picture and a false reading.
     */
    val trendPoints: List<NightPoint> = listOf(
        NightPoint("a1", BASE_MS - 6 * DAY, 23.4f, PointState.ELIGIBLE),
        NightPoint("a2", BASE_MS - 5 * DAY, 19.8f, PointState.ELIGIBLE),
        NightPoint("a3", BASE_MS - 4 * DAY, 21.2f, PointState.ACCEL_MASKED),
        NightPoint("a4", BASE_MS - 3 * DAY, 28.1f, PointState.EXCLUDED),
        NightPoint("a5", BASE_MS - 2 * DAY, 20.6f, PointState.ELIGIBLE),
        NightPoint("a6", BASE_MS - 1 * DAY, 22.9f, PointState.ELIGIBLE),
        NightPoint("a7", BASE_MS, 18.7f, PointState.ELIGIBLE),
    )

    val trendRhythm = TrendChartSpec(
        quantity = Aggregate.Quantity.RHYTHM_SECONDS,
        points = trendPoints,
        bands = listOf(
            MedianBand(BASE_MS - 6 * DAY, BASE_MS, 21f, 18f, 25f, "21 s"),
        ),
        // No reference line on the rhythm: no published threshold applies to it.
        reference = null,
        firstDayMs = BASE_MS - 6 * DAY,
        lastDayMs = BASE_MS,
        zoneId = ZONE,
        pivotMs = null,
        accessibleDescription = trendDescription(
            points = 6,
            start = "6 March",
            end = "12 March",
            median = "21",
            minimum = "18",
            maximum = "26",
            unit = "seconds",
        ),
    )

    val trendCount = trendRhythm.copy(
        quantity = Aggregate.Quantity.HOURLY_COUNT,
        points = trendPoints.map { it.copy(value = it.value * 1.05f) },
        bands = listOf(MedianBand(BASE_MS - 6 * DAY, BASE_MS, 22f, 14f, 31f, "22/h")),
        reference = ReferenceLine(15f, "15/h", THRESHOLD_15_CAPTION),
        accessibleDescription = trendDescription(
            points = 6,
            start = "6 March",
            end = "12 March",
            median = "22",
            minimum = "11",
            maximum = "27",
            unit = "movements per hour",
        ),
    )

    // ---------------------------------------------------------------------------------
    // Night
    // ---------------------------------------------------------------------------------

    private const val NIGHT_START = BASE_MS + 23 * 3_600_000L + 12 * 60_000L
    private const val NIGHT_END = NIGHT_START + 7 * 3_600_000L + 46 * 60_000L

    /**
     * Synthetic envelope: noise floor at x1, periodic bursts at ~22 s, and one isolated peak at
     * x61 — that one is there precisely to check that the min/max decimation does not erase it.
     */
    private val envelope: FloatArray = FloatArray(28_000) { i ->
        val rng = Random(i / 500)
        val floor = 1f + 0.15f * abs(sin(i * 0.017f)) + rng.nextFloat() * 0.05f
        val burst = if ((i / 55) % 40 < 6) 6f + 4f * abs(sin(i * 0.9f)) else 0f
        val peak = if (i == 12_040) 60f else 0f
        floor + burst + peak
    }

    val night = NightChartSpec(
        startMs = NIGHT_START,
        endMs = NIGHT_END,
        pyramid = EnvelopePyramid(envelope),
        envelopeStepMs = 1_000L,
        noiseFloor = FloatArray(280) { 1f },
        onsetThreshold = FloatArray(280) { 8f },
        fineSeriesStepMs = 100_000L,
        outsideSleep = listOf(
            Interval(NIGHT_START, NIGHT_START + 18 * 60_000L),
            Interval(NIGHT_END - 26 * 60_000L, NIGHT_END),
        ),
        gaps = listOf(Interval(NIGHT_START + 3 * 3_600_000L, NIGHT_START + 3 * 3_600_000L + 47_000L)),
        markers = buildList {
            var t = NIGHT_START + 40 * 60_000L
            var n = 0
            while (t < NIGHT_END - 30 * 60_000L) {
                val kind = when {
                    n % 13 == 0 -> MarkerKind.POSTURE_EXCLUDED
                    n % 7 == 0 -> MarkerKind.DURING_WAKE
                    else -> MarkerKind.COUNTED
                }
                add(Marker(t, kind, if (kind == MarkerKind.COUNTED) n / 6 else null))
                t += 22_400L + (n % 5) * 900L
                n++
            }
        },
        series = listOf(
            Interval(NIGHT_START + 55 * 60_000L, NIGHT_START + 78 * 60_000L),
            Interval(NIGHT_START + 190 * 60_000L, NIGHT_START + 236 * 60_000L),
        ),
        peak = AnnotatedPeak(61f, "03:12"),
        accessibleDescription = "Chart of the night of 12 March, 412 movements detected " +
            "between 23:12 and 06:58. Values button for the table.",
    )

    val hypnogram = HypnogramSpec(
        startMs = NIGHT_START,
        endMs = NIGHT_END,
        stages = buildList {
            var t = NIGHT_START
            val cycle = listOf(
                StageUi.WAKE to 18, StageUi.N1 to 12, StageUi.N2 to 42, StageUi.N3 to 38,
                StageUi.N2 to 20, StageUi.REM to 24,
            )
            var i = 0
            while (t < NIGHT_END) {
                val (stage, minutes) = cycle[i % cycle.size]
                val end = minOf(t + minutes * 60_000L, NIGHT_END)
                add(StageSegment(t, end, stage))
                t = end
                i++
            }
        },
        accelStillness = listOf(
            Interval(NIGHT_START + 20 * 60_000L, NIGHT_START + 150 * 60_000L),
            Interval(NIGHT_START + 165 * 60_000L, NIGHT_END - 30 * 60_000L),
        ),
        disagreement = listOf(Interval(NIGHT_START + 150 * 60_000L, NIGHT_START + 165 * 60_000L)),
        unavailableText = "Hypnogram unavailable — accelerometer immobility mask used.",
        statistics = "6 h 58 of sleep · awake 48 min · REM 1 h 24 · N3 1 h 02 · " +
            "source Samsung Health via Health Connect · agreement with the accelerometer mask: κ = 0.71",
    )

    val hypnogramMissing = hypnogram.copy(stages = null, disagreement = emptyList())

    // ---------------------------------------------------------------------------------
    // Device state — the third band
    // ---------------------------------------------------------------------------------

    /**
     * A metrology night that is **deliberately imperfect**.
     *
     * A preview where everything goes well lets one judge none of the three things this band has to
     * make readable: that the timestamping degrades in tiers, that clipping happens in bursts and
     * not continuously, and that a gauge that is not met is distinguishable from one that is met
     * **without colour**. So there is an hour on the charger at the start here, a period where the
     * sensor clipped, a jitter degradation in the middle of the night, and a battery that does not
     * meet the criterion — that is, a hatched gauge.
     *
     * The plurality of the lanes is also what shows the gutter at scale: on a screenshot, the
     * separation must leap to the eye before a single label has been read.
     */
    val metrology = MetrologySpec(
        startMs = NIGHT_START,
        endMs = NIGHT_END,
        wearState = WearState.REMOVED,
        offWrist = listOf(
            Interval(NIGHT_START, NIGHT_START + 12 * 60_000L),
            Interval(NIGHT_END - 22 * 60_000L, NIGHT_END),
        ),
        charging = listOf(Interval(NIGHT_START, NIGHT_START + 58 * 60_000L)),
        timestamping = buildList {
            var t = NIGHT_START
            var i = 0
            while (t < NIGHT_END) {
                val end = minOf(t + 37 * 60_000L, NIGHT_END)
                val level = when {
                    i in 4..5 -> TimestampLevel.COARSE
                    i % 3 == 2 -> TimestampLevel.MEDIUM
                    else -> TimestampLevel.FINE
                }
                add(TimestampTier(t, end, level))
                t = end
                i++
            }
        },
        // Two bursts: clipping is never uniform, it follows the wide movements.
        clipping = buildList {
            var t = NIGHT_START + 62 * 60_000L
            while (t < NIGHT_START + 78 * 60_000L) { add(t); t += 60_000L }
            t = NIGHT_START + 196 * 60_000L
            while (t < NIGHT_START + 203 * 60_000L) { add(t); t += 60_000L }
        },
        freezes = listOf(
            NIGHT_START + 41 * 60_000L,
            NIGHT_START + 154 * 60_000L,
            NIGHT_START + 155 * 60_000L,
            NIGHT_START + 302 * 60_000L,
        ),
        battery = BatteryGauge(
            fraction = 0.34f,
            thresholdMet = false,
            label = "34% at the end of the night  ·  12% projected at 8 h",
        ),
        points = 468,
        unavailableText = "No device telemetry for this night — recorded before the watch sent any.",
        accessibleDescription = metrologyDescription(
            points = 468,
            jitter = "1.4 ms",
            clipped = 1_204,
            freezes = 4,
            battery = "34% at the end of the night",
        ),
    )

    /** The same night, but without a single point: that is what a night from before telemetry gives. */
    val metrologyMissing = metrology.copy(points = 0)

    // ---------------------------------------------------------------------------------
    // Nights
    // ---------------------------------------------------------------------------------

    val nights: List<NightUi> = listOf(
        NightUi(
            "a7", "12 March", "Fri", "23:12", "06:58", "6 h 58 of sleep", text(R.string.settings_health_connect),
            NightState.ELIGIBLE, null, 18.7, 24.0,
            listOf(Flag(text("gap 47 s"))), BASE_MS,
            // Revealed: this is the state of a night whose result has been asked for.
            revealedAtMs = BASE_MS + 7 * 3_600_000L,
        ),
        // Null rhythm: the fit was refused, which is the most frequent case. The preview carries it
        // because that is the row the list will show most often, and a preview set where every
        // night has a rhythm gives a false idea of the screen.
        NightUi(
            "a6", "11 March", "Thu", "23:41", "07:04", "7 h 02 of sleep", text(R.string.settings_health_connect),
            NightState.PROVISIONAL, null, null, 19.0,
            listOf(Flag(text("accelerometer mask"))), BASE_MS - DAY,
        ),
        NightUi(
            "a4", "09 March", "Tue", "00:12", "05:22", "2 h 10 of sleep", text(R.string.settings_accel_mask_only),
            NightState.EXCLUDED, Mapping.reason("TOO_SHORT"), 28.1, 41.0,
            listOf(Flag(text("off-body 12%")), Flag(text("battery 8%"))), BASE_MS - 3 * DAY,
        ),
    )

    val tonight = TonightUi(
        // Deliberately null: nothing on the phone side reads the watch's battery or free space
        // yet. The preview must show the dashes the application displays, not the figure one would
        // like to see there one day.
        batteryPct = null,
        freeSpace = null,
        strap = "4th hole",
        leg = text(R.string.tonight_leg_right),
        sleepSource = text("Samsung Health"),
        sourceActive = null,
        contextSealed = true,
    )

    // ---------------------------------------------------------------------------------
    // Home
    // ---------------------------------------------------------------------------------

    /** The evening: nothing is in flight, the context is still to be sealed. */
    val homeEvening = HomeMachine.of(
        HomeSource(
            recentSession = null,
            contextSealed = false,
            sealedLeg = null,
            strapReference = "4th hole",
            sleepSource = text("Samsung Health"),
            recordedNights = 7,
            eligibleNights = 6,
            lastAnalysedNight = nights.first(),
        ),
        localHour = 22,
    )

    /** The morning: the night is closed and scored, and its result has not been asked for. */
    val homeMorning = HomeMachine.of(
        HomeSource(
            recentSession = HomeSession(
                sessionHex = "a7",
                state = "CLOSED",
                analysed = true,
                readableStart = "23:12",
                readableDate = "12 March",
            ),
            contextSealed = true,
            sealedLeg = text(R.string.tonight_leg_right),
            strapReference = "4th hole",
            sleepSource = text("Samsung Health"),
            recordedNights = 7,
            eligibleNights = 6,
            lastAnalysedNight = nights.first().copy(revealedAtMs = null),
        ),
        localHour = 7,
    )

    val trendReady = TrendUiState.Ready(
        rhythm = rhythm,
        count = count,
        position = Aggregate.position(count.ciLow, count.ciHigh, count.nights),
        qualifiedPeriodicity = Aggregate.qualifyPeriodicity(0.71, 6),
        missRate = 0.31,
        chart = trendRhythm,
        recordedNights = 7,
        eligibleNights = 6,
        excludedNights = 1,
        rule = text("AASM v3 (5–90 s)"),
        mask = text("Health Connect (5/6 nights)"),
        plmw = 9.0,
        waking = WakingState.Provisional("12 March", "07:12", "08:12", "19:04", gaveUp = false),
        customProfile = null,
        mixedHashes = false,
        questionnaireState = text(R.string.quiz_not_filled),
        exportPossible = true,
    )

    val trendRefusal = TrendUiState.Refusal(
        eligibleNights = 2,
        fittedRhythmNights = 2,
        requiredNights = Aggregate.MIN_NIGHTS_AGGREGATE,
        recordedNights = nights.take(2),
        waking = WakingState.None,
    )

    /**
     * The second reason for refusal: the nights are there, the rhythm is not.
     *
     * This is the **frequent** case — 2 accepted fits out of 20 nominal nights — and the preview
     * exists because a screen one never sees in mockup is a screen one writes blind. Nine eligible
     * nights, two identified rhythms.
     */
    val trendRefusalRhythm = trendRefusal.copy(
        eligibleNights = 9,
        fittedRhythmNights = 2,
        recordedNights = nights,
    )

    // ---------------------------------------------------------------------------------
    // P1 gate
    // ---------------------------------------------------------------------------------

    /**
     * Three campaign nights, one of them outside the criteria.
     *
     * The set goes through **real** `night_session` rows and through [P1Gate] rather than through
     * verdicts written by hand: a preview that short-circuits the computation shows the screen and
     * says nothing about what the screen will display. Two compliant nights in a row and a third
     * below the coverage — so the gate is not crossed, and that is the case one has to be able to
     * read.
     */
    val p1Report: P1ReportUi = run {
        val verdicts = listOf(
            p1Night(days = 0, hours = 8.2, coverage = 0.994, battery = 34, fs = 50.31),
            p1Night(days = 1, hours = 8.05, coverage = 0.992, battery = 27, fs = 50.28),
            p1Night(days = 2, hours = 6.1, coverage = 0.978, battery = 41, fs = 50.31),
        ).map { P1Gate.of(it) }
        P1ReportUi(nights = verdicts, campaign = P1Gate.campaign(verdicts))
    }

    private fun p1Night(
        days: Long,
        hours: Double,
        coverage: Double,
        battery: Int,
        fs: Double,
    ): NightSessionEntity {
        val start = BASE_MS - days * DAY + 22 * 3_600_000L // 23:00 local, winter time
        val durationMs = (hours * 3_600_000L).toLong()
        val rate = 50
        return NightSessionEntity(
            sessionHex = "p1$days",
            startWallMs = start,
            plannedStopWallMs = start + 8 * 3_600_000L,
            endWallMs = start + durationMs,
            zoneId = ZONE,
            tzOffsetStartMin = 60,
            tzOffsetEndMin = 60,
            nominalRateHz = rate,
            modeFlags = 0,
            state = "CLOSED",
            batteryPctLast = battery,
            // Without this date the coverage is `null` — an unanalysed night has no numerator — and
            // the preview would show three dashes in place of the three figures it exists to show.
            // This is the intended behaviour of the product, not a workaround: these three preview
            // nights **have** been analysed.
            analyzedAtMs = start + durationMs + 1_800_000L,
            fsMeasuredHz = fs,
            sampleCount = (durationMs * rate / 1000.0 * coverage).toLong(),
            gapCount = 3,
            gapTotalMs = 4_200L,
        )
    }
}

/**
 * The texts the chart `Spec`s carry **already resolved**.
 *
 * They are written in plain text here rather than read from the resources: a Compose preview has no
 * application `Context`, and above all this data set exists to show the screen, not to check the
 * wiring — it is `ui/TextsTest.kt` that checks that every identifier has its string.
 */
private fun trendDescription(
    points: Int,
    start: String,
    end: String,
    median: String,
    minimum: String,
    maximum: String,
    unit: String,
) = "Trend, $points points, from $start to $end, median $median $unit, values from " +
    "$minimum to $maximum $unit, points not joined. Values button for the table."

private fun metrologyDescription(
    points: Int,
    jitter: String,
    clipped: Int,
    freezes: Int,
    battery: String,
) = "Device state, $points points, one per minute. Median timing dispersion $jitter. " +
    "$clipped samples at the sensor range limit. $freezes write freezes longer than one " +
    "sampling period. Battery: $battery. These lanes describe the recorder, not the " +
    "sleeper: none of them caused the movements shown above."

private const val THRESHOLD_15_CAPTION =
    "15/h — above this, periodic limb movements are usually counted as frequent in " +
        "polysomnography (ICSD-3). Many physicians are not concerned below it."
