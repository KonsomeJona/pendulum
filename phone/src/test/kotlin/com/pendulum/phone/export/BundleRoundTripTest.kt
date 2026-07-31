package com.pendulum.phone.export

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader
import com.pendulum.format.ChunkWriter
import com.pendulum.phone.ingest.SessionReassembler
import com.pendulum.phone.work.AnalysisParams
import com.pendulum.phone.work.NightAnalyzer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * **Le test qui justifie l'existence du bundle.**
 *
 * L'affirmation a prouver : une base reconstruite depuis un bundle donne un resultat identique
 * au bit pres. Sans cette propriete, l'export est un souvenir, pas une sauvegarde — et la
 * promesse « on pourra rescorer quand l'algorithme changera » ne tient plus, puisqu'on ne saurait
 * meme pas reproduire le chiffre d'aujourd'hui.
 *
 * Le chemin exerce est complet : generation d'une nuit synthetique -> ecriture de vrais fichiers
 * de chunks -> analyse -> mise en bundle -> relecture du bundle -> reecriture des fichiers ->
 * seconde analyse -> comparaison champ par champ.
 *
 * Ce test tourne sur JVM parce que ni [NightBundle], ni `SessionReassembler`, ni [NightAnalyzer]
 * ne dependent d'Android. C'est la raison pour laquelle ils n'en dependent pas.
 */
class BundleRoundTripTest {

    @Test
    fun `une nuit reconstruite depuis un bundle donne exactement le meme resultat`(
        @TempDir tmp: File,
    ) {
        val origine = File(tmp, "origine").apply { mkdirs() }
        val restaure = File(tmp, "restaure").apply { mkdirs() }

        val chunks = SyntheticNight.write(origine)
        val avant = analyse(origine)

        // --- Export puis reimport ---------------------------------------------------------
        val bundle = NightBundle.toByteArray(
            NightBundle.Content(
                manifest = mapOf(
                    "sessionHex" to SESSION_HEX,
                    "startWallMs" to "1700000000000",
                    "plannedStopWallMs" to "1700028800000",
                    "zoneId" to "Europe/Paris",
                    "tzOffsetStartMin" to "60",
                    "tzOffsetEndMin" to "60",
                    "nominalRateHz" to "50",
                    "modeFlags" to "0",
                    "state" to "CLOSED",
                    "totalChunks" to "${chunks.size}",
                ),
                context = mapOf("leg" to "RIGHT", "strapId" to "strap-a", "aloneInBed" to "true"),
                baseline = mapOf("paramsHash" to AnalysisParams.DEFAULT.paramsHash),
                hypnogramCsv = "",
                chunks = chunks.mapValues { it.value.readBytes() },
            )
        )

        val relu = NightBundle.read(bundle.inputStream())
        assertThat(relu.sessionHex).isEqualTo(SESSION_HEX)
        for ((idx, bytes) in relu.chunks) {
            // Les octets doivent etre identiques : le bundle transporte le brut, il ne le
            // transcode pas. Un re-encodage, meme sans perte apparente, ferait diverger les
            // valeurs quantifiees et donc le resultat.
            assertThat(bytes).isEqualTo(chunks.getValue(idx).readBytes())
            File(restaure, "%05d.pendulum".format(idx)).writeBytes(bytes)
        }

        val apres = analyse(restaure)

        // --- La comparaison qui compte ------------------------------------------------------
        assertThat(canonique(apres)).isEqualTo(canonique(avant))
    }

    @Test
    fun `deux exports du meme contenu produisent le meme fichier`(@TempDir tmp: File) {
        // Determinisme : sans horodatage fige et sans tri des cles, deux exports du meme fond
        // donneraient deux fichiers differents, et il deviendrait impossible de verifier par
        // simple comparaison qu'un export n'a pas altere son contenu.
        val dir = File(tmp, "nuit").apply { mkdirs() }
        val chunks = SyntheticNight.write(dir).mapValues { it.value.readBytes() }
        val contenu = NightBundle.Content(
            manifest = mapOf("sessionHex" to SESSION_HEX, "zoneId" to "Europe/Paris"),
            context = mapOf("leg" to "RIGHT"),
            baseline = emptyMap(),
            hypnogramCsv = "1:2:4;2:3:5",
            chunks = chunks,
        )
        assertThat(NightBundle.toByteArray(contenu)).isEqualTo(NightBundle.toByteArray(contenu))
    }

    @Test
    fun `le sidecar survit a l'aller-retour, retours a la ligne compris`() {
        val map = mapOf(
            "notes" to "reveil difficile\nsecond paragraphe",
            "leg" to "LEFT",
            "chemin" to """C:\donnees""",
        )
        assertThat(NightBundle.decodeMap(NightBundle.encodeMap(map))).isEqualTo(map)
    }

    // ------------------------------------------------------------------

    private fun analyse(dir: File): NightAnalyzer.Result {
        val night = SessionReassembler.reassemble(
            sessionHex = SESSION_HEX,
            files = dir.listFiles()!!.filter { it.name.endsWith(".pendulum") }.sortedBy { it.name },
            sessionStateClosed = true,
            declaredChunks = null,
        )
        assertThat(night.blocks).isNotEmpty()
        assertThat(night.closedCleanly).isTrue()
        return NightAnalyzer.analyze(
            blocks = night.blocks,
            nominalRateHz = night.nominalRateHz,
            sessionClosedCleanly = night.closedCleanly,
            hcWindows = null,
            diary = null,
            baselineGainG = null,
            params = AnalysisParams.DEFAULT,
        )
    }

    /**
     * Serialisation exhaustive du resultat. On ne compare pas avec `equals` : `PlmiResult.equals`
     * ne regarde que quelques champs, et un test qui passe grace a un `equals` partiel ne prouve
     * rien de ce qu'on veut prouver ici.
     */
    private fun canonique(r: NightAnalyzer.Result): String = buildString {
        appendLine("fs=${r.fsHz}")
        appendLine("analysableMin=${r.analysableMin}")
        appendLine("analysableTstMin=${r.analysableTstMin}")
        appendLine("sampleCount=${r.sampleCount}")
        appendLine("gapCount=${r.gapCount} gapTotalMs=${r.gapTotalMs}")
        appendLine("truncated=${r.truncated} rejected=${r.integrityRejectedFraction}")
        appendLine("gain=${r.calibration.gainCalG} source=${r.calibration.gainSource}")
        appendLine("postures=${r.postures.size}")
        appendLine("clms=${r.clms.size}")
        for (c in r.clms) {
            appendLine(
                "  clm ${c.onsetIdx} ${c.offsetIdx} ${c.onsetMsRel} ${c.durationMs} " +
                    "${c.peakAmpG} ${c.medianAmpG} ${c.noiseFloorG} ${c.thresholdOnG} " +
                    "${c.thresholdOffG} ${c.tiltChangeDeg} ${c.flags} ${c.reject}"
            )
        }
        for ((source, mask) in r.masks) {
            appendLine(
                "mask $source tst=${mask.tstMin} spt=${mask.sptMin} waso=${mask.wasoMin} " +
                    "analysable=${mask.analysableTstMin} conv=${mask.fixedPointConverged} " +
                    "windows=${mask.windows.size}"
            )
        }
        for (p in r.results) {
            appendLine(
                "result ${p.rule}/${p.maskSource} plms=${p.plmsCount} plmw=${p.plmwCount} " +
                    "iso=${p.isolatedCount} short=${p.shortImiCount} tst=${p.tstMin} " +
                    "atst=${p.analysableTstMin} plmi=${p.plmi} plmiSpt=${p.plmiSpt} " +
                    "pi=${p.pi.periodicityIndex}/${p.pi.valid} " +
                    "rhythm=${p.rhythm.fundamentalSec}/${p.rhythm.muLog}/${p.rhythm.sigmaLog}/" +
                    "${p.rhythm.missRate}/${p.rhythm.converged}/${p.rhythm.valid} " +
                    "h1=${p.plmiFirstHalf} h2=${p.plmiSecondHalf} resp=${p.plmiRespWorstCase} " +
                    "gate=${p.gate} indep=${p.independence} hash=${p.paramsHash} " +
                    "hist=${p.imiHistogram.joinToString(",")}"
            )
        }
    }

    private companion object {
        const val SESSION_HEX = "0123456789abcdef0123456789abcdef"
    }
}

/**
 * Une nuit synthetique minimale : gravite constante, bruit deterministe, et une salve de
 * mouvements toutes les 22 secondes.
 *
 * Elle n'a pas vocation a etre realiste — `:algo` a son propre generateur pour ca, avec verite
 * terrain. Ici on a besoin d'un signal **reproductible** qui traverse toute la chaine et produise
 * des evenements : ce qui est teste est l'invariance du resultat a l'aller-retour, pas la
 * justesse de la detection.
 */
private object SyntheticNight {

    const val RATE_HZ = 50
    const val BLOCKS_PER_CHUNK = 30
    const val CHUNKS = 4
    const val SAMPLES_PER_BLOCK = 512
    const val DT_NS = 20_000_000L
    const val T0_NS = 1_000_000_000L
    const val START_WALL_MS = 1_700_000_000_000L

    fun write(dir: File): Map<Int, File> {
        val uuid = ByteArray(16) { it.toByte() }
        val out = LinkedHashMap<Int, File>()
        var sampleIndex = 0L

        for (chunkIdx in 0 until CHUNKS) {
            val firstEventNs = T0_NS + sampleIndex * DT_NS
            val header = ChunkHeader(
                sessionUuid = uuid,
                chunkIndex = chunkIdx,
                nominalRateHz = RATE_HZ,
                startWallMs = START_WALL_MS + sampleIndex * 20,
                startElapsedRealtimeNs = firstEventNs,
                firstEventTimestampNs = firstEventNs,
                sensorResolution = 0.0012f,
                sensorMaxRange = 78.4532f,
                fifoMaxEventCount = 3000,
                modeFlags = ChunkFormat.MODE_WAKEUP_SENSOR or ChunkFormat.MODE_BATCHED,
                tzOffsetMin = 60,
            )
            val file = File(dir, "%05d.pendulum".format(chunkIdx))
            file.outputStream().buffered().use { os ->
                val w = ChunkWriter(os, header)
                repeat(BLOCKS_PER_CHUNK) {
                    val x = FloatArray(SAMPLES_PER_BLOCK)
                    val y = FloatArray(SAMPLES_PER_BLOCK)
                    val z = FloatArray(SAMPLES_PER_BLOCK)
                    val tFirst = T0_NS + sampleIndex * DT_NS
                    for (i in 0 until SAMPLES_PER_BLOCK) {
                        val n = sampleIndex + i
                        val tSec = n / RATE_HZ.toDouble()
                        val burst = burstAt(tSec)
                        x[i] = (burst + noise(n)).toFloat()
                        y[i] = noise(n + 7_919).toFloat()
                        z[i] = (ChunkFormat.G_IN_MS2 + noise(n + 104_729)).toFloat()
                    }
                    w.writeBlock(
                        x, y, z, SAMPLES_PER_BLOCK,
                        tFirst, tFirst + (SAMPLES_PER_BLOCK - 1) * DT_NS, 0,
                    )
                    sampleIndex += SAMPLES_PER_BLOCK
                }
                w.finish()
            }
            out[chunkIdx] = file
        }
        return out
    }

    /** Salve de 1 s a 3 Hz toutes les 22 s, fenetree pour ne pas creer de discontinuite. */
    private fun burstAt(tSec: Double): Double {
        val phase = tSec % 22.0
        if (phase >= 1.0) return 0.0
        val window = 0.5 * (1.0 - kotlin.math.cos(2 * PI * phase))
        return 3.0 * window * sin(2 * PI * 3.0 * phase)
    }

    /** Bruit deterministe : meme entree, meme sortie, sans quoi le test ne prouverait rien. */
    private fun noise(n: Long): Double {
        var h = n * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        h = h xor (h ushr 33)
        h *= -49_064_778_989_728_563L
        h = h xor (h ushr 29)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5) * 0.02
    }
}
