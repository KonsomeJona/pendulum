package com.pendulum.phone.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
import com.pendulum.phone.ui.common.BandeauProfilPersonnalise
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.CompteRendu
import com.pendulum.phone.ui.text.resoudre
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

data class ExportUi(
    val inclureQuestionnaire: Boolean,
    /** Par defaut **oui** : masquer les nuits ratees a un medecin est trompeur. */
    val inclureEcartees: Boolean,
    val nuitsEligibles: Int,
    val periode: String,
    val profilPersonnalise: String?,
    /**
     * Ce que la derniere ecriture a donne, ou `null` tant qu'il n'y en a pas eu.
     *
     * Le champ portait le seul **nom du fichier**, pose inconditionnellement apres un
     * `runCatching` qui jetait son exception : « Written: … » s'affichait donc aussi quand rien
     * n'avait ete ecrit. Sur le document qu'on emporte chez le medecin sans le rouvrir, c'est le
     * pire endroit ou taire un echec.
     */
    val ecriture: CompteRendu? = null,
)

/**
 * L'export d'un rapport pour le medecin.
 *
 * ### Ce qui est impose dans le document
 *
 * Le bandeau de tete reprend l'avertissement mot pour mot : mesure personnelle, pas un examen
 * medical, aucun diagnostic, aucune decision de traitement. Puis la periode et les compteurs, le
 * resultat avec son intervalle et son `n`, le graphe de tendance, le tableau par nuit, un graphe
 * de nuit avec son hypnogramme, la methode en six lignes, les quatre limites reprises
 * litteralement de l'ecran d'accueil, et le questionnaire si inclus.
 *
 * Le rapport medecin met le **compte horaire** en premier rang, contrairement a l'ecran : c'est
 * la langue des somnologues et les seuils publies reposent dessus. Le rythme fondamental y figure
 * aussi, avec sa justification.
 *
 * ### Ce qui n'existe pas
 *
 * Aucun chemin reseau. L'application ne declare pas la permission `INTERNET` : c'est une garantie
 * **verifiable** — un `aapt dump permissions` suffit — la ou une politique de confidentialite est
 * une promesse.
 *
 * Et **aucun `ACTION_SEND` non plus** : il exigerait un `FileProvider` au manifeste, c'est-a-dire
 * une seconde surface de sortie, en plus de celle que SAF ouvre deja. Le fichier est ecrit a
 * l'endroit que l'utilisateur designe, geste par geste ; ce qu'il en fait ensuite appartient a
 * son gestionnaire de fichiers, qui sait deja partager. Une porte de sortie de moins a defendre.
 *
 * ### Si l'export est impossible
 *
 * Le bouton reste visible mais desactive, **avec le motif ecrit dessus**. Jamais un bouton actif
 * qui echoue : quelqu'un qui appuie sur « Exporter » et recoit une erreur apprend a se mefier de
 * tous les boutons de l'application.
 */
@Composable
fun ExportScreen(
    etat: ExportUi,
    onQuestionnaire: (Boolean) -> Unit,
    onEcartees: (Boolean) -> Unit,
    onEnregistrer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPendulumColors.current
    val motif = if (etat.nuitsEligibles < Aggregat.MIN_NUITS_AGREGAT) {
        stringResource(R.string.export_unavailable, Aggregat.MIN_NUITS_AGREGAT)
    } else {
        null
    }

    PendulumScreen(modifier) {
        Text(stringResource(R.string.export_title), style = PendulumType.titleL, color = c.textPrimary)

        PendulumCard {
            // Le bandeau qui ouvrira le document, montre ici tel quel : ce que le medecin lira
            // en premier ne doit pas etre une surprise pour celui qui l'imprime.
            Paragraphe(stringResource(R.string.notice_export_banner), couleur = c.textPrimary)
        }

        BandeauProfilPersonnalise(etat.profilPersonnalise)

        PendulumCard {
            SectionHeader(stringResource(R.string.export_section_format))
            Paragraphe(stringResource(R.string.export_format_note))
        }

        PendulumCard {
            SectionHeader(stringResource(R.string.export_section_content))
            Text(
                stringResource(R.string.export_period, etat.periode),
                style = PendulumType.body,
                color = c.textSecondary,
            )
            Text(
                stringResource(R.string.export_eligible_nights, etat.nuitsEligibles),
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s.dp))
            Case(etat.inclureQuestionnaire, stringResource(R.string.export_include_questionnaire), null, onQuestionnaire)
            Case(
                etat.inclureEcartees,
                stringResource(R.string.export_include_excluded),
                stringResource(R.string.export_include_excluded_note),
                onEcartees,
            )
        }

        if (motif != null) {
            PendulumCard {
                Text(stringResource(R.string.error_exp_01_title), style = PendulumType.titleM, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(stringResource(R.string.error_exp_01_cause))
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(stringResource(R.string.error_exp_01_action))
                Text("E-EXP-01", style = PendulumType.caption, color = c.textTertiary)
            }
        }

        BoutonMotive(stringResource(R.string.export_save), motif, onEnregistrer)
        Text(stringResource(R.string.export_no_network), style = PendulumType.caption, color = c.textTertiary)
        // Le compte rendu de l'ecriture, dans les deux cas. En succes, le nom du fichier et rien
        // d'autre : le chemin complet d'un `Uri` SAF est un identifiant de fournisseur illisible,
        // et l'afficher ferait chercher un dossier qui n'existe pas sous ce nom. En echec, ce qui
        // est verifiable — rien n'a ete ecrit — et le geste qui marche.
        etat.ecriture?.let {
            Text(
                it.message.resoudre(),
                style = PendulumType.caption,
                color = if (it.echec) c.attention else c.textSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.l.dp))
    }
}

@Composable
private fun Case(coche: Boolean, titre: String, note: String?, onChange: (Boolean) -> Unit) {
    val c = LocalPendulumColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = coche, onCheckedChange = onChange)
        Column {
            Text(titre, style = PendulumType.body, color = c.textPrimary)
            note?.let { Text(it, style = PendulumType.caption, color = c.textTertiary) }
        }
    }
}

@Preview(name = "Export — possible", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuExport() = PendulumTheme {
    ExportScreen(ExportUi(true, true, 6, "1–15 March", null), {}, {}, {})
}

@Preview(name = "Export — refused below 3 nights", widthDp = 411, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun ApercuExportRefus() = PendulumTheme {
    ExportScreen(ExportUi(true, true, 2, "1–15 March", "threshold 6×"), {}, {}, {})
}
