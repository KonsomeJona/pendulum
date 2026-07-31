package com.pendulum.phone.health

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView

/**
 * L'ecran de justification des permissions Health Connect.
 *
 * Health Connect y renvoie l'utilisateur depuis son propre parametrage, par deux chemins
 * differents selon la version d'Android (voir le manifeste : une `activity` **et** une
 * `activity-alias`). Sans elle, l'application n'apparait pas dans la liste de Health Connect —
 * et le symptome est une absence, pas une erreur.
 *
 * Ecrit en `View` et non en Compose, deliberement : ce paquet ne doit rien devoir a
 * `com.pendulum.phone.ui`, qui est ecrit en parallele. Un ecran de trois paragraphes ne justifie pas
 * une dependance de couche.
 *
 * Le texte est en dur ici plutot que dans `strings.xml` pour la meme raison — les ressources de
 * chaines appartiennent a l'interface, et la regle « aucun verbe d'evolution dans les ressources
 * de chaines » (garde-fou 5) est verifiee par un test sur ce fichier-la, qu'il ne faut pas
 * polluer avec des textes qui ne parlent pas de resultats.
 */
class PermissionsRationaleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            movementMethod = ScrollingMovementMethod()
            textSize = 16f
            text = RATIONALE
        }
        setContentView(text)
    }

    private companion object {
        val RATIONALE = """
            Pourquoi Pendulum lit vos donnees de sommeil

            Pendulum compte des mouvements de jambe avec une montre portee a la cheville. Pour en
            faire un index par heure de sommeil, il faut savoir combien d'heures vous avez dormi.

            Cette duree ne peut pas etre deduite de la montre de cheville : l'application y
            compte des mouvements, et un algorithme de sommeil base sur le mouvement declarerait
            « eveil » precisement pendant les periodes ou il y a le plus de mouvements a compter.
            Le chiffre serait fausse deux fois, dans le meme sens.

            Pendulum lit donc uniquement les sessions de sommeil ecrites par votre montre ou votre
            application de sommeil habituelle.

            Lecture seule. Pendulum n'ecrit rien dans Health Connect.

            La lecture en arriere-plan est necessaire parce que votre montre ne transfere pas sa
            nuit au reveil, mais quand sa propre politique de batterie le decide — parfois
            plusieurs heures plus tard, telephone verrouille.

            Ces donnees ne quittent pas votre telephone. L'application ne declare pas la
            permission d'acces a Internet : elle ne peut techniquement pas les envoyer ailleurs.
        """.trimIndent()
    }
}
