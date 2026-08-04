package com.pendulum.phone.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.PorteP1
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** Ce que le rapport P1 affiche. Tout est deja calcule par [PorteP1], pur et teste sur ses bornes. */
data class RapportP1Ui(
    val nuits: List<PorteP1.VerdictNuit>,
    val campagne: PorteP1.Campagne,
)

/**
 * Le rapport de la porte P1, dans Reglages › Mesure.
 *
 * ### La conclusion est en haut, les nuits en dessous
 *
 * On vient ici avec une question — « est-ce que ca tient ? » — et la reponse est un entier :
 * combien de nuits d'affilee sont restees dans les trois criteres. Les lignes par nuit sont ce
 * qui permet de verifier cet entier, pas ce qu'on lit en premier.
 *
 * ### Aucune jauge, aucune couleur seule porteuse
 *
 * Pas de barre de couverture, pas d'anneau, pas de vert ni de rouge : chaque ligne porte sa
 * **valeur mesuree** et son **seuil**, et l'etat est un signe — `✓`, `✗`, `—` — lisible sans
 * distinguer les couleurs et identique a l'impression. Une jauge a 99 % et une jauge a 97 %
 * ont la meme image ; c'est exactement l'ecart que cette porte existe pour trancher.
 *
 * ### Le tiret est une information
 *
 * Une nuit sans fin connue n'a pas de couverture, elle a une couverture *pour l'instant* ; une
 * nuit de six heures ne dit rien sur la batterie a huit heures tant qu'elle est au-dessus du
 * seuil. Ces lignes affichent un tiret et l'ecran dit pourquoi, plutot que de disparaitre — une
 * ligne absente se lit « ce controle n'existe pas ».
 */
@Composable
fun RapportP1Screen(
    etat: RapportP1Ui,
    onExporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current

    PendulumScreen(modifier) {
        Text(Textes.P1.TITRE, style = PendulumType.titleL, color = c.textPrimary)
        Text(Textes.P1.SOUS_TITRE, style = PendulumType.caption, color = c.textTertiary)

        PendulumCard {
            SectionHeader(Textes.P1.CONCLUSION)
            Text(
                Textes.P1.serie(etat.campagne.serieMax, PorteP1.NUITS_CONSECUTIVES),
                style = PendulumType.bodyEmph,
                color = c.textPrimary,
            )
            val debut = etat.campagne.debutSerie
            val fin = etat.campagne.finSerie
            if (debut != null && fin != null) {
                Text(
                    Textes.P1.etendueSerie(debut, fin),
                    style = PendulumType.bodyNum,
                    color = c.textSecondary,
                )
            }
            Text(
                Textes.P1.comptes(etat.campagne.nuitsConformes, etat.campagne.nuitsExaminees),
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            // Le verdict est une phrase et pas une pastille : « Gate not passed » se recopie dans
            // un message, une pastille orange ne se recopie nulle part.
            Text(
                if (etat.campagne.franchie) Textes.P1.FRANCHIE else Textes.P1.NON_FRANCHIE,
                style = PendulumType.bodyEmph,
                color = c.textPrimary,
            )
        }

        PendulumCard {
            SectionHeader(Textes.P1.NUITS)
            if (etat.nuits.isEmpty()) {
                Paragraphe(Textes.P1.AUCUNE_NUIT)
            } else {
                etat.nuits.forEach { LigneNuit(it) }
            }
        }

        PendulumCard {
            Paragraphe(Textes.P1.INTRO)
            Spacer(Modifier.height(Spacing.s.dp))
            // Le chiffre de batterie est extrapole quand la nuit porte de la telemetrie. Un
            // pourcentage extrapole ressemble a un pourcentage mesure : ce qu'il suppose est ecrit
            // a cote de lui, sur le meme ecran, et pas seulement dans une KDoc.
            Paragraphe(Textes.P1.BATTERIE_EXTRAPOLATION)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.P1.NON_TRANSMIS)
        }

        PendulumCard {
            Paragraphe(Textes.P1.POURQUOI, couleur = c.textPrimary)
            Spacer(Modifier.height(Spacing.sm.dp))
            // Le meme chemin SAF que l'export d'une nuit : l'application n'ecrit jamais dans un
            // repertoire partage de sa propre initiative, l'emplacement est choisi geste par geste.
            BoutonMotive(Textes.P1.EXPORTER, null, onExporter)
            Spacer(Modifier.height(Spacing.s.dp))
            Text(Textes.Export.PAS_DE_RESEAU, style = PendulumType.caption, color = c.textTertiary)
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun LigneNuit(v: PorteP1.VerdictNuit) {
    val c = LocalPendulumColors.current
    Column(Modifier.padding(vertical = Spacing.s.dp)) {
        Text(
            "${v.dateLisible}  ·  ${libelle(v.verdict)}",
            style = PendulumType.bodyEmph,
            color = c.textPrimary,
        )
        v.criteres.forEach {
            InlineValue(it.libelle, "${it.valeur}   ${signe(it.etat)}", note = it.seuil)
        }
    }
}

/** `✓ ✗ —` : la forme porte l'etat, la couleur n'a rien a porter. */
private fun signe(e: PorteP1.Conformite): String = when (e) {
    PorteP1.Conformite.CONFORME -> "✓"
    PorteP1.Conformite.NON_CONFORME -> "✗"
    PorteP1.Conformite.INDETERMINE -> "—"
}

private fun libelle(e: PorteP1.Conformite): String = when (e) {
    PorteP1.Conformite.CONFORME -> Textes.P1.CONFORME
    PorteP1.Conformite.NON_CONFORME -> Textes.P1.NON_CONFORME
    PorteP1.Conformite.INDETERMINE -> Textes.P1.INDETERMINE
}
