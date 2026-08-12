package com.pendulum.phone.ingest

import android.content.Context
import java.io.File
import java.util.zip.CRC32

/**
 * The chunk files on the phone's disk.
 *
 * They live in `filesDir/chunks/<sessionHex>/<idx:05d>.pendulum`. `filesDir` and not the cache:
 * the cache can be wiped by the system under disk pressure, and losing a raw chunk is the only
 * irreversible loss possible here.
 *
 * The index is zero-padded to five digits so that the lexicographic order of the names coincides
 * with the order of the chunks — failing which a plain `listFiles().sorted()` gives back chunk 10
 * before chunk 2, and the reassembly produces an out-of-order night without raising the slightest
 * error.
 */
class ChunkStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "chunks"))

    fun sessionDir(sessionHex: String): File = File(root, sessionHex)

    fun fileFor(sessionHex: String, idx: Int): File =
        File(sessionDir(sessionHex), "%05d.pendulum".format(idx))

    /**
     * Writes a chunk atomically: temporary file, `fsync`, then `rename`.
     *
     * Without the temporary file, an interruption in the middle of the write leaves a file of
     * plausible size and truncated content, which the database nonetheless declares received. The
     * `rename` of an already synced file is atomic on ext4: either the old state or the new one,
     * never anything in between.
     *
     * @return `true` if the file was written, `false` if it already existed at the right size —
     *   the normal case of a resend, which must do nothing.
     */
    fun write(sessionHex: String, idx: Int, bytes: ByteArray): Boolean {
        val target = fileFor(sessionHex, idx)
        if (target.exists() && target.length() == bytes.size.toLong()) return false
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        check(tmp.renameTo(target)) { "rename impossible: ${tmp.absolutePath}" }
        return true
    }

    fun exists(sessionHex: String, idx: Int): Boolean = fileFor(sessionHex, idx).exists()

    fun listSessions(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    /** The files of one session, **sorted by index** thanks to the zero-padding. */
    fun listChunkFiles(sessionHex: String): List<File> =
        sessionDir(sessionHex).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pendulum") }
            ?.sortedBy { it.name }
            .orEmpty()

    fun deleteSession(sessionHex: String): Boolean = sessionDir(sessionHex).deleteRecursively()

    /**
     * Erases **every** raw chunk. This is the disk half of the "delete everything" button:
     * emptying the database without coming through here would leave eight hours of accelerometer
     * signal per night on the phone, which the user believes they have erased.
     */
    fun deleteAll(): Boolean = root.deleteRecursively()

    fun totalBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {

        /**
         * CRC-32 over the **received** bytes.
         *
         * The per-block CRC-16 covers the contents of a block; this one covers the transport, and
         * nothing else covers it. It is verified **before** any insertion into the database: an
         * index whose CRC-32 does not come out right goes into the `needResend` of the
         * acknowledgement, which makes the watch re-put the item. Inserting first and verifying
         * afterwards would amount to acknowledging wrong bytes, hence to making the watch delete
         * the only correct copy.
         */
        fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    }
}
