package com.pendulum.phone.data

import android.content.Context
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.ui.home.SessionAccueil
import com.pendulum.phone.ui.home.SourceAccueil
import com.pendulum.phone.ui.model.Aggregat
import com.pendulum.phone.ui.model.CheminDeCalcul
import com.pendulum.phone.ui.model.EtatNuit
import com.pendulum.phone.ui.model.MachineReveil
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.Controles
import com.pendulum.phone.ui.model.NuitUi
import com.pendulum.phone.ui.model.PorteP1
import com.pendulum.phone.ui.model.Situations
import com.pendulum.phone.ui.nights.NuitDetailUi
import com.pendulum.phone.ui.settings.RapportP1Ui
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.work.AnalysisParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Le pont qui manquait entre la base et les ecrans.
 *
 * Toute la chaine existait deja — ingestion Data Layer, workers d'analyse, Room, Health Connect,
 * export — et **aucun ecran ne la lisait**. `PendulumNavHost` etait cable en dur sur
 * `ApercuDonnees`, le jeu de donnees d'apercu, ce qui donnait a une installation neuve une
 * tendance complete sur sept nuits, un hypnogramme et une source « Samsung Health ». La base,
 * elle, restait vide.
 *
 * ### Ce que ce fichier ne fait pas
 *
 * Il ne calcule rien de neuf. Les medianes, les intervalles bootstrap a graine deterministe, la
 * MDC95 et les cinq phrases de position sont dans [Aggregat], teste sur ses bornes ; les criteres
 * d'exclusion sont dans la vue SQL `comparable_night`, evaluee avant tout code d'affichage ; la
 * mise en forme est dans [Mapping], pur. Ce repository ne fait que **choisir quoi lire et dans
 * quel ordre**, ce qui est exactement ce qu'un test JVM ne peut pas verifier et ce qui n'a donc
 * rien d'autre a faire ici.
 *
 * ### Pourquoi pas d'injection de dependances
 *
 * Le projet n'en a aucune, et en introduire une pour brancher cinq ecrans reviendrait a faire
 * passer un changement d'architecture pour une correction de defaut. La base est deja un
 * singleton de processus (`PendulumDatabase.get`), et les ViewModels construisent leur repository
 * a partir du contexte applicatif.
 */
class PendulumRepository(context: Context) {

    private val app = context.applicationContext
    private val db = PendulumDatabase.get(app)
    private val prefs = PendulumPreferences(app)

    // -------------------------------------------------------------------------------------
    // Lecture des nuits
    // -------------------------------------------------------------------------------------

    /**
     * Toutes les nuits du profil de parametres **actif**, annotees, dans l'ordre chronologique.
     *
     * Le `paramsHash` n'est pas un argument optionnel qu'on pourrait oublier : il vient du profil
     * actif en base, et `TrendDao` n'expose aucune surcharge sans lui. C'est le garde-fou 3
     * (« la tendance refuse de melanger deux hashs ») applique au niveau du type — melanger deux
     * hashs tracerait sur un meme graphe des chiffres produits par deux algorithmes differents,
     * et le saut entre les deux se lirait comme un changement clinique.
     */
    fun observerNuits(): Flow<List<NuitUi>> =
        observerLecture().map { it.nuits }.flowOn(Dispatchers.IO)

    /**
     * Ce que trois ecrans lisent tous les trois : les sessions, le hash actif, la source de
     * sommeil preferee, et les nuits deja traduites.
     *
     * Les trois assemblaient le meme `combine` et le meme `map` — douze lignes identiques,
     * recopiees deux fois et demie. Ce n'etait pas seulement du volume : le repli
     * `?: AnalysisParams.DEFAULT.paramsHash` et le tri antichronologique y figuraient trois fois,
     * donc un ecran pouvait en perdre un sans que rien ne le dise.
     */
    private data class Lecture(
        val sessions: List<NightSessionEntity>,
        val profil: ParamProfileEntity?,
        val hash: String,
        val sourcePreferee: String?,
        val nuits: List<NuitUi>,
    )

    private fun observerLecture(): Flow<Lecture> =
        combine(
            db.nightDao().observeAll(),
            db.paramDao().observeActive(),
            prefs.sourceSommeilPreferee,
        ) { sessions, profil, sourcePreferee -> Triple(sessions, profil, sourcePreferee) }
            .map { (sessions, profil, sourcePreferee) ->
                val hash = profil?.paramsHash ?: AnalysisParams.DEFAULT.paramsHash
                Lecture(sessions, profil, hash, sourcePreferee, nuitsDe(hash, sessions, sourcePreferee))
            }

    /** Les nuits du hash actif, traduites et triees de la plus recente a la plus ancienne. */
    private suspend fun nuitsDe(
        hash: String,
        sessions: List<NightSessionEntity>,
        sourcePreferee: String?,
    ): List<NuitUi> {
        val parHex = sessions.associateBy { it.sessionHex }
        return db.trendDao().allNights(hash, REGLE_PAR_DEFAUT, MASQUE_PAR_DEFAUT)
            .map { n -> versNuitUi(n, parHex[n.sessionHex], sourcePreferee) }
            .sortedByDescending { it.startWallMs }
    }

    /**
     * Les nuits qui ont le droit d'entrer dans un agregat : comparables **et** publiables.
     *
     * Les deux conditions sont distinctes et le restent. Une nuit peut etre parfaitement
     * comparable — meme jambe, meme bracelet, calibration stable — et n'avoir aucun droit de
     * porter un chiffre, parce que son masque n'a pas converge ou que son sommeil analysable est
     * insuffisant.
     */
    private suspend fun nuitsAgregeables(hash: String): List<ComparableNight> =
        db.trendDao().trendPoints(hash, REGLE_PAR_DEFAUT, MASQUE_PAR_DEFAUT)

    /**
     * Ce qu'il faut pour construire l'ecran Tendance, en une seule lecture coherente.
     *
     * Renvoyer un enregistrement plutot que le `TendanceUiState` final laisse au ViewModel le
     * choix de la branche refus/pret, qui est une decision d'interface, et garde ce fichier
     * ignorant de ce qui s'affiche.
     */
    fun observerTendance(): Flow<EtatTendance> =
        observerLecture()
            .map { (sessions, profil, hash, _, nuits) ->
                val agregeables = nuitsAgregeables(hash)
                val ajustees = agregeables.filter { Mapping.rythmeSec(it) != null }

                // `observeAll` trie par `startWallMs DESC` : la premiere ligne est la nuit dont la
                // bande d'etat parle. On ne filtre pas sur une cle de nuit — une nuit d'avant-hier
                // restee en transfert est exactement celle dont il faut dire ou elle en est.
                val recente = sessions.firstOrNull()
                val dernierSnapshot = recente?.let { db.hcSnapshotDao().latest(it.sessionHex) }

                EtatTendance(
                    nuits = nuits,
                    nuitsAgregeables = agregeables,
                    nuitsRythmeAjuste = ajustees.size,
                    // La mediane du rythme ne porte que sur les nuits dont l'ajustement a ete
                    // **accepte**. Sans ce filtre, les nuits refusees entraient dans le bootstrap
                    // avec un `fundamentalSec` fini mais non identifie — ou avec `NaN`, qui rend
                    // la mediane entiere `NaN` des qu'il tombe du bon cote du tri.
                    rythme = Mapping.agregat(
                        Aggregat.Grandeur.RYTHME_SECONDES,
                        ajustees,
                    ) { it.fundamentalSec },
                    compte = Mapping.agregat(
                        Aggregat.Grandeur.COMPTE_HORAIRE,
                        agregeables,
                    ) { it.plmi },
                    // Le taux de manques est mesure par la deconvolution harmonique, pas suppose.
                    // Sa mediane sur les nuits retenues est a la fois un indicateur de qualite et
                    // le critere qui dit si ces nuits mesurent bien la meme chose.
                    tauxManquesMedian = agregeables
                        .takeIf { it.isNotEmpty() }
                        ?.let { Aggregat.mediane(DoubleArray(it.size) { i -> it[i].missRate }) }
                        ?: 0.0,
                    periodiciteMediane = agregeables
                        .takeIf { it.isNotEmpty() }
                        ?.let { Aggregat.mediane(DoubleArray(it.size) { i -> it[i].periodicityIndex }) }
                        ?: 0.0,
                    profilPersonnalise = profil
                        ?.takeIf { it.paramsHash != AnalysisParams.DEFAULT.paramsHash }
                        ?.label,
                    // Deux hashs presents dans les nuits enregistrees : la tendance en tracerait
                    // un seul, et le silence sur l'autre serait trompeur.
                    hashsMelanges = sessions
                        .mapNotNull { it.paramsHash }
                        .distinct()
                        .size > 1,
                    // Les faits bruts de la derniere nuit. La **decision** de ce qu'il faut
                    // afficher appartient a `MachineReveil`, pure et testee sur ses bornes ; ce
                    // fichier ne fait que lire, et il reste ignorant des cinq etats.
                    faitsReveil = recente?.let { s ->
                        MachineReveil.Faits(
                            sessionHex = s.sessionHex,
                            dateLisible = Mapping.dateLisible(s.startWallMs, s.zoneId),
                            zoneId = s.zoneId,
                            etatSession = s.state,
                            chunksRecus = db.chunkDao().count(s.sessionHex),
                            totalChunks = s.totalChunks,
                            octetsRecus = db.chunkDao().totalBytes(s.sessionHex),
                            analyseeAtMs = s.analyzedAtMs,
                            finDeNuitMs = s.endWallMs,
                            // Le seul fait qui dise que le chiffre repose sur un denominateur
                            // independant : une fenetre de sommeil Health Connect existe pour le
                            // hash courant. Un `hc_snapshot` reussi ne suffit pas — le rescore
                            // peut ne pas encore avoir eu lieu.
                            masqueSommeilApplique = db.derivedDao()
                                .windowsOf(s.sessionHex, hash)
                                .any { it.source == MASQUE_PAR_DEFAUT },
                            hypnogrammeRecu = dernierSnapshot?.selectedRecordId != null,
                            tentativesHc = db.hcSnapshotDao().attemptCount(s.sessionHex),
                            derniereTentativeMs = dernierSnapshot?.fetchedAtMs,
                            integriteRejetee = s.integrityRejectedFraction,
                        )
                    },
                    // `originCount` compte les applications distinctes ayant publie une session
                    // recouvrant cette nuit. Deux ou plus, et le denominateur depend de celle
                    // qu'on lit : c'est exactement E-HC-03.
                    originesDerniereNuit = dernierSnapshot?.originCount ?: 0,
                    // Le fuseau de la nuit la plus recente — `observeAll` trie deja par
                    // `startWallMs DESC`. L'axe des X de la tendance est calendaire : il lui faut
                    // un calendrier, et le seul defendable est celui ou les nuits ont ete vecues.
                    // Une campagne a cheval sur deux fuseaux — un voyage — se lira dans le dernier
                    // des deux ; c'est une approximation assumee, la seule alternative etant un
                    // axe dont l'echelle change au milieu.
                    zoneId = recente?.zoneId ?: java.time.ZoneId.systemDefault().id,
                )
            }
            .flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------------------
    // L'accueil
    // -------------------------------------------------------------------------------------

    /**
     * Ce que l'accueil doit savoir, en une seule lecture coherente.
     *
     * Il renvoie un [SourceAccueil] — l'etat persiste — et non l'ecran final : la decision
     * (« preparer la nuit » plutot que « fin de nuit ») vit dans [MachineAccueil], pur et teste
     * sur ses bornes, et ce fichier reste ignorant de ce qui s'affiche.
     *
     * ### L'horloge est un parametre
     *
     * La cle de nuit bascule a midi, donc savoir si le contexte de « la soiree courante » est
     * scelle depend de l'heure qu'il est. La passer en argument est ce qui rend la regle
     * lisible : une horloge appelee au fond d'une fonction rendrait le rattachement d'une nuit
     * intestable, et la bascule de midi est exactement le genre de regle qui se casse en silence.
     *
     * Limite assumee : la cle est figee a la construction du flux, donc a l'ouverture de l'ecran.
     * Une application laissee ouverte a travers midi garderait la cle de la veille jusqu'a sa
     * recreation. C'est une seconde de retard sur un evenement quotidien, contre un
     * `flatMapLatest` sur une horloge qui recomposerait l'ecran sans raison.
     */
    fun observerAccueil(maintenantMs: Long): Flow<SourceAccueil> {
        val cleDeNuit = WirePaths.nightKey(maintenantMs)
        return combine(
            db.nightDao().observeAll(),
            db.contextDao().observe(cleDeNuit),
            db.paramDao().observeActive(),
            prefs.repereDeSerrage,
            prefs.sourceSommeilPreferee,
        ) { sessions, contexte, profil, repere, sourcePreferee ->
            val hash = profil?.paramsHash ?: AnalysisParams.DEFAULT.paramsHash
            val nuits = nuitsDe(hash, sessions, sourcePreferee)

            // `observeAll` trie deja par `startWallMs DESC` : la premiere ligne est la session la
            // plus recente, quelle que soit la soiree a laquelle elle se rattache. On ne filtre
            // volontairement pas sur la cle de nuit courante — une nuit d'avant-hier restee
            // ouverte est exactement ce que le bouton de fin de nuit doit pouvoir fermer.
            val recente = sessions.firstOrNull()

            SourceAccueil(
                sessionRecente = recente?.let {
                    SessionAccueil(
                        sessionHex = it.sessionHex,
                        etat = it.state,
                        analysee = it.analyzedAtMs != null,
                        debutLisible = Mapping.heureLisible(it.startWallMs, it.zoneId),
                        dateLisible = Mapping.dateLisible(it.startWallMs, it.zoneId),
                    )
                },
                contexteScelle = contexte != null,
                jambeScellee = Mapping.libelleJambe(contexte?.leg),
                // Le bracelet du contexte scelle fait foi sur la preference : c'est celui qui a
                // ete confirme ce soir, pas celui d'un soir precedent.
                repereDeSerrage = contexte?.strapId?.takeIf { it.isNotBlank() } ?: repere,
                sourceSommeil = sourcePreferee?.let {
                    Mapping.libelleSource(MASQUE_PAR_DEFAUT, it)
                },
                nuitsEnregistrees = nuits.size,
                nuitsEligibles = nuits.count { it.etat == EtatNuit.ELIGIBLE },
                // `comparable_night` n'existe que pour les nuits deja scorees : la premiere
                // ligne est donc la derniere nuit qui a un chiffre a devoiler.
                derniereNuitAnalysee = nuits.firstOrNull(),
            )
        }.flowOn(Dispatchers.IO)
    }

    private fun versNuitUi(
        n: ComparableNight,
        session: NightSessionEntity?,
        sourcePreferee: String?,
    ): NuitUi = Mapping.nuitUi(
        n = n,
        finWallMs = session?.endWallMs,
        sourceSommeil = Mapping.libelleSource(n.maskSource, sourcePreferee),
        drapeaux = Mapping.drapeaux(
            n = n,
            gapCount = session?.gapCount ?: 0,
            gapTotalMs = session?.gapTotalMs ?: 0L,
            batteryPctLast = session?.batteryPctLast,
        ),
    )

    // -------------------------------------------------------------------------------------
    // Le detail d'une nuit
    // -------------------------------------------------------------------------------------

    /**
     * Tout ce que la base sait d'une nuit.
     *
     * Ce qui n'y est **pas** : l'enveloppe du signal. Elle est calculee pendant l'analyse et
     * jamais persistee ; la reconstruire demanderait de relire les chunks bruts et de refaire la
     * chaine de traitement, ce qui n'a pas sa place dans l'ouverture d'un ecran. Le graphe est
     * donc `null` et la section n'est pas dessinee — plutot que dessinee avec une courbe
     * fabriquee, ce qui etait le cas jusqu'ici.
     */
    suspend fun detailDeNuit(sessionHex: String): NuitDetailUi? = withContext(Dispatchers.IO) {
        val session = db.nightDao().find(sessionHex) ?: return@withContext null
        val hash = db.paramDao().active()?.paramsHash ?: AnalysisParams.DEFAULT.paramsHash
        val nuit = db.trendDao()
            .allNights(hash, REGLE_PAR_DEFAUT, MASQUE_PAR_DEFAUT)
            .firstOrNull { it.sessionHex == sessionHex }
            ?: return@withContext null

        val evenements = db.derivedDao().eventsOf(sessionHex, hash)
        val resultat = db.derivedDao().resultsOf(sessionHex, hash)
            .firstOrNull { it.rule == REGLE_PAR_DEFAUT && it.maskSource == MASQUE_PAR_DEFAUT }
        val sourcePreferee = prefs.sourceSommeilPreferreeMaintenant()
        // Resolu **une fois** et passe aux trois endroits qui l'affichent. Il l'etait deux fois
        // par deux chemins differents, dont l'un ecrivait le libelle en dur : le detail d'une
        // nuit montrait alors deux libelles de source pour la meme nuit.
        val sourceSommeil = Mapping.libelleSource(nuit.maskSource, sourcePreferee)

        NuitDetailUi(
            nuit = versNuitUi(nuit, session, sourcePreferee),
            auLit = Mapping.dureeLisible(nuit.analysableMin),
            graphe = null,
            hypnogramme = null,
            mouvements = evenements.size,
            plms = resultat?.plmsCount ?: 0,
            plmw = resultat?.plmwCount ?: 0,
            // Les rejets sont comptes **a cote** des mouvements retenus et non caches : ce sont
            // eux qui disent pourquoi le chiffre est ce qu'il est, et un ecart de posture qui
            // ecarte quatorze mouvements change la lecture de la nuit.
            ecartesPosture = evenements.count { it.rejectReason == REJET_POSTURE },
            ecartesDuree = evenements.count { it.rejectReason in REJETS_DUREE },
            series = evenements.count { it.inSeriesAasm },
            imiMedianSec = intervalleMedianSec(evenements.map { it.onsetMsRel }),
            controles = Controles.de(session, nuit, resultat, sourceSommeil),
            regleAppliquee = "${Textes.Reglages.REGLE_AASM} · ${session.algoVersion.orEmpty()}",
            // Le taux de manques et l'encadrement respiratoire etaient calcules par `:algo` et
            // persistes dans `plm_result` depuis le debut, et affiches nulle part. Le bloc ne
            // calcule rien de neuf : il met en forme le chemin qui mene au chiffre.
            pourquoi = CheminDeCalcul.de(
                n = nuit,
                resultat = resultat,
                dureeEnregistreeMin = dureeEnregistreeMin(session),
                mouvementsRetenus = (resultat?.plmsCount ?: 0) + (resultat?.plmwCount ?: 0),
                regle = Textes.Reglages.REGLE_AASM,
                sourceSommeil = sourceSommeil,
            ),
            situation = Situations.nuit(nuit, session),
        )
    }

    /**
     * Duree de la session, du demarrage a l'arret, en minutes. Zero tant que la nuit est ouverte.
     *
     * Elle sert de **contexte** au sommeil analysable et jamais de denominateur : « 5 h 12 » ne
     * dit rien, « 5 h 12 sur 7 h 41 enregistrees » dit ou est passe le reste.
     */
    private fun dureeEnregistreeMin(session: NightSessionEntity): Double {
        val fin = session.endWallMs ?: return 0.0
        return ((fin - session.startWallMs) / 60_000.0).coerceAtLeast(0.0)
    }

    /**
     * Mediane des intervalles entre debuts de mouvements consecutifs.
     *
     * C'est une **mediane et non une moyenne** : la distribution des intervalles est
     * log-normale et porte des harmoniques — un mouvement manque fusionne deux intervalles de
     * 21 s en un de 42 s — et une moyenne suivrait ces harmoniques. La mediane les encaisse.
     */
    private fun intervalleMedianSec(onsets: List<Long>): Double {
        if (onsets.size < 2) return 0.0
        val tries = onsets.sorted()
        val ecarts = DoubleArray(tries.size - 1) { (tries[it + 1] - tries[it]) / 1000.0 }
        return Aggregat.mediane(ecarts)
    }

    // -------------------------------------------------------------------------------------
    // La porte P1
    // -------------------------------------------------------------------------------------

    /**
     * Le rapport de la porte P1 sur les dernieres nuits.
     *
     * Il lit `night_session` **et rien d'autre** : la porte P1 porte sur l'acquisition, pas sur
     * l'analyse. Une nuit dont aucun mouvement n'a encore ete detecte a pourtant tout ce qu'il
     * faut pour dire si le capteur a tenu — c'est meme l'ordre du projet, puisque P1 precede la
     * premiere ligne d'algorithme.
     *
     * Le verdict de campagne est calcule sur les **memes** nuits que celles affichees. Le
     * calculer sur toute la base et n'en montrer que quatorze donnerait une conclusion que les
     * lignes visibles ne permettraient pas de verifier.
     */
    suspend fun rapportP1(limite: Int = PorteP1.NUITS_RAPPORT): RapportP1Ui =
        withContext(Dispatchers.IO) {
            val verdicts = db.nightDao().all().take(limite).map { PorteP1.de(it) }
            RapportP1Ui(nuits = verdicts, campagne = PorteP1.campagne(verdicts))
        }

    // -------------------------------------------------------------------------------------
    // Le garde-fou 2 : le resultat est masque au reveil, et le devoiler laisse une trace
    // -------------------------------------------------------------------------------------

    /**
     * Marque une nuit comme devoilee. **Irreversible et horodate**, par la requete elle-meme
     * (`WHERE revealedAtMs IS NULL`) : un second appel ne reecrit pas la date.
     *
     * La trace n'est pas une surveillance, elle sort dans l'export. Lire son chiffre au reveil,
     * dans l'etat ou l'on est le moins capable de le juger, est un choix legitime ; le faire sans
     * que cela se voie dans le document remis au medecin ne l'est pas.
     */
    suspend fun devoiler(sessionHex: String, maintenantMs: Long) {
        db.nightDao().markRevealed(sessionHex, maintenantMs)
    }

    companion object {
        /**
         * Le jeu de regles et le masque lus par defaut.
         *
         * Ils ne sont pas encore reglables depuis l'interface — l'ecran Reglages les affiche, un
         * selecteur reste a brancher. Les valeurs correspondent a `SeriesRule.AASM_V3` et a
         * `MaskSource.HEALTH_CONNECT` de `:algo` ; le jour ou le selecteur existera, c'est ici
         * qu'il se branchera, et nulle part ailleurs.
         */
        const val REGLE_PAR_DEFAUT = "AASM_V3"
        const val MASQUE_PAR_DEFAUT = "HEALTH_CONNECT"

        /**
         * Les motifs de rejet de `ClmRejectReason`, cote `:algo`, tels qu'ils sont persistes.
         *
         * Ils sont comptes et affiches **a cote** des mouvements retenus, pas caches : ce sont
         * eux qui disent pourquoi le chiffre est ce qu'il est. Quatorze mouvements ecartes pour
         * posture changent la lecture d'une nuit.
         */
        const val REJET_POSTURE = "POSTURAL"
        val REJETS_DUREE = setOf("TOO_SHORT", "LM_LONG", "TRUNCATED")
    }
}

/**
 * Une lecture coherente de l'etat de la tendance. `rythme` et `compte` sont `null` sous trois
 * nuits eligibles — et c'est le type qui porte la regle, pas une convention de lecture.
 */
data class EtatTendance(
    val nuits: List<NuitUi>,
    val nuitsAgregeables: List<ComparableNight>,
    /**
     * Combien de nuits agregeables portent un ajustement de rythme accepte. C'est le
     * denominateur du refus : sous [Aggregat.MIN_NUITS_AGREGAT], l'ecran doit dire **lequel**
     * des deux comptes manque, faute de quoi il annonce « pas assez de nuits » a quelqu'un qui
     * en a neuf.
     */
    val nuitsRythmeAjuste: Int,
    val rythme: Aggregat.Resultat?,
    val compte: Aggregat.Resultat?,
    val tauxManquesMedian: Double,
    val periodiciteMediane: Double,
    val profilPersonnalise: String?,
    val hashsMelanges: Boolean,
    val faitsReveil: MachineReveil.Faits? = null,
    val originesDerniereNuit: Int = 0,
    /** Fuseau dans lequel les nuits tracees ont ete vecues. Voir `TendanceChartSpec.zoneId`. */
    val zoneId: String = java.time.ZoneId.systemDefault().id,
) {
    val nuitsEligibles: Int get() = nuitsAgregeables.size
    val nuitsEnregistrees: Int get() = nuits.size
    val nuitsEcartees: Int get() = nuits.count { it.etat == com.pendulum.phone.ui.model.EtatNuit.ECARTEE }
}
