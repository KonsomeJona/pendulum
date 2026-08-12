package com.pendulum.phone.ui.trend

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.BlockingState
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.formatValue
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The comparison of two periods.
 *
 * ### The display order is the message (P3)
 *
 * 1. the distinguishability verdict;
 * 2. the two estimates with their intervals;
 * 3. the difference with its interval;
 * 4. the user's own dispersion, as the yardstick for the noise;
 * 5. the number of nights it would take to decide.
 *
 * The verdict comes **before** the figure because a hurried reader reads the first line and stops.
 * Putting "-9/h" at the top and the reservation underneath is publishing "-9/h".
 *
 * ### What this screen never says
 *
 * No verb of evolution, in either case. When the gap is indistinguishable, the first line is
 * "Inconclusive". When it is distinguishable, it is "Difference larger than the night-to-night
 * variability" — and the application states at once that it does not know what caused it:
 * medication, sleep, alcohol, iron, bedding or the position of the watch would produce the same
 * effect on screen.
 */
@Composable
fun ComparePeriodsScreen(
    result: Aggregate.Comparison?,
    unavailableReason: Pair<UiText, Int>?,
    periodALabel: String,
    periodBLabel: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumScreen(modifier) {
        if (unavailableReason != null || result == null) {
            val (period, nights) = unavailableReason ?: (text(R.string.compare_period_a) to 0)
            BlockingState(
                title = stringResource(R.string.compare_unavailable_title),
                body = stringResource(R.string.compare_unavailable_body, period.resolve(), nights),
            )
            return@PendulumScreen
        }

        PendulumCard {
            // 1. The verdict, first, in bold, without a figure.
            Text(result.verdict.resolve(), style = PendulumType.bodyEmph, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.sm.dp))

            // 2. The two estimates, each with its interval and its n (P2).
            InlineValue(
                stringResource(R.string.compare_period_a),
                estimateLine(result.a),
                note = periodALabel,
            )
            InlineValue(
                stringResource(R.string.compare_period_b),
                estimateLine(result.b),
                note = periodBLabel,
            )

            // 3. The difference, with its interval. Never drawn as an arrow: a downward arrow
            // reads as "this is going the right way".
            InlineValue(
                stringResource(R.string.compare_difference),
                "${sign(result.difference)}${formatValue(kotlin.math.abs(result.difference), result.a.quantity)} " +
                    "${stringResource(result.a.quantity.unit)}  (95% CI " +
                    "${sign(result.diffCiLow)}${formatValue(kotlin.math.abs(result.diffCiLow), result.a.quantity)} to " +
                    "${sign(result.diffCiHigh)}${formatValue(kotlin.math.abs(result.diffCiHigh), result.a.quantity)})",
            )

            Spacer(Modifier.height(Spacing.sm.dp))
            result.inconclusiveReason?.let { Paragraph(it.resolve()) }
            if (result.distinguishable) Paragraph(stringResource(R.string.compare_no_cause))

            Spacer(Modifier.height(Spacing.s.dp))
            // 4. The yardstick for the noise: the dispersion of the user's own nights.
            Text(
                stringResource(
                    R.string.trend_dispersion,
                    formatValue(result.dispersion, result.a.quantity),
                    stringResource(result.a.quantity.unit),
                ),
                style = PendulumType.caption,
                color = c.textTertiary,
            )

            Spacer(Modifier.height(Spacing.s.dp))
            // 5. The number of nights needed — an order of magnitude, and the text says so.
            Paragraph(
                result.nightsNeeded
                    ?.let { stringResource(R.string.compare_nights_needed, it) }
                    ?: stringResource(R.string.compare_out_of_reach),
            )
        }
    }
}

/**
 * The estimate line for a period: the median, its interval, the number of nights.
 *
 * It used to be assembled here by concatenation, which froze both "CI" and "nights" in English
 * **and the order of the three members**: a language that puts the number of nights first had no
 * way of saying so. The whole sentence is therefore a single resource, with positional arguments,
 * and the code now supplies nothing but the values.
 */
@Composable
private fun estimateLine(r: Aggregate.Result): String = stringResource(
    R.string.compare_estimate_line,
    formatValue(r.median, r.quantity),
    stringResource(r.quantity.unit),
    formatValue(r.ciLow, r.quantity),
    formatValue(r.ciHigh, r.quantity),
    r.nights,
)

private fun sign(v: Double): String = if (v < 0) "−" else "+"
