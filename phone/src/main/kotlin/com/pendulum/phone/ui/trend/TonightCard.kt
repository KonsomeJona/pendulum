package com.pendulum.phone.ui.trend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.SectionHeader
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.model.TonightUi
import com.pendulum.phone.ui.model.Feedback
import com.pendulum.phone.ui.text.resolve
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The "Prepare the night" card — the first of the three fixed cards on the home screen.
 *
 * ### It no longer comes and goes
 *
 * It was written to appear only between 8 pm and 4 am, and was never rendered by anyone. The time
 * window is removed: a card at the top of the screen that appears and disappears moves everything
 * below it vertically, twice a day, and the clock is not a reliable source of state — a night
 * worker goes to bed at 9 am. It is permanent from now on, and it is its button that carries the
 * state. Deliberate departure from `docs/06-interface.md` §2.2.
 *
 * ### What is an advisory and what is a gate
 *
 * A battery below 85 % is an **advisory**: the row turns amber, the text gives the figure, and
 * nothing is blocked. It is the user who decides whether to attempt the night.
 *
 * Sealing the context, on the other hand, is the product's only real **gate**: until it is done,
 * the watch refuses to start. The reason is guard rail 1 of `SPEC-v2.md` §3 — a dose and a
 * context noted after the fact are noted in the knowledge of the result, hence unusable.
 *
 * ### Why the button stays, even once sealed
 *
 * The context is append-only: sealing it twice throws. The button could therefore disappear once
 * the gesture is done — and the card would change height at the very moment the user has just
 * acted. It stays, greyed out, and **carries its reason**: the context is sealed on this phone.
 * The card has the same shape from the beginning to the end of the evening.
 *
 * ### The start button gives feedback
 *
 * `requestStart` can fail — the watch is out of range, or its app is not installed — and the
 * ViewModel already published both outcomes into a flow that **nobody collected**. "The watch is
 * recording" and "the watch received nothing" were therefore the same screen. This is the worst
 * place in the application to keep quiet about a failure: it is only found out on waking, when the
 * night is lost and there is nothing left to salvage.
 *
 * [startFeedback] sits under the button, on one line, and disappears — see the caller. The tint
 * comes from `Feedback.failed` and not from the text.
 */
@Composable
fun TonightCard(
    state: TonightUi,
    unavailableReason: String?,
    onSeal: () -> Unit,
    onStart: (() -> Unit)? = null,
    startFeedback: Feedback? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumCard(modifier) {
        SectionHeader(
            if (state.recording == null) {
                stringResource(R.string.home_prepare_title)
            } else {
                stringResource(R.string.tonight_recording)
            },
        )

        val rec = state.recording
        if (rec == null) {
            ValueRow(
                stringResource(R.string.tonight_row_watch),
                "${percentage(state.batteryPct)}  ·  ${state.freeSpace ?: DASH} free",
                if (state.batteryInsufficient) c.attention else null,
            )
            if (state.batteryInsufficient) {
                Text(stringResource(R.string.tonight_low_battery), style = PendulumType.caption, color = c.attention)
            }
            ValueRow(stringResource(R.string.tonight_row_strap), readableWearing(state))
            ValueRow(
                stringResource(R.string.tonight_row_sleep),
                state.sleepSource.resolve(),
                if (state.sourceActive == false) c.attention else null,
            )
            Spacer(Modifier.height(Spacing.sm.dp))

            Paragraph(stringResource(R.string.tonight_seal_body))
            Spacer(Modifier.height(Spacing.s.dp))
            Text(
                stringResource(
                    if (state.contextSealed) R.string.tonight_start_instruction
                    else R.string.tonight_seal_missing,
                ),
                style = if (state.contextSealed) PendulumType.bodyEmph else PendulumType.body,
                color = if (state.contextSealed) c.textPrimary else c.attention,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            // Once the context is sealed the watch accepts to start — and it can be asked to from
            // here. This is the deliberate departure from `docs/06-interface.md` §2.2, which
            // reserves the start for a physical gesture on the watch.
            //
            // What the departure does not cost: `RecordingService` re-checks the preflight before
            // starting, so this button bypasses nothing. What it brings: at bedtime the watch is
            // already on the ankle, under the duvet, and leaning over to wake it produces exactly
            // the movement artefact that the night's measurement is about to record.
            //
            // The instruction "press START on the watch" stays displayed above: the physical
            // gesture remains the nominal path, and this one doubles it without replacing it.
            if (state.contextSealed && onStart != null) {
                Spacer(Modifier.height(Spacing.s.dp))
                Button(
                    onClick = onStart,
                    shape = PendulumShapes.button,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tonight_start_on_watch)) }

                // Under the button and not in its place: a button's label stays invariant, which is
                // the same rule as the reason of `ReasonedButton`. Amber when the watch received
                // nothing — nothing is broken, but the instruction above becomes the only one that
                // works.
                startFeedback?.let {
                    Spacer(Modifier.height(Spacing.xs.dp))
                    Text(
                        it.message.resolve(),
                        style = PendulumType.caption,
                        color = if (it.failed) c.attention else c.textSecondary,
                    )
                }
            }

            ReasonedButton(
                label = stringResource(R.string.tonight_seal_button),
                unavailableReason = unavailableReason,
                onClick = onSeal,
            )
        } else {
            ValueRow(stringResource(R.string.tonight_row_since, rec.since), rec.duration)
            ValueRow(
                stringResource(R.string.tonight_row_samples),
                stringResource(
                    R.string.tonight_row_samples_value,
                    rec.samples,
                    "%.1f".format(rec.measuredHz),
                ),
            )
            ValueRow(
                stringResource(R.string.tonight_row_battery),
                stringResource(R.string.tonight_row_battery_value, rec.batteryPct, rec.gaps),
            )
            Spacer(Modifier.height(Spacing.s.dp))
            // Refreshed every 60 s, screen on and application in the foreground only. No phone
            // service, no persistent notification: nothing to keep an eye on.
            Paragraph(stringResource(R.string.tonight_stop_instruction))
        }
    }
}

/**
 * The wearing: strap and leg, or what is known of them.
 *
 * Before sealing, the leg is unknown and stays so — guessing it would distort the comparability
 * criterion without anything saying so.
 */
@Composable
private fun readableWearing(state: TonightUi): String =
    listOfNotNull(state.strap.takeIf { it.isNotBlank() }, state.leg?.resolve())
        .joinToString(", ")
        .ifBlank { DASH }

private fun percentage(pct: Int?): String = if (pct == null) DASH else "$pct%"

private const val DASH = "—"

@Composable
private fun ValueRow(label: String, value: String, tint: Color? = null) {
    val c = LocalPendulumColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = PendulumType.body, color = c.textSecondary)
        Text(value, style = PendulumType.bodyNum, color = tint ?: c.textPrimary)
    }
}
