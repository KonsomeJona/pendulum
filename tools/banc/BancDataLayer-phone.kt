package com.pendulum.phone

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Data Layer probe, phone side, run **inside the `com.pendulum` process**.
 *
 * Why an instrumentation and not taps: the real bench's phone is an everyday device, locked by a
 * PIN. `uiautomator` then sees nothing but the lock, `am start` does not even start the process
 * ("Requires permission not exported"), and the evening form is out of reach. `am instrument`
 * does not go through the lock: the runner starts inside the application's process, with its UID,
 * hence with the identity GMS checks.
 *
 * Every probe writes a `BANC_*` marker to logcat under the `BANC` tag: ssh over Tailscale does not
 * propagate exit codes, and the standard output of a JUnit test does not surface in the
 * `am instrument` stream. Read logcat, never `$?`.
 *
 * Deployed by `tools/banc/datalayer.sh`.
 *
 * The test method names, the log markers and the keys of the log lines stay in French: the first
 * are typed as sub-commands and the others are quoted word for word in
 * `docs/workings/BENCH-LOG.md`, a dated log whose whole value is being an exact trace. The prose,
 * the Kotlin identifiers and the free text of the messages were translated.
 */
@RunWith(AndroidJUnit4::class)
class BancDataLayer {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(m: String) {
        Log.i("BANC", m)
    }

    private fun uri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    /**
     * The two readings §7.1 sets against each other: `connectedNodes`, which reads a
     * configuration, and `getCapability(FILTER_REACHABLE)`, which tests a scope. Displaying them
     * side by side is the whole point of §3 of the real bench.
     */
    @Test
    fun noeuds() {
        val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 20, TimeUnit.SECONDS)
        log("BANC_CONNECTED_NODES n=${nodes.size} " +
            nodes.joinToString { "${it.displayName}/${it.id}/nearby=${it.isNearby}" })

        val reachable = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_watch_app", CapabilityClient.FILTER_REACHABLE),
            20, TimeUnit.SECONDS,
        )
        log("BANC_CAP_REACHABLE n=${reachable.nodes.size} " +
            reachable.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })

        val all = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_watch_app", CapabilityClient.FILTER_ALL),
            20, TimeUnit.SECONDS,
        )
        log("BANC_CAP_ALL n=${all.nodes.size} " +
            all.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })
    }

    /**
     * Publishes **exactly** the item `ContextPublication.put` produces: same path, same payload,
     * same `setUrgent()`. The deliberate difference is that the `night_context` row is **not**
     * written: it is sealed by an SQLite trigger, hence irreversible, and the bench has no business
     * leaving a false evening in the database of an everyday device. What is measured here is the
     * transport, not the form.
     */
    @Test
    fun publierContexte() {
        val key = WirePaths.nightKey(System.currentTimeMillis())
        val request = PutDataRequest.create(WirePaths.context(key))
            .setData(System.currentTimeMillis().toString().toByteArray())
            .setUrgent()
        val item = Tasks.await(Wearable.getDataClient(ctx).putDataItem(request), 30, TimeUnit.SECONDS)
        log("BANC_CONTEXTE_PUBLIE cle=$key uri=${item.uri}")
    }

    /** The clean-up: without it, the user's watch stays unblocked for the bench's evening. */
    @Test
    fun retirerContexte() {
        val key = WirePaths.nightKey(System.currentTimeMillis())
        val n = Tasks.await(
            Wearable.getDataClient(ctx).deleteDataItems(uri(WirePaths.context(key))),
            30, TimeUnit.SECONDS,
        )
        log("BANC_CONTEXTE_RETIRE cle=$key supprimes=$n")
    }

    /**
     * Health Connect on API 37. §3 of the emulator bench notes that
     * `READ_HEALTH_DATA_IN_BACKGROUND` and `READ_HEALTH_DATA_HISTORY` do not exist before API 35,
     * and that the bench therefore exercised the degraded `BACKGROUND_READ_UNAVAILABLE` branch
     * there. Here we read what the product reads.
     */
    @Test
    fun sante() {
        val status = androidx.health.connect.client.HealthConnectClient.getSdkStatus(ctx)
        log("BANC_HC_SDK_STATUS $status")
        val reader = com.pendulum.phone.health.SleepReader(ctx)
        val availability = kotlinx.coroutines.runBlocking { reader.availability() }
        log("BANC_HC_AVAILABILITY $availability")
        val pm = ctx.packageManager
        for (p in listOf(
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
        )) {
            val defined = runCatching { pm.getPermissionInfo(p, 0) != null }.getOrDefault(false)
            val granted = pm.checkPermission(p, ctx.packageName) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            log("BANC_HC_PERM $p declaree_par_la_plateforme=$defined accordee=$granted")
        }
    }

    /**
     * The time divisor actually compiled into **this** APK.
     *
     * Nothing in the code can check that the two halves of the bench received the same value:
     * `TimeScaling` is one object per module, fed by a Gradle property, and two distinct `gradlew`
     * invocations would silently produce two applications at different scales. The check is
     * therefore **external**: the value is read on both sides and the two are compared. Its twin
     * is `BancDataLayer#echelle` on the watch side.
     */
    @Test
    fun echelle() {
        val d = com.pendulum.phone.time.Durations.ACTIVE
        // The **keys** of this line stay as they are: they are quoted word for word in
        // `docs/workings/BENCH-LOG.md`, which is a dated log. Only the Kotlin field names,
        // invisible in the output, followed the renaming.
        log(
            "BANC_ECHELLE diviseur=${com.pendulum.phone.time.TimeScaling.DIVISOR} " +
                "abandonLectureMs=${d.readGiveUpMs} ageMaxNuitMs=${d.maxNightAgeMs} " +
                "silenceAvantStaleMs=${d.silenceBeforeStaleMs}",
        )
    }

    /**
     * The rows ingestion has written, read **from the database** and not inferred from the files.
     *
     * This is the phone half of the invariant: `AckBuilder.build` only receives the `complete=1`
     * indices of this table, so what is not here cannot be acknowledged, so it cannot be erased
     * from the watch. The command-line `sqlite3` does not exist on this device; the read therefore
     * goes through the same `openHelper` as the product.
     */
    @Test
    fun base() {
        val db = com.pendulum.phone.db.PendulumDatabase.get(ctx).openHelper.readableDatabase
        db.query(
            "SELECT sessionHex, nightKey, state, totalChunks, startWallMs, endWallMs, stopReason " +
                "FROM night_session ORDER BY startWallMs",
        ).use { c ->
            log("BANC_DB_SESSIONS n=${c.count}")
            while (c.moveToNext()) {
                log(
                    "BANC_DB_SESSION hex=${c.getString(0)} cle=${c.getString(1)} " +
                        "etat=${c.getString(2)} totalChunks=${c.getInt(3)} " +
                        "debut=${c.getLong(4)} fin=${if (c.isNull(5)) "-" else c.getLong(5)} " +
                        "raison=${c.getString(6) ?: "-"}",
                )
            }
        }
        db.query(
            "SELECT sessionHex, idx, size, crc32, sampleCount, complete, receivedAtMs " +
                "FROM chunk ORDER BY sessionHex, idx",
        ).use { c ->
            log("BANC_DB_CHUNKS n=${c.count}")
            while (c.moveToNext()) {
                log(
                    "BANC_DB_CHUNK hex=${c.getString(0)} idx=${c.getInt(1)} taille=${c.getInt(2)} " +
                        "crc32=${c.getLong(3)} echantillons=${c.getInt(4)} " +
                        "complet=${c.getInt(5)} recuA=${c.getLong(6)}",
                )
            }
        }
    }

    /**
     * The end-of-bench clean-up: the manufactured night, its chunks in the database and its files.
     *
     * It is passed through `-e session <hex>` rather than guessed: erasing "the last night" from a
     * database that belongs to an everyday device would be a rule that gets it wrong one day.
     * Deleting `night_session` takes the `chunk` rows with it by cascading foreign key.
     */
    @Test
    fun purgerBanc() {
        val hex = InstrumentationRegistry.getArguments().getString("session")
        if (hex.isNullOrBlank()) {
            log("BANC_PURGE_FAIL no session passed through -e session <hex>")
            return
        }
        val db = com.pendulum.phone.db.PendulumDatabase.get(ctx).openHelper.writableDatabase
        val before = db.query("SELECT count(*) FROM chunk WHERE sessionHex='$hex'")
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        db.execSQL("DELETE FROM night_session WHERE sessionHex='$hex'")
        val after = db.query("SELECT count(*) FROM chunk WHERE sessionHex='$hex'")
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        val folder = java.io.File(java.io.File(ctx.filesDir, "chunks"), hex)
        val fileCount = folder.listFiles()?.size ?: 0
        val erased = folder.deleteRecursively()
        // The acknowledgement is published by the phone and stays in the store once the night is
        // over: it is a state, not an event, and nothing removes it.
        val ack = Tasks.await(
            Wearable.getDataClient(ctx)
                .deleteDataItems(uri(WirePaths.ack(hex)), DataClient.FILTER_LITERAL),
            30, TimeUnit.SECONDS,
        )
        log("BANC_PURGE hex=$hex chunks_avant=$before chunks_apres=$after " +
            "fichiers=$fileCount dossier_efface=$erased item_ack=$ack")
    }

    /**
     * The symmetric delivery of `BancDataLayer#livrerSonde` on the watch side: phone -> watch,
     * under the `/pendulum/ack/` prefix `AckObserver` declares in its filter.
     *
     * Unreadable payload, so `Ack.decode` throws and `AckObserver` logs "unreadable ack": no file
     * is erased on the watch, no item deleted. The only thing this probe establishes is that GMS
     * was able to bind — or that it was not.
     */
    @Test
    fun livrerSonde() {
        val path = WirePaths.ack("ba0c0000000000000000000000000000")
        val payload = "BANC-SONDE-${System.currentTimeMillis()}".toByteArray()
        val item = Tasks.await(
            Wearable.getDataClient(ctx)
                .putDataItem(PutDataRequest.create(path).setData(payload).setUrgent()),
            30, TimeUnit.SECONDS,
        )
        log("BANC_SONDE_PUBLIEE uri=${item.uri} ${payload.size}o")
    }

    /** Removes the item from [livrerSonde]. */
    @Test
    fun retirerSonde() {
        val n = Tasks.await(
            Wearable.getDataClient(ctx)
                .deleteDataItems(uri(WirePaths.ack("ba0c0000000000000000000000000000"))),
            30, TimeUnit.SECONDS,
        )
        log("BANC_SONDE_RETIREE supprimes=$n")
    }

    /**
     * The permissions declared by the application's components, and their real existence on the
     * device. This is the reading that was missing in §11.5.6: a permission a component requires
     * and that nobody defines forbids **every** binding, silently.
     *
     * The probe also reads what GMS **requests**, because that is where the question is settled: an
     * install-time permission is only granted to packages that declare it in `<uses-permission>`,
     * whatever its `protectionLevel`.
     */
    @Test
    fun permissionsDesComposants() {
        val pm = ctx.packageManager
        val infos = pm.getPackageInfo(
            ctx.packageName,
            android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS or
                android.content.pm.PackageManager.GET_ACTIVITIES,
        )
        val components = (infos.services.orEmpty().map { it.name to it.permission }) +
            (infos.receivers.orEmpty().map { it.name to it.permission }) +
            (infos.activities.orEmpty().map { it.name to it.permission })
        for ((name, perm) in components) {
            if (perm == null) continue
            val defined = runCatching { pm.getPermissionInfo(perm, 0) }.isSuccess
            log("BANC_PERM_COMPOSANT $name exige=$perm definie_sur_l_appareil=$defined")
        }

        val target = "com.google.android.gms.permission.BIND_WEARABLE_LISTENER"
        log("BANC_PERM_CIBLE definie=" + runCatching { pm.getPermissionInfo(target, 0) }.isSuccess)
        val gms = pm.getPackageInfo(
            "com.google.android.gms",
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        val requested = gms.requestedPermissions.orEmpty()
        log("BANC_PERM_GMS demandees=${requested.size} demande_la_cible=${target in requested.toSet()}")
    }

    /**
     * The attack surface of `PendulumListenerService` once it no longer carries
     * `android:permission`.
     *
     * Two questions, two measurements. **Binding**: `WearableListenerService`'s `onBind` is `final`
     * and checks nothing — it hands its binder to whoever presents one of seven actions. **Being
     * delivered to**: each of the eleven methods of the AIDL interface goes through the same
     * filter, which compares `Binder.getCallingUid()` against the Google Play services UID and
     * refuses everything else.
     *
     * This probe exercises the second from a process that is not GMS — its own. A refusal here does
     * not prove that a third-party application would be refused for the same reason; it proves that
     * the filter exists, that it runs, and that it refuses a UID that is not GMS's.
     */
    @Test
    fun surfaceDeLiaison() {
        val intent = android.content.Intent("com.google.android.gms.wearable.BIND_LISTENER")
            .setClassName(ctx, "com.pendulum.phone.ingest.PendulumListenerService")
        val latch = java.util.concurrent.CountDownLatch(1)
        var binder: android.os.IBinder? = null
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
                binder = b
                latch.countDown()
            }

            override fun onServiceDisconnected(n: android.content.ComponentName?) = Unit
        }
        val bound = ctx.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
        latch.await(20, TimeUnit.SECONDS)
        log("BANC_LIAISON bindService=$bound binder_rendu=${binder != null}")

        val b = binder
        if (b != null) {
            val method = b.javaClass.methods.firstOrNull {
                it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "com.google.android.gms.common.data.DataHolder"
            }
            log("BANC_LIAISON_METHODE ${method?.name ?: "not found"}")
            if (method != null) {
                val empty = com.google.android.gms.common.data.DataHolder.empty(0)
                val outcome = runCatching { method.invoke(b, empty) }
                log("BANC_LIAISON_APPEL uid=${android.os.Process.myUid()} " +
                    "exception=${outcome.exceptionOrNull()?.cause?.javaClass?.simpleName ?: "none"}")
            }
        }
        ctx.unbindService(conn)
    }

    /**
     * The P1 gate verdict, computed by the product's code and not by the bench.
     *
     * `P1Gate` and `P1GateExporter` are the "Settings › Measurement" screen and its CSV export.
     * Calling them here rather than steering the screen is imposed by the same wall as the rest:
     * the phone is locked by a PIN. The file is written to private storage and fetched back with
     * `run-as com.pendulum cat files/banc-porte-p1.csv`.
     */
    @Test
    fun porteP1() = kotlinx.coroutines.runBlocking {
        val db = com.pendulum.phone.db.PendulumDatabase.get(ctx)
        val sessions = db.nightDao().all()
        log("BANC_P1_SESSIONS n=${sessions.size}")
        val verdicts = sessions.map { com.pendulum.phone.ui.model.P1Gate.of(it) }
        for (v in verdicts) {
            log("BANC_P1_NUIT hex=${v.sessionHex} soiree=${v.evening} verdict=${v.verdict}")
            for (c in v.criteria) {
                log("BANC_P1_CRITERE ${c.label} valeur=${c.value} seuil=${c.threshold} etat=${c.state}")
            }
        }
        val campaign = com.pendulum.phone.ui.model.P1Gate.campaign(verdicts)
        log(
            "BANC_P1_CAMPAGNE examinees=${campaign.nightsExamined} conformes=${campaign.compliantNights} " +
                "serieMax=${campaign.longestStreak} franchie=${campaign.crossed}",
        )
        val f = java.io.File(ctx.filesDir, "banc-porte-p1.csv")
        f.outputStream().use { com.pendulum.phone.export.P1GateExporter.exportCsv(ctx, it) }
        log("BANC_P1_CSV ${f.absolutePath} ${f.length()}o")
    }

    /** What the database carries for one night, field by field — the detail `porteP1` summarises. */
    @Test
    fun detailNuit() = kotlinx.coroutines.runBlocking {
        val db = com.pendulum.phone.db.PendulumDatabase.get(ctx)
        for (s in db.nightDao().all()) {
            log(
                "BANC_NUIT hex=${s.sessionHex} etat=${s.state} debut=${s.startWallMs} fin=${s.endWallMs} " +
                    "chunks=${s.totalChunks} echantillons=${s.sampleCount} fs=${s.fsMeasuredHz} " +
                    "trous=${s.gapCount}/${s.gapTotalMs}ms batterie=${s.batteryPctLast} " +
                    "nominal=${s.nominalRateHz} arret=${s.stopReason} analysee=${s.analyzedAtMs}",
            )
        }
        log("BANC_NUIT_FIN")
    }

    /** Everything the store carries under `/pendulum`, seen from the phone. */
    @Test
    fun listerItems() {
        val buf = Tasks.await(
            Wearable.getDataClient(ctx).getDataItems(uri("/pendulum"), DataClient.FILTER_PREFIX),
            30, TimeUnit.SECONDS,
        )
        log("BANC_ITEMS n=${buf.count}")
        buf.forEach { log("BANC_ITEM ${it.uri} ${it.data?.size ?: -1}o") }
        buf.release()
    }
}
