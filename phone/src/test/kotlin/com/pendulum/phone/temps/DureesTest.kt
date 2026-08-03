package com.pendulum.phone.temps

import com.pendulum.format.temps.Temps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Le test qui rend l'echelle **exhaustive** plutot que soigneuse.
 *
 * Il ne verifie pas une liste de durees qu'il faudrait tenir a jour : il construit deux
 * catalogues, l'un a 1 et l'autre a 250, et compare **toutes** les proprietes de la classe par
 * reflexion. Une duree ajoutee a `Durees` sans passer par [Temps.ms] sort identique aux deux
 * echelles et fait tomber le test le jour meme ou elle est ecrite — pas trois mois plus tard,
 * devant un banc dont un seul chemin ne s'accelerait pas et dont personne ne saurait lequel.
 *
 * Son jumeau `RecensementDureesTest` couvre l'autre moitie du probleme : une duree qui n'aurait
 * jamais ete ajoutee ici. Meme test que celui de `:wear`, recopie plutot que partage : les deux
 * modules sont deux applications, sans source set commun ou le poser.
 */
class DureesTest {

    private val diviseur = 250L

    /** Les accesseurs d'instance de [Durees] : toute duree du catalogue en a exactement un. */
    private fun accesseurs(): List<Method> = Durees::class.java.declaredMethods
        .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
        .filter { it.parameterCount == 0 && it.name.startsWith("get") }
        .sortedBy { it.name }

    /** Egalite par contenu : `LongArray.equals` est une identite de reference. */
    private fun memeValeur(a: Any?, b: Any?): Boolean =
        if (a is LongArray && b is LongArray) a.contentEquals(b) else a == b

    @Test
    fun `toutes les durees du catalogue se mettent a l'echelle`() {
        val reel = Durees(Temps.DIVISEUR_REEL)
        val comprime = Durees(diviseur)

        for (accesseur in accesseurs()) {
            when (val nominal = accesseur.invoke(reel)) {
                is Long -> assertThat(accesseur.invoke(comprime) as Long)
                    .`as`("%s : mise a l'echelle par %d", accesseur.name, diviseur)
                    .isEqualTo(Temps.ms(nominal, diviseur))

                is LongArray -> {
                    val obtenu = accesseur.invoke(comprime) as LongArray
                    assertThat(obtenu)
                        .`as`("%s : mise a l'echelle terme a terme par %d", accesseur.name, diviseur)
                        .isEqualTo(Temps.ms(nominal, diviseur))
                }

                // Une duree est un nombre de millisecondes. Un autre type dans ce catalogue est
                // soit une valeur qui n'a rien a y faire, soit une duree exprimee autrement — et
                // dans les deux cas il faut relire la decision avant de continuer.
                else -> throw AssertionError(
                    "${accesseur.name} rend ${nominal?.javaClass?.name} : " +
                        "`Durees` ne contient que des durees en millisecondes",
                )
            }
        }
    }

    @Test
    fun `aucune duree ne survit intacte a la compression`() {
        // Assertion **inversee**, dans l'esprit de T11. Elle affirme que la compression change
        // reellement quelque chose partout. Le jour ou elle echoue, c'est qu'une duree est
        // devenue si courte que le plancher d'une milliseconde la rend indifferente a l'echelle
        // — auquel cas ce n'est plus une duree qu'on comprime, c'est un delai nul qu'on deguise,
        // et la question « pourquoi ce delai existe-t-il ? » redevient ouverte.
        val reel = Durees(Temps.DIVISEUR_REEL)
        val comprime = Durees(diviseur)

        for (accesseur in accesseurs()) {
            assertThat(memeValeur(accesseur.invoke(reel), accesseur.invoke(comprime)))
                .`as`("%s doit changer au diviseur %d", accesseur.name, diviseur)
                .isFalse()
        }
    }

    @Test
    fun `le catalogue n'est pas vide`() {
        // Sans cette borne, supprimer toutes les proprietes de `Durees` rendrait les deux tests
        // ci-dessus verts sur un catalogue vide. Un test qui ne peut plus rien echouer est pire
        // qu'un test absent : il rassure.
        assertThat(accesseurs()).hasSizeGreaterThanOrEqualTo(6)
    }

    @Test
    fun `toutes les durees portent le suffixe Ms`() {
        // La convention de nommage **est** la regle de partage : un `...Ms` est du temps mural et
        // se met a l'echelle ; un `...Ns` ou `...Us` est du temps capteur ou materiel et n'y entre
        // jamais ; un nom d'octets n'a rien a faire ici du tout. Un accesseur `getRotationOctets`
        // qui apparaitrait dans ce catalogue serait la facon exacte dont le plafond de 92 160
        // octets finirait par se comprimer sans que personne l'ait decide.
        for (accesseur in accesseurs()) {
            assertThat(accesseur.name)
                .`as`("nom d'une duree du catalogue")
                .endsWith("Ms")
        }
    }

    @Test
    fun `le catalogue actif suit la variante compilee`() {
        assertThat(Durees.ACTIVES.abandonLectureMs)
            .isEqualTo(Durees(EchelleTemps.DIVISEUR).abandonLectureMs)
    }
}
