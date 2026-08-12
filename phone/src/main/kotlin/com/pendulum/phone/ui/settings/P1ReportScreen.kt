package com.pendulum.phone.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.P1Gate
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** What the P1 report displays. Everything is already computed by [P1Gate], pure and tested on its bounds. */
data class P1ReportUi(
    val nights: List<P1Gate.NightVerdict>,
    val campaign: P1Gate.Campaign,
)

/**
 * The report of the P1 gate, in Settings › Measurement.
 *
 * ### The conclusion is at the top, the nights below
 *
 * One comes here with a question — "does it hold?" — and the answer is an integer: how many nights
 * in a row stayed within the three criteria. The per-night rows are what allows that integer to be
 * checked, not what one reads first.
 *
 * ### No gauge, no colour carrying anything on its own
 *
 * No coverage bar, no ring, no green and no red: each row carries its **measured value** and its
 * **threshold**, and the state is a sign — `✓`, `✗`, `—` — readable without telling colours apart
 * and identical in print. A gauge at 99 % and a gauge at 97 % have the same image; that is exactly
 * the difference this gate exists to settle.
 *
 * ### The dash is information
 *
 * A night with no known end has no coverage, it has a coverage *for now*; a six-hour night says
 * nothing about the battery at eight hours as long as it is above the threshold. Those rows
 * display a dash and the screen says why, rather than disappearing — a missing row reads as "this
 * check does not exist".
 */
@Composable
fun P1ReportScreen(
    state: P1ReportUi,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current

    PendulumScreen(modifier) {
        Text(stringResource(R.string.p1_title), style = PendulumType.titleL, color = c.textPrimary)
        Text(stringResource(R.string.p1_subtitle), style = PendulumType.caption, color = c.textTertiary)

        PendulumCard {
            SectionHeader(stringResource(R.string.p1_campaign))
            Text(
                stringResource(R.string.p1_run, state.campaign.longestStreak, P1Gate.CONSECUTIVE_NIGHTS),
                style = PendulumType.bodyEmph,
                color = c.textPrimary,
            )
            val streakStart = state.campaign.streakStart
            val streakEnd = state.campaign.streakEnd
            if (streakStart != null && streakEnd != null) {
                Text(
                    stringResource(R.string.p1_run_range, streakStart, streakEnd),
                    style = PendulumType.bodyNum,
                    color = c.textSecondary,
                )
            }
            Text(
                stringResource(
                    R.string.p1_counts,
                    state.campaign.compliantNights,
                    state.campaign.nightsExamined,
                ),
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            // The verdict is a sentence and not a dot: "Gate not passed" gets copied into a
            // message, an orange dot gets copied nowhere.
            Text(
                stringResource(
                    if (state.campaign.crossed) R.string.p1_gate_passed
                    else R.string.p1_gate_not_passed,
                ),
                style = PendulumType.bodyEmph,
                color = c.textPrimary,
            )
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.p1_nights))
            if (state.nights.isEmpty()) {
                Paragraph(stringResource(R.string.p1_no_night))
            } else {
                state.nights.forEach { NightVerdictRow(it) }
            }
        }

        PendulumCard {
            Paragraph(stringResource(R.string.p1_intro))
            Spacer(Modifier.height(Spacing.s.dp))
            // The battery figure is extrapolated when the night carries telemetry. An extrapolated
            // percentage looks like a measured percentage: what it assumes is written next to it,
            // on the same screen, and not only in a KDoc.
            Paragraph(stringResource(R.string.p1_battery_extrapolation))
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.p1_not_transmitted))
        }

        PendulumCard {
            Paragraph(stringResource(R.string.p1_why), color = c.textPrimary)
            Spacer(Modifier.height(Spacing.sm.dp))
            // The same SAF path as the export of a night: the application never writes into a
            // shared directory on its own initiative, the location is chosen gesture by gesture.
            ReasonedButton(stringResource(R.string.p1_export), null, onExport)
            Spacer(Modifier.height(Spacing.s.dp))
            Text(stringResource(R.string.export_no_network), style = PendulumType.caption, color = c.textTertiary)
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun NightVerdictRow(v: P1Gate.NightVerdict) {
    val c = LocalPendulumColors.current
    Column(Modifier.padding(vertical = Spacing.s.dp)) {
        Text(
            "${v.readableDate}  ·  ${verdictLabel(v.verdict)}",
            style = PendulumType.bodyEmph,
            color = c.textPrimary,
        )
        v.criteria.forEach {
            InlineValue(
                it.label.resolve(),
                "${it.value.resolve()}   ${verdictSign(it.state)}",
                note = it.threshold.resolve(),
            )
        }
    }
}

/** `✓ ✗ —`: the shape carries the state, the colour has nothing to carry. */
private fun verdictSign(e: P1Gate.Compliance): String = when (e) {
    P1Gate.Compliance.COMPLIANT -> "✓"
    P1Gate.Compliance.NON_COMPLIANT -> "✗"
    P1Gate.Compliance.UNDETERMINED -> "—"
}

/**
 * The word for the verdict. `@Composable` because it resolves a resource: the function is called
 * inside the interpolation of [NightVerdictRow], hence in composition, and keeping it pure would
 * force a `Context` to be passed by hand.
 */
@Composable
private fun verdictLabel(e: P1Gate.Compliance): String = when (e) {
    P1Gate.Compliance.COMPLIANT -> stringResource(R.string.p1_meets)
    P1Gate.Compliance.NON_COMPLIANT -> stringResource(R.string.p1_outside)
    P1Gate.Compliance.UNDETERMINED -> stringResource(R.string.p1_undecidable)
}
