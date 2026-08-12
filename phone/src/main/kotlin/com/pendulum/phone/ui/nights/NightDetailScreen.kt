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
import androidx.compose.ui.res.stringResource
import com.pendulum.phone.R
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
import com.pendulum.phone.ui.model.CompteRendu
import com.pendulum.phone.ui.model.ErreurPendulum
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.text.resoudre
import com.pendulum.phone.ui.text.UiText
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
    val regleAppliquee: UiText,
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
/**
 * Une ligne de la table de qualite.
 *
 * [ok] a **trois** etats et non deux, parce que la mesure en a trois. `null` veut dire « on ne
 * sait pas » — la valeur n'a pas ete mesuree, ou n'a pas ete transmise par la montre — et ce
 * n'est ni un succes ni un echec. `Controles` documentait deja cette distinction et la porte P1
 * la rendait (`INDETERMINE`), mais ce type ne portait qu'un booleen : les appelants ecrasaient
 * donc l'inconnu, trois d'entre eux vers `false` (`== true`) et un vers `true` (le plus grand
 * trou, jamais transmis, affiche comme tenu). La meme inconnue se lisait `✗` a trois lignes et
 * `✓` a la quatrieme.
 */
data class Controle(val libelle: UiText, val valeur: UiText, val seuil: UiText, val ok: Boolean?)

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
    /**
     * Ce que la derniere des deux sorties a donne, ou `null` quand il n'y en a pas eu.
     *
     * Les deux boutons etaient muets : ni succes, ni echec. Le paquet brut est le plus mal place
     * pour l'etre — c'est la seule copie transportable d'une nuit, et le croire ecrit avant
     * d'effacer ses donnees perd le brut.
     */
    ecriture: CompteRendu? = null,
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
            Text(stringResource(R.string.night_detail_title, detail.nuit.dateLisible), style = PendulumType.titleL, color = c.textPrimary)
            Text(
                "${detail.nuit.debut} → ${detail.nuit.fin}  ·  ${detail.auLit} in bed  ·  ${detail.nuit.sommeilLisible}",
                style = PendulumType.bodyNum,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.sm.dp))
            if (devoile) {
                Text(
                    "${Mapping.rythmeLisible(detail.nuit.rythmeSec).resoudre()}  ·  " +
                        Mapping.compteLisible(detail.nuit.comptePlmi),
                    style = PendulumType.metricL,
                    color = c.textSecondary,
                )
                Text(
                    stringResource(R.string.nights_single_value),
                    style = PendulumType.caption,
                    color = c.textTertiary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(stringResource(R.string.nights_single_value_long))
                Spacer(Modifier.height(Spacing.s.dp))
                TextButton(onClick = onVoirTendance) { Text(stringResource(R.string.nights_see_trend)) }
            } else {
                Text(
                    stringResource(R.string.home_result_recorded, detail.nuit.dateLisible),
                    style = PendulumType.bodyEmph,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.s.dp))
                Paragraphe(stringResource(R.string.home_result_hidden_body))
                Spacer(Modifier.height(Spacing.sm.dp))
                BoutonMotive(
                    libelle = stringResource(R.string.home_result_button),
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
                    Paragraphe(stringResource(R.string.night_detail_metrology_note))
                }
            }
        }

        // --- Section 3 : evenements detectes
        //
        // Masquee avec le reste tant que le resultat n'a pas ete demande. Le compte des
        // mouvements n'est pas l'index, mais le laisser visible reviendrait a ne masquer que la
        // division : un garde-fou qu'on contourne en lisant la ligne du dessus n'en est pas un.
        if (devoile) PendulumCard {
            SectionHeader(stringResource(R.string.night_detail_events))
            InlineValue(stringResource(R.string.night_detail_movements), detail.mouvements.toString())
            InlineValue(stringResource(R.string.night_detail_of_which_sleep), detail.plms.toString())
            InlineValue(stringResource(R.string.night_detail_of_which_awake), detail.plmw.toString())
            InlineValue(stringResource(R.string.night_detail_excluded_posture), detail.ecartesPosture.toString())
            InlineValue(stringResource(R.string.night_detail_excluded_duration), detail.ecartesDuree.toString())
            // La couverture des series etait un champ a part de `NuitDetailUi`, alimente par
            // exactement la meme valeur que `nuit.sommeilLisible`, deja porte par le meme objet.
            // Deux champs pour une valeur, c'est deux endroits ou elle peut diverger.
            InlineValue(stringResource(R.string.night_detail_series), "${detail.series}   covering ${detail.nuit.sommeilLisible}")
            InlineValue(stringResource(R.string.night_detail_median_ioi), "%.1f s".format(detail.imiMedianSec))
            // La note change avec la valeur : quand l'ajustement a ete refuse, expliquer la
            // deconvolution des harmoniques repondrait a une question que la ligne ne pose plus.
            // Ce qu'il faut dire alors est **pourquoi il n'y a rien**, et que c'est voulu.
            InlineValue(
                stringResource(R.string.night_detail_fundamental_rhythm),
                Mapping.rythmeLisible(detail.nuit.rythmeSec).resoudre(),
                note = if (detail.nuit.rythmeSec == null) {
                    stringResource(R.string.night_detail_rhythm_not_fitted_note)
                } else {
                    stringResource(R.string.night_detail_rhythm_deconvolution_note)
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
                SectionHeader(bloc.titre.resoudre())
                bloc.lignes.forEach {
                    InlineValue(it.libelle.resoudre(), it.valeur.resoudre(), note = it.note?.resoudre())
                }
                Spacer(Modifier.height(Spacing.sm.dp))
                Paragraphe(bloc.avertissement.resoudre(), couleur = c.textPrimary)
            }
        }

        // --- Section 3 ter : la situation de la nuit, s'il y en a une
        //
        // Ambre pour une nuit courte, une nuit sans hypnogramme, une montre dechargee — ce sont
        // des **situations**. Rouge pour un transfert incomplet, qui est reellement casse. La
        // teinte suit `ErreurPendulum.technique` et non la gravite ressentie.
        //
        // Aucune lambda d'action, et c'est exact : les quatre situations que `Situations.nuit`
        // rend n'ont plus de bouton. La derniere qui en portait un — « Force continuous mode » sur
        // `E-NIGHT-04` — nommait un reglage qui n'existe pas, et il etait rendu ici, ou l'action
        // etait le `{}` par defaut d'`ErrorCard`. Une carte muette dit ce qui s'est passe ; un
        // bouton muet dit que l'application ne repond pas.
        detail.situation?.let { ErrorCard(it) }

        // --- Section 4 : qualite de la nuit
        PendulumCard {
            SectionHeader(stringResource(R.string.night_detail_quality))
            detail.controles.forEach {
                // Le signe porte l'etat pour l'oeil ; `descriptionValeur` le porte pour l'oreille.
                // Sans elle, « 31.1% ✗ » s'annonce « 31,1 pour cent » et l'echec disparait — le
                // signe etait le seul porteur de la seule information que cette table existe pour
                // donner.
                val signe = when (it.ok) {
                    true -> "✓"
                    false -> "✗"
                    null -> Mapping.TIRET
                }
                val etat = stringResource(
                    when (it.ok) {
                        true -> R.string.night_detail_state_pass
                        false -> R.string.night_detail_state_fail
                        null -> R.string.night_detail_state_unknown
                    }
                )
                InlineValue(
                    it.libelle.resoudre(),
                    "${it.valeur.resoudre()}   $signe",
                    note = stringResource(R.string.night_detail_threshold, it.seuil.resoudre()),
                    descriptionValeur = "${it.valeur.resoudre()}, $etat",
                )
            }
            InlineValue(
                stringResource(R.string.night_detail_rule_applied),
                detail.regleAppliquee.resoudre(),
            )
        }

        // --- Section 5 : parametres
        PendulumCard {
            TextButton(onClick = { paramsOuverts = !paramsOuverts }) {
                Text(stringResource(R.string.night_detail_advanced_params))
            }
            if (paramsOuverts) {
                Paragraphe(stringResource(R.string.night_detail_no_per_night_setting))
                Spacer(Modifier.height(Spacing.s.dp))
                OutlinedButton(onClick = onAppliquerATout, shape = PendulumShapes.button) {
                    Text(stringResource(R.string.night_detail_recompute_all))
                }
            }
        }

        // --- Section 6 : les deux sorties de cette nuit
        //
        // Elles sont ici et pas dans l'ecran d'export, qui porte la campagne : ce sont deux
        // documents d'une nuit precise, qu'on sort quand cette nuit-la pose question. Le chemin
        // est le meme que partout ailleurs — SAF, emplacement choisi par l'utilisateur.
        PendulumCard {
            SectionHeader(stringResource(R.string.night_detail_export))
            Paragraphe(stringResource(R.string.night_detail_export_report_note))
            OutlinedButton(onClick = onExporterRapport, shape = PendulumShapes.button) {
                Text(stringResource(R.string.night_detail_export_report))
            }
            Spacer(Modifier.height(Spacing.s.dp))
            Paragraphe(stringResource(R.string.night_detail_export_bundle_note))
            OutlinedButton(onClick = onExporterPaquet, shape = PendulumShapes.button) {
                Text(stringResource(R.string.night_detail_export_bundle))
            }
            // Un seul compte rendu pour les deux boutons : ils ecrivent l'un apres l'autre, jamais
            // ensemble, et deux lignes dont une seule est fraiche se lisent de travers.
            ecriture?.let {
                Spacer(Modifier.height(Spacing.s.dp))
                Text(
                    it.message.resoudre(),
                    style = PendulumType.caption,
                    color = if (it.echec) c.attention else c.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.l.dp))
    }

    val graphe = detail.graphe
    if (devoile && valeursOuvertes && graphe != null) {
        DataTableSheet(
            colonnes = listOf(
                stringResource(R.string.night_detail_table_index),
                stringResource(R.string.night_detail_table_start),
                stringResource(R.string.night_detail_table_duration),
                stringResource(R.string.night_detail_table_amplitude),
            ),
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

