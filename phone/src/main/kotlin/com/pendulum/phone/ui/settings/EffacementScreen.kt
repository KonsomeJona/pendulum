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
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * L'effacement total, et sa confirmation.
 *
 * ### Pourquoi une confirmation, alors que le devoilement d'un chiffre n'en a pas
 *
 * C'est le meme raisonnement qu'au scellement du contexte du soir, pris dans l'autre sens. Une
 * confirmation ne sert pas a proteger d'une faute de doigt : elle sert la ou le geste est
 * **irreversible et non reconstituable**. Un chiffre devoile peut etre revu ; une nuit effacee ne
 * revient de nulle part — la montre n'archive rien, et Health Connect ne detient que
 * l'hypnogramme, jamais les mouvements.
 *
 * ### Un mot a taper, pas une case a cocher
 *
 * `settings_erase_confirmation` demande d'ecrire `ERASE`. Une boite de dialogue a deux
 * boutons se traverse au pouce sans etre lue — c'est le meme geste que le defilement d'une
 * politique de confidentialite. Taper cinq lettres n'est pas une punition : c'est le seul
 * mecanisme simple qui atteste que l'ecran a ete lu, et il est proportionne a ce qui part.
 *
 * ### Le texte dit ce qui part
 *
 * Signal brut, nuits, resultats, contextes scelles, questionnaires, stades de sommeil : nommes un
 * par un dans `settings_erase_body`. « Toutes vos donnees » laisse chacun imaginer
 * autre chose que ce qui va reellement disparaitre.
 */
@Composable
fun EffacementScreen(
    espaceOccupe: String,
    efface: Boolean,
    onEffacer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    var saisie by rememberSaveable { mutableStateOf("") }
    val autorise = saisie.trim() == stringResource(R.string.settings_erase_word)

    PendulumScreen(modifier) {
        Text(stringResource(R.string.settings_erase), style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            InlineValue(stringResource(R.string.settings_space_used), espaceOccupe)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.settings_erase_body), couleur = c.textPrimary)
        }

        if (efface) {
            // Apres coup, plus de champ ni de bouton : il n'y a plus rien a effacer, et laisser
            // le bouton actif inviterait a verifier en appuyant une seconde fois.
            PendulumCard {
                Text(stringResource(R.string.settings_erase_done), style = PendulumType.body, color = c.textPrimary)
            }
        } else {
            PendulumCard {
                Paragraphe(stringResource(R.string.settings_erase_confirmation))
                Spacer(Modifier.height(Spacing.s.dp))
                OutlinedTextField(
                    value = saisie,
                    onValueChange = { saisie = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_erase_field)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = onEffacer,
                enabled = autorise,
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
private fun ApercuEffacement() = PendulumTheme {
    EffacementScreen("612 MB", efface = false, onEffacer = {})
}

@Preview(name = "Erase — done", widthDp = 411, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuEffacementFait() = PendulumTheme {
    EffacementScreen("0 B", efface = true, onEffacer = {})
}
