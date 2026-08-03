package com.pendulum.wear.record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.pendulum.format.ChunkFormat
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.StopReason
import com.pendulum.wear.R
import com.pendulum.wear.Watchdog
import com.pendulum.wear.temps.Durees
import com.pendulum.wear.transfer.DataLayerTransfer
import com.pendulum.wear.transfer.SyncWorker
import com.pendulum.wear.ui.MainActivity
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Service de premier plan de type `health` : la capture, et tout ce qui doit survivre a huit
 * heures d'ecran eteint.
 *
 * **Pourquoi `health` et pas `dataSync`.** Sur Android 15, le delai de six heures par
 * vingt-quatre s'applique a `dataSync` et `mediaProcessing` : une nuit de huit heures le
 * franchit a la sixieme, `onTimeout()` est appele, et un `stopSelf()` manque produit un
 * `RemoteServiceException` fatal. `health` n'a **aucun delai documente**. Et `dataSync` ne peut
 * pas etre demarre depuis `BOOT_COMPLETED` par une application ciblant Android 15 ou plus,
 * alors que `health` n'est pas sur cette liste — c'est ce qui rend la reprise apres reboot
 * legale. Le service est qualifie par `HIGH_SAMPLING_RATE_SENSORS`, permission normale, et non
 * par `BODY_SENSORS`, qui est while-in-use et casserait precisement cette reprise.
 *
 * Le service ne rend jamais la main de lui-meme : `START_STICKY`, et `onTaskRemoved` ne fait
 * rien. Balayer la tache ne doit pas arreter une nuit.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.pendulum.wear.action.START"
        const val ACTION_RESUME = "com.pendulum.wear.action.RESUME"
        const val ACTION_STOP = "com.pendulum.wear.action.STOP"

        private const val TAG = "PendulumRecord"
        private const val CHANNEL_ID = "pendulum_recording"
        private const val NOTIF_ID = 1

        const val RATE_HZ = 50

        /** Un tick toutes les dix secondes de temps **eveille**. Voir [tick]. */
        private val TICK_MS = Durees.ACTIVES.tickServiceMs

        /** Vu du watchdog : un `bindService` pour repondre a une question binaire serait plus
         *  cher en complexite que le `@Volatile` qu'il remplace. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var recordThread: HandlerThread
    private lateinit var handler: Handler
    private val syncExecutor = Executors.newSingleThreadExecutor()

    private lateinit var sessionStore: SessionStore
    private var marker: SessionMarker? = null
    private var store: ChunkStore? = null
    private var pipeline: SensorPipeline? = null
    private var gaps: GapMonitor? = null
    private var envelope: PreviewEnvelope? = null
    private var wakeDetector = WakeDetector()
    private var stopConditions: StopConditions? = null
    private var mode: AcquisitionMode? = null
    private var source: SourceCapteur? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var startElapsedMs = 0L
    private var ticks = 0
    private var closedChunks = 0
    private var lastClosedIdx = -1

    /** Ecrit par le fil de transfert, lu par le fil d'enregistrement. */
    @Volatile
    private var backlogged = false

    /** Ecrit par le capteur off-body, lu par le pipeline. */
    @Volatile
    private var offBody = false
    private var offBodySeconds = 0L
    private val batterySeries = ArrayList<Int>(600)
    private var stopping = false

    // --- cycle de vie ---

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        createChannel()
        recordThread = HandlerThread("pendulum-rec", android.os.Process.THREAD_PRIORITY_FOREGROUND)
        recordThread.start()
        handler = Handler(recordThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Premiere instruction utile, avant toute E/S : cinq secondes de retard et le systeme
        // leve ForegroundServiceDidNotStartInTimeException.
        if (!promoteToForeground()) return START_NOT_STICKY

        // Un redemarrage par START_STICKY livre un intent nul : c'est une reprise, pas un
        // demarrage, et surtout pas une nouvelle session.
        val action = intent?.action ?: ACTION_RESUME
        handler.post {
            when (action) {
                ACTION_START -> startSession(resume = false, intent = intent)
                ACTION_RESUME -> startSession(resume = true, intent = intent)
                ACTION_STOP -> finalizeSession(StopReason.USER)
            }
        }
        return START_STICKY
    }

    /** Ne fait rien, volontairement : le service survit au balayage de la tache. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        recordThread.quitSafely()
        syncExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * `health` n'est pas soumis au delai des services de premier plan. On implemente quand meme :
     * si une version future l'y soumettait, le comportement par defaut serait un plantage a six
     * heures de nuit, toutes les nuits. Cout si inutile : zero. Cout si necessaire et absent :
     * la moitie de chaque nuit.
     */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) = onTimeoutCommon()

    @RequiresApi(34)
    override fun onTimeout(startId: Int) = onTimeoutCommon()

    private fun onTimeoutCommon() {
        Log.w(TAG, "onTimeout : fermeture propre puis relance d'une nouvelle session")
        handler.post {
            finalizeSession(StopReason.MAX_DURATION)
            // Une session neuve, avec un nouvel identifiant, trente secondes plus tard. La
            // relance passe par WorkManager plutot que par une alarme exacte : cela evite de
            // declarer SCHEDULE_EXACT_ALARM pour un chemin qui, en principe, ne s'execute jamais.
            Watchdog.restartAfterTimeout(this)
        }
    }

    // --- demarrage ---

    /**
     * @param intent celui de `onStartCommand`, nul apres un redemarrage par `START_STICKY`. Il
     *   n'est lu que par [FabriqueSource], qui n'en fait quelque chose qu'en variante debug.
     */
    private fun startSession(resume: Boolean, intent: Intent?) {
        if (isRunning) return

        val existing = sessionStore.readMarker()
        if (resume && existing == null) {
            Log.i(TAG, "reprise demandee sans marqueur : rien a reprendre")
            stopSelfClean()
            return
        }
        // Les memes garde-fous que BootReceiver, parce que ce chemin est aussi celui d'un
        // redemarrage par START_STICKY : ne jamais relancer une nuit dont l'heure est passee.
        if (resume && existing != null) {
            if (existing.estPerimee(System.currentTimeMillis())) {
                Log.i(TAG, "reprise refusee : la nuit est terminee, on finalise")
                SyncWorker.enqueue(this, existing.sessionHex)
                stopSelfClean()
                return
            }
        }
        if (!resume) {
            val pre = Preflight.check(this)
            if (!pre.canStart) {
                Log.w(TAG, "preflight bloquant : ${pre.blockers.map { it.id }}")
                RecordingState.update { it.copy(phase = RecordPhase.IDLE) }
                stopSelfClean()
                return
            }
        }

        val sm = getSystemService(SensorManager::class.java)
        val src = FabriqueSource.creer(this, intent)
        val acc = src.decrire()
        if (acc == null) {
            Log.e(TAG, "aucun accelerometre")
            stopSelfClean()
            return
        }
        source = src

        // Le garde-fou d'echelle, place ici et pas plus tot : c'est `FabriqueSource.creer` qui
        // transcrit les extras de l'intent dans la preference, donc la source n'est connue qu'a
        // partir de cette ligne. Le refus est enregistre comme l'est celui du service de premier
        // plan — une preference que le preflight suivant transforme en bloqueur lisible — parce
        // qu'un enregistrement qui s'arrete tout seul en quatorze secondes ne dit rien a personne.
        if (Preflight.echelleDesaccordee(
                diviseur = com.pendulum.wear.temps.EchelleTemps.DIVISEUR,
                sourceSynthetique = FabriqueSource.sourceSynthetiqueActive(this),
            )
        ) {
            Log.e(
                TAG,
                "demarrage refuse : compilation de banc a l'echelle " +
                    "${com.pendulum.wear.temps.EchelleTemps.DIVISEUR} sur le capteur reel. " +
                    "Le diviseur ne comprime que le temps mural ; la latence de salve du FIFO ne " +
                    "se comprime pas, et l'heure butoir couperait la nuit avant le premier " +
                    "echantillon. Recompiler sans -Ppendulum.temps.diviseur, ou demarrer avec " +
                    "--ez pendulum.synth true.",
            )
            prefs().edit().putBoolean(Preflight.PREF_ECHELLE_DESACCORDEE, true).commit()
            RecordingState.update { it.copy(phase = RecordPhase.IDLE) }
            src.arreter()
            source = null
            stopSelfClean()
            return
        }
        prefs().edit().putBoolean(Preflight.PREF_ECHELLE_DESACCORDEE, false).apply()

        // La strategie est decidee ici, a l'execution, sur `fifoReservedEventCount` : la part
        // *garantie* a cette application. `fifoMaxEventCount` est partage entre tous les clients
        // du capteur, et budgeter dessus revient a parier que personne d'autre n'ecoute.
        val m = if (resume && existing != null) {
            SensorStrategy.decide(acc.wakeUp, acc.fifoReserved, existing.nominalRateHz)
        } else {
            SensorStrategy.decide(acc.wakeUp, acc.fifoReserved, RATE_HZ)
        }
        mode = m
        Log.i(
            TAG,
            "capteur=${acc.nom} wakeUp=${acc.wakeUp} " +
                "reserved=${acc.fifoReserved} max=${acc.fifoMax} mode=${m.label}",
        )

        val now = System.currentTimeMillis()
        val stopAtMinutes = stopAtLocalMinutes()
        val current = if (resume && existing != null) {
            existing.copy(modeFlags = m.modeFlags, nominalRateHz = m.rateHz)
        } else {
            SessionMarker(
                sessionHex = SessionStore.newSessionHex(),
                startWallMs = now,
                plannedStopWallMs = plannedStop(now, stopAtMinutes),
                lastChunkIndex = -1,
                modeFlags = m.modeFlags,
                nominalRateHz = m.rateHz,
                zoneId = SessionStore.currentZoneId(),
                stopAtLocalMinutes = stopAtMinutes,
            )
        }
        marker = current
        sessionStore.begin(current)

        val cs = ChunkStore(
            sessionDir = sessionStore.sessionDir(current.sessionHex),
            sessionUuid = SessionStore.uuidBytes(current.sessionHex),
            sensorResolution = acc.resolution,
            sensorMaxRange = acc.maxRange,
            fifoReserved = acc.fifoReserved,
            startIndex = current.lastChunkIndex + 1,
            rateHz = m.rateHz,
            modeFlags = m.modeFlags,
        )
        val gm = GapMonitor(m.rateHz)
        val env = PreviewEnvelope(m.rateHz) { rms, tsNs -> wakeDetector.onSecond(rms, tsNs) }
        val pl = SensorPipeline(
            store = cs,
            gaps = gm,
            envelope = env,
            rateHz = m.rateHz,
            onChunkClosed = ::onChunkClosed,
            offBody = { offBody },
        )
        store = cs
        gaps = gm
        envelope = env
        pipeline = pl
        stopConditions = StopConditions(current.startWallMs, stopAtMinutes)
        wakeDetector = WakeDetector()
        batterySeries.clear()
        closedChunks = 0
        lastClosedIdx = current.lastChunkIndex
        ticks = 0
        startElapsedMs = SystemClock.elapsedRealtime()
        stopping = false

        if (resume) {
            // Le premier bloc apres une reprise porte la trace de l'interruption : sans elle,
            // le trou serait invisible a la relecture et l'analyse daterait faux.
            pl.markNextBlock(ChunkFormat.FLAG_GAP_BEFORE)
        }

        publishSessionOpen(current, m)

        src.demarrer(m, handler, puits)
        offBodySensor = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)?.also {
            // Journalise, jamais actionne : a la cheville, l'off-body lit tres probablement
            // « non porte » en permanence, et s'y fier couperait chaque nuit a sa premiere minute.
            sm.registerListener(offBodyListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        if (m.needsWakeLock) acquireWakeLock()

        isRunning = true
        Watchdog.start(this)
        handler.postDelayed(::tick, TICK_MS)
        publishUiState()
    }

    private fun stopSelfClean() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- boucle ---

    /** Les echantillons, d'ou qu'ils viennent, vont la et nulle part ailleurs. */
    private val puits = PuitsEchantillons { x, y, z, tsNs, arrivalNs, nowMs ->
        pipeline?.onEvent(x, y, z, tsNs, arrivalNs, nowMs)
    }

    /** L'off-body reste cable en direct sur `SensorManager` : il ne traverse pas [SensorPipeline],
     *  il est journalise et jamais actionne, et il n'y a donc rien a en simuler. */
    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 0.0 = non porte. On se contente de le retenir.
            if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                offBody = event.values[0] == 0f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Tick unique, toutes les dix secondes d'uptime. Un `Handler` ne s'ecoule pas pendant la
     * suspension du SoC : ce tick ne provoque donc **aucun reveil**, il s'execute dans le sillage
     * des reveils que le vidage du FIFO cause de toute facon. C'est aussi pour cela qu'il n'y a
     * qu'un seul timer et pas trois — les periodes de 30 s et 60 s sont des multiples comptes.
     */
    private fun tick() {
        if (!isRunning || stopping) return
        ticks++
        try {
            store?.sync()
        } catch (e: Exception) {
            Log.e(TAG, "fsync impossible", e)
        }

        gaps?.consumePendingStep()?.let(::applyDegradation)

        if (ticks % 3 == 0) publishUiState()
        if (ticks % 6 == 0) minuteTick()

        handler.postDelayed(::tick, TICK_MS)
    }

    private fun minuteTick() {
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        if (pct >= 0) batterySeries += pct
        if (offBody) offBodySeconds += 60

        val reason = stopConditions?.evaluate(
            nowMs = System.currentTimeMillis(),
            isCharging = charging,
            batteryPct = pct,
            freeBytes = filesDir.usableSpace,
            localMinutes = localMinutes(),
            wakeRatio = wakeDetector.ratio,
        )
        if (reason != null) {
            Log.i(TAG, "arret automatique : $reason")
            finalizeSession(reason)
            return
        }
        // La notification n'est reecrite qu'une fois par minute : elle est le seul moyen de
        // verifier d'un coup d'oeil que la nuit tourne, mais chaque reecriture est du travail.
        updateNotification()
    }

    /**
     * Auto-degradation. **Monotone** : un palier ne redescend jamais dans la meme session, sinon
     * le systeme oscille toute la nuit entre deux modes. Chaque palier force une rotation de
     * chunk, parce que `modeFlags` et `nominalRateHz` vivent dans l'entete du fichier et que le
     * format est append-only : ils ne seront jamais reecrits.
     */
    private fun applyDegradation(step: Int) {
        val old = mode ?: return
        val src = source ?: return
        val next = old.degradedTo(step)
        Log.w(TAG, "degradation palier $step : ${old.label} -> ${next.label}")
        mode = next

        src.arreter()
        pipeline?.flushBlock(SystemClock.elapsedRealtime())
        store?.rotate(next.rateHz, next.modeFlags)?.let(::onChunkClosed)
        if (next.rateHz != old.rateHz) pipeline?.onRateChanged(next.rateHz, SystemClock.elapsedRealtime())
        if (next.needsWakeLock) acquireWakeLock()
        src.demarrer(next, handler, puits)

        marker = marker?.copy(modeFlags = next.modeFlags, nominalRateHz = next.rateHz)
        sessionStore.updateMode(next.modeFlags, next.rateHz)
    }

    private fun onChunkClosed(idx: Int) {
        closedChunks++
        lastClosedIdx = idx
        sessionStore.updateLastChunkIndex(idx)
        marker = marker?.copy(lastChunkIndex = idx)
        writeSidecar(null, null)
        // Une salve tous les trois chunks fermes, soit un quart d'heure. Passer a un chunk sur
        // un ne multiplierait le cout que par 1,7 — le reveil fixe domine le temps d'emission —
        // mais tant que le budget batterie n'est pas mesure, on garde la version prudente.
        if (closedChunks % DataLayerTransfer.PUSH_EVERY_N_CHUNKS == 0) push(force = false)
    }

    /** La poussee vit sur son propre fil : elle bloque plusieurs secondes et n'a aucune raison
     *  de retarder l'ecriture du bloc suivant. */
    private fun push(force: Boolean) {
        val m = marker ?: return
        val env = envelope?.snapshot() ?: return
        val samples = store?.totalSamples ?: 0
        val bytes = store?.totalBytes ?: 0
        val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
        val battery = batterySeries.lastOrNull() ?: 0
        val gapCount = gaps?.gapCount ?: 0
        val gapMs = gaps?.gapTotalMs ?: 0
        val idx = lastClosedIdx
        val flags = m.modeFlags
        syncExecutor.execute {
            try {
                val dir = sessionStore.sessionDir(m.sessionHex)
                backlogged = DataLayerTransfer.pushChunks(this, m.sessionHex, dir, urgentLast = true)
                DataLayerTransfer.putLive(
                    this,
                    LivePreview(
                        sessionHex = m.sessionHex,
                        lastUpdateMs = System.currentTimeMillis(),
                        elapsedMs = elapsed,
                        samplesWritten = samples,
                        bytesWritten = bytes,
                        batteryPct = battery.coerceIn(0, 100),
                        gapCount = gapCount,
                        gapTotalMs = gapMs,
                        modeFlags = flags,
                        lastClosedChunkIdx = idx,
                        syncBacklogged = backlogged,
                        envU8 = env,
                    ),
                )
            } catch (e: Exception) {
                // Le Data Layer bufferise et resynchronise seul : un echec ici n'est pas une
                // perte, seulement un retard. Le disque reste la source de verite.
                Log.w(TAG, "salve impossible, reportee", e)
                if (force) SyncWorker.enqueue(this)
            }
        }
    }

    // --- fermeture ---

    private fun finalizeSession(reason: StopReason) {
        if (stopping) return
        stopping = true
        val m = marker
        Log.i(TAG, "fermeture de session, raison $reason")
        RecordingState.update { it.copy(phase = RecordPhase.FINALIZING) }
        updateNotification(finalizing = true)

        source?.arreter()
        offBodySensor?.let { getSystemService(SensorManager::class.java).unregisterListener(offBodyListener) }
        handler.removeCallbacksAndMessages(null)

        pipeline?.flushBlock(SystemClock.elapsedRealtime())
        store?.close()?.let { idx ->
            closedChunks++
            lastClosedIdx = idx
        }
        writeSidecar(System.currentTimeMillis(), reason)

        if (m != null) {
            val total = lastClosedIdx + 1
            // On attend la derniere salve : c'est elle qui transforme « la montre est morte a
            // 3 h » en « la montre a ferme a 3 h et tout envoye ».
            val task = syncExecutor.submit {
                try {
                    DataLayerTransfer.closeSession(this, m, System.currentTimeMillis(), total, reason)
                    val dir = sessionStore.sessionDir(m.sessionHex)
                    DataLayerTransfer.pushChunks(this, m.sessionHex, dir, urgentLast = true)
                } catch (e: Exception) {
                    Log.w(TAG, "salve finale impossible", e)
                }
            }
            try {
                task.get(90, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "salve finale non terminee dans le delai", e)
            }
        }

        releaseWakeLock()
        sessionStore.clearActive()
        Watchdog.stop(this)
        SyncWorker.enqueue(this)

        RecordingState.set(
            RecordUiState(
                phase = RecordPhase.IDLE,
                lastStopReason = reason,
                batteryPct = batterySeries.lastOrNull() ?: -1,
                chunksPending = Preflight.pendingChunkCount(sessionStore.chunksRoot),
            ),
        )
        isRunning = false
        stopSelfClean()
    }

    private fun writeSidecar(endWallMs: Long?, reason: StopReason?) {
        val m = marker ?: return
        sessionStore.writeSidecar(
            m.sessionHex,
            Sidecar(
                startWallMs = m.startWallMs,
                endWallMs = endWallMs,
                zoneId = m.zoneId,
                nominalRateHz = m.nominalRateHz,
                measuredRateHz = gaps?.measuredRateHz ?: 0.0,
                modeFlags = m.modeFlags,
                degradationStep = gaps?.step ?: 0,
                gapCount = gaps?.gapCount ?: 0,
                gapTotalMs = gaps?.gapTotalMs ?: 0,
                offBodySeconds = offBodySeconds,
                samples = store?.totalSamples ?: 0,
                bytes = store?.totalBytes ?: 0,
                totalChunks = lastClosedIdx + 1,
                stopReason = reason,
                batterySeries = batterySeries.toList(),
            ),
        )
    }

    // --- utilitaires ---

    private fun publishSessionOpen(m: SessionMarker, mode: AcquisitionMode) {
        syncExecutor.execute {
            try {
                DataLayerTransfer.putSession(
                    this,
                    SessionHeader(
                        sessionHex = m.sessionHex,
                        startWallMs = m.startWallMs,
                        tzOffsetMin = TimeZone.getDefault().getOffset(m.startWallMs) / 60_000,
                        zoneId = m.zoneId,
                        nominalRateHz = mode.rateHz,
                        modeFlags = mode.modeFlags,
                        plannedStopWallMs = m.plannedStopWallMs,
                        state = SessionState.OPEN,
                    ),
                )
            } catch (e: Exception) {
                // Sans cet item, le telephone ne saura pas qu'une nuit existe avant d'en recevoir
                // le premier chunk. C'est un retard d'affichage, pas une perte de donnee.
                Log.w(TAG, "annonce de session impossible", e)
            }
        }
    }

    private fun publishUiState() {
        val m = mode
        RecordingState.set(
            RecordUiState(
                phase = if (stopping) RecordPhase.FINALIZING else RecordPhase.RECORDING,
                elapsedMs = SystemClock.elapsedRealtime() - startElapsedMs,
                samples = store?.totalSamples ?: 0,
                bytesWritten = store?.totalBytes ?: 0,
                batteryPct = batterySeries.lastOrNull() ?: -1,
                gapCount = gaps?.gapCount ?: 0,
                gapTotalMs = gaps?.gapTotalMs ?: 0,
                modeLabel = m?.label ?: "",
                chunksPending = Preflight.pendingChunkCount(sessionStore.chunksRoot),
                chunksTotal = lastClosedIdx + 1,
                syncBacklogged = backlogged,
            ),
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        // Sans delai d'expiration, deliberement : hors magasin d'applications il n'y a aucune
        // contrainte, et un wake lock qui expire a 4 h du matin est un bug silencieux.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pendulum:rec").also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(finalizing: Boolean = false): Notification {
        val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
        val text = if (finalizing) {
            getString(R.string.notif_text_finalizing)
        } else {
            getString(
                R.string.notif_text,
                formatElapsed(elapsed),
                "%.1f".format((store?.totalBytes ?: 0) / 1_048_576.0),
                batterySeries.lastOrNull() ?: 0,
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pendulum)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            // Pas de setSilent : le canal est cree en IMPORTANCE_LOW, donc deja sans son ni
            // vibration. L'appeler ici serait redondant, et la surcharge n'existe pas sur le
            // Notification.Builder de la plateforme (seulement sur NotificationCompat).
            .setLocalOnly(true)
            .build()
    }

    private fun updateNotification(finalizing: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(finalizing))
    }

    private fun promoteToForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            // Le type `health` n'existe pas avant l'API 34 ; sur une Wear OS 4 le service
            // demarre sans type declare, ce qui est le comportement historique.
            startForeground(NOTIF_ID, buildNotification())
        }
        prefs().edit().putBoolean(Preflight.PREF_FGS_REFUSED, false).apply()
        true
    } catch (e: Exception) {
        // SecurityException si la qualification par HIGH_SAMPLING_RATE_SENSORS n'est pas honoree
        // par l'appareil, ForegroundServiceStartNotAllowedException si le demarrage vient de
        // l'arriere-plan. Dans les deux cas le fait doit remonter a l'ecran du coucher, pas
        // seulement dans logcat : c'est le seul moment ou quelqu'un peut y faire quelque chose.
        Log.e(TAG, "demarrage du service de premier plan refuse", e)
        prefs().edit().putBoolean(Preflight.PREF_FGS_REFUSED, true).apply()
        RecordingState.update { it.copy(phase = RecordPhase.IDLE) }
        stopSelf()
        false
    }

    private fun prefs() = getSharedPreferences(Preflight.PREFS, Context.MODE_PRIVATE)

    private fun stopAtLocalMinutes(): Int = prefs().getInt("stop_at_minutes", 10 * 60)

    private fun localMinutes(): Int = Calendar.getInstance().let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    /** Le plus proche de « dix heures de nuit » et de l'heure butoir locale. */
    private fun plannedStop(nowMs: Long, stopAtMinutes: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.HOUR_OF_DAY, stopAtMinutes / 60)
        cal.set(Calendar.MINUTE, stopAtMinutes % 60)
        cal.set(Calendar.SECOND, 0)
        if (nowMinutes >= stopAtMinutes) cal.add(Calendar.DAY_OF_YEAR, 1)
        return minOf(nowMs + StopConditions.MAX_DURATION_MS, cal.timeInMillis)
    }

    private fun formatElapsed(ms: Long): String {
        val minutes = ms / 60_000
        return getString(R.string.elapsed_hm, minutes / 60, minutes % 60)
    }
}
