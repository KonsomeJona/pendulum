package com.pendulum.phone.temps

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Le recensement : **aucune duree murale n'a le droit de vivre hors de [Durees]**.
 *
 * `DureesTest` verifie que tout ce qui est dans le catalogue se met a l'echelle. Il ne peut rien
 * dire de ce qui n'y est jamais entre — et c'est le mode de defaillance reel : personne n'ecrira
 * une duree dans `Durees` sans la mettre a l'echelle, tout le monde ecrira un jour
 * `setInitialDelay(30, TimeUnit.SECONDS)` dans un fichier ou c'etait le geste naturel. Le banc
 * tournerait alors avec un chemin a l'heure reelle au milieu de chemins comprimes, et le
 * symptome serait un scenario qui expire au lieu d'un message disant pourquoi.
 *
 * ### Une assertion inversee, dans l'esprit de T11
 *
 * La liste [tolerances] n'est pas une liste de dettes a resorber : ce sont les valeurs dont il a
 * ete **decide** qu'elles ne se comprimaient pas, chacune avec sa raison. Le test echoue dans les
 * deux sens — une duree nouvelle et non recensee le fait tomber, mais une tolerance devenue
 * obsolete aussi. Dans les deux cas il faut relire la decision plutot que la subir.
 *
 * ### Ce qu'il ne voit pas
 *
 * Un scan de source lit du texte, pas du sens : une duree assemblee a l'execution
 * (`unNombre * uneUnite` lus ailleurs) lui echappe. Il attrape les formes que les gens ecrivent
 * reellement, ce qui est suffisant pour ce qu'il vaut — pas une preuve, un filet.
 */
class RecensementDureesTest {

    /** Les formes sous lesquelles une duree murale se glisse dans du code Kotlin. */
    private val motifs = listOf(
        "duree murale construite avec TimeUnit" to
            Regex("""TimeUnit\.\w+\.to\w+\("""),
        "constante ...Ms definie depuis un litteral numerique" to
            Regex("""\bval\s+\w*(?:_MS|Ms)\b[^=\n]*=\s*[^=\n]*(?<![A-Za-z0-9_])\d[\d_]*[\d_]"""),
        "des heures ecrites en clair" to
            Regex("""\d[\d_]*\s*\*\s*3_600_000"""),
        "comparaison a un litteral de milliers" to
            Regex("""[<>]=?\s*\d[\d_]*_000L?\b"""),
        "periode ou delai de WorkManager en clair" to
            Regex("""(?:PeriodicWorkRequestBuilder<[^>]*>\(\s*\d|setInitialDelay\(\s*\d)"""),
    )

    /**
     * Ce qui a le droit de rester en dur, et pourquoi. La cle est `NomDeFichier.kt:extrait`.
     *
     * Deux familles ici : des **facteurs de conversion** (une heure vaut 3 600 000 ms, quelle que
     * soit la vitesse a laquelle on la traverse) et des **volumes**. Les trois seuils de
     * `Mapping.kt` sont l'exemple a garder en tete : ce sont des octets, et les octets sont
     * precisement ce que ce banc refuse de comprimer.
     */
    private val tolerances = mapOf(
        "TimeAnchor.kt:1_000_000L" to
            "conversion nanosecondes -> millisecondes, pas un delai",
        // Les graduations horaires ont quitte `DessinNuit.kt` pour `Dessin.kt` : les trois bandes
        // empilees partagent le meme axe, donc elles doivent partager la fonction qui le gradue.
        // La tolerance suit le code — un facteur de conversion reste un facteur de conversion.
        "Dessin.kt:3_600_000L" to
            "une heure en millisecondes, facteur d'echelle de l'axe temps partage par les trois bandes",
        "Mapping.kt:1_000_000_000L" to
            "seuil d'affichage en gigaoctets — un volume, jamais une duree",
        "Mapping.kt:1_000_000L" to
            "seuil d'affichage en megaoctets — un volume, jamais une duree",
        "Mapping.kt:1_000L" to
            "seuil d'affichage en kilooctets — un volume, jamais une duree",
        // Troisieme famille, et la seule qui soit vraiment une duree : celles qui se mesurent sur
        // l'horloge du **monde exterieur** et non sur celle du produit. Le catalogue les
        // comprimerait avec le reste, ce qui les casserait — a diviseur 600, ce seuil vaudrait
        // 0,67 ms et toute demande de permission passerait pour etouffee.
        "SleepReader.kt:400L" to
            "aller-retour d'une boite de dialogue du systeme — elle ne s'ouvre pas plus vite " +
            "parce qu'un banc comprime le temps",
    )

    @Test
    fun `aucune duree murale ne vit hors du catalogue`() {
        val trouvailles = mutableListOf<String>()
        val toleranceUtilisee = mutableSetOf<String>()

        for (fichier in sourcesPrincipales()) {
            fichier.readLines().forEachIndexed { index, ligne ->
                val code = codeSeul(ligne)
                for ((quoi, motif) in motifs) {
                    if (!motif.containsMatchIn(code)) continue
                    val cle = tolerances.keys.firstOrNull {
                        val (nom, extrait) = it.split(":", limit = 2)
                        fichier.name == nom && code.contains(extrait)
                    }
                    if (cle != null) {
                        toleranceUtilisee += cle
                    } else {
                        trouvailles += "${fichier.name}:${index + 1} [$quoi] " +
                            "${ligne.trim().take(90)} — a deplacer dans Durees"
                    }
                    break
                }
            }
        }

        assertThat(trouvailles)
            .`as`("durees murales ecrites hors de Durees.kt")
            .isEmpty()

        // L'autre sens de l'assertion inversee : une tolerance qui ne correspond plus a rien
        // signifie que le code a change sous elle, et qu'on ne sait plus ce qu'elle protegeait.
        assertThat(toleranceUtilisee)
            .`as`("tolerances devenues sans objet")
            .containsExactlyInAnyOrderElementsOf(tolerances.keys)
    }

    @Test
    fun `le scan lit bien quelque chose`() {
        // Un chemin faux rendrait le test ci-dessus vert sur zero fichier.
        assertThat(sourcesPrincipales()).hasSizeGreaterThan(15)
    }

    /**
     * `src/main/` seulement : les sources de banc de `src/debug/` ont le droit de leurs delais.
     *
     * Le repertoire de travail d'un test Gradle est celui du module, mais ce n'est garanti par
     * aucun contrat : on remonte donc jusqu'a le trouver plutot que de rendre zero fichier et un
     * test vert. `phone` est le nom du repertoire de module a chercher depuis la racine.
     */
    private fun sourcesPrincipales(): List<File> = racine()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        // Le catalogue lui-meme : c'est l'endroit ou les valeurs nominales doivent etre ecrites.
        .filterNot { it.name == "Durees.kt" }
        .toList()

    private fun racine(): File {
        val direct = File("src/main/kotlin")
        if (direct.isDirectory) return direct
        var candidat = File("").absoluteFile
        repeat(4) {
            val essai = File(candidat, "phone/src/main/kotlin")
            if (essai.isDirectory) return essai
            candidat = candidat.parentFile ?: return direct
        }
        return direct
    }

    /**
     * Retire les commentaires. Une KDoc qui **cite** une valeur — « quinze minutes est le minimum
     * d'un `PeriodicWorkRequest` » — explique la decision, elle ne la porte pas, et un test qui
     * ferait taire ces explications-la aurait tue ce qu'il pretend proteger.
     */
    private fun codeSeul(ligne: String): String {
        val nu = ligne.trim()
        if (nu.startsWith("*") || nu.startsWith("/*") || nu.startsWith("//")) return ""
        return ligne.substringBefore("//")
    }
}
