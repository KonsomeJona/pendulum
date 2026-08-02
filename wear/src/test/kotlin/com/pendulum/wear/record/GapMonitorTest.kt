package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'integrite temporelle vue par [GapMonitor], et surtout la regle qui ne se negocie pas :
 * un trou se mesure sur les ecarts de `SensorEvent.timestamp`, jamais sur l'heure d'arrivee.
 *
 * Le premier test de ce fichier est celui qui protege le comportement le plus couteux a casser.
 * Si quelqu'un reintroduit un jour une detection fondee sur l'heure de livraison, chaque salve
 * batchee redevient un « trou », l'escalade prend un wake lock, et chaque nuit de test brule
 * 65 % de batterie en silence — la panne ressemble alors a « le batching ne marche pas », alors
 * que c'est le moniteur qui a tort.
 */
class GapMonitorTest {

    private companion object {
        const val PERIOD = 20_000_000L // 50 Hz
        const val T0 = 1_000_000_000L
    }

    /**
     * Simule le flux vu par `onSensorChanged` : seuls les timestamps capteur existent.
     * [flagged] compte les echantillons marques `FLAG_GAP_BEFORE`.
     */
    private class Flux(val monitor: GapMonitor, startNs: Long, val periodNs: Long = PERIOD) {
        var ts = startNs
            private set
        var flagged = 0
            private set

        init {
            monitor.onSample(ts)
        }

        /** Echantillons reguliers jusqu'a [targetNs] inclus (multiple de la periode attendu). */
        fun regularUntil(targetNs: Long) {
            while (ts < targetNs) {
                ts += periodNs
                if (monitor.onSample(ts)) flagged++
            }
        }

        /** Silence capteur de [durationNs], puis l'echantillon qui le clot. */
        fun hole(durationNs: Long) {
            ts += durationNs
            if (monitor.onSample(ts)) flagged++
        }
    }

    // -------------------------------------------------------------------------------------
    // La regle non negociable
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("une salve batchee — 30 s de silence puis 1500 evenements d'un coup — n'est pas un trou")
    fun `le batching nominal ne declenche rien`() {
        val monitor = GapMonitor(50)
        val flux = Flux(monitor, T0)

        // Deux lots de 30 s livres « d'un coup » : la boucle serree ci-dessous EST la salve.
        // L'heure d'arrivee n'apparait nulle part, parce que l'API de GapMonitor ne la consomme
        // pas — c'est la signature elle-meme qui verrouille la regle. Un futur monitor qui
        // voudrait regarder l'heure de livraison devrait casser ce test pour le faire.
        flux.regularUntil(T0 + 30_000_000_000L) // lot 1 : 1500 evenements, timestamps reguliers
        flux.regularUntil(T0 + 60_000_000_000L) // lot 2, apres 30 s de silence de livraison

        assertThat(monitor.gapCount)
            .withFailMessage(
                "Le fonctionnement nominal du batching a ete compte comme %d trou(s). " +
                    "Consequence directe : l'escalade prend un PARTIAL_WAKE_LOCK sur une nuit " +
                    "saine, la batterie tombe a 35 %%, et la mesure d'autonomie de la phase de " +
                    "test est invalidee sans aucun message d'erreur.",
                monitor.gapCount,
            )
            .isZero()
        assertThat(monitor.gapTotalMs).isZero()
        assertThat(flux.flagged).isZero()
        assertThat(monitor.step).isZero()
        assertThat(monitor.consumePendingStep()).isNull()
        // La fenetre de 60 s s'est close au passage : le fs mesure est nominal.
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Signal intra-lot : seuil de 3 fois la periode nominale
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("un ecart d'exactement 3 periodes passe ; une nanoseconde de plus est un trou")
    fun `borne exacte du seuil intra-lot`() {
        val monitor = GapMonitor(50)
        monitor.onSample(T0)

        // 60 ms tout juste : la gigue de livraison d'un vrai capteur atteint couramment deux
        // periodes ; compter un trou ici noierait le journal de faux positifs.
        assertThat(monitor.onSample(T0 + 3 * PERIOD)).isFalse()
        assertThat(monitor.gapCount).isZero()

        // 60 ms + 1 ns : le trou est reel, et l'echantillon qui le suit doit porter le drapeau
        // FLAG_GAP_BEFORE — c'est lui qui permettra a l'analyse d'ecarter le bloc.
        assertThat(monitor.onSample(T0 + 3 * PERIOD + 3 * PERIOD + 1)).isTrue()
        assertThat(monitor.gapCount).isEqualTo(1)
        // Duree manquante = dt moins la periode attendue : 60,000001 - 20 = 40 ms.
        assertThat(monitor.gapTotalMs).isEqualTo(40)
    }

    // -------------------------------------------------------------------------------------
    // Escalade : trois gros trous en dix minutes
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("trois trous de 3 s en moins de dix minutes montent d'un palier, pas avant")
    fun `escalade au troisieme gros trou`() {
        val monitor = GapMonitor(50)
        val flux = Flux(monitor, T0)

        // Trois trous d'exactement 3 s (la borne inclusive du « gros trou »), chacun dans une
        // fenetre de mesure differente pour que seul le signal intra-lot les voie : 3 s manquantes
        // sur 60 s laissent 2851 echantillons recus pour 2850 exiges — juste au-dessus du seuil
        // de deficit de 0,95, ce qui verifie au passage cette borne-la aussi.
        flux.regularUntil(T0 + 10_000_000_000L)
        flux.hole(3_000_000_000L) // trou 1, a t=13 s
        assertThat(monitor.step).isZero()

        flux.regularUntil(T0 + 70_000_000_000L)
        flux.hole(3_000_000_000L) // trou 2, a t=73 s
        assertThat(monitor.step)
            .withFailMessage(
                "Deux gros trous ont suffi a escalader. Le wake lock du palier 1 doit se " +
                    "meriter : un appareil qui perd deux lots isoles dans la nuit ne justifie " +
                    "pas de sacrifier l'autonomie de toutes les heures restantes.",
            )
            .isZero()

        flux.regularUntil(T0 + 130_000_000_000L)
        flux.hole(3_000_000_000L) // trou 3, a t=133 s : les trois tiennent dans 10 min

        assertThat(monitor.gapCount).isEqualTo(3)
        assertThat(flux.flagged).isEqualTo(3)
        assertThat(monitor.step).isEqualTo(1)
        assertThat(monitor.consumePendingStep()).isEqualTo(1)
        // Le palier est consomme une seule fois : le service ne doit pas re-appliquer la meme
        // degradation a chaque tour de boucle.
        assertThat(monitor.consumePendingStep()).isNull()
    }

    @Test
    @DisplayName("des trous espaces de plus de dix minutes ne s'additionnent jamais")
    fun `la fenetre d'escalade glisse`() {
        val monitor = GapMonitor(50)
        val flux = Flux(monitor, T0)

        flux.regularUntil(T0 + 10_000_000_000L)
        flux.hole(3_000_000_000L) // trou 1, a t=13 s
        flux.regularUntil(T0 + 70_000_000_000L)
        flux.hole(3_000_000_000L) // trou 2, a t=73 s
        flux.regularUntil(T0 + 612_000_000_000L)
        flux.hole(3_000_000_000L) // trou 3, a t=615 s : le trou 1 est sorti de la fenetre

        // Une nuit entiere accumule fatalement quelques trous isoles. S'ils comptaient pour
        // toujours, toute nuit assez longue finirait sous wake lock — l'escalade ne repondrait
        // plus a une panne mais a la simple duree.
        assertThat(monitor.gapCount).isEqualTo(3)
        assertThat(monitor.step).isZero()
        assertThat(monitor.consumePendingStep()).isNull()
    }

    @Test
    @DisplayName("l'escalade ne redescend jamais et plafonne au palier 3")
    fun `escalade monotone et plafonnee`() {
        val monitor = GapMonitor(50)
        val flux = Flux(monitor, T0)
        val steps = mutableListOf<Int>()

        // Une degradation continue : gros trous en rafale. Peu importe ici que certains soient
        // aussi comptes par le deficit de fenetre — on verifie la trajectoire des paliers, pas
        // le comptage.
        repeat(30) {
            flux.regularUntil(flux.ts + 1_000_000_000L)
            flux.hole(3_000_000_000L)
            monitor.consumePendingStep()?.let { steps += it }
        }

        assertThat(steps)
            .withFailMessage(
                "Les paliers emis sont %s. Ils doivent monter strictement — 1 puis 2 puis 3 — " +
                    "et s'arreter la : un palier qui redescend ou se repete fait osciller le " +
                    "service entre deux modes toute la nuit, une rotation de chunk a chaque fois.",
                steps,
            )
            .isEqualTo(listOf(1, 2, 3))
        assertThat(monitor.step).isEqualTo(3)
    }

    // -------------------------------------------------------------------------------------
    // Signal de fenetre : deficit sur 60 s de temps capteur
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("un capteur qui livre 40 Hz au lieu de 50 est vu par la fenetre, pas par l'intra-lot")
    fun `deficit de fenetre sans trou intra-lot`() {
        val monitor = GapMonitor(50)
        // Ecarts de 25 ms : chacun est tres loin du seuil intra-lot de 60 ms, mais il manque
        // 20 % des echantillons. Sans le signal de fenetre, cette nuit passerait pour saine et
        // tous les filtres de l'analyse tourneraient avec un fs faux de 20 %.
        val flux = Flux(monitor, T0, periodNs = 25_000_000L)
        flux.regularUntil(T0 + 60_000_000_000L)

        assertThat(flux.flagged).isZero() // l'intra-lot ne voit rien : c'est le point
        assertThat(monitor.gapCount).isEqualTo(1)
        // 3000 attendus, 2401 recus : 599 manquants a 20 ms piece = 11 980 ms.
        assertThat(monitor.gapTotalMs).isEqualTo(11_980)
        assertThat(monitor.measuredRateHz).isCloseTo(40.0, within(0.1))
        assertThat(monitor.rateDeviates)
            .withFailMessage(
                "fs mesure a 40 Hz pour 50 nominal sans que rateDeviates leve. Un fs faux " +
                    "decale toutes les durees de mouvement de la chaine d'analyse de 20 %%.",
            )
            .isTrue()
    }

    @Test
    @DisplayName("le fs mesure reste a zero tant qu'aucune fenetre de 60 s n'est close")
    fun `fs mesure avant la premiere fenetre`() {
        val monitor = GapMonitor(50)
        val flux = Flux(monitor, T0)
        flux.regularUntil(T0 + 59_000_000_000L)

        // Un fs « mesure » sur trois secondes de donnees serait un mensonge de precision :
        // 0 dit honnetement « pas encore de mesure », et l'appelant peut l'afficher tel quel.
        assertThat(monitor.measuredRateHz).isEqualTo(0.0)

        flux.regularUntil(T0 + 60_000_000_000L)
        assertThat(monitor.measuredRateHz).isCloseTo(50.0, within(0.1))
        assertThat(monitor.rateDeviates).isFalse()
    }

    // -------------------------------------------------------------------------------------
    // Re-inscription du capteur
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("apres onRateChanged, la discontinuite de re-inscription n'est pas comptee")
    fun `re-inscription sans trou auto-inflige`() {
        val monitor = GapMonitor(50)
        monitor.onSample(T0)
        monitor.onSample(T0 + PERIOD)

        monitor.onRateChanged(25)

        // La re-inscription rompt la continuite : le premier echantillon du nouveau regime
        // arrive bien plus tard, et ce silence est notre fait, pas celui du capteur.
        val t1 = T0 + 100_000_000_000L
        assertThat(monitor.onSample(t1)).isFalse()
        assertThat(monitor.gapCount).isZero()

        // Et le seuil suit la nouvelle periode : 100 ms passent a 25 Hz (seuil 120 ms) alors
        // qu'ils etaient un trou a 50 Hz — sinon le palier 3 se punirait lui-meme.
        assertThat(monitor.onSample(t1 + 100_000_000L)).isFalse()
        assertThat(monitor.onSample(t1 + 100_000_000L + 121_000_000L)).isTrue()
    }

    // -------------------------------------------------------------------------------------
    // Comportement observe, en tension avec la KDoc — voir le rapport de revue
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("DEFAUT DOCUMENTE : un meme trou physique est compte par les deux signaux a la fois")
    fun `double comptage d un gros trou par la fenetre`() {
        val monitor = GapMonitor(50)
        val flux = Flux(monitor, T0)

        // Un unique trou de 25 s. Le signal intra-lot le compte a son arrivee ; puis la fenetre
        // de 60 s, qui ne sait pas que ce deficit a deja ete vu, le recompte a sa cloture.
        flux.regularUntil(T0 + 10_000_000_000L)
        flux.hole(25_000_000_000L)
        flux.regularUntil(T0 + 60_000_000_000L)

        // Ce test fige le comportement REEL, pas le comportement souhaite. La KDoc annonce que
        // la fenetre « rattrape un trou tombant entre deux lots » et que l'escalade demande
        // « trois gros trous » ; en pratique un seul trou > 3 s produit deux entrees de gros
        // trou, donc DEUX trous physiques dans la meme fenetre suffisent a escalader. Si ce
        // test casse parce que le double comptage a ete corrige, c'est une amelioration :
        // mettre a jour les assertions, pas restaurer le comportement.
        assertThat(monitor.gapCount).isEqualTo(2)
        assertThat(monitor.step).isZero()

        flux.regularUntil(T0 + 70_000_000_000L)
        flux.hole(25_000_000_000L) // deuxieme trou physique : troisieme entree de gros trou

        assertThat(monitor.step).isEqualTo(1)
        assertThat(monitor.consumePendingStep()).isEqualTo(1)
    }
}
