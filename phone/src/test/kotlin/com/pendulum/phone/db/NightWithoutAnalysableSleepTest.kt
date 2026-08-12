package com.pendulum.phone.db

import com.pendulum.algo.indices.Plmi
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.PublicationGate
import com.pendulum.algo.model.RhythmResult
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.FloorMode
import com.pendulum.phone.R
import com.pendulum.phone.ui.Resources
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.ComputationPath
import com.pendulum.phone.ui.model.Checks
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.text.text
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **The defect that made a whole night disappear, without a word.**
 *
 * `safeRate` returns `Double.NaN` when the denominator does not exist, and that is the right
 * decision: a rate with no denominator is not zero, it does not exist. But SQLite does not know
 * `NaN` — `sqlite3_bind_double` of a `NaN` **writes a `NULL`**. A `NOT NULL` column then refuses
 * the insertion:
 *
 * ```
 * android.database.sqlite.SQLiteConstraintException: NOT NULL constraint failed: plm_result.plmi
 * ```
 *
 * `AnalyzeWorker` catches it and returns `Result.retry()`. The night is therefore retried
 * endlessly, never analyses, and appears nowhere — `analyzedAtMs` stays empty. Measured on two
 * real recordings from the Pixel Fold: transferred whole, CRC-checked, invisible.
 *
 * This is not an edge case. It is the **default** path of every user with no Health Connect
 * hypnogram: with no analysable sleep, six rates are `NaN` at once, and `Rhythm.emptyFit` adds
 * four more — a refused rhythm fit is the frequent case too (2 accepted out of 20 nominal nights).
 *
 * The repair **never** replaces the `NaN` with zero: displaying "0 movements per hour" for a night
 * where nothing could be measured would be a clinical lie worse than the absence. It makes the
 * column nullable, and `null` reads as "we do not know" all the way to the dash on the screen.
 */
class NightWithoutAnalysableSleepTest {

    /**
     * The columns of `plm_result` that `:algo` can return as `NaN`, and which must therefore
     * accept `NULL` in the database. Six come from `safeRate` (`Indices.kt`), four from
     * `Rhythm.emptyFit`.
     *
     * `periodicityIndex` is not there and has no business being there: `Periodicity.fromIntervals`
     * returns `0.0` when there is no interval at all, and carries its own `valid`.
     */
    private val quantitiesThatMayNotExist = listOf(
        "plmi", "plmiSpt", "plmw", "plmiFirstHalf", "plmiSecondHalf", "plmiRespWorstCase",
        "fundamentalSec", "muLog", "sigmaLog", "missRate",
    )

    @Test
    @DisplayName("a night without analysable sleep does produce non-existent rates")
    fun `with no denominator the rates are NaN`() {
        val r = resultWithoutSleep()

        assertThat(r.plmi).isNaN()
        assertThat(r.plmiSpt).isNaN()
        assertThat(r.plmiRespWorstCase).isNaN()
        // And the publication gate already says it: this night carries no index.
        assertThat(r.gate).isEqualTo(PublicationGate.NO_PLMI)
    }

    /**
     * The test is on the **return type of the accessor**, and not on an annotation: that is
     * exactly what Room reads to decide the constraint. A non-null `Double` compiles to a
     * primitive `double` and gives a `NOT NULL` column; a `Double?` becomes a `java.lang.Double`
     * and gives a column that accepts `NULL`. The primitive is therefore the signature of the
     * defect.
     */
    @Test
    @DisplayName("every quantity that may not exist accepts NULL in the database")
    fun `the NaN columns are nullable`() {
        for (name in quantitiesThatMayNotExist) {
            val accessor = PlmResultEntity::class.java
                .getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
            assertThat(accessor.returnType.isPrimitive)
                .withFailMessage(
                    "`plm_result.%s` is declared NOT NULL while `:algo` can put a `NaN` in it. " +
                        "SQLite converts that `NaN` into a `NULL` at binding time, the insertion " +
                        "raises `SQLiteConstraintException: NOT NULL constraint failed: " +
                        "plm_result.%s`, `AnalyzeWorker` returns `retry()` — and the night never " +
                        "appears. Make the column nullable; above all do not write a zero, which " +
                        "would announce a measurement where there is none.",
                    name, name,
                )
                .isFalse()
        }
    }

    /**
     * The heart of the repair: this night must produce an **insertable** row, in which every
     * absent quantity is `null` — never zero.
     */
    @Test
    @DisplayName("the persisted row carries NULLs, and above all not zeros")
    fun `the result is persistable and reads back as null`() {
        val row = rowWithoutSleep()

        assertThat(
            listOf(
                row.plmi, row.plmiSpt, row.plmw,
                row.plmiFirstHalf, row.plmiSecondHalf, row.plmiRespWorstCase,
                row.fundamentalSec, row.muLog, row.sigmaLog, row.missRate,
            )
        ).containsOnlyNulls()

        // `containsOnlyNulls` carries the whole weight of the test: the natural temptation when
        // fixing this defect is to write `0.0`, which would insert without a murmur — and would
        // announce "no movement per hour" for a night where nothing could be measured.

        // What is **counted**, on the other hand, exists and stays written: the movement count has
        // no denominator, so nothing stops it from being zero for good.
        assertThat(row.plmsCount).isZero()
        assertThat(row.gate).isEqualTo(PublicationGate.NO_PLMI.name)
        assertThat(row.rhythmValid).isFalse()
    }

    @Test
    @DisplayName("it enters neither the eligible nights nor any median")
    fun `a night without an index counts in no aggregate`() {
        // Eligibility: `TrendDao.trendPoints` filters on `gate = 'FULL'`, and this night is
        // `NO_PLMI`. The query further requires `plmi IS NOT NULL`, so that the publication gate
        // and the existence of the figure stay two distinct conditions.
        assertThat(rowWithoutSleep().gate).isNotEqualTo(PublicationGate.FULL.name)

        // Median: even if such a night did cross the gate, it leaves the aggregate before being
        // counted. Two measured nights plus one without an index do not make three nights.
        val nights = listOf(
            nightUi(hex = "a", plmi = 18.0),
            nightUi(hex = "b", plmi = 22.0),
            nightUi(hex = "none", plmi = null),
        )
        assertThat(Mapping.aggregate(Aggregate.Quantity.HOURLY_COUNT, nights) { it.plmi })
            .withFailMessage(
                "A night without an index was counted as an eligible night. Below %d measured " +
                    "nights there must be no aggregate at all — and a night that carries no " +
                    "figure is not one of them.",
                Aggregate.MIN_NIGHTS_AGGREGATE,
            )
            .isNull()

        // And with three measured nights, the median sees only those.
        val withThree = nights + nightUi(hex = "c", plmi = 20.0)
        val r = Mapping.aggregate(Aggregate.Quantity.HOURLY_COUNT, withThree) { it.plmi }
        assertThat(r).isNotNull
        assertThat(r!!.nights).isEqualTo(3)
        assertThat(r.median).isEqualTo(20.0)
    }

    @Test
    @DisplayName("the screens render a dash, never a zero")
    fun `an absence reads as an absence`() {
        val row = rowWithoutSleep()
        val night = nightUi(hex = "none", plmi = null)

        // The list and the detail: the hourly count and the rhythm.
        assertThat(Mapping.readableCount(night.plmi)).isEqualTo(Mapping.DASH)
        assertThat(Mapping.rhythmSec(night)).isNull()
        assertThat(Mapping.nightUi(night, endWallMs = null, sleepSource = text("x")).plmiCount)
            .isNull()

        // The "why this figure" block: it stays displayed — its lines say where the computation
        // stopped — but its title names no figure and its absent values are dashes.
        val block = requireNotNull(
            ComputationPath.of(
                n = night,
                result = row,
                recordedDurationMin = 42.0,
                movementsKept = 0,
                rule = text("AASM"),
                sleepSource = text("Pendulum"),
            )
        )
        assertThat(Resources.resolve(block.title))
            .isEqualTo(Resources.read(R.string.night_why_title_no_index))
        assertThat(Resources.resolve(block.lines[5].value)).isEqualTo(Mapping.DASH)
        assertThat(Resources.resolve(block.lines[6].value)).isEqualTo(Mapping.DASH)

        // The quality table: dash **and** unknown state. A `✓` or a `✗` on an absent value would
        // assert a check that never took place.
        val missedRate = Checks.of(session(), night, row, text("Pendulum"))
            .single { it.label == text(R.string.night_detail_missed_rate) }
        assertThat(Resources.resolve(missedRate.value)).isEqualTo(Mapping.DASH)
        assertThat(missedRate.ok).isNull()
    }

    // -------------------------------------------------------------------------------------

    private fun rowWithoutSleep() = PlmResultEntity.from(
        sessionHex = "abcd",
        paramsHash = "h",
        computedAtMs = 0L,
        algoVersion = "1.4.0",
        r = resultWithoutSleep(),
    )

    private fun nightUi(hex: String, plmi: Double?) = ComparableNight(
        sessionHex = hex,
        startWallMs = 1_700_000_000_000L,
        zoneId = "Europe/Paris",
        paramsHash = "h",
        rule = "AASM_V3",
        maskSource = "ACCEL_IMMOBILITY",
        gate = if (plmi == null) PublicationGate.NO_PLMI.name else "FULL",
        independence = "CIRCULAR",
        plmi = plmi,
        plmiSpt = plmi,
        fundamentalSec = null,
        rhythmValid = false,
        periodicityIndex = 0.0,
        missRate = null,
        analysableTstMin = if (plmi == null) 0.0 else 312.0,
        analysableMin = 42.0,
        truncated = false,
        revealedAtMs = null,
        comparable = plmi != null,
        exclusionReason = ComparabilityRule.OK,
    )

    private fun session() = NightSessionEntity(
        sessionHex = "abcd",
        startWallMs = 1_700_000_000_000L,
        plannedStopWallMs = 1_700_028_800_000L,
        zoneId = "Europe/Paris",
        tzOffsetStartMin = 60,
        tzOffsetEndMin = 60,
        nominalRateHz = 50,
        modeFlags = 0,
        state = "CLOSED",
    )

    /** A mask without a single analysable minute of sleep: the default case without Health Connect. */
    private fun resultWithoutSleep() = Plmi.compute(
        clms = emptyList(),
        series = emptyList(),
        mask = SleepMask(
            windows = emptyList(),
            source = MaskSource.ACCEL_IMMOBILITY,
            sptMin = 0.0,
            tstMin = 0.0,
            wasoMin = 0.0,
            analysableTstMin = 0.0,
            analysableSptMin = 0.0,
            corrected = false,
            lagAppliedMs = 0L,
            independence = DenominatorIndependence.CIRCULAR,
            fixedPointConverged = true,
        ),
        fsHz = 50.0,
        rule = SeriesRule.AASM_V3,
        pi = PiResult(periodicityIndex = 0.0, valid = false, totalIntervals = 0, lmRatePerHour = 0.0),
        rhythm = RhythmResult(
            fundamentalSec = Double.NaN,
            muLog = Double.NaN,
            sigmaLog = Double.NaN,
            missRate = Double.NaN,
            harmonicWeights = DoubleArray(0),
            alternationSuspect = false,
            intervalsUsed = 0,
            converged = false,
            valid = false,
        ),
        floorMode = FloorMode.BILATERAL,
        truncated = false,
        truncatedSeriesDropped = 0,
        paramsHash = "h",
    )
}
