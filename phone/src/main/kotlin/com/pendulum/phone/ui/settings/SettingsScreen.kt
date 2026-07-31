package com.pendulum.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
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
    onJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        PendulumCard {
            SectionHeader(Textes.Reglages.MESURE)
            InlineValue(Textes.Tendance.REGLE_COMPTAGE, etat.regle)
            InlineValue(Textes.Reglages.SOURCE_PREFEREE, etat.sourcePreferee)
            InlineValue(Textes.Reglages.PROFIL_PARAMS, etat.profil)
            InlineValue(Textes.Reglages.REPERE_PORT, etat.repereDePort)
            InlineValue(Textes.Reglages.ARRET_AUTO, etat.arretAutomatique)
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

        PendulumCard {
            SectionHeader(Textes.Reglages.DONNEES)
            InlineValue(Textes.Reglages.ESPACE_OCCUPE, etat.espaceOccupe)
            Ligne(Textes.Reglages.PURGER, Textes.Reglages.PURGER_NOTE) {}
            Ligne(Textes.Reglages.JOURNAL, null, onJournal)
            Ligne(Textes.Reglages.EFFACER, Textes.Reglages.EFFACER_CONFIRMATION, onEffacer)
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
            Ligne(Textes.Reglages.SOURCES_SCIENTIFIQUES, "Offline, inside the application") {}
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

@Preview(name = "Settings", widthDp = 411, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuReglages() = PendulumTheme {
    SettingsScreen(
        ReglagesUi(
            regle = Textes.Reglages.REGLE_AASM,
            sourcePreferee = "Samsung Health",
            profil = "default",
            repereDePort = "4th hole, right leg",
            arretAutomatique = "On charger",
            montre = "Pixel Watch 3 · 98% · 1.2 GB",
            healthConnect = "Sleep read access granted",
            espaceOccupe = "3.4 GB",
            versionApp = "0.1.0",
            versionAlgo = "1.4.0",
            theme = Textes.Reglages.THEME_SOMBRE,
        ),
        {}, {}, {},
    )
}
