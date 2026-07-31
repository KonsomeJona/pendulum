package com.pendulum.phone.ingest

import com.pendulum.format.wire.ChunkMeta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * L'accuse de reception et la detection de troncature : les deux endroits ou une erreur fait
 * perdre des donnees plutot que d'afficher un faux chiffre.
 *
 * L'accuse est ce qui autorise la montre a **effacer** son fichier. Un accuse trop optimiste est
 * la seule facon de perdre definitivement des donnees dans ce protocole.
 */
class AckAndReassemblyTest {

    private val hex = "0123456789abcdef0123456789abcdef"

    @Test
    fun `une nuit recue dans l'ordre a un bitmap vide`() {
        val ack = AckBuilder.build(hex, (0..9).toList(), emptyList(), 0L)
        assertThat(ack.ackedUpTo).isEqualTo(10)
        // Le prefixe continu suffit : le bitmap ne sert qu'aux arrivees dans le desordre, et le
        // garder vide dans le cas nominal garde l'item leger.
        assertThat(ack.ackedBitmap).isEmpty()
        assertThat(ack.isAcked(0)).isTrue()
        assertThat(ack.isAcked(9)).isTrue()
        assertThat(ack.isAcked(10)).isFalse()
    }

    @Test
    fun `un trou arrete le prefixe et bascule le reste dans le bitmap`() {
        val ack = AckBuilder.build(hex, listOf(0, 1, 2, 5, 7), emptyList(), 0L)
        assertThat(ack.ackedUpTo).isEqualTo(3)
        assertThat(ack.isAcked(2)).isTrue()
        assertThat(ack.isAcked(3)).isFalse()
        assertThat(ack.isAcked(4)).isFalse()
        assertThat(ack.isAcked(5)).isTrue()
        assertThat(ack.isAcked(6)).isFalse()
        assertThat(ack.isAcked(7)).isTrue()
        assertThat(ack.isAcked(8)).isFalse()
    }

    @Test
    fun `un accuse survit a l'encodage et au decodage`() {
        val ack = AckBuilder.build(hex, listOf(0, 1, 4, 9), listOf(2, 3), 1234L)
        val relu = com.pendulum.format.wire.Ack.decode(ack.encode())
        assertThat(relu.ackedUpTo).isEqualTo(ack.ackedUpTo)
        assertThat(relu.needResend).containsExactly(2, 3)
        for (i in 0..12) assertThat(relu.isAcked(i)).isEqualTo(ack.isAcked(i))
    }

    @Test
    fun `un doublon ne change rien a l'accuse`() {
        val a = AckBuilder.build(hex, listOf(0, 1, 2), emptyList(), 0L)
        val b = AckBuilder.build(hex, listOf(0, 1, 1, 2, 2, 2), emptyList(), 0L)
        assertThat(b.ackedUpTo).isEqualTo(a.ackedUpTo)
        assertThat(b.ackedBitmap).isEqualTo(a.ackedBitmap)
    }

    // --- Detection de troncature -------------------------------------------------------------

    @Test
    fun `les index manquants tiennent compte du total annonce`() {
        // Le faux negatif silencieux a eviter : la montre a annonce 96 chunks, on en a 40, et
        // ne regarder que les recus dirait « aucun trou » a une nuit dont les deux tiers
        // manquent.
        val manquants = SessionReassembler.missingIndices((0..39).toList(), declaredChunks = 96)
        assertThat(manquants).hasSize(56)
        assertThat(manquants.first()).isEqualTo(40)
        assertThat(manquants.last()).isEqualTo(95)
    }

    @Test
    fun `un trou au milieu est detecte`() {
        assertThat(SessionReassembler.missingIndices(listOf(0, 1, 3, 4), null))
            .containsExactly(2)
    }

    @Test
    fun `une nuit complete n'a aucun manquant`() {
        assertThat(SessionReassembler.missingIndices((0..9).toList(), 10)).isEmpty()
    }

    @Test
    fun `une session sans aucun chunk n'invente pas de manquants`() {
        assertThat(SessionReassembler.missingIndices(emptyList(), null)).isEmpty()
    }

    // --- Verification d'un chunk recu ---------------------------------------------------------

    private fun meta(size: Int, crc: Long, session: String = hex) =
        ChunkMeta(session, idx = 3, size = size, crc32 = crc, sampleCount = 512,
            tFirstNs = 0, tLastNs = 1, flagsOr = 0)

    @Test
    fun `des octets conformes sont acceptes`() {
        val bytes = ByteArray(200) { it.toByte() }
        val verdict = ChunkVerifier.verify(hex, meta(bytes.size, ChunkStore.crc32(bytes)), bytes)
        assertThat(verdict).isEqualTo(ChunkVerifier.Verdict.OK)
    }

    @Test
    fun `un CRC faux est refuse, et la taille est verifiee avant lui`() {
        val bytes = ByteArray(200) { it.toByte() }
        assertThat(ChunkVerifier.verify(hex, meta(bytes.size, 0xDEADBEEF), bytes))
            .isEqualTo(ChunkVerifier.Verdict.CRC_MISMATCH)
        // Taille annoncee differente : on refuse sans payer le balayage CRC.
        assertThat(ChunkVerifier.verify(hex, meta(999, ChunkStore.crc32(bytes)), bytes))
            .isEqualTo(ChunkVerifier.Verdict.SIZE_MISMATCH)
    }

    @Test
    fun `un item route vers une autre session est refuse`() {
        val bytes = ByteArray(200)
        val autre = "ffffffffffffffffffffffffffffffff"
        assertThat(ChunkVerifier.verify(hex, meta(bytes.size, ChunkStore.crc32(bytes), autre), bytes))
            .isEqualTo(ChunkVerifier.Verdict.SESSION_MISMATCH)
    }

    @Test
    fun `une taille aberrante est refusee`() {
        val minuscule = ByteArray(10)
        assertThat(ChunkVerifier.verify(hex, meta(10, ChunkStore.crc32(minuscule)), minuscule))
            .isEqualTo(ChunkVerifier.Verdict.IMPLAUSIBLE_SIZE)

        val enorme = ByteArray(200 * 1024)
        assertThat(ChunkVerifier.verify(hex, meta(enorme.size, ChunkStore.crc32(enorme)), enorme))
            .isEqualTo(ChunkVerifier.Verdict.IMPLAUSIBLE_SIZE)
    }

    @Test
    fun `l'enveloppe de chunk fait l'aller-retour sans perte`() {
        val chunk = ByteArray(1024) { (it * 7).toByte() }
        val m = meta(chunk.size, ChunkStore.crc32(chunk))
        val decode = ChunkEnvelope.decode(ChunkEnvelope.encode(m, chunk))
        assertThat(decode.meta).isEqualTo(m)
        assertThat(decode.chunkBytes).isEqualTo(chunk)
    }
}
