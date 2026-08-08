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
import com.pendulum.phone.ui.model.ErreurPendulum
import com.pendulum.phone.ui.model.EtapeAnalyse
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.text.texte
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * La bande d'etat en tete de l'ecran Tendance : **un seul etat a la fois, au plus une action**.
 *
 * P4 limite a trois informations au-dessus de la ligne de flottaison au reveil : ou en est la
 * nuit d'hier, l'agregat s'il existe, une action. Cette bande porte la premiere, et une seule
 * action — pas un menu, pas un choix, pas un formulaire. A 7 h du matin, embrume, une main, un
 * choix multiple est un choix qu'on ne fait pas.
 */
@Composable
fun StatusStrip(
    etat: EtatReveil,
    onAction: () -> Unit = {},
) {
    when (etat) {
        EtatReveil.Rien -> Unit

        is EtatReveil.EnAttenteTransfert -> PendulumCard {
            Titre(stringResource(R.string.waking_pending_title, etat.date))
            Paragraphe(stringResource(R.string.waking_pending_body, etat.mo, etat.minutes))
            Spacer(Modifier.height(Spacing.sm.dp))
            Button(onClick = onAction, shape = PendulumShapes.button) {
                Text(stringResource(R.string.waking_pending_action))
            }
        }

        is EtatReveil.Transfert -> PendulumCard {
            Titre(stringResource(R.string.waking_transfer_title, etat.recuMo, etat.totalMo))
            Spacer(Modifier.height(Spacing.s.dp))
            // Le pourcentage ne recule jamais : le transfert reprend ou il s'est arrete, et un
            // compteur qui redescend fait croire a une perte de donnees.
            Progression(etat.fraction)
            Spacer(Modifier.height(Spacing.s.dp))
            Text(stringResource(R.string.waking_transfer_chunk, etat.chunk, etat.chunks), style = PendulumType.caption)
            Paragraphe(stringResource(R.string.waking_transfer_body))
        }

        is EtatReveil.Analyse -> PendulumCard {
            Titre(stringResource(R.string.waking_analysis_title, etat.date))
            Spacer(Modifier.height(Spacing.s.dp))
            Progression(etat.etape.fraction)
            Spacer(Modifier.height(Spacing.s.dp))
            // Trois libelles d'etape et pas un de plus. Le log technique vit dans Reglages.
            Text(stringResource(etat.etape.libelle), style = PendulumType.body)
            Text(stringResource(R.string.waking_analysis_remaining, etat.secondesRestantes), style = PendulumType.caption)
        }

        // L'etat NORMAL du reveil. Aucune icone d'alerte, aucun rouge, le mot « provisoire » en
        // titre et l'explication du delai avant l'action : c'est un resultat en cours de
        // consolidation, pas une panne, et le presenter autrement rendrait l'application
        // suspecte tous les matins.
        //
        // **Et surtout : aucun indicateur de progression ici.** Les deux `Progression` de ce
        // fichier sont determinees et portent une attente de quelques minutes. Celle-ci dure des
        // heures, et Material 3 calibre son indicateur pour des attentes de moins de cinq
        // secondes : un cercle qui tourne six heures est un message de panne, quoi que dise le
        // texte a cote. Ce qui remplace l'animation est ecrit en clair — derniere tentative,
        // prochaine, et l'echeance datee de l'abandon.
        is EtatReveil.Provisoire -> PendulumCard {
            val c = LocalPendulumColors.current
            Titre(stringResource(R.string.waking_provisional_title, etat.date))
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.waking_provisional_body))
            Spacer(Modifier.height(Spacing.sm.dp))
            Text(
                when {
                    etat.abandonne -> stringResource(R.string.waking_provisional_given_up)
                    etat.derniereTentative == null && etat.prochaineTentative != null ->
                        stringResource(R.string.waking_provisional_first_attempt, etat.prochaineTentative)
                    etat.prochaineTentative != null ->
                        stringResource(
                            R.string.waking_provisional_attempts,
                            etat.derniereTentative.orEmpty(),
                            etat.prochaineTentative,
                        )
                    else -> stringResource(R.string.waking_provisional_given_up)
                },
                style = PendulumType.caption,
                color = c.textTertiary,
            )
            if (!etat.abandonne) {
                Text(
                    stringResource(R.string.waking_provisional_give_up_at, etat.abandonA),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedButton(onClick = onAction, shape = PendulumShapes.button) {
                Text(stringResource(R.string.waking_provisional_action))
            }
        }

        // Un seul bouton, et il fait quelque chose : la chaine de fin de nuit repart. Le
        // « See the technical detail » qui l'accompagnait appelait un `{}` — il n'existe aucun
        // ecran de journal technique dans ce module. Voir la KDoc d'`ErrorCard`.
        is EtatReveil.Echec -> Column(Modifier.fillMaxWidth()) {
            ErrorCard(etat.erreur, onAction = onAction)
        }
    }
}

@Composable
private fun Titre(texte: String) {
    Text(texte, style = PendulumType.titleM, color = LocalPendulumColors.current.textPrimary)
}

// -----------------------------------------------------------------------------------------
// Apercus — ils servent aussi a produire les captures d'ecran de la documentation, donc les
// donnees sont realistes et non des « lorem ipsum ».
// -----------------------------------------------------------------------------------------

@Preview(name = "Wake-up — state 1 pending", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuEnAttente() = PendulumTheme {
    StatusStrip(EtatReveil.EnAttenteTransfert("12 March", "8.8", 4))
}

@Preview(name = "Wake-up — state 2 transfer", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuTransfert() = PendulumTheme {
    StatusStrip(EtatReveil.Transfert("4.1", "8.8", 8, 17))
}

@Preview(name = "Wake-up — state 3 analysis", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuAnalyse() = PendulumTheme {
    StatusStrip(EtatReveil.Analyse("12 March", EtapeAnalyse.DETECTION, 40))
}

@Preview(name = "Wake-up — state 4 provisional (normal case)", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuProvisoire() = PendulumTheme {
    StatusStrip(EtatReveil.Provisoire("12 March", "07:12", "08:12", "19:04", abandonne = false))
}

@Preview(name = "Wake-up — state 4 after giving up at T+36 h", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuProvisoireAbandonne() = PendulumTheme {
    StatusStrip(EtatReveil.Provisoire("12 March", "18:04", null, "19:04", abandonne = true))
}

@Preview(name = "Wake-up — state 5 failure", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuEchec() = PendulumTheme {
    StatusStrip(
        EtatReveil.Echec(
            "12 March",
            ErreurPendulum(
                code = "E-ANA-01",
                titre = texte(R.string.error_ana_01_title),
                cause = texte(R.string.error_ana_01_cause),
                action = texte(R.string.error_ana_01_action),
                bouton = texte(R.string.error_ana_01_button),
                technique = true,
            ),
        ),
    )
}
