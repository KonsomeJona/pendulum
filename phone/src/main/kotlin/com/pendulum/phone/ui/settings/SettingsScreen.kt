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
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.text.resoudre
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** Ce que l'ecran de reglages affiche. Tout est deja resolu par le ViewModel. */
data class ReglagesUi(
    val regle: UiText,
    val sourcePreferee: UiText,
    val profil: UiText,
    val repereDePort: UiText,
    val arretAutomatique: UiText,
    val montre: UiText,
    val healthConnect: UiText,
    val espaceOccupe: UiText,
    val versionApp: UiText,
    val versionAlgo: UiText,
    val theme: UiText,
    /**
     * Le compte rendu du dernier reimport de paquet, ou `null` quand il n'y en a pas eu dans
     * cette session. Il est ici et pas dans une bulle : un import qui a echoue doit rester lisible
     * apres coup, et un import reussi doit dire quelle nuit est entree.
     */
    val dernierImport: UiText? = null,
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
            SectionHeader(stringResource(R.string.settings_measurement))
            InlineValue(stringResource(R.string.trend_counting_rule), etat.regle.resoudre())
            InlineValue(stringResource(R.string.settings_preferred_source), etat.sourcePreferee.resoudre())
            InlineValue(stringResource(R.string.settings_param_profile), etat.profil.resoudre())
            InlineValue(stringResource(R.string.settings_wearing_reference), etat.repereDePort.resoudre())
            InlineValue(stringResource(R.string.settings_auto_stop), etat.arretAutomatique.resoudre())
            // Le rapport P1 est ici et pas dans « A propos » : il porte sur ce que le capteur a
            // reellement delivre, ce qui est le sujet de cette carte. C'est aussi le seul ecran
            // de l'application qui parle du materiel plutot que du dormeur.
            Ligne(stringResource(R.string.p1_title), stringResource(R.string.p1_subtitle), onRapportP1)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.settings_accel_mask_forbidden))
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.trend_rules_snackbar))
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.settings_devices))
            InlineValue(stringResource(R.string.settings_watch), etat.montre.resoudre())
            InlineValue(stringResource(R.string.settings_health_connect), etat.healthConnect.resoudre())
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
            SectionHeader(stringResource(R.string.settings_data))
            InlineValue(stringResource(R.string.settings_space_used), etat.espaceOccupe.resoudre())
            Ligne(stringResource(R.string.settings_import), stringResource(R.string.settings_import_note), onImporterNuit)
            Ligne(
                stringResource(R.string.settings_erase),
                stringResource(R.string.settings_erase_confirmation),
                onEffacer,
            )
            etat.dernierImport?.let {
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(it.resoudre(), style = PendulumType.caption, color = c.textSecondary)
            }
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.settings_appearance))
            // Sombre par defaut, et force au premier lancement : consultation nocturne et
            // matinale, souvent dans le noir.
            InlineValue(stringResource(R.string.settings_theme), etat.theme.resoudre())
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.settings_about))
            InlineValue(stringResource(R.string.settings_app_version), etat.versionApp.resoudre())
            InlineValue(stringResource(R.string.settings_algo_version), etat.versionAlgo.resoudre())
            Ligne(stringResource(R.string.settings_read_notice_again), null, onRelireAvertissement)
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.notice_export_banner), couleur = c.textSecondary)
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

