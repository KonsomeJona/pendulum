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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.pendulum.phone.data.SaisieDuSoir
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * Le formulaire du soir — l'ecran qui manquait, et la seule porte du produit.
 *
 * `Textes.CeSoir.SCELLEMENT_TITRE` et `SCELLEMENT_CONFIRMATION` etaient ecrits depuis le debut et
 * n'etaient references par aucun composable : la specification decrivait cet ecran, le bouton
 * existait sur la carte « Ce soir », et il appelait un `onSceller = {}`. Pendant ce temps la
 * montre refusait de demarrer en renvoyant ici.
 *
 * ### Ce qui est demande, et ce qui ne l'est pas
 *
 * Exactement les colonnes de `NightContextEntity`, ni plus ni moins. Un formulaire qui collecte
 * plus que ce que la base scelle collecte des donnees de sante que rien ne protege ; un
 * formulaire qui en collecte moins laisse des colonnes vides dont personne ne saura dire, six
 * mois plus tard, si elles sont fausses ou simplement absentes.
 *
 * ### Pourquoi une confirmation, alors que le reste de l'application n'en a pas
 *
 * Le scellement est le seul geste irreversible de l'application : les declencheurs SQLite
 * refusent ensuite toute modification. Une confirmation ne sert donc pas a proteger d'une faute
 * de frappe — elle sert a ce que l'utilisateur sache **avant** que la jambe et le cran de serrage
 * qu'il vient de saisir ne pourront plus etre corriges. Les corriger au matin, apres avoir vu le
 * chiffre, est precisement ce que le garde-fou empeche.
 */
@Composable
fun EveningContextScreen(
    repereDeSerrage: String,
    onSceller: (SaisieDuSoir) -> Unit,
    onAnnuler: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current

    var jambe by remember { mutableStateOf(SaisieDuSoir.JAMBE_DROITE) }
    // Pre-rempli avec le repere note a l'assistant : il doit etre identique d'une nuit a l'autre,
    // donc le retaper chaque soir serait a la fois penible et une occasion de divergence.
    var bracelet by remember { mutableStateOf(repereDeSerrage) }
    var seul by remember { mutableStateOf(true) }
    var medicaments by remember { mutableStateOf("") }
    var cafe by remember { mutableStateOf(false) }
    var alcool by remember { mutableStateOf("") }
    var exercice by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    var confirmation by remember { mutableStateOf(false) }

    // Le bracelet est le seul champ exige : c'est un critere de comparabilite dur, et une nuit
    // scellee sans lui sortira ecartee au matin. Tous les autres ont un defaut defendable.
    val complet = bracelet.isNotBlank()

    PendulumScreen(modifier) {
        Text(Textes.CeSoir.SCELLEMENT_TITRE, style = PendulumType.titleL, color = c.textPrimary)
        Paragraphe(Textes.CeSoir.SCELLEMENT_CORPS)

        PendulumCard {
            SectionHeader(Textes.CeSoir.SECTION_PORT)

            // Deux boutons plutot qu'une liste deroulante : il n'y a que deux jambes, et une
            // liste deroulante cache la valeur courante derriere un geste.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s.dp)) {
                ChoixJambe(Textes.CeSoir.JAMBE_GAUCHE, jambe == SaisieDuSoir.JAMBE_GAUCHE) {
                    jambe = SaisieDuSoir.JAMBE_GAUCHE
                }
                ChoixJambe(Textes.CeSoir.JAMBE_DROITE, jambe == SaisieDuSoir.JAMBE_DROITE) {
                    jambe = SaisieDuSoir.JAMBE_DROITE
                }
            }
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = bracelet,
                onValueChange = { bracelet = it },
                label = { Text(Textes.CeSoir.CHAMP_BRACELET) },
                supportingText = { Text(Textes.CeSoir.CHAMP_BRACELET_AIDE, style = PendulumType.caption) },
                singleLine = true,
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PendulumCard {
            SectionHeader(Textes.CeSoir.SECTION_CONTEXTE)
            Interrupteur(Textes.CeSoir.CHAMP_SEUL, Textes.CeSoir.CHAMP_SEUL_AIDE, seul) { seul = it }
            Interrupteur(Textes.CeSoir.CHAMP_CAFE, null, cafe) { cafe = it }
            Interrupteur(Textes.CeSoir.CHAMP_EXERCICE, null, exercice) { exercice = it }
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = alcool,
                onValueChange = { alcool = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text(Textes.CeSoir.CHAMP_ALCOOL) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PendulumCard {
            SectionHeader(Textes.CeSoir.SECTION_DOSE)
            Paragraphe(Textes.CeSoir.CHAMP_DOSE_AIDE)
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = medicaments,
                onValueChange = { medicaments = it },
                label = { Text(Textes.CeSoir.CHAMP_DOSE) },
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.s.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(Textes.CeSoir.CHAMP_NOTES) },
                shape = PendulumShapes.field,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Meme regle qu'a l'avertissement : le libelle de l'etat desactive porte le motif, donc
        // il doit se lire. Les teintes desactivees par defaut de Material tombent a 3,02:1.
        BoutonMotive(
            libelle = Textes.CeSoir.SCELLEMENT_BOUTON,
            motifIndisponible = Textes.CeSoir.CHAMP_BRACELET_MANQUANT.takeIf { !complet },
            onClick = { confirmation = true },
        )
        TextButton(onClick = onAnnuler, modifier = Modifier.fillMaxWidth()) {
            Text(Textes.CeSoir.ANNULER)
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }

    if (confirmation) {
        AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text(Textes.CeSoir.SCELLEMENT_TITRE, style = PendulumType.titleM) },
            text = { Paragraphe(Textes.CeSoir.SCELLEMENT_CONFIRMATION) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = false
                    onSceller(
                        SaisieDuSoir(
                            jambe = jambe,
                            bracelet = bracelet.trim(),
                            seulDansLeLit = seul,
                            // Le champ est libre et stocke tel quel. Un schema de posologie
                            // structure supposerait de connaitre la liste des molecules, leurs
                            // unites et leurs equivalences — et un champ structure a moitie juste
                            // vaut moins qu'un texte que le medecin lit lui-meme.
                            medicationJson = medicaments.trim(),
                            cafeApres16h = cafe,
                            unitesAlcool = alcool.toDoubleOrNull() ?: 0.0,
                            exerciceInhabituel = exercice,
                            notes = notes.trim().takeIf { it.isNotBlank() },
                        )
                    )
                }) { Text(Textes.CeSoir.SCELLEMENT_BOUTON) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }) { Text(Textes.CeSoir.RELIRE) }
            },
        )
    }
}

@Composable
private fun ChoixJambe(libelle: String, choisi: Boolean, onChoisir: () -> Unit) {
    val c = LocalPendulumColors.current
    Button(
        onClick = onChoisir,
        shape = PendulumShapes.button,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (choisi) c.accent else c.surfaceElevated,
            contentColor = if (choisi) c.background else c.textSecondary,
        ),
        modifier = Modifier.width(120.dp),
    ) {
        Text(libelle, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Interrupteur(
    libelle: String,
    aide: String?,
    valeur: Boolean,
    onChanger: (Boolean) -> Unit,
) {
    val c = LocalPendulumColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(libelle, style = PendulumType.body, color = c.textPrimary)
            aide?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
        }
        Switch(checked = valeur, onCheckedChange = onChanger)
    }
}
