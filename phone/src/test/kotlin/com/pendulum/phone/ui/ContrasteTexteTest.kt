package com.pendulum.phone.ui

import androidx.compose.ui.graphics.Color
import com.pendulum.phone.ui.theme.PendulumColors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Le contraste du **texte**, et le fond sur lequel il atterrit reellement.
 *
 * ### Le defaut que ce fichier existe pour empecher
 *
 * L'ecran d'avertissement du premier lancement — celui qui porte « ceci n'est pas un dispositif
 * medical », rendu non esquivable par un defilement bloquant et quatre acquittements — s'affichait
 * en gris tres clair sur gris tres clair. **Contraste mesure : 1,06:1.** Il est reste illisible a
 * travers des `@Preview`, un test de contraste calcule et une revue.
 *
 * La cause n'etait pas une couleur mal choisie : les deux palettes sont correctes et le sont
 * restees. C'est que **rien ne peignait le fond**. Compose ne peint pas de fond de lui-meme, et le
 * module `:phone` ne declarait aucun `android:theme` ; Android applique alors
 * `Theme.DeviceDefault.Light.DarkActionBar` — un `windowBackground` clair sous une barre d'action
 * sombre, ce qui explique au passage pourquoi la barre de titre, elle, paraissait juste : elle
 * n'etait pas la notre. Partout ou la composition peignait un fond par accident — le `Scaffold` de
 * `PendulumNavHost`, dont le `containerColor` vaut `colorScheme.background` — le defaut etait
 * invisible. L'assistant du premier lancement est une simple `Column` : le fond clair de la
 * fenetre y restait visible sous un texte ecrit dans la palette sombre.
 *
 * ### Pourquoi ces deux tests, et pas un test d'egalite de couleurs
 *
 * Un test qui verifierait « `MaterialTheme.colorScheme.background` egale
 * `LocalPendulumColors.current.background` » serait passe au vert : les deux descendent deja d'une
 * source unique, `PendulumColors.toMaterialScheme()`. Ce n'est pas la qu'etait l'ecart. L'ecart
 * etait entre la palette Compose et **la fenetre Android**, qui est une troisieme source que
 * personne ne regardait.
 *
 * Le fond de fenetre est donc lu la ou il vit — `res/values/themes.xml` — et verse dans la liste
 * des fonds contre lesquels chaque role de texte est mesure. Sur l'arbre d'avant la correction, le
 * fichier n'existe pas : les deux tests tombent, le premier en disant que rien n'est declare, le
 * second en refusant de mesurer contre un fond qu'il ne connait pas.
 *
 * ### Le seuil
 *
 * WCAG 2.1 critere 1.4.3 : **4,5:1** pour le texte courant. Aucune exemption ici — la seule qui
 * existe dans la norme vise le texte de grande taille (18 pt, ou 14 pt gras), et les trois roles
 * de texte du produit servent tous a du corps de texte quelque part.
 */
class ContrasteTexteTest {

    /**
     * Le fond de fenetre declare par le module, lu dans la ressource.
     *
     * `null` quand aucun theme n'est declare — c'est-a-dire l'etat qui a produit le defaut, et
     * c'est cet etat que les deux tests ci-dessous refusent.
     */
    private fun fondDeFenetreDeclare(): Color? {
        val themes = fichierDuModule("src/main/res/values/themes.xml") ?: return null
        val hex = Regex("""android:windowBackground"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*<""")
            .find(themes.readText())
            ?.groupValues
            ?.get(1)
            ?: return null
        return Color(hex.removePrefix("#").padStart(8, 'F').toLong(16).toULong().toInt())
    }

    private fun themeDeclareAuManifeste(): Boolean =
        fichierDuModule("src/main/AndroidManifest.xml")
            ?.readText()
            ?.contains("android:theme=\"@style/Theme.Pendulum\"")
            ?: false

    /**
     * **Le test qui aurait attrape le defaut.**
     *
     * Il ne parle pas de couleurs mais de sources : la fenetre doit avoir un fond, ce fond doit
     * etre declare, et il doit valoir exactement celui de la palette par defaut. Trois conditions,
     * et l'arbre d'avant la correction en ratait les trois.
     */
    @Test
    fun `le fond de la fenetre est declare, et c'est celui de la palette sombre`() {
        assertThat(themeDeclareAuManifeste())
            .describedAs(
                "AndroidManifest.xml doit porter android:theme=\"@style/Theme.Pendulum\". Sans " +
                    "android:theme, Android applique Theme.DeviceDefault.Light.DarkActionBar, " +
                    "dont le windowBackground est CLAIR : il transparait partout ou la " +
                    "composition n'en peint pas, et le texte de la palette sombre y devient " +
                    "illisible.",
            )
            .isTrue()

        val fond = fondDeFenetreDeclare()
        assertThat(fond)
            .describedAs(
                "res/values/themes.xml doit declarer android:windowBackground pour " +
                    "Theme.Pendulum : c'est la seule couleur visible avant que Compose ne " +
                    "s'execute.",
            )
            .isNotNull()

        assertThat(fond)
            .describedAs(
                "le fond de fenetre et PendulumColors.Dark.background sont la meme couleur vue " +
                    "de deux endroits ; les laisser diverger, c'est reintroduire le defaut sous " +
                    "la forme d'un flash au demarrage.",
            )
            .isEqualTo(PendulumColors.Dark.background)
    }

    /**
     * Chaque role de texte, contre chaque fond qu'il peut rencontrer — **le fond de fenetre
     * compris**, ce qui est exactement ce qui manquait.
     *
     * Les fonds de la palette et le fond de la fenetre sont mesures dans le meme tableau, sans
     * distinction, parce que l'utilisateur ne fait pas la difference : il lit du texte sur ce que
     * l'appareil affiche, quel que soit le calque qui l'a peint.
     */
    @Test
    fun `chaque role de texte tient le 4,5 pour 1 sur chaque fond qu'il peut rencontrer`() {
        val fenetre = fondDeFenetreDeclare()
        assertThat(fenetre)
            .describedAs("fond de fenetre introuvable : voir le test precedent")
            .isNotNull()

        data class Mesure(val nom: String, val ratio: Double)

        val mesures = buildList {
            listOf(
                "sombre" to PendulumColors.Dark,
                "claire (export)" to PendulumColors.Light,
            ).forEach { (nomPalette, p) ->
                val fonds = buildList {
                    add("background" to p.background)
                    add("surface" to p.surface)
                    add("surfaceElevated" to p.surfaceElevated)
                    add("surfaceMuted" to p.surfaceMuted)
                    // Le fond de fenetre n'appartient qu'a la palette par defaut : c'est celle
                    // que le theme force, et la seule que la fenetre puisse montrer.
                    if (p.isDark) add("windowBackground (res/values/themes.xml)" to fenetre!!)
                }
                val textes = listOf(
                    "textPrimary" to p.textPrimary,
                    "textSecondary" to p.textSecondary,
                    "textTertiary" to p.textTertiary,
                    // `attention` porte du texte : « qualite degradee », « nuit courte ». Les deux
                    // autres teintes semantiques aussi, dans la bande d'etat technique.
                    "attention" to p.attention,
                    "error" to p.error,
                    "success" to p.success,
                )
                fonds.forEach { (nomFond, fond) ->
                    textes.forEach { (nomTexte, texte) ->
                        add(
                            Mesure(
                                "palette $nomPalette : $nomTexte sur $nomFond",
                                Colorimetrie.ratio(texte, fond),
                            ),
                        )
                    }
                }
            }
        }

        print(
            buildString {
                append("\ncontraste du texte (WCAG 1.4.3, seuil ${Colorimetrie.TEXTE_MINIMUM}:1)\n")
                mesures.forEach {
                    append(
                        "  %-58s %5.2f:1  %s%n".format(
                            it.nom,
                            it.ratio,
                            if (it.ratio >= Colorimetrie.TEXTE_MINIMUM) "ok" else "ECHEC",
                        ),
                    )
                }
            },
        )

        val echecs = mesures.filter { it.ratio < Colorimetrie.TEXTE_MINIMUM }
        assertThat(echecs)
            .withFailMessage("Texte sous %s:1 (WCAG 1.4.3) : %s", Colorimetrie.TEXTE_MINIMUM, echecs)
            .isEmpty()
    }

    /**
     * La projection Material rend la palette telle quelle, sur les quatre roles qui decident d'un
     * fond et de la couleur du texte pose dessus.
     *
     * C'est ce qui rend vraie la phrase « `MaterialTheme.colorScheme.background` et
     * `LocalPendulumColors.current.background` sont la meme couleur ». Elle l'etait deja ; elle
     * n'etait garantie par rien.
     */
    @Test
    fun `la projection Material rend exactement la palette sur les roles de fond et de texte`() {
        listOf(PendulumColors.Dark, PendulumColors.Light).forEach { p ->
            val m = p.toMaterialScheme()
            assertThat(m.background).describedAs("background").isEqualTo(p.background)
            assertThat(m.onBackground).describedAs("onBackground").isEqualTo(p.textPrimary)
            assertThat(m.surface).describedAs("surface").isEqualTo(p.surface)
            assertThat(m.onSurface).describedAs("onSurface").isEqualTo(p.textPrimary)
        }
    }

    /** Remonte depuis le repertoire de travail jusqu'a trouver un fichier du module `:phone`. */
    private fun fichierDuModule(relatif: String): File? {
        var dossier: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dossier != null) {
            for (candidat in listOf(File(dossier, relatif), File(dossier, "phone/$relatif"))) {
                if (candidat.isFile) return candidat
            }
            dossier = dossier.parentFile
        }
        return null
    }
}
