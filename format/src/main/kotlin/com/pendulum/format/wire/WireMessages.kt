package com.pendulum.format.wire

/**
 * `/pendulum/session/<sessionHex>` — montre -> telephone, `setUrgent()`.
 *
 * Pose a l'ouverture ([SessionState.OPEN]), reecrit a la fermeture propre. C'est ce qui permet
 * au telephone de savoir qu'une nuit **existe** avant d'en avoir recu la fin : sans cet item,
 * une nuit interrompue est indiscernable d'une nuit qui n'a jamais eu lieu.
 *
 * @param zoneId identifiant IANA (`Europe/Paris`). Il vit ici et dans le sidecar, pas dans
 *   l'entete binaire du chunk, qui ne porte que l'offset en minutes — voir la KDoc de
 *   `ChunkFormat` pour la justification.
 * @param plannedStopWallMs heure d'arret prevue, connue des l'ouverture : le telephone peut
 *   ainsi distinguer « la nuit n'est pas finie » de « la montre ne repond plus ».
 */
data class SessionHeader(
    val sessionHex: String,
    val startWallMs: Long,
    val tzOffsetMin: Int,
    val zoneId: String,
    val nominalRateHz: Int,
    val modeFlags: Int,
    val plannedStopWallMs: Long,
    val state: SessionState,
    val endWallMs: Long? = null,
    val totalChunks: Int? = null,
    val stopReason: StopReason? = null,
) {
    fun encode(): ByteArray = WireWriter(96)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i64(startWallMs)
        .i16(tzOffsetMin)
        .str(zoneId)
        .i16(nominalRateHz)
        .i16(modeFlags)
        .i64(plannedStopWallMs)
        .u8(state.code)
        .optI64(endWallMs)
        .optI32(totalChunks)
        .optI32(stopReason?.code)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): SessionHeader {
            val r = WireReader(bytes)
            r.version("SessionHeader")
            return SessionHeader(
                sessionHex = r.str(),
                startWallMs = r.i64(),
                tzOffsetMin = r.i16().toShort().toInt(),
                zoneId = r.str(),
                nominalRateHz = r.u16(),
                modeFlags = r.u16(),
                plannedStopWallMs = r.i64(),
                state = SessionState.fromCode(r.u8()),
                endWallMs = r.optI64(),
                totalChunks = r.optI32(),
                stopReason = r.optI32()?.let { StopReason.fromCode(it) },
            )
        }
    }
}

/**
 * `/pendulum/chunk/<sessionHex>/<idx:05d>` — montre -> telephone. Metadonnees accompagnant les
 * octets exacts du fichier de chunk.
 *
 * Le telephone recalcule [crc32] sur les octets recus avant d'inserer quoi que ce soit :
 * c'est la seule verification qui couvre le transport, la ou le CRC16 par bloc ne couvre que
 * le contenu. Un index dont le CRC32 ne retombe pas juste part dans `needResend` de l'ack.
 *
 * @param flagsOr `OU` de tous les `flags` des blocs du chunk : permet de reperer un chunk
 *   contenant de la saturation ou un trou sans le decoder.
 */
data class ChunkMeta(
    val sessionHex: String,
    val idx: Int,
    val size: Int,
    val crc32: Long,
    val sampleCount: Int,
    val tFirstNs: Long,
    val tLastNs: Long,
    val flagsOr: Int,
) {
    fun encode(): ByteArray = WireWriter(64)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i32(idx)
        .i32(size)
        .i64(crc32)
        .i32(sampleCount)
        .i64(tFirstNs)
        .i64(tLastNs)
        .i16(flagsOr)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ChunkMeta {
            val r = WireReader(bytes)
            r.version("ChunkMeta")
            return ChunkMeta(
                sessionHex = r.str(),
                idx = r.i32(),
                size = r.i32(),
                crc32 = r.i64(),
                sampleCount = r.i32(),
                tFirstNs = r.i64(),
                tLastNs = r.i64(),
                flagsOr = r.u16(),
            )
        }
    }
}

/**
 * `/pendulum/live/<sessionHex>` — montre -> telephone, `setUrgent()`, **remplace** a chaque salve
 * et jamais accumule : c'est un etat, pas un journal.
 *
 * Repond au besoin reel derriere « je veux voir le graphe de mouvement » : savoir que
 * l'enregistrement est vivant, et voir la forme du signal, pour ~1 Ko toutes les 5 min au lieu
 * des 300 o/s du brut.
 *
 * @param envU8 enveloppe RMS a 1 Hz quantifiee u8 logarithmique, [PreviewEnvelopeCodec.LENGTH]
 *   octets = 15 min glissantes. Voir [PreviewEnvelopeCodec].
 * @param syncBacklogged vrai quand le plafond d'items en vol est atteint : l'enregistrement
 *   continue sans degradation, seul le transfert est en retard. A afficher, pas a alarmer.
 */
class LivePreview(
    val sessionHex: String,
    val lastUpdateMs: Long,
    val elapsedMs: Long,
    val samplesWritten: Long,
    val bytesWritten: Long,
    val batteryPct: Int,
    val gapCount: Int,
    val gapTotalMs: Long,
    val modeFlags: Int,
    val lastClosedChunkIdx: Int,
    val syncBacklogged: Boolean,
    val envU8: ByteArray,
) {
    init {
        require(envU8.size == PreviewEnvelopeCodec.LENGTH) {
            "envU8 doit faire ${PreviewEnvelopeCodec.LENGTH} octets, recu ${envU8.size}"
        }
        require(batteryPct in 0..100) { "batteryPct hors bornes : $batteryPct" }
    }

    fun encode(): ByteArray = WireWriter(PreviewEnvelopeCodec.LENGTH + 96)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i64(lastUpdateMs)
        .i64(elapsedMs)
        .i64(samplesWritten)
        .i64(bytesWritten)
        .u8(batteryPct)
        .i32(gapCount)
        .i64(gapTotalMs)
        .i16(modeFlags)
        .i32(lastClosedChunkIdx)
        .bool(syncBacklogged)
        .blob(envU8)
        .toByteArray()

    // equals/hashCode manuels : ByteArray a une identite par reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LivePreview) return false
        return sessionHex == other.sessionHex &&
            lastUpdateMs == other.lastUpdateMs &&
            elapsedMs == other.elapsedMs &&
            samplesWritten == other.samplesWritten &&
            bytesWritten == other.bytesWritten &&
            batteryPct == other.batteryPct &&
            gapCount == other.gapCount &&
            gapTotalMs == other.gapTotalMs &&
            modeFlags == other.modeFlags &&
            lastClosedChunkIdx == other.lastClosedChunkIdx &&
            syncBacklogged == other.syncBacklogged &&
            envU8.contentEquals(other.envU8)
    }

    override fun hashCode(): Int {
        var result = sessionHex.hashCode()
        result = 31 * result + lastUpdateMs.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + samplesWritten.hashCode()
        result = 31 * result + bytesWritten.hashCode()
        result = 31 * result + batteryPct
        result = 31 * result + gapCount
        result = 31 * result + gapTotalMs.hashCode()
        result = 31 * result + modeFlags
        result = 31 * result + lastClosedChunkIdx
        result = 31 * result + syncBacklogged.hashCode()
        result = 31 * result + envU8.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): LivePreview {
            val r = WireReader(bytes)
            r.version("LivePreview")
            return LivePreview(
                sessionHex = r.str(),
                lastUpdateMs = r.i64(),
                elapsedMs = r.i64(),
                samplesWritten = r.i64(),
                bytesWritten = r.i64(),
                batteryPct = r.u8(),
                gapCount = r.i32(),
                gapTotalMs = r.i64(),
                modeFlags = r.u16(),
                lastClosedChunkIdx = r.i32(),
                syncBacklogged = r.bool(),
                envU8 = r.blob(),
            )
        }
    }
}

/**
 * `/pendulum/ack/<sessionHex>` — telephone -> montre, `setUrgent()`, reecrit a chaque ingestion.
 *
 * **L'accuse est un `DataItem`, pas un message** : un message envoye pendant que la montre est
 * hors de portee serait perdu et la montre garderait ses fichiers pour toujours. Ici l'accuse
 * est un *etat convergent* — le relire dix fois donne le meme resultat que le relire une fois,
 * et l'idempotence du protocole est acquise sans compteur.
 *
 * @param ackedUpTo tous les index strictement inferieurs sont acquittes et peuvent etre effaces.
 * @param ackedBitmap couvre `[bitmapBase, bitmapBase + 8 * taille)`, bit de poids faible en tete.
 * @param needResend index recus mais dont le **CRC32 est invalide** : a supprimer puis re-poser,
 *   un `putDataItem` identique etant deduplique et ne declenchant rien.
 */
class Ack(
    val sessionHex: String,
    val ackedUpTo: Int,
    val bitmapBase: Int,
    val ackedBitmap: ByteArray,
    val needResend: IntArray,
    val phoneMs: Long,
) {
    /** Vrai si le chunk [idx] est acquitte, donc supprimable du disque de la montre. */
    fun isAcked(idx: Int): Boolean {
        if (idx < ackedUpTo) return true
        val bit = idx - bitmapBase
        if (bit < 0) return false
        val byte = bit / 8
        if (byte >= ackedBitmap.size) return false
        return (ackedBitmap[byte].toInt() shr (bit % 8)) and 1 == 1
    }

    fun encode(): ByteArray = WireWriter(64 + ackedBitmap.size)
        .u8(WireProtocol.VERSION)
        .str(sessionHex)
        .i32(ackedUpTo)
        .i32(bitmapBase)
        .blob(ackedBitmap)
        .ints(needResend)
        .i64(phoneMs)
        .toByteArray()

    // equals/hashCode manuels : ByteArray et IntArray ont une identite par reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ack) return false
        return sessionHex == other.sessionHex &&
            ackedUpTo == other.ackedUpTo &&
            bitmapBase == other.bitmapBase &&
            ackedBitmap.contentEquals(other.ackedBitmap) &&
            needResend.contentEquals(other.needResend) &&
            phoneMs == other.phoneMs
    }

    override fun hashCode(): Int {
        var result = sessionHex.hashCode()
        result = 31 * result + ackedUpTo
        result = 31 * result + bitmapBase
        result = 31 * result + ackedBitmap.contentHashCode()
        result = 31 * result + needResend.contentHashCode()
        result = 31 * result + phoneMs.hashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): Ack {
            val r = WireReader(bytes)
            r.version("Ack")
            return Ack(
                sessionHex = r.str(),
                ackedUpTo = r.i32(),
                bitmapBase = r.i32(),
                ackedBitmap = r.blob(),
                needResend = r.ints(),
                phoneMs = r.i64(),
            )
        }
    }
}
