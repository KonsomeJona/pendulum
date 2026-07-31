package com.pendulum.format

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.abs
import kotlin.random.Random

class ChunkCodecTest {

    /** Periode nominale a 50 Hz. Tout bloc de test doit respecter cette cadence (F-03). */
    private val stepNs = 20_000_000L

    private fun header(
        chunkIndex: Int = 0,
        modeFlags: Int = 0,
        tzOffsetMin: Int = 120,
    ) = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = chunkIndex,
        nominalRateHz = 50,
        startWallMs = 1_753_600_000_000L,
        startElapsedRealtimeNs = 123_456_789_000L,
        firstEventTimestampNs = 987_654_321_000L,
        sensorResolution = 0.0023956f,
        sensorMaxRange = 78.4532f,
        fifoMaxEventCount = 3000,
        modeFlags = modeFlags,
        tzOffsetMin = tzOffsetMin,
    )

    /**
     * Valeur d'identification d'un bloc : exactement [k] LSB, donc invariante par la
     * quantification. Ecrire `k.toFloat()` ne marcherait pas — 2 m/s^2 se relit a 2,0016.
     */
    private fun marker(k: Int): Float = ChunkFormat.toMs2(k.toShort())

    /** Ecrit un bloc plat de [n] echantillons, cadence 50 Hz exacte, marque par [mark]. */
    private fun ChunkWriter.writeFlat(n: Int, t0: Long, mark: Int = 0, flags: Int = 0) {
        writeBlock(
            FloatArray(n) { marker(mark) }, FloatArray(n), FloatArray(n),
            n, t0, t0 + (n - 1) * stepNs, flags,
        )
    }

    private fun blockSize(n: Int) = ChunkFormat.BLOCK_HEADER_SIZE + n * ChunkFormat.BYTES_PER_SAMPLE

    private fun putShortLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putLongLe(buf: ByteArray, offset: Int, v: Long) {
        for (i in 0 until 8) buf[offset + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    /** Recalcule le CRC d'un bloc apres l'avoir modifie : simule une corruption *coherente*. */
    private fun refreshBlockCrc(buf: ByteArray, blockOffset: Int) {
        val count = (buf[blockOffset + 4].toInt() and 0xFF) or ((buf[blockOffset + 5].toInt() and 0xFF) shl 8)
        val payloadLen = count * ChunkFormat.BYTES_PER_SAMPLE
        val crc = ChunkFormat.crc16(
            buf, blockOffset + ChunkFormat.BLOCK_HEADER_SIZE, payloadLen,
            seed = ChunkFormat.crc16(buf, blockOffset, ChunkFormat.BLOCK_CRC_OFFSET),
        )
        putShortLe(buf, blockOffset + ChunkFormat.BLOCK_CRC_OFFSET, crc)
    }

    // --- Entete de fichier ---

    @Test
    fun `l'entete fait exactement la taille annoncee`() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header())
        assertThat(out.size()).isEqualTo(ChunkFormat.HEADER_SIZE)
    }

    @Test
    fun `l'entete est ecrite champ par champ au layout documente`() {
        val h = header(chunkIndex = 7, modeFlags = 3, tzOffsetMin = -60)
        val out = ByteArrayOutputStream()
        ChunkWriter(out, h)
        val raw = out.toByteArray()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(raw.copyOf(8)).isEqualTo(ChunkFormat.FILE_MAGIC)
        assertThat(bb.getShort(8).toInt()).isEqualTo(ChunkFormat.FORMAT_VERSION)
        assertThat(bb.getShort(10).toInt()).isEqualTo(ChunkFormat.HEADER_SIZE)
        assertThat(bb.getShort(12).toInt()).isEqualTo(50)
        assertThat(bb.getShort(14).toInt()).isEqualTo(3000)
        assertThat(raw.copyOfRange(16, 32)).isEqualTo(h.sessionUuid)
        assertThat(bb.getLong(32)).isEqualTo(h.startWallMs)
        assertThat(bb.getLong(40)).isEqualTo(h.startElapsedRealtimeNs)
        assertThat(bb.getLong(48)).isEqualTo(h.firstEventTimestampNs)
        assertThat(bb.getFloat(56)).isEqualTo(h.sensorResolution)
        assertThat(bb.getFloat(60)).isEqualTo(h.sensorMaxRange)
        assertThat(bb.getInt(64)).isEqualTo(7)
        assertThat(bb.getShort(68).toInt()).isEqualTo(3)
        assertThat(bb.getShort(70).toInt()).isEqualTo(-60)
        assertThat(raw.copyOfRange(72, 78)).containsOnly(0)
        // Le CRC est le dernier champ et couvre tout ce qui precede (F-08).
        assertThat(bb.getShort(78).toInt() and 0xFFFF)
            .isEqualTo(ChunkFormat.crc16(raw, 0, ChunkFormat.HEADER_SIZE - 2))
    }

    @Test
    fun `une entete corrompue est rejetee par son CRC`() {
        // Un octet retourne dans startWallMs daterait toute la nuit faux, en silence (F-08).
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header())
        val bytes = out.toByteArray()
        bytes[35] = (bytes[35].toInt() xor 0x01).toByte()
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(bytes)) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("CRC d'entete")
    }

    @Test
    fun `une entete plus grande produite par une version ulterieure reste relisible`() {
        // F-32 : headerSize permet d'ajouter des champs en queue sans casser les archives.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(32, 0L, mark = 1)
        w.finish()
        val raw = out.toByteArray()

        val grownSize = 96
        val grown = ByteArray(grownSize)
        System.arraycopy(raw, 0, grown, 0, ChunkFormat.HEADER_SIZE - 2)
        putShortLe(grown, 10, grownSize)
        grown[80] = 0x42 // champ inconnu de cette version, ignore a la relecture
        putShortLe(grown, grownSize - 2, ChunkFormat.crc16(grown, 0, grownSize - 2))
        val file = grown + raw.copyOfRange(ChunkFormat.HEADER_SIZE, raw.size)

        val read = ChunkReader.read(ByteArrayInputStream(file))
        assertThat(read.header.headerSize).isEqualTo(grownSize)
        assertThat(read.header.nominalRateHz).isEqualTo(50)
        assertThat(read.blocks).hasSize(1)
        assertThat(read.scan.complete).isTrue()
    }

    @Test
    fun `l'entete refuse un FIFO ou une cadence hors u16`() {
        // F-26 : le .toShort() silencieux enregistrait 70000 comme 4464.
        assertThatThrownBy {
            header().copy(fifoMaxEventCount = 70_000)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("fifoMaxEventCount")
        assertThatThrownBy {
            header().copy(nominalRateHz = 100_000)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("nominalRateHz")
        assertThatThrownBy {
            header().copy(tzOffsetMin = 2000)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("tzOffsetMin")
    }

    @Test
    fun `aller-retour, l'entete est preservee a l'identique, fuseau compris`() {
        val h = header(
            chunkIndex = 7,
            modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
            tzOffsetMin = -330,
        )
        val out = ByteArrayOutputStream()
        ChunkWriter(out, h)
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.header).isEqualTo(h)
        assertThat(read.header.tzOffsetMin).isEqualTo(-330)
        assertThat(read.blocks).isEmpty()
        assertThat(read.corruptBlocks).isZero()
        assertThat(read.truncatedTail).isFalse()
    }

    // --- Blocs ---

    @Test
    fun `un bloc fait exactement l'entete de bloc plus 6 octets par echantillon`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        val n = 100
        w.writeFlat(n, 0L)
        assertThat(out.size()).isEqualTo(ChunkFormat.HEADER_SIZE + blockSize(n))
    }

    @Test
    fun `l'entete de bloc est ecrite au layout documente et son CRC couvre l'entete`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        val n = 8
        w.writeFlat(n, 1_000L, flags = ChunkFormat.FLAG_GAP_BEFORE)
        val raw = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(raw.copyOfRange(off, off + 4)).isEqualTo(ChunkFormat.BLOCK_MAGIC)
        assertThat(bb.getShort(off + 4).toInt()).isEqualTo(n)
        assertThat(bb.getLong(off + 6)).isEqualTo(1_000L)
        assertThat(bb.getLong(off + 14)).isEqualTo(1_000L + (n - 1) * stepNs)
        assertThat(bb.getShort(off + 22).toInt()).isEqualTo(ChunkFormat.FLAG_GAP_BEFORE)
        // F-02 : le CRC couvre blockHeader[0,24) puis le payload, pas le payload seul.
        val expected = ChunkFormat.crc16(
            raw, off + ChunkFormat.BLOCK_HEADER_SIZE, n * ChunkFormat.BYTES_PER_SAMPLE,
            seed = ChunkFormat.crc16(raw, off, ChunkFormat.BLOCK_CRC_OFFSET),
        )
        assertThat(bb.getShort(off + 24).toInt() and 0xFFFF).isEqualTo(expected)
        assertThat(raw.copyOfRange(off + 26, off + 32)).containsOnly(0)
    }

    @Test
    fun `aller-retour, les echantillons sont preserves a la resolution de quantification`() {
        val rnd = Random(42)
        val n = 512
        // Plage realiste pour une cheville : la gravite (~9,81) plus des a-coups de quelques g.
        val x = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 40f }
        val y = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 40f }
        val z = FloatArray(n) { 9.81f + (rnd.nextFloat() - 0.5f) * 10f }

        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeBlock(x, y, z, n, 1_000L, 1_000L + (n - 1) * stepNs, ChunkFormat.FLAG_FIFO_BOUNDARY)

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.blocks).hasSize(1)
        val b = read.blocks[0]
        assertThat(b.sampleCount).isEqualTo(n)
        assertThat(b.flags).isEqualTo(ChunkFormat.FLAG_FIFO_BOUNDARY)
        assertThat(b.suspectTimebase).isFalse()

        // 1 LSB = 9,80665/2048 = 0,004789 m/s^2 ; l'erreur d'arrondi est bornee par un demi-LSB.
        val maxError = ChunkFormat.G_IN_MS2 / ChunkFormat.LSB_PER_G / 2 + 1e-6
        for (i in 0 until n) {
            assertThat(abs(b.x[i] - x[i])).isLessThan(maxError.toFloat())
            assertThat(abs(b.y[i] - y[i])).isLessThan(maxError.toFloat())
            assertThat(abs(b.z[i] - z[i])).isLessThan(maxError.toFloat())
        }
    }

    @Test
    fun `la quantification sature en haut de l'echelle, qui n'est pas 16 g pile`() {
        // Un choc violent doit ecreter proprement, pas repasser en negatif par overflow.
        assertThat(ChunkFormat.toRaw(1000f)).isEqualTo(Short.MAX_VALUE)
        assertThat(ChunkFormat.toRaw(-1000f)).isEqualTo(Short.MIN_VALUE)
        assertThat(ChunkFormat.toRaw((16.0 * ChunkFormat.G_IN_MS2).toFloat())).isEqualTo(Short.MAX_VALUE)
        assertThat(ChunkFormat.toRaw((15.9 * ChunkFormat.G_IN_MS2).toFloat())).isLessThan(Short.MAX_VALUE)
        // F-33 : l'echelle n'est symetrique qu'a un LSB pres, 32767/2048 = 15,9995 g et non 16 g.
        assertThat(ChunkFormat.toMs2(Short.MAX_VALUE) / ChunkFormat.G_IN_MS2)
            .isCloseTo(15.99951, within(1e-4))
        assertThat(ChunkFormat.toMs2(Short.MIN_VALUE) / ChunkFormat.G_IN_MS2)
            .isCloseTo(-16.0, within(1e-6))
    }

    @Test
    fun `les saturations et les valeurs non finies sont comptees et flaguees`() {
        // F-11 : sans compteur ni drapeau, l'ecretage et les NaN sont indetectables a la relecture.
        val n = 10
        val x = FloatArray(n)
        x[0] = 500f            // bien au-dela du plafond du format
        x[1] = Float.NaN       // capteur en defaut : devient 0, indiscernable d'une chute libre
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeBlock(x, FloatArray(n), FloatArray(n), n, 0L, (n - 1) * stepNs, 0)

        assertThat(w.saturatedSamples).isEqualTo(1)
        assertThat(w.nonFiniteSamples).isEqualTo(1)

        val b = ChunkReader.read(ByteArrayInputStream(out.toByteArray())).blocks[0]
        assertThat(b.flags and ChunkFormat.FLAG_SATURATED).isNotZero()
        assertThat(b.flags and ChunkFormat.FLAG_NON_FINITE).isNotZero()
    }

    @Test
    fun `plusieurs blocs sont relus dans l'ordre`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(10) { k -> w.writeFlat(50, k * 1_000_000_000L, mark = k) }
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.blocks).hasSize(10)
        assertThat(read.sampleCount).isEqualTo(500)
        read.blocks.forEachIndexed { k, b ->
            assertThat(b.x[0]).isEqualTo(marker(k))
            assertThat(b.tFirstNs).isEqualTo(k * 1_000_000_000L)
        }
    }

    // --- Validation temporelle a l'ecriture (F-03, F-14) ---

    @Test
    fun `writeBlock refuse une base de temps inversee`() {
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy {
            w.writeBlock(FloatArray(10), FloatArray(10), FloatArray(10), 10, 1_000L, 500L, 0)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("inversee")
    }

    @Test
    fun `writeBlock refuse une cadence implicite absurde`() {
        // L'ancien jeu de tests ecrivait n=100 sur 1 ns et verrouillait ce comportement (F-14).
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy {
            w.writeBlock(FloatArray(100), FloatArray(100), FloatArray(100), 100, 0L, 1L, 0)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cadence implicite")
    }

    @Test
    fun `writeBlock refuse un bloc a cheval sur deux vidages du FIFO`() {
        // F-03 : 256 echantillons a 50 Hz couvrent 5,1 s ; un trou de 3 s au milieu porte
        // l'intervalle moyen a 31,8 ms, soit +59 % — l'interpolation daterait tout le bloc faux.
        val n = 256
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy {
            w.writeBlock(
                FloatArray(n), FloatArray(n), FloatArray(n), n,
                0L, (n - 1) * stepNs + 3_000_000_000L, 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("vidages du FIFO")
    }

    @Test
    fun `writeBlock refuse un count hors bornes`() {
        val w = ChunkWriter(ByteArrayOutputStream(), header())
        assertThatThrownBy { w.writeBlock(FloatArray(10), FloatArray(10), FloatArray(10), 0, 0, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            w.writeBlock(FloatArray(10), FloatArray(10), FloatArray(10), 11, 0, 0, 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `le lecteur signale une base de temps suspecte sans jeter le bloc`() {
        // Bloc ecrit valide, puis tLastNs deplace de 3 s *avec* recalcul du CRC : la corruption
        // est coherente, seule la coherence physique la trahit.
        val n = 256
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(n, 0L, mark = 1)
        val bytes = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        putLongLe(bytes, off + 14, (n - 1) * stepNs + 3_000_000_000L)
        refreshBlockCrc(bytes, off)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.blocks).hasSize(1)
        assertThat(read.blocks[0].suspectTimebase).isTrue()
        assertThat(read.scan.suspectTimebaseBlocks).isEqualTo(1)
        assertThat(read.corruptBlocks).isZero()
    }

    @Test
    fun `le lecteur rejette un bloc dont la chronologie est impossible`() {
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(n, 10_000_000_000L, mark = 1)
        val bytes = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        putLongLe(bytes, off + 14, 5_000_000_000L) // tLast < tFirst
        refreshBlockCrc(bytes, off)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.blocks).isEmpty()
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.INVALID_TIMEBASE)
    }

    // --- Corruption, resynchronisation, localisation (F-02, F-12, F-35) ---

    @Test
    fun `un bloc au CRC corrompu est saute et les blocs suivants sont conserves`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        val n = 64
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        // On corrompt un octet du payload du bloc du milieu (index 1).
        val victim = ChunkFormat.HEADER_SIZE + blockSize(n) + ChunkFormat.BLOCK_HEADER_SIZE + 3
        bytes[victim] = (bytes[victim].toInt() xor 0xFF).toByte()

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.blocks).hasSize(2)
        // Les blocs 0 et 2 survivent : la perte est bornee a un bloc, pas a la nuit.
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(2))
    }

    @Test
    fun `un count corrompu est detecte par le CRC et ne desynchronise pas la suite`() {
        // F-02 : c'est le cas ou l'ancien `continue` perdait la synchro, puisque la longueur
        // de payload lue derivait de `count`, precisement le champ corrompu.
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(4) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        w.finish()
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + blockSize(n)
        putShortLe(bytes, victimBlock + 4, 32) // count 64 -> 32, sans recalcul du CRC

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(2), marker(3))
        assertThat(read.scan.resyncSkippedBytes).isEqualTo(blockSize(n).toLong())
        assertThat(read.scan.complete).isTrue()
        assertThat(read.scan.lostBlocks).isEqualTo(1)
    }

    @Test
    fun `un tLastNs corrompu est detecte par le CRC`() {
        // Avant F-02, corrompre tLastNs decalait toute la base de temps de la nuit sans
        // qu'aucun controle ne s'en apercoive : le CRC ne couvrait que le payload.
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + blockSize(n)
        putLongLe(bytes, victimBlock + 14, 999_999_999_999L) // pas de recalcul du CRC

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.corruptBlocks).isEqualTo(1)
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(2))
    }

    @Test
    fun `un magic detruit au milieu du fichier declenche une resynchronisation`() {
        val n = 32
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(5) { k -> w.writeFlat(n, k * 1_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + 2 * blockSize(n)
        bytes[victimBlock] = 'Z'.code.toByte()

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.blocks.map { it.x[0] }).containsExactly(marker(0), marker(1), marker(3), marker(4))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_MAGIC)
        assertThat(read.scan.desynchronised).isTrue()
        assertThat(read.truncatedTail).isFalse()
    }

    @Test
    fun `une zone perdue est localisee dans le fichier et dans le temps`() {
        // F-35 : c'est ce qui permet de dire "30 s manquantes a 3h12" plutot que "1 bloc perdu".
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        val bytes = out.toByteArray()

        val victimBlock = ChunkFormat.HEADER_SIZE + blockSize(n)
        bytes[victimBlock + 40] = (bytes[victimBlock + 40].toInt() xor 0xFF).toByte()

        val scan = ChunkReader.forEachBlock(ByteArrayInputStream(bytes)) {}
        val range = scan.damagedRanges.single()
        assertThat(range.reason).isEqualTo(DamageReason.BAD_CRC)
        assertThat(range.fileOffset).isEqualTo(victimBlock.toLong())
        assertThat(range.byteLength).isEqualTo(blockSize(n).toLong())
        assertThat(range.afterTimestampNs).isEqualTo((n - 1) * stepNs)
        assertThat(range.beforeTimestampNs).isEqualTo(4_000_000_000L)
        assertThat(range.missingDurationNs).isEqualTo(4_000_000_000L - (n - 1) * stepNs)
        assertThat(scan.missingDurationNs).isEqualTo(range.missingDurationNs)
    }

    @Test
    fun `une queue tronquee et une desynchronisation sont deux etats distincts`() {
        // F-12 : l'un est benin (kill brutal, un bloc au plus), l'autre non.
        val n = 128
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(5) { k -> w.writeFlat(n, k * 3_000_000_000L, mark = k) }
        val cut = out.toByteArray().copyOf(out.size() - 200)

        val read = ChunkReader.read(ByteArrayInputStream(cut))
        assertThat(read.truncatedTail).isTrue()
        assertThat(read.scan.desynchronised).isFalse()
        assertThat(read.scan.complete).isFalse()
        assertThat(read.blocks).hasSize(4)
        assertThat(read.sampleCount).isEqualTo(4 * n)
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.TRUNCATED_TAIL)
    }

    // --- Marqueur de fin de fichier (F-37) ---

    @Test
    fun `un chunk clos porte un marqueur de fin coherent`() {
        val n = 64
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(3) { k -> w.writeFlat(n, k * 2_000_000_000L, mark = k) }
        w.finish()
        assertThat(out.size()).isEqualTo(
            ChunkFormat.HEADER_SIZE + 3 * blockSize(n) + ChunkFormat.FOOTER_SIZE
        )

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.scan.complete).isTrue()
        assertThat(read.scan.declaredBlockCount).isEqualTo(3)
        assertThat(read.scan.declaredSampleCount).isEqualTo(3L * n)
        assertThat(read.scan.lostBlocks).isEqualTo(0)
    }

    @Test
    fun `un chunk en cours d'ecriture n'est pas declare complet`() {
        // F-13/F-37 : c'est ce qui interdit au telephone d'acquitter un fichier partiel.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(64, 0L)
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.scan.complete).isFalse()
        assertThat(read.scan.declaredBlockCount).isNull()
        assertThat(read.blocks).hasSize(1)
    }

    @Test
    fun `finish est idempotent et ferme le chunk aux ecritures`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(16, 0L)
        w.finish()
        val size = out.size()
        w.finish()
        assertThat(out.size()).isEqualTo(size)
        assertThatThrownBy { w.writeFlat(16, 1_000_000_000L) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // --- Lecture streaming (F-27) et robustesse du flux (F-31) ---

    @Test
    fun `l'API streaming rend le meme bilan sans materialiser les blocs`() {
        val n = 128
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(40) { k -> w.writeFlat(n, k * 3_000_000_000L, mark = k) }
        w.finish()

        var seen = 0
        var lastX = -1f
        val scan = ChunkReader.forEachBlock(ByteArrayInputStream(out.toByteArray())) {
            seen++
            lastX = it.x[0]
        }
        assertThat(seen).isEqualTo(40)
        assertThat(lastX).isEqualTo(marker(39))
        assertThat(scan.blockCount).isEqualTo(40)
        assertThat(scan.decodedSampleCount).isEqualTo(40L * n)
        assertThat(scan.complete).isTrue()
    }

    @Test
    fun `un flux qui rend zero sans fin de fichier ne boucle pas indefiniment`() {
        // F-31 : cas reel d'un Channel du Data Layer.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(32, 0L)
        val bytes = out.toByteArray()
        val stalling = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return 0 // ni donnee, ni EOF
                val k = minOf(len, bytes.size - pos)
                System.arraycopy(bytes, pos, b, off, k)
                pos += k
                return k
            }
        }
        val read = assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            ThrowingSupplier { ChunkReader.read(stalling) },
        )
        assertThat(read.blocks).hasSize(1)
    }

    // --- Divers ---

    @Test
    fun `un fichier vide ou une entete tronquee leve`() {
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(ByteArray(0))) }
            .isInstanceOf(IOException::class.java)
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(ByteArray(40))) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `un magic de fichier invalide leve`() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header())
        val bytes = out.toByteArray()
        bytes[0] = 'X'.code.toByte()
        assertThatThrownBy { ChunkReader.read(ByteArrayInputStream(bytes)) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("magic")
    }

    @Test
    fun `les timestamps interpoles sont uniformement repartis`() {
        val n = 51
        val tFirst = 1_000_000_000L
        val b = DecodedBlock(tFirst, tFirst + (n - 1) * stepNs, 0, FloatArray(n), FloatArray(n), FloatArray(n))
        assertThat(b.timestampNs(0)).isEqualTo(tFirst)
        assertThat(b.timestampNs(n - 1)).isEqualTo(tFirst + (n - 1) * stepNs)
        assertThat(b.timestampNs(25)).isEqualTo(tFirst + 25 * stepNs)
    }

    @Test
    fun `un bloc a un seul echantillon ne divise pas par zero`() {
        val b = DecodedBlock(500L, 500L, 0, FloatArray(1), FloatArray(1), FloatArray(1))
        assertThat(b.timestampNs(0)).isEqualTo(500L)
    }

    @Test
    fun `le CRC16 est celui de CCITT-FALSE sur le vecteur de reference`() {
        // Vecteur canonique : "123456789" -> 0x29B1 en CRC-16/CCITT-FALSE.
        assertThat(ChunkFormat.crc16("123456789".toByteArray(Charsets.US_ASCII))).isEqualTo(0x29B1)
    }

    @Test
    fun `le CRC chaine sur deux tableaux vaut celui de leur concatenation`() {
        val a = "1234".toByteArray(Charsets.US_ASCII)
        val b = "56789".toByteArray(Charsets.US_ASCII)
        assertThat(ChunkFormat.crc16(b, seed = ChunkFormat.crc16(a))).isEqualTo(0x29B1)
    }

    @Test
    fun `le debit annonce est respecte, une nuit de 8 h a 50 Hz tient sous 9 Mo`() {
        // Verification du budget de stockage sur lequel repose la strategie de transfert.
        val samples = 50 * 3600 * 8
        val blocks = (samples + ChunkFormat.MAX_SAMPLES_PER_BLOCK - 1) / ChunkFormat.MAX_SAMPLES_PER_BLOCK
        val chunks = 8 * 3600 / 300 // rotation 5 min
        val bytes = (ChunkFormat.HEADER_SIZE + ChunkFormat.FOOTER_SIZE).toLong() * chunks +
            blocks.toLong() * ChunkFormat.BLOCK_HEADER_SIZE +
            samples.toLong() * ChunkFormat.BYTES_PER_SAMPLE
        assertThat(bytes).isLessThan(9L * 1024 * 1024)
    }
}
