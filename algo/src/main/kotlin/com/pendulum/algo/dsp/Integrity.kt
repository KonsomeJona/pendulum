package com.pendulum.algo.dsp

import com.pendulum.algo.model.IntegrityConfig
import com.pendulum.algo.model.IntegrityReport
import com.pendulum.algo.model.IntegrityViolation
import com.pendulum.algo.model.SampleBlock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Constantes du format de bloc **recopiees** ici a dessein.
 *
 * `:algo` ne depend pas de `:format` (§4) : c'est ce qui lui permet de revalider les timestamps
 * sans heriter des garanties — absentes — du CRC de bloc, et de rester testable sans le codec.
 * Le prix a payer est cette duplication de trois entiers. Elle doit rester synchronisee avec
 * `com.pendulum.format.ChunkFormat` ; un test de coherence vit du cote de `:phone`, la ou les deux
 * modules se rencontrent.
 */
object BlockFlags {
    const val FIFO_BOUNDARY = 1 shl 0
    const val GAP_BEFORE = 1 shl 1
    const val OFF_BODY = 1 shl 2

    /** Nombre maximal d'echantillons par bloc du format (`ChunkFormat.MAX_SAMPLES_PER_BLOCK`). */
    const val MAX_SAMPLES_PER_BLOCK = 512

    /** 1 LSB = 1/2048 g : un i16 sature a 32767 LSB, soit 15,9995 g. */
    const val LSB_PER_G = 2048.0

    /** Amplitude de saturation du codec, en g. Un echantillon a cette valeur est suspect. */
    const val SATURATION_G = 32767.0f / LSB_PER_G.toFloat()
}

/**
 * Etape −1 — controles d'integrite. **L'algo ne fait confiance a rien.**
 *
 * Raison d'etre, litteralement (§2, etape −1) : le CRC16 du format ne couvre **que le payload**.
 * `count`, `tFirstNs`, `tLastNs` et `flags` de l'en-tete de bloc ne sont pas proteges. Une base de
 * temps corrompue traverse donc le decodeur en silence — et toute la chaine v2 repose sur les
 * timestamps (estimation de `fs`, reechantillonnage, durees de CLM, IMI). Un seul bloc a
 * timestamp corrompu deplace toute la ligne de temps en aval de plusieurs secondes, ce qui fait
 * basculer de classe tous les evenements aux bornes.
 *
 * Tous les controles sont en O(n) et sans allocation par echantillon.
 *
 * Deux controles de la table de §2 ne sont **pas** implementables ici et ne le seront jamais dans
 * ce module :
 *  - n° 7 (`chunkIndex` strictement croissant, `sessionUuid` constant) : ces champs vivent dans
 *    l'en-tete de **chunk**, pas dans [SampleBlock]. Le controle appartient a l'adaptateur de
 *    `:phone`, qui doit produire [IntegrityViolation.BAD_CHUNK_INDEX] de son cote.
 *  - la partie « coupure de session » du n° 6 : ici on se contente de compter la violation ;
 *    c'est l'etape 0 qui transforme l'ecart en frontiere de segment dure.
 */
object Integrity {

    /**
     * @return les blocs **acceptes** (dans l'ordre d'entree) et le rapport. Tout bloc rejete est
     *   simplement absent de la liste : l'etape 0 le verra comme un trou de sa duree nominale et
     *   lui appliquera la politique de trous, ce qui est exactement ce que demande §2.
     */
    fun check(
        blocks: List<SampleBlock>,
        nominalHz: Double,
        cfg: IntegrityConfig = IntegrityConfig(),
    ): Pair<List<SampleBlock>, IntegrityReport> {
        val counts = LinkedHashMap<IntegrityViolation, Int>()
        for (v in IntegrityViolation.entries) counts[v] = 0
        fun bump(v: IntegrityViolation) {
            counts[v] = (counts[v] ?: 0) + 1
        }

        val accepted = ArrayList<SampleBlock>(blocks.size)
        var rejected = 0
        var longestRejectRun = 0
        var currentRejectRun = 0

        // Collecte pour le controle n° 9 : mediane des normes sur les fenetres statiques.
        val staticNorms = ArrayList<Double>()

        // Intervalle nominal entre deux echantillons, utilise par le controle de chevauchement.
        val nominalStepNs = if (nominalHz > 0.0) 1e9 / nominalHz else 2e7
        val minInterBlockNs = (0.5 * nominalStepNs).toLong()

        var prev: SampleBlock? = null

        for (b in blocks) {
            var ok = true

            // --- 1. Coherence de taille -------------------------------------------------
            val n = b.x.size
            if (n < 1 || n > BlockFlags.MAX_SAMPLES_PER_BLOCK || b.y.size != n || b.z.size != n) {
                bump(IntegrityViolation.BAD_COUNT)
                ok = false
            }

            // --- 2. Ordre et positivite des timestamps ----------------------------------
            if (ok && (b.tFirstNs <= 0L || b.tLastNs <= 0L || b.tFirstNs > b.tLastNs)) {
                bump(IntegrityViolation.BAD_TIMESTAMP_ORDER)
                ok = false
            }

            // --- 3. Cadence implicite du bloc -------------------------------------------
            // Le bloc porte sa propre frequence : (N-1) intervalles entre tFirst et tLast. Un
            // bloc dont la cadence implicite s'ecarte de plus de 20 % du nominal a une base de
            // temps corrompue — ce n'est pas de la derive, la derive d'un quartz est en ppm.
            if (ok && n >= 2) {
                val spanNs = (b.tLastNs - b.tFirstNs).toDouble()
                val fsBlock = if (spanNs > 0.0) (n - 1) * 1e9 / spanNs else Double.POSITIVE_INFINITY
                if (nominalHz > 0.0 && abs(fsBlock - nominalHz) / nominalHz > cfg.maxRateDeviation) {
                    bump(IntegrityViolation.IMPLAUSIBLE_RATE)
                    ok = false
                }
            }

            // --- 4 / 5 / 6. Controles inter-blocs ---------------------------------------
            // Compares au dernier bloc ACCEPTE, pas au dernier bloc vu : sinon un bloc corrompu
            // contaminerait le jugement porte sur son successeur, qui est sain.
            val p = prev
            if (ok && p != null) {
                val delta = b.tFirstNs - p.tLastNs
                if (delta < 0L) {
                    bump(IntegrityViolation.NON_MONOTONIC)
                    ok = false
                } else if (delta < minInterBlockNs) {
                    // Deux blocs qui se touchent a moins d'un demi-echantillon se recouvrent :
                    // les memes instants seraient decrits deux fois, et le reechantillonnage
                    // produirait une discontinuite invisible dans les statistiques.
                    bump(IntegrityViolation.OVERLAP)
                    ok = false
                } else if (delta > cfg.maxGapNs) {
                    // Au-dela de 14 h, ce n'est plus un trou mais une remise a zero d'horloge.
                    // Le bloc reste valide : c'est la SESSION qui est coupee, et l'etape 0
                    // transformera l'ecart en frontiere de segment dure.
                    bump(IntegrityViolation.IMPLAUSIBLE_GAP)
                }
            }

            // --- 8. Jerk impossible ------------------------------------------------------
            // Plus de 8 g d'ecart entre deux echantillons consecutifs a 50 Hz est physiquement
            // impossible a la cheville (cela vaut 400 g/s). C'est la signature du repliement de
            // signe d'une saturation mal implementee : +16 g qui bascule a -16 g. Ce controle
            // reste utile meme apres correction du bug `toRaw` de `:format` — il detecte toute
            // corruption d'octets qui produirait un saut, pas seulement celle-la.
            if (ok && n >= 2) {
                val maxJerk2 = cfg.maxJerkG.toDouble() * cfg.maxJerkG.toDouble()
                var bad = false
                for (i in 1 until n) {
                    val dx = (b.x[i] - b.x[i - 1]).toDouble()
                    val dy = (b.y[i] - b.y[i - 1]).toDouble()
                    val dz = (b.z[i] - b.z[i - 1]).toDouble()
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > maxJerk2) { bad = true; break }
                }
                if (bad) {
                    bump(IntegrityViolation.IMPOSSIBLE_JERK)
                    ok = false
                }
            }

            // --- 10. Saturation ----------------------------------------------------------
            // La cheville n'atteint pas +/-16 g. Un bloc dont plus de `saturationFraction` des
            // echantillons est colle a la butee n'est pas sature, il est corrompu.
            if (ok && n >= 1) {
                var sat = 0
                val lim = BlockFlags.SATURATION_G * (1f - 1e-6f)
                for (i in 0 until n) {
                    if (abs(b.x[i]) >= lim || abs(b.y[i]) >= lim || abs(b.z[i]) >= lim) sat++
                }
                if (sat.toDouble() / n > cfg.saturationFraction) {
                    bump(IntegrityViolation.SATURATED)
                    ok = false
                }
            }

            // --- 11. Coherence de FLAG_GAP_BEFORE ---------------------------------------
            // Incoherence => drapeau, jamais rejet : le drapeau est une information de confort
            // produite par la montre, il n'a pas autorite sur les timestamps.
            if (ok && p != null) {
                val delta = b.tFirstNs - p.tLastNs
                val measuredGap = delta > 1.5 * nominalStepNs
                val declaredGap = (b.flags and BlockFlags.GAP_BEFORE) != 0
                if (measuredGap != declaredGap) bump(IntegrityViolation.FLAG_INCONSISTENT)
            }

            if (ok) {
                accepted.add(b)
                prev = b
                currentRejectRun = 0
                collectStaticNorm(b, staticNorms)
            } else {
                rejected++
                currentRejectRun++
                if (currentRejectRun > longestRejectRun) longestRejectRun = currentRejectRun
            }
        }

        // --- 9. Plausibilite gravitaire, au niveau session -------------------------------
        // Sur les fenetres statiques, la norme mesuree DOIT valoir la gravite. Si la mediane des
        // fenetres statiques sort de [0,80 ; 1,20] g, ce n'est ni du bruit ni un capteur mal
        // calibre (l'autocalibration corrige au plus quelques pourcents) : c'est une echelle
        // fausse ou un decodage desynchronise. La session entiere devient suspecte.
        var gravityBad = false
        if (staticNorms.isNotEmpty()) {
            val med = Numeric.median(staticNorms.toDoubleArray())
            if (med.isNaN() || med < cfg.gravityRangeG.start || med > cfg.gravityRangeG.endInclusive) {
                bump(IntegrityViolation.GRAVITY_IMPLAUSIBLE)
                gravityBad = true
            }
        }

        val total = blocks.size
        val fraction = if (total > 0) rejected.toDouble() / total else 0.0

        // « Le motif de rejets evoque une desynchronisation du decodeur, pas du bruit. »
        // Interpretation retenue, faute de definition dans la specification : le bruit frappe des
        // blocs isoles et au hasard ; une desynchronisation emporte une rafale contigue, ou fait
        // sortir la gravite de sa plage. D'ou les trois criteres ci-dessous.
        val structural = (counts[IntegrityViolation.IMPOSSIBLE_JERK] ?: 0) +
            (counts[IntegrityViolation.SATURATED] ?: 0) +
            (counts[IntegrityViolation.BAD_COUNT] ?: 0)
        val decodeSuspect = gravityBad ||
            longestRejectRun >= 3 ||
            (total > 0 && structural.toDouble() / total > 0.005)

        val report = IntegrityReport(
            blocksTotal = total,
            blocksRejected = rejected,
            byViolation = counts.filterValues { it > 0 },
            rejectedFraction = fraction,
            decodeSuspect = decodeSuspect,
        )
        return accepted to report
    }

    /**
     * Si le bloc est statique (ecart-type < 13 mg sur les trois axes, le meme seuil que
     * l'autocalibration de §3.3), ajoute la mediane de sa norme a [out].
     * Un bloc de 512 echantillons a 50 Hz dure 10,2 s : c'est deja la fenetre de 10 s de §3.3,
     * inutile de re-decouper.
     */
    private fun collectStaticNorm(b: SampleBlock, out: MutableList<Double>) {
        val n = b.x.size
        if (n < 8) return
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var sx2 = 0.0; var sy2 = 0.0; var sz2 = 0.0
        for (i in 0 until n) {
            val x = b.x[i].toDouble(); val y = b.y[i].toDouble(); val z = b.z[i].toDouble()
            sx += x; sy += y; sz += z
            sx2 += x * x; sy2 += y * y; sz2 += z * z
        }
        val vx = sx2 / n - (sx / n) * (sx / n)
        val vy = sy2 / n - (sy / n) * (sy / n)
        val vz = sz2 / n - (sz / n) * (sz / n)
        val sd = 0.013
        if (sqrt(maxOf(0.0, vx)) >= sd || sqrt(maxOf(0.0, vy)) >= sd || sqrt(maxOf(0.0, vz)) >= sd) return
        // Fenetre statique : la norme du vecteur moyen suffit, la dispersion etant negligeable.
        val mx = sx / n; val my = sy / n; val mz = sz / n
        out.add(sqrt(mx * mx + my * my + mz * mz))
    }
}
