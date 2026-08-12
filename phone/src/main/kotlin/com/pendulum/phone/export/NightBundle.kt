package com.pendulum.phone.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The bundle format: one night, whole, in one file.
 *
 * ```
 * pendulum-<sessionHex>.zip
 *   manifest.txt          key=value : bundle version, session, clocks, state, time zone
 *   context.txt           key=value : the sealed evening context (dose, leg, strap…)
 *   baseline.txt          key=value : gain reference of the reference night, hash and parameters
 *   hypnogram.csv         startMs:endMs:type;… exactly as Health Connect returned it
 *   chunks/00000.pendulum     the exact chunk bytes, not transcoded
 * ```
 *
 * ### Why `key=value` and not JSON
 *
 * This file has to be **read back**, and read back in ten years, by a version of the application
 * that does not exist yet. A format that parses in fifteen lines without a library cannot start
 * failing differently depending on the version of a parser. JSON would be prettier, and would
 * bring a dependency and a class of bugs, for zero benefit at this scale.
 *
 * ### Why the chunks leave as they are
 *
 * They are **the only irreplaceable data**. A bundle that carried the results but not the raw
 * signal would be a report, not a backup: it would not allow a rescore when the algorithm
 * changes. The results, for their part, are deliberately not in the bundle — including them
 * would invite comparing them with those of a later version without going through a rescore.
 *
 * ### The determinism of the file produced
 *
 * The entries are written in a fixed order and **timestamped to zero**. Without that, two exports
 * of the same night would produce two different files (the default ZIP timestamp is the instant
 * of writing) and it would become impossible to check, by simple comparison, that an export has
 * not altered its content.
 *
 * A pure object, with no Android: that is what makes the property that matters testable on the
 * JVM — export, then reimport, then reanalyse gives back the same result, bit for bit.
 */
object NightBundle {

    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.txt"
    const val CONTEXT = "context.txt"
    const val BASELINE = "baseline.txt"
    const val HYPNOGRAM = "hypnogram.csv"
    const val CHUNK_DIR = "chunks/"

    /**
     * @param chunks index -> the exact bytes of the chunk file. A `LinkedHashMap` sorted by index
     *   when writing: the order of the entries is part of the determinism of the file.
     */
    data class Content(
        val manifest: Map<String, String>,
        val context: Map<String, String>,
        val baseline: Map<String, String>,
        val hypnogramCsv: String,
        val chunks: Map<Int, ByteArray>,
    ) {
        val sessionHex: String get() = manifest["sessionHex"].orEmpty()
    }

    fun write(out: OutputStream, content: Content) {
        ZipOutputStream(out).use { zip ->
            putText(zip, MANIFEST, encodeMap(content.manifest + ("bundleVersion" to "$FORMAT_VERSION")))
            putText(zip, CONTEXT, encodeMap(content.context))
            putText(zip, BASELINE, encodeMap(content.baseline))
            putText(zip, HYPNOGRAM, content.hypnogramCsv)
            for ((idx, bytes) in content.chunks.toSortedMap()) {
                putBytes(zip, "$CHUNK_DIR%05d.pendulum".format(idx), bytes)
            }
            zip.finish()
        }
    }

    fun read(input: InputStream): Content {
        var manifest: Map<String, String> = emptyMap()
        var context: Map<String, String> = emptyMap()
        var baseline: Map<String, String> = emptyMap()
        var hypnogram = ""
        val chunks = LinkedHashMap<Int, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                when {
                    entry.name == MANIFEST -> manifest = decodeMap(bytes.decodeToString())
                    entry.name == CONTEXT -> context = decodeMap(bytes.decodeToString())
                    entry.name == BASELINE -> baseline = decodeMap(bytes.decodeToString())
                    entry.name == HYPNOGRAM -> hypnogram = bytes.decodeToString()
                    entry.name.startsWith(CHUNK_DIR) && entry.name.endsWith(".pendulum") -> {
                        val idx = entry.name.removePrefix(CHUNK_DIR).removeSuffix(".pendulum").toIntOrNull()
                        if (idx != null) chunks[idx] = bytes
                    }
                }
                zip.closeEntry()
            }
        }

        if (manifest["bundleVersion"]?.toIntOrNull() == null) {
            throw IOException("this file is not a Pendulum bundle (manifest missing or unreadable)")
        }
        if (manifest["sessionHex"].isNullOrBlank()) {
            throw IOException("bundle with no session identifier")
        }
        return Content(manifest, context, baseline, hypnogram, chunks)
    }

    // ------------------------------------------------------------------

    /**
     * `key=value`, one per line, **sorted by key**.
     *
     * The sort is not cosmetic: without it, the iteration order of a map would decide the content
     * of the file, and two exports identical in substance would produce two different sets of
     * bytes. Newlines inside a value are escaped, failing which a multi-line note would break the
     * format when read back.
     */
    fun encodeMap(map: Map<String, String>): String =
        map.toSortedMap().entries.joinToString("\n") { (k, v) ->
            "$k=" + v.replace("\\", "\\\\").replace("\n", "\\n")
        }

    fun decodeMap(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out[line.substring(0, eq)] = unescape(line.substring(eq + 1))
        }
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) =
        putBytes(zip, name, text.toByteArray(Charsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        // Frozen timestamp: two exports of the same content must give the same file.
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Test helper: write into an array rather than into a stream. */
    fun toByteArray(content: Content): ByteArray =
        ByteArrayOutputStream().also { write(it, content) }.toByteArray()
}
