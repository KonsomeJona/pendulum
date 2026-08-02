package com.pendulum.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** Ce que l'ecran de reglages affiche. Tout est deja resolu par le ViewModel. */
data class ReglagesUi(
    val regle: String,
    val sourcePreferee: String,
    val profil: String,
    val repereDePort: String,
    val arretAutomatique: String,
    val montre: String,
    val healthConnect: String,
    val espaceOccupe: String,
    val versionApp: String,
    val versionAlgo: String,
    val theme: String,
    /**
     * Le compte rendu du dernier reimport de paquet, ou `null` quand il n'y en a pas eu dans
     * cette session. Il est ici et pas dans une bulle : un import qui a echoue doit rester lisible
     * apres coup, et un import reussi doit dire quelle nuit est entree.
     */
    val dernierImport: String? = null,
)

/**
 * Les reglages : listes plates, pas de recherche, pas de sous-menus profonds.
 *
 * Deux choses meritent d'etre ici plutot qu'ailleurs.
 *
 * L'**avertissement** est accessible en permanence depuis « A propos ». Il n'est pas seulement
 * montre une fois au premier lancement : quelqu'un qui consulte un chiffre trois mois plus tard
 * doit pouvoir relire, en deux gestes, pourquoi ce chiffre n'est pas un diagnostic.
 *
 * Le **masque accelero seul** figure dans la liste des sources, mais l'ecran dit pourquoi il ne
 * peut jamais porter le resultat principal : le denominateur serait calcule a partir du meme
 * signal que le numerateur, et un effet nul pourrait alors s'afficher comme un changement franc.
 */
@Composable
fun SettingsScreen(
    etat: ReglagesUi,
    onRelireAvertissement: () -> Unit,
    onEffacer: () -> Unit,
    onImporterNuit: () -> Unit,
    onRapportP1: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    PendulumScreen(modifier) {
        PendulumCard {
            SectionHeader(Textes.Reglages.MESURE)
            InlineValue(Textes.Tendance.REGLE_COMPTAGE, etat.regle)
            InlineValue(Textes.Reglages.SOURCE_PREFEREE, etat.sourcePreferee)
            InlineValue(Textes.Reglages.PROFIL_PARAMS, etat.profil)
            InlineValue(Textes.Reglages.REPERE_PORT, etat.repereDePort)
            InlineValue(Textes.Reglages.ARRET_AUTO, etat.arretAutomatique)
            // Le rapport P1 est ici et pas dans « A propos » : il porte sur ce que le capteur a
            // reellement delivre, ce qui est le sujet de cette carte. C'est aussi le seul ecran
            // de l'application qui parle du materiel plutot que du dormeur.
            Ligne(Textes.P1.TITRE, Textes.P1.SOUS_TITRE, onRapportP1)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.Reglages.MASQUE_ACCELERO_INTERDIT)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.Tendance.SNACKBAR_REGLES)
        }

        PendulumCard {
            SectionHeader(Textes.Reglages.APPAREILS)
            InlineValue(Textes.Reglages.MONTRE, etat.montre)
            InlineValue(Textes.Reglages.HEALTH_CONNECT, etat.healthConnect)
        }

        // Trois lignes ont disparu de cette carte et de la suivante, et c'est deliberement une
        // suppression et non un report : « Purge raw signals older than 90 days », « Technical
        // log » et « Scientific sources » se cliquaient et appelaient un `{}`. Aucune purge
        // selective, aucun journal persiste et aucun corpus hors-ligne n'existe dans ce module —
        // ni table, ni fichier, ni fonction. Une ligne de reglages qui ouvre le vide ne se
        // distingue pas, pour celui qui appuie, d'une application cassee ; et une ligne qui
        // annonce une purge automatique que rien n'execute est une affirmation fausse sur le
        // traitement de donnees de sante. Elles reviendront avec leur implementation.
        PendulumCard {
            SectionHeader(Textes.Reglages.DONNEES)
            InlineValue(Textes.Reglages.ESPACE_OCCUPE, etat.espaceOccupe)
            Ligne(Textes.Reglages.IMPORTER, Textes.Reglages.IMPORTER_NOTE, onImporterNuit)
            Ligne(Textes.Reglages.EFFACER, Textes.Reglages.EFFACER_CONFIRMATION, onEffacer)
            etat.dernierImport?.let {
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(it, style = PendulumType.caption, color = c.textSecondary)
            }
        }

        PendulumCard {
            SectionHeader(Textes.Reglages.APPARENCE)
            // Sombre par defaut, et force au premier lancement : consultation nocturne et
            // matinale, souvent dans le noir.
            InlineValue("Theme", etat.theme)
        }

        PendulumCard {
            SectionHeader(Textes.Reglages.A_PROPOS)
            InlineValue(Textes.Reglages.VERSION_APP, etat.versionApp)
            InlineValue(Textes.Reglages.VERSION_ALGO, etat.versionAlgo)
            Ligne(Textes.Reglages.RELIRE_AVERTISSEMENT, null, onRelireAvertissement)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.Avertissement.BANDEAU_EXPORT, couleur = c.textSecondary)
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun Ligne(titre: String, sousTitre: String?, onClick: () -> Unit) {
    val c = LocalPendulumColors.current
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.s.dp),
    ) {
        Text(titre, style = PendulumType.body, color = c.textPrimary)
        sousTitre?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
    }
}

