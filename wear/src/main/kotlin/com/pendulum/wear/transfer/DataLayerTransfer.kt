package com.pendulum.wear.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.ChunkReader
import com.pendulum.format.wire.Ack
import com.pendulum.format.wire.ChunkMeta
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.StopReason
import com.pendulum.format.wire.WirePaths
import com.pendulum.format.wire.WireProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Poussee incrementale des chunks vers le telephone par `DataClient`.
 *
 * **Pourquoi `DataClient` et rien d'autre.** C'est un magasin **replique et persistant** : un
 * item pose a 2 h du matin est deja replique, et la montre qui meurt a 3 h ne le concerne plus.
 * Il est bufferise hors connexion et synchronise a la reconnexion — c'est documente, donc tester
 * la joignabilite du telephone avant d'ecrire reviendrait a reimplementer a la main une logique
 * que la couche fournit, et a se tromper au moment precis ou elle compte. On ecrit, point.
 * `MessageClient` est du fire-and-forget sans file d'attente : jamais pour une donnee qu'on ne
 * peut pas perdre. `ChannelClient` n'a aucune persistance : le canal meurt avec la connexion.
 *
 * **Le protocole est idempotent par construction.** Le telephone insere en `INSERT OR IGNORE`
 * sur `(sessionId, idx)`, l'accuse est un `DataItem` — c'est-a-dire un *etat convergent*, relu
 * dix fois pour le meme resultat — et **aucun fichier n'est efface avant son bit d'accuse**.
 * Le disque de la montre reste la source de verite jusqu'a ce que la base du telephone le
 * devienne.
 */
object DataLayerTransfer {

    private const val TAG = "PendulumTransfer"

    /**
     * Plafond d'items en vol : deux heures de nuit, ~2,2 Mo dans le magasin. Le quota reel du
     * magasin de `DataItem` n'est documente nulle part, et le decouvrir par un plantage a 4 h du
     * matin n'est pas une methode acceptable. Au-dela, la montre cesse de publier et **continue
     * d'enregistrer sur le disque sans la moindre degradation**.
     */
    const val MAX_INFLIGHT_ITEMS = 24

    /** Une salve tous les trois chunks fermes, soit un quart d'heure de nuit. */
    const val PUSH_EVERY_N_CHUNKS = 3

    private const val TASK_TIMEOUT_S = 60L

    private fun client(ctx: Context): DataClient = Wearable.getDataClient(ctx)

    /** URI sans autorite : elle designe le chemin sur tous les noeuds, ce qui est exactement le
     *  besoin, aussi bien pour lire l'accuse pose par le telephone que pour effacer nos items. */
    private fun uri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    private fun <T> await(task: Task<T>): T = Tasks.await(task, TASK_TIMEOUT_S, TimeUnit.SECONDS)

    // --- session ---

    fun putSession(ctx: Context, header: SessionHeader) {
        val req = PutDataRequest.create(WirePaths.session(header.sessionHex))
            .setData(header.encode())
            .setUrgent()
        await(client(ctx).putDataItem(req))
    }

    // --- chunks ---

    /**
     * Publie les chunks presents sur le disque et absents du magasin, dans l'ordre des index,
     * jusqu'a saturation du plafond d'items en vol.
     *
     * @return vrai si le plafond est atteint alors qu'il reste des fichiers a envoyer.
     */
    fun pushChunks(ctx: Context, sessionHex: String, dir: File, urgentLast: Boolean): Boolean {
        val files = dir.listFiles { f -> f.name.endsWith(".pendulum") }
            ?.sortedBy { it.name }
            ?: return false
        if (files.isEmpty()) return false

        val inflight = inflightIndices(ctx, sessionHex)
        var slots = MAX_INFLIGHT_ITEMS - inflight.size
        var backlogged = false
        var lastReq: PutDataRequest? = null

        for (f in files) {
            val idx = f.nameWithoutExtension.toIntOrNull() ?: continue
            if (idx in inflight) continue
            if (slots <= 0) {
                backlogged = true
                break
            }
            val req = buildChunkRequest(sessionHex, idx, f) ?: continue
            lastReq?.let { await(client(ctx).putDataItem(it)) }
            lastReq = req
            slots--
        }

        // `setUrgent()` n'est pose que sur le dernier item de la salve, en pariant — confiance
        // moyenne, non documente — que le vidage qu'il provoque emporte aussi les items non
        // urgents deja en file. Si la mesure dit le contraire, marquer tout le monde urgent :
        // l'impact energetique est nul, ils partent dans le meme reveil.
        lastReq?.let {
            if (urgentLast) it.setUrgent()
            await(client(ctx).putDataItem(it))
        }
        return backlogged
    }

    /**
     * Construit l'item d'un chunk : `ChunkMeta` puis les octets exacts du fichier.
     *
     * Un chunk **sans marqueur de fin est ignore** : il est en cours d'ecriture, ou bien il est
     * le reliquat d'un kill brutal. Le publier exposerait le telephone a acquitter — donc a
     * faire supprimer — un fichier partiel.
     */
    private fun buildChunkRequest(sessionHex: String, idx: Int, file: File): PutDataRequest? {
        val bytes = file.readBytes()
        val summary = summarize(bytes) ?: return null
        val meta = ChunkMeta(
            sessionHex = sessionHex,
            idx = idx,
            size = bytes.size,
            crc32 = summary.crc32,
            sampleCount = summary.sampleCount,
            tFirstNs = summary.tFirstNs,
            tLastNs = summary.tLastNs,
            flagsOr = summary.flagsOr,
        ).encode()

        val payload = ByteArrayOutputStream(meta.size + bytes.size + 4)
        // Cadre minimal : longueur de la meta sur 4 octets petit-boutistes, puis la meta, puis
        // le fichier. Pas de `DataMap` : un dictionnaire echoue en silence quand une cle change
        // de nom, alors qu'ici un changement de layout est rejete au premier octet.
        payload.write(meta.size and 0xFF)
        payload.write((meta.size shr 8) and 0xFF)
        payload.write((meta.size shr 16) and 0xFF)
        payload.write((meta.size shr 24) and 0xFF)
        payload.write(meta)
        payload.write(bytes)
        val data = payload.toByteArray()
        if (data.size > WireProtocol.MAX_DATA_ITEM_BYTES) {
            // Ne devrait pas arriver : la rotation plafonne a 92 160 octets. Si c'est le cas,
            // le chemin de secours est `Asset.createFromFd()` sur le meme item — une ligne, a
            // n'ecrire que le jour ou la mesure le demande.
            Log.e(TAG, "chunk $idx trop gros pour un DataItem : ${data.size} o")
            return null
        }
        return PutDataRequest.create(WirePaths.chunk(sessionHex, idx)).setData(data)
    }

    private class Summary(
        val crc32: Long,
        val sampleCount: Int,
        val tFirstNs: Long,
        val tLastNs: Long,
        val flagsOr: Int,
    )

    /**
     * Relit le fichier pour en tirer ses metadonnees. Ce que la relecture coute (quelques
     * millisecondes sur 91 Ko) elle le rend en garantie : le CRC-32 de transport et le fait que
     * le marqueur de fin est bien present sont calcules sur **les octets qui partent**, pas sur
     * des compteurs tenus en memoire qui pourraient diverger du fichier.
     */
    private fun summarize(bytes: ByteArray): Summary? {
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var samples = 0
        var flags = 0
        val scan = try {
            ChunkReader.forEachBlock(bytes.inputStream()) { b ->
                if (b.tFirstNs < first) first = b.tFirstNs
                if (b.tLastNs > last) last = b.tLastNs
                samples += b.sampleCount
                flags = flags or b.flags
            }
        } catch (e: Exception) {
            Log.e(TAG, "chunk illisible, non publie", e)
            return null
        }
        if (!scan.complete || samples == 0) return null
        val crc = CRC32().apply { update(bytes) }.value
        return Summary(crc, samples, first, last, flags)
    }

    /** Index des chunks presents dans le magasin, donc en vol et comptant pour le quota. */
    fun inflightIndices(ctx: Context, sessionHex: String): Set<Int> {
        val prefix = WirePaths.CHUNK_PREFIX + sessionHex + "/"
        val buffer = await(client(ctx).getDataItems(uri(prefix), DataClient.FILTER_PREFIX))
        try {
            return buffer.mapNotNull { it.uri.lastPathSegment?.toIntOrNull() }.toSet()
        } finally {
            buffer.release()
        }
    }

    // --- apercu ---

    fun putLive(ctx: Context, preview: LivePreview) {
        val req = PutDataRequest.create(WirePaths.live(preview.sessionHex))
            .setData(preview.encode())
            .setUrgent()
        await(client(ctx).putDataItem(req))
    }

    // --- accuse ---

    /**
     * Applique un accuse : suppression des fichiers acquittes **puis** de leurs items, et
     * renvoi de ceux dont le CRC-32 n'est pas retombe juste. L'ordre compte — un item supprime
     * avant son fichier laisserait un fichier orphelin qu'aucun accuse ne reclamerait plus.
     */
    fun applyAck(ctx: Context, ack: Ack, dir: File): Int {
        var deleted = 0
        dir.listFiles { f -> f.name.endsWith(".pendulum") }?.forEach { f ->
            val idx = f.nameWithoutExtension.toIntOrNull() ?: return@forEach
            if (ack.isAcked(idx)) {
                f.delete()
                await(client(ctx).deleteDataItems(uri(WirePaths.chunk(ack.sessionHex, idx))))
                deleted++
            }
        }
        for (idx in ack.needResend) {
            val f = File(dir, "%05d.pendulum".format(idx))
            if (!f.exists()) continue
            // Un `putDataItem` identique est deduplique par le Data Layer et ne declencherait
            // rien du tout : il faut donc supprimer l'item avant de le reposer.
            await(client(ctx).deleteDataItems(uri(WirePaths.chunk(ack.sessionHex, idx))))
            buildChunkRequest(ack.sessionHex, idx, f)?.let {
                await(client(ctx).putDataItem(it.setUrgent()))
            }
        }
        return deleted
    }

    // --- contexte du soir ---

    /**
     * Verrou du contexte du soir. Le telephone pose un item sous `/pendulum/context/<cle de nuit>`
     * quand le formulaire est scelle ; la montre refuse de demarrer tant qu'il n'est pas la.
     *
     * **La presence de l'item suffit.** On ne decode pas son contenu pour decider : si le
     * telephone ecrit un jour un champ de plus, un decodage strict transformerait une evolution
     * de format en nuit perdue. Le verrou est un fait binaire, pas une structure.
     */
    fun isEveningContextSealed(ctx: Context, nightKey: String): Boolean {
        val buffer = await(
            client(ctx).getDataItems(uri(CONTEXT_PREFIX + nightKey), DataClient.FILTER_LITERAL)
        )
        try {
            return buffer.count > 0
        } finally {
            buffer.release()
        }
    }

    /**
     * Le contexte du soir et sa cle de nuit vivaient ici, et le telephone ne les connaissait pas :
     * il n'ecrivait donc jamais l'item que `Preflight` exige, et START restait bloque pour
     * toujours. Les deux sont remontes dans `:format`, le module partage par les deux
     * applications, parce que le mode de defaillance du Data Layer est le silence et non
     * l'erreur — deux constantes recopiees qui divergent d'un caractere ne produisent aucun
     * message, elles produisent une montre qui ne demarre plus.
     *
     * Les alias sont conserves : ce fichier est le point d'entree du Data Layer cote montre, et
     * y lire le nom du chemin evite d'avoir a savoir dans quel module il est declare.
     */
    const val CONTEXT_PREFIX = WirePaths.CONTEXT_PREFIX

    fun nightKey(nowMs: Long): String = WirePaths.nightKey(nowMs)

    // --- fermeture de session ---

    fun closeSession(
        ctx: Context,
        marker: com.pendulum.wear.record.SessionMarker,
        endWallMs: Long,
        totalChunks: Int,
        reason: StopReason,
    ) {
        putSession(
            ctx,
            SessionHeader(
                sessionHex = marker.sessionHex,
                startWallMs = marker.startWallMs,
                tzOffsetMin = java.util.TimeZone.getTimeZone(marker.zoneId)
                    .getOffset(marker.startWallMs) / 60_000,
                zoneId = marker.zoneId,
                nominalRateHz = marker.nominalRateHz,
                modeFlags = marker.modeFlags,
                plannedStopWallMs = marker.plannedStopWallMs,
                state = SessionState.CLOSED,
                endWallMs = endWallMs,
                totalChunks = totalChunks,
                stopReason = reason,
            ),
        )
    }
}
