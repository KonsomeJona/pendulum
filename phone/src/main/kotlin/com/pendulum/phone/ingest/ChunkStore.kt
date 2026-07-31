package com.pendulum.phone.ingest

import android.content.Context
import java.io.File
import java.util.zip.CRC32

/**
 * Les fichiers de chunks sur le disque du telephone.
 *
 * Ils vivent dans `filesDir/chunks/<sessionHex>/<idx:05d>.pendulum`. `filesDir` et non le cache :
 * le cache est effacable par le systeme sous pression disque, et perdre un chunk brut est la
 * seule perte irreversible possible ici.
 *
 * L'index est zero-pade sur cinq chiffres pour que l'ordre lexicographique des noms coincide
 * avec l'ordre des chunks — sans quoi un simple `listFiles().sorted()` rend le chunk 10 avant
 * le chunk 2, et le reassemblage produit une nuit dans le desordre sans lever la moindre erreur.
 */
class ChunkStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "chunks"))

    fun sessionDir(sessionHex: String): File = File(root, sessionHex)

    fun fileFor(sessionHex: String, idx: Int): File =
        File(sessionDir(sessionHex), "%05d.pendulum".format(idx))

    /**
     * Ecrit un chunk de facon atomique : fichier temporaire, `fsync`, puis `rename`.
     *
     * Sans le temporaire, une coupure au milieu de l'ecriture laisse un fichier de taille
     * plausible et de contenu tronque, que la base declare pourtant recu. Le `rename` d'un
     * fichier deja synchronise est atomique sur ext4 : soit l'ancien etat, soit le nouveau,
     * jamais un entre-deux.
     *
     * @return `true` si le fichier a ete ecrit, `false` s'il existait deja a la bonne taille —
     *   cas normal d'une reemission, qui ne doit rien faire.
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
        check(tmp.renameTo(target)) { "renommage impossible : ${tmp.absolutePath}" }
        return true
    }

    fun exists(sessionHex: String, idx: Int): Boolean = fileFor(sessionHex, idx).exists()

    fun listSessions(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    /** Les fichiers d'une session, **tries par index** grace au zero-padding. */
    fun listChunkFiles(sessionHex: String): List<File> =
        sessionDir(sessionHex).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pendulum") }
            ?.sortedBy { it.name }
            .orEmpty()

    fun deleteSession(sessionHex: String): Boolean = sessionDir(sessionHex).deleteRecursively()

    /**
     * Efface **tous** les chunks bruts. C'est la moitie disque du bouton « tout supprimer » :
     * vider la base sans passer ici laisserait sur le telephone huit heures de signal
     * accelerometrique par nuit, que l'utilisateur croit avoir effacees.
     */
    fun deleteAll(): Boolean = root.deleteRecursively()

    fun totalBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {

        /**
         * CRC-32 sur les octets **recus**.
         *
         * Le CRC-16 par bloc couvre le contenu d'un bloc ; celui-ci couvre le transport, et
         * rien d'autre ne le couvre. Il est verifie **avant** toute insertion en base : un
         * index dont le CRC-32 ne retombe pas juste part dans `needResend` de l'accuse, ce qui
         * fait re-poser l'item par la montre. Inserer d'abord et verifier ensuite reviendrait a
         * acquitter des octets faux, donc a faire supprimer par la montre le seul exemplaire
         * correct.
         */
        fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    }
}
