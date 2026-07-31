package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.wire.ChunkMeta
import com.pendulum.format.wire.WireProtocol

/**
 * Verification d'un chunk recu, **avant** toute ecriture disque ou base.
 *
 * Objet pur, sans Android : c'est la partie du service de reception qui peut etre exercee sur
 * JVM, et c'est aussi celle ou une erreur coute le plus cher — accepter des octets faux revient
 * a acquitter, donc a faire supprimer par la montre le seul exemplaire correct.
 */
object ChunkVerifier {

    /**
     * Ordre des refus : du moins cher au plus cher.
     *
     * On compare la taille avant de calculer un CRC-32, parce qu'une taille fausse est le
     * symptome d'une troncature de transport et qu'il n'y a aucune raison de payer un balayage
     * de 91 Ko pour l'apprendre. On refuse aussi ce qui est trop gros pour un `DataItem` : un
     * item au-dela du plafond documente ne devrait pas exister, et le voir arriver signale un
     * emetteur qui n'est pas la montre attendue.
     */
    enum class Verdict {
        /** Octets conformes : a ecrire, puis a acquitter une fois le fichier relu et complet. */
        OK,

        /** Session annoncee differente de celle du chemin : item mal forme, on ignore. */
        SESSION_MISMATCH,

        /** Taille annoncee et taille recue different : troncature de transport. Reemission. */
        SIZE_MISMATCH,

        /** Au-dela du plafond `DataItem`, ou en-deca d'un en-tete de fichier. Item aberrant. */
        IMPLAUSIBLE_SIZE,

        /** CRC-32 faux : les octets sont corrompus. Reemission. */
        CRC_MISMATCH,
    }

    /**
     * @param sessionHexFromPath la session lue dans le **chemin** du `DataItem`, pas dans la
     *   charge utile. Les comparer verifie que le chemin et le contenu parlent de la meme nuit :
     *   sans ce controle, un item mal route ecrirait des chunks d'une session dans le dossier
     *   d'une autre, et le reassemblage melangerait deux nuits sans rien signaler.
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
