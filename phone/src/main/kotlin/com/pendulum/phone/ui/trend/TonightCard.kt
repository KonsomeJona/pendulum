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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.ApercuDonnees
import com.pendulum.phone.ui.model.CeSoirUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * La carte « Ce soir », en tete de l'ecran Tendance, **visible entre 20 h et 4 h uniquement**.
 *
 * Hors de cette plage elle disparait completement — elle ne se grise pas, elle n'est pas repliee.
 * Au reveil elle n'a plus rien a dire, et P4 limite a trois informations au-dessus de la ligne de
 * flottaison. Une carte inutile mais presente coute une des trois places.
 *
 * ### Ce qui est un avis et ce qui est une porte
 *
 * La batterie sous 85 % est un **avis** : la ligne passe en ambre, le texte donne le chiffre, et
 * rien n'est bloque. C'est l'utilisateur qui decide s'il tente la nuit.
 *
 * Le scellement du contexte, lui, est la seule vraie **porte** du produit : tant qu'il n'est pas
 * fait, la montre refuse de demarrer. Le motif est le garde-fou 1 de `SPEC-v2.md` §3 — une dose
 * et un contexte notes apres coup sont notes en connaissance du resultat, donc inutilisables. Le
 * bouton dit ce qu'il fait, et la confirmation dit que c'est irreversible.
 */
@Composable
fun TonightCard(
    etat: CeSoirUi,
    onSceller: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumCard(modifier) {
        SectionHeader(if (etat.enregistrement == null) Textes.CeSoir.TITRE else Textes.CeSoir.EN_COURS)

        val enr = etat.enregistrement
        if (enr == null) {
            Ligne(
                Textes.CeSoir.LIGNE_MONTRE,
                "${etat.batteriePct}%  ·  ${etat.espaceLibre} free",
                if (etat.batterieInsuffisante) c.attention else null,
            )
            if (etat.batterieInsuffisante) {
                Text(Textes.CeSoir.BATTERIE_BASSE, style = PendulumType.caption, color = c.attention)
            }
            Ligne(Textes.CeSoir.LIGNE_BRACELET, "${etat.bracelet}, ${etat.jambe}")
            Ligne(
                Textes.CeSoir.LIGNE_SOMMEIL,
                "${etat.sourceSommeil}, ${if (etat.sourceActive) "active" else "inactive"}",
                if (etat.sourceActive) null else c.attention,
            )
            Spacer(Modifier.height(Spacing.sm.dp))

            if (etat.contexteScelle) {
                Text(Textes.CeSoir.SCELLEMENT_FAIT, style = PendulumType.body, color = c.textSecondary)
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(Textes.CeSoir.CONSIGNE_DEMARRAGE, style = PendulumType.bodyEmph, color = c.textPrimary)
            } else {
                Paragraphe(Textes.CeSoir.SCELLEMENT_CORPS)
                Spacer(Modifier.height(Spacing.s.dp))
                Text(Textes.CeSoir.SCELLEMENT_MANQUANT, style = PendulumType.body, color = c.attention)
                Spacer(Modifier.height(Spacing.s.dp))
                Button(onClick = onSceller, shape = PendulumShapes.button, modifier = Modifier.fillMaxWidth()) {
                    Text(Textes.CeSoir.SCELLEMENT_BOUTON)
                }
            }
        } else {
            Ligne("Since ${enr.depuis}", enr.duree)
            Ligne("Samples", "${enr.echantillons}  ·  ${"%.1f".format(enr.hzMesures)} Hz")
            Ligne("Watch battery", "${enr.batteriePct}%  ·  ${enr.trous} gaps")
            Spacer(Modifier.height(Spacing.s.dp))
            // Rafraichissement 60 s, ecran allume et application au premier plan uniquement.
            // Aucun service telephone, aucune notification persistante : rien a surveiller.
            Paragraphe(Textes.CeSoir.CONSIGNE_ARRET)
        }
    }
}

@Composable
private fun Ligne(libelle: String, valeur: String, teinte: Color? = null) {
    val c = LocalPendulumColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(libelle, style = PendulumType.body, color = c.textSecondary)
        Text(valeur, style = PendulumType.bodyNum, color = teinte ?: c.textPrimary)
    }
}

@Preview(name = "Tonight — context sealed", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuCeSoir() = PendulumTheme {
    TonightCard(ApercuDonnees.ceSoir, onSceller = {})
}

@Preview(name = "Tonight — to seal, low battery", widthDp = 411, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun ApercuCeSoirASceller() = PendulumTheme {
    TonightCard(ApercuDonnees.ceSoir.copy(batteriePct = 62, contexteScelle = false), onSceller = {})
}
