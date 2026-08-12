package com.pendulum.phone.ingest

import com.pendulum.format.wire.Ack

/**
 * Building the acknowledgement **from the database**, never from an in-memory counter.
 *
 * An in-memory counter does not survive the fact that the receiving service is started and killed
 * by Google Play Services at will, potentially dozens of times over a night. An over-optimistic
 * acknowledgement makes the watch delete a file the phone does not have: that is the only way to
 * lose data for good in this protocol, and it goes through a line of code as innocuous as a
 * `count++`.
 *
 * The acknowledgement is a `DataItem`, hence **convergent state**: reading it ten times gives the
 * same result as reading it once. That is what makes idempotence free, with no sequence counter
 * and no sliding window.
 */
object AckBuilder {

    /**
     * @param completeIndices indices of the chunks present in the database **and complete** (end
     *   marker read and verified). An incomplete chunk is never acknowledged: the watch would
     *   erase a file the phone holds only a piece of.
     * @param needResend received indices whose CRC-32 was wrong. The watch must **delete then
     *   re-put** the item: an identical `putDataItem` is deduplicated by the Data Layer and would
     *   trigger nothing at all.
     */
    fun build(
        sessionHex: String,
        completeIndices: List<Int>,
        needResend: List<Int>,
        phoneMs: Long,
    ): Ack {
        val sorted = completeIndices.distinct().sorted()

        // `ackedUpTo` = length of the contiguous prefix from 0. Everything below it is
        // acknowledged without consulting the bitmap, which keeps the bitmap small in the nominal
        // case (a night that arrives in order has an empty bitmap).
        var upTo = 0
        for (i in sorted) {
            if (i == upTo) upTo++ else if (i > upTo) break
        }

        val above = sorted.filter { it >= upTo }
        if (above.isEmpty()) {
            return Ack(sessionHex, upTo, upTo, ByteArray(0), needResend.toIntArray(), phoneMs)
        }

        val base = upTo
        val highest = above.last()
        val bitmap = ByteArray((highest - base) / 8 + 1)
        for (i in above) {
            val bit = i - base
            bitmap[bit / 8] = (bitmap[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return Ack(sessionHex, upTo, base, bitmap, needResend.toIntArray(), phoneMs)
    }
}
