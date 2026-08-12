package com.pendulum.phone.ui.nights

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.chart.Hypnogram
import com.pendulum.phone.ui.chart.HypnogramSpec
import com.pendulum.phone.ui.chart.MetrologyBand
import com.pendulum.phone.ui.chart.MetrologySpec
import com.pendulum.phone.ui.chart.NightChart
import com.pendulum.phone.ui.chart.NightChartSpec
import com.pendulum.phone.ui.chart.XTransform
import com.pendulum.phone.ui.common.DataTableSheet
import com.pendulum.phone.ui.common.ErrorCard
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.ComputationPath
import com.pendulum.phone.ui.model.Feedback
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.model.PendulumError
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** What the detail of a night can display, computed upstream by the ViewModel. */
data class NightDetailUi(
    val night: NightUi,
    val inBed: String,
    /**
     * The chart of the signal, or `null` when the envelope is not available.
     *
     * It is computed during the analysis and **is not persisted**: rebuilding it requires
     * re-reading the night's raw chunks and redoing the processing chain, which has no place in
     * the opening of a screen. As long as that re-reading does not exist, the section is not
     * drawn — rather than drawn empty or with a manufactured curve.
     *
     * It is the same rule as below three nights: an empty axis invites the eye to imagine the
     * curve that is missing. The rest of the detail — the counts, the quality checks, the
     * hypnogram — comes from the database and is displayed.
     */
    val chart: NightChartSpec?,
    val hypnogram: HypnogramSpec?,
    /**
     * The state of the device during the night, on the **same axis** as the two bands above.
     *
     * It is independent of [chart] and of [hypnogram]: it comes from `telemetry_point`, which
     * ingestion fills as the chunks arrive, where the envelope requires redoing the whole
     * processing chain. A night can therefore have its state band without having its curve — and
     * that is the common case as long as the re-reading of the raw data does not exist.
     *
     * `null` when the night carries no telemetry: a night recorded before the `TLM!` block
     * existed, or by a watch whose chunks are in v1 of the format.
     */
    val metrology: MetrologySpec?,
    val movements: Int,
    val plms: Int,
    val plmw: Int,
    val postureExcluded: Int,
    val durationExcluded: Int,
    val series: Int,
    val imiMedianSec: Double,
    val checks: List<Check>,
    val appliedRule: UiText,
    /**
     * The computation path of the figure — "why 18.4 /h". `null` when the night has no result
     * yet, so nothing to explain.
     */
    val why: ComputationPath.Block? = null,
    /**
     * The situation of the night, or `null` when it calls for no explanation. Amber for
     * situations, red for what is really broken: see [com.pendulum.phone.ui.model.Situations].
     */
    val situation: PendulumError? = null,
)

/** A quality check: its measured value, its threshold, its state. All three, always. */
/**
 * A row of the quality table.
 *
 * [ok] has **three** states and not two, because the measurement has three. `null` means "we do
 * not know" — the value was not measured, or was not transmitted by the watch — and that is
 * neither a pass nor a failure. `Checks` already documented that distinction and the P1 gate
 * rendered it (`UNDETERMINED`), but this type carried only a boolean: the callers therefore
 * flattened the unknown, three of them to `false` (`== true`) and one to `true` (the largest gap,
 * never transmitted, shown as met). The same unknown read `✗` on three rows and `✓` on the fourth.
 */
data class Check(val label: UiText, val value: UiText, val threshold: UiText, val ok: Boolean?)

/**
 * The detail of a night — five sections.
 *
 * This is the only place where the figure of a night is presented with its warning **merged into
 * the same block**. The merge is deliberate: a large figure followed by a caution underneath
 * produces exactly the behaviour one wants to avoid, because the figure is read and the caution is
 * not. Here the sentence is part of the value block, at the same distance from the eye.
 *
 * The settings panel, collapsed by default, is where the anti-self-deception rule bites: there is
 * **no** "recompute this night". A change of parameter is global, bumps the `paramsHash`, and
 * triggers a rescore of every night from the raw data.
 *
 * ### Guard rail 2: the result is hidden until it has been asked for
 *
 * As long as `night_session.revealedAtMs` is null, the value block, the charts and the detail of
 * the events are not rendered. What stays visible is what `01-overview.md` §4 describes as the
 * morning screen: the night was recorded, its quality was verified — and the list of checks,
 * further down, shows it in figures.
 *
 * **A single gesture lifts the mask.** No confirmation modal, no warning to accept. The
 * justification is measured rather than assumed: over about 8,000 responses from patients
 * receiving their laboratory results before medical review, 95.7% want to receive them immediately
 * and only 7.5% report increased worry. Wanting one's figure is therefore the norm; the friction
 * must slow the gesture down, not tax it.
 *
 * What is not negotiable is the **trace**: the reveal is timestamped in the database and comes out
 * in the export. It is silent — permission is not asked for, the date is recorded.
 */
@Composable
fun NightDetailScreen(
    detail: NightDetailUi,
    onSeeTrend: () -> Unit,
    onApplyToAll: () -> Unit,
    onReveal: () -> Unit,
    onExportReport: () -> Unit,
    onExportBundle: () -> Unit,
    /**
     * What the last of the two exports gave, or `null` when there has been none.
     *
     * Both buttons were mute: neither success nor failure. The raw bundle is the worst placed to
     * be so — it is the only transportable copy of a night, and believing it written before
     * erasing one's data loses the raw data.
     */
    writeFeedback: Feedback? = null,
    modifier: Modifier = Modifier,
) {
    val revealed = detail.night.revealedAtMs != null
    val c = LocalPendulumColors.current
    // **A single transform for the three bands.** It is what makes the axis common, and the common
    // axis is what prevents a measurement artefact from being read as a physiological event. The
    // window comes from the chart when it exists, from the metrology band otherwise — the two
    // specs carry the same bounds, built together by the ViewModel.
    val window = detail.chart?.let { it.startMs to it.endMs }
        ?: detail.metrology?.let { it.startMs to it.endMs }
    val transform = remember(window) {
        window?.let { (start, end) -> XTransform(start, end) }
    }
    var cursor by remember { mutableStateOf<Long?>(null) }
    var valuesOpen by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }

    PendulumScreen(modifier) {
        // --- Section 1: header + night value, merged
        PendulumCard {
            Text(stringResource(R.string.night_detail_title, detail.night.readableDate), style = PendulumType.titleL, color = c.textPrimary)
            Text(
                "${detail.night.start} → ${detail.night.end}  ·  ${detail.inBed} in bed  ·  ${detail.night.readableSleep}",
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.sm.dp))
            if (revealed) {
                Text(
                    "${Mapping.readableRhythm(detail.night.rhythmSec).resolve()}  ·  " +
                        Mapping.readableCount(detail.night.plmiCount),
                    style = PendulumType.metricL,
                    color = c.textSecondary,
                )
                Text(
                    stringResource(R.string.nights_single_value),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraph(stringResource(R.string.nights_single_value_long))
                Spacer(Modifier.height(Spacing.s.dp))
                TextButton(onClick = onSeeTrend) { Text(stringResource(R.string.nights_see_trend)) }
            } else {
                Text(
                    stringResource(R.string.home_result_recorded, detail.night.readableDate),
                    style = PendulumType.bodyEmph,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraph(stringResource(R.string.home_result_hidden_body))
                Spacer(Modifier.height(Spacing.sm.dp))
                ReasonedButton(
                    label = stringResource(R.string.home_result_button),
                    unavailableReason = null,
                    onClick = onReveal,
                )
            }
        }

        // --- Section 2: the three bands, a single X axis, a single cursor
        //
        // At the top, physiology — envelope and hypnogram, continuous, analogue, thin strokes. At
        // the bottom, metrology, separated by a clear gutter and drawn in a foreign grammar:
        // blocks, hard steps, rug plots, no curve. The axis is common because without it a
        // measurement artefact is read as a physiological event; the form is separate because the
        // battery and the movements have no causality between them.
        //
        // Each band disappears when its data is missing, and none is drawn empty: no empty frame,
        // no error message. The envelope is not persisted and is not yet read back from the raw
        // data; the telemetry, for its part, is in the database from ingestion onwards, so the
        // state band appears on nights where the curve is still missing.
        //
        // The device state band is in the **same card** as the two others, and not in a card of
        // its own: a separate card would be a second surface, therefore a second context, and the
        // alignment of the three axes would stop being obvious to the eye. What separates them is
        // the gutter, which belongs to the drawing.
        //
        // It is displayed even when the envelope is missing. This is not an exception to the "no
        // empty frame" rule: it does not draw the absence of the signal, it draws what the
        // telemetry carries, which exists independently.
        if (revealed && transform != null && (detail.chart != null || detail.metrology != null)) {
            PendulumCard {
                if (detail.chart != null && detail.hypnogram != null) {
                    NightChart(
                        spec = detail.chart,
                        transform = transform,
                        cursorMs = cursor,
                        onCursor = { cursor = it },
                        onEvent = {},
                        onValues = { valuesOpen = true },
                    )
                    Hypnogram(detail.hypnogram, transform, cursor)
                    Spacer(Modifier.height(Spacing.xs.dp))
                    Text(detail.hypnogram.statistics, style = PendulumType.caption, color = c.textTertiary)
                }
                detail.metrology?.let { metrology ->
                    MetrologyBand(metrology, transform, cursor)
                    Spacer(Modifier.height(Spacing.xs.dp))
                    // The legend says what the form already says to the eye: these lanes describe
                    // the recorder, not the sleeper. It is short and it is here, under the band,
                    // because a legend placed elsewhere is read after the conclusion.
                    Paragraph(stringResource(R.string.night_detail_metrology_note))
                }
            }
        }

        // --- Section 3: detected events
        //
        // Hidden with the rest as long as the result has not been asked for. The movement count is
        // not the index, but leaving it visible would amount to hiding only the division: a guard
        // rail that is bypassed by reading the line above is not one.
        if (revealed) PendulumCard {
            SectionHeader(stringResource(R.string.night_detail_events))
            InlineValue(stringResource(R.string.night_detail_movements), detail.movements.toString())
            InlineValue(stringResource(R.string.night_detail_of_which_sleep), detail.plms.toString())
            InlineValue(stringResource(R.string.night_detail_of_which_awake), detail.plmw.toString())
            InlineValue(stringResource(R.string.night_detail_excluded_posture), detail.postureExcluded.toString())
            InlineValue(stringResource(R.string.night_detail_excluded_duration), detail.durationExcluded.toString())
            // The coverage of the series was a separate field of `NightDetailUi`, fed by exactly
            // the same value as `night.readableSleep`, already carried by the same object. Two
            // fields for one value is two places where it can diverge.
            InlineValue(stringResource(R.string.night_detail_series), "${detail.series}   covering ${detail.night.readableSleep}")
            InlineValue(stringResource(R.string.night_detail_median_ioi), "%.1f s".format(detail.imiMedianSec))
            // The note changes with the value: when the fit was refused, explaining the harmonic
            // deconvolution would answer a question the row no longer asks. What must be said then
            // is **why there is nothing**, and that it is intended.
            InlineValue(
                stringResource(R.string.night_detail_fundamental_rhythm),
                Mapping.readableRhythm(detail.night.rhythmSec).resolve(),
                note = if (detail.night.rhythmSec == null) {
                    stringResource(R.string.night_detail_rhythm_not_fitted_note)
                } else {
                    stringResource(R.string.night_detail_rhythm_deconvolution_note)
                },
            )
        }

        // --- Section 3 bis: "why this figure"
        //
        // The only legitimate "why" here is **generative**: it shows the computation path, not
        // contributions. No ranking of factors by importance — those methods do not distinguish
        // correlation from causality and over-attribute as soon as the variables are correlated,
        // which is the case of every variable in this table.
        //
        // The last line of the block does all the work, and it comes from the model and not from
        // the screen: a computation path without its non-causality sentence cannot exist.
        if (revealed) detail.why?.let { block ->
            PendulumCard {
                SectionHeader(block.title.resolve())
                block.lines.forEach {
                    InlineValue(it.label.resolve(), it.value.resolve(), note = it.note?.resolve())
                }
                Spacer(Modifier.height(Spacing.sm.dp))
                Paragraph(block.disclaimer.resolve(), color = c.textPrimary)
            }
        }

        // --- Section 3 ter: the situation of the night, if there is one
        //
        // Amber for a short night, a night without a hypnogram, a discharged watch — these are
        // **situations**. Red for an incomplete transfer, which is really broken. The tint follows
        // `PendulumError.technical` and not the perceived severity.
        //
        // No action lambda, and that is correct: the four situations `Situations.night` returns no
        // longer have a button. The last one that carried one — "Force continuous mode" on
        // `E-NIGHT-04` — named a setting that does not exist, and it was rendered here, where the
        // action was `ErrorCard`'s default `{}`. A mute card says what happened; a mute button
        // says the application is not responding.
        detail.situation?.let { ErrorCard(it) }

        // --- Section 4: quality of the night
        PendulumCard {
            SectionHeader(stringResource(R.string.night_detail_quality))
            detail.checks.forEach {
                // The sign carries the state for the eye; `valueDescription` carries it for the
                // ear. Without it, "31.1% ✗" is announced as "31.1 percent" and the failure
                // disappears — the sign was the sole carrier of the only information this table
                // exists to give.
                val sign = when (it.ok) {
                    true -> "✓"
                    false -> "✗"
                    null -> Mapping.DASH
                }
                val stateLabel = stringResource(
                    when (it.ok) {
                        true -> R.string.night_detail_state_pass
                        false -> R.string.night_detail_state_fail
                        null -> R.string.night_detail_state_unknown
                    }
                )
                InlineValue(
                    it.label.resolve(),
                    "${it.value.resolve()}   $sign",
                    note = stringResource(R.string.night_detail_threshold, it.threshold.resolve()),
                    valueDescription = "${it.value.resolve()}, $stateLabel",
                )
            }
            InlineValue(
                stringResource(R.string.night_detail_rule_applied),
                detail.appliedRule.resolve(),
            )
        }

        // --- Section 5: parameters
        PendulumCard {
            TextButton(onClick = { paramsOpen = !paramsOpen }) {
                Text(stringResource(R.string.night_detail_advanced_params))
            }
            if (paramsOpen) {
                Paragraph(stringResource(R.string.night_detail_no_per_night_setting))
                Spacer(Modifier.height(Spacing.s.dp))
                OutlinedButton(onClick = onApplyToAll, shape = PendulumShapes.button) {
                    Text(stringResource(R.string.night_detail_recompute_all))
                }
            }
        }

        // --- Section 6: the two exports of this night
        //
        // They are here and not in the export screen, which carries the campaign: these are two
        // documents of one precise night, produced when that particular night raises a question.
        // The path is the same as everywhere else — SAF, location chosen by the user.
        PendulumCard {
            SectionHeader(stringResource(R.string.night_detail_export))
            Paragraph(stringResource(R.string.night_detail_export_report_note))
            OutlinedButton(onClick = onExportReport, shape = PendulumShapes.button) {
                Text(stringResource(R.string.night_detail_export_report))
            }
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.night_detail_export_bundle_note))
            OutlinedButton(onClick = onExportBundle, shape = PendulumShapes.button) {
                Text(stringResource(R.string.night_detail_export_bundle))
            }
            // A single feedback for the two buttons: they write one after the other, never
            // together, and two lines of which only one is fresh are read wrongly.
            writeFeedback?.let {
                Spacer(Modifier.height(Spacing.s.dp))
                Text(
                    it.message.resolve(),
                    style = PendulumType.caption,
                    color = if (it.failed) c.attention else c.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }

    val chart = detail.chart
    if (revealed && valuesOpen && chart != null) {
        DataTableSheet(
            columns = listOf(
                stringResource(R.string.night_detail_table_index),
                stringResource(R.string.night_detail_table_start),
                stringResource(R.string.night_detail_table_duration),
                stringResource(R.string.night_detail_table_amplitude),
            ),
            rows = chart.markers.take(200).mapIndexed { i, m ->
                listOf(
                    "${i + 1}",
                    "${(m.onsetMs - chart.startMs) / 1000} s",
                    m.durationMs?.let { "%.1f s".format(it / 1000.0) } ?: "—",
                    m.amplitudeRatio?.let { "×%.1f".format(it) } ?: "—",
                )
            },
            onClose = { valuesOpen = false },
        )
    }
}
