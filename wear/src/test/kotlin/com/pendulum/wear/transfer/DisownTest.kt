package com.pendulum.wear.transfer

import com.pendulum.wear.record.SessionMarker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the phone's erase order does to the watch's disk, and what it leaves alone.
 *
 * The defect these tests keep out: "erase everything" on the phone never reached the watch. Its
 * unacknowledged files stayed for good — counted as pending every evening, never re-pushed, never
 * acknowledged — and a night being recorded came back at its close, when the service re-put the
 * CLOSED header and the phone recreated the row from it.
 *
 * The marker, the clock, the sidecar reader and the store are injected, as in
 * `AckReconciliationTest`: what is asserted is the policy, not Google Play Services.
 */
class DisownTest {

    @TempDir
    lateinit var root: File

    private val erasedAt = 1_757_000_000_000L
    private val hour = 3_600_000L

    private fun session(hex: String, vararg indices: Int): File {
        val dir = File(root, hex).apply { mkdirs() }
        indices.forEach { File(dir, "%05d.pendulum".format(it)).writeBytes(ByteArray(8)) }
        File(dir, "sidecar.json").writeText("{}")
        return dir
    }

    private fun marker(hex: String, startWallMs: Long) = SessionMarker(
        sessionHex = hex,
        startWallMs = startWallMs,
        plannedStopWallMs = startWallMs + 8 * hour,
        lastChunkIndex = 2,
        modeFlags = 0,
        nominalRateHz = 50,
        zoneId = "Europe/Paris",
        stopAtLocalMinutes = 600,
    )

    private fun disown(
        active: SessionMarker? = null,
        recording: Boolean = false,
        starts: Map<String, Long?> = emptyMap(),
        deleteItems: (String) -> Unit = {},
        clearActive: () -> Unit = {},
    ) = DataLayerTransfer.disownSessions(
        chunksRoot = root,
        active = active,
        recording = recording,
        erasedBeforeMs = erasedAt,
        startOf = { dir -> starts[dir.name] },
        deleteItems = deleteItems,
        clearActive = clearActive,
    )

    @Test
    fun `a session started before the erasure loses its files and its items`() {
        // The night whose chunks were in flight when the user typed the confirmation. Before:
        // three files nobody would ever push or acknowledge again, "3 chunks pending" for good.
        val dir = session("aa", 0, 1, 2)
        val itemsDeleted = ArrayList<String>()

        val result = disown(starts = mapOf("aa" to erasedAt - 6 * hour), deleteItems = { itemsDeleted += it })

        assertThat(result.sessions).containsExactly("aa")
        assertThat(result.stopRecording).isFalse()
        assertThat(dir).doesNotExist()
        assertThat(itemsDeleted).containsExactly("aa")
    }

    @Test
    fun `a session started after the erasure is not the phone's to disown`() {
        // The order reaches a watch that was out of range, hours later, and a new night has begun
        // in the meantime. Disowning it would throw away a night the phone never erased.
        val old = session("aa", 0, 1)
        val new = session("bb", 0, 1, 2)
        val itemsDeleted = ArrayList<String>()

        val result = disown(
            starts = mapOf("aa" to erasedAt - 6 * hour, "bb" to erasedAt + 2 * hour),
            deleteItems = { itemsDeleted += it },
        )

        assertThat(result.sessions).containsExactly("aa")
        assertThat(old).doesNotExist()
        assertThat(new).isDirectory()
        assertThat(new.listFiles { f -> f.name.endsWith(".pendulum") }).hasSize(3)
        assertThat(itemsDeleted).containsExactly("aa")
    }

    @Test
    fun `the session being recorded is tombstoned and reported, not deleted`() {
        // The service holds an open stream in that directory and rotates new chunks into it until
        // its close: a deleted directory would only come back at the next rotation, without the
        // tombstone. The files go now; the tombstone catches the rest; the caller stops the
        // service.
        val dir = session("cc", 0, 1, 2)
        var markerCleared = false

        val result = disown(
            active = marker("cc", erasedAt - 3 * hour),
            recording = true,
            clearActive = { markerCleared = true },
        )

        assertThat(result.sessions).containsExactly("cc")
        assertThat(result.stopRecording).isTrue()
        assertThat(dir).isDirectory()
        assertThat(dir.listFiles { f -> f.name.endsWith(".pendulum") }).isEmpty()
        assertThat(DataLayerTransfer.isDisowned(dir)).isTrue()
        // The marker is the service's to clear, in `finalizeSession`, once it has closed.
        assertThat(markerCleared).isFalse()
    }

    @Test
    fun `the recording started after the erasure carries on`() {
        val dir = session("cc", 0)

        val result = disown(active = marker("cc", erasedAt + hour), recording = true)

        assertThat(result.sessions).isEmpty()
        assertThat(result.stopRecording).isFalse()
        assertThat(DataLayerTransfer.isDisowned(dir)).isFalse()
        assertThat(dir.listFiles { f -> f.name.endsWith(".pendulum") }).hasSize(1)
    }

    @Test
    fun `a marker with no service behind it is a crashed session, deleted marker included`() {
        // Otherwise the next resume path — boot, watchdog, START_STICKY — would bring the night
        // back and record it to its planned end, and the close would announce it to the phone.
        val dir = session("dd", 0, 1)
        var markerCleared = false

        val result = disown(
            active = marker("dd", erasedAt - 2 * hour),
            recording = false,
            clearActive = { markerCleared = true },
        )

        assertThat(result.sessions).containsExactly("dd")
        assertThat(result.stopRecording).isFalse()
        assertThat(dir).doesNotExist()
        assertThat(markerCleared).isTrue()
    }

    @Test
    fun `a directory that cannot be dated is disowned`() {
        // No sidecar: a session killed inside its first five minutes and never finalised. Keeping
        // it would keep, for ever, a file nothing will ever date.
        val dir = session("ee", 0)
        File(dir, "sidecar.json").delete()

        val result = disown(starts = mapOf("ee" to null))

        assertThat(result.sessions).containsExactly("ee")
        assertThat(dir).doesNotExist()
    }

    @Test
    fun `the store is left alone after its first failure, the files are not`() {
        // A GMS callback, like `reconcileAck`: one timeout, and the remaining item deletions wait
        // — the phone deleted the items on its side at the instant of the erasure anyway. The
        // files are the point of the order and go regardless.
        val a = session("aa", 0)
        val b = session("bb", 0)
        var storeCalls = 0

        val result = disown(
            starts = mapOf("aa" to erasedAt - hour, "bb" to erasedAt - hour),
            deleteItems = { storeCalls++; throw java.util.concurrent.TimeoutException("store unreachable") },
        )

        assertThat(result.sessions).containsExactly("aa", "bb")
        assertThat(storeCalls).isEqualTo(1)
        assertThat(a).doesNotExist()
        assertThat(b).doesNotExist()
    }

    @Test
    fun `a tombstoned directory pushes nothing and sheds what the service wrote since`() {
        // The burst after the tombstone: the service has rotated two more chunks into the
        // directory. They are erased here, and nothing goes to the store — this is the one place
        // a file is deleted without its acknowledgement bit, because the phone asked for it.
        val dir = session("cc", 3, 4)
        File(dir, DataLayerTransfer.DISOWNED_MARKER).writeBytes(ByteArray(0))

        assertThat(DataLayerTransfer.discardIfDisowned(dir)).isTrue()
        assertThat(dir.listFiles { f -> f.name.endsWith(".pendulum") }).isEmpty()
        assertThat(File(dir, DataLayerTransfer.DISOWNED_MARKER)).exists()

        // And a directory without the tombstone is not touched: the ordinary push follows.
        val live = session("ff", 0, 1)
        assertThat(DataLayerTransfer.discardIfDisowned(live)).isFalse()
        assertThat(live.listFiles { f -> f.name.endsWith(".pendulum") }).hasSize(2)
    }
}
