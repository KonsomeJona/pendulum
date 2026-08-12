package com.pendulum.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** What the settings screen displays. Everything is already resolved by the ViewModel. */
data class SettingsUi(
    val rule: UiText,
    val preferredSource: UiText,
    val profile: UiText,
    val wearingReference: UiText,
    val autoStop: UiText,
    val watch: UiText,
    val healthConnect: UiText,
    val spaceUsed: UiText,
    val appVersion: UiText,
    val algoVersion: UiText,
    val theme: UiText,
    /**
     * The feedback from the last bundle re-import, or `null` when there has been none in this
     * session. It is here and not in a snackbar: an import that failed must stay readable
     * afterwards, and a successful import must say which night came in.
     */
    val lastImport: UiText? = null,
)

/**
 * The settings: flat lists, no search, no deep sub-menus.
 *
 * Two things deserve to be here rather than elsewhere.
 *
 * The **notice** is permanently reachable from "About". It is not merely shown once at first
 * launch: someone who consults a figure three months later must be able to re-read, in two
 * gestures, why that figure is not a diagnosis.
 *
 * The **accelerometer-only mask** appears in the list of sources, but the screen says why it can
 * never carry the main result: the denominator would be computed from the same signal as the
 * numerator, and a null effect could then show up as a clear-cut change.
 */
@Composable
fun SettingsScreen(
    state: SettingsUi,
    onReadNoticeAgain: () -> Unit,
    onErase: () -> Unit,
    onImportNight: () -> Unit,
    onP1Report: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumScreen(modifier) {
        PendulumCard {
            SectionHeader(stringResource(R.string.settings_measurement))
            InlineValue(stringResource(R.string.trend_counting_rule), state.rule.resolve())
            InlineValue(stringResource(R.string.settings_preferred_source), state.preferredSource.resolve())
            InlineValue(stringResource(R.string.settings_param_profile), state.profile.resolve())
            InlineValue(stringResource(R.string.settings_wearing_reference), state.wearingReference.resolve())
            InlineValue(stringResource(R.string.settings_auto_stop), state.autoStop.resolve())
            // The P1 report is here and not in "About": it bears on what the sensor really
            // delivered, which is the subject of this card. It is also the only screen in the
            // application that speaks about the hardware rather than about the sleeper.
            SettingsRow(stringResource(R.string.p1_title), stringResource(R.string.p1_subtitle), onP1Report)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.settings_accel_mask_forbidden))
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.trend_rules_snackbar))
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.settings_devices))
            InlineValue(stringResource(R.string.settings_watch), state.watch.resolve())
            InlineValue(stringResource(R.string.settings_health_connect), state.healthConnect.resolve())
        }

        // Three rows have disappeared from this card and from the next one, and this is
        // deliberately a removal and not a postponement: "Purge raw signals older than 90 days",
        // "Technical log" and "Scientific sources" were clickable and called a `{}`. No selective
        // purge, no persisted log and no offline corpus exists in this module — no table, no file,
        // no function. A settings row that opens onto nothing is indistinguishable, for whoever
        // presses it, from a broken application; and a row announcing an automatic purge that
        // nothing performs is a false statement about health data processing. They will come back
        // together with their implementation.
        PendulumCard {
            SectionHeader(stringResource(R.string.settings_data))
            InlineValue(stringResource(R.string.settings_space_used), state.spaceUsed.resolve())
            SettingsRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_note), onImportNight)
            SettingsRow(
                stringResource(R.string.settings_erase),
                stringResource(R.string.settings_erase_confirmation),
                onErase,
            )
            state.lastImport?.let {
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(it.resolve(), style = PendulumType.caption, color = c.textSecondary)
            }
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.settings_appearance))
            // Dark by default, and forced at first launch: consulted at night and in the morning,
            // often in the dark.
            InlineValue(stringResource(R.string.settings_theme), state.theme.resolve())
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.settings_about))
            InlineValue(stringResource(R.string.settings_app_version), state.appVersion.resolve())
            InlineValue(stringResource(R.string.settings_algo_version), state.algoVersion.resolve())
            SettingsRow(stringResource(R.string.settings_read_notice_again), null, onReadNoticeAgain)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.notice_export_banner), color = c.textSecondary)
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun SettingsRow(rowTitle: String, rowSubtitle: String?, onClick: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.s.dp),
    ) {
        Text(rowTitle, style = PendulumType.body, color = c.textPrimary)
        rowSubtitle?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
    }
}
