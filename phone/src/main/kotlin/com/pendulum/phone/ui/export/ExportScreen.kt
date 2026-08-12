package com.pendulum.phone.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.CustomProfileBanner
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.Feedback
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

data class ExportUi(
    val includeQuestionnaire: Boolean,
    /** **Yes** by default: hiding the failed nights from a doctor is misleading. */
    val includeExcluded: Boolean,
    val eligibleNights: Int,
    val period: String,
    val customProfile: String?,
    /**
     * What the last write gave, or `null` as long as there has been none.
     *
     * The field carried the **file name** alone, set unconditionally after a `runCatching` that
     * threw its exception away: "Written: ..." was therefore displayed even when nothing had been
     * written. On the document one takes to the doctor without opening it again, that is the worst
     * place to keep a failure quiet.
     */
    val writeFeedback: Feedback? = null,
)

/**
 * The export of a report for the doctor.
 *
 * ### What is imposed in the document
 *
 * The header banner repeats the notice word for word: personal measurement, not a medical
 * examination, no diagnosis, no treatment decision. Then the period and the counters, the result
 * with its interval and its `n`, the trend chart, the per-night table, a night chart with its
 * hypnogram, the method in six lines, the four limits taken literally from the home screen, and
 * the questionnaire if it is included.
 *
 * The doctor's report puts the **hourly count** first, unlike the screen: it is the language of
 * sleep physicians and the published thresholds rest on it. The fundamental rhythm appears there
 * too, with its justification.
 *
 * ### What does not exist
 *
 * No network path. The application does not declare the `INTERNET` permission: that is a
 * **verifiable** guarantee — an `aapt dump permissions` is enough — where a privacy policy is a
 * promise.
 *
 * And **no `ACTION_SEND` either**: it would require a `FileProvider` in the manifest, that is, a
 * second outbound surface on top of the one SAF already opens. The file is written at the place
 * the user designates, gesture by gesture; what they then do with it belongs to their file
 * manager, which already knows how to share. One way out fewer to defend.
 *
 * ### If the export is impossible
 *
 * The button stays visible but disabled, **with the reason written on it**. Never an active button
 * that fails: someone who presses "Export" and receives an error learns to distrust every button
 * in the application.
 */
@Composable
fun ExportScreen(
    state: ExportUi,
    onQuestionnaire: (Boolean) -> Unit,
    onExcluded: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    val unavailableReason = if (state.eligibleNights < Aggregate.MIN_NIGHTS_AGGREGATE) {
        stringResource(R.string.export_unavailable, Aggregate.MIN_NIGHTS_AGGREGATE)
    } else {
        null
    }

    PendulumScreen(modifier) {
        Text(stringResource(R.string.export_title), style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            // The banner that will open the document, shown here as it is: what the doctor reads
            // first must not be a surprise for the one who prints it.
            Paragraph(stringResource(R.string.notice_export_banner), color = c.textPrimary)
        }

        CustomProfileBanner(state.customProfile)

        PendulumCard {
            SectionHeader(stringResource(R.string.export_section_format))
            Paragraph(stringResource(R.string.export_format_note))
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.export_section_content))
            Text(
                stringResource(R.string.export_period, state.period),
                style = PendulumType.body,
                color = c.textSecondary,
            )
            Text(
                stringResource(R.string.export_eligible_nights, state.eligibleNights),
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            CheckboxRow(state.includeQuestionnaire, stringResource(R.string.export_include_questionnaire), null, onQuestionnaire)
            CheckboxRow(
                state.includeExcluded,
                stringResource(R.string.export_include_excluded),
                stringResource(R.string.export_include_excluded_note),
                onExcluded,
            )
        }

        if (unavailableReason != null) {
            PendulumCard {
                Text(stringResource(R.string.error_exp_01_title), style = PendulumType.titleM, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraph(stringResource(R.string.error_exp_01_cause))
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraph(stringResource(R.string.error_exp_01_action))
                Text("E-EXP-01", style = PendulumType.caption, color = c.textTertiary)
            }
        }

        ReasonedButton(stringResource(R.string.export_save), unavailableReason, onSave)
        Text(stringResource(R.string.export_no_network), style = PendulumType.caption, color = c.textTertiary)
        // The feedback from the write, in both cases. On success, the file name and nothing else:
        // the full path of a SAF `Uri` is an unreadable provider identifier, and displaying it
        // would send the user looking for a folder that does not exist under that name. On
        // failure, what is verifiable — nothing was written — and the gesture that works.
        state.writeFeedback?.let {
            Text(
                it.message.resolve(),
                style = PendulumType.caption,
                color = if (it.failed) c.attention else c.textSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun CheckboxRow(checked: Boolean, rowTitle: String, note: String?, onChange: (Boolean) -> Unit) {
    val c = LocalPendulumColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(rowTitle, style = PendulumType.body, color = c.textPrimary)
            note?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
        }
    }
}

@Preview(name = "Export — possible", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ExportPreview() = PendulumTheme {
    ExportScreen(ExportUi(true, true, 6, "1–15 March", null), {}, {}, {})
}

@Preview(name = "Export — refused below 3 nights", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ExportRefusedPreview() = PendulumTheme {
    ExportScreen(ExportUi(true, true, 2, "1–15 March", "threshold 6×"), {}, {}, {})
}
