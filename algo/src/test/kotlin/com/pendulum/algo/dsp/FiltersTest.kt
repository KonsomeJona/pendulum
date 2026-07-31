package com.pendulum.algo.dsp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FiltersTest {

    private val fs = 50.0

    @Test
    fun `le coude d un Butterworth passe-bas est a moins 3 dB`() {
        val lp = Filters.butterLowpass(fs, 8.0, 2)
        assertThat(Filters.magnitudeAt(lp, 8.0)).isCloseTo(0.7071, within(1e-3))
        assertThat(Filters.magnitudeAt(lp, 0.5)).isCloseTo(1.0, within(2e-3))
    }

    @Test
    fun `le passe-bande laisse passer 2 Hz et rejette la posture et le haut du spectre`() {
        val bp = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        assertThat(Filters.magnitudeAt(bp, 2.0)).isGreaterThan(0.95)
        // 0,25 Hz : la bande de la respiration. Le tableau de `docs/fr/ALGO-v2.md` §1.2 chiffre les
        // deux options et **retient l'ordre 2** : |H| = 0,243 (-12,3 dB) a l'ordre 2, 0,062
        // (-24,1 dB) a l'ordre 4. L'ordre 4 est explicitement rejete (sonnerie de posture 2 s -> 4 s),
        // et `hpOrder = 2` est la valeur publiee en §6.1. C'est donc 0,243 qu'il faut asserter ici ;
        // exiger < 0,07 revenait a asserter la ligne « ordre 4 » sur un filtre d'ordre 2.
        assertThat(Filters.magnitudeAt(bp, 0.25)).isCloseTo(0.243, within(2e-3))
        // Le gain a 0,7 Hz (composante d'un CLM lent) doit rester quasi intact. Valeur exacte de
        // l'ordre 2 : (f/fc)^2 / sqrt(1 + (f/fc)^4) = 1,96 / 2,2004 = 0,891. Le tableau §1.2 portait
        // 0,915 sur cette case ; c'etait faux, et le tableau a ete corrige (07-validation.md §5.4),
        // ce qui permet d'asserter la valeur exacte plutot qu'une borne lache.
        assertThat(Filters.magnitudeAt(bp, 0.7)).isCloseTo(0.891, within(2e-3))
        // 20 Hz : haut du spectre. Aucune case du tableau §1.2 ne chiffre ce point ; la borne 0,03
        // etait un chiffre rond, et le filtre publie ne la tient pas. Valeur exacte du passe-bas
        // numerique d'ordre 2 obtenu par transformation bilineaire prewarpee :
        //   |H| = 1 / sqrt(1 + (tan(pi.20/50) / tan(pi.8/50))^4) = 1 / sqrt(1 + 5,598^4) = 0,0319.
        // Ce n'est pas la valeur analogique (0,158) : c'est la distorsion de frequence de la
        // bilineaire pres de Nyquist, et elle joue **en faveur** de la rejection. La borne est donc
        // relachee a 0,035, ce qui laisse voir une regression de conception sans sur-contraindre.
        assertThat(Filters.magnitudeAt(bp, 20.0)).isLessThan(0.035)
    }

    @Test
    fun `resetToDc supprime le transitoire de 1 g du passe-bas gravite`() {
        val lp = Filters.butterLowpass(fs, 0.15, 2)
        lp.resetToDc(1.0f)
        // Entree constante a 1 g : la sortie doit valoir 1 g des le premier echantillon.
        for (i in 0 until 100) {
            assertThat(lp.step(1.0f).toDouble()).isCloseTo(1.0, within(1e-6))
        }

        val naive = Filters.butterLowpass(fs, 0.15, 2)
        naive.reset()
        // Sans amorcage, le premier echantillon est quasi nul : un transitoire de la taille de
        // la gravite, soit 5 a 30 fois l'amplitude d'un vrai CLM.
        assertThat(naive.step(1.0f)).isLessThan(0.01f)
    }

    @Test
    fun `le filtrage en flux est identique quel que soit le decoupage en blocs`() {
        val n = 3000
        val x = FloatArray(n) { 1.0f + 0.05f * sin(2 * PI * 2.0 * it / fs).toFloat() }

        val whole = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        whole.resetToDc(1.0f)
        val ref = whole.process(x)

        // Meme filtre, meme etat, mais alimente par blocs de 512 echantillons.
        val streamed = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        streamed.resetToDc(1.0f)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val end = minOf(n, i + 512)
            for (k in i until end) out[k] = streamed.step(x[k])
            i = end
        }
        assertThat(out).isEqualTo(ref) // bit-identique : l'etat ne se perd jamais
    }

    @Test
    fun `reinitialiser le filtre a chaque bloc fabrique un artefact periodique`() {
        // C'est le piege n° 3 de la v1 : le transitoire se repete a la cadence des blocs et
        // ressemble a une serie PLM parfaite. Le test verifie qu'on sait le mesurer, donc que
        // l'implementation stateful n'est pas une precaution decorative.
        val n = 3000
        val blockLen = 512
        val x = FloatArray(n) { 1.0f + 0.05f * sin(2 * PI * 2.0 * it / fs).toFloat() }

        val whole = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        whole.resetToDc(1.0f)
        val ref = whole.process(x)

        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val end = minOf(n, i + blockLen)
            val perBlock = Filters.butterBandpass(fs, 0.50, 8.0, 2)
            perBlock.reset() // etat nul a chaque bloc : la faute a ne pas commettre
            for (k in i until end) out[k] = perBlock.step(x[k])
            i = end
        }
        // L'erreur maximale est du meme ordre que la gravite elle-meme, soit des dizaines de
        // fois l'amplitude du signal utile (50 mg ici).
        var maxErr = 0.0
        for (k in 0 until n) maxErr = maxOf(maxErr, abs(out[k] - ref[k]).toDouble())
        assertThat(maxErr).isGreaterThan(0.5)
    }

    @Test
    fun `snapshot et restore rendent le filtre reprenable a l identique`() {
        val a = Filters.butterHighpass(fs, 0.5, 2)
        a.resetToDc(1f)
        repeat(200) { a.step(1f + 0.01f * it) }
        val state = a.snapshot()

        val b = Filters.butterHighpass(fs, 0.5, 2)
        b.restore(state)
        for (k in 0 until 100) {
            val v = 2f + 0.01f * k
            assertThat(b.step(v)).isEqualTo(a.step(v))
        }
    }

    @Test
    fun `le temps d etablissement annonce reste sous le warmup de la specification`() {
        val bp = Filters.butterBandpass(fs, 0.50, 8.0, 2)
        assertThat(bp.settlingTimeSec).isLessThan(5.0) // warmupSec = 5,0 s (§6.1)
    }
}
