package com.pendulum.algo.dsp

import com.pendulum.algo.model.IntegrityViolation
import com.pendulum.algo.model.SimpleBlock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntegrityTest {

    private val fs = 50.0
    private val stepNs = (1e9 / fs).toLong()

    private fun sane(tStartNs: Long, n: Int = 100): SimpleBlock {
        val span = Math.round((n - 1) * 1e9 / fs)
        return SimpleBlock(
            tStartNs, tStartNs + span, 0,
            FloatArray(n), FloatArray(n), FloatArray(n) { 1f },
        )
    }

    @Test
    fun `une session saine passe sans rejet`() {
        var t = 1_000_000_000L
        val blocks = (0 until 20).map { sane(t).also { b -> t = b.tLastNs + stepNs } }
        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(20)
        assertThat(report.blocksRejected).isZero()
        assertThat(report.acceptable).isTrue()
    }

    @Test
    fun `un repliement de saturation est detecte par le jerk meme si le bloc n est pas sature`() {
        var t = 1_000_000_000L
        val blocks = ArrayList<SimpleBlock>()
        repeat(5) { blocks.add(sane(t).also { t = it.tLastNs + stepNs }) }
        val n = 100
        val x = FloatArray(n)
        // Bascule +16 g -> -16 g entre deux echantillons : signature du bug `toRaw` de :format.
        x[50] = 15.99f
        x[51] = -15.99f
        blocks.add(SimpleBlock(t, t + Math.round((n - 1) * 1e9 / fs), 0, x, FloatArray(n), FloatArray(n) { 1f }))

        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(5)
        assertThat(report.byViolation[IntegrityViolation.IMPOSSIBLE_JERK]).isEqualTo(1)
    }

    @Test
    fun `un bloc majoritairement colle a la butee est corrompu et non sature`() {
        var t = 1_000_000_000L
        val blocks = ArrayList<SimpleBlock>()
        repeat(5) { blocks.add(sane(t).also { t = it.tLastNs + stepNs }) }
        val n = 100
        // Butee constante : aucun saut entre voisins, donc le controle de jerk laisse passer.
        // C'est bien le controle de saturation qui doit attraper le bloc.
        val z = FloatArray(n) { BlockFlags.SATURATION_G }
        blocks.add(SimpleBlock(t, t + Math.round((n - 1) * 1e9 / fs), 0, FloatArray(n), FloatArray(n), z))

        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(5)
        assertThat(report.byViolation[IntegrityViolation.SATURATED]).isEqualTo(1)
        assertThat(report.byViolation[IntegrityViolation.IMPOSSIBLE_JERK] ?: 0).isZero()
    }

    @Test
    fun `un timestamp qui recule est rejete`() {
        val b0 = sane(1_000_000_000L)
        val b1 = sane(b0.tLastNs - 5_000_000L) // demarre avant la fin du precedent
        val (kept, report) = Integrity.check(listOf(b0, b1), fs)
        assertThat(kept).containsExactly(b0)
        assertThat(report.byViolation[IntegrityViolation.NON_MONOTONIC]).isEqualTo(1)
    }

    @Test
    fun `deux blocs separes de moins d un demi-echantillon se recouvrent`() {
        val b0 = sane(1_000_000_000L)
        val b1 = sane(b0.tLastNs + 5_000_000L) // 5 ms, soit un quart d'echantillon a 50 Hz
        val (kept, report) = Integrity.check(listOf(b0, b1), fs)
        assertThat(kept).containsExactly(b0)
        assertThat(report.byViolation[IntegrityViolation.OVERLAP]).isEqualTo(1)
    }

    @Test
    fun `une cadence de bloc a plus de 20 pourcent du nominal est rejetee`() {
        val b0 = sane(1_000_000_000L)
        val n = 100
        val bad = SimpleBlock(
            b0.tLastNs + stepNs, b0.tLastNs + stepNs + Math.round((n - 1) * 1e9 / 30.0), 0,
            FloatArray(n), FloatArray(n), FloatArray(n) { 1f },
        )
        val (kept, report) = Integrity.check(listOf(b0, bad), fs)
        assertThat(kept).containsExactly(b0)
        assertThat(report.byViolation[IntegrityViolation.IMPLAUSIBLE_RATE]).isEqualTo(1)
    }

    @Test
    fun `une gravite implausible rend la session suspecte sans rejeter de bloc`() {
        var t = 1_000_000_000L
        // Norme statique a 0,5 g : echelle fausse ou decodage desynchronise.
        val blocks = (0 until 10).map {
            val n = 100
            val b = SimpleBlock(t, t + Math.round((n - 1) * 1e9 / fs), 0, FloatArray(n), FloatArray(n), FloatArray(n) { 0.5f })
            t = b.tLastNs + stepNs
            b
        }
        val (kept, report) = Integrity.check(blocks, fs)
        assertThat(kept).hasSize(10)
        assertThat(report.byViolation[IntegrityViolation.GRAVITY_IMPLAUSIBLE]).isEqualTo(1)
        assertThat(report.decodeSuspect).isTrue()
        assertThat(report.acceptable).isFalse()
    }
}
