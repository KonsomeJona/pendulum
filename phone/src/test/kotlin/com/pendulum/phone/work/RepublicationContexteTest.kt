package com.pendulum.phone.work

import androidx.work.ListenableWorker
import com.pendulum.format.wire.WirePaths
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * L'outbox du contexte du soir : reposer tant que la soiree dure, abandonner ensuite.
 *
 * Le worker est la seconde moitie du correctif — `OrdreDuScellementTest` couvre la premiere, celle
 * qui enfile. Ici on verifie ce que la file fait de ce travail : elle repose l'item, elle
 * redemande un essai tant que le put echoue, et elle **s'arrete** quand la soiree visee est
 * passee.
 *
 * ### Pourquoi le garde-fou d'arret se teste sur la bascule de midi
 *
 * `WirePaths.nightKey` rattache une soiree a la date locale, la bascule ayant lieu **a midi** : un
 * scellement a 22 h et un demarrage a 1 h 30 partagent la meme cle. Un garde-fou naif ecrit sur la
 * date du jour arreterait donc le rejeu a minuit, c'est-a-dire au moment precis ou la montre
 * attend encore l'item. Les deux tests de bascule ci-dessous existent pour cela, et le fuseau y
 * est explicite : la bascule est le coeur du garde-fou, elle ne doit pas dependre de la machine
 * qui execute le test.
 */
class RepublicationContexteTest {

    private val zone = ZoneId.of("Europe/Paris")

    /** La soiree du 2 mars 2026, telle que la nommerait un scellement fait ce soir-la a 22 h. */
    private val soiree = WirePaths.nightKey(instant(jour = 2, heure = 22), zone)

    private fun instant(jour: Int, heure: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 3, jour, heure, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun issue(maintenantMs: Long, poser: () -> Boolean): ListenableWorker.Result =
        PublicationContexteWorker.issue(soiree, maintenantMs, zone, poser)

    @Test
    fun `l'item repose est un succes`() {
        var poses = 0
        val resultat = issue(instant(jour = 2, heure = 22, minute = 30)) { poses++; true }

        assertThat(resultat).isEqualTo(ListenableWorker.Result.success())
        assertThat(poses).`as`("poses de l'item").isEqualTo(1)
    }

    @Test
    fun `un put encore en echec redemande un essai`() {
        // `retry` et non `failure` : c'est le repli exponentiel de la file qui fait tout l'interet
        // de cette outbox. `failure` retirerait le travail au premier echec, donc reproduirait
        // exactement le defaut qu'on repare.
        val resultat = issue(instant(jour = 2, heure = 23)) { false }

        assertThat(resultat).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `passe minuit la soiree court toujours et le rejeu continue`() {
        // 1 h 30, donc encore la soiree du 2 : la bascule est a midi. Un garde-fou ecrit sur la
        // date du jour abandonnerait ici — a l'heure ou la montre attend l'item.
        var poses = 0
        val resultat = issue(instant(jour = 3, heure = 1, minute = 30)) { poses++; false }

        assertThat(resultat).isEqualTo(ListenableWorker.Result.retry())
        assertThat(poses).`as`("poses de l'item").isEqualTo(1)
    }

    @Test
    fun `passe midi la soiree est finie, le travail abandonne sans reposer l'item`() {
        var poses = 0
        val resultat = issue(instant(jour = 3, heure = 12, minute = 1)) { poses++; true }

        // `success` et non `failure` : le travail s'arrete parce que son objet a disparu, pas
        // parce qu'il a echoue. C'est la meme convention que `FetchSchedule.GiveUp`.
        assertThat(resultat).isEqualTo(ListenableWorker.Result.success())
        assertThat(poses)
            .`as`("le Data Layer n'est pas sollicite pour une soiree passee")
            .isZero()
    }

    @Test
    fun `la soiree suivante n'herite pas du rejeu de la precedente`() {
        // Le lendemain 22 h : la cle a bascule, l'item vise ne debloquerait plus rien puisque la
        // montre reclame celui de la soiree en cours. Sans ce garde-fou, le travail reessaierait
        // indefiniment, en promettant un rattrapage qui n'aura pas lieu.
        var poses = 0
        val resultat = issue(instant(jour = 3, heure = 22)) { poses++; false }

        assertThat(resultat).isEqualTo(ListenableWorker.Result.success())
        assertThat(poses).isZero()
    }

    @Test
    fun `le rejeu vise bien la soiree du scellement et non le jour du scellement`() {
        // Garde-fou du garde-fou : si `nightKey` cessait de basculer a midi, tous les tests
        // ci-dessus resteraient verts en mesurant autre chose.
        assertThat(soiree).isEqualTo("2026-03-02")
        assertThat(WirePaths.nightKey(instant(jour = 3, heure = 1, minute = 30), zone))
            .isEqualTo("2026-03-02")
        assertThat(WirePaths.nightKey(instant(jour = 3, heure = 12, minute = 1), zone))
            .isEqualTo("2026-03-03")
    }
}
