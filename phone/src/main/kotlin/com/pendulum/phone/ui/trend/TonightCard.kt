package com.pendulum.phone.ui.trend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.CeSoirUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/**
 * La carte « Preparer la nuit » — la premiere des trois cartes fixes de l'accueil.
 *
 * ### Elle ne va plus et ne vient plus
 *
 * Elle etait ecrite pour n'apparaitre qu'entre 20 h et 4 h, et n'a jamais ete rendue par personne.
 * La plage horaire est retiree : une carte en tete d'ecran qui apparait et disparait deplace
 * verticalement tout ce qui la suit, deux fois par jour, et l'horloge n'est pas une source d'etat
 * fiable — un travailleur de nuit se couche a 9 h. Elle est desormais permanente, et c'est son
 * bouton qui porte l'etat. Ecart assume vis-a-vis de `docs/06-interface.md` §2.2.
 *
 * ### Ce qui est un avis et ce qui est une porte
 *
 * La batterie sous 85 % est un **avis** : la ligne passe en ambre, le texte donne le chiffre, et
 * rien n'est bloque. C'est l'utilisateur qui decide s'il tente la nuit.
 *
 * Le scellement du contexte, lui, est la seule vraie **porte** du produit : tant qu'il n'est pas
 * fait, la montre refuse de demarrer. Le motif est le garde-fou 1 de `SPEC-v2.md` §3 — une dose
 * et un contexte notes apres coup sont notes en connaissance du resultat, donc inutilisables.
 *
 * ### Pourquoi le bouton reste, meme scelle
 *
 * Le contexte est append-only : le sceller deux fois leve. Le bouton pourrait donc disparaitre
 * une fois le geste fait — et la carte changerait de hauteur au moment precis ou l'utilisateur
 * vient d'agir. Il reste, grise, et **porte son motif** : « contexte scelle, la montre peut
 * demarrer ». La forme de la carte est la meme du debut a la fin de la soiree.
 */
@Composable
fun TonightCard(
    etat: CeSoirUi,
    motifIndisponible: String?,
    onSceller: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumCard(modifier) {
        SectionHeader(
            if (etat.enregistrement == null) {
                Textes.EcranAccueil.Preparer.TITRE
            } else {
                Textes.CeSoir.EN_COURS
            },
        )

        val enr = etat.enregistrement
        if (enr == null) {
            Ligne(
                Textes.CeSoir.LIGNE_MONTRE,
                "${pourcentage(etat.batteriePct)}  ·  ${etat.espaceLibre ?: TIRET} free",
                if (etat.batterieInsuffisante) c.attention else null,
            )
            if (etat.batterieInsuffisante) {
                Text(Textes.CeSoir.BATTERIE_BASSE, style = PendulumType.caption, color = c.attention)
            }
            Ligne(Textes.CeSoir.LIGNE_BRACELET, portLisible(etat))
            Ligne(
                Textes.CeSoir.LIGNE_SOMMEIL,
                etat.sourceSommeil,
                if (etat.sourceActive == false) c.attention else null,
            )
            Spacer(Modifier.height(Spacing.sm.dp))

            Paragraphe(Textes.CeSoir.SCELLEMENT_CORPS)
            Spacer(Modifier.height(Spacing.s.dp))
            Text(
                if (etat.contexteScelle) Textes.CeSoir.CONSIGNE_DEMARRAGE else Textes.CeSoir.SCELLEMENT_MANQUANT,
                style = if (etat.contexteScelle) PendulumType.bodyEmph else PendulumType.body,
                color = if (etat.contexteScelle) c.textPrimary else c.attention,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            BoutonMotive(
                libelle = Textes.CeSoir.SCELLEMENT_BOUTON,
                motifIndisponible = motifIndisponible,
                onClick = onSceller,
            )
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

/**
 * Le port : bracelet et jambe, ou ce qu'on en sait.
 *
 * Avant le scellement la jambe est inconnue et le reste — la deviner fausserait le critere de
 * comparabilite sans que rien ne le signale.
 */
private fun portLisible(etat: CeSoirUi): String =
    listOfNotNull(etat.bracelet.takeIf { it.isNotBlank() }, etat.jambe)
        .joinToString(", ")
        .ifBlank { TIRET }

private fun pourcentage(pct: Int?): String = if (pct == null) TIRET else "$pct%"

private const val TIRET = "—"

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
