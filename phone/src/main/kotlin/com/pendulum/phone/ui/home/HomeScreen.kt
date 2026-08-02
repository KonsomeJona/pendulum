package com.pendulum.phone.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing
import com.pendulum.phone.ui.trend.TonightCard

/**
 * L'accueil : trois cartes, dans cet ordre, tout le temps.
 *
 * ```
 * PREPARE THE NIGHT   montre, espace libre, bracelet, source de sommeil
 * END OF NIGHT        grisee avec son motif s'il n'y a pas de session ouverte
 * HISTORY             n nuits · m eligibles
 * ```
 *
 * ### Pourquoi cet ecran existe
 *
 * L'accueil etait la Tendance, et la Tendance melange deux regimes cognitifs incompatibles : le
 * geste quotidien — rapide, memorise, fait d'une main a 23 h ou a 7 h — et la lecture d'un
 * resultat statistique, lente et chargee. Mis sur le meme ecran, le premier se paie du second :
 * on vient pour appuyer sur un bouton et on lit un chiffre en chemin, dans l'etat ou l'on est le
 * moins capable de le juger.
 *
 * ### Pourquoi les cartes ne bougent jamais
 *
 * Une carte sans objet n'est **pas retiree** : elle est desactivee et porte son motif. Le cout
 * d'un contenu qui apparait et disparait ne se voit pas sur une maquette — il se voit a l'usage,
 * quand la cible visee hier a change de place aujourd'hui parce qu'il est 5 h et non 23 h. Trois
 * positions fixes valent mieux qu'un ecran qui a raison.
 *
 * ### Ce qu'il n'y a pas
 *
 * Aucun chiffre. Ni rythme, ni compte horaire, ni compteur de nuits eligibles presente comme une
 * performance. La carte « fin de nuit » peut annoncer qu'une nuit est enregistree et sa qualite
 * verifiee ; c'est tout ce qu'elle dit tant que le resultat n'a pas ete demande — garde-fou 2.
 */
@Composable
fun HomeScreen(
    etat: AccueilUi,
    onSceller: () -> Unit,
    onDemarrer: () -> Unit,
    onFinDeNuit: () -> Unit,
    onDevoiler: (String) -> Unit,
    onHistorique: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PendulumScreen(modifier) {
        // 1 — Preparer la nuit. Le scellement est la seule porte du produit.
        TonightCard(
            etat = etat.ceSoir,
            motifIndisponible = etat.motifPreparer,
            onSceller = onSceller,
            onDemarrer = onDemarrer,
        )

        // 2 — Fin de nuit. Une carte, une action : soit on ferme la nuit, soit on demande le
        // resultat de la derniere. Jamais les deux — a 7 h du matin, d'une main, un choix
        // multiple est un choix qu'on ne fait pas.
        CarteFinDeNuit(etat, onFinDeNuit, onDevoiler)

        // 3 — Historique. La liste des nuits est une destination empilee : on y va, on n'y vit pas.
        CarteHistorique(etat, onHistorique)

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun CarteFinDeNuit(
    etat: AccueilUi,
    onFinDeNuit: () -> Unit,
    onDevoiler: (String) -> Unit,
) {
    val c = LocalPendulumColors.current
    val aDevoiler = etat.nuitADevoiler
    PendulumCard {
        SectionHeader(Textes.EcranAccueil.Fin.TITRE)
        Text(etat.ligneFinDeNuit, style = PendulumType.body, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.s.dp))
        Paragraphe(
            if (aDevoiler != null) {
                Textes.EcranAccueil.Resultat.MASQUE_CORPS
            } else {
                Textes.EcranAccueil.Fin.CORPS
            },
        )
        Spacer(Modifier.height(Spacing.sm.dp))
        // Un seul geste pour devoiler : pas de modale de confirmation, pas d'avertissement a
        // accepter. La friction est un ralentisseur, pas un peage — vouloir son chiffre au
        // reveil est la norme et non l'exception. Ce qui reste, c'est la trace, et elle est
        // silencieuse.
        if (aDevoiler != null) {
            BoutonMotive(
                libelle = Textes.EcranAccueil.Resultat.BOUTON,
                motifIndisponible = null,
                onClick = { onDevoiler(aDevoiler) },
            )
        } else {
            BoutonMotive(
                libelle = Textes.EcranAccueil.Fin.BOUTON,
                motifIndisponible = etat.motifFinDeNuit,
                onClick = onFinDeNuit,
            )
        }
    }
}

@Composable
private fun CarteHistorique(etat: AccueilUi, onHistorique: () -> Unit) {
    val c = LocalPendulumColors.current
    PendulumCard {
        SectionHeader(Textes.EcranAccueil.Historique.TITRE)
        Text(etat.ligneHistorique, style = PendulumType.bodyNum, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm.dp))
        BoutonMotive(
            libelle = Textes.EcranAccueil.Historique.BOUTON,
            motifIndisponible = etat.motifHistorique,
            onClick = onHistorique,
        )
    }
}
