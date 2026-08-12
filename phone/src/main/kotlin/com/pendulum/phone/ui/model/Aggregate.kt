package com.pendulum.phone.ui.model

import androidx.annotation.StringRes
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The rules that are code, and not recommendations.
 *
 * This whole file is plain Kotlin, with no Android dependency: that is what makes it testable on
 * the JVM, exhaustively, including on the exact bounds. The screens are only readers of it — no
 * composable recomputes a median or decides on eligibility.
 */
object Aggregate {

    /**
     * Below this number of eligible nights, **nothing** is aggregated.
     *
     * This is not a warning: there is no branch of this code that produces a value below it. The
     * reason is a figure and it comes from Skeba 2016 — in confirmed patients, the 15/h threshold
     * is crossed on only ~34 % of individual nights, and the night to night variability of the
     * hourly count is 43 % +/- 37. A number shown on one or two nights is dominated by chance, in
     * either direction.
     */
    const val MIN_NIGHTS_AGGREGATE = 3

    /**
     * Below this, no textual category is offered, whatever the interval.
     * Between 3 and 4 nights we do aggregate — the interval then says for itself that it is wide —
     * but we do not put someone in a box on four nights.
     */
    const val MIN_NIGHTS_CATEGORY = 5

    /** Minimum per period for a comparison to mean anything. */
    const val MIN_NIGHTS_COMPARISON = 5

    /** Bootstrap resamplings. Enough for a 95 % percentile, few enough to hold. */
    const val BOOTSTRAP_N = 2000

    /**
     * Below this number of nights, the interval **is not** a 95 % interval and the screen says so.
     *
     * ### What was measured
     *
     * `BootstrapCoverageTest` simulates 10,000 replicates and counts the proportion in which the
     * true median falls inside the interval returned by [bootstrapCi]. Below six nights the real
     * coverage is far below the label — the interval shown is narrower than it announces, which is
     * the most embarrassing possible defect on a product whose interval is the argument.
     *
     * ### Why this is not an implementation defect, and why BCa does not fix it
     *
     * The median of a resampling with replacement of `n` values is **one of those `n` values**. Any
     * interval built from the bootstrap distribution is therefore trapped inside the range of the
     * sample, and `P(min < true median < max) = 1 - 2 x 2^-n`: see [maxBootstrapCoverage]. At three
     * nights the ceiling is 75 %; no percentile, bias- and acceleration-corrected or not, can cross
     * it. Implementing BCa would have given forty more lines and the same coverage.
     *
     * The only honest answer is therefore to name what the interval really is below six nights —
     * `trend_interval_uncalibrated_note` — and not to write "95 %".
     */
    const val MIN_NIGHTS_CALIBRATED_CI = 6

    /**
     * Maximum coverage attainable by **any** bootstrap interval on the median of `n` observations:
     * `1 - 2 x 2^-n`.
     *
     * 75 % at 3 nights, 87.5 % at 4, 93.75 % at 5, 96.9 % at 6. It is that bound which fixes
     * [MIN_NIGHTS_CALIBRATED_CI]: six is the first `n` whose ceiling exceeds 95 %.
     */
    fun maxBootstrapCoverage(n: Int): Double = 1.0 - 2.0 * 2.0.pow(-n.toDouble())

    /**
     * What the trend plots.
     *
     * `SPEC-v2.md` §5 changed the tracked quantity after the fact: it is no longer the hourly count
     * but the **fundamental rhythm in seconds**. Three reasons, all measured: twelve times less
     * night to night variability (log IMI: 3.6 % +/- 3.7 against 43.2 % +/- 37.1); no denominator,
     * so the circularity of the accelerometric mask disappears for the tracking; and the 15/h
     * threshold was not transposable to an ankle measurement anyway, since 39 % of the movements
     * seen on EMG are mechanically invisible to accelerometry.
     *
     * The hourly count **stays** — it is the language of sleep physicians — but in second place on
     * screen and in first place in the report for the doctor.
     */
    enum class Quantity(@StringRes val unit: Int, val decimals: Int) {
        RHYTHM_SECONDS(R.string.trend_unit_seconds, 0),
        HOURLY_COUNT(R.string.trend_unit_per_hour, 0),
    }

    /**
     * A complete aggregate. There is no aggregate without its interval and its `n`: the three
     * fields are non-null by construction, which makes P2 ("every aggregated number carries its
     * uncertainty on the same line") impossible to violate by omission — there is no constructor
     * that accepts a median alone.
     */
    data class Result(
        val quantity: Quantity,
        val median: Double,
        val ciLow: Double,
        val ciHigh: Double,
        val nights: Int,
        /** MAD x 1.4826: the user's own dispersion, the yardstick for the noise. */
        val dispersion: Double,
        /** Smallest detectable change at 95 %, in the unit of [quantity]. */
        val mdc95: Double,
    ) {
        /**
         * Does the interval deserve its "95 %" label?
         *
         * False below [MIN_NIGHTS_CALIBRATED_CI] nights, where the measured real coverage falls to
         * 75 %. The screen then changes label (`trend_interval_uncalibrated`) instead of promising
         * a precision that does not exist.
         */
        val ciCalibrated: Boolean get() = nights >= MIN_NIGHTS_CALIBRATED_CI
    }

    // -------------------------------------------------------------------------------------
    // Estimators
    // -------------------------------------------------------------------------------------

    fun median(values: DoubleArray): Double {
        require(values.isNotEmpty()) { "median on an empty sample" }
        val sorted = values.sortedArray()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2.0
    }

    /** MAD x 1.4826: robust standard deviation, insensitive to one aberrant night. */
    fun dispersion(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val med = median(values)
        val deviations = DoubleArray(values.size) { abs(values[it] - med) }
        return median(deviations) * 1.4826
    }

    /**
     * 95 % percentile confidence interval by bootstrap over the nights.
     *
     * **The seed is fixed and derived from the set of session identifiers.** That is an interface
     * requirement, not a statistical one: an interval that changes at every recomposition destroys
     * trust in the whole screen, and the user who reopens the app ten minutes later must read the
     * same number again. Same set of nights = same interval, forever.
     *
     * What this interval captures: the variability **from one night to the next**, which is the
     * dominant source of error. What it does not capture: the uncertainty *within* a night, for
     * which no defensible model exists — the events are periodic by definition, therefore not
     * independent, therefore not resampleable. We do not manufacture what we do not know how to
     * estimate.
     */
    fun bootstrapCi(
        values: DoubleArray,
        seed: Long,
        draws: Int = BOOTSTRAP_N,
    ): Pair<Double, Double> {
        require(values.isNotEmpty()) { "bootstrap on an empty sample" }
        if (values.size == 1) return values[0] to values[0]
        val rng = Random(seed)
        val medians = DoubleArray(draws)
        val draw = DoubleArray(values.size)
        for (b in 0 until draws) {
            for (i in values.indices) draw[i] = values[rng.nextInt(values.size)]
            medians[b] = median(draw)
        }
        medians.sort()
        val low = medians[(0.025 * (draws - 1)).toInt()]
        val high = medians[ceil(0.975 * (draws - 1)).toInt()]
        return low to high
    }

    /** Deterministic seed: same set of sessions, same seed, whatever the order. */
    fun seedOf(sessionHex: List<String>): Long =
        sessionHex.sorted().fold(0L) { acc, s -> acc * 31 + s.hashCode() }

    /**
     * Smallest detectable change at 95 % (MDC95).
     *
     * Formula chosen: `1.96 x sqrt(2) x dispersion / sqrt(n)`. It is the classic MDC applied to the
     * standard error of the median estimated over `n` nights, taking the night to night dispersion
     * as the measurement error.
     *
     * **What this formula assumes, honestly**: that night to night variability is measurement noise
     * and not signal. Strictly speaking that is false — a night of insomnia is not a sensor error.
     * The choice is conservative in the right direction: it makes the band wider, hence the
     * interface more cautious. A band that was too narrow would announce differences that are not
     * ones, which is exactly the failure mode to avoid.
     */
    fun mdc95(dispersion: Double, n: Int): Double {
        if (n <= 0) return Double.POSITIVE_INFINITY
        return 1.96 * sqrt(2.0) * dispersion / sqrt(n.toDouble())
    }

    // -------------------------------------------------------------------------------------
    // Position sentence: five outcomes, and no other wording exists in the app
    // -------------------------------------------------------------------------------------

    sealed interface Position {
        /** Below three nights: refusal screen, no sentence at all. */
        data object Refusal : Position
        data class Provisional(val nights: Int) : Position
        data object BelowThreshold : Position
        data object AboveThreshold : Position
        data object SpansThreshold : Position

        /** `null` below three nights: the refusal screen states no position sentence. */
        fun sentence(): UiText? = when (this) {
            Refusal -> null
            is Provisional -> text(R.string.trend_position_provisional, nights)
            BelowThreshold -> text(R.string.trend_below_threshold)
            AboveThreshold -> text(R.string.trend_above_threshold)
            SpansThreshold -> text(R.string.trend_spans_threshold)
        }
    }

    /**
     * The 15/h threshold, and the only place where that number is written.
     *
     * **It comes from polysomnography and it is applied here to an ankle accelerometer.** That is
     * not the same measurement, and saying so is part of the product: see
     * [ANKLE_ACTIGRAPHY_THRESHOLD_PER_HOUR] and `chart_threshold_15_legend`.
     */
    const val CLINICAL_THRESHOLD_PER_HOUR = 15.0

    /**
     * The **actigraphic** equivalent of the previous threshold: 16.0/h.
     *
     * It comes from the validation of the PAM-RL — an ankle actigraph, the same mounting as
     * Pendulum's — against polysomnography: 16.0/h on the actigraph corresponds to 15/h on PSG
     * (Aritake-Okada et al., *Sleep Medicine* 2014, n = 41).
     *
     * **This constant drives no branch, and that is deliberate.** A single paper, a single cohort:
     * substituting it for [CLINICAL_THRESHOLD_PER_HOUR] would move every position sentence on the
     * strength of a single source, whereas 15/h has ICSD-3 and clinical practice behind it. It
     * exists so that the gap is **written down** rather than passed over in silence, and
     * `ThresholdsTest` checks that the chart legend mentions it.
     */
    const val ANKLE_ACTIGRAPHY_THRESHOLD_PER_HOUR = 16.0

    /**
     * The number of nights beyond which the result stops being announced as provisional.
     *
     * ### The defect this function corrects
     *
     * The hard minimum of three nights comes from the night to night variability of the hourly
     * count. But that variability **is not the same on both sides of the threshold**. The PAM-RL
     * validation measures an intraclass correlation >= 0.90 from **3 nights when the PLMI exceeds
     * 15/h**, and **26 are needed when it is below** (Aritake-Okada 2014).
     *
     * Now [Position.BelowThreshold] — that is, the sentence that reassures — was returned exactly
     * in the regime where three nights give the worst reliability. The application was **more
     * confident when it reassured than when it warned**, which is the opposite of its purpose.
     *
     * ### What the function does, and what it does not do
     *
     * It does not touch the hard refusal at three nights: below three nights, nothing is
     * aggregated, and that does not change. It moves the boundary between *provisional* and
     * *final*, which is a label, not a gate — a number already computed is never hidden.
     *
     * ### The value 14 is not sourced, and the text says so
     *
     * 3 and 26 are measured. 14 is a compromise: asking for 26 nights would make the screen
     * unusable in practice, asking for 3 would make it confident precisely where it has the least
     * reason to be. `trend_nights_required_low` states both figures and names the compromise as
     * such.
     */
    fun requiredNights(ciLow: Double, ciHigh: Double): Int = when {
        ciLow > CLINICAL_THRESHOLD_PER_HOUR -> 3
        ciHigh < CLINICAL_THRESHOLD_PER_HOUR -> 14
        else -> 7
    }

    /** The reason, spelled out, for the number returned by [requiredNights]. Never a bare figure. */
    fun requiredNightsReason(ciLow: Double, ciHigh: Double): UiText = when {
        ciLow > CLINICAL_THRESHOLD_PER_HOUR -> text(R.string.trend_nights_required_high)
        ciHigh < CLINICAL_THRESHOLD_PER_HOUR -> text(R.string.trend_nights_required_low)
        else -> text(R.string.trend_nights_required_straddling)
    }

    /**
     * A pure function, five outcomes, tested on the exact bounds (`ciLow == 15`).
     *
     * It applies **only to the hourly count**. The fundamental rhythm has no equivalent: the
     * published threshold on periodicity (~ 0.5, Ferri 2016) is on another scale with other
     * evidence behind it, and transposing it mechanically would be manufacturing a clinical
     * boundary. See `trend_rhythm_no_threshold`.
     */
    fun position(ciLow: Double, ciHigh: Double, n: Int): Position = when {
        n < MIN_NIGHTS_AGGREGATE -> Position.Refusal
        n < MIN_NIGHTS_CATEGORY -> Position.Provisional(n)
        ciHigh < CLINICAL_THRESHOLD_PER_HOUR -> Position.BelowThreshold
        ciLow > CLINICAL_THRESHOLD_PER_HOUR -> Position.AboveThreshold
        else -> Position.SpansThreshold
    }

    // -------------------------------------------------------------------------------------
    // Comparison of two periods
    // -------------------------------------------------------------------------------------

    /**
     * The order of the fields is the display order, and it is not negotiable (P3): verdict, then
     * the two estimates, then the difference, then the user's own dispersion as a yardstick,
     * then the number of nights that would be needed.
     *
     * A hurried reader reads the first line and stops. It is therefore the first line that must
     * carry the reservation — not a note under the number.
     */
    data class Comparison(
        val distinguishable: Boolean,
        val a: Result,
        val b: Result,
        val difference: Double,
        val diffCiLow: Double,
        val diffCiHigh: Double,
        val dispersion: Double,
        val mdc95: Double,
        /** `null` when the required number exceeds 30: saying "out of reach" is more honest. */
        val nightsNeeded: Int?,
    ) {
        val verdict: UiText
            get() = if (distinguishable) {
                text(R.string.compare_distinguishable)
            } else {
                text(R.string.compare_inconclusive)
            }

        /** Why it is not conclusive: zero inside the interval, or a gap below the MDC95. */
        val inconclusiveReason: UiText?
            get() = when {
                distinguishable -> null
                abs(difference) <= mdc95 -> text(R.string.compare_below_mdc)
                else -> text(R.string.compare_zero_in_interval)
            }
    }

    /**
     * Two cumulative conditions to declare a difference distinguishable: the interval of the
     * difference excludes zero **and** the gap exceeds the MDC95.
     *
     * The second is not redundant. A bootstrap interval can exclude zero by a hair on few nights;
     * the MDC95 is a reminder that below a certain gap this method simply cannot decide, whatever
     * the luck of the draw.
     */
    fun compare(
        a: Result,
        b: Result,
        diffCiLow: Double,
        diffCiHigh: Double,
        nightsNeeded: Int?,
    ): Comparison {
        val diff = b.median - a.median
        val dispersion = maxOf(a.dispersion, b.dispersion)
        val mdc = mdc95(dispersion, minOf(a.nights, b.nights))
        val excludesZero = (diffCiLow > 0.0 && diffCiHigh > 0.0) || (diffCiLow < 0.0 && diffCiHigh < 0.0)
        return Comparison(
            distinguishable = excludesZero && abs(diff) > mdc,
            a = a,
            b = b,
            difference = diff,
            diffCiLow = diffCiLow,
            diffCiHigh = diffCiHigh,
            dispersion = dispersion,
            mdc95 = mdc,
            nightsNeeded = nightsNeeded?.takeIf { it <= 30 },
        )
    }

    // -------------------------------------------------------------------------------------
    // Periodicity: never bare
    // -------------------------------------------------------------------------------------

    /**
     * The periodicity index is never shown as such between 0 and 1.
     *
     * "0.58" means nothing to anyone — not to the user, not to the doctor who receives the sheet.
     * The rhythm is presented as an **interval in seconds**, which one can picture, and the
     * periodicity as a qualifier, available only when there are enough nights for a category to
     * mean something.
     */
    fun qualifyPeriodicity(index: Double, nights: Int): UiText? = when {
        nights < MIN_NIGHTS_CATEGORY -> null
        index >= 0.5 -> text(R.string.trend_periodicity_high)
        else -> text(R.string.trend_periodicity_low)
    }
}
