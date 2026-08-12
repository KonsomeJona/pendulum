package com.pendulum.phone

import android.app.Application
import android.util.Log
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.AnalysisParams
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Entry point of the process.
 *
 * Two acts at start-up, and only one of them is obvious.
 *
 * 1. **The watchdog** is (re)scheduled. A periodic WorkManager job survives reboots, but neither
 *    an uninstall of updates nor a data erasure: laying it down again at every launch costs one
 *    request and prevents an open session from staying open for ever.
 *
 * 2. **The detection of a parameter change.** If the profile active in the database no longer
 *    matches the hash the code knows how to produce — the case of an application update that has
 *    changed a default value of `:algo` — then the nights already in the database were computed
 *    with an algorithm this version does not reproduce. Guard rail 3 mandates the rescore of
 *    **all** the nights from the raw data; this is where it is triggered, because this is the
 *    only place that runs once per launch and not once per night.
 *
 * It is also the reason why the raw chunks are never deleted automatically. The day the
 * algorithm changes — and it will — the past campaign is worth only what the rescore can draw
 * from it.
 */
class PendulumApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        WorkScheduler.ensureWatchdog(this)

        scope.launch {
            val db = PendulumDatabase.get(this@PendulumApp)
            val active = db.paramDao().active()
            if (active != null && active.paramsHash != AnalysisParams.DEFAULT.paramsHash) {
                Log.i(
                    TAG,
                    "parameters changed (${active.paramsHash} -> ${AnalysisParams.DEFAULT.paramsHash}): " +
                        "rescore of all the nights from the raw data",
                )
                WorkScheduler.enqueueRescoreAll(this@PendulumApp)
            }
        }
    }

    private companion object {
        const val TAG = "PendulumApp"
    }
}
