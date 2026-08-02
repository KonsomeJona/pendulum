package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les quatre branches de [SensorStrategy.decide] et leurs bornes exactes.
 *
 * Ces tests existent parce que la KDoc de `SensorStrategy` promet qu'ils existent. Mais la vraie
 * raison est ailleurs : chaque branche engage un cout de batterie different pour toute une nuit
 * (wake lock ou pas, reveils du SoC frequents ou pas), et la frontiere entre deux branches est un
 * simple comparateur sur `fifoReservedEventCount`. Un `>` qui devient `>=` — ou l'inverse — ne
 * plante rien : il change silencieusement le mode d'acquisition d'un appareil donne, et la panne
 * apparait des semaines plus tard sous la forme « la batterie ne tient plus la nuit ».
 */
class SensorStrategyTest {

    // -------------------------------------------------------------------------------------
    // Branche 1 : wake-up avec FIFO garanti suffisant
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("wake-up avec exactement 500 evenements garantis : le batching budgete est choisi")
    fun `borne exacte de la branche wake-up batchee`() {
        // rateHz = 20 pour que la latence calculee (12,5 s) se distingue du plancher (10 s) :
        // a 50 Hz les branches 1 et 2 produisent le meme resultat et la borne serait invisible.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 500, rateHz = 20)

        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(mode.maxReportLatencyUs)
            .withFailMessage(
                "A la borne fifoReserved = RESERVED_FOR_WAKEUP_BATCH la latence doit etre " +
                    "calculee sur la moitie de la part garantie (0,5 x 500 / 20 Hz = 12,5 s), " +
                    "pas retombee au plancher : sinon la borne a glisse et des capteurs juste " +
                    "au seuil perdent leur budget de batching. Obtenu : %d us.",
                mode.maxReportLatencyUs,
            )
            .isEqualTo(12_500_000)
        assertThat(mode.needsWakeLock).isFalse()
        assertThat(mode.wakeUpSensor).isTrue()
    }

    @Test
    @DisplayName("la latence ne descend jamais sous 10 s : on ne reveille pas le SoC plus souvent")
    fun `plancher de latence`() {
        // 0,5 x 500 / 50 Hz = 5 s, sous le plancher : la valeur doit remonter a 10 s.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 500, rateHz = 50)
        assertThat(mode.maxReportLatencyUs).isEqualTo(10_000_000)
    }

    @Test
    @DisplayName("la latence ne depasse jamais 60 s, meme avec un FIFO enorme")
    fun `plafond de latence`() {
        // 0,5 x 12000 / 50 Hz = 120 s : sans plafond, la premiere salve arriverait deux minutes
        // apres le coucher et l'apercu temps reel serait juge mort par l'utilisateur.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 12_000, rateHz = 50)
        assertThat(mode.maxReportLatencyUs).isEqualTo(60_000_000)
    }

    @Test
    @DisplayName("entre plancher et plafond, la latence vaut la moitie de la part garantie")
    fun `latence budgetee sans ecretage`() {
        // 0,5 x 3000 / 50 Hz = 30 s : la marge de 50 % absorbe une derive de fs sans jamais
        // toucher le plafond du FIFO, donc sans jamais perdre d'evenement.
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)
        assertThat(mode.maxReportLatencyUs).isEqualTo(30_000_000)
        assertThat(mode.needsWakeLock).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Branche 2 : wake-up avec FIFO maigre
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("wake-up a 499 evenements garantis : batching au plancher, toujours sans wake lock")
    fun `wake-up sous la borne`() {
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 499, rateHz = 20)

        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(mode.maxReportLatencyUs).isEqualTo(10_000_000)
        // Le contrat HAL wake-up garantit deja qu'aucun evenement n'est perdu : prendre un wake
        // lock ici serait payer deux fois la meme assurance, toute la nuit.
        assertThat(mode.needsWakeLock)
            .withFailMessage(
                "Un capteur wake-up ne doit jamais exiger de wake lock : le contrat HAL reveille " +
                    "le SoC avant toute perte. Un wake lock ici double le cout batterie pour rien.",
            )
            .isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Branche 3 : non-wake-up avec grand FIFO
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("non-wake-up avec exactement 3000 evenements garantis : batching sous wake lock")
    fun `borne exacte de la branche non-wake-up batchee`() {
        val mode = SensorStrategy.decide(isWakeUp = false, fifoReserved = 3_000, rateHz = 50)

        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKELOCK)
        assertThat(mode.maxReportLatencyUs).isEqualTo(20_000_000)
        assertThat(mode.needsWakeLock).isTrue()
        assertThat(mode.wakeUpSensor).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Branche 4 : non-wake-up avec FIFO court
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("non-wake-up a 2999 : le continu sous wake lock est le seul mode sans perte")
    fun `non-wake-up sous la borne`() {
        val mode = SensorStrategy.decide(isWakeUp = false, fifoReserved = 2_999, rateHz = 50)

        // La perte en suspend d'un non-wake-up au FIFO court est documentee par le HAL, pas
        // hypothetique : batcher ici, c'est accepter des trous dans chaque nuit.
        assertThat(mode.kind).isEqualTo(AcquisitionKind.CONTINUOUS_WAKELOCK)
        assertThat(mode.maxReportLatencyUs).isEqualTo(0)
        assertThat(mode.needsWakeLock).isTrue()
    }

    @Test
    @DisplayName("une cadence nulle ou negative est un defaut d'appel, jamais un mode par defaut")
    fun `cadence invalide rejetee`() {
        assertThatIllegalArgumentException().isThrownBy {
            SensorStrategy.decide(isWakeUp = true, fifoReserved = 500, rateHz = 0)
        }
    }

    // -------------------------------------------------------------------------------------
    // Degradation : monotone, et le drapeau wake-up decrit le capteur, pas le mode
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("les trois paliers degradent dans l'ordre : wake lock, continu, 25 Hz")
    fun `paliers de degradation`() {
        val nominal = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)

        val p1 = nominal.degradedTo(1)
        assertThat(p1.needsWakeLock).isTrue()
        assertThat(p1.degraded).isTrue()
        // Le palier 1 ne touche pas au batching : il ne repond qu'aux pertes en suspend.
        assertThat(p1.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(p1.maxReportLatencyUs).isEqualTo(nominal.maxReportLatencyUs)

        val p2 = nominal.degradedTo(2)
        assertThat(p2.kind).isEqualTo(AcquisitionKind.CONTINUOUS_WAKELOCK)
        assertThat(p2.maxReportLatencyUs).isEqualTo(0)
        assertThat(p2.rateHz).isEqualTo(50)

        val p3 = nominal.degradedTo(3)
        assertThat(p3.kind).isEqualTo(AcquisitionKind.CONTINUOUS_WAKELOCK)
        assertThat(p3.rateHz).isEqualTo(25)
    }

    @Test
    @DisplayName("le drapeau wake-up survit au palier 2 : il decrit le capteur, pas le mode")
    fun `drapeau wake-up conserve en degradation`() {
        val degraded = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)
            .degradedTo(2)

        // L'entete du chunk est la seule trace qui permette, des semaines plus tard, de savoir
        // sur quel capteur physique la nuit a ete enregistree. Perdre ce bit en degradation
        // rendrait deux nuits du meme appareil incomparables sans raison.
        assertThat(degraded.modeFlags and ChunkFormat.MODE_WAKEUP_SENSOR)
            .withFailMessage(
                "MODE_WAKEUP_SENSOR a disparu au palier 2. Le drapeau decrit le capteur retenu, " +
                    "pas le mode d'acquisition : la degradation ne change pas de capteur.",
            )
            .isNotZero()
        assertThat(degraded.modeFlags and ChunkFormat.MODE_BATCHED).isZero()
        assertThat(degraded.modeFlags and ChunkFormat.MODE_WAKE_LOCK).isNotZero()
        assertThat(degraded.modeFlags and ChunkFormat.MODE_DEGRADED).isNotZero()
    }

    @Test
    @DisplayName("les drapeaux du mode nominal wake-up : batche, sans wake lock, non degrade")
    fun `drapeaux du mode nominal`() {
        val mode = SensorStrategy.decide(isWakeUp = true, fifoReserved = 3_000, rateHz = 50)

        assertThat(mode.modeFlags and ChunkFormat.MODE_WAKEUP_SENSOR).isNotZero()
        assertThat(mode.modeFlags and ChunkFormat.MODE_BATCHED).isNotZero()
        assertThat(mode.modeFlags and ChunkFormat.MODE_WAKE_LOCK).isZero()
        assertThat(mode.modeFlags and ChunkFormat.MODE_DEGRADED).isZero()
        assertThat(mode.samplingPeriodUs).isEqualTo(20_000)
    }
}
