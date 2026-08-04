package com.pendulum.phone.ui.nights

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.pendulum.phone.ui.chart.BandeMetrologie
import com.pendulum.phone.ui.chart.GrapheNuit
import com.pendulum.phone.ui.chart.HypnogrammeSpec
import com.pendulum.phone.ui.chart.Hypnogramme
import com.pendulum.phone.ui.chart.MetrologieSpec
import com.pendulum.phone.ui.chart.NuitChartSpec
import com.pendulum.phone.ui.chart.XTransform
import com.pendulum.phone.ui.common.BoutonMotive
import com.pendulum.phone.ui.common.DataTableSheet
import com.pendulum.phone.ui.common.ErrorCard
import com.pendulum.phone.ui.common.InlineValue
import com.pendulum.phone.ui.common.Paragraphe
import com.pendulum.phone.ui.common.PendulumCard
import com.pendulum.phone.ui.common.PendulumScreen
import com.pendulum.phone.ui.common.SectionHeader
import com.pendulum.phone.ui.model.CheminDeCalcul
import com.pendulum.phone.ui.model.ErreurPendulum
import com.pendulum.phone.ui.model.Mapping
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
    /**
     * L'etat de l'appareil pendant la nuit, sur le **meme axe** que les deux bandes du dessus.
     *
     * Elle est independante de [graphe] et de [hypnogramme] : elle vient de `telemetry_point`, que
     * l'ingestion remplit au fil des chunks, la ou l'enveloppe demande de refaire toute la chaine
     * de traitement. Une nuit peut donc avoir sa bande d'etat sans avoir sa courbe — et c'est le
     * cas courant tant que la relecture du brut n'existe pas.
     *
     * `null` quand la nuit ne porte aucune telemetrie : une nuit enregistree avant que le bloc
     * `TLM!` n'existe, ou par une montre dont les chunks sont en v1 du format.
     */
    val metrologie: MetrologieSpec?,
    val mouvements: Int,
    val plms: Int,
    val plmw: Int,
    val ecartesPosture: Int,
    val ecartesDuree: Int,
    val series: Int,
    val imiMedianSec: Double,
    val controles: List<Controle>,
    val regleAppliquee: String,
    /**
     * Le chemin de calcul du chiffre — « pourquoi 18,4 /h ». `null` quand la nuit n'a pas encore
     * de resultat, donc rien a expliquer.
     */
    val pourquoi: CheminDeCalcul.Bloc? = null,
    /**
     * La situation de la nuit, ou `null` quand elle n'appelle aucune explication. Ambre pour les
     * situations, rouge pour ce qui est reellement casse : voir [com.pendulum.phone.ui.model.Situations].
     */
    val situation: ErreurPendulum? = null,
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
    onExporterRapport: () -> Unit,
    onExporterPaquet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val devoile = detail.nuit.devoileeAtMs != null
    val c = LocalPendulumColors.current
    // **Une seule transformation pour les trois bandes.** C'est elle qui fait l'axe commun, et
    // l'axe commun est ce qui empeche de lire un artefact de mesure comme un evenement
    // physiologique. La fenetre vient du graphe quand il existe, de la bande de metrologie sinon —
    // les deux specs portent les memes bornes, construites ensemble par le ViewModel.
    val fenetre = detail.graphe?.let { it.debutMs to it.finMs }
        ?: detail.metrologie?.let { it.debutMs to it.finMs }
    val transform = remember(fenetre) {
        fenetre?.let { (debut, fin) -> XTransform(debut, fin) }
    }
    var curseur by remember { mutableStateOf<Long?>(null) }
    var valeursOuvertes by remember { mutableStateOf(false) }
    var paramsOuverts by remember { mutableStateOf(false) }

    PendulumScreen(modifier) {
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
                    "${Mapping.rythmeLisible(detail.nuit.rythmeSec)}  ·  ${Math.round(detail.nuit.comptePlmi)}/h",
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

        // --- Section 2 : les trois bandes, un seul axe X, un seul curseur
        //
        // En haut la physiologie — enveloppe et hypnogramme, continues, analogiques, traits fins.
        // En bas la metrologie, separee par une gouttiere franche et dessinee dans une grammaire
        // etrangere : blocs, escaliers durs, rug plots, aucune courbe. L'axe est commun parce que
        // sans lui on lit un artefact de mesure comme un evenement physiologique ; la forme est
        // separee parce que la batterie et les mouvements n'ont aucune causalite entre eux.
        //
        // Chaque bande disparait quand sa donnee manque, et aucune n'est dessinee vide : ni cadre
        // vide, ni message d'erreur. L'enveloppe n'est pas persistee et n'est pas encore relue
        // depuis le brut ; la telemetrie, elle, est en base des l'ingestion, donc la bande d'etat
        // s'affiche sur des nuits ou la courbe manque encore.
        //
        // La bande d'etat de l'appareil est dans la **meme carte** que les deux autres, et pas
        // dans une carte a elle : une carte separee serait une seconde surface, donc un second
        // contexte, et l'alignement des trois axes cesserait d'etre evident a l'oeil. Ce qui les
        // separe est la gouttiere, qui appartient au dessin.
        //
        // Elle s'affiche meme quand l'enveloppe manque. Ce n'est pas une exception a la regle du
        // « pas de cadre vide » : elle ne dessine pas l'absence du signal, elle dessine ce que la
        // telemetrie porte, qui existe independamment.
        if (devoile && transform != null && (detail.graphe != null || detail.metrologie != null)) {
            PendulumCard {
                if (detail.graphe != null && detail.hypnogramme != null) {
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
                detail.metrologie?.let { metro ->
                    BandeMetrologie(metro, transform, curseur)
                    Spacer(Modifier.height(Spacing.xs.dp))
                    // La legende dit ce que la forme dit deja a l'oeil : ces voies decrivent
                    // l'enregistreur, pas le dormeur. Elle est courte et elle est ici, sous la
                    // bande, parce qu'une legende posee ailleurs se lit apres la conclusion.
                    Paragraphe(Textes.Nuits.Detail.METROLOGIE_NOTE)
                }
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
            // La couverture des series etait un champ a part de `NuitDetailUi`, alimente par
            // exactement la meme valeur que `nuit.sommeilLisible`, deja porte par le meme objet.
            // Deux champs pour une valeur, c'est deux endroits ou elle peut diverger.
            InlineValue(Textes.Nuits.Detail.SERIES, "${detail.series}   covering ${detail.nuit.sommeilLisible}")
            InlineValue(Textes.Nuits.Detail.IMI_MEDIAN, "%.1f s".format(detail.imiMedianSec))
            // La note change avec la valeur : quand l'ajustement a ete refuse, expliquer la
            // deconvolution des harmoniques repondrait a une question que la ligne ne pose plus.
            // Ce qu'il faut dire alors est **pourquoi il n'y a rien**, et que c'est voulu.
            InlineValue(
                Textes.Nuits.Detail.RYTHME_FONDAMENTAL,
                Mapping.rythmeLisible(detail.nuit.rythmeSec),
                note = if (detail.nuit.rythmeSec == null) {
                    Textes.Nuits.Detail.RYTHME_NON_AJUSTE_NOTE
                } else {
                    Textes.Nuits.Detail.RYTHME_DECONVOLUTION_NOTE
                },
            )
        }

        // --- Section 3 bis : « pourquoi ce chiffre »
        //
        // Le seul « pourquoi » legitime ici est **generatif** : il montre le chemin de calcul,
        // pas des contributions. Aucun classement de facteurs par importance — ces methodes ne
        // distinguent pas correlation et causalite et sur-attribuent des que les variables sont
        // correlees, ce qui est le cas de toutes celles de ce tableau.
        //
        // La derniere ligne du bloc fait tout le travail, et elle vient du modele et non de
        // l'ecran : un chemin de calcul sans sa phrase de non-causalite ne peut pas exister.
        if (devoile) detail.pourquoi?.let { bloc ->
            PendulumCard {
                SectionHeader(bloc.titre)
                bloc.lignes.forEach { InlineValue(it.libelle, it.valeur, note = it.note) }
                Spacer(Modifier.height(Spacing.sm.dp))
                Paragraphe(bloc.avertissement, couleur = c.textPrimary)
            }
        }

        // --- Section 3 ter : la situation de la nuit, s'il y en a une
        //
        // Ambre pour une nuit courte, une nuit sans hypnogramme, une montre dechargee — ce sont
        // des **situations**. Rouge pour un transfert incomplet, qui est reellement casse. La
        // teinte suit `ErreurPendulum.technique` et non la gravite ressentie.
        detail.situation?.let { ErrorCard(it) }

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

        // --- Section 6 : les deux sorties de cette nuit
        //
        // Elles sont ici et pas dans l'ecran d'export, qui porte la campagne : ce sont deux
        // documents d'une nuit precise, qu'on sort quand cette nuit-la pose question. Le chemin
        // est le meme que partout ailleurs — SAF, emplacement choisi par l'utilisateur.
        PendulumCard {
            SectionHeader(Textes.Nuits.Detail.EXPORT)
            Paragraphe(Textes.Nuits.Detail.EXPORTER_RAPPORT_NOTE)
            OutlinedButton(onClick = onExporterRapport, shape = PendulumShapes.button) {
                Text(Textes.Nuits.Detail.EXPORTER_RAPPORT)
            }
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(Textes.Nuits.Detail.EXPORTER_PAQUET_NOTE)
            OutlinedButton(onClick = onExporterPaquet, shape = PendulumShapes.button) {
                Text(Textes.Nuits.Detail.EXPORTER_PAQUET)
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

