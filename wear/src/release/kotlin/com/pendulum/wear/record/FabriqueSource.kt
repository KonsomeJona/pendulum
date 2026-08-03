package com.pendulum.wear.record

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager

/**
 * Variante **release** : il n'y a qu'une source, et c'est le capteur.
 *
 * Ce fichier a un jumeau dans `src/debug/` portant le meme nom pleinement qualifie. C'est la
 * forme que prend ici la separation par source set : le compilateur ne voit qu'un seul des deux
 * selon la variante, et la variante release ne connait meme pas le nom `SourceSynthetique`.
 * Aucune branche a l'execution, aucun drapeau a oublier, aucune classe de rejeu dans l'APK
 * publie — et si quelqu'un tente d'appeler du code de banc depuis `src/main/`, cela ne compile
 * pas au lieu de produire, silencieusement, un enregistrement credible tire de rien.
 *
 * @param intent ignore ici. Il porte, cote debug, la configuration du banc ; le declarer dans les
 *   deux variantes garde une seule signature d'appel dans [RecordingService].
 */
object FabriqueSource {

    @Suppress("UNUSED_PARAMETER")
    fun creer(context: Context, intent: Intent?): SourceCapteur =
        SourceCapteurMaterielle(context.getSystemService(SensorManager::class.java))

    /**
     * Toujours `false` : il n'y a pas de rejeu dans cette variante, et rien a lire pour s'en
     * assurer. La fonction existe pour que le garde-fou d'echelle de `RecordingService` ait une
     * seule signature d'appel — en release il se compile en une condition toujours fausse, ce qui
     * est exactement ce qu'on veut, `EchelleTemps.DIVISEUR` y valant 1 par construction.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sourceSynthetiqueActive(context: Context): Boolean = false
}
