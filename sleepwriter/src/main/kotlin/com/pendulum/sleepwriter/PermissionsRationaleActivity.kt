package com.pendulum.sleepwriter

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView

/**
 * L'ecran de justification des permissions Health Connect.
 *
 * Il existe pour une raison mecanique et non redactionnelle : sans les deux declarations du
 * manifeste qui pointent vers lui, Health Connect **n'affiche pas l'application dans sa liste**,
 * et le symptome est une absence, pas une erreur. Or c'est par cette liste qu'on accorde
 * `WRITE_SLEEP` a la main le jour ou la sequence UiAutomator du banc rate.
 *
 * Le texte dit ce que cet outil est, pour que quiconque le trouve installe sur un appareil
 * comprenne en trois lignes qu'il s'agit d'un simulateur de banc et non d'une source de sommeil
 * reelle.
 */
class PermissionsRationaleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                movementMethod = ScrollingMovementMethod()
                textSize = 16f
                text = JUSTIFICATION
            }
        )
    }

    private companion object {
        val JUSTIFICATION = """
            SleepWriter ${BuildConfig.SOURCE_LABEL} — outil de banc d'essai

            Cette application n'est pas un produit et ne mesure rien. Elle ecrit des sessions de
            sommeil fabriquees dans Health Connect, pour verifier que Pendulum sait les lire.

            Elle existe parce que Pendulum ne peut pas ecrire lui-meme ce qu'il lit : la duree de
            sommeil doit venir d'un appareil independant de la montre de cheville, sinon la mesure
            devient circulaire. Pendulum ne declare donc que des permissions de lecture, et cet
            outil est la contrepartie qui joue le role de l'application tierce.

            Si vous trouvez cette application installee sur un appareil qui n'est pas un banc
            d'essai, desinstallez-la : elle ecrit de fausses nuits.
        """.trimIndent()
    }
}
