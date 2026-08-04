package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkReader
import com.pendulum.format.TelemetryPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * La telemetrie vue de la montre : ou elle atterrit, et ce qu'elle ne doit pas couter.
 *
 * L'invariant central est celui que la KDoc de
 * [WireProtocol.TELEMETRY_PERIOD_MS][com.pendulum.format.wire.WireProtocol.TELEMETRY_PERIOD_MS]
 * enonce : **un chunk complet porte au moins un point**. Sans lui, un chunk perdu emporterait un
 * trou de telemetrie qu'aucun autre chunk ne comblerait — et que personne ne saurait meme compter.
 */
class ChunkStoreTelemetrieTest {

    @TempDir
    lateinit var dossier: File

    private val stepNs = 20_000_000L

    private fun store(rotationMs: Long = 300L, maxRange: Float = 78.4532f) = ChunkStore(
        sessionDir = File(dossier, "session"),
        sessionUuid = ByteArray(16) { it.toByte() },
        sensorResolution = 0.0023956f,
        sensorMaxRange = maxRange,
        fifoReserved = 3000,
        startIndex = 0,
        rateHz = 50,
        modeFlags = 0,
        rotationMs = rotationMs,
    )

    private fun point(elapsedNs: Long) = TelemetryPoint(
        elapsedRealtimeNs = elapsedNs,
        sensorTsNs = elapsedNs,
        batteryChargeUah = 210_000,
        maxIntervalUs = 20_100,
        fsyncTotalUs = 1_234,
        fsyncMaxUs = 900,
        temperatureDeciC = 312,
        measuredRateCentiHz = 5_003,
        jitterStdUs = 180,
        clippedSamples = 0,
        fsyncCount = 3,
        batteryPct = 77,
        offBody = TelemetryPoint.OFF_BODY_PORTE,
        charging = false,
    )

    /** Un bloc plat de [n] echantillons a 50 Hz exacts, valeur [valeur] sur l'axe x. */
    private fun ChunkStore.bloc(n: Int, tFirstNs: Long, nowMs: Long, valeur: Float = 0f): Int? =
        writeBlock(
            FloatArray(n) { valeur }, FloatArray(n), FloatArray(n),
            n, tFirstNs, tFirstNs + (n - 1) * stepNs, 0, nowMs,
        )

    private fun fichiers(): List<File> =
        File(dossier, "session").listFiles { f -> f.name.endsWith(".pendulum") }!!.sortedBy { it.name }

    // --- Ou le point atterrit ---

    @Test
    @DisplayName("un point ecrit pendant un chunk se relit dans ce chunk")
    fun `le point atterrit dans le chunk courant`() {
        val cs = store()
        cs.bloc(50, 0L, 0L)
        val p = point(42_000_000L)
        assertThat(cs.writeTelemetry(p)).isTrue()
        cs.bloc(50, 50 * stepNs, 20L)
        cs.close()

        val lu = ChunkReader.read(fichiers().single().inputStream())
        assertThat(lu.telemetry).containsExactly(p)
        assertThat(lu.blocks).hasSize(2)
        assertThat(lu.scan.complete).isTrue()
        assertThat(lu.scan.declaredTelemetryPointCount).isEqualTo(1)
    }

    @Test
    @DisplayName("sans chunk ouvert, le point est refuse plutot qu'invente")
    fun `aucun chunk ouvert, aucun point`() {
        // La telemetrie n'ouvre jamais de chunk a elle seule : l'entete de fichier porte
        // `firstEventTimestampNs`, qui n'existe pas tant qu'aucun echantillon n'est arrive. Un
        // chunk ouvert par la telemetrie porterait une base de temps inventee.
        val cs = store()
        assertThat(cs.chunkOuvert).isFalse()
        assertThat(cs.writeTelemetry(point(0L))).isFalse()
        assertThat(File(dossier, "session").listFiles()).isEmpty()
    }

    @Test
    @DisplayName("chaque chunk d'une rotation porte ses points")
    fun `chaque chunk porte au moins un point`() {
        // Le rapport 5 pour 1 de la marche reelle — 300 s de rotation, un point par minute —
        // rejoue a l'echelle du test : 300 ms de rotation, un point toutes les 60 ms.
        val cs = store(rotationMs = 300L)
        var now = 0L
        var ts = 0L
        var prochainPoint = 60L
        var ecrits = 0
        repeat(60) {
            cs.bloc(50, ts, now)
            ts += 50 * stepNs
            now += 20L
            if (now >= prochainPoint) {
                if (cs.writeTelemetry(point(now * 1_000_000L))) ecrits++
                prochainPoint += 60L
            }
        }
        cs.close()

        val chunks = fichiers()
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4)
        val parChunk = chunks.map { ChunkReader.read(it.inputStream()).telemetry.size }
        assertThat(parChunk)
            .`as`("points de telemetrie par chunk")
            .allSatisfy { assertThat(it).isGreaterThanOrEqualTo(1) }
        assertThat(parChunk.sum()).isEqualTo(ecrits)
    }

    @Test
    @DisplayName("les octets de telemetrie comptent dans la rotation par le volume")
    fun `la telemetrie ne contourne pas le plafond d'octets`() {
        // Elle n'a pas de plafond a elle : ses octets entrent dans le `bytesWritten` du writer,
        // c'est-a-dire dans la condition de rotation par le volume. C'est ce qui fait que le
        // garde-fou des 92 160 octets la couvre sans qu'on ait rien eu a lui ajouter.
        val cs = store()
        cs.bloc(50, 0L, 0L)
        val avant = cs.totalBytes
        cs.writeTelemetry(point(0L))
        assertThat(cs.totalBytes - avant)
            .isEqualTo(
                (ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE).toLong(),
            )
    }

    // --- Les compteurs consommes par le point ---

    @Test
    @DisplayName("les gels dus aux fsync sont consommes puis remis a zero")
    fun `compteurs de fsync consommes`() {
        val cs = store()
        cs.bloc(50, 0L, 0L) // l'ouverture du chunk fsync son entete
        cs.sync()
        cs.sync()
        cs.close()

        val e = cs.consommerEcrituresFlash()
        assertThat(e.count).isEqualTo(4)
        assertThat(e.totalUs).isGreaterThanOrEqualTo(0L)
        assertThat(e.maxUs).isLessThanOrEqualTo(e.totalUs)

        // Consommer veut dire consommer : sinon deux points consecutifs compteraient deux fois
        // le meme gel, et le budget de gel de la nuit ressortirait double.
        assertThat(cs.consommerEcrituresFlash()).isEqualTo(EcrituresFlash(0, 0L, 0L))
    }

    @Test
    @DisplayName("l'ecretage du capteur se compte a travers les rotations de chunk")
    fun `ecretages cumules par-dessus les rotations`() {
        // Le compteur du writer repart de zero a chaque chunk ; la telemetrie, elle, court sur
        // toute la nuit. Sans la difference faite par `ChunkStore`, chaque rotation remettrait le
        // compteur a zero et l'ecretage d'une fin de chunk disparaitrait purement et simplement.
        val quatreG = 4f * ChunkFormat.G_IN_MS2.toFloat()
        val cs = store(rotationMs = 10L, maxRange = quatreG)
        cs.bloc(10, 0L, 0L, valeur = quatreG + 1f)
        cs.bloc(10, 10 * stepNs, 100L, valeur = quatreG + 1f) // ferme le chunk 0, ouvre le 1
        cs.close()

        assertThat(fichiers()).hasSize(2)
        assertThat(cs.consommerEcretages()).isEqualTo(20L)
        assertThat(cs.consommerEcretages()).isZero()
    }
}
