package com.pendulum.wear.temps

import com.pendulum.format.wire.WireProtocol
import com.pendulum.wear.record.SensorStrategy
import com.pendulum.wear.record.SourceSynthetique
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Le garde-fou qui interdit une echelle incoherente avec le rejeu.
 *
 * Deux accelerations vivent dans ce banc, et **elles doivent etre le meme nombre** :
 *
 *  - le rejeu de `SourceSynthetique` avance de N secondes de temps capteur par seconde de temps
 *    mural. Ce facteur n'est pas libre : il tombe de la taille de salve (`maxReportLatencyUs x
 *    rateHz`) et de la pause entre salves, elle-meme contrainte par `SensorPipeline.FLUSH_GAP_NS` ;
 *  - `EchelleTemps.DIVISEUR` divise les durees murales.
 *
 * S'ils different, la course entre les deux conditions de rotation de chunk change de vainqueur —
 * et avec elle ce que le banc teste reellement. La demonstration chiffree est dans la KDoc de
 * `ChunkStore.writeBlock` ; ce test-ci en est l'execution.
 *
 * Le cas `DIVISEUR == 1` est laisse passer : c'est une compilation debug ordinaire, qui n'est pas
 * un banc et n'a aucun rejeu en cours.
 */
class CoherenceEchelleTest {

    /**
     * Le mode nominal du produit, celui que le banc exerce : accelerometre wake-up, 3 000
     * evenements de FIFO garantis, 50 Hz — soit `BATCHED_WAKEUP` a 30 s de latence.
     */
    private val modeNominal = SensorStrategy.decide(
        isWakeUp = SourceSynthetique.CAPTEUR_SIMULE.wakeUp,
        fifoReserved = SourceSynthetique.CAPTEUR_SIMULE.fifoReserved,
        rateHz = 50,
    )

    /** Secondes de temps capteur livrees par seconde de temps mural. */
    private val accelerationRejeu: Long
        get() = modeNominal.maxReportLatencyUs.toLong() / 1_000L / SourceSynthetique.PAUSE_SALVE_MS

    @Test
    fun `le rejeu avance bien de 250 s de temps capteur par seconde`() {
        // 30 s de salve pour 120 ms de pause. Si ce nombre bouge — parce que `FLUSH_GAP_NS`
        // change, ou parce que le FIFO simule change — c'est le diviseur du banc qu'il faut
        // changer avec lui, et non l'inverse.
        assertThat(accelerationRejeu).isEqualTo(250L)
    }

    @Test
    fun `le diviseur du banc vaut l'acceleration du rejeu`() {
        if (EchelleTemps.DIVISEUR == 1L) return // compilation debug ordinaire, pas un banc

        assertThat(EchelleTemps.DIVISEUR)
            .`as`(
                "diviseur de temps : comprimer le temps mural exactement autant que le rejeu " +
                    "comprime le temps capteur, sinon la rotation de chunk change de cause",
            )
            .isEqualTo(accelerationRejeu)
    }

    /**
     * Pourquoi le garde-fou de `Preflight.echelleDesaccordee` existe, en chiffres.
     *
     * A l'echelle du banc, le delai de garde de l'heure butoir passe sous la latence de salve du
     * FIFO. Sur le rejeu ce n'est pas un probleme : la salve est elle aussi comprimee, puisque le
     * temps capteur avance 250 fois plus vite. Sur le capteur reel elle ne l'est pas — c'est du
     * materiel — et l'enregistrement s'arrete donc avant son premier echantillon. C'est la mesure
     * du §11.5.3 : 14,636 s de nuit, zero chunk, aucun message.
     *
     * Assertion **inversee**, comme celle du remplissage : le jour ou elle tombe, ce n'est pas ce
     * test qu'il faut ajuster, c'est que la raison d'etre du garde-fou a change.
     */
    @Test
    fun `a l'echelle du banc, l'heure butoir couperait avant la premiere salve du FIFO`() {
        val gardeButoirMs = com.pendulum.wear.temps.Durees(accelerationRejeu).delaiMinAvantHeureButoirMs
        val salveFifoMs = modeNominal.maxReportLatencyUs / 1_000L

        assertThat(gardeButoirMs)
            .`as`("delai de garde de l'heure butoir, comprime au diviseur du banc")
            .isLessThan(salveFifoMs)
        assertThat(salveFifoMs).isEqualTo(30_000L)
        assertThat(gardeButoirMs).isEqualTo(14_400L)
    }

    @Test
    fun `a l'echelle du rejeu, la duree ferme le chunk juste avant le plafond d'octets`() {
        // C'est la propriete que le banc doit conserver, et elle est vraie en marche reelle :
        // 50 Hz x 6 octets plus les entetes de bloc font environ 303 o/s, donc 92 160 octets sont
        // atteints ~4 s **apres** les 300 s de la borne de duree. Les chunks sortent remplis a
        // ~99 % du plafond, et c'est ce remplissage qui exerce la tenue des tampons et le passage
        // sous les 100 Ko d'un `DataItem`.
        //
        // Assertion **inversee** : le jour ou elle tombe, ce n'est pas ce test qu'il faut ajuster,
        // c'est le fait que le banc a cesse de faire circuler des chunks pleins.
        val octetsParSecondeCapteur = 50.0 * 6.0 * (1.0 + 32.0 / (512.0 * 6.0))
        val secondesPourRemplir = WireProtocol.CHUNK_ROTATION_BYTES / octetsParSecondeCapteur
        val secondesDeLaBorne = WireProtocol.CHUNK_ROTATION_MS / 1_000.0

        assertThat(secondesDeLaBorne)
            .`as`("la borne de duree doit fermer le chunk avant le plafond d'octets")
            .isLessThan(secondesPourRemplir)
        assertThat(secondesDeLaBorne / secondesPourRemplir)
            .`as`("remplissage des chunks a la fermeture")
            .isGreaterThan(0.95)
    }
}
