package com.pendulum.algo.detect

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.TriAxial
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ClmDetectorTest {

    private fun detect(
        m: FloatArray,
        gravity: TriAxial = flatGravity(m.size),
        postures: List<PostureChange> = emptyList(),
    ): List<Clm> = ClmDetector.detect(
        env = dualEnvelope(m),
        floor = constantFloor(m.size),
        floorExtrapolated = noExtrapolation(m.size),
        gravity = gravity,
        segments = wholeNight(m.size),
        blindZones = emptyList(),
        postures = postures,
        calibration = noCalibration(),
    )

    @Test
    @DisplayName("un mouvement synthetique simple est detecte et correctement date")
    fun simpleMovement() {
        // Plancher 5 mg -> Theta_on = max(8x5, 20, 0) = 40 mg, Theta_off = max(2.5x5, 6.25) = 12.5 mg.
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 2.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        val e = clms.single()
        assertThat(e.isClm).isTrue()
        assertThat(e.reject).isNull()
        assertThat(e.onsetMsRel).isBetween(9_800L, 10_150L)
        assertThat(e.durationMs).isBetween(1_900, 2_400)
        assertThat(e.peakAmpG).isCloseTo(0.15f, Offset.offset(0.01f))
        assertThat(e.thresholdOnG).isCloseTo(0.04f, Offset.offset(1e-6f))
        assertThat(e.thresholdOffG).isCloseTo(0.0125f, Offset.offset(1e-6f))
    }

    @Test
    @DisplayName("deux bouffees separees de moins de 0,5 s fusionnent en un seul mouvement")
    fun closeBurstsMerge() {
        // §1.5-iv : aucune etape de fusion dediee. C'est la definition de l'offset (rester sous
        // Theta_off pendant 0,5 s) qui empeche mecaniquement deux evenements d'etre plus proches.
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 1.0, ampG = 0.15f)
        burst(m, startSec = 11.3, durSec = 1.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        assertThat(clms.single().isClm).isTrue()
        assertThat(clms.single().durationMs).isBetween(2_200, 2_600)
    }

    @Test
    @DisplayName("deux bouffees separees de plus de 0,5 s restent deux mouvements")
    fun distantBurstsStaySeparate() {
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 1.0, ampG = 0.15f)
        burst(m, startSec = 12.5, durSec = 1.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(2)
        assertThat(clms).allMatch { it.isClm }
        assertThat(clms[1].onsetMsRel - clms[0].onsetMsRel).isBetween(2_400L, 2_600L)
    }

    @Test
    @DisplayName("un mouvement de plus de 10 s est marque LM_LONG et n'est jamais un CLM")
    fun longMovementIsNeverAClm() {
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 12.0, ampG = 0.15f)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        val e = clms.single()
        assertThat(e.durationMs).isGreaterThan(10_000)
        assertThat(e.flags and ClmFlags.LM_LONG).isNotZero()
        assertThat(e.isClm).isFalse()
    }

    @Test
    @DisplayName("une sonnerie breve de type matelas est rejetee par le critere de morphologie")
    fun mattressRingRejectedByMorphology() {
        // §3.2 : une vibration transmise a une crete elevee mais aucune periode de 0,5 s dont la
        // mediane depasse Theta_off. Le tilt ne bouge pas non plus -> TRANSMITTED_SUSPECT.
        //
        // La sonnerie fait 0,15 s, pas 0,25 s, et ce n'est pas un detail de confort. Le critere
        // WASM 3.2.1-d exige une fenetre de `morphologyWinSec = 0,50 s` **contenue dans
        // l'evenement**, et il compare la mediane de `env_f` a `Theta_off = 0,3125 x Theta_on`.
        // Or l'evenement detecte fait toujours ~0,24 s de plus que la sonnerie physique (le front
        // montant est date sur `env_c`, dont la fenetre de 0,50 s est **centree** — §7.2 du dossier
        // d'algorithme : « the coarse onset leads the physical start by up to 12 samples »), et
        // `env_f` etale encore la sonnerie de 0,15 s de plus. Pour une sonnerie de 0,25 s
        // (13 echantillons), l'evenement fait 26 echantillons, la seule fenetre de morphologie
        // disponible est centree sur la sonnerie, et `env_f` y est non nul sur 21 echantillons
        // sur 25 : la mediane vaut alors 0,785 x la crete de `env_c`, tres au-dessus du rapport
        // d'hysteresis 0,3125. Le critere ne peut donc **jamais** rejeter une sonnerie de 0,25 s,
        // quelle que soit son amplitude — la conclusion de §3.2 (« une sonnerie de 0,3 s a une
        // crete elevee mais une mediane sur 0,5 s faible ») ne vaut que sous ~0,17 s.
        // 0,15 s reste dans la plage 0,05-0,40 s de la famille 4 du tableau §5.2.
        val m = quietMagnitude(60.0)
        burst(m, startSec = 10.0, durSec = 0.15, ampG = 0.30f, rampSec = 0.05)

        val clms = detect(m)

        assertThat(clms).hasSize(1)
        val e = clms.single()
        assertThat(e.isClm).isFalse()
        assertThat(e.reject).isEqualTo(ClmRejectReason.MORPHOLOGY)
        assertThat(e.flags and ClmFlags.TRANSMITTED_SUSPECT).isNotZero()
    }

    @Test
    @DisplayName("un changement de posture ne produit aucun CLM")
    fun postureProducesNoClm() {
        // Le transitoire de posture est 5 a 30 fois plus gros qu'un CLM et dure exactement la bonne
        // duree pour etre compte : sans le detecteur de posture, il serait compte comme un CLM.
        val n = samples(60.0)
        val gravity = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 90.0)
        val m = quietMagnitude(60.0)
        burst(m, startSec = 30.0, durSec = 2.0, ampG = 0.50f)

        val postures = PostureDetector.detect(gravity, wholeNight(n))
        assertThat(postures).hasSize(1)

        val clms = detect(m, gravity, postures)

        assertThat(clms).hasSize(1)
        assertThat(clms).noneMatch { it.isClm }
        assertThat(clms.single().flags and ClmFlags.POSTURAL).isNotZero()
        assertThat(clms.single().reject).isEqualTo(ClmRejectReason.POSTURAL)
    }

    @Test
    @DisplayName("sans l'entree posture, le meme transitoire n'est plus arrete que par le tilt")
    fun sameTransientWithoutPostureInput() {
        val n = samples(60.0)
        val gravity = rotatingGravity(n, startSec = 30.0, durSec = 1.0, deg = 90.0)
        val m = quietMagnitude(60.0)
        burst(m, startSec = 30.0, durSec = 2.0, ampG = 0.10f)

        val clms = detect(m, gravity, postures = emptyList())

        // Sans l'entree posture il reste rejete, mais par le seul critere de tilt (GBM) : c'est la
        // seconde ligne de defense, et elle ne couvre pas les rotations sous `postureDeg`.
        assertThat(clms).hasSize(1)
        assertThat(clms.single().tiltChangeDeg).isGreaterThan(80f)
        assertThat(clms.single().reject).isEqualTo(ClmRejectReason.GROSS_BODY)
    }
}
