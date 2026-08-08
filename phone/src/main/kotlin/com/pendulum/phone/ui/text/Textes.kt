package com.pendulum.phone.ui.text

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/**
 * Le pont entre la couche de modele, qui n'a pas de `Context`, et `res/values/strings.xml`.
 *
 * ### Ce que ce fichier a remplace
 *
 * Il portait `object Textes`, 461 constantes anglaises en dur, et sa KDoc expliquait pourquoi elles
 * n'etaient pas dans `strings.xml` : un objet Kotlin se teste, un `strings.xml` non. L'argument
 * etait vrai et il coutait la traduction — une chaine anglaise compilee dans un `object` ne se
 * localise pas, quelle que soit la qualite de son test. Les deux choses sont desormais tenues
 * ensemble : les textes vivent dans les ressources, et `ui/TextesTest.kt` les y lit.
 *
 * ### Le probleme que [UiText] resout
 *
 * La moitie des textes de ce produit n'est pas ecrite par un ecran. Elle est **assemblee par des
 * fonctions pures** — `Aggregat.position`, `Situations.nuit`, `Mapping.drapeaux`,
 * `CheminDeCalcul.de`, `PorteP1.campagne` — dont la justesse est verifiee par des tests JVM sans
 * Android. Leur donner un `Context` les ferait sortir de ce regime, et c'est exactement ce regime
 * qui rend la regle des « 3 nuits minimum » ou le refus d'ajustement de rythme verifiables.
 *
 * Ces fonctions rendent donc un [UiText] : un **identifiant de ressource et ses arguments**, pas un
 * texte. Elles restent pures, comparables et testables ; la couche Compose resout au dernier
 * moment, avec la locale de l'appareil au moment de l'affichage — ce qu'une chaine resolue trop tot
 * ne saurait pas faire.
 *
 * ### Ce qui reste en Kotlin, et pourquoi ce n'est pas du texte
 *
 * [NomsDeFichier] et [Formats]. Un nom de fichier se trie, se cherche et se recopie dans un
 * message : le traduire ferait dependre le nom du fichier de la langue du telephone qui l'a ecrit,
 * et deux exports du meme utilisateur ne se rangeraient plus ensemble. Un separateur de milliers
 * n'est pas une chaine non plus — c'est une regle de formatage, qui suivra `NumberFormat` le jour
 * ou une seconde langue arrivera.
 */
@Immutable
sealed interface UiText {

    /**
     * Un identifiant de ressource et ses arguments.
     *
     * Les arguments sont une **`List` et non un `vararg`** : un `vararg` dans une `data class`
     * rend un `equals` par identite de tableau, et c'est precisement l'egalite structurelle que les
     * tests JVM utilisent pour verifier qu'une fonction pure a rendu le bon texte. Un argument peut
     * lui-meme etre un [UiText] — voir [resoudre], qui descend dedans.
     */
    @Immutable
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * Un texte qui ne vient pas des ressources parce qu'il n'est pas traduisible : un nom
     * d'application tiers lu dans Health Connect, un pourcentage deja mis en forme, une date.
     *
     * Il n'est pas une porte de sortie pour du texte d'interface : tout ce qui s'ecrit en anglais
     * dans le code appartient a `strings.xml`. Ce qui passe par ici est ce que l'appareil ou la
     * mesure a produit.
     */
    @Immutable
    data class Brut(val valeur: String) : UiText
}

/** Fabrique lisible : `texte(cle, n, m)` plutot que `UiText.Res(cle, listOf(n, m))`. */
fun texte(@StringRes id: Int, vararg args: Any): UiText.Res = UiText.Res(id, args.toList())

/** Fabrique pour ce que la mesure ou l'appareil a produit. Voir [UiText.Brut]. */
fun texte(valeur: String): UiText.Brut = UiText.Brut(valeur)

/**
 * La resolution, hors composition.
 *
 * Elle est ecrite ici plutot que dans le composable pour deux raisons. Elle est **recursive** : un
 * argument peut etre un [UiText], ce qui permet d'ecrire « une valeur et son unite » sans que la
 * fonction pure qui la compose ait a connaitre l'unite en clair. Et elle est utilisable **hors
 * Compose** — un exportateur ou un service qui aurait un `Resources` sous la main lit le meme
 * texte que l'ecran, sans qu'il existe une seconde mise en forme quelque part.
 */
fun UiText.resoudre(res: Resources): String = when (this) {
    is UiText.Brut -> valeur
    is UiText.Res ->
        if (args.isEmpty()) {
            res.getString(id)
        } else {
            val resolus = args.map { if (it is UiText) it.resoudre(res) else it }
            res.getString(id, *resolus.toTypedArray())
        }
}

/** La resolution en composition : la locale est celle de l'appareil au moment de l'affichage. */
@Composable
fun UiText.resoudre(): String = resoudre(LocalContext.current.resources)

/**
 * Les noms de fichier proposes au selecteur SAF.
 *
 * Ce ne sont pas des textes d'interface et ils ne sont pas dans `strings.xml` : un nom de fichier
 * se trie, se cherche et se recopie dans un message. Le traduire ferait dependre le nom du fichier
 * de la langue du telephone qui l'a ecrit, et un dossier d'exports cesserait de s'ordonner tout
 * seul — ce qui est la seule chose qu'on lui demande.
 */
object NomsDeFichier {

    /** `pendulum-night-2026-03-12.md` : le rapport d'**une** nuit, en texte. Quelques kilo-octets. */
    fun rapportDeNuit(jour: String) = "pendulum-night-$jour.md"

    /**
     * `pendulum-night-2026-03-12.bundle` : le signal brut, le contexte scelle et l'hypnogramme.
     *
     * Il est nomme separement du rapport parce que les deux ne servent pas au meme lecteur : le
     * rapport se lit et s'imprime, le paquet se reimporte. Les confondre fait emporter chez le
     * medecin quatre-vingt-dix megaoctets d'accelerometrie.
     */
    fun paquetDeNuit(jour: String) = "pendulum-night-$jour.bundle"

    /** `pendulum-report-2026-03-15.md` : le rapport de campagne. Triable, sans ambiguite. */
    fun rapportDeCampagne(jour: String) = "pendulum-report-$jour.md"

    /** Le rapport de la porte P1, en CSV. Un seul par appareil, donc pas de date dans le nom. */
    const val RAPPORT_P1 = "pendulum-p1.csv"
}

/** Les mises en forme qui ne sont pas du texte. */
object Formats {

    /**
     * Virgule comme separateur de milliers, convention anglaise.
     *
     * Ce n'est pas une chaine, c'est une regle de formatage : le jour ou une seconde langue
     * arrive, c'est `NumberFormat` qu'il faudra appeler ici, pas une ressource qu'il faudra
     * traduire.
     */
    fun milliers(n: Int): String =
        n.toString().reversed().chunked(3).joinToString(",").reversed()
}
