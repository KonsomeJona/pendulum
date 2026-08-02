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
import com.pendulum.phone.DataEraser
import com.pendulum.phone.export.NightExporter
import com.pendulum.phone.export.ReportExporter
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
import com.pendulum.phone.ui.model.MachineReveil
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.model.Situations
import com.pendulum.phone.ui.nights.NuitDetailUi
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.QuestionnaireResponseEntity
import com.pendulum.phone.export.ReportExporter as RapportExporteur
import com.pendulum.phone.ui.export.ExportUi
import com.pendulum.phone.ui.quiz.IssueQuestionnaire
import com.pendulum.phone.ui.settings.RapportP1Ui
import com.pendulum.phone.ui.settings.ReglagesUi
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
    private val lecteur = SleepReader(app)

    /**
     * Ce que Health Connect repond, relu a la demande.
     *
     * Ce n'est pas un flux Room : la disponibilite et la liste des sources sont des appels
     * suspendus vers un fournisseur systeme, sans notification de changement. On les relit a
     * l'ouverture de l'ecran et au retour au premier plan — c'est-a-dire aux deux moments ou une
     * permission vient d'etre accordee ailleurs.
     */
    private val _disponibiliteSante = MutableStateFlow<SleepReader.Availability?>(null)
    private val _sourcesRecentes = MutableStateFlow<Int?>(null)

    init {
        relireLaSante()
    }

    fun relireLaSante() {
        viewModelScope.launch {
            val disponibilite = lecteur.availability()
            _disponibiliteSante.value = disponibilite
            _sourcesRecentes.value = if (disponibilite == SleepReader.Availability.READY) {
                lecteur.sourcesRecentes(System.currentTimeMillis())?.size
            } else {
                null
            }
        }
    }

    val etat: StateFlow<TendanceUiState> = combine(
        repo.observerTendance(),
        _disponibiliteSante,
        _sourcesRecentes,
    ) { tendance, disponibilite, sources ->
        tendance.versUiState(disponibilite, sources)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TendanceUiState.Chargement)

    private fun EtatTendance.versUiState(
        disponibilite: SleepReader.Availability?,
        sourcesRecentes: Int?,
    ): TendanceUiState {
        val r = rythme
        val c = compte

        // La bande d'etat du reveil : une fonction pure, alimentee par les faits que le
        // repository a lus. Elle etait cablee sur `EtatReveil.Rien`, donc la bande n'etait jamais
        // rendue et les cinq etats de `06-interface.md` §2.3 n'existaient qu'en apercu.
        val reveil = MachineReveil.de(faitsReveil, System.currentTimeMillis()) { ms ->
            Mapping.heureLisible(ms, faitsReveil?.zoneId ?: java.time.ZoneId.systemDefault().id)
        }
        val situation = Situations.sommeil(disponibilite, sourcesRecentes, originesDerniereNuit)

        // Aucun agregat n'existe, donc aucun graphe n'est construit. Pas meme un graphe vide avec
        // ses axes — un axe vide invite l'oeil a imaginer la courbe qui manque, ce qui est
        // exactement le contraire de ce que le refus veut dire.
        //
        // Deux causes menent ici et `Refus` les distingue par ses deux comptes : pas assez de
        // nuits eligibles, ou assez de nuits mais trop peu d'ajustements de rythme acceptes. La
        // seconde est la plus frequente et elle n'est pas une panne — voir `MotifRefus`.
        if (r == null || c == null) {
            return TendanceUiState.Refus(
                nuitsEligibles = nuitsEligibles,
                nuitsRythmeAjuste = nuitsRythmeAjuste,
                nuitsRequises = Aggregat.MIN_NUITS_AGREGAT,
                nuitsEnregistrees = nuits,
                reveil = reveil,
                situationSommeil = situation,
                sessionReveil = faitsReveil?.sessionHex,
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
            reveil = reveil,
            sessionReveil = faitsReveil?.sessionHex,
            profilPersonnalise = profilPersonnalise,
            hashsMelanges = hashsMelanges,
            questionnaireEtat = Textes.Questionnaire.NON_REMPLI,
            exportPossible = nuitsEligibles >= Aggregat.MIN_NUITS_AGREGAT,
            situationSommeil = situation,
        )
    }

    /**
     * L'action de la bande d'etat du reveil, pour les quatre etats qui en portent une.
     *
     * Les quatre libelles disent des choses differentes — « Transfer now », « Try again now »,
     * « Resume the transfer », « Run the analysis again » — et **demandent tous la meme chose** :
     * que la nuit reparte dans la chaine du bouton de fin de nuit. Le balayage demande a la montre
     * de pousser ce qu'elle detient encore ; la chaine reconcilie le disque, relit Health Connect,
     * puis score. Un etat qui manque des chunks les recoit, un etat qui attend l'hypnogramme le
     * redemande, une analyse en echec repart sur le brut conserve.
     *
     * On n'attend pas le succes du balayage : c'est le meme raisonnement qu'a l'accueil — la
     * montre a peut-etre deja tout pousse, et subordonner l'analyse a sa joignabilite rendrait une
     * nuit complete inexploitable parce que le bracelet est reste dans la salle de bain.
     */
    fun relancerLeReveil(sessionHex: String) {
        viewModelScope.launch {
            WatchCommands.demanderLeBalayage(getApplication())
            WorkScheduler.enqueueFinDeNuit(getApplication(), sessionHex)
        }
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
        // Une nuit sans ajustement accepte n'a pas de point : elle n'a pas de valeur du tout.
        // C'est la meme regle qu'a la liste et au detail, portee par la nullite de `rythmeSec`.
        val points = nuits
            .mapNotNull { n ->
                val valeur = n.rythmeSec ?: return@mapNotNull null
                PointNuit(
                    sessionHex = n.sessionHex,
                    dateMs = n.startWallMs,
                    valeur = valeur.toFloat(),
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
            // L'axe des X est calendaire, donc il lui faut un calendrier : les graduations sont
            // des dates locales, pas des multiples de 86 400 000 ms. Sans fuseau, une nuit
            // commencee a 23 h 14 s'etiquette au lendemain.
            zoneId = zoneId,
            pivotMs = null,
            // Un resume, pas une etiquette de bloc. Un `contentDescription` du type « graphe de
            // tendance sur 9 nuits » apprend a un lecteur d'ecran qu'il existe un graphe et rien
            // de ce qu'il contient. Le tableau de valeurs reste le chemin principal — aucun
            // resume ne remplace des donnees — mais il ne doit pas etre le seul moyen de savoir
            // qu'il y a quelque chose a y lire.
            descriptionAccessible = descriptionDe(points, r),
        )
    }

    private fun EtatTendance.descriptionDe(
        points: List<PointNuit>,
        r: Aggregat.Resultat,
    ): String {
        if (points.isEmpty()) return Textes.Graphes.DESCRIPTION_TENDANCE_VIDE
        val parHex = nuits.associateBy { it.sessionHex }
        val unite = Aggregat.Grandeur.RYTHME_SECONDES.unite
        fun date(p: PointNuit) = parHex[p.sessionHex]?.dateLisible.orEmpty()
        fun valeur(v: Float) = Math.round(v).toString()
        return Textes.Graphes.descriptionTendance(
            points = points.size,
            debut = date(points.first()),
            fin = date(points.last()),
            mediane = Math.round(r.mediane).toString(),
            minimum = valeur(points.minOf { it.valeur }),
            maximum = valeur(points.maxOf { it.valeur }),
            unite = unite,
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

    /**
     * Garde-fou 3 : un parametre ne se regle pas nuit par nuit.
     *
     * Le bouton s'appelle « Apply to every night » et il n'en existe pas d'autre : `RescoreAllWorker`
     * recalcule **toutes** les nuits depuis le brut, sous le hash courant. Il n'y a volontairement
     * pas de variante « ne recalculer que les recentes » — une tendance a trois points dont deux
     * ont ete calcules autrement n'est pas une tendance partielle, c'est un graphe faux.
     *
     * L'ecran ne se rafraichit pas dans la foulee, et c'est exact : le rescore est un travail de
     * fond qui peut durer, et afficher un nouveau chiffre avant qu'il ne soit calcule apprendrait
     * a lire des chiffres avant qu'ils ne soient vrais. La nuit se relit quand on y revient.
     */
    fun appliquerATout() {
        WorkScheduler.enqueueRescoreAll(getApplication())
    }

    /**
     * Le rapport d'une nuit, ecrit dans l'`Uri` que l'utilisateur vient de designer.
     *
     * Le flux vient de SAF et de nulle part ailleurs : l'application n'ecrit jamais dans un
     * repertoire partage de sa propre initiative, et ne declare pas `INTERNET`. Voir la KDoc de
     * [com.pendulum.phone.export.NightExporter].
     */
    fun exporterRapport(sessionHex: String, uri: android.net.Uri) {
        viewModelScope.launch {
            ecrire(uri) { ReportExporter.exportNight(getApplication(), sessionHex, it) }
        }
    }

    /** Le paquet brut d'une nuit, meme chemin SAF. Voir [exporterRapport]. */
    fun exporterPaquet(sessionHex: String, uri: android.net.Uri) {
        viewModelScope.launch {
            ecrire(uri) { NightExporter.exportBundle(getApplication(), sessionHex, it) }
        }
    }

    private suspend fun ecrire(uri: android.net.Uri, bloc: suspend (java.io.OutputStream) -> Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { bloc(it) }
            }
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
 * Le rapport de la porte P1.
 *
 * Un instantane, charge une fois : le rapport repond a une question qui ne bouge pas pendant
 * qu'on la lit — combien de nuits d'affilee sont restees dans les trois criteres. Un flux ferait
 * recomposer la conclusion pendant la lecture des lignes qui la justifient.
 *
 * `null` tant que la lecture n'a pas abouti, et l'ecran n'affiche alors rien : la meme regle qu'a
 * l'accueil et au detail de nuit. Un rapport qui s'ouvre sur « 0 nuit sur 3 » puis se remplit
 * apprend a lire un verdict avant qu'il ne soit vrai.
 */
class RapportP1ViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    private val _rapport = MutableStateFlow<RapportP1Ui?>(null)
    val rapport: StateFlow<RapportP1Ui?> = _rapport

    init {
        viewModelScope.launch { _rapport.value = repo.rapportP1() }
    }
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

    /**
     * L'espace occupe, relu a la demande et non observe.
     *
     * Ce n'est pas un flux : c'est une somme de tailles de fichiers plus celle du fichier de base,
     * donc une lecture disque. La relire a chaque emission d'une preference ferait un acces disque
     * par frappe de theme. Elle est relue a l'ouverture de l'ecran et apres un effacement — les
     * deux seuls moments ou elle change de facon que l'utilisateur puisse constater.
     */
    private val _espace = MutableStateFlow(NON_RENSEIGNE)

    /** Compte rendu du dernier reimport de paquet. Nul tant qu'il n'y en a pas eu. */
    private val _import = MutableStateFlow<String?>(null)

    init {
        relireLEspace()
    }

    fun relireLEspace() {
        viewModelScope.launch {
            val octets = withContext(Dispatchers.IO) { DataEraser.bytesOnDisk(getApplication()) }
            _espace.value = Mapping.octetsLisibles(octets)
        }
    }

    val reglages: StateFlow<ReglagesUi> = combine(
        prefs.sourceSommeilPreferee,
        prefs.repereDeSerrage,
        prefs.theme,
        _espace,
        _import,
    ) { source, repere, theme, espace, importe ->
        ReglagesUi(
            regle = Textes.Reglages.REGLE_AASM,
            sourcePreferee = source ?: Textes.Reglages.SOURCE_INCONNUE,
            profil = PROFIL_DEFAUT,
            repereDePort = repere.ifBlank { NON_RENSEIGNE },
            arretAutomatique = Textes.Reglages.ARRET_AUTO,
            montre = NON_RENSEIGNE,
            healthConnect = NON_RENSEIGNE,
            espaceOccupe = espace,
            versionApp = com.pendulum.phone.BuildConfig.VERSION_NAME,
            versionAlgo = NON_RENSEIGNE,
            theme = theme,
            dernierImport = importe,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), REGLAGES_VIDES)

    /**
     * Le retour du paquet d'une nuit — l'autre moitie de [NightExporter], et la seule qui rende
     * l'aller verifiable.
     *
     * Un export dont personne ne sait relire le produit n'est pas un export, c'est une perte
     * differee : `BundleRoundTripTest` prouve qu'une base reconstruite depuis un paquet rend un
     * resultat identique, et cette prouve ne vaut que s'il existe un chemin utilisateur qui
     * l'emprunte. C'est aussi ce qui permet de porter une campagne d'un telephone a un autre sans
     * passer par un serveur, ce que l'absence de permission `INTERNET` interdit de toute facon.
     *
     * L'echec est annonce et n'est pas une exception qui remonte : un fichier choisi au hasard
     * dans le selecteur est le cas ordinaire, pas un incident.
     */
    fun importerNuit(uri: android.net.Uri) {
        viewModelScope.launch {
            val resultat = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        NightExporter.importBundle(getApplication(), it)
                    }
                }.getOrNull()
            }
            _import.value = if (resultat == null) {
                Textes.Reglages.IMPORT_REFUSE
            } else {
                // La nuit importee est nommee par sa date : « import reussi » ne permet pas de
                // verifier qu'on a repris le bon fichier. La date vient de la session ecrite par
                // l'import, pas de `comparable_night` — cette vue n'a pas encore de ligne, la
                // nuit n'ayant pas ete analysee.
                val session = withContext(Dispatchers.IO) {
                    PendulumDatabase.get(getApplication()).nightDao().find(resultat)
                }
                // Le paquet porte le brut, jamais les resultats : c'est un choix de
                // `NightExporter`, pour qu'on ne compare pas un chiffre exporte a un chiffre
                // recalcule par une version ulterieure. Une nuit importee doit donc etre
                // **analysee**, sans quoi elle entre en base et n'apparait nulle part.
                WorkScheduler.enqueueNightChain(getApplication(), resultat)
                Textes.Reglages.importee(
                    session?.let { Mapping.dateLisible(it.startWallMs, it.zoneId) } ?: resultat
                )
            }
            relireLEspace()
        }
    }

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

/**
 * L'ecran d'export, et le document qui est la raison d'etre du projet.
 *
 * ### Ce qu'il repare
 *
 * `ReportExporter` etait ecrit, complet, et **n'avait aucun appelant** : les cinq lambdas de
 * l'ecran d'export etaient vides dans `MainActivity`. Le seul but que `README.md` juge defendable
 * — « produire un document a poser devant un medecin » — n'etait atteignable par aucun geste.
 *
 * ### Le garde-fou tient au meme endroit qu'a l'ecran
 *
 * Le bouton reste visible et desactive sous [Aggregat.MIN_NUITS_AGREGAT] nuits eligibles, avec son
 * motif ecrit dessus : c'est `ExportScreen` qui le decide, a partir du seul champ
 * `nuitsEligibles`, et non ce ViewModel. Il n'y a donc pas deux endroits ou la regle peut diverger,
 * et aucun chemin ou le bouton serait actif et l'ecriture echouerait.
 */
class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    private val _questionnaire = MutableStateFlow(true)

    /** Par defaut **oui** : masquer les nuits ratees a un medecin est trompeur. */
    private val _ecartees = MutableStateFlow(true)

    /** Le nom du fichier ecrit, une fois l'ecriture faite. Consomme par l'ecran, pas efface. */
    private val _ecrit = MutableStateFlow<String?>(null)

    val etat: StateFlow<ExportUi?> = combine(
        repo.observerTendance(),
        _questionnaire,
        _ecartees,
        _ecrit,
    ) { tendance, questionnaire, ecartees, ecrit ->
        val nuits = tendance.nuits.sortedBy { it.startWallMs }
        ExportUi(
            inclureQuestionnaire = questionnaire,
            inclureEcartees = ecartees,
            nuitsEligibles = tendance.nuitsEligibles,
            periode = when {
                nuits.isEmpty() -> "—"
                nuits.size == 1 -> nuits.first().dateLisible
                else -> "${nuits.first().dateLisible} – ${nuits.last().dateLisible}"
            },
            profilPersonnalise = tendance.profilPersonnalise,
            ecrit = ecrit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun poserQuestionnaire(inclus: Boolean) {
        _questionnaire.value = inclus
    }

    fun poserEcartees(inclus: Boolean) {
        _ecartees.value = inclus
    }

    /** Le nom propose dans le selecteur SAF. Le jour de la generation, pas celui d'une nuit. */
    fun nomFichier(): String =
        Textes.Export.nomFichier(Mapping.jourIso(System.currentTimeMillis(), java.time.ZoneId.systemDefault().id))

    /**
     * L'ecriture, dans l'`Uri` que l'utilisateur vient de designer, et nulle part ailleurs.
     *
     * L'etat de la tendance est relu au moment de l'ecriture plutot que capture a l'affichage :
     * entre l'ouverture de l'ecran et le choix de l'emplacement, un rescore a pu se terminer, et
     * un document qui porterait les chiffres d'avant sans le dire serait un document faux.
     */
    fun enregistrer(uri: android.net.Uri, nom: String) {
        viewModelScope.launch {
            val tendance = repo.observerTendance().first()
            withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        RapportExporteur.exportCampagne(
                            context = getApplication(),
                            etat = tendance,
                            inclureQuestionnaire = _questionnaire.value,
                            inclureEcartees = _ecartees.value,
                            out = it,
                        )
                    }
                }
            }
            _ecrit.value = nom
        }
    }
}

/**
 * Le questionnaire de depistage.
 *
 * ### Une seule question, et une reponse qui se garde
 *
 * `questionnaire_response` est append-only par usage : on ajoute une passation, on ne corrige pas.
 * « Revoir mes reponses » ne modifie donc rien — il repose la question, et la reponse suivante
 * s'ajoute avec sa date. C'est ce qui permet de dire quand une reponse a ete donnee, et le
 * rapport pour le medecin les liste toutes.
 *
 * ### Pourquoi la reponse « non » n'efface pas la mesure
 *
 * L'issue est une phrase, jamais un score, et elle ne conditionne aucun autre ecran : le
 * questionnaire porte sur ce qui est ressenti a l'eveil, la montre mesure ce qui se passe pendant
 * le sommeil. Faire dependre l'un de l'autre reviendrait a laisser un depistage clore une mesure.
 */
class QuizViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = PendulumDatabase.get(app).questionnaireDao()

    private val _issue = MutableStateFlow<IssueQuestionnaire?>(null)
    val issue: StateFlow<IssueQuestionnaire?> = _issue

    init {
        viewModelScope.launch {
            _issue.value = withContext(Dispatchers.IO) {
                dao.all().firstOrNull()?.let { issueDe(it.answersJson) }
            }
        }
    }

    fun repondre(urgenceDeBouger: Boolean) {
        viewModelScope.launch {
            val json = """{"urge_to_move":$urgenceDeBouger}"""
            withContext(Dispatchers.IO) {
                dao.append(
                    QuestionnaireResponseEntity(
                        kind = KIND,
                        answeredAtMs = System.currentTimeMillis(),
                        answersJson = json,
                    )
                )
            }
            _issue.value = issueDe(json)
        }
    }

    /** Reposer la question. La passation precedente reste en base avec sa date. */
    fun revoir() {
        _issue.value = null
    }

    private fun issueDe(json: String): IssueQuestionnaire = when {
        json.contains("\"urge_to_move\":true") -> IssueQuestionnaire.COMPATIBLE
        json.contains("\"urge_to_move\":false") -> IssueQuestionnaire.NON_COMPATIBLE
        else -> IssueQuestionnaire.INCOMPLET
    }

    private companion object {
        /** Le nom de la passation. Une seule question ; le questionnaire detaille viendra a cote. */
        const val KIND = "screening-single"
    }
}

/**
 * L'effacement total.
 *
 * `DataEraser` etait ecrit — travaux annules, fichiers avant base, `VACUUM` — et **n'avait aucun
 * appelant** : la ligne « Erase all data » des reglages appelait un `{}`. Une application de sante
 * dont le bouton d'effacement ne fait rien promet exactement ce qu'elle ne tient pas.
 *
 * L'espace occupe est relu avant et apres, et affiche : c'est la seule confirmation verifiable que
 * les huit heures d'accelerometrie par nuit sont bien parties de `filesDir`, la ou une base vide
 * et un ecran vide ne prouvent rien.
 */
class EffacementViewModel(app: Application) : AndroidViewModel(app) {

    private val _espace = MutableStateFlow("—")
    val espace: StateFlow<String> = _espace

    private val _efface = MutableStateFlow(false)
    val efface: StateFlow<Boolean> = _efface

    init {
        relire()
    }

    fun effacer() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DataEraser.eraseEverything(getApplication()) }
            // `DataEraser` annule **tous** les travaux, et c'est voulu : un `SleepFetchWorker`
            // deja en file recreerait une ligne quelques minutes apres l'effacement. Le chien de
            // garde, lui, ne recree rien — il constate qu'aucune session n'est ouverte — et sans
            // lui l'application reste sans surveillance jusqu'au prochain demarrage.
            WorkScheduler.ensureWatchdog(getApplication())
            _efface.value = true
            relire()
        }
    }

    private fun relire() {
        viewModelScope.launch {
            val octets = withContext(Dispatchers.IO) { DataEraser.bytesOnDisk(getApplication()) }
            _espace.value = Mapping.octetsLisibles(octets)
        }
    }
}
