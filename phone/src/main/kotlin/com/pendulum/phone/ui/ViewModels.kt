package com.pendulum.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pendulum.phone.data.AppairageMontre
import com.pendulum.phone.data.EtatAppairage
import com.pendulum.phone.data.EtatMontre
import com.pendulum.phone.data.EtatTendance
import com.pendulum.phone.data.EveningContextSealer
import com.pendulum.phone.data.SaisieDuSoir
import com.pendulum.phone.data.PendulumPreferences
import com.pendulum.phone.data.PendulumRepository
import com.pendulum.phone.data.WatchCommands
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SourcesSommeil
import com.pendulum.phone.ui.onboarding.RepriseAssistant
import com.pendulum.phone.ui.chart.BandeMediane
import com.pendulum.phone.ui.chart.EtatPoint
import com.pendulum.phone.ui.chart.LigneReference
import com.pendulum.phone.ui.chart.PointNuit
import com.pendulum.phone.ui.chart.TendanceChartSpec
import com.pendulum.phone.ui.home.AccueilUi
import com.pendulum.phone.ui.home.MachineAccueil
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.nights.NuitDetailUi
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.settings.ReglagesUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.work.WorkScheduler
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
 * L'accueil.
 *
 * ### L'horloge est un champ, pas un appel enfoui
 *
 * Elle sert a deux choses et a rien d'autre : la cle de nuit qui dit quel contexte est « celui de
 * ce soir », et l'heure locale qui **departage** la machine a etats quand la base laisse deux
 * lectures egalement plausibles. Les deux usages sont explicites, et la decision elle-meme vit
 * dans [MachineAccueil], pur et teste sur ses bornes.
 *
 * L'heure locale est relue a chaque emission plutot que figee : une session qui se ferme a 6 h du
 * matin doit changer l'ecran, et une application restee ouverte toute la nuit ne doit pas
 * continuer a proposer de preparer une nuit qui a eu lieu.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    val etat: StateFlow<AccueilUi?> = repo.observerAccueil(horloge())
        .map { MachineAccueil.de(it, heureLocale()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Le bouton « fin de nuit », dans l'ordre, et l'ordre compte.
     *
     * Le balayage d'abord : il demande a la montre de pousser ce qu'elle detient encore, et c'est
     * un message — donc il echoue franchement quand la montre est hors de portee, au lieu de
     * s'inscrire dans un etat replique dont rien ne dirait s'il a ete lu. Les workers ensuite,
     * qui reconcilient ce qui est arrive, lisent l'hypnogramme puis scorent.
     *
     * On **n'attend pas** le succes du balayage pour enfiler la chaine : la montre a peut-etre
     * deja tout pousse pendant la nuit, auquel cas il n'y a rien a ramener et tout a analyser.
     * Subordonner l'analyse a la joignabilite de la montre rendrait une nuit complete
     * inexploitable parce que le bracelet est reste dans la salle de bain.
     */
    fun finDeNuit(sessionHex: String) {
        viewModelScope.launch {
            WatchCommands.demanderLeBalayage(getApplication())
            WorkScheduler.enqueueFinDeNuit(getApplication(), sessionHex)
        }
    }

    /**
     * Garde-fou 2 : le devoilement, journalise et horodate.
     *
     * Un seul appel, aucune confirmation a demander avant. `NightDao.markRevealed` porte
     * `WHERE revealedAtMs IS NULL`, donc rejouer le geste ne reecrit pas la date — la trace dit
     * quand le chiffre a ete vu pour la premiere fois, pas quand l'ecran a ete rouvert.
     */
    fun devoiler(sessionHex: String) {
        viewModelScope.launch { repo.devoiler(sessionHex, horloge()) }
    }

    private fun horloge(): Long = System.currentTimeMillis()

    private fun heureLocale(): Int =
        java.time.Instant.ofEpochMilli(horloge()).atZone(java.time.ZoneId.systemDefault()).hour
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

    /**
     * Garde-fou 2 : le devoilement, puis la relecture.
     *
     * La relecture n'est pas une precaution mais la seule facon de montrer le chiffre : le detail
     * est un instantane charge une fois, pas un flux, et `revealedAtMs` fait partie de ce qu'il
     * porte. `markRevealed` ne reecrit jamais une date deja posee, donc rejouer le geste est un
     * no-op — la trace dit quand le chiffre a ete vu la premiere fois, pas combien de fois
     * l'ecran a ete rouvert.
     */
    fun devoiler(sessionHex: String) {
        viewModelScope.launch {
            repo.devoiler(sessionHex, System.currentTimeMillis())
            _detail.value = repo.detailDeNuit(sessionHex)
        }
    }
}

/** La liste des nuits. Rien a decider : la vue SQL a deja annote, [Mapping] a deja traduit. */
class NightsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    val nuits: StateFlow<List<NuitUi>> = repo.observerNuits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * L'assistant de premier lancement.
 *
 * ### Ce qu'il repare
 *
 * `OnboardingPager` existait, complet, et **n'avait aucun appelant**. La consequence n'etait pas
 * cosmetique : le seul `rememberLauncherForActivityResult` de l'application vivait dans cet ecran
 * inatteignable, donc aucun chemin utilisateur n'accordait jamais les permissions Health Connect.
 * Chaque nuit etait alors scoree par le seul masque accelerometrique — la circularite
 * numerateur/denominateur que tout le projet existe pour eviter — sans qu'aucun ecran ne le dise.
 *
 * ### L'etat de l'appairage est un flux, pas une lecture
 *
 * `AppairageMontre.observer` s'abonne a `CapabilityClient` : l'etape 3 se coche d'elle-meme quand
 * l'application apparait sur la montre, pendant que l'utilisateur est encore en train de
 * l'installer. `WhileSubscribed` garantit que l'abonnement au Data Layer s'arrete des que l'ecran
 * part — un ecouteur Wearable oublie survit au composable, pas au ViewModel.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PendulumPreferences(app)
    private val lecteur = SleepReader(app)

    /** `null` tant que la premiere lecture du DataStore n'a pas abouti : on ne compose rien. */
    val etape: StateFlow<Int?> = prefs.etapeAssistant
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val montre: StateFlow<EtatMontre> = AppairageMontre.observer(app)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            EtatMontre(EtatAppairage.AUCUNE_MONTRE),
        )

    val sourcePreferee: StateFlow<String?> = prefs.sourceSommeilPreferee
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val repereDeSerrage: StateFlow<String> = prefs.repereDeSerrage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** `null` tant que Health Connect n'a pas ete interroge : l'ecran n'affiche alors rien. */
    private val _sante = MutableStateFlow<EtatSante?>(null)
    val sante: StateFlow<EtatSante?> = _sante

    /** Dernier resultat d'une tentative d'ouverture du magasin sur la montre, consomme une fois. */
    private val _installation = MutableStateFlow<Boolean?>(null)
    val installation: StateFlow<Boolean?> = _installation

    /**
     * L'etape est ecrite a la **sortie** de la page, et jamais a l'entree : une etape commencee
     * puis abandonnee n'est pas une etape franchie.
     */
    fun franchir(page: Int) {
        viewModelScope.launch {
            prefs.poserEtapeAssistant(RepriseAssistant.etapeApres(page, etape.value ?: 0))
        }
    }

    /**
     * Relit Health Connect : sa disponibilite, puis les sources des sept derniers jours.
     *
     * Appele a l'ouverture de l'etape 4 **et** au retour de la demande de permission. Sans le
     * second appel, l'ecran resterait sur `PERMISSIONS_MISSING` juste apres que l'utilisateur les
     * a accordees, ce qui se lit comme un refus.
     */
    fun relireLaSante() {
        viewModelScope.launch {
            val disponibilite = lecteur.availability()
            _sante.value = EtatSante(
                disponibilite = disponibilite,
                sources = if (disponibilite == SleepReader.Availability.READY) {
                    lecteur.sourcesRecentes(System.currentTimeMillis())
                } else {
                    null
                },
            )
        }
    }

    /**
     * Le choix de la source, ecrit dans les preferences.
     *
     * C'est le reglage que `SleepFetchWorker` lit et que personne n'ecrivait. Il ne s'agit pas
     * d'un confort : quand deux applications publient des sessions qui se chevauchent, le
     * denominateur depend de celle qu'on lit, et un denominateur qui change d'une nuit a l'autre
     * fabrique une tendance qui n'existe pas.
     */
    fun choisirSource(paquet: String) {
        viewModelScope.launch { prefs.poserSourceSommeilPreferee(paquet) }
    }

    /** Le repere de serrage, saisi a l'etape 5 et jusqu'ici jete. */
    fun poserLeRepere(repere: String) {
        viewModelScope.launch { prefs.poserRepereDeSerrage(repere) }
    }

    fun installerSurLaMontre() {
        viewModelScope.launch {
            _installation.value = AppairageMontre.ouvrirLeMagasinSurLaMontre(getApplication())
        }
    }

    fun installationConsommee() {
        _installation.value = null
    }
}

/**
 * Ce que l'etape 4 sait de Health Connect.
 *
 * @param sources `null` quand la question n'a pas de sens — Health Connect absent, trop ancien,
 *   ou permissions non accordees. Une liste vide, elle, est une reponse : rien n'ecrit de
 *   sommeil sur ce telephone, et l'ecran doit alors aider plutot que rester muet.
 */
data class EtatSante(
    val disponibilite: SleepReader.Availability,
    val sources: List<SourcesSommeil.Observee>?,
)

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
