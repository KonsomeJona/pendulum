package com.pendulum.phone.ui

import com.pendulum.phone.ui.model.EtatReveil
import com.pendulum.phone.ui.model.EtapeAnalyse
import com.pendulum.phone.ui.model.MachineReveil
import com.pendulum.phone.work.FetchSchedule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Les cinq etats du reveil, un par un, sur leurs bornes.
 *
 * C'est la logique la plus lue de l'application — elle decide de ce qui s'affiche chaque matin —
 * et c'est celle qu'aucune revue d'ecran ne verifie : on ne peut pas mettre un emulateur dans
 * l'etat « transfert a 8 chunks sur 17, hypnogramme pas encore arrive, T+7 h » a la demande.
 */
class MachineReveilTest {

    private val fin = 1_700_000_000_000L
    private fun h(n: Long) = TimeUnit.HOURS.toMillis(n)

    /** Mise en forme deterministe : la machine calcule des instants, pas des libelles. */
    private val heure: (Long) -> String = { ms -> "T${(ms - fin) / 60_000}" }

    private fun faits(
        etatSession: String = "CLOSED",
        chunksRecus: Int = 17,
        totalChunks: Int? = 17,
        octetsRecus: Long = 8_800_000,
        analyseeAtMs: Long? = fin + h(1),
        finDeNuitMs: Long? = fin,
        masqueSommeilApplique: Boolean = true,
        hypnogrammeRecu: Boolean = true,
        tentativesHc: Int = 1,
        derniereTentativeMs: Long? = fin + FetchSchedule.OFFSETS_MS[0],
        integriteRejetee: Double = 0.0,
    ) = MachineReveil.Faits(
        sessionHex = "abcd",
        dateLisible = "12 March",
        zoneId = "Europe/Paris",
        etatSession = etatSession,
        chunksRecus = chunksRecus,
        totalChunks = totalChunks,
        octetsRecus = octetsRecus,
        analyseeAtMs = analyseeAtMs,
        finDeNuitMs = finDeNuitMs,
        masqueSommeilApplique = masqueSommeilApplique,
        hypnogrammeRecu = hypnogrammeRecu,
        tentativesHc = tentativesHc,
        derniereTentativeMs = derniereTentativeMs,
        integriteRejetee = integriteRejetee,
    )

    /**
     * L'instant par defaut est le **rang T+2 h de l'echelle**, et non deux heures ecrites en
     * clair. Les deux coincident en temps reel ; ils divergent des qu'une variante de banc
     * comprime l'echelle, et c'est alors la lecture de `MachineReveil` qui echouerait — pour une
     * raison qui n'a rien a voir avec ce que ce fichier verifie. La machine se lit relativement
     * a l'echelle, donc ses tests aussi.
     */
    private fun etat(f: MachineReveil.Faits?, maintenant: Long = fin + FetchSchedule.OFFSETS_MS[2]) =
        MachineReveil.de(f, maintenant, heure)

    // -------------------------------------------------------------------------------------
    // Les bornes de la machine
    // -------------------------------------------------------------------------------------

    @Test
    fun `sans nuit, la bande n'existe pas`() {
        assertThat(etat(null)).isEqualTo(EtatReveil.Rien)
    }

    @Test
    fun `pendant l'enregistrement, la bande du reveil se tait`() {
        // La carte de l'accueil porte deja l'enregistrement en cours. Le repeter ici volerait la
        // seule ligne que P4 accorde a l'etat de la nuit d'hier.
        assertThat(etat(faits(etatSession = MachineReveil.OUVERTE, analyseeAtMs = null)))
            .isEqualTo(EtatReveil.Rien)
    }

    @Test
    fun `etat 1 - nuit fermee, rien n'est encore arrive`() {
        val e = etat(faits(chunksRecus = 0, octetsRecus = 0, analyseeAtMs = null))
        assertThat(e).isInstanceOf(EtatReveil.EnAttenteTransfert::class.java)
        e as EtatReveil.EnAttenteTransfert
        assertThat(e.date).isEqualTo("12 March")
        // 17 chunks x 1,2 Mo estimes a 200 ko/s : environ 102 s, arrondies a 2 minutes.
        assertThat(e.minutes).isGreaterThan(0)
    }

    @Test
    fun `etat 2 - le transfert est en cours et le pourcentage ne recule pas`() {
        val e = etat(faits(chunksRecus = 8, octetsRecus = 4_100_000, analyseeAtMs = null))
        assertThat(e).isInstanceOf(EtatReveil.Transfert::class.java)
        e as EtatReveil.Transfert
        assertThat(e.chunk).isEqualTo(8)
        assertThat(e.chunks).isEqualTo(17)
        assertThat(e.fraction).isBetween(0f, 1f)
    }

    @Test
    fun `etat 3 - tous les chunks sont la, l'analyse tourne`() {
        val sansHypno = etat(faits(analyseeAtMs = null, hypnogrammeRecu = false))
        assertThat(sansHypno).isInstanceOf(EtatReveil.Analyse::class.java)
        assertThat((sansHypno as EtatReveil.Analyse).etape).isEqualTo(EtapeAnalyse.DETECTION)

        // L'etape affichee se deduit de ce qui est en base, pas d'un compteur que rien
        // n'alimenterait : l'hypnogramme est la, donc on en est au croisement.
        val avecHypno = etat(faits(analyseeAtMs = null, hypnogrammeRecu = true))
        assertThat((avecHypno as EtatReveil.Analyse).etape).isEqualTo(EtapeAnalyse.CROISEMENT)
    }

    @Test
    fun `etat 4 - le cas NORMAL du reveil, et il n'est pas un echec`() {
        val e = etat(faits(masqueSommeilApplique = false, hypnogrammeRecu = false))
        assertThat(e).isInstanceOf(EtatReveil.Provisoire::class.java)
        e as EtatReveil.Provisoire

        // Ce que l'etat montre a la place d'un indicateur d'avancement indetermine.
        assertThat(e.derniereTentative).isNotNull()
        assertThat(e.prochaineTentative).isNotNull()
        assertThat(e.abandonA).isNotBlank()
        assertThat(e.abandonne).isFalse()

        // Et surtout : ce n'est pas un `Echec`, donc il ne peut pas passer par `ErrorCard` ni en
        // heriter la teinte rouge. La contrainte est portee par le type, pas par une convention.
        assertThat(e).isNotInstanceOf(EtatReveil.Echec::class.java)
    }

    @Test
    fun `etat 4 - avant toute tentative, on n'ecrit pas un tiret`() {
        val e = etat(
            faits(masqueSommeilApplique = false, tentativesHc = 0, derniereTentativeMs = null),
            maintenant = fin + TimeUnit.MINUTES.toMillis(5),
        ) as EtatReveil.Provisoire
        assertThat(e.derniereTentative).isNull()
        assertThat(e.prochaineTentative).isNotNull()
    }

    @Test
    fun `etat 4 - passe T+36 h, l'abandon est dit et plus rien n'est planifie`() {
        val e = etat(
            faits(masqueSommeilApplique = false),
            maintenant = fin + FetchSchedule.GIVE_UP_MS,
        ) as EtatReveil.Provisoire
        assertThat(e.abandonne).isTrue()
        assertThat(e.prochaineTentative).isNull()
    }

    @Test
    fun `l'echeance d'abandon est bien T+36 h apres la fin de la nuit`() {
        val e = etat(faits(masqueSommeilApplique = false)) as EtatReveil.Provisoire
        assertThat(e.abandonA).isEqualTo(heure(fin + FetchSchedule.GIVE_UP_MS))
    }

    @Test
    fun `le masque de sommeil applique termine la nuit`() {
        // Etat complet : la bande disparait. C'est `sleep_window` qui fait foi, pas le snapshot —
        // un hypnogramme recu mais pas encore rescore laisse la nuit provisoire.
        assertThat(etat(faits(masqueSommeilApplique = true))).isEqualTo(EtatReveil.Rien)
        assertThat(etat(faits(masqueSommeilApplique = false, hypnogrammeRecu = true)))
            .isInstanceOf(EtatReveil.Provisoire::class.java)
    }

    // -------------------------------------------------------------------------------------
    // Les deux seules pannes que la base permet de constater
    // -------------------------------------------------------------------------------------

    @Test
    fun `etat 5 - integrite en defaut, en rouge et avec son code`() {
        val e = etat(faits(integriteRejetee = 0.4, analyseeAtMs = null)) as EtatReveil.Echec
        assertThat(e.erreur.code).isEqualTo("E-ANA-01")
        assertThat(e.erreur.technique).isTrue()
        assertThat(e.erreur.bouton).isNotNull()
    }

    @Test
    fun `etat 5 - nuit tronquee avec des chunks manquants`() {
        val e = etat(
            faits(etatSession = MachineReveil.TRONQUEE, chunksRecus = 12, analyseeAtMs = null),
        ) as EtatReveil.Echec
        assertThat(e.erreur.code).isEqualTo("E-NIGHT-07")
        assertThat(e.erreur.technique).isTrue()
    }

    @Test
    fun `une nuit tronquee mais complete n'est pas une panne`() {
        // Le watchdog marque `TRUNCATED` des 14 h ; si tous les chunks sont la, il n'y a rien de
        // casse. Afficher une carte rouge ici apprendrait a ignorer le rouge.
        assertThat(etat(faits(etatSession = MachineReveil.TRONQUEE, chunksRecus = 17)))
            .isEqualTo(EtatReveil.Rien)
    }

    @Test
    fun `l'etat ne recule jamais - une nuit analysee ne repasse pas en attente`() {
        // Un chunk tardif arrive apres l'analyse : le compte des chunks redevient incomplet.
        // L'ecran doit rester sur l'aval, pas revenir a « en attente de transfert ».
        val e = etat(faits(chunksRecus = 3, totalChunks = 40, analyseeAtMs = fin + h(1)))
        assertThat(e).isNotInstanceOf(EtatReveil.EnAttenteTransfert::class.java)
        assertThat(e).isNotInstanceOf(EtatReveil.Transfert::class.java)
    }
}
