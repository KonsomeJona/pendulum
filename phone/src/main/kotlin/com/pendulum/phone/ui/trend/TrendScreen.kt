package com.pendulum.phone.ui.trend

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.ui.model.PendulumError
import com.pendulum.phone.ui.chart.PointState
import com.pendulum.phone.ui.chart.TrendChart
import com.pendulum.phone.ui.common.CustomProfileBanner
import com.pendulum.phone.ui.common.BlockingState
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.CollectionProgress
import com.pendulum.phone.ui.common.DataTableSheet
import com.pendulum.phone.ui.common.ErrorCard
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.MetricHeadline
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.PositionBox
import com.pendulum.phone.ui.common.StatusStrip
import com.pendulum.phone.ui.common.formatValue
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.RefusalReason
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.model.TrendUiState
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The Trend screen: the start destination, and the first screen designed for this product.
 *
 * ### What it no longer is
 *
 * The start destination. It was, and the home screen then mixed the daily gesture with the reading
 * of a statistical result — two incompatible cognitive regimes on the same screen. The "tonight"
 * card went with it, to `ui/home/HomeScreen.kt`: this screen now carries nothing but the reading.
 *
 * ### What it shows, in this order, and why this order
 *
 * 1. The waking status strip — where last night has got to.
 * 2. The **fundamental rhythm in seconds**, with its interval and its `n` on the following line.
 *    It is the quantity tracked since `SPEC-v2.md` §5: twelve times more stable from night
 *    to night than the hourly count, and without a denominator.
 * 3. The **hourly count in second place**, with its position sentence and its 15/h threshold.
 *    It stays because it is the language of sleep physicians, but it no longer drives the tracking.
 * 4. The chart.
 * 5. The active rules, the night counter, the questionnaire, the report.
 *
 * ### What it never shows
 *
 * Below three eligible nights: nothing. No median, no category, no chart — **not even an empty
 * chart with its axes**, because an empty axis invites the eye to imagine the curve that is
 * missing. The refusal is a branch in its own right, not a degraded state of the complete screen.
 */
@Composable
fun TrendScreen(
    state: TrendUiState,
    onNight: (String) -> Unit,
    /** The night list, where the "N nights recorded ... (see Nights)" counter leads. */
    onNights: () -> Unit,
    onCompare: () -> Unit,
    onQuestionnaire: () -> Unit,
    onExport: () -> Unit,
    onWakingAction: () -> Unit,
    /** The button on the Health Connect situation card: open Health Connect, or the settings. */
    onSleepSituation: () -> Unit,
    /**
     * True when the system has stopped showing the permission dialog. The sleep card then changes
     * its promise: it no longer announces a request — which would show nothing any more — but the
     * trip through the Health Connect screen, the only gesture left.
     */
    permissionSuppressed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    var valuesOpen by remember { mutableStateOf(false) }

    PendulumScreen(modifier) {
        when (state) {
            TrendUiState.Loading -> Unit

            is TrendUiState.Refusal -> {
                StatusStrip(state.waking, onWakingAction)
                // The sleep source situation comes **before** the refusal: a permission that was
                // never granted is repaired now, not after six nights scored on the accelerometric
                // mask alone.
                state.sleepSituation?.let { ErrorCard(sleepCard(it, permissionSuppressed), onAction = onSleepSituation) }
                RefusalCard(state, onNight)
                // The export is visible but disabled, with its reason written on the button:
                // never an active button that fails.
                //
                // The gate for the export is the **eligible night count**, the same one as in
                // `Ready.exportPossible`. A rhythm the model refused removes no night, and the
                // report is precisely where that refusal has to be written: disabling it here
                // would deprive the doctor of the very document that explains it.
                ReasonedButton(
                    label = stringResource(R.string.trend_report),
                    unavailableReason = if (state.reason == RefusalReason.RHYTHM_NOT_FITTED) {
                        null
                    } else {
                        stringResource(R.string.export_unavailable, Aggregate.MIN_NIGHTS_AGGREGATE)
                    },
                    onClick = onExport,
                )
            }

            is TrendUiState.Ready -> {
                StatusStrip(state.waking, onWakingAction)

                state.sleepSituation?.let { ErrorCard(sleepCard(it, permissionSuppressed), onAction = onSleepSituation) }

                CustomProfileBanner(state.customProfile)
                if (state.mixedHashes) {
                    PendulumCard { Paragraph(stringResource(R.string.trend_mixed_hashes), color = c.attention) }
                }

                PendulumCard {
                    state.provisionalBanner?.let {
                        Paragraph(it.resolve(), color = c.attention)
                        // The number of nights asked for depends on the position of the interval,
                        // and a bare number cannot be argued with: its reason is quantified and
                        // sourced, including when the source is missing — the low regime says that
                        // 14 is a compromise.
                        Text(
                            state.requiredNightsReason.resolve(),
                            style = PendulumType.caption,
                            color = c.textTertiary,
                        )
                        Spacer(Modifier.height(Spacing.sm.dp))
                    }

                    // Levels 1 and 2 of the display hierarchy: the quantity tracked, its interval,
                    // its n. The only place metricXL is used in the application.
                    MetricHeadline(
                        result = state.rhythm,
                        label = stringResource(R.string.trend_rhythm_label),
                        qualifier = state.qualifiedPeriodicity,
                    )

                    if (!state.rhythm.ciCalibrated) {
                        // What the interval really is below six nights, with the measurement that
                        // says so. A badly calibrated interval displayed without reservation would
                        // be the most embarrassing defect in this product — it is the interval
                        // that carries everything.
                        Spacer(Modifier.height(Spacing.s.dp))
                        Paragraph(stringResource(R.string.trend_interval_uncalibrated_note), color = c.textTertiary)
                    }

                    Spacer(Modifier.height(Spacing.sm.dp))
                    Paragraph(stringResource(R.string.trend_rhythm_no_threshold))

                    Spacer(Modifier.height(Spacing.m.dp))

                    // Second rank: the hourly count. Same P2 rule — value, interval and n on the
                    // same line — but at body text size.
                    Text(
                        stringResource(
                            R.string.trend_count_second_rank,
                            Math.round(state.count.median).toInt(),
                            Math.round(state.count.ciLow).toInt(),
                            Math.round(state.count.ciHigh).toInt(),
                            state.count.nights,
                        ),
                        style = PendulumType.bodyNum,
                        color = c.textSecondary,
                    )
                    Text(stringResource(R.string.trend_count_label), style = PendulumType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraph(stringResource(R.string.trend_count_note))

                    Spacer(Modifier.height(Spacing.sm.dp))
                    // The position sentence, a neutral frame in all five cases.
                    state.position.sentence()?.let { PositionBox(it.resolve()) }

                    // **The 15/h threshold, labelled, right under the sentence that invokes it.**
                    //
                    // It is the best benefit/effort ratio on the whole screen, and that is not an
                    // opinion: over 1 618 adults, adding to a reference bound a mention along the
                    // lines of "many doctors are not concerned below this value" brings the
                    // request for urgent contact down from 55.8 % to 34.7 % on near-normal values
                    // (Zikmund-Fisher, JMIR 2018). A bare line at 15 is exactly the same device as
                    // a bare bound on a laboratory report. The caption additionally carries the
                    // PSG / ankle actigraphy gap.
                    Spacer(Modifier.height(Spacing.s.dp))
                    Paragraph(stringResource(R.string.chart_threshold_15_legend), color = c.textTertiary)

                    Spacer(Modifier.height(Spacing.s.dp))
                    Text(
                        stringResource(
                            R.string.trend_dispersion,
                            formatValue(state.rhythm.dispersion, state.rhythm.quantity),
                            stringResource(state.rhythm.quantity.unit),
                        ),
                        style = PendulumType.caption,
                        color = c.textTertiary,
                    )
                }

                PendulumCard {
                    TrendChart(
                        spec = state.chart,
                        onNight = onNight,
                        onValues = { valuesOpen = true },
                    )
                }

                PendulumCard {
                    ActionRow(
                        stringResource(R.string.trend_compare),
                        stringResource(R.string.trend_compare_subtitle),
                        onCompare,
                    )
                }

                PendulumCard {
                    InlineValue(stringResource(R.string.trend_counting_rule), state.rule.resolve())
                    InlineValue(stringResource(R.string.trend_sleep_mask), state.mask.resolve())
                    InlineValue(stringResource(R.string.trend_movements_awake), Mapping.readableCount(state.plmw))
                    InlineValue(
                        stringResource(R.string.trend_periodicity),
                        // Never the bare index: a qualifier, or nothing.
                        state.qualifiedPeriodicity?.resolve() ?: "—",
                        note = stringResource(R.string.trend_periodicity_note),
                    )
                    InlineValue(
                        stringResource(R.string.trend_missed_rate),
                        state.missRate?.let { "${Math.round(it * 100)}%" } ?: Mapping.DASH,
                        note = stringResource(R.string.trend_missed_rate_note),
                    )
                }

                PendulumCard {
                    // The counter carries "(see Nights)" in its text, and it could be tapped
                    // without doing anything. A row that announces where to go and does not go
                    // there teaches you not to try the others.
                    ActionRow(
                        stringResource(
                            R.string.waking_counter,
                            state.recordedNights,
                            state.eligibleNights,
                            state.excludedNights,
                        ),
                        null,
                        onNights,
                    )
                    ActionRow(
                        stringResource(R.string.trend_questionnaire),
                        state.questionnaireState.resolve(),
                        onQuestionnaire,
                    )
                }

                ReasonedButton(
                    label = stringResource(R.string.trend_report),
                    unavailableReason = if (state.exportPossible) null else stringResource(R.string.export_unavailable, Aggregate.MIN_NIGHTS_AGGREGATE),
                    onClick = onExport,
                )

                Spacer(Modifier.height(Spacing.l.dp))
            }
        }
    }

    if (valuesOpen && state is TrendUiState.Ready) {
        DataTableSheet(
            columns = listOf(
                stringResource(R.string.trend_table_date),
                stringResource(R.string.trend_table_rhythm),
                stringResource(R.string.trend_table_state),
            ),
            rows = state.chart.points.map {
                listOf(
                    formatShortDay(it.dateMs),
                    "${Math.round(it.value)} s",
                    stringResource(readableState(it.state)),
                )
            },
            onClose = { valuesOpen = false },
        )
    }
}

/**
 * The refusal to aggregate, in its two reasons.
 *
 * The reason is given **in figures** and not as an instruction. The intended user is a technician:
 * the figure "one night in three" convinces them, "please wait" irritates them and pushes them to
 * look for a way round.
 *
 * The two reasons share the same shape and share nothing else. The second —
 * [RefusalReason.RHYTHM_NOT_FITTED] — is the more frequent: the model refuses to publish a period
 * that the intervals do not identify, 18 times out of 20 on simulated nights. Presenting it with
 * the night counter would make somebody who has nine of them read "nights are missing", and
 * presenting it in red would make a breakdown out of the product doing exactly what it is asked to.
 *
 * The link to a night's detail stays active in both cases: that is where the per-night figure
 * lives, and the technician has to be able to check that their measurement worked.
 */
@Composable
private fun RefusalCard(state: TrendUiState.Refusal, onNight: (String) -> Unit) {
    val c = LocalPendulumColors.current
    val rhythm = state.reason == RefusalReason.RHYTHM_NOT_FITTED
    BlockingState(
        title = stringResource(
            if (rhythm) R.string.trend_rhythm_refusal_title else R.string.trend_refusal_title,
        ),
        body = stringResource(
            if (rhythm) R.string.trend_rhythm_refusal_body else R.string.trend_refusal_body,
        ),
        action = stringResource(
            if (rhythm) R.string.trend_rhythm_refusal_action else R.string.trend_refusal_action,
        ),
        header = {
            Column(
                Modifier.fillMaxWidth().padding(bottom = Spacing.m.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(
                        if (rhythm) R.string.trend_rhythm_counter else R.string.trend_nights_counter,
                        state.acquiredNights,
                        state.requiredNights,
                    ),
                    style = PendulumType.titleL,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                CollectionProgress(state.acquiredNights, state.requiredNights)
            }
        },
        footer = {
            Text(
                stringResource(
                    if (rhythm) R.string.trend_rhythm_refusal_list else R.string.trend_refusal_list,
                ),
                style = PendulumType.label,
                color = c.textTertiary,
            )
            state.recordedNights.forEach { n -> CompactNightRow(n, onNight) }
        },
    )
}

@Composable
private fun CompactNightRow(n: NightUi, onNight: (String) -> Unit) {
    val c = LocalPendulumColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onNight(n.sessionHex) }
            .padding(vertical = Spacing.s.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(n.readableDate, style = PendulumType.body, color = c.textPrimary)
        Text(n.readableSleep, style = PendulumType.bodyNum, color = c.textSecondary)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s.dp),
    ) {
        Text(title, style = PendulumType.body, color = c.textPrimary)
        subtitle?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
    }
}

/**
 * A night's state in the "State" column of the values sheet.
 *
 * It used to be written `it.state.name.lowercase()`, which painted the name of the Kotlin constant
 * onto the screen — so `masque_accelero` and `ecartee`, two French words in an English interface,
 * and three strings that no `values-fr/` could ever have reached.
 */
@StringRes
private fun readableState(state: PointState): Int = when (state) {
    PointState.ELIGIBLE -> R.string.trend_table_state_eligible
    PointState.ACCEL_MASKED -> R.string.trend_table_state_accel_mask
    PointState.EXCLUDED -> R.string.trend_table_state_excluded
}

private fun formatShortDay(ms: Long): String {
    val days = ms / 86_400_000L
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    return "%02d/%02d".format(d, month)
}

// -----------------------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------------------


/**
 * The sleep card, adapted to what the system still agrees to do.
 *
 * As long as the permission dialog can be shown, the original card is right: its button asks for
 * the permission, and that is the shortest gesture. Once the dialog has been suppressed — two
 * refusals, then a definitive silence — that button lies: it would show nothing any more. The card
 * then says where to go and its button takes you there.
 *
 * The cause stays the same, only the procedure changes: that is why the situation code (`E-HC-02`)
 * and the title do not move.
 */
private fun sleepCard(base: PendulumError, suppressed: Boolean): PendulumError =
    if (!suppressed) base
    else base.copy(
        action = text(R.string.error_hc_02_action_manual),
        button = text(R.string.error_hc_02_button_manual),
    )
