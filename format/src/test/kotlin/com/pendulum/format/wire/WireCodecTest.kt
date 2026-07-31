package com.pendulum.format.wire

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class WireCodecTest {

    private val sessionHex = "000102030405060708090a0b0c0d0e0f"

    // --- Chemins et identifiants ---

    @Test
    fun `l'UUID de session fait un aller-retour hexadecimal`() {
        val uuid = ByteArray(16) { it.toByte() }
        assertThat(uuid.toSessionHex()).isEqualTo(sessionHex)
        assertThat(sessionHexToBytes(sessionHex)).isEqualTo(uuid)
    }

    @Test
    fun `les chemins de chunk sont zero-pades pour rester tries`() {
        // Un listing lexicographique doit rendre les chunks dans l'ordre, sinon le 10 passe
        // avant le 2 et la reconstruction de la nuit est fausse.
        val paths = listOf(2, 10, 100).map { WirePaths.chunk(sessionHex, it) }
        assertThat(paths).isSorted()
        assertThat(paths.first()).isEqualTo("/pendulum/chunk/$sessionHex/00002")
    }

    @Test
    fun `la rotation de chunk est de 5 min ou 90 Kio`() {
        assertThat(WireProtocol.CHUNK_ROTATION_MS).isEqualTo(300_000L)
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isEqualTo(92_160L)
        // Le plafond en octets doit laisser de la marge sous la charge utile d'un DataItem.
        assertThat(WireProtocol.CHUNK_ROTATION_BYTES).isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES.toLong())
    }

    // --- Aller-retour des quatre structures ---

    @Test
    fun `aller-retour d'une entete de session ouverte`() {
        val h = SessionHeader(
            sessionHex = sessionHex,
            startWallMs = 1_753_600_000_000L,
            tzOffsetMin = -330,
            zoneId = "Asia/Kolkata",
            nominalRateHz = 50,
            modeFlags = 0b1011,
            plannedStopWallMs = 1_753_636_000_000L,
            state = SessionState.OPEN,
        )
        assertThat(SessionHeader.decode(h.encode())).isEqualTo(h)
    }

    @Test
    fun `aller-retour d'une entete de session fermee avec sa cause d'arret`() {
        val h = SessionHeader(
            sessionHex = sessionHex,
            startWallMs = 1_753_600_000_000L,
            tzOffsetMin = 120,
            zoneId = "Europe/Paris",
            nominalRateHz = 50,
            modeFlags = 0,
            plannedStopWallMs = 1_753_636_000_000L,
            state = SessionState.CLOSED,
            endWallMs = 1_753_630_000_000L,
            totalChunks = 96,
            stopReason = StopReason.LOW_BATTERY,
        )
        val decoded = SessionHeader.decode(h.encode())
        assertThat(decoded).isEqualTo(h)
        assertThat(decoded.stopReason).isEqualTo(StopReason.LOW_BATTERY)
        assertThat(decoded.tzOffsetMin).isEqualTo(120)
    }

    @Test
    fun `aller-retour d'une entree de chunk`() {
        val m = ChunkMeta(
            sessionHex = sessionHex,
            idx = 41,
            size = 91_010,
            crc32 = 0xDEADBEEFL, // u32 : le bit de poids fort ne doit pas devenir negatif
            sampleCount = 15_000,
            tFirstNs = 987_654_321_000L,
            tLastNs = 1_287_654_321_000L,
            flagsOr = 0xFFFF,
        )
        val decoded = ChunkMeta.decode(m.encode())
        assertThat(decoded).isEqualTo(m)
        assertThat(decoded.crc32).isEqualTo(0xDEADBEEFL)
    }

    @Test
    fun `aller-retour d'un apercu en direct`() {
        val rnd = Random(7)
        val env = ByteArray(PreviewEnvelopeCodec.LENGTH) { rnd.nextInt(256).toByte() }
        val live = LivePreview(
            sessionHex = sessionHex,
            lastUpdateMs = 1_753_612_345_678L,
            elapsedMs = 12_345_678L,
            samplesWritten = 617_283L,
            bytesWritten = 3_703_698L,
            batteryPct = 63,
            gapCount = 4,
            gapTotalMs = 8_200L,
            modeFlags = 0b0110,
            lastClosedChunkIdx = 41,
            syncBacklogged = true,
            envU8 = env,
        )
        val decoded = LivePreview.decode(live.encode())
        assertThat(decoded).isEqualTo(live)
        assertThat(decoded.envU8).isEqualTo(env)
        // ~1 Ko par salve : c'est le prix du "graphe de mouvement" en quasi-temps reel.
        assertThat(live.encode().size).isLessThan(1200)
    }

    @Test
    fun `aller-retour d'un accuse de reception`() {
        val ack = Ack(
            sessionHex = sessionHex,
            ackedUpTo = 12,
            bitmapBase = 12,
            ackedBitmap = byteArrayOf(0b0000_1101, 0b0000_0010),
            needResend = intArrayOf(19, 23),
            phoneMs = 1_753_612_000_000L,
        )
        assertThat(Ack.decode(ack.encode())).isEqualTo(ack)
    }

    @Test
    fun `l'accuse designe les chunks supprimables`() {
        val ack = Ack(
            sessionHex = sessionHex,
            ackedUpTo = 12,
            bitmapBase = 12,
            ackedBitmap = byteArrayOf(0b0000_1101),
            needResend = intArrayOf(),
            phoneMs = 0L,
        )
        assertThat(ack.isAcked(11)).isTrue()   // sous ackedUpTo
        assertThat(ack.isAcked(12)).isTrue()   // bit 0
        assertThat(ack.isAcked(13)).isFalse()  // bit 1
        assertThat(ack.isAcked(14)).isTrue()   // bit 2
        assertThat(ack.isAcked(15)).isTrue()   // bit 3
        assertThat(ack.isAcked(20)).isFalse()  // hors du bitmap
    }

    // --- Robustesse du decodage ---

    @Test
    fun `une charge utile tronquee est rejetee explicitement`() {
        val m = ChunkMeta(sessionHex, 1, 100, 0L, 10, 0L, 1L, 0)
        val bytes = m.encode()
        assertThatThrownBy { ChunkMeta.decode(bytes.copyOf(bytes.size - 4)) }
            .isInstanceOf(WireFormatException::class.java)
            .hasMessageContaining("tronquee")
    }

    @Test
    fun `une version de fil inconnue est rejetee au premier octet`() {
        val bytes = ChunkMeta(sessionHex, 1, 100, 0L, 10, 0L, 1L, 0).encode()
        bytes[0] = 99
        assertThatThrownBy { ChunkMeta.decode(bytes) }
            .isInstanceOf(WireFormatException::class.java)
            .hasMessageContaining("version de fil")
    }

    @Test
    fun `un code d'etat de session inconnu est rejete`() {
        assertThatThrownBy { SessionState.fromCode(42) }
            .isInstanceOf(WireFormatException::class.java)
    }

    // --- Enveloppe d'apercu ---

    @Test
    fun `l'enveloppe quantifiee tient en 900 octets`() {
        val rms = DoubleArray(PreviewEnvelopeCodec.LENGTH) { 0.05 }
        assertThat(PreviewEnvelopeCodec.encode(rms)).hasSize(900)
    }

    @Test
    fun `la quantification logarithmique garde une erreur relative bornee sur quatre decades`() {
        // L'interet du logarithme : la meme precision relative a 2e-3 qu'a 30 m/s^2, la ou une
        // quantification lineaire ecraserait tout le sommeil sur deux niveaux.
        var v = PreviewEnvelopeCodec.MIN_MS2 * 1.05
        while (v < PreviewEnvelopeCodec.MAX_MS2) {
            val back = PreviewEnvelopeCodec.dequantize(PreviewEnvelopeCodec.quantize(v))
            assertThat(abs(back - v) / v).isLessThan(0.025)
            v *= 1.37
        }
    }

    @Test
    fun `les bornes de l'enveloppe sont respectees`() {
        assertThat(PreviewEnvelopeCodec.quantize(0.0)).isZero()
        assertThat(PreviewEnvelopeCodec.quantize(Double.NaN)).isZero()
        assertThat(PreviewEnvelopeCodec.quantize(1e-6)).isZero()
        assertThat(PreviewEnvelopeCodec.quantize(1_000.0)).isEqualTo(255)
        assertThat(PreviewEnvelopeCodec.dequantize(255))
            .isCloseTo(PreviewEnvelopeCodec.MAX_MS2, within(1e-6))
    }

    @Test
    fun `une fenetre partielle est alignee a droite`() {
        // Le dernier octet est toujours le plus recent : le telephone n'a pas a savoir depuis
        // combien de temps la nuit a commence pour tracer la courbe.
        val encoded = PreviewEnvelopeCodec.encode(doubleArrayOf(1.0, 2.0, 3.0))
        assertThat(encoded.copyOf(PreviewEnvelopeCodec.LENGTH - 3)).containsOnly(0)
        assertThat(encoded[PreviewEnvelopeCodec.LENGTH - 1].toInt() and 0xFF)
            .isEqualTo(PreviewEnvelopeCodec.quantize(3.0))
    }

    @Test
    fun `aller-retour d'une fenetre complete`() {
        val rnd = Random(3)
        val rms = DoubleArray(PreviewEnvelopeCodec.LENGTH) { rnd.nextDouble(0.01, 20.0) }
        val back = PreviewEnvelopeCodec.decode(PreviewEnvelopeCodec.encode(rms))
        for (i in rms.indices) {
            assertThat(abs(back[i] - rms[i]) / rms[i]).isLessThan(0.025)
        }
    }
}
