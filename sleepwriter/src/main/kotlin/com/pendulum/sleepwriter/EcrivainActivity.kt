package com.pendulum.sleepwriter

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * L'unique activite : elle lit ses extras, agit, journalise et se termine.
 *
 * ### Pourquoi une activite et pas un service ou un `BroadcastReceiver`
 *
 * La demande de permission Health Connect passe par
 * `PermissionController.createRequestPermissionResultContract()`, qui exige un
 * `ActivityResultCaller` — donc une activite. Un service aurait ete plus propre pour un outil
 * scriptable, mais il aurait fallu une activite **en plus** pour le seul geste qu'on ne sait pas
 * scripter de facon fiable ; deux composants pour trente lignes de logique n'en valaient pas la
 * peine.
 *
 * ### Ce que « pilotable en ligne de commande » impose
 *
 * L'activite est exportee, lit tout de `Intent.getExtras`, et se termine d'elle-meme. Le resultat
 * ne s'affiche pas seulement a l'ecran : il part dans `logcat` sous l'etiquette [Journal.TAG],
 * parce que le banc ne regarde pas l'ecran.
 *
 * ### L'interface, et pourquoi elle est aussi pauvre
 *
 * C'est un outil, pas un produit. Un bouton pour accorder la permission, un bouton pour ecrire une
 * nuit par defaut, et la derniere ligne de journal affichee telle quelle. Tout ce qui serait ajoute
 * ici serait a maintenir sans jamais etre teste.
 *
 * ### Ce module n'est jamais publie
 *
 * Il declare `WRITE_SLEEP`, que Pendulum s'interdit. Sa variante release est desactivee dans
 * `build.gradle.kts` : il n'existe qu'en debug, et seulement sur un banc.
 */
class EcrivainActivity : ComponentActivity() {

    private lateinit var affichage: TextView

    private val demandeDePermission =
        registerForActivityResult(EcrivainSommeil.contratDePermission()) {
            // Le retour du contrat ne dit pas de facon fiable ce qui a ete accorde : on relit
            // l'etat plutot que de croire la reponse. C'est aussi ce que le script attend — une
            // ligne `mode=permissions` fraiche apres la sequence UiAutomator.
            lancer(Requete(Mode.PERMISSIONS, Scenario.RIEN, 0L, 0L))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(interfaceMinimale())

        val requete = requeteDepuis(intent)
        if (requete == null) {
            // Lancement depuis l'icone : on ne fait rien tout seul. Un outil qui ecrit dans Health
            // Connect parce qu'on a touche son icone est un outil dont on ne sait plus, plus tard,
            // d'ou vient une nuit.
            affichage.text = AIDE
            return
        }

        // `keepScreenOn` pour la seule raison qui compte : une ecriture differee attend dans le
        // processus, et un ecran qui s'eteint sur un emulateur finit par emporter l'activite.
        if (requete.delaiMs > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lancer(requete, terminer = intent.getBooleanExtra(EXTRA_FINISH, true))
    }

    private fun lancer(requete: Requete, terminer: Boolean = false) {
        lifecycleScope.launch {
            if (requete.delaiMs > 0) {
                // Ligne distincte de `RESULT` : un script qui cherche un resultat ne doit pas
                // prendre l'annonce pour l'aboutissement.
                Journal.emettre(
                    Journal.EN_ATTENTE,
                    listOf(
                        "mode" to requete.mode.cle,
                        "scenario" to requete.scenario.cle,
                        "source" to BuildConfig.SOURCE_LABEL,
                        "pkg" to packageName,
                        "delayMs" to requete.delaiMs,
                    ),
                )
                delay(requete.delaiMs)
            }
            affichage.text = EcrivainSommeil(applicationContext).executer(requete)
            if (terminer) finish()
        }
    }

    /**
     * Traduction des extras d'`am start` en [Requete].
     *
     * ### Le piege du format des extras
     *
     * `am start` type les extras par le drapeau et non par la valeur : `--es` chaine, `--ei`
     * entier 32 bits, `--el` long, `--ez` booleen. Les instants d'epoque en millisecondes
     * **depassent 2^31** : passes en `--ei`, ils font echouer la commande, et passes en `--es` ils
     * arrivent bien mais dans le mauvais type. Les deux echecs se ressemblent a l'ecran.
     *
     * D'ou la lecture defensive ci-dessous : un `getLongExtra` seul rendrait la valeur par defaut
     * sur un extra de type chaine, donc une nuit silencieusement fausse. On lit le long, puis on
     * retombe sur la chaine, et on prefere une nuit par defaut explicite a une nuit fausse.
     */
    private fun requeteDepuis(intent: Intent): Requete? {
        val extras = intent.extras ?: return null
        val mode = Mode.depuis(extras.getString(EXTRA_MODE)) ?: return null
        val scenario = Scenario.depuis(extras.getString(EXTRA_SCENARIO)) ?: Scenario.NUIT_COMPLETE

        // Fenetre par defaut : les huit dernieres heures. Toujours dans le passe, donc toujours
        // acceptable par Health Connect, et suffisante pour un essai de fumee sans calculer une
        // date.
        val maintenant = System.currentTimeMillis()
        val fin = long(extras, EXTRA_END_MS) ?: maintenant
        val debut = long(extras, EXTRA_START_MS) ?: (fin - 8 * 3_600_000L)

        return Requete(
            mode = mode,
            scenario = scenario,
            debutMs = debut,
            finMs = fin,
            delaiMs = long(extras, EXTRA_DELAY_MS) ?: 0L,
            margeMin = long(extras, EXTRA_MARGIN_MIN) ?: 180L,
        )
    }

    // `Bundle.get` est deprecie au profit des accesseurs types, et c'est pourtant le seul qui
    // convienne ici : on ne cherche pas a lire un long, on cherche a savoir **de quel type** est
    // l'extra qu'on a recu. `getLong` rendrait 0 sur un extra passe en `--es` sans rien signaler,
    // c'est-a-dire une nuit fausse au lieu d'une commande refusee.
    @Suppress("DEPRECATION")
    private fun long(extras: Bundle, cle: String): Long? {
        if (!extras.containsKey(cle)) return null
        return when (val valeur = extras.get(cle)) {
            is Long -> valeur
            is Int -> valeur.toLong()
            is String -> valeur.toLongOrNull()
            else -> null
        }
    }

    private fun interfaceMinimale(): ViewGroup {
        affichage = TextView(this).apply {
            textSize = 12f
            setPadding(0, 32, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            addView(
                Button(context).apply {
                    text = "Accorder l'ecriture du sommeil"
                    setOnClickListener { demandeDePermission.launch(EcrivainSommeil.PERMISSIONS) }
                }
            )
            addView(
                Button(context).apply {
                    text = "Ecrire les 8 dernieres heures"
                    setOnClickListener {
                        val fin = System.currentTimeMillis()
                        lancer(
                            Requete(
                                Mode.ECRIRE,
                                Scenario.NUIT_COMPLETE,
                                fin - 8 * 3_600_000L,
                                fin,
                            )
                        )
                    }
                }
            )
            addView(affichage)
        }
    }

    private companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_START_MS = "startMs"
        const val EXTRA_END_MS = "endMs"
        const val EXTRA_DELAY_MS = "delayMs"
        const val EXTRA_MARGIN_MIN = "marginMin"
        const val EXTRA_FINISH = "finish"

        val AIDE = """
            SleepWriter ${BuildConfig.SOURCE_LABEL} — ${BuildConfig.APPLICATION_ID}

            Ecrivain d'hypnogrammes du banc Pendulum. Debug uniquement, jamais publie.

            Pilotage : adb shell am start -n <paquet>/com.pendulum.sleepwriter.EcrivainActivity
              --es mode write|verify|permissions|purge
              --es scenario nuit-complete|duree-seule|rien|conflit
              --el startMs <epoch ms>  --el endMs <epoch ms>
              --el delayMs <ms>        --el marginMin <minutes>

            Resultat : adb logcat -d -s ${Journal.TAG}:I | grep ${Journal.RESULTAT}

            Voir sleepwriter/README.md.
        """.trimIndent()
    }
}
