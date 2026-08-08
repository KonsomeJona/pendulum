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
 * 3. relire le fichier : le marqueur de fin, et les points de telemetrie du bloc `TLM!` ;
 * 4. `INSERT OR IGNORE` la telemetrie, puis `INSERT OR IGNORE` sur `(sessionHex, idx)` ;
 * 5. relire l'etat **depuis la base** et publier l'accuse.
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

    /** Nombre de demandes de reemission deja faites, par `sessionHex#idx`. Voir [peutEncoreDemander]. */
    private val reemissions = HashMap<String, Int>()

    /**
     * Repli de l'ouverture a distance : la montre demande que le telephone s'ouvre.
     *
     * **Ce service ne lance pas d'activite**, et ce n'est pas un oubli. Il est demarre par les
     * services Google Play, donc depuis l'arriere-plan, et Android bloque les lancements
     * d'activite depuis l'arriere-plan depuis la version 10 — sans exception lisible, sans erreur,
     * avec pour seule trace une ligne dans les journaux du systeme. Le chemin nominal passe par
     * `RemoteActivityHelper` cote montre ; quand il echoue, on poste une notification, dont le tap
     * par l'utilisateur est la seule exemption fiable.
     */
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path != WirePaths.OPEN_PHONE) return
        com.pendulum.phone.notify.Notifications.demandeDeContexteDuSoir(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // Les sessions touchees pendant cette salve : l'accuse n'est publie qu'une fois par
        // session et par salve, apres avoir tout ecrit. Un accuse par chunk multiplierait par
        // trois les `putDataItem` pour la meme information finale.
        val touched = LinkedHashMap<String, MutableSet<Int>>()

        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val payload = event.dataItem.data ?: continue
            try {
                when {
                    path.startsWith(WirePaths.SESSION_PREFIX) -> onSession(payload)
                    path.startsWith(WirePaths.CHUNK_PREFIX) -> {
                        val hex = sessionHexOfChunkPath(path) ?: continue
                        val aReemettre = touched.getOrPut(hex) { linkedSetOf() }
                        onChunk(hex, payload)?.let { idx ->
                            if (peutEncoreDemander(hex, idx)) aReemettre += idx
                        }
                    }
                    path.startsWith(WirePaths.LIVE_PREFIX) -> onLive(payload)
                }
            } catch (t: Throwable) {
                // Un item mal forme ne doit pas empecher le traitement des suivants : la salve
                // contient trois chunks, en perdre trois pour un est une perte de 15 min de nuit.
                Log.w(TAG, "item ignore : $path", t)
            }
        }

        for ((hex, aReemettre) in touched) {
            runCatching { publishAck(hex, aReemettre.sorted()) }
                .onFailure { Log.w(TAG, "accuse non publie pour $hex", it) }
        }
    }

    /**
     * **Combien de fois on redemande un chunk avant d'abandonner.**
     *
     * La reemission est indispensable — sans elle un chunk refuse est perdu pour toujours **et**
     * garde une des 24 places en vol jusqu'au matin — mais elle ne peut pas etre inconditionnelle :
     * un chunk corrompu **sur le disque de la montre** se reemettrait a l'identique a chaque
     * accuse, et la nuit se passerait a le renvoyer. Une panne de transport merite d'etre retentee,
     * une panne de stockage merite d'etre abandonnee ; rien ne les distingue vu d'ici, donc on
     * borne.
     *
     * Le compteur vit en memoire, et c'est assume : s'il repart a zero parce que le service a ete
     * recree, on aura au pire quelques tentatives de plus, ce qui est exactement le comportement
     * qu'on voudrait apres un redemarrage du telephone. Le persister ajouterait un troisieme etat a
     * reconcilier pour un benefice nul.
     */
    private fun peutEncoreDemander(sessionHex: String, idx: Int): Boolean {
        val cle = "$sessionHex#$idx"
        val n = (reemissions[cle] ?: 0) + 1
        reemissions[cle] = n
        if (n > MAX_REEMISSIONS) {
            Log.w(TAG, "chunk $idx de $sessionHex abandonne apres $MAX_REEMISSIONS demandes")
            return false
        }
        return true
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
                // Le fuseau vient de la montre et il est resolu **ici**, sur le telephone : les
                // deux appareils n'ont pas forcement la meme base tzdb, et un identifiant que
                // celle-ci ne connait pas leve. Sans ce repli, l'exception remontait au `catch`
                // generique de `onDataChanged`, la ligne `night_session` n'etait jamais creee, et
                // comme les chunks portent une cle etrangere `CASCADE` vers elle, chacun d'eux
                // violait la contrainte a son tour : la nuit entiere disparaissait sans un mot.
                // `offsetAt`, quinze lignes plus bas, se protegeait deja — pas celui-ci.
                nightKey = WirePaths.nightKey(
                    h.startWallMs,
                    runCatching { java.time.ZoneId.of(h.zoneId) }
                        .getOrElse {
                            Log.w(TAG, "fuseau inconnu du telephone : ${h.zoneId}", it)
                            java.time.ZoneId.systemDefault()
                        },
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

    /** @return l'index du chunk a reemettre si la verification a echoue, `null` sinon. */
    private fun onChunk(sessionHexFromPath: String, payload: ByteArray): Int? = runBlocking {
        val (meta, bytes) = ChunkEnvelope.decode(payload)
        when (val verdict = ChunkVerifier.verify(sessionHexFromPath, meta, bytes)) {
            ChunkVerifier.Verdict.OK -> Unit
            else -> {
                Log.w(TAG, "chunk ${meta.idx} refuse : $verdict")
                // On ne memorise pas la demande de reemission en base : elle se rededuit de
                // l'absence de la ligne. Un etat « a reemettre » persistant serait un troisieme
                // etat a reconcilier, alors que « present ou absent » suffit.
                return@runBlocking meta.idx
            }
        }

        store.write(meta.sessionHex, meta.idx, bytes)

        // Le fichier est relu pour savoir s'il est *complet* : le marqueur de fin est la seule
        // chose qui distingue un chunk clos d'un chunk en cours d'ecriture, et un chunk non
        // complet ne doit jamais etre acquitte.
        //
        // La telemetrie est recoltee **dans la meme passe**. Le lecteur traversait deja les blocs
        // `TLM!` pour verifier leur CRC et jetait les points ; les collecter ici ne coute pas une
        // seconde lecture de 90 Ko, et une seconde passe serait de toute facon une occasion de
        // diverger — un chunk juge complet par la premiere et illisible par la seconde.
        val telemetrie = ArrayList<com.pendulum.format.TelemetryPoint>()
        val complete = store.fileFor(meta.sessionHex, meta.idx).inputStream().buffered().use {
            com.pendulum.format.ChunkReader
                .forEachBlock(it, onTelemetry = { point -> telemetrie += point }) { }
                .complete
        }

        // Ecrite **avant** la ligne de chunk, et donc avant tout accuse : l'accuse fait supprimer
        // le fichier sur la montre, et c'est ce fichier qui porte les points. L'ordre du protocole
        // est le meme que pour le signal — rien ne s'acquitte avant d'etre en base.
        //
        // Un chunk v1 du format ne porte aucun bloc `TLM!` : la liste est vide, l'insertion est un
        // no-op, et la nuit n'aura pas de bande de metrologie. C'est la verite, pas une panne.
        if (telemetrie.isNotEmpty()) {
            db.telemetryDao().insertAllIfAbsent(
                TelemetryAdapter.versEntites(meta.sessionHex, telemetrie)
            )
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
        null
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
    private fun publishAck(sessionHex: String, needResend: List<Int>) = runBlocking {
        val complete = db.chunkDao().completeIndices(sessionHex)
        val ack = AckBuilder.build(sessionHex, complete, needResend, System.currentTimeMillis())
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

        /**
         * Trois, parce qu'une panne de transport se resout en une ou deux tentatives et qu'au-dela
         * c'est le fichier lui-meme qui est en cause. Redemander sans fin couterait la batterie de
         * la nuit pour un chunk qui ne sera jamais bon.
         */
        const val MAX_REEMISSIONS = 3
    }
}
