package com.pendulum.format

import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Le bloc de telemetrie : sa symetrie, son layout, et surtout **ce qu'il ne casse pas**.
 *
 * L'exigence centrale n'est pas qu'il fonctionne — c'est qu'il ne coute rien a ce qui fonctionnait
 * deja. D'ou la forme de ce fichier : chaque propriete du signal qui aurait pu regresser a son
 * test, et l'assertion inversee (« la telemetrie survit a un bloc de signal corrompu, et
 * reciproquement ») compte autant que l'aller-retour.
 */
class TelemetryBlockTest {

    private val stepNs = 20_000_000L

    private fun header(maxRange: Float = 78.4532f, resolution: Float = 0.0023956f) = ChunkHeader(
        sessionUuid = ByteArray(16) { it.toByte() },
        chunkIndex = 0,
        nominalRateHz = 50,
        startWallMs = 1_753_600_000_000L,
        startElapsedRealtimeNs = 123_456_789_000L,
        firstEventTimestampNs = 987_654_321_000L,
        sensorResolution = resolution,
        sensorMaxRange = maxRange,
        fifoMaxEventCount = 3000,
        modeFlags = 0,
        tzOffsetMin = 120,
    )

    /** Un point dont **aucun** champ ne vaut sa valeur par defaut : un octet oublie a l'encodage
     *  ou un champ interverti se voit alors, ce qu'un point a zero ne montrerait pas. */
    private fun point() = TelemetryPoint(
        elapsedRealtimeNs = 0x0102030405060708L,
        sensorTsNs = 987_654_321_000L,
        batteryChargeUah = -123_456,
        // Au-dela d'Int.MAX_VALUE : c'est le champ u32 qui exerce le passage par un Int signe.
        maxIntervalUs = 4_000_000_000L,
        fsyncTotalUs = 123_456L,
        fsyncMaxUs = 9_999L,
        temperatureDeciC = -157,
        measuredRateCentiHz = 5026,
        jitterStdUs = 65_535,
        clippedSamples = 12,
        fsyncCount = 7,
        batteryPct = 83,
        offBody = TelemetryPoint.OFF_BODY_RETIRE,
        charging = true,
    )

    private fun ChunkWriter.writeFlat(n: Int, t0: Long, mark: Int = 0) {
        writeBlock(
            FloatArray(n) { ChunkFormat.toMs2(mark.toShort()) }, FloatArray(n), FloatArray(n),
            n, t0, t0 + (n - 1) * stepNs, 0,
        )
    }

    private fun putShortLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun blockSize(n: Int) = ChunkFormat.BLOCK_HEADER_SIZE + n * ChunkFormat.BYTES_PER_SAMPLE

    private val telemetryBlockSize =
        ChunkFormat.TELEMETRY_HEADER_SIZE + ChunkFormat.TELEMETRY_POINT_SIZE

    // --- Symetrie et layout ---

    @Test
    fun `aller-retour, un point de telemetrie est preserve champ par champ`() {
        val p = point()
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(8, 0L)
        w.writeTelemetry(p)
        w.finish()

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.telemetry).containsExactly(p)
        assertThat(read.blocks).hasSize(1)
        assertThat(read.scan.telemetryPointCount).isEqualTo(1)
    }

    @Test
    fun `un bloc de telemetrie fait exactement son entete plus la taille d'un point`() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header()).writeTelemetry(point())
        assertThat(out.size()).isEqualTo(ChunkFormat.HEADER_SIZE + telemetryBlockSize)
    }

    @Test
    fun `le bloc de telemetrie est ecrit au layout documente`() {
        val p = point()
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header()).writeTelemetry(p)
        val raw = out.toByteArray()
        val off = ChunkFormat.HEADER_SIZE
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(raw.copyOfRange(off, off + 4)).isEqualTo(ChunkFormat.TELEMETRY_MAGIC)
        assertThat(bb.getShort(off + 4).toInt()).isEqualTo(1)
        assertThat(bb.getShort(off + 6).toInt()).isEqualTo(ChunkFormat.TELEMETRY_POINT_SIZE)
        assertThat(bb.getShort(off + 8).toInt()).isZero()
        // Le CRC couvre l'entete[0,10) puis le payload, exactement comme pour un bloc de signal.
        val expected = ChunkFormat.crc16(
            raw, off + ChunkFormat.TELEMETRY_HEADER_SIZE, ChunkFormat.TELEMETRY_POINT_SIZE,
            seed = ChunkFormat.crc16(raw, off, ChunkFormat.TELEMETRY_CRC_OFFSET),
        )
        assertThat(bb.getShort(off + ChunkFormat.TELEMETRY_CRC_OFFSET).toInt() and 0xFFFF)
            .isEqualTo(expected)
        assertThat(raw.copyOfRange(off + 12, off + 16)).containsOnly(0)

        val q = off + ChunkFormat.TELEMETRY_HEADER_SIZE
        assertThat(bb.getLong(q)).isEqualTo(p.elapsedRealtimeNs)
        assertThat(bb.getLong(q + 8)).isEqualTo(p.sensorTsNs)
        assertThat(bb.getInt(q + 16)).isEqualTo(p.batteryChargeUah)
        assertThat(bb.getInt(q + 20).toLong() and 0xFFFFFFFFL).isEqualTo(p.maxIntervalUs)
        assertThat(bb.getInt(q + 24).toLong() and 0xFFFFFFFFL).isEqualTo(p.fsyncTotalUs)
        assertThat(bb.getInt(q + 28).toLong() and 0xFFFFFFFFL).isEqualTo(p.fsyncMaxUs)
        assertThat(bb.getShort(q + 32).toInt()).isEqualTo(p.temperatureDeciC)
        assertThat(bb.getShort(q + 34).toInt() and 0xFFFF).isEqualTo(p.measuredRateCentiHz)
        assertThat(bb.getShort(q + 36).toInt() and 0xFFFF).isEqualTo(p.jitterStdUs)
        assertThat(bb.getShort(q + 38).toInt() and 0xFFFF).isEqualTo(p.clippedSamples)
        assertThat(bb.getShort(q + 40).toInt() and 0xFFFF).isEqualTo(p.fsyncCount)
        assertThat(raw[q + 42].toInt() and 0xFF).isEqualTo(p.batteryPct)
        assertThat(raw[q + 43].toInt() and 0xFF).isEqualTo(TelemetryPoint.OFF_BODY_RETIRE)
        assertThat(raw[q + 44].toInt()).isEqualTo(1)
        assertThat(raw.copyOfRange(q + 45, q + ChunkFormat.TELEMETRY_POINT_SIZE)).containsOnly(0)
    }

    @Test
    fun `les sentinelles d'absence traversent l'aller-retour`() {
        // Une lecture manquante ne doit jamais ressortir comme une mesure : 0 % de batterie et
        // 0 degre sont des valeurs parfaitement plausibles, l'absence a donc ses propres codes.
        val p = point().copy(
            batteryPct = TelemetryPoint.BATTERIE_INCONNUE,
            batteryChargeUah = TelemetryPoint.CHARGE_INCONNUE,
            temperatureDeciC = TelemetryPoint.TEMPERATURE_INCONNUE,
            offBody = TelemetryPoint.OFF_BODY_ABSENT,
            charging = false,
        )
        val out = ByteArrayOutputStream()
        ChunkWriter(out, header()).writeTelemetry(p)
        assertThat(ChunkReader.read(ByteArrayInputStream(out.toByteArray())).telemetry)
            .containsExactly(p)
    }

    @Test
    fun `un point agrandi par une version ulterieure reste relisible`() {
        // Meme regle que `headerSize` pour l'entete de fichier (F-32) : `pointSize` autorise a
        // ajouter des champs en queue du point sans casser les archives ni changer de version.
        val p = point()
        val natif = ByteArrayOutputStream()
        val w = ChunkWriter(natif, header())
        w.writeFlat(8, 0L)
        val avant = natif.size()
        w.writeTelemetry(p)
        val blocNatif = natif.toByteArray().copyOfRange(avant, natif.size())

        val agrandi = 56
        val bloc = ByteArray(ChunkFormat.TELEMETRY_HEADER_SIZE + agrandi)
        System.arraycopy(blocNatif, 0, bloc, 0, blocNatif.size)
        putShortLe(bloc, 6, agrandi)
        // Les octets inconnus de cette version : ils doivent etre sautes, pas lus.
        for (i in blocNatif.size until bloc.size) bloc[i] = 0x5A
        putShortLe(
            bloc, ChunkFormat.TELEMETRY_CRC_OFFSET,
            ChunkFormat.crc16(
                bloc, ChunkFormat.TELEMETRY_HEADER_SIZE, agrandi,
                seed = ChunkFormat.crc16(bloc, 0, ChunkFormat.TELEMETRY_CRC_OFFSET),
            ),
        )

        val fichier = natif.toByteArray().copyOf(avant) + bloc
        val read = ChunkReader.read(ByteArrayInputStream(fichier))
        assertThat(read.telemetry).containsExactly(p)
        assertThat(read.blocks).hasSize(1)
        assertThat(read.corruptBlocks).isZero()
    }

    @Test
    fun `un point plus petit que celui de cette version est rejete`() {
        // L'autre sens n'est pas symetrique : un point tronque ne laisserait pas de quoi remplir
        // les champs, et les remplir par defaut inventerait des mesures.
        val natif = ByteArrayOutputStream()
        val w = ChunkWriter(natif, header())
        w.writeTelemetry(point())
        w.writeFlat(8, 0L)
        val bytes = natif.toByteArray()
        putShortLe(bytes, ChunkFormat.HEADER_SIZE + 6, ChunkFormat.TELEMETRY_POINT_SIZE - 8)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).isEmpty()
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_COUNT)
        // Le signal qui suit est intact : la resynchronisation a retrouve son magic.
        assertThat(read.blocks).hasSize(1)
    }

    // --- Cohabitation avec le signal ---

    @Test
    fun `signal et telemetrie se melangent dans le meme chunk sans se gener`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        repeat(5) { k ->
            w.writeFlat(64, k * 2_000_000_000L, mark = k)
            w.writeTelemetry(point().copy(fsyncCount = k))
        }
        w.finish()

        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.blocks).hasSize(5)
        assertThat(read.blocks.map { it.x[0] })
            .containsExactlyElementsOf((0..4).map { ChunkFormat.toMs2(it.toShort()) })
        assertThat(read.telemetry.map { it.fsyncCount }).containsExactly(0, 1, 2, 3, 4)
        assertThat(read.scan.complete).isTrue()
        assertThat(read.corruptBlocks).isZero()
        assertThat(read.scan.desynchronised).isFalse()
    }

    @Test
    fun `le marqueur de fin annonce les points de telemetrie`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(32, 0L)
        repeat(3) { w.writeTelemetry(point()) }
        w.finish()

        val scan = ChunkReader.forEachBlock(ByteArrayInputStream(out.toByteArray())) {}
        assertThat(scan.declaredTelemetryPointCount).isEqualTo(3)
        assertThat(scan.telemetryPointCount).isEqualTo(3)
        assertThat(scan.lostTelemetryPoints).isZero()
    }

    @Test
    fun `un bloc de telemetrie corrompu est saute et le signal survit`() {
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(64, 0L, mark = 1)
        w.writeTelemetry(point())
        w.writeFlat(64, 2_000_000_000L, mark = 2)
        w.finish()
        val bytes = out.toByteArray()

        val victime = ChunkFormat.HEADER_SIZE + blockSize(64) + ChunkFormat.TELEMETRY_HEADER_SIZE + 3
        bytes[victime] = (bytes[victime].toInt() xor 0xFF).toByte()

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).isEmpty()
        assertThat(read.blocks.map { it.x[0] })
            .containsExactly(ChunkFormat.toMs2(1), ChunkFormat.toMs2(2))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_CRC)
        assertThat(read.scan.resyncSkippedBytes).isEqualTo(telemetryBlockSize.toLong())
        // Le marqueur de fin annonce le point que la relecture n'a pas retrouve : la perte est
        // chiffree, pas seulement subie.
        assertThat(read.scan.lostTelemetryPoints).isEqualTo(1)
    }

    @Test
    fun `un count de telemetrie aberrant est rejete sans desynchroniser la suite`() {
        // Le cas exact que `MAX_TELEMETRY_POINTS` existe pour borner : sans lui, un `count`
        // corrompu ferait lire une longueur de payload aberrante, donc perdre la synchro.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeTelemetry(point())
        w.writeFlat(64, 0L, mark = 7)
        w.finish()
        val bytes = out.toByteArray()
        putShortLe(bytes, ChunkFormat.HEADER_SIZE + 4, 0xFFFF)

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).isEmpty()
        assertThat(read.blocks.map { it.x[0] }).containsExactly(ChunkFormat.toMs2(7))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_COUNT)
        assertThat(read.scan.complete).isTrue()
    }

    @Test
    fun `un bloc de signal corrompu ne fait pas perdre la telemetrie qui le suit`() {
        // C'est l'assertion qui verrouille la connaissance de `TLM!` par la resynchronisation :
        // sans elle, le lecteur sauterait de bloc de signal en bloc de signal et jetterait au
        // passage une telemetrie intacte — celle qui explique peut-etre la corruption.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeFlat(64, 0L, mark = 1)
        w.writeTelemetry(point())
        w.writeFlat(64, 2_000_000_000L, mark = 2)
        w.finish()
        val bytes = out.toByteArray()

        bytes[ChunkFormat.HEADER_SIZE] = 'Z'.code.toByte() // magic du premier bloc de signal

        val read = ChunkReader.read(ByteArrayInputStream(bytes))
        assertThat(read.telemetry).containsExactly(point())
        assertThat(read.blocks.map { it.x[0] }).containsExactly(ChunkFormat.toMs2(2))
        assertThat(read.scan.damagedRanges.single().reason).isEqualTo(DamageReason.BAD_MAGIC)
    }

    // --- Ecretage du capteur (FLAG_SENSOR_CLIPPED) ---

    @Test
    fun `l'ecretage du capteur est distinct de la saturation du format`() {
        // Un capteur a 4 g s'ecrete a 39,23 m/s2, soit au quart de ce que le format sait coder :
        // l'artefact d'ecretage etait donc totalement invisible, puisque FLAG_SATURATED ne se pose
        // qu'a 16 g. C'est cet ecart-la qui faussait l'amplitude, donc le seuil de detection.
        val quatreG = 4f * ChunkFormat.G_IN_MS2.toFloat()
        val n = 10
        val x = FloatArray(n)
        x[0] = quatreG + 1f
        x[1] = -(quatreG + 1f)

        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header(maxRange = quatreG))
        w.writeBlock(x, FloatArray(n), FloatArray(n), n, 0L, (n - 1) * stepNs, 0)

        assertThat(w.clippedSamples).isEqualTo(2)
        assertThat(w.saturatedSamples).isZero()

        val b = ChunkReader.read(ByteArrayInputStream(out.toByteArray())).blocks[0]
        assertThat(b.flags and ChunkFormat.FLAG_SENSOR_CLIPPED).isNotZero()
        assertThat(b.flags and ChunkFormat.FLAG_SATURATED).isZero()
    }

    @Test
    fun `un mouvement dans la dynamique du capteur ne pose aucun drapeau`() {
        // Assertion inversee : un drapeau qui se poserait tout le temps ne dirait plus rien. La
        // gravite plus quelques g d'a-coups reste tres en deca d'un rail a 8 g.
        val n = 64
        val z = FloatArray(n) { 9.81f + (it % 7) }
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        w.writeBlock(FloatArray(n), FloatArray(n), z, n, 0L, (n - 1) * stepNs, 0)

        assertThat(w.clippedSamples).isZero()
        val b = ChunkReader.read(ByteArrayInputStream(out.toByteArray())).blocks[0]
        assertThat(b.flags and ChunkFormat.FLAG_SENSOR_CLIPPED).isZero()
    }

    @Test
    fun `une dynamique inconnue ne fait pas declarer d'ecretage`() {
        // `sensorMaxRange` a zero veut dire « on ne sait pas », et on ne devine pas : declarer
        // toute la nuit ecretee serait pire que de ne rien declarer.
        val n = 8
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header(maxRange = 0f))
        w.writeBlock(FloatArray(n) { 100f }, FloatArray(n), FloatArray(n), n, 0L, (n - 1) * stepNs, 0)
        assertThat(w.clippedSamples).isZero()
    }

    // --- Non-regression de taille ---

    @Test
    fun `un bloc de telemetrie plein reste plus petit qu'un bloc de signal plein`() {
        // C'est ce qui garantit que la fenetre du lecteur — dimensionnee sur le plus grand bloc —
        // n'a pas eu a grandir. Une fenetre plus grande, c'est de la memoire en plus sur la montre
        // *et* sur le telephone, pour une donnee qui pese quelques kilo-octets par nuit.
        val telemetriePleine = ChunkFormat.TELEMETRY_HEADER_SIZE +
            ChunkFormat.MAX_TELEMETRY_POINTS * ChunkFormat.TELEMETRY_POINT_SIZE
        val signalPlein = ChunkFormat.BLOCK_HEADER_SIZE +
            ChunkFormat.MAX_SAMPLES_PER_BLOCK * ChunkFormat.BYTES_PER_SAMPLE
        assertThat(telemetriePleine).isLessThanOrEqualTo(signalPlein)
    }

    @Test
    fun `la telemetrie ne fait deborder ni la rotation ni un DataItem`() {
        // Les deux plafonds que la KDoc de `WireProtocol` declare intouchables. La telemetrie
        // s'ecrit sans consulter la condition de rotation — elle ne peut donc que s'ajouter apres
        // coup, et c'est ce depassement-la qu'on borne ici.
        val pointsParRotation =
            (WireProtocol.CHUNK_ROTATION_MS / WireProtocol.TELEMETRY_PERIOD_MS).toInt()
        val pire = WireProtocol.CHUNK_ROTATION_BYTES +
            pointsParRotation * telemetryBlockSize +
            ChunkFormat.FOOTER_SIZE
        // 256 octets de marge pour le `ChunkMeta` et son cadre de longueur, que `DataLayerTransfer`
        // place devant le fichier dans la meme charge utile.
        assertThat(pire + 256).isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES.toLong())
        assertThat(pire - WireProtocol.CHUNK_ROTATION_BYTES).isLessThan(1024L)
    }

    @Test
    fun `un chunk plein de signal accepte encore ses cinq points`() {
        // La verification par les octets reels, et non par l'arithmetique ci-dessus : on remplit
        // un chunk jusqu'au plafond de rotation, on y ajoute les points d'une rotation, et on
        // verifie que le fichier passe toujours dans un `DataItem`.
        val out = ByteArrayOutputStream()
        val w = ChunkWriter(out, header())
        var t = 0L
        val n = ChunkFormat.MAX_SAMPLES_PER_BLOCK
        while (w.bytesWritten + blockSize(n) <= WireProtocol.CHUNK_ROTATION_BYTES) {
            w.writeFlat(n, t)
            t += n * stepNs
        }
        repeat((WireProtocol.CHUNK_ROTATION_MS / WireProtocol.TELEMETRY_PERIOD_MS).toInt()) {
            w.writeTelemetry(point())
        }
        w.finish()

        assertThat(out.size()).isLessThan(WireProtocol.MAX_DATA_ITEM_BYTES)
        val read = ChunkReader.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.scan.complete).isTrue()
        assertThat(read.telemetry).hasSize(5)
        assertThat(read.corruptBlocks).isZero()
    }
}
