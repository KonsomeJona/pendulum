package com.pendulum.phone.ingest

import com.pendulum.format.wire.ChunkMeta
import com.pendulum.format.wire.WireFormatException

/**
 * Le contenu exact d'un `DataItem` `/pendulum/chunk/<hex>/<idx>` : les metadonnees puis les octets
 * du fichier.
 *
 * ```
 * [u32 metaLen][metaLen octets de ChunkMeta.encode()][le fichier de chunk, tel quel]
 * ```
 *
 * **Pourquoi pas un `DataMap`.** Un `DataMap` est un dictionnaire : renommer une cle cote
 * emetteur donne un `null` cote recepteur, sans erreur, et le symptome apparait des semaines
 * plus tard sous la forme d'une nuit vide. Ici une divergence de layout echoue au premier
 * champ, avec un message.
 *
 * Ce n'est pas dans `:format` uniquement parce que l'enveloppe est une convention de transport
 * entre `:wear` et `:phone` et non une structure de fil versionnee — `ChunkMeta`, qui l'est,
 * porte deja le numero de version du protocole en tete de ses octets.
 */
object ChunkEnvelope {

    fun encode(meta: ChunkMeta, chunkBytes: ByteArray): ByteArray {
        val m = meta.encode()
        val out = ByteArray(4 + m.size + chunkBytes.size)
        out[0] = (m.size and 0xFF).toByte()
        out[1] = ((m.size ushr 8) and 0xFF).toByte()
        out[2] = ((m.size ushr 16) and 0xFF).toByte()
        out[3] = ((m.size ushr 24) and 0xFF).toByte()
        m.copyInto(out, 4)
        chunkBytes.copyInto(out, 4 + m.size)
        return out
    }

    data class Decoded(val meta: ChunkMeta, val chunkBytes: ByteArray)

    fun decode(payload: ByteArray): Decoded {
        if (payload.size < 4) throw WireFormatException("enveloppe de chunk trop courte")
        val metaLen = (payload[0].toInt() and 0xFF) or
            ((payload[1].toInt() and 0xFF) shl 8) or
            ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 24)
        if (metaLen < 0 || 4 + metaLen > payload.size) {
            throw WireFormatException("longueur de metadonnees invalide : $metaLen")
        }
        val meta = ChunkMeta.decode(payload.copyOfRange(4, 4 + metaLen))
        return Decoded(meta, payload.copyOfRange(4 + metaLen, payload.size))
    }
}
