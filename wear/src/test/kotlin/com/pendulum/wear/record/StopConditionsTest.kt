package com.pendulum.wear.record

import com.pendulum.format.wire.StopReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les conditions d'arret automatique et leurs bornes.
 *
 * Chaque condition ferme la nuit proprement — fichier clos, salve finale urgente. Le defaut
 * qu'on veut rendre impossible est double : une condition qui se declenche trop tot ampute une
 * nuit valide (le faux contact du chargeur magnetique, l'heure butoir sur un enregistrement qui
 * vient de demarrer), une condition qui se declenche trop tard laisse le systeme tuer le service
 * et la nuit se termine en « la montre est morte a 3 h » au lieu de « tout est envoye ».
 */
class StopConditionsTest {

    private companion object {
        const val START = 0L
        const val TWO_HOURS = 2 * 3_600_000L
    }

    private fun conditions(stopAt: Int = 600) = StopConditions(START, stopAt)

    /** Une nuit saine a 3 h du matin : aucune condition ne doit se presenter. */
    private fun StopConditions.eval(
        nowMs: Long = TWO_HOURS,
        isCharging: Boolean = false,
        batteryPct: Int = 50,
        freeBytes: Long = 1L shl 30,
        localMinutes: Int = 180,
        wakeRatio: Double = 0.0,
    ): StopReason? = evaluate(nowMs, isCharging, batteryPct, freeBytes, localMinutes, wakeRatio)

    @Test
    @DisplayName("une nuit saine ne s'arrete pas")
    fun `cas nominal`() {
        assertThat(conditions().eval()).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Chargeur : 60 s de charge soutenue
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("la charge n'arrete qu'apres 60 s soutenues, a la milliseconde pres")
    fun `anti-rebond du chargeur`() {
        val c = conditions()

        assertThat(c.eval(nowMs = TWO_HOURS, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 59_999, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 60_000, isCharging = true))
            .isEqualTo(StopReason.CHARGING)
    }

    @Test
    @DisplayName("un faux contact du chargeur magnetique ne coupe pas la nuit")
    fun `faux contact remet l anti-rebond a zero`() {
        val c = conditions()

        // Le dormeur roule sur le chargeur pose sur la table de nuit : contact bref, rupture,
        // recontact. Sans remise a zero, les contacts brefs s'additionneraient et la nuit
        // s'arreterait au premier qui depasse le total — pour une montre jamais vraiment posee.
        assertThat(c.eval(nowMs = TWO_HOURS, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 30_000, isCharging = false)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 40_000, isCharging = true)).isNull()
        // 60 s depuis le PREMIER contact, mais 59,999 s depuis le second : pas d'arret.
        assertThat(c.eval(nowMs = TWO_HOURS + 99_999, isCharging = true)).isNull()
        assertThat(c.eval(nowMs = TWO_HOURS + 100_000, isCharging = true))
            .isEqualTo(StopReason.CHARGING)
    }

    // -------------------------------------------------------------------------------------
    // Batterie
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("5 % arrete, 6 % continue : la fermeture doit preceder la mort du systeme")
    fun `borne de batterie basse`() {
        assertThat(conditions().eval(batteryPct = 6)).isNull()
        assertThat(conditions().eval(batteryPct = 5)).isEqualTo(StopReason.LOW_BATTERY)
        assertThat(conditions().eval(batteryPct = 0)).isEqualTo(StopReason.LOW_BATTERY)
    }

    @Test
    @DisplayName("un niveau de batterie inconnu n'arrete jamais rien")
    fun `batterie inconnue`() {
        // BatteryManager repond -1 quand la valeur n'est pas disponible. Traiter « inconnu »
        // comme « vide » couperait des nuits entieres sur un simple rate de lecture.
        assertThat(conditions().eval(batteryPct = -1)).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Duree maximale
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("dix heures pile arretent ; une milliseconde de moins continue")
    fun `borne de duree maximale`() {
        assertThat(conditions().eval(nowMs = START + StopConditions.MAX_DURATION_MS - 1)).isNull()
        assertThat(conditions().eval(nowMs = START + StopConditions.MAX_DURATION_MS))
            .isEqualTo(StopReason.MAX_DURATION)
    }

    // -------------------------------------------------------------------------------------
    // Heure butoir
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("l'heure butoir ne vaut que si la nuit a plus d'une heure")
    fun `heure butoir et nuit qui vient de demarrer`() {
        // Enregistrement demarre a 11 h avec butoir a 10 h : l'heure locale depasse le butoir
        // des la premiere milliseconde. Sans le garde-fou d'une heure, la nuit s'arreterait
        // avant d'avoir existe.
        assertThat(conditions().eval(nowMs = START + 1, localMinutes = 660)).isNull()
        // Une heure pile ne suffit pas : la borne est strictement au-dela.
        assertThat(conditions().eval(nowMs = START + 3_600_000, localMinutes = 660)).isNull()
        assertThat(conditions().eval(nowMs = START + 3_600_001, localMinutes = 660))
            .isEqualTo(StopReason.TIME_LIMIT)
    }

    @Test
    @DisplayName("la minute butoir elle-meme arrete ; la minute d'avant continue")
    fun `borne de l heure butoir`() {
        assertThat(conditions().eval(localMinutes = 599)).isNull()
        assertThat(conditions().eval(localMinutes = 600)).isEqualTo(StopReason.TIME_LIMIT)
    }

    // -------------------------------------------------------------------------------------
    // Reveil
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("80 % d'epoques actives continuent ; strictement au-dela, la personne est levee")
    fun `borne du ratio de reveil`() {
        assertThat(conditions().eval(wakeRatio = 0.80)).isNull()
        assertThat(conditions().eval(wakeRatio = 0.801)).isEqualTo(StopReason.WAKE_DETECTED)
    }

    // -------------------------------------------------------------------------------------
    // Disque
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("sous 50 Mo libres, fermer maintenant vaut mieux qu'ecrire jusqu'au mur")
    fun `borne d espace disque`() {
        assertThat(conditions().eval(freeBytes = StopConditions.MIN_FREE_BYTES)).isNull()
        assertThat(conditions().eval(freeBytes = StopConditions.MIN_FREE_BYTES - 1))
            .isEqualTo(StopReason.DISK_FULL)
        assertThat(conditions().eval(freeBytes = 0)).isEqualTo(StopReason.DISK_FULL)
    }

    // -------------------------------------------------------------------------------------
    // Priorite : la premiere condition presentee gagne
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("la charge soutenue passe avant la batterie basse : le motif dit la cause premiere")
    fun `priorite de la charge`() {
        val c = conditions()
        c.eval(nowMs = TWO_HOURS, isCharging = true) // le contact demarre l'anti-rebond

        // Sur le chargeur avec 3 % : la nuit est finie parce que l'utilisateur a pose la montre,
        // pas parce que la batterie est vide. Le sidecar racontera la mauvaise histoire si
        // l'ordre change — et c'est ce motif que l'ecran du telephone affichera au reveil.
        assertThat(c.eval(nowMs = TWO_HOURS + 60_000, isCharging = true, batteryPct = 3))
            .isEqualTo(StopReason.CHARGING)
    }

    @Test
    @DisplayName("le reveil detecte passe avant le disque plein")
    fun `priorite du reveil sur le disque`() {
        assertThat(conditions().eval(wakeRatio = 0.9, freeBytes = 0))
            .isEqualTo(StopReason.WAKE_DETECTED)
    }
}

/**
 * Le vrai detecteur de « montre retiree » : la locomotion. L'off-body du PPG n'existe pas ici,
 * et c'est voulu — a la cheville il lirait « non porte » en permanence et couperait chaque nuit
 * a sa premiere minute.
 */
class WakeDetectorTest {

    private companion object {
        const val T0 = 1_000_000_000L
        const val ACTIVE_RMS = 3.0 // marche : bien au-dela du seuil de locomotion de 1,5 m/s^2
        const val QUIET_RMS = 0.1 // sommeil
    }

    @Test
    @DisplayName("le ratio reste a zero tant qu'aucune epoque de 30 s n'est close")
    fun `pas de verdict sans epoque complete`() {
        val d = WakeDetector()
        var ts = T0
        repeat(29) {
            d.onSecond(ACTIVE_RMS, ts)
            ts += 1_000_000_000L
        }

        // 29 s d'agitation ne font pas un lever : se prononcer avant la premiere epoque close,
        // c'est arreter la nuit sur un retournement.
        assertThat(d.ratio).isEqualTo(0.0)
    }

    @Test
    @DisplayName("une epoque bascule a la majorite stricte de secondes actives, pas a l'egalite")
    fun `borne de majorite dans l epoque`() {
        // Premiere epoque : 31 appels (le 31e la clot). 16 secondes actives sur 31 = majorite.
        val actif = WakeDetector()
        feed(actif, activeSeconds = 16, totalSeconds = 31)
        assertThat(actif.ratio).isEqualTo(1.0)

        // 15 sur 31 : l'egalite arrondie ne suffit pas, l'epoque reste une epoque de sommeil.
        val calme = WakeDetector()
        feed(calme, activeSeconds = 15, totalSeconds = 31)
        assertThat(calme.ratio).isEqualTo(0.0)
    }

    @Test
    @DisplayName("un lever reel franchit le seuil de 0,80 seulement apres une locomotion soutenue")
    fun `scenario du lever`() {
        val d = WakeDetector()
        var ts = T0

        // Fin de nuit calme : 3 epoques de sommeil...
        repeat(91) { // la 1re epoque compte 31 appels, les suivantes 30
            d.onSecond(QUIET_RMS, ts)
            ts += 1_000_000_000L
        }
        // ... puis la personne se leve et reste debout : 17 epoques de locomotion.
        repeat(17 * 30) {
            d.onSecond(ACTIVE_RMS, ts)
            ts += 1_000_000_000L
        }

        // 17 epoques actives sur les 20 de la fenetre de 10 min : 0,85, au-dela du seuil de
        // 0,80 de StopConditions. Le sursaut d'une minute, lui, n'y arrivera jamais — c'est
        // toute la difference entre « alle aux toilettes » et « leve pour de bon ».
        assertThat(d.ratio).isCloseTo(0.85, within(1e-9))
    }

    /** Alimente [d] a 1 Hz : d'abord [activeSeconds] secondes actives, puis du calme. */
    private fun feed(d: WakeDetector, activeSeconds: Int, totalSeconds: Int) {
        var ts = T0
        repeat(totalSeconds) { i ->
            d.onSecond(if (i < activeSeconds) ACTIVE_RMS else QUIET_RMS, ts)
            ts += 1_000_000_000L
        }
    }
}
