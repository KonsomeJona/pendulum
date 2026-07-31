package com.pendulum.phone.ingest

import com.pendulum.format.wire.Ack

/**
 * Construction de l'accuse de reception **a partir de la base**, jamais d'un compteur en memoire.
 *
 * Un compteur en memoire ne survit pas au fait que le service de reception est demarre et tue
 * par Google Play Services a sa guise, potentiellement des dizaines de fois dans la nuit. Un
 * accuse trop optimiste fait supprimer par la montre un fichier que le telephone n'a pas : c'est
 * la seule facon de perdre definitivement des donnees dans ce protocole, et elle passe par une
 * ligne de code aussi anodine qu'un `count++`.
 *
 * L'accuse est un `DataItem`, donc un **etat convergent** : le relire dix fois donne le meme
 * resultat que le relire une fois. C'est ce qui rend l'idempotence gratuite, sans compteur de
 * sequence ni fenetre glissante.
 */
object AckBuilder {

    /**
     * @param completeIndices index des chunks presents en base **et complets** (marqueur de fin
     *   lu et verifie). Un chunk incomplet n'est jamais acquitte : la montre effacerait un
     *   fichier dont le telephone n'a qu'un morceau.
     * @param needResend index recus dont le CRC-32 etait faux. La montre doit **supprimer puis
     *   re-poser** l'item : un `putDataItem` identique est dedoublonne par le Data Layer et ne
     *   declencherait rien du tout.
     */
    fun build(
        sessionHex: String,
        completeIndices: List<Int>,
        needResend: List<Int>,
        phoneMs: Long,
    ): Ack {
        val sorted = completeIndices.distinct().sorted()

        // `ackedUpTo` = longueur du prefixe continu depuis 0. Tout ce qui est en-deca est
        // acquitte sans consulter le bitmap, ce qui garde ce dernier petit dans le cas nominal
        // (une nuit qui arrive dans l'ordre a un bitmap vide).
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
