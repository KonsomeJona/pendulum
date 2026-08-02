package com.pendulum.phone.ingest

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.ChunkEntity
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.runBlocking

/**
 * Reception Data Layer.
 *
 * ### Pourquoi ce service et pas un worker qui interroge
 *
 * Google Play Services demarre ce service pour livrer un `DataItem`, **meme si l'application n'a
 * jamais ete ouverte**, et il le fait des que l'item est synchronise. C'est la seule facon
 * d'encaisser une nuit entiere sans que l'utilisateur touche le telephone. Un worker
 * periodique manquerait la fenetre : les items arrivent par salves de trois toutes les quinze
 * minutes, et les faire attendre le prochain reveil de WorkManager ne servirait qu'a garder plus
 * longtemps des items en vol — donc a se rapprocher du plafond de 24 au-dela duquel la montre
 * arrete de publier.
 *
 * ### L'ordre des operations, qui est le protocole lui-meme
 *
 * 1. verifier la taille, puis le CRC-32 sur les octets recus ;
 * 2. ecrire le fichier de facon atomique ;
 * 3. `INSERT OR IGNORE` sur `(sessionHex, idx)` ;
 * 4. relire l'etat **depuis la base** et publier l'accuse.
 *
 * Aucune de ces etapes n'est commutative. Acquitter avant d'avoir ecrit ferait supprimer par la
 * montre le seul exemplaire correct ; inserer avant d'avoir verifie enregistrerait des octets
 * faux comme valides ; recalculer l'accuse depuis autre chose que la base le rendrait faux au
 * premier redemarrage du service.
 *
 * `runBlocking` est assume : les callbacks de `WearableListenerService` arrivent deja sur un
 * thread de fond, et GMS considere l'evenement traite quand la methode retourne. Lancer une
 * coroutine et rendre la main ferait perdre l'evenement si le processus est tue entre-temps.
 */
class PendulumListenerService : WearableListenerService() {

    private val db by lazy { PendulumDatabase.get(this) }
    private val store by lazy { ChunkStore(this) }

    override fun onDataChanged(events: DataEventBuffer) {
        // Les sessions touchees pendant cette salve : l'accuse n'est publie qu'une fois par
        // session et par salve, apres avoir tout ecrit. Un accuse par chunk multiplierait par
        // trois les `putDataItem` pour la meme information finale.
        val touched = LinkedHashSet<String>()
        var resendRequested = false

        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val payload = event.dataItem.data ?: continue
            try {
                when {
                    path.startsWith(WirePaths.SESSION_PREFIX) -> onSession(payload)
                    path.startsWith(WirePaths.CHUNK_PREFIX) -> {
                        val hex = sessionHexOfChunkPath(path) ?: continue
                        if (onChunk(hex, payload)) resendRequested = true
                        touched += hex
                    }
                    path.startsWith(WirePaths.LIVE_PREFIX) -> onLive(payload)
                }
            } catch (t: Throwable) {
                // Un item mal forme ne doit pas empecher le traitement des suivants : la salve
                // contient trois chunks, en perdre trois pour un est une perte de 15 min de nuit.
                Log.w(TAG, "item ignore : $path", t)
            }
        }

        for (hex in touched) {
            runCatching { publishAck(hex) }
                .onFailure { Log.w(TAG, "accuse non publie pour $hex", it) }
        }
        if (resendRequested) Log.w(TAG, "des chunks ont ete demandes en reemission")
    }

    // ------------------------------------------------------------------
    // /pendulum/session
    // ------------------------------------------------------------------

    private fun onSession(payload: ByteArray) = runBlocking {
        val h = SessionHeader.decode(payload)
        val dao = db.nightDao()

        // `insertIfAbsent` et non `REPLACE` : cet item est repose a chaque changement d'etat, et
        // un REPLACE ecraserait au passage tout ce que l'analyse a ecrit dans la ligne.
        dao.insertIfAbsent(
            NightSessionEntity(
                sessionHex = h.sessionHex,
                // La soiree a laquelle cette nuit se rattache, derivee de son heure de debut par
                // la meme bascule a midi que le chemin du contexte scelle. C'est par elle que la
                // nuit retrouve le formulaire rempli plusieurs heures avant qu'elle n'existe :
                // le `sessionHex` n'etait pas connu au moment du scellement, et il ne peut donc
                // pas servir de rattachement.
                //
                // Le fuseau est celui **annonce par la montre**, pas celui du telephone. Les deux
                // sont normalement identiques ; quand ils ne le sont pas — un vol pendant la
                // journee — c'est le fuseau ou la nuit a ete vecue qui definit la soiree.
                nightKey = WirePaths.nightKey(
                    h.startWallMs,
                    java.time.ZoneId.of(h.zoneId),
                ),
                startWallMs = h.startWallMs,
                plannedStopWallMs = h.plannedStopWallMs,
                zoneId = h.zoneId,
                tzOffsetStartMin = h.tzOffsetMin,
                // A l'ouverture, l'offset de fin est celui du debut. Il n'est corrige qu'a la
                // fermeture : c'est leur difference qui detecte une nuit de changement d'heure.
                tzOffsetEndMin = h.tzOffsetMin,
                nominalRateHz = h.nominalRateHz,
                modeFlags = h.modeFlags,
                state = h.state.name,
            )
        )

        if (h.state == SessionState.CLOSED) {
            dao.markClosed(
                hex = h.sessionHex,
                state = h.state.name,
                endWallMs = h.endWallMs,
                totalChunks = h.totalChunks,
                stopReason = h.stopReason?.name,
                tzOffsetEndMin = offsetAt(h),
            )
            // La montre a fini d'emettre : c'est le moment de lancer la chaine complete.
            WorkScheduler.enqueueNightChain(applicationContext, h.sessionHex)
        }
    }

    /**
     * Offset UTC local **a la fin** de la nuit.
     *
     * Il est recalcule ici a partir de `zoneId` et de `endWallMs` plutot que repris de l'item :
     * la montre publie l'offset qu'elle avait a l'ouverture, et une nuit de changement d'heure
     * est exactement celle ou les deux different. Reprendre le meme des deux cotes rendrait le
     * critere `tzOffsetStartMin <> tzOffsetEndMin` structurellement toujours faux — un garde-fou
     * qui ne se declenche jamais est pire que pas de garde-fou, parce qu'il rassure.
     */
    private fun offsetAt(h: SessionHeader): Int {
        val end = h.endWallMs ?: return h.tzOffsetMin
        return runCatching {
            java.time.ZoneId.of(h.zoneId)
                .rules
                .getOffset(java.time.Instant.ofEpochMilli(end))
                .totalSeconds / 60
        }.getOrDefault(h.tzOffsetMin)
    }

    // ------------------------------------------------------------------
    // /pendulum/chunk
    // ------------------------------------------------------------------

    /** @return vrai si le chunk doit etre reemis (verification echouee). */
    private fun onChunk(sessionHexFromPath: String, payload: ByteArray): Boolean = runBlocking {
        val (meta, bytes) = ChunkEnvelope.decode(payload)
        when (val verdict = ChunkVerifier.verify(sessionHexFromPath, meta, bytes)) {
            ChunkVerifier.Verdict.OK -> Unit
            else -> {
                Log.w(TAG, "chunk ${meta.idx} refuse : $verdict")
                // On ne memorise pas la demande de reemission en base : elle se rededuit de
                // l'absence de la ligne. Un etat « a reemettre » persistant serait un troisieme
                // etat a reconcilier, alors que « present ou absent » suffit.
                return@runBlocking true
            }
        }

        store.write(meta.sessionHex, meta.idx, bytes)

        // Le fichier est relu pour savoir s'il est *complet* : le marqueur de fin est la seule
        // chose qui distingue un chunk clos d'un chunk en cours d'ecriture, et un chunk non
        // complet ne doit jamais etre acquitte.
        val complete = store.fileFor(meta.sessionHex, meta.idx).inputStream().buffered().use {
            com.pendulum.format.ChunkReader.forEachBlock(it) { }.complete
        }

        db.chunkDao().insertIfAbsent(
            ChunkEntity(
                sessionHex = meta.sessionHex,
                idx = meta.idx,
                path = store.fileFor(meta.sessionHex, meta.idx).absolutePath,
                size = meta.size,
                crc32 = meta.crc32,
                sampleCount = meta.sampleCount,
                tFirstNs = meta.tFirstNs,
                tLastNs = meta.tLastNs,
                flagsOr = meta.flagsOr,
                complete = complete,
                receivedAtMs = System.currentTimeMillis(),
            )
        )
        db.nightDao().touchChunkArrival(meta.sessionHex, System.currentTimeMillis())
        false
    }

    // ------------------------------------------------------------------
    // /pendulum/live
    // ------------------------------------------------------------------

    /**
     * L'apercu n'est **jamais** stocke ni utilise pour un calcul : c'est un etat d'affichage,
     * remplace a chaque salve. La seule chose qu'on en retient est le pourcentage de batterie,
     * parce qu'il permet de rapporter une cause probable d'interruption (« derniere lecture a
     * 6 % » -> batterie) au lieu de la deviner.
     */
    private fun onLive(payload: ByteArray) = runBlocking {
        val live = LivePreview.decode(payload)
        db.nightDao().setBattery(live.sessionHex, live.batteryPct)
    }

    // ------------------------------------------------------------------
    // /pendulum/ack
    // ------------------------------------------------------------------

    /**
     * L'accuse est recalcule **entierement depuis la base**, a chaque fois. C'est plus cher
     * qu'un compteur incremental et c'est le but : le cout est une requete sur quelques
     * dizaines de lignes, le benefice est qu'aucun etat en memoire ne peut diverger de la
     * verite.
     */
    private fun publishAck(sessionHex: String) = runBlocking {
        val complete = db.chunkDao().completeIndices(sessionHex)
        val ack = AckBuilder.build(sessionHex, complete, emptyList(), System.currentTimeMillis())
        val request = PutDataRequest.create(WirePaths.ack(sessionHex))
            .setData(ack.encode())
            // Sans `setUrgent()`, le systeme peut retarder la synchronisation de 30 minutes.
            // La montre garde ses fichiers jusqu'a l'accuse : la retarder, c'est saturer son
            // disque et son plafond d'items en vol pour rien.
            .setUrgent()
        Tasks.await(Wearable.getDataClient(this@PendulumListenerService).putDataItem(request))
    }

    private fun sessionHexOfChunkPath(path: String): String? {
        val rest = path.removePrefix(WirePaths.CHUNK_PREFIX)
        val slash = rest.indexOf('/')
        return if (slash <= 0) null else rest.substring(0, slash)
    }

    private companion object {
        const val TAG = "PendulumIngest"
    }
}
