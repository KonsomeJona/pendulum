package com.pendulum.wear.record

import android.os.Handler
import android.os.SystemClock
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.synth.NightSpec
import com.pendulum.algo.synth.NightSynth

/**
 * Rejeu d'une nuit synthetique a la place du capteur. **Debug uniquement** : ce fichier n'est pas
 * compile dans la variante release, et c'est la seule garantie qui tienne — voir la KDoc de
 * [SourceCapteur] pour pourquoi un drapeau d'execution n'en serait pas une.
 *
 * ### La regle qui decide de la valeur du banc
 *
 * **Les `SensorEvent.timestamp` restent coherents entre eux** — espaces de la periode nominale en
 * temps capteur — pendant que le defilement en temps reel est beaucoup plus rapide. C'est la
 * regle que [GapMonitor] enonce comme non negociable : un trou se mesure sur les ecarts de
 * `SensorEvent.timestamp`, jamais sur l'heure d'arrivee. Une source qui calerait ses timestamps
 * sur l'horloge reelle acceleree fabriquerait un trou a chaque salve ; l'escalade prendrait un
 * `PARTIAL_WAKE_LOCK`, et le banc ne mesurerait plus que son propre injecteur.
 *
 * ### La livraison par salves n'est pas un detail d'implementation
 *
 * Le materiel livre par salves — 1 500 evenements d'un coup apres trente secondes de silence — et
 * c'est cette forme-la que [SensorPipeline] observe pour placer `FLAG_FIFO_BOUNDARY`. Le rejeu la
 * reproduit, et les deux constantes qui le permettent ne sont pas libres :
 *
 *  - la **taille de salve** vaut `maxReportLatencyUs x rateHz`, c'est-a-dire exactement ce qu'un
 *    vidage de FIFO contient. Avec la description par defaut ci-dessous, 30 s a 50 Hz = 1 500
 *    echantillons — la figure meme que cite la KDoc de [GapMonitor] ;
 *  - la **pause entre salves** doit depasser [SensorPipeline.FLUSH_GAP_NS] (100 ms), sinon le
 *    pipeline ne voit plus la frontiere et le rejeu cesse de ressembler a du mode batche.
 *
 * L'acceleration du temps n'est donc **pas un parametre libre** : elle tombe de ces deux
 * contraintes. 30 s de temps capteur par 120 ms de temps reel, soit x250, soit une nuit de huit
 * heures en moins de deux minutes.
 *
 * En mode continu ([AcquisitionKind.CONTINUOUS_WAKELOCK], latence nulle) la pause tombe a zero :
 * les arrivees reelles y sont espacees de 20 ms, donc **deja** sous le seuil de 100 ms, et les
 * livrer plus vite encore ne change rien a ce que le pipeline observe — aucune frontiere n'est
 * fabriquee, aucune n'est perdue.
 *
 * ### Ce que ce rejeu ne simule pas, et qu'il ne faut pas laisser croire
 *
 *  - **Le batching FIFO materiel et les reveils du SoC.** C'est le prix de l'injection, enonce en
 *    entier dans la KDoc de [SourceCapteur] : ce banc ne rapproche la phase P1 d'aucun pas.
 *  - **L'auto-degradation.** Une re-inscription a 25 Hz continue de rejouer les timestamps
 *    generes a 50 Hz : le rejeu ne re-rend pas le signal a une autre cadence. Le chemin de
 *    degradation se verifie ailleurs, sur [GapMonitor] en JVM et sur le materiel.
 *  - **La resilience du processus.** Voir [SourceCapteur] : elle passe par le chemin reel.
 */
class SourceSynthetique(
    private val spec: NightSpec = NightSpec(),
    private val seed: Long = 1L,
    private val description: DescriptionCapteur = CAPTEUR_SIMULE,
    private val horlogeNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val horlogeMs: () -> Long = SystemClock::elapsedRealtime,
) : SourceCapteur {

    companion object {
        /**
         * Juste au-dessus de [SensorPipeline.FLUSH_GAP_NS] (100 ms). En dessous, le pipeline ne
         * distingue plus deux salves d'un flot continu et `FLAG_FIFO_BOUNDARY` disparait des
         * chunks : le rejeu testerait alors une forme de livraison que le materiel ne produit pas.
         */
        const val PAUSE_SALVE_MS = 120L

        /**
         * Taille du paquet de travail quand la latence de report est nulle. Sans latence il n'y a
         * pas de salve a imiter ; ce nombre n'a donc aucune valeur physique, il borne seulement la
         * duree d'un tour de boucle pour que [arreter] reste reactif.
         */
        const val PAQUET_CONTINU = 250

        /**
         * Un accelerometre plausible de montre, classe BMI270 : wake-up, +/-8 g, 16 bits, et une
         * part de FIFO garantie de 3 000 evenements. **Valeurs vraisemblables, pas mesurees** —
         * elles servent a faire tomber [SensorStrategy.decide] sur `BATCHED_WAKEUP` a 30 s de
         * latence, qui est le mode nominal du produit et donc celui que le banc doit exercer.
         */
        val CAPTEUR_SIMULE = DescriptionCapteur(
            nom = "synthetique (rejeu algo/synth)",
            wakeUp = true,
            fifoReserved = 3000,
            fifoMax = 3000,
            resolution = 0.0023942f,
            maxRange = 78.4532f,
        )
    }

    /**
     * Cree une fois, conserve a travers les re-inscriptions : une degradation ne doit pas faire
     * repartir la nuit a son debut, ce qui reecrirait les memes echantillons sous de nouveaux
     * chunks et rendrait le resultat de bout en bout incomparable a la reference JVM.
     */
    private var rejeu: RejeuSynthetique? = null
    private var boucle: Runnable? = null
    private var handler: Handler? = null

    override fun decrire(): DescriptionCapteur = description

    override fun demarrer(mode: AcquisitionMode, handler: Handler, puits: PuitsEchantillons) {
        arreter()
        val taille = tailleSalve(mode)
        val pause = if (mode.maxReportLatencyUs == 0) 0L else PAUSE_SALVE_MS
        val r = rejeu ?: RejeuSynthetique(
            blocs = NightSynth.generate(spec, seed).blocks,
            // Le rejeu est recale sur `elapsedRealtimeNanos` : les vrais `SensorEvent.timestamp`
            // partagent cette base, et la reconstruction cote telephone en depend pour rendre une
            // heure murale. Le generateur, lui, part d'un `startNs` fixe — aucune horloge n'entre
            // dans une nuit synthetique, c'est ce qui la rend deterministe.
            originNs = horlogeNs(),
        ).also { rejeu = it }

        this.handler = handler
        val runnable = object : Runnable {
            override fun run() {
                val salve = r.salveSuivante(taille) ?: return
                // Une seule lecture de l'horloge murale par salve : un vidage de FIFO arrive
                // effectivement dans la meme milliseconde, et c'est ce que le pipeline attend.
                val nowMs = horlogeMs()
                for (i in 0 until salve.n) {
                    puits.onEchantillon(
                        salve.x[i],
                        salve.y[i],
                        salve.z[i],
                        salve.tsNs[i],
                        // Lue par echantillon, comme dans le vrai ecouteur : a l'interieur d'une
                        // salve les arrivees sont espacees de quelques centaines de nanosecondes,
                        // tres en dessous du seuil de frontiere, et c'est precisement ce qui fait
                        // qu'une salve n'est pas prise pour une reprise apres silence.
                        horlogeNs(),
                        nowMs,
                    )
                }
                handler.postDelayed(this, pause)
            }
        }
        boucle = runnable
        handler.post(runnable)
    }

    override fun arreter() {
        boucle?.let { handler?.removeCallbacks(it) }
        boucle = null
    }

    /** Ce qu'un vidage de FIFO contient : la latence de report multipliee par la cadence. */
    private fun tailleSalve(mode: AcquisitionMode): Int {
        val n = mode.maxReportLatencyUs.toLong() * mode.rateHz / 1_000_000L
        return if (n >= 2) n.toInt() else PAQUET_CONTINU
    }
}

/** Une salve prete a livrer. [n] echantillons utiles ; les tableaux peuvent etre plus longs. */
class Salve(
    val n: Int,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val tsNs: LongArray,
)

/**
 * Le decoupage d'une nuit generee en salves, **pur** : ni Android, ni horloge, ni fil. C'est la
 * meme discipline que [GapMonitor] — la partie qui decide quelque chose doit etre testable en JVM,
 * parce que c'est elle qui peut mentir.
 *
 * Deux choix ont un motif :
 *
 *  1. **Les salves ignorent les frontieres de bloc du generateur.** Un vidage de FIFO ne sait rien
 *     du decoupage qu'un generateur a choisi ; couper les salves sur les blocs produirait une
 *     correlation qui n'existe pas dans le materiel, et le banc validerait une coincidence.
 *  2. **Les timestamps sont interpoles lineairement entre `tFirstNs` et `tLastNs` de chaque bloc**,
 *     exactement comme le format le fait a la relecture — le format n'a pas de timestamp par
 *     echantillon. Rejouer autre chose reviendrait a injecter un signal que la chaine ne saurait
 *     de toute facon pas restituer.
 *
 * Les trous que le generateur a programmes se traduisent naturellement en sauts de `tsNs` entre
 * deux echantillons consecutifs, et c'est bien ainsi : ils doivent etre vus par [GapMonitor]. Une
 * nuit sans trous, elle, ne doit en produire aucun.
 */
class RejeuSynthetique(
    private val blocs: List<SampleBlock>,
    originNs: Long,
) {

    private val decalageNs: Long = if (blocs.isEmpty()) 0L else originNs - blocs.first().tFirstNs

    private var iBloc = 0
    private var iEch = 0

    val termine: Boolean get() = iBloc >= blocs.size

    /**
     * @param taille nombre d'echantillons de la salve.
     * @return la salve suivante, ou `null` quand la nuit est epuisee.
     */
    fun salveSuivante(taille: Int): Salve? {
        require(taille >= 1) { "taille de salve doit etre >= 1 : $taille" }
        if (termine) return null
        val x = FloatArray(taille)
        val y = FloatArray(taille)
        val z = FloatArray(taille)
        val ts = LongArray(taille)
        var n = 0
        while (n < taille && iBloc < blocs.size) {
            val b = blocs[iBloc]
            if (iEch >= b.x.size) {
                iBloc++
                iEch = 0
                continue
            }
            x[n] = b.x[iEch]
            y[n] = b.y[iEch]
            z[n] = b.z[iEch]
            ts[n] = decalageNs + tsEchantillon(b, iEch)
            iEch++
            n++
        }
        return if (n == 0) null else Salve(n, x, y, z, ts)
    }

    private fun tsEchantillon(b: SampleBlock, i: Int): Long {
        val len = b.x.size
        if (len <= 1) return b.tFirstNs
        return b.tFirstNs + i.toLong() * (b.tLastNs - b.tFirstNs) / (len - 1)
    }
}
