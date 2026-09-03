package com.pendulum.phone.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.model.PendulumError
import com.pendulum.phone.ui.model.AnalysisStep
import com.pendulum.phone.ui.model.WakingState
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The status strip at the head of the Trend screen: **one state at a time, at most one action**.
 *
 * P4 limits to three the pieces of information above the fold at waking: where last night stands,
 * the aggregate if it exists, one action. This strip carries the first, and a single action — not a
 * menu, not a choice, not a form. At 7 am, foggy, one-handed, a multiple choice is a choice one
 * does not make.
 */
@Composable
fun StatusStrip(
    state: WakingState,
    onAction: () -> Unit = {},
) {
    when (state) {
        WakingState.None -> Unit

        is WakingState.AwaitingTransfer -> PendulumCard {
            Title(stringResource(R.string.waking_pending_title, state.date))
            // The size and the delay are one estimate, and it exists only once the watch has
            // announced its chunk count. Before that — a night it has not closed — the model
            // carries `null` for both, and the body says so in words: the formatted variant used
            // to be filled with "0.0 MB" and "1 minutes" on exactly that morning.
            val mb = state.mb
            val minutes = state.minutes
            Paragraph(
                if (mb != null && minutes != null) {
                    stringResource(R.string.waking_pending_body, mb, minutes)
                } else {
                    stringResource(R.string.waking_pending_body_unknown)
                },
            )
            Spacer(Modifier.height(Spacing.sm.dp))
            Button(onClick = onAction, shape = PendulumShapes.button) {
                Text(stringResource(R.string.waking_pending_action))
            }
        }

        is WakingState.Transfer -> PendulumCard {
            Title(stringResource(R.string.waking_transfer_title, state.receivedMb, state.totalMb))
            Spacer(Modifier.height(Spacing.s.dp))
            // The percentage never goes backwards: the transfer resumes where it stopped, and a
            // counter that goes back down makes one believe data has been lost.
            Progress(state.fraction)
            Spacer(Modifier.height(Spacing.s.dp))
            Text(stringResource(R.string.waking_transfer_chunk, state.chunk, state.chunks), style = PendulumType.caption)
            Paragraph(stringResource(R.string.waking_transfer_body))
        }

        is WakingState.Analysis -> PendulumCard {
            Title(stringResource(R.string.waking_analysis_title, state.date))
            Spacer(Modifier.height(Spacing.s.dp))
            Progress(state.step.fraction)
            Spacer(Modifier.height(Spacing.s.dp))
            // Three step labels and not one more. The technical log lives in Settings.
            Text(stringResource(state.step.label), style = PendulumType.body)
            Text(stringResource(R.string.waking_analysis_remaining, state.secondsRemaining), style = PendulumType.caption)
        }

        // The NORMAL state at waking. No alert icon, no red, the word "provisional" in the title
        // and the explanation of the delay before the action: this is a result being consolidated,
        // not a failure, and presenting it otherwise would make the application look suspect every
        // morning.
        //
        // **And above all: no progress indicator here.** The two `Progress` bars in this file are
        // determinate and carry a wait of a few minutes. This one lasts hours, and Material 3
        // calibrates its indicator for waits of under five seconds: a circle spinning for six hours
        // is a failure message, whatever the text beside it says. What replaces the animation is
        // written in plain words — last attempt, next one, and the dated deadline for giving up.
        is WakingState.Provisional -> PendulumCard {
            val c = LocalPendulumColors.current
            Title(stringResource(R.string.waking_provisional_title, state.date))
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.waking_provisional_body))
            Spacer(Modifier.height(Spacing.sm.dp))
            Text(
                when {
                    state.gaveUp -> stringResource(R.string.waking_provisional_given_up)
                    state.lastAttempt == null && state.nextAttempt != null ->
                        stringResource(R.string.waking_provisional_first_attempt, state.nextAttempt)
                    state.nextAttempt != null ->
                        stringResource(
                            R.string.waking_provisional_attempts,
                            state.lastAttempt.orEmpty(),
                            state.nextAttempt,
                        )
                    else -> stringResource(R.string.waking_provisional_given_up)
                },
                style = PendulumType.caption,
                color = c.textTertiary,
            )
            if (!state.gaveUp) {
                Text(
                    stringResource(R.string.waking_provisional_give_up_at, state.givingUpAt),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedButton(onClick = onAction, shape = PendulumShapes.button) {
                Text(stringResource(R.string.waking_provisional_action))
            }
        }

        // One button only, and it does something: the end-of-night chain starts again. The
        // "See the technical detail" that used to accompany it called a `{}` — there is no
        // technical log screen in this module. See the KDoc of `ErrorCard`.
        is WakingState.Failure -> Column(Modifier.fillMaxWidth()) {
            ErrorCard(state.error, onAction = onAction)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = PendulumType.titleM, color = LocalPendulumColors.current.textPrimary)
}

// -----------------------------------------------------------------------------------------
// Previews — they also serve to produce the screenshots of the documentation, so the data is
// realistic and not "lorem ipsum".
// -----------------------------------------------------------------------------------------

@Preview(name = "Wake-up — state 1 pending", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewPending() = PendulumTheme {
    StatusStrip(WakingState.AwaitingTransfer("12 March", "8.8", 4))
}

@Preview(name = "Wake-up — state 1 pending, night not closed", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewPendingUnknownSize() = PendulumTheme {
    // The watch has gone quiet without closing: no chunk count, hence no size and no delay.
    StatusStrip(WakingState.AwaitingTransfer("12 March", null, null))
}

@Preview(name = "Wake-up — state 2 transfer", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewTransfer() = PendulumTheme {
    StatusStrip(WakingState.Transfer("4.1", "8.8", 8, 17))
}

@Preview(name = "Wake-up — state 3 analysis", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewAnalysis() = PendulumTheme {
    StatusStrip(WakingState.Analysis("12 March", AnalysisStep.DETECTION, 40))
}

@Preview(name = "Wake-up — state 4 provisional (normal case)", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewProvisional() = PendulumTheme {
    StatusStrip(WakingState.Provisional("12 March", "07:12", "08:12", "19:04", gaveUp = false))
}

@Preview(name = "Wake-up — state 4 after giving up at T+36 h", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewProvisionalGivenUp() = PendulumTheme {
    StatusStrip(WakingState.Provisional("12 March", "18:04", null, "19:04", gaveUp = true))
}

@Preview(name = "Wake-up — state 5 failure", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun PreviewFailure() = PendulumTheme {
    StatusStrip(
        WakingState.Failure(
            "12 March",
            PendulumError(
                code = "E-ANA-01",
                title = text(R.string.error_ana_01_title),
                cause = text(R.string.error_ana_01_cause),
                action = text(R.string.error_ana_01_action),
                button = text(R.string.error_ana_01_button),
                technical = true,
            ),
        ),
    )
}
