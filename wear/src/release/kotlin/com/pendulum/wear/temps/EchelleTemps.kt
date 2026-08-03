package com.pendulum.wear.temps

import com.pendulum.format.temps.Temps

/**
 * Variante **release** : le temps est le temps, et il n'y a rien a regler.
 *
 * Ce fichier a un jumeau dans `src/debug/` portant le meme nom pleinement qualifie. Le
 * compilateur n'en voit qu'un selon la variante, et celui-ci n'a **aucune entree** : ni champ de
 * `BuildConfig`, ni preference, ni extra d'intent, ni propriete Gradle. Il n'existe donc pas de
 * chemin — pas meme un chemin fautif — par lequel une release pourrait tourner a une autre
 * echelle que le temps reel.
 *
 * `const` et non `val` : la valeur est inlinee sur ses sites d'appel. Il n'y a pas de champ
 * statique a reflechir, pas de setter a synthetiser, rien qu'un litteral recopie — ce qui ferme
 * aussi la porte de derriere qu'aurait laissee une propriete mutable atteinte par reflexion.
 */
object EchelleTemps {

    const val DIVISEUR: Long = Temps.DIVISEUR_REEL
}
