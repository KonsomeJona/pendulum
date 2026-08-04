package com.pendulum.phone.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NightDao {

    /**
     * `IGNORE` et non `REPLACE` : l'item `/pendulum/session` est **repose** par la montre a chaque
     * changement d'etat, et un `REPLACE` ecraserait au passage tout ce que l'analyse a ecrit
     * dans la ligne (gain, minutes analysables, hash). Les mises a jour passent par les methodes
     * ciblees ci-dessous, qui ne touchent que leurs colonnes.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: NightSessionEntity): Long

    @Update
    suspend fun update(session: NightSessionEntity)

    @Query("SELECT * FROM night_session WHERE sessionHex = :hex")
    suspend fun find(hex: String): NightSessionEntity?

    /**
     * La nuit enregistree sous une soiree donnee, s'il y en a une.
     *
     * Une soiree peut avoir un contexte scelle et aucune nuit — c'est le cas de tout formulaire
     * rempli avant que la montre ne demarre, donc de **toutes** les soirees entre 20 h et le
     * coucher. `null` est ici un etat normal et non une anomalie.
     */
    @Query("SELECT * FROM night_session WHERE nightKey = :nightKey LIMIT 1")
    suspend fun findByNightKey(nightKey: String): NightSessionEntity?

    @Query("SELECT * FROM night_session ORDER BY startWallMs DESC")
    fun observeAll(): Flow<List<NightSessionEntity>>

    /**
     * Les nuits en une lecture ponctuelle, la plus recente d'abord.
     *
     * Le rapport de la porte P1 et son export CSV en ont besoin sans flux : ce sont des
     * instantanes, produits une fois a l'ouverture de l'ecran ou a l'ecriture du fichier, et un
     * flux ferait recomposer un rapport pendant qu'on le lit.
     */
    @Query("SELECT * FROM night_session ORDER BY startWallMs DESC")
    suspend fun all(): List<NightSessionEntity>

    @Query("SELECT * FROM night_session WHERE state = 'OPEN' OR state = 'STALE'")
    suspend fun openOrStale(): List<NightSessionEntity>

    @Query("SELECT sessionHex FROM night_session ORDER BY startWallMs ASC")
    suspend fun allHexOldestFirst(): List<String>

    /**
     * Les nuits fermees depuis moins de 36 h : exactement celles dont l'hypnogramme peut encore
     * arriver. C'est l'ensemble que le declencheur opportuniste balaie quand le telephone est
     * branche ou que l'application revient au premier plan.
     */
    @Query(
        """
        SELECT * FROM night_session
        WHERE endWallMs IS NOT NULL AND endWallMs >= :depuisMs
        ORDER BY startWallMs DESC
        """
    )
    suspend fun endedSince(depuisMs: Long): List<NightSessionEntity>

    /**
     * Fermeture annoncee par la montre. `endWallMs` et `stopReason` ne s'ecrivent qu'ici : une
     * fin devinee est un bug, `UNKNOWN` est une reponse acceptable.
     */
    @Query(
        """
        UPDATE night_session
        SET state = :state, endWallMs = :endWallMs, totalChunks = :totalChunks,
            stopReason = :stopReason, tzOffsetEndMin = :tzOffsetEndMin
        WHERE sessionHex = :hex
        """
    )
    suspend fun markClosed(
        hex: String,
        state: String,
        endWallMs: Long?,
        totalChunks: Int?,
        stopReason: String?,
        tzOffsetEndMin: Int,
    )

    @Query("UPDATE night_session SET state = :state WHERE sessionHex = :hex")
    suspend fun setState(hex: String, state: String)

    @Query("UPDATE night_session SET lastChunkArrivalMs = :atMs WHERE sessionHex = :hex")
    suspend fun touchChunkArrival(hex: String, atMs: Long)

    @Query("UPDATE night_session SET batteryPctLast = :pct WHERE sessionHex = :hex")
    suspend fun setBattery(hex: String, pct: Int)

    /**
     * Le devoilement du resultat est **journalise** (garde-fou 2). `revealedAtMs` ne s'ecrit
     * qu'une fois : `WHERE revealedAtMs IS NULL` fait que rejouer le geste ne reecrit pas la
     * date, sans quoi la trace dirait « devoile ce matin » pour une nuit ouverte trois fois.
     */
    @Query("UPDATE night_session SET revealedAtMs = :atMs WHERE sessionHex = :hex AND revealedAtMs IS NULL")
    suspend fun markRevealed(hex: String, atMs: Long)

    /** Ecrit par l'analyse, et par elle seule. */
    @Query(
        """
        UPDATE night_session
        SET analyzedAtMs = :atMs, algoVersion = :algoVersion, paramsHash = :paramsHash,
            fsMeasuredHz = :fsHz, sampleCount = :sampleCount, gapCount = :gapCount,
            gapTotalMs = :gapTotalMs, analysableMin = :analysableMin, gainCalG = :gainCalG,
            gainSource = :gainSource, truncated = :truncated,
            integrityRejectedFraction = :rejectedFraction
        WHERE sessionHex = :hex
        """
    )
    suspend fun writeAnalysisSummary(
        hex: String,
        atMs: Long,
        algoVersion: String,
        paramsHash: String,
        fsHz: Double,
        sampleCount: Long,
        gapCount: Int,
        gapTotalMs: Long,
        analysableMin: Double,
        gainCalG: Double?,
        gainSource: String?,
        truncated: Boolean,
        rejectedFraction: Double,
    )
}

@Dao
interface ChunkDao {

    /**
     * **Le point d'appui de tout le protocole de transfert.** `IGNORE` sur
     * `UNIQUE(sessionHex, idx)` : recevoir deux fois le meme chunk est un no-op silencieux, donc
     * chaque voie de reemission (ack perdu, sweep apres coupure, redemarrage du telephone) est
     * sure sans compteur ni machine a etats.
     *
     * @return l'identifiant insere, ou -1 si la ligne existait deja.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(chunk: ChunkEntity): Long

    @Query("SELECT * FROM chunk WHERE sessionHex = :hex ORDER BY idx ASC")
    suspend fun ofSession(hex: String): List<ChunkEntity>

    /**
     * Les index **complets** d'une session, tries. L'accuse de reception se recalcule a partir
     * de cette requete a chaque fois, jamais a partir d'un compteur en memoire : un compteur
     * survit mal a un redemarrage du service, et un accuse trop optimiste fait supprimer par la
     * montre un fichier que le telephone n'a pas.
     */
    @Query("SELECT idx FROM chunk WHERE sessionHex = :hex AND complete = 1 ORDER BY idx ASC")
    suspend fun completeIndices(hex: String): List<Int>

    @Query("SELECT COUNT(*) FROM chunk WHERE sessionHex = :hex")
    suspend fun count(hex: String): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM chunk WHERE sessionHex = :hex")
    suspend fun totalBytes(hex: String): Long

    @Query("SELECT path FROM chunk")
    suspend fun allPaths(): List<String>

    @Query("SELECT * FROM chunk WHERE sessionHex = :hex AND idx = :idx")
    suspend fun find(hex: String, idx: Int): ChunkEntity?
}

/**
 * La telemetrie de nuit. **Ecrite par l'ingestion, jamais par l'analyse**, et jamais effacee par
 * un rescore : c'est du recu, pas du derive.
 *
 * Il n'existe volontairement ni `@Update` ni `@Delete`. Un point de telemetrie decrit l'etat d'un
 * appareil a un instant : il n'y a rien a y corriger, et le seul effacement legitime est celui qui
 * emporte la nuit entiere, porte par la cle etrangere en `CASCADE`.
 */
@Dao
interface TelemetryDao {

    /**
     * `IGNORE` sur `UNIQUE(sessionHex, elapsedRealtimeNs)`, exactement pour la meme raison que
     * `ChunkDao.insertIfAbsent` : un chunk reemis repasse ses points par ici, et re-inserer doit
     * etre un no-op silencieux plutot qu'un doublon. Un doublon ne leverait rien et fausserait
     * toutes les moyennes de la bande de metrologie.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(points: List<TelemetryPointEntity>)

    /**
     * Les points d'une nuit, dans l'ordre de la base de temps des **echantillons**. C'est celle
     * qui date les mouvements ; trier sur l'horloge monotone donnerait le meme ordre dans le cas
     * nominal et un ordre different exactement quand les deux horloges divergent, c'est-a-dire
     * dans le seul cas ou la question se pose.
     */
    @Query("SELECT * FROM telemetry_point WHERE sessionHex = :hex ORDER BY sensorTsNs ASC, elapsedRealtimeNs ASC")
    suspend fun ofSession(hex: String): List<TelemetryPointEntity>

    @Query("SELECT COUNT(*) FROM telemetry_point WHERE sessionHex = :hex")
    suspend fun count(hex: String): Int
}

/**
 * Tout le derive. Une seule regle : **on efface avant de reecrire, par `(nuit, paramsHash)`**.
 * Un rescore qui empilerait au lieu d'ecraser doublerait les evenements a chaque passage, et le
 * symptome (un index qui double) ressemblerait a une aggravation clinique.
 */
@Dao
abstract class DerivedDao {

    @Query("DELETE FROM sleep_window WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun clearWindows(hex: String, paramsHash: String)

    @Query("DELETE FROM clm_event WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun clearEvents(hex: String, paramsHash: String)

    @Query("DELETE FROM plm_result WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun clearResults(hex: String, paramsHash: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertWindows(rows: List<SleepWindowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEvents(rows: List<ClmEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertResults(rows: List<PlmResultEntity>)

    /**
     * Remplacement atomique. En cas d'interruption au milieu, la transaction est annulee et
     * l'ancienne analyse reste : mieux vaut un resultat perime qu'une nuit a moitie rescoree,
     * qui serait indiscernable d'une nuit sans mouvements.
     */
    @Transaction
    open suspend fun replaceAnalysis(
        hex: String,
        paramsHash: String,
        windows: List<SleepWindowEntity>,
        events: List<ClmEventEntity>,
        results: List<PlmResultEntity>,
    ) {
        clearWindows(hex, paramsHash)
        clearEvents(hex, paramsHash)
        clearResults(hex, paramsHash)
        insertWindows(windows)
        insertEvents(events)
        insertResults(results)
    }

    @Query("SELECT * FROM plm_result WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun resultsOf(hex: String, paramsHash: String): List<PlmResultEntity>

    @Query("SELECT * FROM plm_result WHERE sessionHex = :hex")
    abstract suspend fun allResultsOf(hex: String): List<PlmResultEntity>

    @Query("SELECT * FROM sleep_window WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    abstract suspend fun windowsOf(hex: String, paramsHash: String): List<SleepWindowEntity>

    @Query("SELECT * FROM clm_event WHERE sessionHex = :hex AND paramsHash = :paramsHash ORDER BY onsetMsRel")
    abstract suspend fun eventsOf(hex: String, paramsHash: String): List<ClmEventEntity>
}

/**
 * Le contexte du soir. **Ce DAO n'a volontairement ni `@Update` ni `@Delete`** — et si quelqu'un
 * en ajoutait un, les declencheurs SQLite poses par [PendulumDatabase] le feraient echouer a
 * l'execution. La convention et la garantie sont toutes les deux la, dans cet ordre.
 */
@Dao
interface ContextDao {

    /** `ABORT` : reecrire un contexte deja scelle doit lever, pas ecraser en silence. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun seal(context: NightContextEntity)

    @Query("SELECT * FROM night_context WHERE nightKey = :nightKey")
    suspend fun find(nightKey: String): NightContextEntity?

    /**
     * Le contexte d'une nuit deja enregistree, retrouve par sa session.
     *
     * Le rattachement passe par `night_session.nightKey` et non par une egalite de cle : la
     * session connait la soiree a laquelle elle appartient, le contexte a ete scelle sous cette
     * meme soiree, et c'est la seule jointure possible puisque le scellement precede la session
     * de plusieurs heures.
     */
    @Query(
        """
        SELECT c.* FROM night_context c
        JOIN night_session s ON s.nightKey = c.nightKey
        WHERE s.sessionHex = :sessionHex
        """
    )
    suspend fun findForSession(sessionHex: String): NightContextEntity?

    /**
     * Le contexte scelle est-il celui de la soiree en cours ? C'est la question que pose la carte
     * « Ce soir », et elle est distincte de [find] : ici on veut un flux, parce que le scellement
     * doit faire disparaitre le bouton sans qu'on ait a revenir sur l'ecran.
     */
    @Query("SELECT * FROM night_context WHERE nightKey = :nightKey")
    fun observe(nightKey: String): Flow<NightContextEntity?>

    @Query("SELECT * FROM night_context ORDER BY sealedAtMs ASC LIMIT 1")
    suspend fun reference(): NightContextEntity?

    @Query("SELECT * FROM night_context ORDER BY sealedAtMs DESC")
    suspend fun all(): List<NightContextEntity>
}

@Dao
interface HcSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(snapshot: HcSnapshotEntity): Long

    @Query("SELECT * FROM hc_snapshot WHERE sessionHex = :hex ORDER BY fetchedAtMs ASC")
    suspend fun ofSession(hex: String): List<HcSnapshotEntity>

    /**
     * Le nombre de tentatives **planifiees** deja faites : c'est lui qui pilote le repli, pas une
     * preference.
     *
     * `attemptIndex >= 0` exclut les lectures opportunistes (chargeur branche, retour au premier
     * plan), qui sont journalisees avec `FetchSchedule.INDEX_OPPORTUNISTE`. Les compter ferait
     * avancer l'echelle a chaque branchement : trois aller-retours de cable epuiseraient les sept
     * rangs en une minute, et l'application abandonnerait la nuit avant midi.
     */
    @Query("SELECT COUNT(*) FROM hc_snapshot WHERE sessionHex = :hex AND attemptIndex >= 0")
    suspend fun attemptCount(hex: String): Int

    @Query("SELECT * FROM hc_snapshot WHERE sessionHex = :hex ORDER BY fetchedAtMs DESC LIMIT 1")
    suspend fun latest(hex: String): HcSnapshotEntity?
}

@Dao
interface QuestionnaireDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(response: QuestionnaireResponseEntity): Long

    @Query("SELECT * FROM questionnaire_response WHERE sessionHex = :hex ORDER BY answeredAtMs")
    suspend fun ofSession(hex: String): List<QuestionnaireResponseEntity>

    @Query("SELECT * FROM questionnaire_response ORDER BY answeredAtMs DESC")
    suspend fun all(): List<QuestionnaireResponseEntity>
}

@Dao
abstract class ParamDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(profile: ParamProfileEntity)

    @Query("SELECT * FROM param_profile WHERE active = 1 LIMIT 1")
    abstract suspend fun active(): ParamProfileEntity?

    @Query("SELECT * FROM param_profile WHERE active = 1 LIMIT 1")
    abstract fun observeActive(): Flow<ParamProfileEntity?>

    @Query("SELECT * FROM param_profile ORDER BY createdAtMs DESC")
    abstract suspend fun all(): List<ParamProfileEntity>

    @Query("UPDATE param_profile SET active = 0")
    abstract suspend fun deactivateAll()

    @Query("UPDATE param_profile SET active = 1 WHERE paramsHash = :hash")
    abstract suspend fun setActive(hash: String)

    /**
     * Un seul profil actif, garanti par la transaction. C'est ce qui rend le
     * `WHERE paramsHash = :hash` de [TrendDao] suffisant : il n'existe jamais deux hashs
     * « courants » entre lesquels une requete pourrait choisir au hasard.
     */
    @Transaction
    open suspend fun activate(profile: ParamProfileEntity) {
        insertIfAbsent(profile)
        deactivateAll()
        setActive(profile.paramsHash)
    }
}

/**
 * Les tendances. **Toutes** passent par `comparable_night`, et toutes exigent un `paramsHash`.
 *
 * Il n'existe deliberement aucune surcharge sans le parametre `paramsHash` : c'est l'application
 * du garde-fou 3 (« la tendance refuse de melanger deux hashs ») au niveau du type, la ou une
 * regle ecrite dans un document se contourne par distraction. Melanger deux hashs signifierait
 * tracer sur un meme graphe des chiffres produits par deux algorithmes differents, et lire le
 * saut entre les deux comme un changement clinique.
 *
 * `gate` est filtre a part : une nuit peut etre parfaitement comparable et n'avoir aucun droit
 * de porter un chiffre (masque non convergent, sommeil analysable insuffisant, confiance
 * respiratoire basse). Comparabilite et publiabilite sont deux questions distinctes.
 */
@Dao
interface TrendDao {

    @Query(
        """
        SELECT * FROM comparable_night
        WHERE paramsHash = :paramsHash AND rule = :rule AND maskSource = :maskSource
        ORDER BY startWallMs ASC
        """
    )
    suspend fun allNights(paramsHash: String, rule: String, maskSource: String): List<ComparableNight>

    /**
     * Les points qui ont le droit d'entrer dans une courbe. `comparable = 1` **et**
     * `gate = 'FULL'`.
     */
    @Query(
        """
        SELECT * FROM comparable_night
        WHERE paramsHash = :paramsHash AND rule = :rule AND maskSource = :maskSource
          AND comparable = 1 AND gate = 'FULL'
        ORDER BY startWallMs ASC
        """
    )
    suspend fun trendPoints(paramsHash: String, rule: String, maskSource: String): List<ComparableNight>

    @Query(
        """
        SELECT * FROM comparable_night
        WHERE paramsHash = :paramsHash AND rule = :rule AND maskSource = :maskSource
          AND comparable = 1 AND gate = 'FULL'
        ORDER BY startWallMs ASC
        """
    )
    fun observeTrendPoints(
        paramsHash: String,
        rule: String,
        maskSource: String,
    ): Flow<List<ComparableNight>>

    @Query("SELECT * FROM comparable_night WHERE sessionHex = :hex AND paramsHash = :paramsHash")
    suspend fun forNight(hex: String, paramsHash: String): List<ComparableNight>

    /**
     * Les hashs presents en base. Si cette liste a plus d'un element apres un changement de
     * parametre, c'est que `RescoreAllWorker` n'est pas alle au bout : l'interface doit le dire
     * plutot que d'afficher une tendance amputee.
     */
    @Query("SELECT DISTINCT paramsHash FROM plm_result")
    suspend fun distinctHashes(): List<String>
}

/**
 * L'effacement total. Il efface **aussi les chunks bruts** — c'est tout l'objet du bouton.
 *
 * Room ne supprime que ce qu'il connait ; les fichiers de chunks vivent sur le disque et sont
 * effaces par `ChunkStore.deleteAll()`. L'ordre importe : fichiers d'abord, base ensuite. Si
 * l'operation est interrompue entre les deux, il reste une base qui pointe vers des fichiers
 * disparus — etat detectable et reparable. L'ordre inverse laisserait des fichiers orphelins
 * dont plus rien ne connait l'existence, c'est-a-dire des donnees de sante que l'utilisateur
 * croit avoir effacees.
 */
@Dao
interface MaintenanceDao {

    @Query("DELETE FROM night_session")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM questionnaire_response")
    suspend fun deleteAllQuestionnaires()

    @Query("DELETE FROM param_profile")
    suspend fun deleteAllProfiles()

    @Query("DELETE FROM night_context WHERE 1 = 1")
    suspend fun deleteAllContexts()
}
