package com.pendulum.phone

import android.content.Context
import androidx.work.WorkManager
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.ingest.ChunkStore

/**
 * La suppression totale.
 *
 * Ce qui est efface, et l'ordre, qui n'est pas indifferent :
 *
 * 1. **les travaux planifies** — sinon un `SleepFetchWorker` deja en file recreerait une ligne
 *    `hc_snapshot` quelques minutes apres l'effacement, et l'utilisateur verrait reapparaitre
 *    une nuit qu'il vient de supprimer ;
 * 2. **les fichiers de chunks** — c'est le gros morceau, ~8,7 Mo par nuit de signal brut. Les
 *    oublier est l'erreur classique : la base est vide, l'ecran est vide, et huit heures
 *    d'accelerometrie par nuit dorment toujours dans `filesDir` ;
 * 3. **la base**, avec retrait temporaire des declencheurs append-only, puis `VACUUM` — sans
 *    lui, les pages liberees restent lisibles dans le fichier de base et « tout effacer »
 *    laisse le contenu recuperable.
 *
 * Fichiers avant base, et pas l'inverse : une interruption entre les deux laisse une base qui
 * pointe vers des fichiers disparus, etat detectable et reparable. L'ordre inverse laisserait
 * des fichiers orphelins dont plus rien ne connait l'existence — c'est-a-dire des donnees de
 * sante que l'utilisateur croit avoir effacees.
 */
object DataEraser {

    suspend fun eraseEverything(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        ChunkStore(context).deleteAll()
        PendulumDatabase.get(context).eraseEverything()
    }

    /** Taille occupee, pour que l'ecran de suppression annonce ce qu'il va detruire. */
    fun bytesOnDisk(context: Context): Long {
        val chunks = ChunkStore(context).totalBytes()
        val db = context.getDatabasePath(PendulumDatabase.NAME)
        val dbBytes = if (db.exists()) db.length() else 0L
        return chunks + dbBytes
    }
}
