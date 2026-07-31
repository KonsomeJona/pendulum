package com.pendulum.phone.ui

import com.pendulum.phone.ui.text.Textes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Le test qui rend le garde-fou 5 executable.
 *
 * `SPEC-v2.md` §3 : « Aucun verbe d'evolution dans les ressources de chaines — verifiable par un
 * test unitaire sur le fichier de textes. »
 *
 * ### Pourquoi c'est un test et pas une consigne de revue
 *
 * Ce projet produit un nombre qui pourrait influencer une decision de dosage. Le mode de
 * defaillance principal n'est pas un bug de calcul : c'est une phrase. « Amelioration de 9/h »
 * affirme une direction que la donnee ne porte pas, parce que sous la plus petite variation
 * detectable, l'ecart n'est pas distinguable du bruit d'une nuit a l'autre. Une consigne de revue
 * s'oublie a la troisieme relecture d'un fichier de 700 lignes ; un test rouge, non.
 *
 * Ce que l'application dit a la place : « Inconclusive », ou « Difference larger than
 * the variability between your nights » — un constat de distinguabilite, jamais une direction, et
 * jamais une cause.
 *
 * ### Deux filets, pas un
 *
 * 1. Le **scan du fichier source** attrape tout, y compris les chaines construites dans des
 *    fonctions d'interpolation et les commentaires — un commentaire fautif finit toujours par
 *    etre recopie dans une chaine.
 * 2. Le **scan par reflexion** attrape ce qui aurait ete deplace dans un autre fichier tout en
 *    restant expose par `Textes`.
 */
class TextesTest {

    /**
     * Les racines proscrites, en minuscules et sans accent apres normalisation.
     *
     * Elles couvrent les formes flechies : « improve », « improvement », « improved » partagent
     * la racine `improv`. La liste vit **ici** et pas dans le fichier de textes — l'y mettre la
     * ferait detecter par son propre test.
     *
     * ### Deux langues, une seule regle
     *
     * L'interface est en anglais depuis que le depot est public : ce sont les racines **anglaises**
     * qui gardent reellement les chaines affichees. Les racines francaises restent dans la liste
     * parce que la langue de travail du projet l'est aussi — les KDoc de `Textes.kt` sont ecrits
     * en francais et le scan du fichier source les lit. Un commentaire qui parle d'« amelioration »
     * finit toujours par etre recopie dans une chaine, et c'est exactement le moment ou ce test
     * doit tomber.
     */
    private val racinesProscrites = listOf(
        // --- anglais : ce que l'utilisateur lit
        "improv",    // improve, improvement, improved
        "worsen",    // worsen, worsening
        "worse",     // worse, getting worse
        "better",    // better, getting better
        "deteriorat", // deteriorate, deterioration
        "declin",    // decline, declining
        "regress",   // regression d'un resultat
        "effectiv",  // effective, effectiveness (« efficacy » tombe sur `efficac`)
        "healing",
        "cured",
        "remission",
        "recovery",
        "it works",
        "reduction in the number",
        "increase in the number",
        "drop in the index",
        "rise in the index",
        // --- francais : les KDoc et les commentaires du fichier de textes
        "amelior",   // amélioration, amélioré, s'améliorer
        "aggrav",    // aggravation, aggravé
        "empir",     // empiré, empirer
        "degrad",    // dégradation d'un résultat (le mot reste permis pour un masque : voir plus bas)
        "progres",   // progrès, progression d'un résultat — attrape aussi « progress »
        "efficac",   // efficace, efficacité — attrape aussi « efficacy »
        "ca marche",
        "ça marche",
        "va mieux",
        "meilleur",
        "pire",
        "guerison",
        "gueri",
        "reduction du nombre",
        "augmentation du nombre",
        "baisse de l'index",
        "hausse de l'index",
    )

    /**
     * Les seules occurrences tolerees, parce qu'elles ne qualifient pas un **resultat de sante**.
     * Chacune doit etre justifiee ici avant d'etre ajoutee ; la liste est volontairement courte
     * et son allongement est le signal qu'on est en train de contourner la regle.
     */
    private val exceptions = listOf(
        "degraded mask",      // qualifie la qualite d'une mesure, pas une evolution clinique
        "degraded quality",
        "masque degrade",
        "qualite degradee",
    )

    private fun normaliser(s: String): String = s
        .lowercase()
        .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
        .replace('à', 'a').replace('â', 'a')
        .replace('î', 'i').replace('ï', 'i')
        .replace('ô', 'o').replace('ö', 'o')
        .replace('ù', 'u').replace('û', 'u').replace('ü', 'u')
        .replace('ç', 'c')

    private fun sansExceptions(s: String): String =
        exceptions.fold(s) { acc, e -> acc.replace(e, "") }

    @Test
    fun `le fichier de textes ne contient aucun verbe d'evolution`() {
        val fichier = fichierTextes()
        assertThat(fichier)
            .withFailMessage(
                "Textes.kt est introuvable. Ce test est le garde-fou 5 : s'il ne peut pas lire " +
                    "le fichier de textes, il ne garantit rien et doit echouer plutot que passer.",
            )
            .isNotNull()

        val contenu = sansExceptions(normaliser(fichier!!.readText()))
        val trouvees = racinesProscrites.filter { contenu.contains(it) }

        assertThat(trouvees)
            .withFailMessage(
                "Verbe(s) d'evolution trouve(s) dans %s : %s.\n" +
                    "Pendulum ne dit jamais qu'un chiffre a evolue dans un sens : sous la plus petite " +
                    "variation detectable, la direction n'est pas distinguable de la variabilite " +
                    "nuit a nuit. Formulations admises : « Inconclusive » et " +
                    "« Difference larger than the variability between your nights ».",
                fichier.path, trouvees,
            )
            .isEmpty()
    }

    @Test
    fun `aucune chaine exposee par Textes ne contient de verbe d'evolution`() {
        val fautives = chainesDe(Textes).filter { chaine ->
            val n = sansExceptions(normaliser(chaine))
            racinesProscrites.any { n.contains(it) }
        }
        assertThat(fautives).isEmpty()
    }

    @Test
    fun `l'avertissement dit ce qu'il doit dire`() {
        val n = normaliser(Textes.Avertissement.CORPS)
        // Les cinq affirmations non negociables. Si l'une saute a la reecriture, le test tombe.
        assertThat(n).contains("this is not an official health application")
        assertThat(n).contains("this is not a medical device")
        assertThat(n).contains("makes no diagnosis")
        assertThat(n).contains("no treatment decision should rest on")
        assertThat(n).contains("does not measure your breathing")
        // Et il figure aussi, condense, en tete de chaque export.
        val e = normaliser(Textes.Avertissement.BANDEAU_EXPORT)
        assertThat(e).contains("this is not a medical device")
        assertThat(e).contains("no treatment decision should rest on")
    }

    @Test
    fun `aucun texte ne promet un diagnostic`() {
        val fautives = chainesDe(Textes).filter {
            val n = normaliser(it)
            n.contains("diagnoses your") || n.contains("you have a syndrome")
        }
        assertThat(fautives).isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // Outils
    // ---------------------------------------------------------------------------------

    /** Remonte depuis le repertoire de travail jusqu'a trouver le fichier de textes. */
    private fun fichierTextes(): File? {
        val relatif = "src/main/kotlin/com/pendulum/phone/ui/text/Textes.kt"
        var dossier: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dossier != null) {
            for (candidat in listOf(File(dossier, relatif), File(dossier, "phone/$relatif"))) {
                if (candidat.isFile) return candidat
            }
            dossier = dossier.parentFile
        }
        return null
    }

    /** Toutes les constantes de type `String` exposees par [Textes] et ses objets imbriques. */
    private fun chainesDe(racine: Any): List<String> {
        val vues = HashSet<Class<*>>()
        val sortie = ArrayList<String>()

        fun visiter(instance: Any) {
            val classe = instance.javaClass
            if (!vues.add(classe)) return
            classe.declaredFields.forEach { champ ->
                runCatching {
                    champ.isAccessible = true
                    when (val v = champ.get(instance)) {
                        is String -> sortie += v
                        null -> Unit
                        else -> if (v.javaClass.name.startsWith("com.pendulum.phone.ui.text")) visiter(v)
                    }
                }
            }
            classe.declaredClasses.forEach { imbriquee ->
                runCatching {
                    val champInstance = imbriquee.getDeclaredField("INSTANCE")
                    champInstance.isAccessible = true
                    champInstance.get(null)?.let { visiter(it) }
                }
            }
        }

        visiter(racine)
        return sortie
    }
}
