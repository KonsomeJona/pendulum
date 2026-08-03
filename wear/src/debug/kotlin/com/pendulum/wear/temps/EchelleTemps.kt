package com.pendulum.wear.temps

import com.pendulum.wear.BuildConfig

/**
 * Variante **debug** : le diviseur de temps est celui que la compilation a recu.
 *
 * `BuildConfig.TEMPS_DIVISEUR` n'est declare que dans le bloc `debug` de `wear/build.gradle.kts`.
 * Ce fichier ne peut donc pas exister ailleurs qu'ici : son jumeau `src/release/` compile contre
 * une classe `BuildConfig` qui **ne porte pas ce champ**. La separation par source set n'est pas
 * ici une convention de proprete, c'est ce qui rend l'oubli impossible — un `if (BuildConfig.DEBUG)`
 * se contourne par distraction lors d'un remaniement, deux compilations qui ne voient pas le meme
 * code, non. C'est la meme discipline que `FabriqueSource` deux repertoires plus loin.
 *
 * ### Pourquoi une propriete Gradle et pas une preference
 *
 * Le banc **deploie une configuration** : `./gradlew -Ppendulum.temps.diviseur=600 :wear:assembleDebug`.
 * Rien a lire au demarrage, rien a installer avant le premier usage, aucune fenetre entre le
 * lancement du processus et la premiere duree consommee — et une nuit ne peut pas changer
 * d'echelle en cours de route. L'APK dit ce qu'il est, et `adb shell dumpsys package` le retrouve
 * dans son empreinte.
 *
 * Valeur par defaut : 1. Une compilation debug ordinaire se comporte exactement comme la release.
 */
object EchelleTemps {

    /** 600 sur le banc : cinq minutes valent cinq cents millisecondes, huit heures quarante-huit secondes. */
    val DIVISEUR: Long = BuildConfig.TEMPS_DIVISEUR
}
