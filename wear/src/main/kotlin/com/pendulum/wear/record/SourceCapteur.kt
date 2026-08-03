package com.pendulum.wear.record

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

/**
 * Le capteur, vu par [RecordingService] : de quoi decider la strategie, ouvrir le flot, le fermer.
 *
 * **Pourquoi cette couture existe.** Une nuit dure huit heures et ne se rejoue pas. Tant que
 * [SensorPipeline] ne peut etre alimente que par `SensorManager`, la seule facon d'exercer la
 * chaine — decoupage, chunks, transfert, analyse — est d'attendre une nuit reelle. La couture
 * permet a un banc de substituer un signal a verite terrain connue, et de comparer ce qui sort de
 * bout en bout a ce que le meme signal produit en test JVM pur : toute divergence est alors un
 * defaut de la chaine, et c'est la seule facon de le savoir.
 *
 * **Ce que la substitution fait renoncer a prouver — et ce n'est pas un compromis regrettable
 * qu'on minimise.** Remplacer la source, c'est cesser de tester ce qui se passe **sous** elle :
 *
 *  - le **batching FIFO materiel** — que le HAL accumule reellement dans
 *    `fifoReservedEventCount` et ne perde rien, contrat sur lequel repose tout
 *    [SensorStrategy] ;
 *  - les **reveils du SoC** — qu'un capteur wake-up leve effectivement le processeur avant de
 *    depasser sa latence de report, et donc que le processeur **dorme** entre deux levees.
 *
 * C'est exactement ce que la phase P1 existe pour mesurer, et P1 reste le seul chemin vers la
 * couverture d'echantillons a 99 % et l'autonomie a huit heures. **Un banc qui passe ne rapproche
 * P1 d'aucun pas.**
 *
 * **Ce que cette couture ne doit pas servir a tester.** La resilience du processus — service tue,
 * reprise apres `am kill`, Doze — se verifie sur le chemin **reel**, source materielle comprise.
 * Faire passer les deux preoccupations par le meme point d'injection donnerait un test qui passe
 * en ne garantissant rien : on verifierait que l'injecteur redemarre, pas que la capture reprend.
 *
 * **La separation se fait par source set, pas par un drapeau.** `FabriqueSource` existe en deux
 * exemplaires — `src/debug/` et `src/release/` — et seul celui de debug sait construire une
 * source synthetique. Un `if (BuildConfig.DEBUG)` se contourne par distraction lors d'un
 * remaniement et rien ne le signale ; un source set ne se contourne pas, parce que le code n'est
 * pas compile dans la variante release et que l'oubli devient une erreur de compilation plutot
 * qu'un enregistrement credible produit a partir de rien. C'est la discipline deja appliquee aux
 * fixtures d'apercu de `:phone` (`phone/src/debug/.../preview/`).
 */
interface SourceCapteur {

    /**
     * Ce qu'il faut savoir du capteur **avant** d'ouvrir une session : la strategie
     * ([SensorStrategy.decide]) et l'entete de chunk s'en deduisent.
     *
     * @return `null` quand l'appareil n'a pas d'accelerometre — le seul cas ou une nuit ne peut
     *   pas commencer.
     */
    fun decrire(): DescriptionCapteur?

    /**
     * Ouvre le flot. Les echantillons arrivent sur [handler], jamais sur le fil appelant : c'est
     * le contrat de `registerListener(..., handler)` et tout [SensorPipeline] en depend, puisqu'il
     * n'est pas synchronise.
     */
    fun demarrer(mode: AcquisitionMode, handler: Handler, puits: PuitsEchantillons)

    /** Ferme le flot. Idempotent : appele a la fermeture de session et a chaque degradation. */
    fun arreter()
}

/**
 * Les proprietes du capteur qui decident quelque chose, et rien d'autre.
 *
 * [fifoReserved] est la part **garantie** a cette application ; [fifoMax] est la capacite totale,
 * partagee entre tous les clients du capteur. Les deux sont exposes parce que le premier decide
 * ([SensorStrategy] budgete dessus) et que le second se journalise — voir la KDoc de
 * [SensorStrategy] pour pourquoi budgeter sur le second est un pari perdu sur une Pixel Watch.
 */
data class DescriptionCapteur(
    val nom: String,
    val wakeUp: Boolean,
    val fifoReserved: Int,
    val fifoMax: Int,
    /** `Sensor.getResolution()`, en m/s2. Part telle quelle dans l'entete du chunk. */
    val resolution: Float,
    /** `Sensor.getMaximumRange()`, en m/s2. Part telle quelle dans l'entete du chunk. */
    val maxRange: Float,
)

/**
 * Ou vont les echantillons. La signature est **exactement** celle de [SensorPipeline.onEvent], et
 * ce n'est pas un hasard : les deux horloges qu'elle transporte ne servent jamais a la meme chose
 * et une source qui les confondrait casserait tout le reste.
 *
 *  - [tsNs] : `SensorEvent.timestamp`, la base de temps de la **mesure**. Seule elle date les
 *    echantillons, seule [GapMonitor] la regarde, seule elle decide de la validite d'un bloc.
 *  - [arrivalNs] : `SystemClock.elapsedRealtimeNanos()` a la **reception**. Elle ne dit rien de la
 *    mesure et tout du materiel : c'est elle, et elle seule, qui repere la frontiere entre deux
 *    vidages du FIFO.
 */
fun interface PuitsEchantillons {
    fun onEchantillon(x: Float, y: Float, z: Float, tsNs: Long, arrivalNs: Long, nowMs: Long)
}

/**
 * La vraie source : `SensorManager`, inchangee dans son comportement.
 *
 * L'accelerometre wake-up est prefere quand il existe, et le repli sur la variante ordinaire est
 * conserve tel quel : le contrat HAL rend le premier seul defendable, et [SensorStrategy] ne sait
 * decider qu'a partir de ce choix-la.
 *
 * **Le detecteur off-body n'est pas ici.** Il est journalise et jamais actionne, il n'alimente pas
 * [SensorPipeline], et il n'a aucune raison d'etre simule : le service le garde. La couture
 * remplace l'accelerometre, et seulement lui.
 */
class SourceCapteurMaterielle(private val sm: SensorManager) : SourceCapteur {

    private var capteur: Sensor? = null
    private var ecouteur: SensorEventListener? = null

    private fun capteur(): Sensor? = capteur ?: (
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        )?.also { capteur = it }

    override fun decrire(): DescriptionCapteur? = capteur()?.let {
        DescriptionCapteur(
            nom = it.name,
            wakeUp = it.isWakeUpSensor,
            fifoReserved = it.fifoReservedEventCount,
            fifoMax = it.fifoMaxEventCount,
            resolution = it.resolution,
            maxRange = it.maximumRange,
        )
    }

    override fun demarrer(mode: AcquisitionMode, handler: Handler, puits: PuitsEchantillons) {
        val c = capteur() ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                puits.onEchantillon(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    event.timestamp,
                    SystemClock.elapsedRealtimeNanos(),
                    SystemClock.elapsedRealtime(),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        ecouteur = l
        sm.registerListener(l, c, mode.samplingPeriodUs, mode.maxReportLatencyUs, handler)
    }

    override fun arreter() {
        ecouteur?.let { sm.unregisterListener(it) }
        ecouteur = null
    }
}
