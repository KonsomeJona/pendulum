package com.pendulum.phone.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.HcSnapshotEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.health.Hypnogram
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.ingest.ChunkStore
import com.pendulum.phone.ingest.SessionReassembler
import com.pendulum.format.ChunkReader
import java.util.concurrent.TimeUnit

internal const val KEY_SESSION = "sessionHex"
private const val TAG = "PendulumWork"

/**
 * Reconciliation disque <-> base, en tete de chaine.
 *
 * Le service de reception ecrit le fichier **puis** la ligne. Entre les deux, le processus peut
 * mourir : Google Play Services demarre et tue ce service librement, et un telephone sous
 * pression memoire pendant la nuit n'a rien d'exotique. Il reste alors un fichier valide dont
 * la base ignore l'existence — donc un chunk jamais acquitte, que la montre garde indefiniment,
 * et qui manque au reassemblage.
 *
 * Ce worker relit le repertoire, reinsere ce qui manque (`INSERT OR IGNORE`, donc sans risque),
 * et remet la base d'accord avec le disque. Il ne supprime jamais rien : un fichier inconnu est
 * une donnee a recuperer, pas un dechet.
 */
class IngestWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val db = PendulumDatabase.get(applicationContext)
        val store = ChunkStore(applicationContext)

        var recovered = 0
        for (file in store.listChunkFiles(hex)) {
            val idx = SessionReassembler.indexOf(file) ?: continue
            if (db.chunkDao().find(hex, idx) != null) continue

            val bytes = file.readBytes()
            val scan = file.inputStream().buffered().use { ChunkReader.forEachBlock(it) { } }
            db.chunkDao().insertIfAbsent(
                ChunkEntity(
                    sessionHex = hex,
                    idx = idx,
                    path = file.absolutePath,
                    size = bytes.size,
                    crc32 = ChunkStore.crc32(bytes),
                    sampleCount = scan.decodedSampleCount.toInt(),
                    tFirstNs = 0L,
                    tLastNs = 0L,
                    flagsOr = 0,
                    complete = scan.complete,
                    receivedAtMs = file.lastModified(),
                )
            )
            recovered++
        }
        if (recovered > 0) Log.i(TAG, "$hex : $recovered chunk(s) recuperes sur disque")
        return Result.success(workDataOf(KEY_SESSION to hex))
    }
}

/**
 * L'analyse au reveil. **Elle ne depend pas de Health Connect** : elle tourne avec le masque
 * accelerometrique, immediatement.
 *
 * C'est la decision qui rend le reste supportable. L'hypnogramme arrive quand le fournisseur le
 * decide — parfois huit heures plus tard — et faire attendre l'analyse produirait une
 * application qui, au reveil, n'a rien a dire. Le masque accelerometrique a le droit d'exister
 * et de s'afficher ; il n'a pas le droit de porter le resultat principal, parce que le
 * denominateur y est derive du meme signal que le numerateur. `RescoreWorker` ajoute le second
 * bras, non circulaire, des que l'hypnogramme est la.
 */
class AnalyzeWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val params = WorkScheduler.activeParams(applicationContext)
        return try {
            val ok = AnalysisRunner.analyse(applicationContext, hex, params)
            if (ok) Result.success(workDataOf(KEY_SESSION to hex)) else Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "analyse de $hex echouee", t)
            // `retry` et non `failure` : une analyse qui echoue sur un manque de memoire
            // reussira peut-etre quand le telephone sera au calme. Les chunks, eux, sont la.
            Result.retry()
        }
    }
}

/**
 * La lecture Health Connect, avec son echelle de reprise ([FetchSchedule]).
 *
 * **Aucune contrainte `requiresCharging`.** C'est un piege identifie : combinee a un telephone
 * qu'on ne recharge pas systematiquement le matin, elle produit un worker qui ne s'execute
 * jamais et une nuit qui n'a jamais son denominateur — sans le moindre message d'erreur. La
 * lecture coute une requete a un fournisseur local ; elle ne merite aucune contrainte.
 */
class SleepFetchWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val db = PendulumDatabase.get(applicationContext)
        val session = db.nightDao().find(hex) ?: return Result.success()

        val endMs = session.endWallMs ?: session.plannedStopWallMs
        val attempts = db.hcSnapshotDao().attemptCount(hex)
        val now = System.currentTimeMillis()

        when (val plan = FetchSchedule.plan(attempts, endMs, now)) {
            is FetchSchedule.Plan.GiveUp -> {
                Log.i(TAG, "$hex : abandon de la lecture sommeil (${plan.reason})")
                return Result.success()
            }
            is FetchSchedule.Plan.Retry -> {
                if (plan.delayMs > 0) {
                    // Pas encore l'heure : on se replanifie et on rend la main. Attendre dans le
                    // worker tiendrait un `wakelock` pendant des heures pour ne rien faire.
                    WorkScheduler.scheduleSleepFetch(applicationContext, hex, plan.delayMs)
                    return Result.success()
                }
            }
        }

        val reader = SleepReader(applicationContext)
        val availability = reader.availability()
        if (availability != SleepReader.Availability.READY) {
            Log.i(TAG, "$hex : Health Connect indisponible ($availability)")
            // On journalise quand meme la tentative : sans ligne, l'echelle ne progresse pas et
            // on reessaierait indefiniment au meme rang.
            appendSnapshot(db, hex, attempts, null, availability.name)
            WorkScheduler.scheduleNextSleepFetch(applicationContext, hex, endMs, attempts + 1)
            return Result.success()
        }

        val previous = db.hcSnapshotDao().latest(hex)
        val reading = reader.read(
            windowStartMs = session.startWallMs,
            windowEndMs = endMs,
            // TODO(source-preferee) : ce `null` fait retomber la selection sur l'heuristique de
            // `SleepSourceSelector` (couverture, puis nombre de stades). L'ecran de reglages affiche
            // une source preferee, mais rien ne la persiste encore — il n'y a aucun DataStore dans
            // le module. Tant que ce chemin n'existe pas, le reglage est decoratif, et le dire ici
            // vaut mieux que de laisser croire qu'il est honore.
            preferredPackage = null,
        )
        appendSnapshot(db, hex, attempts, reading, reading?.verdict ?: "LECTURE_IMPOSSIBLE")

        val chosen = reading?.selection?.chosen
        val rescore = FetchSchedule.shouldRescore(
            previousRecordId = previous?.selectedRecordId,
            previousLastModifiedMs = previous?.lastModifiedTimeMs,
            previousStageCount = previous?.stageCount ?: 0,
            currentRecordId = chosen?.recordId,
            currentLastModifiedMs = chosen?.lastModifiedMs,
            currentStageCount = chosen?.stages?.size ?: 0,
        )

        // On continue l'echelle **meme apres un succes** : un fournisseur peut reecrire une
        // session deja publiee, et la nuit lue a T+1 h differer de la meme nuit a T+8 h.
        WorkScheduler.scheduleNextSleepFetch(applicationContext, hex, endMs, attempts + 1)

        if (rescore) WorkScheduler.enqueueRescore(applicationContext, hex)
        return Result.success(workDataOf(KEY_SESSION to hex))
    }

    private suspend fun appendSnapshot(
        db: PendulumDatabase,
        hex: String,
        attemptIndex: Int,
        reading: SleepReader.Reading?,
        outcome: String,
    ) {
        val chosen = reading?.selection?.chosen
        db.hcSnapshotDao().append(
            HcSnapshotEntity(
                sessionHex = hex,
                fetchedAtMs = System.currentTimeMillis(),
                attemptIndex = attemptIndex,
                selectedPackage = chosen?.packageName,
                selectedRecordId = chosen?.recordId,
                lastModifiedTimeMs = chosen?.lastModifiedMs,
                sessionStartMs = chosen?.startMs,
                sessionEndMs = chosen?.endMs,
                stageCount = chosen?.stages?.size ?: 0,
                distinctStageTypes = chosen?.distinctStageTypes ?: 0,
                stageCoverageMin = (chosen?.stageCoverageMs ?: 0L) / 60_000.0,
                overlapFraction = 0.0,
                aggregateTstMin = reading?.aggregateTstMin,
                originCount = reading?.allCandidates?.map { it.packageName }?.distinct()?.size ?: 0,
                selectedStagesCsv = chosen?.let { Hypnogram.encodeCsv(it.stages) }.orEmpty(),
                recordsJson = traceOf(reading),
                outcome = outcome,
            )
        )
    }

    /**
     * Trace lisible de **tout** ce que Health Connect a renvoye, y compris les sessions
     * ecartees. Jamais reparse (voir la KDoc de `hc_snapshot.selectedStagesCsv`) : elle existe
     * pour qu'une nuit anormale soit explicable six mois plus tard.
     */
    private fun traceOf(reading: SleepReader.Reading?): String {
        if (reading == null) return "{}"
        return buildString {
            append("""{"candidats":[""")
            reading.allCandidates.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append(
                    """{"paquet":"${c.packageName}","id":"${c.recordId}","debut":${c.startMs},""" +
                        """"fin":${c.endMs},"modifie":${c.lastModifiedMs},"stades":${c.stages.size},""" +
                        """"typesDistincts":${c.distinctStageTypes}}"""
                )
            }
            append("""],"retenu":"${reading.selection.chosen?.recordId ?: ""}",""")
            append(""""motif":"${reading.selection.reason}","verdict":"${reading.verdict}"}""")
        }
    }
}

/**
 * Le rescore d'une nuit : exactement la meme analyse, avec l'hypnogramme desormais disponible.
 *
 * Il partage tout son code avec [AnalyzeWorker] et c'est voulu — deux chemins de calcul
 * differents pour la meme nuit finiraient par diverger, et l'ecart serait attribue au sommeil.
 */
class RescoreWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val hex = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val params = WorkScheduler.activeParams(applicationContext)
        return try {
            AnalysisRunner.analyse(applicationContext, hex, params)
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "rescore de $hex echoue", t)
            Result.retry()
        }
    }
}

/**
 * Le rescore de **toutes** les nuits, depuis le brut.
 *
 * Declenche par tout changement de parametre. C'est la moitie executive du garde-fou 3 : la
 * tendance refuse de melanger deux `paramsHash`, donc changer un parametre sans tout recalculer
 * viderait la tendance de tous ses points anterieurs. Recalculer est la seule reponse qui
 * conserve la campagne.
 *
 * Les nuits sont traitees de la plus ancienne a la plus recente, parce que l'etalon de gain de
 * reference est celui de la premiere nuit : la recalculer en dernier ferait analyser toutes les
 * autres avec une reference perimee.
 */
class RescoreAllWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val db = PendulumDatabase.get(applicationContext)
        val params = WorkScheduler.activeParams(applicationContext)
        var failures = 0
        for (hex in db.nightDao().allHexOldestFirst()) {
            try {
                AnalysisRunner.analyse(applicationContext, hex, params)
            } catch (t: Throwable) {
                failures++
                Log.e(TAG, "rescore global : $hex echoue", t)
            }
        }
        // Un echec partiel laisse deux hashs en base. `TrendDao.distinctHashes()` le rend
        // visible, et l'interface doit le dire plutot que de tracer une tendance amputee.
        return if (failures == 0) Result.success() else Result.retry()
    }
}

/**
 * Le chien de garde des sessions restees ouvertes.
 *
 * La montre peut mourir sans jamais publier de fermeture : batterie vide non detectee, kill
 * systeme, arrachage du bracelet. L'item `/pendulum/session` reste alors `OPEN` pour toujours, et
 * sans ce worker la nuit ne serait jamais analysee — pas parce qu'elle est inexploitable, mais
 * parce que personne ne dit qu'elle est finie.
 *
 * Deux paliers, et le second declenche l'analyse :
 *  - plus de 45 min sans nouveau chunk -> `STALE`. La montre s'est tue ; elle peut revenir.
 *  - plus de 14 h depuis le debut -> `TRUNCATED`, **et on analyse ce qu'on a**.
 *
 * Ce n'est jamais un etat final : si des chunks arrivent apres coup (montre rechargee), la
 * chaine repasse et rescorer donne le meme resultat que si tout etait arrive a l'heure.
 */
class WatchdogWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val db = PendulumDatabase.get(applicationContext)
        val now = System.currentTimeMillis()

        for (s in db.nightDao().openOrStale()) {
            val silence = now - s.lastChunkArrivalMs
            val age = now - s.startWallMs
            when {
                age > MAX_NIGHT_MS -> {
                    db.nightDao().setState(s.sessionHex, "TRUNCATED")
                    WorkScheduler.enqueueNightChain(applicationContext, s.sessionHex)
                }
                s.state == "OPEN" && s.lastChunkArrivalMs > 0 && silence > STALE_MS -> {
                    db.nightDao().setState(s.sessionHex, "STALE")
                }
            }
        }
        return Result.success()
    }

    private companion object {
        val STALE_MS = TimeUnit.MINUTES.toMillis(45)
        val MAX_NIGHT_MS = TimeUnit.HOURS.toMillis(14)
    }
}
