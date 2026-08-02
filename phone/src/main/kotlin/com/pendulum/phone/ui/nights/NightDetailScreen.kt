package com.pendulum.phone.ui.nights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pendulum.phone.ui.chart.GrapheNuit
import com.pendulum.phone.ui.chart.HypnogrammeSpec
import com.pendulum.phone.ui.chart.Hypnogramme
import com.pendulum.phone.ui.chart.NuitChartSpec
import com.pendulum.phone.ui.chart.XTransform
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.DataTableSheet
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.LocalPendulumColors
import com.pendulum.phone.ui.theme.PendulumShapes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.theme.PendulumType
import com.pendulum.phone.ui.theme.Spacing

/** Ce que le detail d'une nuit sait afficher, calcule en amont par le ViewModel. */
data class NuitDetailUi(
    val nuit: NuitUi,
    val auLit: String,
    /**
     * Le graphe du signal, ou `null` quand l'enveloppe n'est pas disponible.
     *
     * Elle est calculee pendant l'analyse et **n'est pas persistee** : la reconstruire demande de
     * relire les chunks bruts de la nuit et de refaire la chaine de traitement, ce qui n'a pas sa
     * place dans l'ouverture d'un ecran. Tant que cette relecture n'existe pas, la section n'est
     * pas dessinee — plutot que dessinee vide ou avec une courbe fabriquee.
     *
     * C'est la meme regle que sous trois nuits : un axe vide invite l'oeil a imaginer la courbe
     * qui manque. Le reste du detail — les comptes, les controles qualite, l'hypnogramme — vient
     * de la base et s'affiche.
     */
    val graphe: NuitChartSpec?,
    val hypnogramme: HypnogrammeSpec?,
    val mouvements: Int,
    val plms: Int,
    val plmw: Int,
    val ecartesPosture: Int,
    val ecartesDuree: Int,
    val series: Int,
    val couvertureSeries: String,
    val imiMedianSec: Double,
    val controles: List<Controle>,
    val regleAppliquee: String,
)

/** Un controle de qualite : sa valeur mesuree, son seuil, son etat. Les trois, toujours. */
data class Controle(val libelle: String, val valeur: String, val seuil: String, val ok: Boolean)

/**
 * Le detail d'une nuit — cinq sections.
 *
 * C'est le seul endroit ou le chiffre d'une nuit est presente avec son avertissement **fusionne
 * dans le meme bloc**. La fusion est deliberee : un grand chiffre suivi d'une precaution en
 * dessous produit exactement le comportement qu'on veut eviter, parce que le chiffre est lu et la
 * precaution ne l'est pas. Ici la phrase fait partie du bloc de valeur, a la meme distance de
 * l'oeil.
 *
 * Le panneau de parametres, replie par defaut, est l'endroit ou mord la regle anti-auto-tromperie :
 * il n'y a **pas** de « recalculer cette nuit ». Un changement de parametre est global, bump le
 * `paramsHash`, et declenche un rescore de toutes les nuits depuis le brut.
 *
 * ### Le garde-fou 2 : le resultat est masque tant qu'il n'a pas ete demande
 *
 * Tant que `night_session.revealedAtMs` est nul, le bloc de valeur, les graphes et le detail des
 * evenements ne sont pas rendus. Ce qui reste visible est ce que `01-overview.md` §4 decrit comme
 * l'ecran du matin : la nuit a ete enregistree, sa qualite a ete verifiee — et la liste de
 * controles, plus bas, le montre en chiffres.
 *
 * **Un seul geste pour lever le masque.** Pas de modale de confirmation, pas d'avertissement a
 * accepter. La justification est mesuree plutot que supposee : sur environ 8 000 reponses de
 * patients recevant leurs resultats de laboratoire avant relecture medicale, 95,7 % veulent les
 * recevoir immediatement et 7,5 % seulement rapportent une inquietude accrue. Vouloir son chiffre
 * est donc la norme ; la friction doit ralentir le geste, pas le taxer.
 *
 * Ce qui n'est pas negociable est la **trace** : le devoilement est horodate en base et sort dans
 * l'export. Elle est silencieuse — on ne demande pas la permission, on note la date.
 */
@Composable
fun NightDetailScreen(
    detail: NuitDetailUi,
    onVoirTendance: () -> Unit,
    onAppliquerATout: () -> Unit,
    onDevoiler: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val devoile = detail.nuit.devoileeAtMs != null
    val c = LocalPendulumColors.current
    val transform = remember(detail.graphe) {
        detail.graphe?.let { XTransform(it.debutMs, it.finMs) }
    }
    var curseur by remember { mutableStateOf<Long?>(null) }
    var valeursOuvertes by remember { mutableStateOf(false) }
    var paramsOuverts by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.betweenCards.dp),
    ) {
        // --- Section 1 : en-tete + valeur de la nuit, fusionnees
        PendulumCard {
            Text(Textes.Nuits.Detail.titre(detail.nuit.dateLisible), style = PendulumType.titleL, color = c.textPrimary)
            Text(
                "${detail.nuit.debut} → ${detail.nuit.fin}  ·  ${detail.auLit} in bed  ·  ${detail.nuit.sommeilLisible}",
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.sm.dp))
            if (devoile) {
                Text(
                    "${Math.round(detail.nuit.rythmeSec)} s  ·  ${Math.round(detail.nuit.comptePlmi)}/h",
                    style = PendulumType.metricL,
                    color = c.textSecondary,
                )
                Text(Textes.Nuits.VALEUR_UNE_NUIT, style = PendulumType.caption, color = c.textTertiary)
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(Textes.Nuits.VALEUR_UNE_NUIT_LONG)
                Spacer(Modifier.height(Spacing.s.dp))
                TextButton(onClick = onVoirTendance) { Text(Textes.Nuits.VOIR_TENDANCE) }
            } else {
                Text(
                    Textes.EcranAccueil.Resultat.enregistree(detail.nuit.dateLisible),
                    style = PendulumType.bodyEmph,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(Textes.EcranAccueil.Resultat.MASQUE_CORPS)
                Spacer(Modifier.height(Spacing.sm.dp))
                BoutonMotive(
                    libelle = Textes.EcranAccueil.Resultat.BOUTON,
                    motifIndisponible = null,
                    onClick = onDevoiler,
                )
            }
        }

        // --- Section 2 : les deux graphes, un seul axe X, un seul curseur
        //
        // La carte entiere disparait quand l'enveloppe n'a pas ete reconstruite. Ni cadre vide, ni
        // message d'erreur : il n'y a rien de casse, il y a seulement une donnee que cette version
        // ne relit pas encore depuis le brut.
        if (devoile && detail.graphe != null && detail.hypnogramme != null && transform != null) {
            PendulumCard {
                GrapheNuit(
                    spec = detail.graphe,
                    transform = transform,
                    curseurMs = curseur,
                    onCurseur = { curseur = it },
                    onEvenement = {},
                    onValeurs = { valeursOuvertes = true },
                )
                Hypnogramme(detail.hypnogramme, transform, curseur)
                Spacer(Modifier.height(Spacing.xs.dp))
                Text(detail.hypnogramme.statistiques, style = PendulumType.caption, color = c.textTertiary)
            }
        }

        // --- Section 3 : evenements detectes
        //
        // Masquee avec le reste tant que le resultat n'a pas ete demande. Le compte des
        // mouvements n'est pas l'index, mais le laisser visible reviendrait a ne masquer que la
        // division : un garde-fou qu'on contourne en lisant la ligne du dessus n'en est pas un.
        if (devoile) PendulumCard {
            SectionHeader(Textes.Nuits.Detail.EVENEMENTS)
            InlineValue(Textes.Nuits.Detail.MOUVEMENTS, detail.mouvements.toString())
            InlineValue(Textes.Nuits.Detail.DONT_SOMMEIL, detail.plms.toString())
            InlineValue(Textes.Nuits.Detail.DONT_EVEIL, detail.plmw.toString())
            InlineValue(Textes.Nuits.Detail.ECARTES_POSTURE, detail.ecartesPosture.toString())
            InlineValue(Textes.Nuits.Detail.ECARTES_DUREE, detail.ecartesDuree.toString())
            InlineValue(Textes.Nuits.Detail.SERIES, "${detail.series}   covering ${detail.couvertureSeries}")
            InlineValue(Textes.Nuits.Detail.IMI_MEDIAN, "%.1f s".format(detail.imiMedianSec))
            InlineValue(
                Textes.Nuits.Detail.RYTHME_FONDAMENTAL,
                "${Math.round(detail.nuit.rythmeSec)} s",
                note = "Estimated by deconvolution of the harmonics: a sensor on a single leg " +
                    "sees a doubled interval when movements alternate left/right.",
            )
        }

        // --- Section 4 : qualite de la nuit
        PendulumCard {
            SectionHeader(Textes.Nuits.Detail.QUALITE)
            detail.controles.forEach {
                InlineValue(it.libelle, "${it.valeur}   ${if (it.ok) "✓" else "✗"}", note = "threshold ${it.seuil}")
            }
            InlineValue(Textes.Nuits.Detail.REGLE_APPLIQUEE, detail.regleAppliquee)
        }

        // --- Section 5 : parametres
        PendulumCard {
            TextButton(onClick = { paramsOuverts = !paramsOuverts }) {
                Text(Textes.Nuits.Detail.PARAMS_AVANCES)
            }
            if (paramsOuverts) {
                Paragraphe(Textes.Nuits.Detail.PAS_DE_REGLAGE_PAR_NUIT)
                Spacer(Modifier.height(Spacing.s.dp))
                OutlinedButton(onClick = onAppliquerATout, shape = PendulumShapes.button) {
                    Text(Textes.Nuits.Detail.RECALCULER_TOUTES)
                }
            }
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }

    val graphe = detail.graphe
    if (devoile && valeursOuvertes && graphe != null) {
        DataTableSheet(
            colonnes = listOf("#", "Start", "Duration", "Ampl."),
            lignes = graphe.marqueurs.take(200).mapIndexed { i, m ->
                listOf(
                    "${i + 1}",
                    "${(m.onsetMs - graphe.debutMs) / 1000} s",
                    m.dureeMs?.let { "%.1f s".format(it / 1000.0) } ?: "—",
                    m.amplitudeRatio?.let { "×%.1f".format(it) } ?: "—",
                )
            },
            onFermer = { valeursOuvertes = false },
        )
    }
}

