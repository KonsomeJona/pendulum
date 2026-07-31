package com.pendulum.phone.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Le format de bundle : une nuit, entiere, dans un fichier.
 *
 * ```
 * pendulum-<sessionHex>.zip
 *   manifest.txt          cle=valeur : version du bundle, session, horloges, etat, fuseau
 *   context.txt           cle=valeur : le contexte du soir scelle (dose, jambe, bracelet…)
 *   baseline.txt          cle=valeur : etalon de gain de reference, hash et parametres
 *   hypnogram.csv         debutMs:finMs:type;… tel que Health Connect l'avait rendu
 *   chunks/00000.pendulum     les octets exacts des chunks, non transcodes
 * ```
 *
 * ### Pourquoi `cle=valeur` et pas du JSON
 *
 * Ce fichier doit se **relire**, et se relire dans dix ans, sur une version de l'application qui
 * n'existe pas encore. Un format que l'on parse en quinze lignes sans bibliotheque ne peut pas
 * se mettre a echouer differemment selon la version d'un analyseur. Le JSON serait plus joli et
 * apporterait une dependance et une classe de bugs, pour zero benefice a cette echelle.
 *
 * ### Pourquoi les chunks partent tels quels
 *
 * Ce sont **les seules donnees irremplaçables**. Un bundle qui contiendrait les resultats mais
 * pas le brut serait un rapport, pas une sauvegarde : il ne permettrait pas de rescorer quand
 * l'algorithme changera. Les resultats, eux, ne sont volontairement pas dans le bundle — les
 * inclure inviterait a les comparer a ceux d'une version ulterieure sans passer par un rescore.
 *
 * ### La determinisme du fichier produit
 *
 * Les entrees sont ecrites dans un ordre fixe et **horodatees a zero**. Sans cela, deux exports
 * de la meme nuit produiraient deux fichiers differents (l'horodatage ZIP par defaut est
 * l'instant d'ecriture) et il deviendrait impossible de verifier par simple comparaison qu'un
 * export n'a pas altere son contenu.
 *
 * Objet pur, sans Android : c'est ce qui rend testable sur JVM la propriete qui compte —
 * exporter puis reimporter puis reanalyser redonne le meme resultat, au bit pres.
 */
object NightBundle {

    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.txt"
    const val CONTEXT = "context.txt"
    const val BASELINE = "baseline.txt"
    const val HYPNOGRAM = "hypnogram.csv"
    const val CHUNK_DIR = "chunks/"

    /**
     * @param chunks index -> octets exacts du fichier de chunk. Une `LinkedHashMap` triee par
     *   index a l'ecriture : l'ordre des entrees fait partie du determinisme du fichier.
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
            throw IOException("ce fichier n'est pas un bundle Pendulum (manifest absent ou illisible)")
        }
        if (manifest["sessionHex"].isNullOrBlank()) {
            throw IOException("bundle sans identifiant de session")
        }
        return Content(manifest, context, baseline, hypnogram, chunks)
    }

    // ------------------------------------------------------------------

    /**
     * `cle=valeur`, une par ligne, **triees par cle**.
     *
     * Le tri n'est pas cosmetique : sans lui, l'ordre d'iteration d'une map deciderait du
     * contenu du fichier, et deux exports identiques quant au fond produiraient deux octets
     * differents. Les retours a la ligne dans une valeur sont echappes, faute de quoi une note
     * multiligne casserait le format a la relecture.
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
        // Horodatage fige : deux exports du meme contenu doivent donner le meme fichier.
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Utilitaire de test : ecrire dans un tableau plutot que dans un flux. */
    fun toByteArray(content: Content): ByteArray =
        ByteArrayOutputStream().also { write(it, content) }.toByteArray()
}
