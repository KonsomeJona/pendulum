package com.pendulum.wear.temps

import com.pendulum.wear.BuildConfig

/**
 * Les derogations de banc, variante **debug**.
 *
 * Meme construction que [EchelleTemps] et pour la meme raison : la valeur vient d'un champ de
 * `BuildConfig` qui n'est declare que dans le variant debug. La variante release de ce fichier
 * rend une constante `false`, donc le compilateur retire la branche — la derogation ne s'y
 * desactive pas, elle n'y existe pas.
 */
object Banc {

    /**
     * Ignorer l'arret automatique sur chargeur.
     *
     * La montre s'arrete apres soixante secondes de charge soutenue, ce qui est exactement ce
     * qu'on veut la nuit — et ce qui rend impossible tout essai de plus d'une minute quand elle
     * est reliee en USB a la machine qui la pilote. Un essai instrumente branche est le seul cas
     * ou cette regle nuit, et c'est un cas de banc.
     *
     * Sur le banc : `./gradlew -Ppendulum.banc.ignorer.chargeur=true :wear:assembleDebug`.
     */
    val IGNORER_CHARGEUR: Boolean = BuildConfig.BANC_IGNORER_CHARGEUR
}
