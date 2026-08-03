package com.pendulum.wear.record

import com.google.android.gms.wearable.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Ce que `PHONE_UNREACHABLE` mesure reellement, ecrit une fois pour toutes.
 *
 * Trois sessions de banc ont tourne autour de ce predicat, et deux d'entre elles ont propose une
 * correction que la troisieme a mesuree fausse. Ce qu'aucune ne pouvait faire, faute de predicat
 * separe de son appel GMS, c'est **epingler le comportement courant** — y compris ses defauts —
 * de facon qu'un changement futur soit un choix et non un effet de bord.
 *
 * Le test ne dit donc pas « c'est correct ». Il dit ce que c'est, avec la mesure en face :
 * `BANC-ESSAI.md` §7.1, §11.3 et §12.2.
 */
class PreflightJoignabiliteTest {

    private fun noeud(nom: String, proche: Boolean) = object : Node {
        override fun getId(): String = nom
        override fun getDisplayName(): String = nom
        override fun isNearby(): Boolean = proche
    }

    @Test
    fun `aucun noeud, telephone injoignable`() {
        assertThat(Preflight.phoneReachable(emptyList())).isFalse()
    }

    @Test
    fun `un noeud proche, telephone joignable`() {
        assertThat(Preflight.phoneReachable(listOf(noeud("Pixel 10 Pro Fold", proche = true))))
            .isTrue()
    }

    /**
     * **Le cas qui decide, et il n'est pas celui qu'on croyait.**
     *
     * `isNearby=false` ne veut pas dire « injoignable » : mesure du §12.2, Bluetooth coupe et les
     * deux appareils sur le meme WiFi, un `DataItem` publie par le telephone arrive sur la montre
     * en moins de 45 s et une suppression en moins de 60 s. Rendre `false` ici — la correction
     * proposee par le §11.3 — afficherait « telephone injoignable » pendant que la
     * synchronisation se fait.
     *
     * Le jour ou ce test tombe, c'est que quelqu'un a applique `nodes.any { it.isNearby }`. Qu'il
     * lise la mesure avant de changer le test.
     */
    @Test
    fun `un noeud hors de portee de proximite reste joignable`() {
        assertThat(Preflight.phoneReachable(listOf(noeud("Pixel 10 Pro Fold", proche = false))))
            .`as`("le Data Layer bascule sur le WiFi : isNearby=false n'est pas un transport mort")
            .isTrue()
    }

    /**
     * Le faux vert qui reste, nomme plutot que tu.
     *
     * Sur emulateur, l'emulateur telephone tue, `connectedNodes` rend toujours un noeud (§7.1) —
     * et ce predicat rend donc « joignable » pour un telephone qui n'existe plus. Il n'a pas ete
     * reproduit sur materiel reel, faute d'avoir pu rendre le telephone injoignable sans perdre
     * le lien `adb` qui sert a le mesurer. Le test epingle l'etat des lieux : la seule chose que
     * ce predicat sache distinguer est « une montre a ete appairee au moins une fois ».
     */
    @Test
    fun `un noeud appaire suffit, meme si plus rien ne tourne en face`() {
        val nodes = listOf(noeud("telephone eteint", proche = false))
        assertThat(nodes).`as`("la liste reste non vide : c'est tout le faux vert").isNotEmpty()
        assertThat(Preflight.phoneReachable(nodes)).isTrue()
    }
}
