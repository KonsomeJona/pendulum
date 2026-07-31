package com.pendulum.algo.indices

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Tests de la deconvolution des harmoniques (`SPEC-v2.md` §5.3).
 *
 * Les tolerances ne sont pas choisies apres coup : elles viennent de la spec (periode a mieux que
 * 5 %, taux de manques a mieux que 0,1) et les marges reellement observees sur simulation sont
 * environ trois fois meilleures.
 */
class RhythmTest {

    private val fundamentalSec = 22.0

    @Test
    fun `intervalles parfaitement periodiques - aucun manque et periode exacte`() {
        val imi = DoubleArray(200) { fundamentalSec }

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isTrue()
        assertThat(fit.result.converged).isTrue()
        assertThat(fit.result.fundamentalSec).isCloseTo(fundamentalSec, within(1e-6))
        assertThat(fit.result.missRate).isLessThan(0.01)
        assertThat(fit.result.alternationSuspect).isFalse()
        assertThat(fit.reject).isNull()
        // Toute la masse sur la composante fondamentale : aucun harmonique n'est invente.
        assertThat(fit.componentShare[0]).isCloseTo(1.0, within(1e-9))
        // La statistique KS est degeneree quand la dispersion l'est ; le garde-fou doit donc etre
        // neutralise, sinon une nuit parfaitement periodique serait declaree non interpretable.
        assertThat(fit.result.sigmaLog).isLessThan(RhythmConfig().ksMinSigma)
    }

    @Test
    fun `39 pourcent de manques - periode retrouvee a mieux que 5 pourcent et taux a mieux que 0,1`() {
        for (seed in longArrayOf(11L, 12L, 13L)) {
            val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.39, 4000, seed)

            val fit = Rhythm.fit(imi)

            assertThat(fit.result.valid).`as`("seed %s valide", seed).isTrue()
            assertThat(fit.result.converged).isTrue()
            assertThat(abs(fit.result.fundamentalSec - fundamentalSec) / fundamentalSec)
                .`as`("seed %s : erreur relative de periode", seed)
                .isLessThan(0.05)
            assertThat(abs(fit.result.missRate - 0.39))
                .`as`("seed %s : erreur sur le taux de manques", seed)
                .isLessThan(0.10)
            // Les manques etant ici VRAIMENT independants, la mesure d'adequation doit rester basse.
            assertThat(fit.geometricMisfit).isLessThan(RhythmConfig().maxGeometricMisfit)
        }
    }

    @Test
    fun `la moyenne brute du log est biaisee de +0,357 nats, la deconvolution ne l'est pas`() {
        val cfg = RhythmConfig()
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.39, 4000, 12L)
        val kept = imi.filter { it >= cfg.minIntervalSec && it <= cfg.maxIntervalSec }
        val rawLogMean = kept.sumOf { ln(it) } / kept.size

        val fit = Rhythm.fit(imi, cfg)

        // C'est le coeur de l'argument du §5.3 : la moyenne brute du log est decalee d'environ
        // +0,357 nats (intervalle x1,43), soit 3,3 fois la variabilite nuit a nuit publiee.
        assertThat(rawLogMean - ln(fundamentalSec)).isBetween(0.28, 0.45)
        // La deconvolution, elle, retombe sur la periode vraie.
        assertThat(abs(fit.result.muLog - ln(fundamentalSec))).isLessThan(0.06)
        // Et le biais theorique tabule dans la spec est bien celui-la.
        assertThat(Rhythm.rawLogBias(0.39)).isCloseTo(0.357, within(0.002))
    }

    @Test
    fun `le biais theorique reproduit le tableau de la SPEC`() {
        assertThat(Rhythm.rawLogBias(0.20)).isCloseTo(0.158, within(0.002))
        assertThat(Rhythm.rawLogBias(0.30)).isCloseTo(0.255, within(0.002))
        assertThat(Rhythm.rawLogBias(0.50)).isCloseTo(0.508, within(0.002))
        // La SPEC annonce +0,901 pour p = 0,70 ; la valeur exacte de la serie est +0,9146
        // (x2,50 et non x2,46). A p = 0,70 la serie converge lentement et 0,901 correspond a une
        // somme arretee vers k = 15. Les quatre autres lignes du tableau sont exactes.
        assertThat(Rhythm.rawLogBias(0.70)).isCloseTo(0.9146, within(0.001))
        assertThat(exp(Rhythm.rawLogBias(0.39))).isCloseTo(1.43, within(0.01))
        assertThat(Rhythm.rawLogBias(0.0)).isEqualTo(0.0)
    }

    @Test
    fun `alternance gauche-droite - un mouvement sur deux vu leve le drapeau`() {
        // Lateralisation stochastique : chaque mouvement de la serie vraie est vu une fois sur deux.
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.50, 4000, 21L)

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isTrue()
        assertThat(fit.result.alternationSuspect).isTrue()
        assertThat(fit.result.missRate).isGreaterThan(0.45)
        // La periode FONDAMENTALE reste celle des deux jambes reunies : c'est tout l'interet de la
        // deconvolution, une lecture naive aurait annonce 44 s.
        assertThat(abs(fit.result.fundamentalSec - fundamentalSec) / fundamentalSec).isLessThan(0.08)
    }

    @Test
    fun `39 pourcent de manques mecaniques ne levent PAS le drapeau d'alternance`() {
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.22, 0.39, 6000, 13L)

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isTrue()
        assertThat(fit.result.alternationSuspect).isFalse()
        assertThat(fit.componentShare[0]).isGreaterThan(0.55)
    }

    @Test
    fun `trop peu d'intervalles - aucun resultat n'est produit`() {
        val imi = DoubleArray(20) { 22.0 + it * 0.1 }

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isFalse()
        assertThat(fit.result.converged).isFalse()
        assertThat(fit.result.intervalsUsed).isEqualTo(20)
        assertThat(fit.reject).isEqualTo(RhythmReject.TOO_FEW_INTERVALS)
        // NaN et non zero : « pas de valeur » doit empoisonner visiblement tout calcul aval.
        assertThat(fit.result.fundamentalSec.isNaN()).isTrue()
        assertThat(fit.result.missRate.isNaN()).isTrue()
        assertThat(fit.result.harmonicWeights).isEmpty()
    }

    @Test
    fun `la fenetre de selection ecarte les fragments intra-salve et les pauses inter-series`() {
        val cfg = RhythmConfig()
        val imi = DoubleArray(120) {
            when {
                it % 10 == 0 -> 2.5      // fragment intra-salve, sous minIntervalSec
                it % 10 == 5 -> 600.0    // pause entre deux series, au-dessus de maxIntervalSec
                else -> 22.0
            }
        }

        val fit = Rhythm.fit(imi, cfg)

        assertThat(fit.result.intervalsUsed).isEqualTo(96)
        assertThat(fit.result.fundamentalSec).isCloseTo(22.0, within(1e-6))
    }

    @Test
    fun `des manques agglomeres, non geometriques, invalident le resultat`() {
        // Reserve du §5.3 codee, pas seulement documentee : l'accelerometre rate d'abord les
        // mouvements de faible amplitude, donc les manques peuvent s'agglomerer et creuser le pic
        // 2x. On fabrique ici le cas extreme — un intervalle sur deux vaut 4 fois le fondamental,
        // AUCUN ne vaut 2 fois — et l'on exige un refus plutot qu'un chiffre faux a l'air sur.
        val rnd = java.util.Random(7L)
        val imi = DoubleArray(1000) {
            val base = 22.0 * exp(0.20 * rnd.nextGaussian())
            if (it % 2 == 0) base else base * 4.0
        }

        val fit = Rhythm.fit(imi)

        assertThat(fit.result.valid).isFalse()
        assertThat(fit.reject)
            .isIn(RhythmReject.GEOMETRIC_MISFIT, RhythmReject.DISTRIBUTION_MISFIT)
        assertThat(fit.geometricMisfit).isGreaterThan(RhythmConfig().maxGeometricMisfit)
    }

    @Test
    fun `deux executions sur la meme entree sont identiques au bit`() {
        val imi = simulateObservedIntervalsSec(fundamentalSec, 0.25, 0.42, 3000, 99L)

        val a = Rhythm.fit(imi)
        val b = Rhythm.fit(imi.copyOf())

        assertThat(a).isEqualTo(b)
        assertThat(a.result.muLog.toRawBits()).isEqualTo(b.result.muLog.toRawBits())
        assertThat(a.result.sigmaLog.toRawBits()).isEqualTo(b.result.sigmaLog.toRawBits())
        assertThat(a.result.missRate.toRawBits()).isEqualTo(b.result.missRate.toRawBits())
        assertThat(a.result.fundamentalSec.toRawBits()).isEqualTo(b.result.fundamentalSec.toRawBits())
        assertThat(a.logLikelihood.toRawBits()).isEqualTo(b.logLikelihood.toRawBits())
        assertThat(a.iterations).isEqualTo(b.iterations)
        for (i in a.result.harmonicWeights.indices) {
            assertThat(a.result.harmonicWeights[i].toRawBits())
                .isEqualTo(b.result.harmonicWeights[i].toRawBits())
        }
    }

    @Test
    fun `les poids sont ceux d'une geometrique tronquee et somment a un`() {
        val k = 5
        val w = Rhythm.geometricWeights(0.4, k)

        assertThat(w.sum()).isCloseTo(1.0, within(1e-12))
        for (i in 1 until k) assertThat(w[i]).isLessThan(w[i - 1])
        assertThat(w[1] / w[0]).isCloseTo(0.4, within(1e-12))
        assertThat(Rhythm.geometricWeights(0.0, k)[0]).isCloseTo(1.0, within(1e-12))
    }

    @Test
    fun `l'estimation de p inverse exactement le rang moyen du modele`() {
        for (p in doubleArrayOf(0.0, 0.05, 0.2, 0.39, 0.5, 0.7, 0.85)) {
            val m = Rhythm.meanRank(p, 5)
            assertThat(Rhythm.solveMissRate(m, 5)).isCloseTo(p, within(1e-9))
        }
        // Hors domaine : un rang moyen de 1 signifie « aucun manque ».
        assertThat(Rhythm.solveMissRate(1.0, 5)).isEqualTo(0.0)
        assertThat(Rhythm.solveMissRate(0.5, 5)).isEqualTo(0.0)
    }
}
