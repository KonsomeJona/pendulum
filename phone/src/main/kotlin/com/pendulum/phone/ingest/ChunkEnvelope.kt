package com.pendulum.phone.ingest

import com.pendulum.format.wire.ChunkMeta
import com.pendulum.format.wire.WireFormatException

/**
 * The exact contents of a `/pendulum/chunk/<hex>/<idx>` `DataItem`: the metadata, then the bytes
 * of the file.
 *
 * ```
 * [u32 metaLen][metaLen bytes of ChunkMeta.encode()][the chunk file, as it is]
 * ```
 *
 * **Why not a `DataMap`.** A `DataMap` is a dictionary: renaming a key on the sender side yields a
 * `null` on the receiver side, with no error, and the symptom shows up weeks later in the form of
 * an empty night. Here a layout divergence fails on the first field, with a message.
 *
 * This is not in `:format` solely because the envelope is a transport convention between `:wear`
 * and `:phone` and not a versioned wire structure — `ChunkMeta`, which is one, already carries the
 * protocol version number at the head of its bytes.
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
        if (payload.size < 4) throw WireFormatException("chunk envelope too short")
        val metaLen = (payload[0].toInt() and 0xFF) or
            ((payload[1].toInt() and 0xFF) shl 8) or
            ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 24)
        if (metaLen < 0 || 4 + metaLen > payload.size) {
            throw WireFormatException("invalid metadata length: $metaLen")
        }
        val meta = ChunkMeta.decode(payload.copyOfRange(4, 4 + metaLen))
        return Decoded(meta, payload.copyOfRange(4 + metaLen, payload.size))
    }
}
