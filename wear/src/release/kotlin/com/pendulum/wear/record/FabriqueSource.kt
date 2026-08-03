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
}
