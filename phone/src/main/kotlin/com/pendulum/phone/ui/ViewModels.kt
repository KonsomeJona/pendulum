package com.pendulum.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pendulum.phone.data.EtatTendance
import com.pendulum.phone.data.EveningContextSealer
import com.pendulum.phone.data.SaisieDuSoir
import com.pendulum.phone.data.PendulumPreferences
import com.pendulum.phone.data.PendulumRepository
import com.pendulum.phone.ui.chart.BandeMediane
import com.pendulum.phone.ui.chart.EtatPoint
import com.pendulum.phone.ui.chart.LigneReference
import com.pendulum.phone.ui.chart.PointNuit
import com.pendulum.phone.ui.chart.TendanceChartSpec
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.nights.NuitDetailUi
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.settings.ReglagesUi
import com.pendulum.phone.ui.text.Textes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Les ViewModels. Ils sont volontairement minces, et c'est le point.
 *
 * Tout ce qui decide quelque chose vit ailleurs et se teste sans Android : les seuils et les
 * estimateurs dans `ui/model/Aggregat.kt`, les criteres d'exclusion dans la vue SQL
 * `comparable_night`, la mise en forme dans `ui/model/Mapping.kt`. Ce qui reste ici est le
 * cablage — quel flux alimente quel ecran — plus la construction des `Spec` de graphe, qui doit
 * se faire en amont d'un `@Composable` pour que l'export PDF puisse reutiliser exactement les
 * memes objets.
 *
 * `AndroidViewModel` plutot qu'une fabrique : le repository n'a besoin que du contexte
 * applicatif, et le projet n'a aucune injection de dependances. En introduire une pour brancher
 * trois ecrans ferait passer un changement d'architecture pour une correction de defaut.
 */

/**
 * L'ecran Tendance.
 *
 * La bascule entre [TendanceUiState.Refus] et [TendanceUiState.Pret] n'est pas un `if` sur un
 * booleen : elle depend de la **nullite** des agregats, que `Mapping.agregat` refuse de produire
 * sous trois nuits. Il n'existe donc aucun chemin par lequel un chiffre agrege pourrait
 * apparaitre plus tot, meme en se trompant de branche.
 */
class TrendViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    val etat: StateFlow<TendanceUiState> = repo.observerTendance()
        .map { it.versUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TendanceUiState.Chargement)

    private fun EtatTendance.versUiState(): TendanceUiState {
        val r = rythme
        val c = compte

        // Sous trois nuits eligibles : aucun agregat n'existe, donc aucun graphe n'est construit.
        // Pas meme un graphe vide avec ses axes — un axe vide invite l'oeil a imaginer la courbe
        // qui manque, ce qui est exactement le contraire de ce que le refus veut dire.
        if (r == null || c == null) {
            return TendanceUiState.Refus(
                nuitsEligibles = nuitsEligibles,
                nuitsRequises = Aggregat.MIN_NUITS_AGREGAT,
                nuitsEnregistrees = nuits,
                ceSoir = null,
                reveil = EtatReveil.Rien,
            )
        }

        return TendanceUiState.Pret(
            rythme = r,
            compte = c,
            // La phrase de position s'applique au **compte horaire** et a lui seul : le rythme
            // fondamental n'a pas de seuil publie transposable a une mesure de cheville.
            position = Aggregat.position(c.ciBas, c.ciHaut, c.nuits),
            periodiciteQualifiee = Aggregat.qualifierPeriodicite(periodiciteMediane, r.nuits),
            tauxManques = tauxManquesMedian,
            graphe = grapheTendance(r),
            nuitsEnregistrees = nuitsEnregistrees,
            nuitsEligibles = nuitsEligibles,
            nuitsEcartees = nuitsEcartees,
            regle = Textes.Reglages.REGLE_AASM,
            masque = Textes.Reglages.HEALTH_CONNECT,
            plmw = nuitsAgregeables.map { it.plmiSpt }.average().takeIf { !it.isNaN() } ?: 0.0,
            ceSoir = null,
            reveil = EtatReveil.Rien,
            profilPersonnalise = profilPersonnalise,
            hashsMelanges = hashsMelanges,
            questionnaireEtat = Textes.Questionnaire.NON_REMPLI,
            exportPossible = nuitsEligibles >= Aggregat.MIN_NUITS_AGREGAT,
        )
    }

    /**
     * Le graphe de tendance.
     *
     * **L'axe des X est calendaire et non ordinal** : une nuit se place a sa date reelle, donc une
     * semaine sans mesure laisse un trou visible. C'est une information, pas un defaut — et c'est
     * la moitie de la raison pour laquelle les points ne sont pas relies. Relier deux points
     * separes de six jours affirmerait une trajectoire continue que la mesure ne soutient pas.
     *
     * Les nuits ecartees sont **tracees quand meme**, en cercle creux, a leur valeur, et exclues
     * de tout calcul. Les cacher donnerait une image plus propre et une lecture fausse.
     */
    private fun EtatTendance.grapheTendance(r: Aggregat.Resultat): TendanceChartSpec {
        val points = nuits
            .filter { it.rythmeSec > 0.0 }
            .map { n ->
                PointNuit(
                    sessionHex = n.sessionHex,
                    dateMs = n.startWallMs,
                    valeur = n.rythmeSec.toFloat(),
                    etat = when (n.etat) {
                        EtatNuit.ELIGIBLE -> EtatPoint.ELIGIBLE
                        EtatNuit.PROVISOIRE -> EtatPoint.MASQUE_ACCELERO
                        EtatNuit.ECARTEE -> EtatPoint.ECARTEE
                    },
                )
            }
            .sortedBy { it.dateMs }

        val premier = points.firstOrNull()?.dateMs ?: 0L
        val dernier = points.lastOrNull()?.dateMs ?: premier

        return TendanceChartSpec(
            grandeur = Aggregat.Grandeur.RYTHME_SECONDES,
            points = points,
            bandes = listOf(
                BandeMediane(
                    debutMs = premier,
                    finMs = dernier,
                    mediane = r.mediane.toFloat(),
                    ciBas = r.ciBas.toFloat(),
                    ciHaut = r.ciHaut.toFloat(),
                    etiquette = null,
                ),
            ),
            // Aucune ligne de reference sur le rythme : le seuil publie sur la periodicite est sur
            // une autre echelle avec d'autres preuves derriere lui, et le transposer fabriquerait
            // une frontiere clinique. Le seuil de 15/h appartient au compte horaire.
            reference = null as LigneReference?,
            premierJourMs = premier,
            dernierJourMs = dernier,
            pivotMs = null,
            descriptionAccessible = Textes.Graphes.descriptionTendance(
                points.size,
                Aggregat.Grandeur.RYTHME_SECONDES.unite,
            ),
        )
    }
}

/**
 * Le formulaire du soir et son scellement.
 *
 * L'horloge est un parametre et non `System.currentTimeMillis()` appele au fond d'une fonction :
 * la cle de nuit bascule a midi, donc toute la logique de rattachement depend de l'heure qu'il
 * est, et une horloge cachee rend cette regle intestable.
 */
class EveningViewModel(app: Application) : AndroidViewModel(app) {

    private val sealer = EveningContextSealer(app)
    private val prefs = PendulumPreferences(app)

    /** Vrai des que le contexte de la soiree en cours est scelle — donc que la montre peut partir. */
    val scelle: StateFlow<Boolean> = sealer
        .observerSoireeCourante(horloge())
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val repereDeSerrage: StateFlow<String> = prefs.repereDeSerrage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Resultat du scellement, consomme une fois par l'ecran puis remis a `null`.
     *
     * [ResultatScellement.PublicationEchouee] n'est pas une erreur au sens habituel : le contexte
     * **est** scelle, ce qui est l'essentiel et ce qui est irreversible. Seule la montre ne le
     * sait pas encore, et le Data Layer la rattrapera a la reconnexion. L'ecran doit le dire —
     * annoncer un succes complet ferait chercher pendant dix minutes pourquoi START reste bloque.
     */
    private val _resultat = MutableStateFlow<ResultatScellement?>(null)
    val resultat: StateFlow<ResultatScellement?> = _resultat

    fun sceller(saisie: SaisieDuSoir) {
        viewModelScope.launch {
            val maintenant = horloge()
            _resultat.value = try {
                if (sealer.sceller(saisie, maintenant)) {
                    // Le repere de serrage est retenu pour les soirs suivants : il doit etre
                    // identique d'une nuit a l'autre, donc le retaper serait une occasion de
                    // divergence plutot qu'une verification.
                    prefs.poserRepereDeSerrage(saisie.bracelet)
                    ResultatScellement.Scelle
                } else {
                    prefs.poserRepereDeSerrage(saisie.bracelet)
                    ResultatScellement.PublicationEchouee
                }
            } catch (e: Exception) {
                // `OnConflictStrategy.ABORT` : sceller deux fois la meme soiree leve plutot que
                // d'ecraser en silence. C'est le comportement voulu, et l'ecran doit dire
                // laquelle des deux choses s'est produite.
                ResultatScellement.DejaScelle
            }
        }
    }

    fun resultatConsomme() {
        _resultat.value = null
    }

    private fun horloge(): Long = System.currentTimeMillis()
}

enum class ResultatScellement { Scelle, PublicationEchouee, DejaScelle }

/**
 * Le detail d'une nuit.
 *
 * Il **lit enfin l'argument de route**. Le `night/{hex}` du graphe de navigation etait ignore :
 * quelle que soit la nuit sur laquelle on tapait, l'ecran affichait le meme jeu de demonstration —
 * 412 mouvements, sept controles qualite tous verts, une regle « algo 1.4.0 ».
 */
class NightDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    private val _detail = MutableStateFlow<NuitDetailUi?>(null)
    val detail: StateFlow<NuitDetailUi?> = _detail

    fun charger(sessionHex: String) {
        viewModelScope.launch { _detail.value = repo.detailDeNuit(sessionHex) }
    }
}

/** La liste des nuits. Rien a decider : la vue SQL a deja annote, [Mapping] a deja traduit. */
class NightsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    val nuits: StateFlow<List<NuitUi>> = repo.observerNuits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Les reglages.
 *
 * Plusieurs lignes affichent encore un tiret plutot qu'une valeur, et c'est deliberement visible :
 * la montre appairee et l'etat de Health Connect arrivent avec le chantier de l'assistant, l'espace
 * occupe avec celui de la porte P1. Un tiret dit « pas encore branche » ; « Pixel Watch 3 » ecrit
 * en dur disait « branche », ce qui etait faux.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PendulumPreferences(app)

    val reglages: StateFlow<ReglagesUi> = combine(
        prefs.sourceSommeilPreferee,
        prefs.repereDeSerrage,
        prefs.theme,
    ) { source, repere, theme ->
        ReglagesUi(
            regle = Textes.Reglages.REGLE_AASM,
            sourcePreferee = source ?: Textes.Reglages.SOURCE_INCONNUE,
            profil = PROFIL_DEFAUT,
            repereDePort = repere.ifBlank { NON_RENSEIGNE },
            arretAutomatique = Textes.Reglages.ARRET_AUTO,
            montre = NON_RENSEIGNE,
            healthConnect = NON_RENSEIGNE,
            espaceOccupe = NON_RENSEIGNE,
            versionApp = com.pendulum.phone.BuildConfig.VERSION_NAME,
            versionAlgo = NON_RENSEIGNE,
            theme = theme,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), REGLAGES_VIDES)

    fun poserTheme(theme: String) {
        viewModelScope.launch { prefs.poserTheme(theme) }
    }

    private companion object {
        const val PROFIL_DEFAUT = "default"
        const val NON_RENSEIGNE = "—"

        val REGLAGES_VIDES = ReglagesUi(
            regle = Textes.Reglages.REGLE_AASM,
            sourcePreferee = NON_RENSEIGNE,
            profil = PROFIL_DEFAUT,
            repereDePort = NON_RENSEIGNE,
            arretAutomatique = NON_RENSEIGNE,
            montre = NON_RENSEIGNE,
            healthConnect = NON_RENSEIGNE,
            espaceOccupe = NON_RENSEIGNE,
            versionApp = NON_RENSEIGNE,
            versionAlgo = NON_RENSEIGNE,
            theme = PendulumPreferences.THEME_SOMBRE,
        )
    }
}
