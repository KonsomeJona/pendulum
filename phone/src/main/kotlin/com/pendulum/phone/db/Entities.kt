package com.pendulum.phone.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pendulum.algo.model.PlmiResult

/**
 * Schema Room de `:phone`.
 *
 * ### Le principe qui gouverne toutes les tables ci-dessous
 *
 * **Le brut est la seule chose irremplaçable.** Un resultat, un masque, un evenement CLM :
 * tout cela se recalcule a partir des chunks. Les chunks, eux, ne se recalculent pas. Le schema
 * separe donc strictement ce qui est *recu* (`night_session`, `chunk`, `telemetry_point`,
 * `night_context`, `hc_snapshot`, `questionnaire_response`) de ce qui est *derive*
 * (`sleep_window`, `clm_event`, `plm_result`). Tout le derive porte un `paramsHash` et peut etre
 * efface et reconstruit ; rien du recu ne le peut.
 *
 * Consequence directe sur les migrations : voir la KDoc de [PendulumDatabase], qui porte les deux
 * regles a tenir — une migration ne perd jamais une colonne du brut, et on ne recree une vue que
 * si on la change. `fallbackToDestructiveMigration` est interdit, parce qu'il detruirait justement
 * la moitie qui ne se reconstruit pas.
 *
 * La base est en **v1** et ne porte aucune migration : les quatre qui existaient ont ete retirees
 * le 7 aout 2026, l'application n'ayant jamais ete installee nulle part. Le detail de ce qu'elles
 * faisaient est conserve dans la KDoc de [PendulumDatabase].
 */

/**
 * Une nuit. Cle primaire = `sessionHex`, l'identifiant que la montre a choisi et qui apparait
 * dans les chemins `DataItem` — aucune cle auto-generee ici : c'est ce qui rend l'ingestion
 * idempotente sans table de correspondance.
 *
 * @param state etat vu du telephone. `OPEN` -> `STALE` -> `TRUNCATED` est pilote par
 *   `WatchdogWorker` ; `CLOSED` vient de la montre.
 * @param tzOffsetStartMin,tzOffsetEndMin offsets UTC locaux au debut et a la fin. Les stocker
 *   **tous les deux** est ce qui permet de detecter une nuit de changement d'heure : c'est
 *   exactement `tzOffsetStartMin <> tzOffsetEndMin`, et cette nuit-la ne doit pas entrer dans
 *   une tendance (garde-fou 4 de `SPEC-v2.md` §3). Aucune duree n'est jamais calculee a partir
 *   d'eux : les durees viennent de `SensorEvent.timestamp`.
 * @param analysableMin minutes reellement analysables (segments valides, hors zones aveugles,
 *   hors off-body). C'est le critere « au moins 4 h analysables » de la vue [ComparableNight],
 *   et il est ecrit par l'analyse, pas par l'ingestion.
 * @param gainCalG etalon de gain de la nuit. Sert au critere de comparabilite : un bracelet
 *   resserre differemment change l'amplitude d'un facteur 2 a 3 et rend la nuit incomparable
 *   sans que rien d'autre ne le signale.
 * @param truncated la nuit s'est arretee sans fermeture propre. Reste analysable, mais son
 *   index est biaise a la hausse de facon non corrigeable : hors tendance.
 */
@Entity(
    tableName = "night_session",
    indices = [Index("startWallMs"), Index("nightKey")],
)
data class NightSessionEntity(
    @PrimaryKey val sessionHex: String,
    /**
     * La soiree a laquelle cette nuit se rattache, `AAAA-MM-JJ`, bascule a midi
     * (`WirePaths.nightKey`). C'est par elle que la nuit retrouve le contexte scelle avant
     * qu'elle n'existe — le rattachement ne peut pas se faire par `sessionHex`, qui n'est connu
     * qu'au moment ou la montre annonce la session, c'est-a-dire apres le scellement.
     *
     * Indexee : c'est la jointure de la vue `comparable_night`, donc elle est parcourue une fois
     * par nuit et par lecture de tendance.
     */
    val nightKey: String = "",
    val startWallMs: Long,
    val plannedStopWallMs: Long,
    val endWallMs: Long? = null,
    val zoneId: String,
    val tzOffsetStartMin: Int,
    val tzOffsetEndMin: Int,
    val nominalRateHz: Int,
    val modeFlags: Int,
    val state: String,
    val stopReason: String? = null,
    val totalChunks: Int? = null,
    val lastChunkArrivalMs: Long = 0L,
    val batteryPctLast: Int? = null,

    // --- rempli par l'analyse ---
    val analyzedAtMs: Long? = null,
    val algoVersion: String? = null,
    val paramsHash: String? = null,
    val fsMeasuredHz: Double? = null,
    val sampleCount: Long = 0L,
    val gapCount: Int = 0,
    val gapTotalMs: Long = 0L,
    val analysableMin: Double = 0.0,
    val gainCalG: Double? = null,
    val gainSource: String? = null,
    val truncated: Boolean = false,
    val integrityRejectedFraction: Double = 0.0,

    /**
     * Le resultat a-t-il ete devoile ? Garde-fou 2 : au reveil l'ecran dit « nuit enregistree,
     * qualite OK » et rien d'autre ; le devoilement est **journalise**, ici, et exporte. Ce
     * champ appartient a la base et non a une preference, parce qu'il doit survivre a un
     * effacement de cache et partir dans l'export.
     */
    val revealedAtMs: Long? = null,
)

/**
 * Un chunk recu. `UNIQUE(sessionHex, idx)` est le pivot de tout le protocole : l'ingestion fait
 * un `INSERT OR IGNORE` dessus, donc recevoir deux fois le meme chunk est un no-op silencieux
 * et **toutes** les voies de reemission (ack perdu, sweep apres coupure Bluetooth, reprise
 * apres reboot du telephone) sont sures sans compteur ni etat.
 *
 * @param crc32 CRC-32 recalcule sur les octets **recus**, et compare a celui annonce par la
 *   montre avant toute insertion. Le CRC-16 par bloc couvre le contenu ; celui-ci couvre le
 *   transport, et rien d'autre ne le couvre.
 * @param complete le marqueur de fin de fichier a ete lu et verifie. **Un chunk non complet
 *   n'est jamais acquitte** : l'acquitter ferait supprimer par la montre un fichier partiel.
 */
@Entity(
    tableName = "chunk",
    indices = [
        Index(value = ["sessionHex", "idx"], unique = true),
        Index("sessionHex"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val idx: Int,
    val path: String,
    val size: Int,
    val crc32: Long,
    val sampleCount: Int,
    val tFirstNs: Long,
    val tLastNs: Long,
    val flagsOr: Int,
    val complete: Boolean,
    val receivedAtMs: Long,
)

/**
 * L'etat de l'appareil a un instant de la nuit — un point par minute, cinq par chunk complet.
 *
 * ### Ce n'est pas une table derivee
 *
 * Elle est rangee ici avec `chunk` et `night_context`, et pas avec `sleep_window` ou `clm_event`,
 * parce qu'elle en partage la propriete qui gouverne tout ce fichier : **elle ne se reconstitue
 * pas**. Les points vivent dans les blocs `TLM!` des chunks, et les chunks sont effaces de la
 * montre des qu'ils sont acquittes ; une nuit dont la telemetrie serait perdue en base garderait
 * son signal mais plus aucune trace de la batterie, de la gigue ou de l'ecretage qui ont decide
 * de sa lecture. Consequence directe : aucune migration ne la supprime, et le rescore ne la
 * touche pas — elle ne porte pas de `paramsHash`, parce qu'aucun parametre ne la produit.
 *
 * Techniquement le brut serait relisable : les fichiers de chunks restent sur le telephone. Mais
 * relire sept fichiers de 90 Ko a chaque ouverture d'ecran pour retrouver quarante points est
 * exactement le calcul que `detailDeNuit` refuse deja de faire pour l'enveloppe. La table est
 * l'extraction, faite une fois, a l'ingestion.
 *
 * ### La cle d'idempotence n'est pas `sensorTsNs`, et c'est mesure et non suppose
 *
 * Un chunk peut arriver deux fois — accuse perdu, balayage apres coupure, redemarrage du
 * telephone — et l'ingestion doit alors etre un no-op silencieux, exactement comme pour `chunk`.
 * Il faut donc une cle unique par point.
 *
 * `sensorTsNs` ne peut pas la porter : il vaut **0 tant qu'aucun echantillon n'a ete vu**
 * (`TelemetryPoint.sensorTsNs`), c'est-a-dire pour les premiers points d'une session, et deux
 * points a zero se confondraient. C'est `elapsedRealtimeNs` qui est unique : une horloge monotone
 * lue une fois par minute, jamais deux fois la meme valeur dans une session. L'unicite est donc
 * `(sessionHex, elapsedRealtimeNs)`.
 *
 * `(sessionHex, sensorTsNs)` reste **indexe**, parce que c'est l'ordre de lecture : la bande de
 * metrologie place chaque point sur la base de temps des echantillons, la seule qui date les
 * mouvements, et elle lit la nuit dans cet ordre.
 *
 * @param sessionHex la nuit. `CASCADE` : effacer une nuit efface sa telemetrie, comme ses chunks.
 * @param charging un point sous charge doit **sortir** de toute regression de pente de batterie.
 *   Il est conserve — c'est un fait de la nuit — mais [com.pendulum.phone.ui.model.PenteBatterie]
 *   le retire avant de calculer quoi que ce soit.
 *
 * Les autres champs sont ceux de [com.pendulum.format.TelemetryPoint], sans renommage et sans
 * conversion d'unite : la table est une projection du bloc `TLM!`, et toute unite convertie ici
 * serait une seconde convention a tenir. Ce que chacun explique est documente une seule fois,
 * dans la KDoc du format.
 */
@Entity(
    tableName = "telemetry_point",
    indices = [
        Index(value = ["sessionHex", "elapsedRealtimeNs"], unique = true),
        Index(value = ["sessionHex", "sensorTsNs"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TelemetryPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val elapsedRealtimeNs: Long,
    val sensorTsNs: Long,
    val batteryChargeUah: Int,
    val maxIntervalUs: Long,
    val fsyncTotalUs: Long,
    val fsyncMaxUs: Long,
    val temperatureDeciC: Int,
    val measuredRateCentiHz: Int,
    val jitterStdUs: Int,
    val clippedSamples: Int,
    val fsyncCount: Int,
    val batteryPct: Int,
    val offBody: Int,
    val charging: Boolean,
)

/**
 * Une fenetre de sommeil, quelle que soit sa provenance. Derive : efface et reconstruit a
 * chaque rescore.
 *
 * @param source `ACCEL_IMMOBILITY | HEALTH_CONNECT | DIARY | FUSED`, valeurs de
 *   `com.pendulum.algo.model.MaskSource`.
 * @param sourcePackage `dataOrigin.packageName` quand la source est Health Connect. **Sans lui,
 *   une nuit anormale est indebogable** : on ne sait meme pas quelle application a ecrit
 *   l'hypnogramme qu'on a utilise.
 * @param startMsRel,endMsRel millisecondes **relatives au debut de la session**, jamais des
 *   horloges murales : une resynchronisation NTP en pleine nuit, ou un changement d'heure,
 *   decalerait tout le reste.
 */
@Entity(
    tableName = "sleep_window",
    indices = [Index(value = ["sessionHex", "source"])],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SleepWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val source: String,
    val stage: String,
    val startMsRel: Long,
    val endMsRel: Long,
    val sourcePackage: String? = null,
    val paramsHash: String,
)

/**
 * Un mouvement candidat. Derive.
 *
 * On stocke **aussi les rejetes** (avec leur motif) : le rapport de qualite en a besoin, et
 * surtout, comparer les rejets d'une nuit a l'autre est le seul moyen de voir qu'un reglage a
 * change le comportement du detecteur plutot que le sommeil du dormeur.
 */
@Entity(
    tableName = "clm_event",
    indices = [Index(value = ["sessionHex", "paramsHash"])],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ClmEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val paramsHash: String,
    val onsetMsRel: Long,
    val durationMs: Int,
    val peakAmpG: Double,
    val medianAmpG: Double,
    val noiseFloorG: Double,
    val thresholdOnG: Double,
    val tiltChangeDeg: Double,
    val flags: Int,
    val rejectReason: String? = null,
    val inSeriesAasm: Boolean = false,
    val inSeriesWasm: Boolean = false,
    val stageAtOnset: String? = null,
    val duringWake: Boolean = false,
)

/**
 * Un resultat. **Quatre lignes par nuit et par `paramsHash`** : 2 jeux de regles x 2 masques.
 * Les quatre existent pour rendre l'ecart *visible* plutot que de choisir en silence — c'est
 * l'ecart entre masque accelerometrique et masque Health Connect qui dit a quel point on peut
 * faire confiance au premier.
 *
 * @param paramsHash le hash des parametres qui ont produit ce chiffre. `UNIQUE(sessionHex,
 *   paramsHash, rule, maskSource)` : deux hashs coexistent dans la table, jamais dans une
 *   tendance — voir [ComparableNight] et `TrendDao`.
 * @param gate ce que l'analyse **autorise** a publier (`FULL | TRUNCATED_NO_TREND | NO_PLMI`).
 *   Evalue par le code, jamais contournable depuis l'interface.
 * @param fundamentalSec la metrique de suivi (`SPEC-v2.md` §5) : rythme fondamental en
 *   secondes, sans denominateur, donc sans circularite, et douze fois plus stable d'une nuit a
 *   l'autre que le compte horaire.
 * @param missRate taux de manques estime. **A lire a cote de `fundamentalSec` et jamais sans
 *   lui** : un taux qui saute d'une nuit a l'autre signale deux nuits non comparables, et un
 *   taux proche de 0,5 avec un pic fondamental faible evoque une alternance gauche/droite.
 */
@Entity(
    tableName = "plm_result",
    indices = [
        Index(value = ["sessionHex", "paramsHash", "rule", "maskSource"], unique = true),
        Index(value = ["paramsHash"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlmResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val paramsHash: String,
    val rule: String,
    val maskSource: String,
    val computedAtMs: Long,
    val algoVersion: String,

    val plmsCount: Int,
    val plmwCount: Int,
    val isolatedCount: Int,
    val shortImiCount: Int,

    val tstMin: Double,
    val analysableTstMin: Double,
    val sptMin: Double,
    val wasoMin: Double,

    /**
     * Les six taux, **nullables**, et `null` veut dire « cette nuit n'en porte pas ».
     *
     * `safeRate` rend `NaN` des que le denominateur n'existe pas, et c'est la bonne decision — un
     * taux sans denominateur n'est pas zero. Mais SQLite ne connait pas `NaN` : la liaison le
     * convertit en `NULL`, et une colonne `NOT NULL` refusait alors l'insertion
     * (`SQLiteConstraintException: NOT NULL constraint failed: plm_result.plmi`). Comme
     * `AnalyzeWorker` rend `retry()` sur exception, la nuit etait reessayee sans fin et
     * n'apparaissait **nulle part** : c'est le chemin par defaut de tout utilisateur sans
     * hypnogramme Health Connect, pas un cas limite.
     *
     * Ecrire `0.0` a la place aurait ete pire que le defaut : « 0 mouvement par heure » est une
     * mesure, et l'annoncer pour une nuit ou rien n'a pu etre mesure est un mensonge clinique.
     * `null` se propage jusqu'au tiret de l'ecran ([com.pendulum.phone.ui.model.Mapping.TIRET]),
     * qui dit exactement ce qui s'est passe.
     *
     * La conversion se fait en un seul endroit : [PlmResultEntity.depuis].
     */
    val plmi: Double?,
    val plmiSpt: Double?,
    val plmw: Double?,
    val plmiFirstHalf: Double?,
    val plmiSecondHalf: Double?,
    val plmiRespWorstCase: Double?,

    /** Jamais `NaN` : `Periodicity.fromIntervals` rend `0.0` sans intervalle, et porte son `valid`. */
    val periodicityIndex: Double,
    val periodicityValid: Boolean,

    /**
     * Les quatre sorties de la deconvolution, nullables pour la meme raison que les taux
     * ci-dessus — `Rhythm.emptyFit` les met toutes a `NaN` quand l'ajustement est refuse.
     *
     * **Le refus est le cas frequent**, pas l'exception : `RhythmMeasurementTest` mesure 2
     * ajustements acceptes sur 20 nuits nominales. La colonne `NOT NULL` faisait donc echouer
     * l'insertion de la plupart des nuits, y compris celles dont le PLMI, lui, existait.
     */
    val fundamentalSec: Double?,
    val muLog: Double?,
    val sigmaLog: Double?,
    val missRate: Double?,
    val alternationSuspect: Boolean,
    val rhythmConverged: Boolean,
    val rhythmValid: Boolean,

    val truncatedSeriesDropped: Int,
    val independence: String,
    val gate: String,
    val floorMode: String,
) {
    companion object {

        /**
         * L'unique fabrique d'une ligne de resultat, et **l'unique endroit ou un `NaN` devient un
         * `NULL`**.
         *
         * Elle existe parce que la conversion doit vivre a la frontiere de persistance et nulle
         * part ailleurs. Deux appelants construisent cette ligne — l'analyse reelle et
         * l'ensemencement du banc — et une conversion recopiee chez chacun d'eux est une
         * conversion dont l'un des deux exemplaires finira par oublier un champ. Le champ oublie
         * ne se verrait pas : il ferait echouer l'insertion, `AnalyzeWorker` rendrait `retry()`,
         * et la nuit disparaitrait en silence. C'est precisement le defaut qu'on repare.
         */
        fun depuis(
            sessionHex: String,
            paramsHash: String,
            computedAtMs: Long,
            algoVersion: String,
            r: PlmiResult,
        ): PlmResultEntity = PlmResultEntity(
            sessionHex = sessionHex,
            paramsHash = paramsHash,
            rule = r.rule.name,
            maskSource = r.maskSource.name,
            computedAtMs = computedAtMs,
            algoVersion = algoVersion,
            plmsCount = r.plmsCount,
            plmwCount = r.plmwCount,
            isolatedCount = r.isolatedCount,
            shortImiCount = r.shortImiCount,
            tstMin = r.tstMin,
            analysableTstMin = r.analysableTstMin,
            sptMin = r.sptMin,
            wasoMin = r.wasoMin,
            plmi = r.plmi.siDefinie(),
            plmiSpt = r.plmiSpt.siDefinie(),
            plmw = r.plmw.siDefinie(),
            plmiFirstHalf = r.plmiFirstHalf.siDefinie(),
            plmiSecondHalf = r.plmiSecondHalf.siDefinie(),
            plmiRespWorstCase = r.plmiRespWorstCase.siDefinie(),
            periodicityIndex = r.pi.periodicityIndex,
            periodicityValid = r.pi.valid,
            fundamentalSec = r.rhythm.fundamentalSec.siDefinie(),
            muLog = r.rhythm.muLog.siDefinie(),
            sigmaLog = r.rhythm.sigmaLog.siDefinie(),
            missRate = r.rhythm.missRate.siDefinie(),
            alternationSuspect = r.rhythm.alternationSuspect,
            rhythmConverged = r.rhythm.converged,
            rhythmValid = r.rhythm.valid,
            truncatedSeriesDropped = r.truncatedSeriesDropped,
            independence = r.independence.name,
            gate = r.gate.name,
            floorMode = r.floorMode.name,
        )
    }
}

/**
 * La traduction de la convention de `:algo` vers celle de SQLite : `NaN` (et l'infini, que
 * `safeRate` peut produire si le denominateur devient infinitesimal) veut dire « pas de valeur »,
 * et « pas de valeur » s'ecrit `NULL`.
 *
 * `night_session.gainCalG` appliquait deja cette regle a la main ; elle porte desormais un nom.
 */
internal fun Double.siDefinie(): Double? = takeIf { it.isFinite() }

/**
 * Le contexte du soir : dose, jambe portante, bracelet, seul dans le lit, cafe, alcool.
 *
 * **Table append-only, et l'interdiction est structurelle** : deux declencheurs SQLite
 * (`night_context_no_update`, `night_context_no_delete`, poses par [PendulumDatabase]) font echouer
 * tout `UPDATE` et tout `DELETE`. Le DAO n'expose ni `@Update` ni `@Delete`, mais un DAO se
 * modifie ; un declencheur, non — c'est ce qui fait la difference entre une convention et une
 * garantie.
 *
 * **Pourquoi ce n'est pas de la paranoia** : le mode de defaillance de ce projet n'est pas la
 * fraude, c'est la retouche de bonne foi. Se souvenir le lendemain matin, apres avoir vu un
 * chiffre elevé, qu'« en fait j'avais pris la dose plus tard » et corriger, suffit a fabriquer
 * la correlation qu'on cherchait. Le scellement doit precer la mesure : la montre refuse de
 * demarrer tant que cette ligne n'est pas ecrite.
 *
 * ### Pourquoi la cle est la nuit et non la session
 *
 * Le scellement **precede** la nuit : au moment ou l'utilisateur remplit le formulaire, aucune
 * session n'existe, et il n'y a donc pas de `sessionHex` a poser. La v1 exigeait pourtant cette
 * colonne en cle primaire, ce qui rendait le scellement litteralement impossible — et comme les
 * declencheurs interdisent tout `UPDATE`, on ne pouvait pas non plus la renseigner apres coup.
 *
 * La cle est donc la **cle de nuit** de `WirePaths.nightKey` — date locale de la soiree, bascule
 * a midi — c'est-a-dire exactement la chaine que porte le chemin du `DataItem` publie vers la
 * montre. Une seule convention, partagee par la base et par le protocole : un decalage entre les
 * deux serait indiscernable d'une absence de contexte, et la montre refuserait de demarrer sans
 * rien pouvoir expliquer.
 *
 * @param nightKey `AAAA-MM-JJ` de la soiree. Un coucher a 1 h 30 se rattache a la veille.
 * @param sealedAtMs instant du scellement. Doit etre **anterieur** a `night_session.startWallMs`.
 * @param leg `LEFT | RIGHT`. Critere de comparabilite : un capteur unilateral voit un intervalle
 *   double en cas d'alternance, changer de jambe change la mesure.
 * @param strapId identifiant du bracelet utilise. Idem : le SPEC mesure des amplitudes x2 a x3
 *   selon le jeu du bracelet.
 * @param aloneInBed un partenaire de lit transmet ses propres mouvements par le matelas.
 */
@Entity(tableName = "night_context")
data class NightContextEntity(
    @PrimaryKey val nightKey: String,
    val sealedAtMs: Long,
    val leg: String,
    val strapId: String,
    val aloneInBed: Boolean,
    val bedTimeLocalMs: Long? = null,
    val riseTimeLocalMs: Long? = null,
    val medicationJson: String,
    val caffeineAfter16h: Boolean = false,
    val alcoholUnits: Double = 0.0,
    val unusualExercise: Boolean = false,
    val notes: String? = null,
)

/**
 * Instantane de ce que Health Connect a **effectivement renvoye**, avant toute interpretation.
 *
 * Il existe pour une raison precise et verifiee : certains fournisseurs ne se contentent pas
 * d'inserer, ils **reecrivent** une session deja publiee (« inserts or *updates* », FAQ
 * developpeur Samsung). Une nuit lue a T+1 h peut donc differer de la meme nuit a T+8 h. Sans
 * cet instantane, un chiffre qui change entre deux consultations est inexplicable ; avec lui,
 * la comparaison de deux lignes le montre en une requete.
 *
 * Append-only par usage : chaque lecture ajoute une ligne, aucune n'en modifie une.
 *
 * @param lastModifiedTimeMs `metadata.lastModifiedTime` tel que renvoye par Health Connect.
 *   C'est le champ qui trahit la reecriture.
 * @param recordsJson serialisation brute des sessions retenues **et** ecartees, avec leur
 *   origine. Volontairement redondant avec `sleep_window` : `sleep_window` est derive et efface
 *   au rescore, celui-ci ne l'est jamais.
 */
@Entity(
    tableName = "hc_snapshot",
    indices = [Index(value = ["sessionHex", "fetchedAtMs"])],
    foreignKeys = [
        ForeignKey(
            entity = NightSessionEntity::class,
            parentColumns = ["sessionHex"],
            childColumns = ["sessionHex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HcSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String,
    val fetchedAtMs: Long,
    val attemptIndex: Int,
    val selectedPackage: String? = null,
    val selectedRecordId: String? = null,
    val lastModifiedTimeMs: Long? = null,
    val sessionStartMs: Long? = null,
    val sessionEndMs: Long? = null,
    val stageCount: Int = 0,
    val distinctStageTypes: Int = 0,
    val stageCoverageMin: Double = 0.0,
    val overlapFraction: Double = 0.0,
    /** TST rendu par `aggregate(SLEEP_DURATION_TOTAL)`, dedoublonne par le systeme. */
    val aggregateTstMin: Double? = null,
    val originCount: Int = 0,

    /**
     * L'hypnogramme retenu, encode `debutMs:finMs:type;...` en horloge murale UTC.
     *
     * Un CSV et non du JSON, pour une raison qui n'est pas l'economie : **c'est cette colonne
     * qui est relue** a chaque rescore, et un format qui se relit en dix lignes sans
     * bibliotheque ne peut pas se mettre a echouer differemment selon la version d'un parseur.
     * `recordsJson`, lui, est une trace destinee a l'oeil humain et n'est jamais reparse — s'il
     * l'etait, il faudrait le versionner.
     */
    val selectedStagesCsv: String = "",

    val recordsJson: String,
    val outcome: String,
)

/** Une reponse de questionnaire. Append-only par usage : on ajoute une passation, on ne corrige pas. */
@Entity(
    tableName = "questionnaire_response",
    indices = [Index(value = ["sessionHex"]), Index(value = ["kind", "answeredAtMs"])],
)
data class QuestionnaireResponseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionHex: String? = null,
    val kind: String,
    val answeredAtMs: Long,
    val answersJson: String,
    val score: Double? = null,
)

/**
 * Un jeu de parametres d'algorithme, identifie par son hash.
 *
 * Garde-fou 3 : **aucun reglage par nuit**. Un changement de parametre est global, cree une
 * nouvelle ligne ici, et declenche `RescoreAllWorker` qui recalcule *toutes* les nuits depuis
 * le brut. La tendance refuse ensuite de melanger deux hashs — ce n'est pas une politique
 * d'affichage, c'est un `WHERE paramsHash = :hash` dont il n'existe aucune variante sans filtre
 * dans `TrendDao`.
 *
 * @param active un seul profil actif a la fois. L'unicite est tenue par [ParamDao.activate],
 *   qui desactive tout dans la meme transaction.
 */
@Entity(tableName = "param_profile")
data class ParamProfileEntity(
    @PrimaryKey val paramsHash: String,
    val createdAtMs: Long,
    val algoVersion: String,
    val paramsJson: String,
    val active: Boolean,
    val label: String? = null,
)
