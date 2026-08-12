package com.pendulum.phone.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraph
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * Total erasure, and its confirmation.
 *
 * ### Why a confirmation here, when revealing a figure has none
 *
 * It is the same reasoning as at the sealing of the evening context, taken the other way round. A
 * confirmation is not there to protect against a slip of the finger: it is there where the gesture
 * is **irreversible and cannot be reconstituted**. A revealed figure can be looked at again; an
 * erased night comes back from nowhere — the watch archives nothing, and Health Connect holds only
 * the hypnogram, never the movements.
 *
 * ### A word to type, not a box to tick
 *
 * `settings_erase_confirmation` asks for `ERASE` to be written. A two-button dialog is crossed
 * with the thumb without being read — the same gesture as scrolling through a privacy policy.
 * Typing five letters is not a punishment: it is the only simple mechanism that attests the screen
 * was read, and it is proportionate to what goes.
 *
 * ### The text says what goes
 *
 * Raw signal, nights, results, sealed contexts, questionnaires, sleep stages: named one by one in
 * `settings_erase_body`. "All your data" leaves everyone imagining something other than what is
 * really going to disappear.
 */
@Composable
fun ErasureScreen(
    spaceUsed: String,
    erased: Boolean,
    onErase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    var typed by rememberSaveable { mutableStateOf("") }
    val allowed = typed.trim() == stringResource(R.string.settings_erase_word)

    PendulumScreen(modifier) {
        Text(stringResource(R.string.settings_erase), style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            InlineValue(stringResource(R.string.settings_space_used), spaceUsed)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraph(stringResource(R.string.settings_erase_body), color = c.textPrimary)
        }

        if (erased) {
            // Afterwards, no field and no button any more: there is nothing left to erase, and
            // leaving the button active would invite checking by pressing a second time.
            PendulumCard {
                Text(stringResource(R.string.settings_erase_done), style = PendulumType.body, color = c.textPrimary)
            }
        } else {
            PendulumCard {
                Paragraph(stringResource(R.string.settings_erase_confirmation))
                Spacer(Modifier.height(Spacing.s.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_erase_field)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = onErase,
                enabled = allowed,
                shape = PendulumShapes.button,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.error,
                    contentColor = c.onAccent,
                    disabledContainerColor = c.surfaceMuted,
                    disabledContentColor = c.textTertiary,
                ),
            ) {
                Text(stringResource(R.string.settings_erase_button), style = PendulumType.body)
            }
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Preview(name = "Erase — confirmation", widthDp = 411, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ErasePreview() = PendulumTheme {
    ErasureScreen("612 MB", erased = false, onErase = {})
}

@Preview(name = "Erase — done", widthDp = 411, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun EraseDonePreview() = PendulumTheme {
    ErasureScreen("0 B", erased = true, onErase = {})
}
