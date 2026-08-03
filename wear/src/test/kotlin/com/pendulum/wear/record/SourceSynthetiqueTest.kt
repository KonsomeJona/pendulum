package com.pendulum.wear.record

import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth
import com.pendulum.algo.synth.SleepSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La fidelite de l'injecteur, et rien d'autre.
 *
 * Ce que ces tests protegent tient en une phrase : **si la source synthetique fabrique des trous,
 * le banc ne mesurera plus que l'injecteur.** [GapMonitor] repond aux trous en escaladant, chaque
 * palier prend un `PARTIAL_WAKE_LOCK`, et une nuit de banc sous wake lock ne ressemble a rien de
 * ce qu'on cherche a observer. Le test central de ce fichier est donc celui qui verifie qu'un
 * `GapMonitor` alimente par le rejeu ne voit **aucun** trou sur un signal continu — pendant que le
 * defilement en temps reel, lui, est deux cent cinquante fois plus rapide.
 *
 * Les nuits generees ici sont courtes (30 min) : la propriete testee est locale — l'espacement de
 * deux echantillons consecutifs — et rien ne la rend plus vraie sur huit heures que sur trente
 * minutes, alors qu'une nuit complete couterait seize fois le temps de generation a chaque test.
 */
class SourceSynthetiqueTest {

    private companion object {
        const val PERIODE_NS = 20_000_000L // 50 Hz
        const val GRAINE = 7L

        /** Une base de temps quelconque, pour verifier que le recalage est bien applique. */
        const val ORIGINE_NS = 987_654_321_000L

        /**
         * Trente minutes, sans distracteur ni trou : les eveils intra-SPT sont desactives parce
         * qu'ils n'ont pas de sens sur une demi-heure, pas parce qu'ils generaient.
         */
        val NUIT_CONTINUE = NightSpec(
            durationH = 0.5,
            distractors = DistractorSpec.NONE,
            sleep = SleepSpec(sleepLatencyMin = 2.0, finalWakeMin = 2.0, wasoCount = 0),
        )

        /** La meme, avec trois trous de 4 a 5 s programmes. */
        val NUIT_TROUEE = NUIT_CONTINUE.copy(
            distractors = DistractorSpec.NONE.copy(
                gapCountMin = 3,
                gapCountMax = 3,
                gapMinSec = 4.0,
                gapMaxSec = 5.0,
            ),
        )
    }

    private fun blocs(spec: NightSpec): List<SampleBlock> =
        NightSynth.generate(spec, GRAINE).blocks

    /** Rejoue toute la nuit et rend les salves dans l'ordre. */
    private fun rejouer(blocs: List<SampleBlock>, taille: Int): List<Salve> {
        val rejeu = RejeuSynthetique(blocs, ORIGINE_NS)
        val salves = ArrayList<Salve>()
        while (true) salves += rejeu.salveSuivante(taille) ?: break
        return salves
    }

    private fun tousLesTs(salves: List<Salve>): LongArray {
        val total = salves.sumOf { it.n }
        val out = LongArray(total)
        var k = 0
        for (s in salves) for (i in 0 until s.n) out[k++] = s.tsNs[i]
        return out
    }

    // -------------------------------------------------------------------------------------
    // Le test qui decide de la valeur du banc
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("un GapMonitor alimente par le rejeu ne voit aucun trou sur un signal continu")
    fun `l injecteur ne fabrique pas de trous`() {
        val monitor = GapMonitor(50)
        val salves = rejouer(blocs(NUIT_CONTINUE), taille = 1500)

        // Salve par salve, exactement comme le materiel livre : entre deux salves il s'ecoule
        // 120 ms de temps reel, et cette heure d'arrivee n'apparait nulle part ici — parce que
        // l'API de GapMonitor ne la consomme pas, et parce que le rejeu ne s'en sert pas non plus
        // pour dater ses echantillons.
        for (s in salves) for (i in 0 until s.n) monitor.onSample(s.tsNs[i])

        assertThat(monitor.gapCount)
            .withFailMessage(
                "Le rejeu synthetique a fabrique %d trou(s) sur une nuit qui n'en contient " +
                    "aucun. L'escalade prendrait un PARTIAL_WAKE_LOCK, et le banc ne mesurerait " +
                    "plus que son propre injecteur : toute nuit de banc paraitrait degradee, et " +
                    "la panne ressemblerait a un defaut de capture.",
                monitor.gapCount,
            )
            .isZero()
        assertThat(monitor.gapTotalMs).isZero()
        assertThat(monitor.step).isZero()
        assertThat(monitor.consumePendingStep()).isNull()
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
    }

    @Test
    @DisplayName("les trous que le generateur a programmes, eux, traversent le rejeu")
    fun `l injecteur ne masque pas les vrais trous`() {
        val salves = rejouer(blocs(NUIT_TROUEE), taille = 1500)
        val ts = tousLesTs(salves)

        // Un injecteur qui ne fabrique pas de trous mais qui les gommerait serait tout aussi
        // inutile : les scenarios degrades du banc reposent sur des trous connus a l'avance.
        val discontinuites = (1 until ts.size).count { ts[it] - ts[it - 1] > 3 * PERIODE_NS }
        assertThat(discontinuites).isEqualTo(3)

        val monitor = GapMonitor(50)
        for (t in ts) monitor.onSample(t)
        assertThat(monitor.gapCount).isGreaterThanOrEqualTo(3)
        assertThat(monitor.gapTotalMs).isGreaterThanOrEqualTo(12_000)
    }

    // -------------------------------------------------------------------------------------
    // Base de temps
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("les timestamps sont strictement croissants et espaces de la periode nominale")
    fun `base de temps coherente`() {
        val ts = tousLesTs(rejouer(blocs(NUIT_CONTINUE), taille = 1500))

        assertThat(ts.size).isGreaterThan(80_000) // 30 min a 50 Hz, trous exclus
        assertThat(ts.first())
            .withFailMessage(
                "Le premier echantillon n'est pas recale sur l'origine fournie. Les vrais " +
                    "`SensorEvent.timestamp` partagent la base `elapsedRealtimeNanos` et la " +
                    "reconstruction cote telephone en depend pour rendre une heure murale.",
            )
            .isEqualTo(ORIGINE_NS)

        var ecartsHorsNominal = 0
        for (i in 1 until ts.size) {
            assertThat(ts[i]).isGreaterThan(ts[i - 1])
            if (ts[i] - ts[i - 1] != PERIODE_NS) ecartsHorsNominal++
        }
        assertThat(ecartsHorsNominal)
            .withFailMessage(
                "%d ecart(s) hors periode nominale sur une nuit continue. Une source dont les " +
                    "timestamps suivraient l'horloge reelle acceleree produirait exactement " +
                    "cela, et chaque salve deviendrait un trou.",
                ecartsHorsNominal,
            )
            .isZero()
    }

    // -------------------------------------------------------------------------------------
    // Livraison par salves
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("la livraison se fait par salves pleines, la derniere exceptee, sans perdre un echantillon")
    fun `livraison par salves`() {
        val blocs = blocs(NUIT_CONTINUE)
        val attendus = blocs.sumOf { it.x.size }
        val salves = rejouer(blocs, taille = 1500)

        assertThat(salves.size).isGreaterThan(1)
        // Les salves ignorent volontairement les frontieres de bloc du generateur : un vidage de
        // FIFO ne sait rien du decoupage qu'un generateur a choisi.
        assertThat(salves.dropLast(1).map { it.n }.distinct()).containsExactly(1500)
        assertThat(salves.last().n).isBetween(1, 1500)
        assertThat(salves.sumOf { it.n }).isEqualTo(attendus)

        // Et la nuit s'arrete : une source qui rebouclerait produirait un horodatage retrograde,
        // que GapMonitor ignore — le banc tournerait alors indefiniment sans rien ecrire.
        val rejeu = RejeuSynthetique(blocs, ORIGINE_NS)
        while (rejeu.salveSuivante(1500) != null) Unit
        assertThat(rejeu.termine).isTrue()
        assertThat(rejeu.salveSuivante(1500)).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Les deux constantes qui ne sont pas libres
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("la salve vaut un vidage de FIFO : 30 s a 50 Hz, soit 1500 evenements")
    fun `la taille de salve tombe de la strategie`() {
        val d = SourceSynthetique.CAPTEUR_SIMULE
        val mode = SensorStrategy.decide(d.wakeUp, d.fifoReserved, RecordingService.RATE_HZ)

        // Le capteur simule est choisi pour tomber sur le mode nominal du produit : c'est celui
        // que le banc doit exercer, et sa latence de report fixe la taille de salve.
        assertThat(mode.kind).isEqualTo(AcquisitionKind.BATCHED_WAKEUP)
        assertThat(mode.maxReportLatencyUs).isEqualTo(30_000_000)
        assertThat(mode.maxReportLatencyUs.toLong() * mode.rateHz / 1_000_000L)
            .withFailMessage(
                "La salve ne vaut plus 1500 evenements — la figure meme que cite la KDoc de " +
                    "GapMonitor pour decrire une livraison batchee nominale.",
            )
            .isEqualTo(1500L)
    }

    @Test
    @DisplayName("la pause entre salves depasse le seuil de frontiere de vidage du pipeline")
    fun `la pause rend la frontiere observable`() {
        assertThat(SourceSynthetique.PAUSE_SALVE_MS * 1_000_000L)
            .withFailMessage(
                "La pause entre deux salves est passee sous FLUSH_GAP_NS. SensorPipeline ne " +
                    "distinguera plus deux vidages d'un flot continu, FLAG_FIFO_BOUNDARY " +
                    "disparaitra des chunks, et le rejeu testera une forme de livraison que le " +
                    "materiel ne produit pas.",
            )
            .isGreaterThan(SensorPipeline.FLUSH_GAP_NS)
    }
}
