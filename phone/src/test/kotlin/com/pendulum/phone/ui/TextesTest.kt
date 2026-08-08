package com.pendulum.phone.ui

import com.pendulum.phone.ui.text.UiText
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
 * ### Deux filets, et pourquoi il en faut deux
 *
 * 1. Le **scan de `res/values/strings.xml`**, ou vivent tous les textes que l'interface affiche.
 *    Le fichier entier est scanne, **commentaires XML compris** : les KDoc de l'ancien fichier de
 *    textes y ont ete portees, et un commentaire fautif finit toujours par etre recopie dans une
 *    chaine.
 * 2. Le **scan de `ui/text/Textes.kt`**, qui ne porte plus que le pont `UiText` et deux
 *    utilitaires. Il n'y a plus rien a y trouver, et c'est precisement pourquoi le scan reste :
 *    remettre une phrase anglaise en dur dans ce fichier est le raccourci le plus naturel du monde,
 *    et il doit rester rouge.
 *
 * ### La garde anti-test-vide
 *
 * Le scan des ressources commence par verifier qu'il a **trouve son fichier** et qu'il y a **lu
 * quelque chose**. Un test de garde-fou qui ne trouve pas sa cible doit echouer, pas
 * passer : un `filter` sur une chaine vide rend une liste vide, et une liste vide est exactement ce
 * que ce test attend en cas de succes. C'est le seul mode de defaillance qui rendrait le garde-fou
 * silencieusement inutile.
 */
class TextesTest {

    /**
     * Les racines proscrites, en minuscules et sans accent apres normalisation.
     *
     * Elles couvrent les formes flechies : « improve », « improvement », « improved » partagent
     * la racine `improv`. La liste vit **ici** et pas dans les fichiers de textes — les y mettre
     * les ferait detecter par leur propre test.
     *
     * ### Deux langues, une seule regle
     *
     * L'interface est en anglais depuis que le depot est public : ce sont les racines **anglaises**
     * qui gardent reellement les chaines affichees. Les racines francaises restent dans la liste
     * parce que la langue de travail du projet l'est aussi — les commentaires de `strings.xml` et
     * les KDoc de `Textes.kt` sont ecrits en francais et les scans de fichier les lisent. Un
     * commentaire qui parle d'« amelioration » finit toujours par etre recopie dans une chaine, et
     * c'est exactement le moment ou ce test doit tomber.
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
        // --- francais : les commentaires des deux fichiers de textes
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

    // ---------------------------------------------------------------------------------
    // Filet 1 : les ressources de chaines
    // ---------------------------------------------------------------------------------

    @Test
    fun `les ressources de chaines ne contiennent aucun verbe d'evolution`() {
        val fichier = fichierRessources()
        assertThat(fichier)
            .withFailMessage(
                "strings.xml est introuvable. Ce test est le garde-fou 5 : s'il ne peut pas lire " +
                    "les ressources de chaines, il ne garantit rien et doit echouer plutot que passer.",
            )
            .isNotNull()

        val brut = fichier!!.readText()

        // La garde anti-test-vide. Un fichier present mais vide, ou dont le format aurait change,
        // rendrait zero chaine — et un `filter` sur zero chaine rend la liste vide que ce test
        // attend en cas de succes.
        val chaines = chainesDuXml(brut)
        assertThat(chaines)
            .withFailMessage(
                "Aucune chaine lue dans %s. Le garde-fou ne verifie plus rien : soit le fichier " +
                    "est vide, soit la forme `<string name=\"…\">…</string>` a change.",
                fichier.path,
            )
            .isNotEmpty()

        // Le fichier **entier**, commentaires XML compris : les KDoc y ont ete portees, et c'est
        // dans un commentaire qu'une formulation fautive apparait en premier.
        val contenu = sansExceptions(normaliser(brut))
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

    // ---------------------------------------------------------------------------------
    // Filet 2 : ce qui est reste dans le fichier de textes
    // ---------------------------------------------------------------------------------

    @Test
    fun `le fichier de textes ne contient aucun verbe d'evolution`() {
        val fichier = fichierTextes()
        assertThat(fichier)
            .withFailMessage(
                "Textes.kt est introuvable. Ce test est le garde-fou 5 : s'il ne peut pas lire " +
                    "le fichier de textes, il ne garantit rien et doit echouer plutot que passer.",
            )
            .isNotNull()

        val brut = fichier!!.readText()
        assertThat(brut)
            .withFailMessage(
                "%s est vide. Le garde-fou ne verifie plus rien.",
                fichier.path,
            )
            .isNotBlank()

        val contenu = sansExceptions(normaliser(brut))
        val trouvees = racinesProscrites.filter { contenu.contains(it) }

        assertThat(trouvees)
            .withFailMessage(
                "Verbe(s) d'evolution trouve(s) dans %s : %s.\n" +
                    "Meme regle que pour les ressources : deplacer une phrase de `strings.xml` vers " +
                    "ce fichier ne la sort pas du garde-fou.",
                fichier.path, trouvees,
            )
            .isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // Le cablage : chaque identifiant du code trouve sa chaine
    // ---------------------------------------------------------------------------------

    /**
     * Une ressource renommee d'un seul cote ne compile pas ; une ressource **vide** compile tres
     * bien et fait passer toutes les assertions `doesNotContain` du module. Ce test refuse donc les
     * chaines vides, qui sont le seul echec silencieux que ce format autorise.
     */
    @Test
    fun `aucune chaine de ressource n'est vide`() {
        val vides = chainesDuXml(fichierRessources()!!.readText())
            .filterValues { it.isBlank() }
            .keys
        assertThat(vides).isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // Le contenu non negociable de l'avertissement
    // ---------------------------------------------------------------------------------

    @Test
    fun `l'avertissement dit ce qu'il doit dire`() {
        val ressources = chainesDuXml(fichierRessources()!!.readText())

        val corps = normaliser(
            ressources["notice_body"]
                ?: error("`notice_body` a disparu de strings.xml : l'avertissement n'a plus de corps."),
        )
        // Les cinq affirmations non negociables. Si l'une saute a la reecriture, le test tombe.
        assertThat(corps).contains("this is not an official health application")
        assertThat(corps).contains("this is not a medical device")
        assertThat(corps).contains("makes no diagnosis")
        assertThat(corps).contains("no treatment decision should rest on")
        assertThat(corps).contains("does not measure your breathing")

        // Et il figure aussi, condense, en tete de chaque export.
        val bandeau = normaliser(
            ressources["notice_export_banner"]
                ?: error("`notice_export_banner` a disparu : l'export n'a plus son avertissement."),
        )
        assertThat(bandeau).contains("this is not a medical device")
        assertThat(bandeau).contains("no treatment decision should rest on")
    }

    @Test
    fun `aucun texte ne promet un diagnostic`() {
        val toutes = chainesDuXml(fichierRessources()!!.readText()).values
        val fautives = toutes.filter {
            val n = normaliser(it)
            n.contains("diagnoses your") || n.contains("you have a syndrome")
        }
        assertThat(fautives).isEmpty()
    }

    // ---------------------------------------------------------------------------------
    // Outils
    // ---------------------------------------------------------------------------------

    /** Remonte depuis le repertoire de travail jusqu'a trouver le fichier de textes. */
    private fun fichierTextes(): File? =
        remonterVers("src/main/kotlin/com/pendulum/phone/ui/text/Textes.kt")

    /** Meme remontee, pour les ressources de chaines. */
    private fun fichierRessources(): File? = remonterVers("src/main/res/values/strings.xml")

    private fun remonterVers(relatif: String): File? {
        var dossier: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dossier != null) {
            for (candidat in listOf(File(dossier, relatif), File(dossier, "phone/$relatif"))) {
                if (candidat.isFile) return candidat
            }
            dossier = dossier.parentFile
        }
        return null
    }

    /**
     * Les chaines de `strings.xml`, par nom.
     *
     * Un analyseur XML complet serait plus rigoureux, mais il masquerait ce que ce test cherche : la
     * regex echoue bruyamment — zero chaine — si la forme du fichier change, et la garde
     * anti-test-vide transforme cet echec en test rouge. Un analyseur tolerant, lui, rendrait une
     * liste partielle sans rien dire.
     */
    private fun chainesDuXml(brut: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(brut)
            .associate { it.groupValues[1] to it.groupValues[2] }

}

internal object Ressources {

    private val parId: Map<Int, String> by lazy {
        com.pendulum.phone.R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { champ ->
                champ.isAccessible = true
                (champ.get(null) as Int) to champ.name
            }
    }

    private val parNom: Map<String, String> by lazy {
        val fichier = fichierStrings()
            ?: error("res/values/strings.xml est introuvable depuis ${System.getProperty("user.dir")}")
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(fichier.readText())
            .associate { it.groupValues[1] to desechapper(it.groupValues[2]) }
            .also { require(it.isNotEmpty()) { "Aucune chaine lue dans ${fichier.path}" } }
    }

    /** Le texte brut d'une ressource, sans substitution. */
    fun lire(@androidx.annotation.StringRes id: Int): String {
        val nom = parId[id] ?: error("Aucun nom de ressource pour l'identifiant $id")
        return parNom[nom] ?: error("`$nom` est reference par le code et absent de strings.xml")
    }

    /** La meme resolution que `UiText.resoudre`, arguments imbriques compris. */
    fun resoudre(t: UiText): String = when (t) {
        is UiText.Brut -> t.valeur
        is UiText.Res ->
            if (t.args.isEmpty()) {
                lire(t.id)
            } else {
                val resolus = t.args.map { if (it is UiText) resoudre(it) else it }
                String.format(java.util.Locale.UK, lire(t.id), *resolus.toTypedArray())
            }
    }

    /**
     * Les echappements d'aapt, appliques a la main.
     *
     * Ce ne sont pas tous ceux du format : ce sont ceux que ce fichier emploie. En ajouter un
     * suppose qu'une chaine l'utilise, et c'est le moment de verifier qu'elle s'affiche bien.
     */
    private fun desechapper(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun fichierStrings(): java.io.File? {
        val relatif = "src/main/res/values/strings.xml"
        var dossier: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dossier != null) {
            for (c in listOf(java.io.File(dossier, relatif), java.io.File(dossier, "phone/$relatif"))) {
                if (c.isFile) return c
            }
            dossier = dossier.parentFile
        }
        return null
    }
}
