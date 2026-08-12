package com.pendulum.phone.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.ErrorCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.Feedback
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing
import com.pendulum.phone.ui.trend.TonightCard

/**
 * The home screen: three cards, in this order, all the time.
 *
 * ```
 * PREPARE THE NIGHT   watch, free space, strap, sleep source
 * END OF NIGHT        greyed out with its reason if there is no open session
 * HISTORY             n nights · m eligible
 * ```
 *
 * ### Why this screen exists
 *
 * The home screen used to be the Trend, and the Trend mixes two incompatible cognitive regimes: the
 * daily gesture — quick, memorised, done one-handed at 11 pm or at 7 am — and the reading of a
 * statistical result, slow and heavy. Put on the same screen, the first is paid for by the second:
 * one comes to press a button and reads a figure on the way, in the state in which one is least
 * able to judge it.
 *
 * ### Why the cards never move
 *
 * A card with nothing to do is **not withdrawn**: it is disabled and carries its reason. The cost of
 * content that appears and disappears is not visible on a mockup — it is visible in use, when the
 * target aimed at yesterday has moved today because it is 5 am and not 11 pm. Three fixed positions
 * are worth more than a screen that is right.
 *
 * ### What is not here
 *
 * No figure. No rhythm, no hourly count, no counter of eligible nights presented as a performance.
 * The "end of night" card may announce that a night has been recorded and its quality checked; that
 * is all it says until the result has been asked for — guard rail 2.
 */
@Composable
fun HomeScreen(
    state: HomeUi,
    onSeal: () -> Unit,
    onStart: () -> Unit,
    onEndOfNight: () -> Unit,
    onReveal: (String) -> Unit,
    onHistory: () -> Unit,
    /**
     * What the last start request came to, or `null` when there is nothing to say.
     *
     * It is not part of [HomeUi] and that is not an oversight: `HomeUi` describes a state read from
     * the database, rebuilt on every emission of the flow, whereas this is the outcome of a
     * gesture, which belongs to the screen session and not to the night.
     */
    startFeedback: Feedback? = null,
    onSleepSituation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    PendulumScreen(modifier) {
        // 0 — The sleep permission, **before** everything else when it is missing.
        //
        // Without a hypnogram, the sleep time that serves as the denominator comes from the same
        // signal as the counted movements: the application then refuses to let that figure carry
        // the result, and every night ends with nothing to show. This card used to live only on the
        // trend — a screen one opens only after three nights, and which stays shut as long as they
        // are not there. The one screen that is seen every day said nothing.
        state.sleepSituation?.let { ErrorCard(it, onAction = onSleepSituation) }

        // 1 — Prepare the night. The sealing is the product's only gate.
        TonightCard(
            state = state.tonight,
            unavailableReason = state.prepareReason?.resolve(),
            onSeal = onSeal,
            onStart = onStart,
            startFeedback = startFeedback,
        )

        // 2 — End of night. One card, one action: either the night is closed, or the result of the
        // last one is asked for. Never both — at 7 am, one-handed, a multiple choice is a choice one
        // does not make.
        EndOfNightCard(state, onEndOfNight, onReveal)

        // 3 — History. The list of nights is a stacked destination: one goes there, one does not
        // live there.
        HistoryCard(state, onHistory)

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun EndOfNightCard(
    state: HomeUi,
    onEndOfNight: () -> Unit,
    onReveal: (String) -> Unit,
) {
    val c = LocalPendulumColors.current
    val toReveal = state.nightToReveal
    PendulumCard {
        SectionHeader(stringResource(R.string.home_end_title))
        Text(state.endOfNightLine.resolve(), style = PendulumType.body, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraph(
            if (toReveal != null) {
                stringResource(R.string.home_result_hidden_body)
            } else {
                stringResource(R.string.home_end_body)
            },
        )
        Spacer(Modifier.height(Spacing.sm.dp))
        // A single gesture to reveal: no confirmation dialog, no warning to accept. The friction is
        // a speed bump, not a toll — wanting one's figure on waking is the norm and not the
        // exception. What remains is the trace, and it is silent.
        if (toReveal != null) {
            ReasonedButton(
                label = stringResource(R.string.home_result_button),
                unavailableReason = null,
                onClick = { onReveal(toReveal) },
            )
        } else {
            ReasonedButton(
                label = stringResource(R.string.home_end_button),
                unavailableReason = state.endOfNightReason?.resolve(),
                onClick = onEndOfNight,
            )
        }
    }
}

@Composable
private fun HistoryCard(state: HomeUi, onHistory: () -> Unit) {
    val c = LocalPendulumColors.current
    PendulumCard {
        SectionHeader(stringResource(R.string.home_history_title))
        Text(state.historyLine.resolve(), style = PendulumType.bodyNum, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        ReasonedButton(
            label = stringResource(R.string.home_history_button),
            unavailableReason = state.historyReason?.resolve(),
            onClick = onHistory,
        )
    }
}
