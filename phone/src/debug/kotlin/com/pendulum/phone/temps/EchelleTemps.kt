package com.pendulum.phone.temps

import com.pendulum.phone.BuildConfig

/**
 * Variante **debug** : le diviseur de temps est celui que la compilation a recu.
 *
 * Jumeau de `wear/src/debug/.../EchelleTemps.kt`, et pour la meme raison :
 * `BuildConfig.TEMPS_DIVISEUR` n'est declare que dans le bloc `debug` de
 * `phone/build.gradle.kts`, donc ce fichier ne compile que dans cette variante. Son jumeau
 * `src/release/` ne connait meme pas le nom du champ.
 *
 * ### Les deux moities doivent recevoir la meme valeur, et rien ne le verifie
 *
 * Les deux modules sont deux applications distinctes, compilees separement, avec chacune son
 * `BuildConfig`. Rien dans le code ne peut constater qu'elles ont ete construites avec le meme
 * `-Ppendulum.temps.diviseur` : c'est au script du banc de les compiler dans la meme invocation
 * Gradle. Une paire mal assortie ne produit pas d'erreur, elle produit un telephone qui declare
 * la nuit perimee pendant que la montre l'enregistre encore — exactement le genre de panne que ce
 * depot documente plutot que d'esperer.
 *
 * Valeur par defaut : 1. Une compilation debug ordinaire se comporte exactement comme la release.
 */
object EchelleTemps {

    /** 600 sur le banc : trente minutes valent trois secondes, T+36 h vaut trois minutes et demie. */
    val DIVISEUR: Long = BuildConfig.TEMPS_DIVISEUR
}
