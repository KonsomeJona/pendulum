package com.pendulum.phone.ui.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.pendulum.phone.data.EveningEntry
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.ReasonedButton
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.SealingResult
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * The evening form — the screen that was missing, and the only gate of the product.
 *
 * `tonight_seal_title` and `tonight_seal_confirmation` had been written from the start and were
 * referenced by no composable: the specification described this screen, the button existed on the
 * "Tonight" card, and it called an `onSeal = {}`. All that time the watch refused to start,
 * sending the user back here.
 *
 * ### What is asked, and what is not
 *
 * Exactly the columns of `NightContextEntity`, no more and no less. A form that collects more than
 * what the database seals collects health data that nothing protects; a form that collects less
 * leaves empty columns of which nobody will be able to say, six months later, whether they are
 * wrong or simply absent.
 *
 * ### Why a confirmation, when the rest of the application has none
 *
 * Sealing is the only irreversible gesture of the application: the SQLite triggers then refuse any
 * modification. A confirmation is therefore not there to protect against a typing mistake — it is
 * there so that the user knows **before** that the leg and the tightness notch just entered can no
 * longer be corrected. Correcting them in the morning, after seeing the figure, is precisely what
 * the guard rail prevents.
 *
 * ### The three outcomes are not the same outcome
 *
 * [SealingResult] carries three of them and the caller treated them identically: the screen
 * closed. Only one of the three is a success.
 *
 * [SealingResult.PublicationFailed] is the costliest because it is silent **and** contradictory:
 * the database has the context, so home displays it as sealed, while the watch — which has not
 * received the `DataItem` — keeps displaying "fill in the evening form". The two devices
 * contradict each other and neither of them is wrong. The screen therefore stays open and says
 * what to do: wait for the replay, which is enqueued, without entering anything again.
 *
 * [SealingResult.AlreadySealed] is an `OnConflictStrategy.ABORT` that threw. What has to be said is
 * that the entry just made **was not recorded** — closing without saying it would let one believe
 * it replaced the previous one, that is to say exactly the touch-up the guard rail exists to
 * prevent.
 */
@Composable
fun EveningContextScreen(
    strapReference: String,
    /**
     * The result of the last attempt, or `null` as long as there has been none. On
     * [SealingResult.Sealed] this screen has nothing to display: the caller closes it.
     */
    result: SealingResult?,
    onSeal: (EveningEntry) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current

    // `rememberSaveable` and not `remember`, for every field. The activity neither fixes its
    // orientation nor declares `configChanges`, so a rotation or an unfolding recreates it, and a
    // process killed by an incoming call at 23:00 does the same. Every field used to go back to
    // its default — leg to "right", "alone" to true, the dose and the notes to blank — and a form
    // retyped at that hour is retyped in a hurry or abandoned, which leaves the evening unsealed
    // and the watch refusing to start. Same reason as the four acknowledgements of
    // `DisclaimerPage`. All the values are strings and booleans: the Bundle takes them as they
    // are, no `Saver` to write.
    var leg by rememberSaveable { mutableStateOf(EveningEntry.LEG_RIGHT) }
    // The strap field shows the reference noted during onboarding until the user touches it, and
    // only what they typed is state — `null` as long as they have not.
    //
    // It used to be `remember { mutableStateOf(strapReference) }`, which freezes the parameter at
    // the first composition. The ViewModel exposes the reference as a `StateFlow` seeded with "",
    // whose DataStore value arrives a frame later, so the field was empty every single evening and
    // the button greyed out with "strap missing". The user retyped the reference by hand, and one
    // evening "hole 5" instead of "hole 4" is a night sealed for good with a different strap —
    // exactly the divergence the pre-fill exists to prevent. Deriving the value keeps the field in
    // step with the reference for as long as the user has not spoken, without an effect that would
    // race the restored state after a process death.
    var strapEdit by rememberSaveable { mutableStateOf<String?>(null) }
    val strap = strapEdit ?: strapReference
    var alone by rememberSaveable { mutableStateOf(true) }
    var medication by rememberSaveable { mutableStateOf("") }
    var coffee by rememberSaveable { mutableStateOf(false) }
    var alcohol by rememberSaveable { mutableStateOf("") }
    var exercise by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }

    var confirmation by rememberSaveable { mutableStateOf(false) }

    // The strap is the only required field: it is a hard comparability criterion, and a night
    // sealed without it will come out excluded in the morning. All the others have a defensible
    // default value.
    val complete = strap.isNotBlank()

    // After normalisation the only text the alcohol field can hold that no parser reads is a lone
    // ".". Sealing it used to record 0.0 without a word, on a row the triggers then refuse to
    // correct — so the button carries the reason instead of letting it through.
    val alcoholReadable = alcoholParsable(alcohol)

    PendulumScreen(modifier) {
        Text(stringResource(R.string.tonight_seal_title), style = PendulumType.titleL, color = c.textPrimary)
        Paragraph(stringResource(R.string.tonight_seal_body))

        PendulumCard {
            SectionHeader(stringResource(R.string.tonight_section_wearing))

            // Two buttons rather than a drop-down list: there are only two legs, and a drop-down
            // list hides the current value behind a gesture.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s.dp)) {
                LegChoice(stringResource(R.string.tonight_leg_left), leg == EveningEntry.LEG_LEFT) {
                    leg = EveningEntry.LEG_LEFT
                }
                LegChoice(stringResource(R.string.tonight_leg_right), leg == EveningEntry.LEG_RIGHT) {
                    leg = EveningEntry.LEG_RIGHT
                }
            }
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = strap,
                onValueChange = { strapEdit = it },
                label = { Text(stringResource(R.string.tonight_field_strap)) },
                supportingText = { Text(stringResource(R.string.tonight_field_strap_help), style = PendulumType.caption) },
                singleLine = true,
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.tonight_section_evening))
            SwitchRow(stringResource(R.string.tonight_field_alone), stringResource(R.string.tonight_field_alone_help), alone) { alone = it }
            SwitchRow(stringResource(R.string.tonight_field_coffee), null, coffee) { coffee = it }
            SwitchRow(stringResource(R.string.tonight_field_exercise), null, exercise) { exercise = it }
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = alcohol,
                // Not a bare digit-and-dot filter: a French keyboard sends the comma, and the
                // filter used to drop it, so "1,5" was sealed as fifteen units. See
                // `normaliseAlcoholInput` for what is kept and what is refused.
                onValueChange = { alcohol = normaliseAlcoholInput(it) },
                label = { Text(stringResource(R.string.tonight_field_alcohol)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.tonight_section_dose))
            Paragraph(stringResource(R.string.tonight_field_dose_help))
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = medication,
                onValueChange = { medication = it },
                label = { Text(stringResource(R.string.tonight_field_dose)) },
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.tonight_field_notes)) },
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // What the sealing gave, when it is not a success. The card sits above the button and not
        // under it: it explains why the button has just greyed out, and a reason placed after the
        // element it justifies is read after the fact.
        //
        // Amber and not red in both cases. Nothing is broken: in the first, the database has the
        // context and the replay is enqueued; in the second, refusing the duplicate is the intended
        // behaviour. Red stays reserved for what is really broken.
        val blocked = result == SealingResult.PublicationFailed ||
            result == SealingResult.AlreadySealed
        if (blocked) {
            PendulumCard {
                Text(
                    stringResource(
                        if (result == SealingResult.PublicationFailed) {
                            R.string.tonight_seal_unpublished_title
                        } else {
                            R.string.tonight_seal_already_title
                        },
                    ),
                    style = PendulumType.titleM,
                    color = c.attention,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraph(
                    stringResource(
                        if (result == SealingResult.PublicationFailed) {
                            R.string.tonight_seal_unpublished_body
                        } else {
                            R.string.tonight_seal_already_body
                        },
                    ),
                    color = c.textPrimary,
                )
            }
        }

        // Same rule as on the notice: the label of the disabled state carries the reason, so it has
        // to be readable. Material's default disabled tints fall to 3.02:1.
        //
        // An evening is sealed only once: after either of the two blocking outcomes, replaying the
        // gesture can only raise `AlreadySealed`. The button is therefore greyed out with its
        // reason, rather than left active so as to fail again.
        ReasonedButton(
            label = stringResource(R.string.tonight_seal_button),
            unavailableReason = when {
                blocked -> stringResource(R.string.tonight_seal_locked)
                !complete -> stringResource(R.string.tonight_field_strap_missing)
                !alcoholReadable -> stringResource(R.string.tonight_field_alcohol_invalid)
                else -> null
            },
            onClick = { confirmation = true },
        )
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tonight_cancel))
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }

    if (confirmation) {
        AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text(stringResource(R.string.tonight_seal_title), style = PendulumType.titleM) },
            text = { Paragraph(stringResource(R.string.tonight_seal_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = false
                    onSeal(
                        EveningEntry(
                            leg = leg,
                            strap = strap.trim(),
                            aloneInBed = alone,
                            // The field is free-form and stored as it is. A structured dosage
                            // schema would presuppose knowing the list of molecules, their units
                            // and their equivalences — and a structured field that is only half
                            // right is worth less than a text the doctor reads for themselves.
                            medicationJson = medication.trim(),
                            caffeineAfter16h = coffee,
                            alcoholUnits = alcohol.toDoubleOrNull() ?: 0.0,
                            unusualExercise = exercise,
                            notes = notes.trim().takeIf { it.isNotBlank() },
                        )
                    )
                }) { Text(stringResource(R.string.tonight_seal_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }) { Text(stringResource(R.string.tonight_read_again)) }
            },
        )
    }
}

@Composable
private fun LegChoice(legLabel: String, selected: Boolean, onSelect: () -> Unit) {
    val c = LocalPendulumColors.current
    Button(
        onClick = onSelect,
        shape = PendulumShapes.button,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (selected) c.accent else c.surfaceElevated,
            contentColor = if (selected) c.background else c.textSecondary,
        ),
        modifier = Modifier.width(120.dp),
    ) {
        Text(legLabel, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SwitchRow(
    rowLabel: String,
    help: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val c = LocalPendulumColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rowLabel, style = PendulumType.body, color = c.textPrimary)
            help?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
