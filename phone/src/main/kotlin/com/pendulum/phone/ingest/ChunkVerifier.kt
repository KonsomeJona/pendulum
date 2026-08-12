package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.wire.ChunkMeta
import com.pendulum.format.wire.WireProtocol

/**
 * Verification of a received chunk, **before** any write to disk or to the database.
 *
 * A pure object, with no Android in it: this is the part of the receiving service that can be
 * exercised on the JVM, and it is also the part where a mistake costs the most — accepting wrong
 * bytes amounts to acknowledging them, hence to making the watch delete the only correct copy.
 */
object ChunkVerifier {

    /**
     * Order of the refusals: from the cheapest to the most expensive.
     *
     * The size is compared before computing a CRC-32, because a wrong size is the symptom of a
     * transport truncation and there is no reason to pay for a 91 KB sweep to learn it. Anything
     * too large for a `DataItem` is refused as well: an item beyond the documented ceiling should
     * not exist, and seeing one arrive signals a sender that is not the expected watch.
     */
    enum class Verdict {
        /** Bytes conform: to be written, then acknowledged once the file is read back and complete. */
        OK,

        /** Announced session differs from the one in the path: malformed item, ignored. */
        SESSION_MISMATCH,

        /** Announced size and received size differ: transport truncation. Resend. */
        SIZE_MISMATCH,

        /** Beyond the `DataItem` ceiling, or below a single file header. Aberrant item. */
        IMPLAUSIBLE_SIZE,

        /** CRC-32 wrong: the bytes are corrupt. Resend. */
        CRC_MISMATCH,
    }

    /**
     * @param sessionHexFromPath the session read from the **path** of the `DataItem`, not from the
     *   payload. Comparing them checks that the path and the contents speak of the same night:
     *   without this check, a misrouted item would write the chunks of one session into the
     *   directory of another, and the reassembly would mix two nights without flagging anything.
     */
    fun verify(sessionHexFromPath: String, meta: ChunkMeta, bytes: ByteArray): Verdict = when {
        meta.sessionHex != sessionHexFromPath -> Verdict.SESSION_MISMATCH
        bytes.size < ChunkFormat.HEADER_SIZE -> Verdict.IMPLAUSIBLE_SIZE
        bytes.size > WireProtocol.MAX_DATA_ITEM_BYTES -> Verdict.IMPLAUSIBLE_SIZE
        meta.size != bytes.size -> Verdict.SIZE_MISMATCH
        ChunkStore.crc32(bytes) != meta.crc32 -> Verdict.CRC_MISMATCH
        else -> Verdict.OK
    }
}
