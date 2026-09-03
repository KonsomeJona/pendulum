package com.pendulum.wear.transfer

import com.pendulum.format.wire.Ack
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What one acknowledgement does to the disk and to the store, with the store failing the way it
 * does at 7 a.m. when the Bluetooth link falters.
 *
 * The defect these tests keep out: an `applyAck` that stopped at the first failed item deletion
 * and left the remaining acknowledged files on the watch, where nothing ever came back for them —
 * `pushChunks` skips every index still in the store, `AckObserver` needs the acknowledgement to
 * change. "15 chunks pending" for good, a session directory never released, ~1.4 MB of files and
 * as much in the store per such night.
 *
 * The store operations are injected, which is the only way to make a Data Layer failure happen on
 * demand on a JVM. What the tests assert is the reconciliation policy, not Google Play Services.
 */
class AckReconciliationTest {

    @TempDir
    lateinit var dir: File

    private val hex = "0123456789abcdef0123456789abcdef"

    private fun file(idx: Int) = File(dir, "%05d.pendulum".format(idx))

    private fun writeFiles(vararg indices: Int) =
        indices.forEach { file(it).writeBytes(ByteArray(16) { b -> b.toByte() }) }

    private fun ackUpTo(upTo: Int, vararg needResend: Int) = Ack(
        sessionHex = hex,
        ackedUpTo = upTo,
        bitmapBase = upTo,
        ackedBitmap = ByteArray(0),
        needResend = needResend,
        phoneMs = 0L,
    )

    @Test
    fun `an item deletion that fails still erases every acknowledged file`() {
        writeFiles(0, 1, 2, 3, 4, 5)
        val attempted = ArrayList<Int>()

        val deleted = DataLayerTransfer.reconcileAck(
            ack = ackUpTo(5),
            dir = dir,
            inflight = { emptySet() },
            deleteItem = { idx ->
                attempted += idx
                // The first `deleteDataItems` times out, as it does when the link drops in the
                // middle of the last acknowledgement of the night.
                throw java.util.concurrent.TimeoutException("store unreachable")
            },
            resendItem = { _, _ -> error("nothing to resend") },
        )

        // Before: files 1 to 4 stayed, each with its item, counted as pending every evening.
        assertThat(deleted).isEqualTo(5)
        for (idx in 0..4) assertThat(file(idx)).doesNotExist()
        assertThat(file(5)).exists()
        assertThat(attempted).containsExactly(0)
    }

    @Test
    fun `an item whose file is already gone is deleted from the store`() {
        // A previous pass erased files 0 and 1, then died before deleting their items: the
        // acknowledgement will never name them again, and each holds one of the 24 slots.
        writeFiles(2, 3)
        val deletedItems = ArrayList<Int>()

        DataLayerTransfer.reconcileAck(
            ack = ackUpTo(2),
            dir = dir,
            inflight = { setOf(0, 1, 2, 3) },
            deleteItem = { deletedItems += it },
            resendItem = { _, _ -> error("nothing to resend") },
        )

        assertThat(deletedItems).containsExactlyInAnyOrder(0, 1)
        assertThat(file(2)).exists()
        assertThat(file(3)).exists()
    }

    @Test
    fun `an item in flight whose file is on disk is not taken for an orphan`() {
        // Nothing acknowledged yet: the three items are simply waiting for the phone.
        writeFiles(0, 1, 2)
        val deletedItems = ArrayList<Int>()

        DataLayerTransfer.reconcileAck(
            ack = ackUpTo(0),
            dir = dir,
            inflight = { setOf(0, 1, 2) },
            deleteItem = { deletedItems += it },
            resendItem = { _, _ -> error("nothing to resend") },
        )

        assertThat(deletedItems).isEmpty()
        for (idx in 0..2) assertThat(file(idx)).exists()
    }

    @Test
    fun `the store is left alone after its first failure`() {
        // This runs inside a GMS callback: twenty-four timeouts of 60 s would hold it for
        // twenty-four minutes. One failure, and the store — items, sweep, resends — waits for
        // the next pass; the files, which the phone already holds, do not.
        writeFiles(0, 1, 2, 7)
        var storeCalls = 0
        var inflightRead = false
        var resent = false

        val deleted = DataLayerTransfer.reconcileAck(
            ack = ackUpTo(3, 7),
            dir = dir,
            inflight = { inflightRead = true; emptySet() },
            deleteItem = { storeCalls++; throw IllegalStateException("store unreachable") },
            resendItem = { _, _ -> resent = true },
        )

        assertThat(deleted).isEqualTo(3)
        for (idx in 0..2) assertThat(file(idx)).doesNotExist()
        assertThat(storeCalls).isEqualTo(1)
        assertThat(inflightRead).isFalse()
        assertThat(resent).isFalse()
        assertThat(file(7)).exists()
    }

    @Test
    fun `a chunk refused by the phone is resent when the store answers`() {
        writeFiles(3, 7)
        val resent = ArrayList<Int>()

        DataLayerTransfer.reconcileAck(
            ack = ackUpTo(3, 7),
            dir = dir,
            inflight = { setOf(3, 7) },
            deleteItem = { },
            resendItem = { idx, f ->
                resent += idx
                assertThat(f).isEqualTo(file(idx))
            },
        )

        assertThat(resent).containsExactly(7)
        assertThat(file(3)).exists()
    }
}
