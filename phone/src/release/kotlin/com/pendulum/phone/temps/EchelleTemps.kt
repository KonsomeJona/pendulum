package com.pendulum.phone.temps

import com.pendulum.format.temps.Temps

/**
 * Variante **release** : le temps est le temps, et il n'y a rien a regler.
 *
 * Jumeau de `wear/src/release/.../EchelleTemps.kt`. Aucune entree : ni champ de `BuildConfig`,
 * ni preference, ni extra d'intent, ni propriete Gradle. Une release ne sait pas tourner a une
 * autre echelle que le temps reel, et ce n'est pas une consigne — c'est le seul code qu'elle
 * contienne.
 *
 * C'est le premier `src/release/` de `:phone`. Le module avait deja un `src/debug/` — les
 * fixtures d'apercu — mais sans jumeau, parce qu'un apercu Compose n'est reference que par du
 * code lui-meme debug. Ici le code appelant vit dans `src/main/`, donc les deux variantes
 * doivent fournir le symbole, et l'absence du jumeau serait une erreur de compilation.
 */
object EchelleTemps {

    const val DIVISEUR: Long = Temps.DIVISEUR_REEL
}
