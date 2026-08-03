package com.pendulum.wear.record

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.util.Log
import com.pendulum.algo.synth.DistractorSpec
import com.pendulum.algo.synth.NightSpec

/**
 * Variante **debug** : le capteur, ou le rejeu synthetique quand le banc le demande.
 *
 * Le jumeau `src/release/` de ce fichier ne connait pas [SourceSynthetique] et ne peut pas la
 * construire. C'est la separation par source set, et c'est ce qui rend l'oubli impossible : il n'y
 * a pas de drapeau a laisser a `true`, il y a deux compilations qui ne voient pas le meme code.
 *
 * ### Deux facons de l'activer, et pourquoi les deux
 *
 *  - **Un extra sur l'intent de demarrage**, qui est la forme scriptable : le banc dit
 *    explicitement ce qu'il veut au moment ou il le demande.
 *  - **Une preference, ecrite par l'extra**, relue quand l'intent n'en porte pas. Sans elle, un
 *    redemarrage par `START_STICKY` — qui livre un intent nul et qui est le fonctionnement
 *    **nominal**, pas seulement le scenario de mort du service — reprendrait la nuit sur le vrai
 *    capteur. La moitie du fichier viendrait du rejeu, l'autre du poignet, et rien ne le dirait.
 *
 * Un `ACTION_START` qui ne porte aucun extra remet la preference a zero : une nuit qui commence
 * dit toujours explicitement d'ou vient son signal.
 */
object FabriqueSource {

    private const val TAG = "PendulumBanc"
    private const val PREFS = "pendulum_banc"

    /** `--ez pendulum.synth true` */
    const val EXTRA_ACTIF = "pendulum.synth"

    /** `--el pendulum.synth.seed 42` — meme graine, sortie bit-identique (test T13 de `:algo`). */
    const val EXTRA_SEED = "pendulum.synth.seed"

    /** `--ef pendulum.synth.hours 8.0` */
    const val EXTRA_HEURES = "pendulum.synth.hours"

    /** `--ez pendulum.synth.distractors false` pour une nuit propre, sans trous ni distracteurs. */
    const val EXTRA_DISTRACTEURS = "pendulum.synth.distractors"

    fun creer(context: Context, intent: Intent?): SourceCapteur {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent != null && intent.action == RecordingService.ACTION_START) {
            configurer(
                context = context,
                actif = intent.getBooleanExtra(EXTRA_ACTIF, false),
                seed = intent.getLongExtra(EXTRA_SEED, 1L),
                heures = intent.getFloatExtra(EXTRA_HEURES, 8f),
                distracteurs = intent.getBooleanExtra(EXTRA_DISTRACTEURS, true),
            )
        }
        if (!p.getBoolean(EXTRA_ACTIF, false)) {
            return SourceCapteurMaterielle(context.getSystemService(SensorManager::class.java))
        }

        val heures = p.getFloat(EXTRA_HEURES, 8f).toDouble()
        val seed = p.getLong(EXTRA_SEED, 1L)
        val spec = NightSpec(
            durationH = heures,
            distractors = if (p.getBoolean(EXTRA_DISTRACTEURS, true)) {
                DistractorSpec.ALL
            } else {
                DistractorSpec.NONE
            },
        )
        // En WARN, et nomme sans ambiguite : un journal de nuit qu'on relira dans six mois doit
        // dire d'ou venait son signal, sinon la mesure qu'on en tire ne veut rien dire.
        Log.w(TAG, "SOURCE SYNTHETIQUE ACTIVE — graine=$seed duree=${heures}h. " +
            "Ni le batching FIFO ni les reveils du SoC ne sont exerces par cette nuit.")
        return SourceSynthetique(spec, seed)
    }

    /**
     * Configuration directe, pour un test instrumente qui tourne dans le processus de
     * l'application et n'a donc pas d'intent a fabriquer.
     */
    fun configurer(
        context: Context,
        actif: Boolean,
        seed: Long = 1L,
        heures: Float = 8f,
        distracteurs: Boolean = true,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(EXTRA_ACTIF, actif)
            .putLong(EXTRA_SEED, seed)
            .putFloat(EXTRA_HEURES, heures)
            .putBoolean(EXTRA_DISTRACTEURS, distracteurs)
            .apply()
    }
}
