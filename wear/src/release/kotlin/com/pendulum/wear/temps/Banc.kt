package com.pendulum.wear.temps

/**
 * Les derogations de banc, variante **release** : il n'y en a aucune.
 *
 * `const val` et non `val` : la valeur est connue a la compilation, donc la branche qui en depend
 * est eliminee. Une derogation de banc ne doit pas pouvoir exister dans une application publiee,
 * meme desactivee — voir la variante debug pour ce qu'elle permet et pourquoi.
 */
object Banc {
    const val IGNORER_CHARGEUR: Boolean = false
}
