package com.pendulum.phone.ui.nights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.QualityChip
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The list of nights, in reverse chronological order.
 *
 * ### A failed night is never silently erased
 *
 * It appears, struck through, with its reason. The counter always explains where the difference
 * between "recorded" and "eligible" comes from. A night that disappeared without trace is a night
 * one forgets to explain, and a counter that does not add up makes the rest doubtful.
 *
 * ### There is no "exclude this night" button
 *
 * This is guard rail 4 of `SPEC-v2.md` §3, and the absence matters as much as what is present. An
 * exclusion decided **after** seeing the figure is a complete self-deception mechanism on its own:
 * one sets aside, in good faith, the nights that do not go in the expected direction, and the
 * trend that comes out of it is manufactured. The counter-measure is not to resist the temptation,
 * it is to make the gesture impossible — the exclusions are deterministic predicates evaluated in
 * SQL before any computation. The screen explains it once, plainly, rather than leaving the user
 * hunting for a button that does not exist.
 */
@Composable
fun NightListScreen(
    nights: List<NightUi>,
    onNight: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(NightFilter.ALL) }
    val shown = remember(nights, filter) { filter.filter(nights) }

    Column(modifier.fillMaxSize().padding(horizontal = Spacing.screen.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.s.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s.dp),
        ) {
            NightFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(stringResource(f.label), style = PendulumType.label) },
                    shape = PendulumShapes.chip,
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s.dp)) {
            items(shown, key = { it.sessionHex }) { NightRow(it, onNight) }
            item {
                Spacer(Modifier.height(Spacing.m.dp))
                PendulumCard { Paragraph(stringResource(R.string.nights_no_exclude_button)) }
                Spacer(Modifier.height(Spacing.l.dp))
            }
        }
    }
}

/**
 * The three filters. The label is a **resource identifier** and not a string: an `enum`
 * constructor has no `Context`, and resolving it here would freeze the language at the moment the
 * class is loaded.
 */
enum class NightFilter(@StringRes val label: Int) {
    ALL(R.string.nights_filter_all),
    ELIGIBLE(R.string.nights_filter_eligible),
    EXCLUDED(R.string.nights_filter_excluded),
    ;

    fun filter(l: List<NightUi>): List<NightUi> = when (this) {
        ALL -> l
        ELIGIBLE -> l.filter { it.state != NightState.EXCLUDED }
        EXCLUDED -> l.filter { it.state == NightState.EXCLUDED }
    }
}

/**
 * A night row.
 *
 * The figure of the night appears there — the user is a technician and hiding it from them would
 * be both condescending and counter-productive — but **at body size, in the secondary colour**,
 * with the mention "single-night value". The constraint is on prominence, not on availability
 * (P7). This figure never appears as a title, nor in a notification, nor in the summary of the
 * export.
 *
 * ### Except as long as it has not been asked for
 *
 * A night whose result has never been revealed shows "result not shown" in place of its value
 * (guard rail 2). Without that, the mask on the detail would hide nothing: opening the list would
 * be enough to read, without trace, the figure one is supposed to ask for. The reveal happens on
 * the detail screen, in one gesture, and it is timestamped.
 */
@Composable
fun NightRow(n: NightUi, onNight: (String) -> Unit) {
    val c = LocalPendulumColors.current
    val excluded = n.state == NightState.EXCLUDED
    val revealed = n.revealedAtMs != null
    PendulumCard(
        Modifier
            .heightIn(min = 72.dp)
            .clickable { onNight(n.sessionHex) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${n.readableDate}   ${n.shortDay}", style = PendulumType.titleM, color = c.textPrimary)
            StateDot(n.state)
        }
        Text(
            "${n.start} → ${n.end} · ${n.readableSleep} (${n.sleepSource.resolve()})",
            style = PendulumType.caption,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.xs.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) {
                    Mapping.readableRhythm(n.rhythmSec).resolve()
                } else {
                    stringResource(R.string.home_result_hidden_line)
                },
                style = PendulumType.bodyNum,
                color = if (revealed) c.textSecondary else c.textTertiary,
                textDecoration = if (excluded && revealed) TextDecoration.LineThrough else null,
            )
            if (revealed) {
                Text(
                    "   " + stringResource(R.string.nights_single_value),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
        }
        n.reason?.let {
            Text(
                "${stringResource(R.string.nights_state_excluded)}: ${it.resolve()}",
                style = PendulumType.caption,
                color = c.attention,
            )
        }
        if (n.flags.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs.dp)) {
                n.flags.take(3).forEach { QualityChip(it.label) }
                if (n.flags.size > 3) {
                    Text("+${n.flags.size - 3}", style = PendulumType.caption, color = c.textTertiary)
                }
            }
        }
    }
}

/** Three states, three distinct glyphs: colour never carries the information on its own. */
@Composable
private fun StateDot(state: NightState) {
    val c = LocalPendulumColors.current
    val (glyph, label, tint) = when (state) {
        NightState.ELIGIBLE -> Triple("●", stringResource(R.string.nights_state_eligible), c.accent)
        NightState.PROVISIONAL -> Triple("◐", stringResource(R.string.nights_state_provisional), c.attention)
        NightState.EXCLUDED -> Triple("○", stringResource(R.string.nights_state_excluded), c.textTertiary)
    }
    Text("$glyph $label", style = PendulumType.label, color = tint)
}
