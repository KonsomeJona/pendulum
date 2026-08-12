package com.pendulum.phone.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The bench seeding trigger, invocable from `adb`.
 *
 * ```
 * # seed 7 eligible nights (+ 1 provisional, + 1 excluded)
 * adb shell am start -n com.pendulum/com.pendulum.phone.ui.MainActivity
 * adb shell am broadcast -f 0x00000020 \
 *   -n com.pendulum/com.pendulum.phone.bench.SeederReceiver \
 *   -a com.pendulum.bench.SEED --ei nights 7
 *
 * # return the device to its fresh state
 * adb shell am broadcast -f 0x00000020 \
 *   -n com.pendulum/com.pendulum.phone.bench.SeederReceiver \
 *   -a com.pendulum.bench.ERASE
 * ```
 *
 * `-f 0x00000020` is `FLAG_INCLUDE_STOPPED_PACKAGES`, and it is not decorative: an application
 * freshly installed, or one that has just been through a `pm clear`, is in the *stopped* state,
 * and the system **silently filters out** every broadcast towards it. Without that flag the
 * command returns "Broadcast completed: result=0" and nothing happens.
 *
 * Follow the progress with `adb logcat -s PendulumBench`. The last line says "finished in N ms".
 *
 * ### Start the application first, and this is not a comfort detail
 *
 * The seeding takes several tens of seconds — synthesising an eight-hour night at 50 Hz costs a
 * few seconds on a phone — and **nothing in a receiver makes it possible to hold a process that
 * long**. Both routes were tried and measured on the Pixel Fold:
 *
 *  - **WorkManager**: the work starts, writes two nights, and `onStopJob` interrupts it after four
 *    seconds. The cause is not WorkManager but the priority of the process — once the broadcast is
 *    over, the application has no component in the foreground and it drops back to cached. Making
 *    it *expedited* would impose a `getForegroundInfo`, hence a notification channel and a visible
 *    notification, in a tool whose entire point is to display nothing.
 *  - **`goAsync` held to the end**: the process does keep its priority, but the system clamps a
 *    manifest receiver to 60 s. Measured, verbatim, when the action still bore its French name:
 *    `ANR in com.pendulum / Reason: Broadcast of Intent { act=com.pendulum.banc.ENSEMENCER }`, at
 *    the fifth night out of nine.
 *
 * The receiver **therefore finishes its broadcast immediately** and carries on in a scope of its
 * own: no deadline weighs on it any more. What keeps the process alive is then the activity, hence
 * the first line of the command. It is also the natural order of the bench, which opens the
 * application in order to photograph it.
 */
class SeederReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action
        if (action != ACTION_SEED && action != ACTION_ERASE) {
            Log.w(TAG, "unknown action: $action")
            return
        }

        val nights = intent.getIntExtra(EXTRA_NIGHTS, Seeding.DEFAULT_NIGHTS)
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                if (action == ACTION_SEED) {
                    Seeding.seed(app, nights)
                } else {
                    Seeding.erase(app)
                }
                Log.i(TAG, "$action: finished in ${System.currentTimeMillis() - startedAt} ms")
            } catch (t: Throwable) {
                // An interrupted seeding leaves a half-filled database. That is not a state to
                // repair: `seed` begins by erasing everything, so replaying the command starts
                // again cleanly.
                Log.e(TAG, "$action: failed", t)
            }
        }
    }

    companion object {
        const val TAG = "PendulumBench"

        const val ACTION_SEED = "com.pendulum.bench.SEED"
        const val ACTION_ERASE = "com.pendulum.bench.ERASE"

        /** Number of **eligible** nights. The provisional one and the excluded one come on top. */
        const val EXTRA_NIGHTS = "nights"

        /**
         * The scope that outlives the receiver, and that is deliberately a singleton.
         *
         * A `BroadcastReceiver` is thrown away as soon as `onReceive` returns; a scope built
         * inside the method would therefore be destroyed with it, and the work along with it. This
         * one lives in the class loader, hence for as long as the process. It is never cancelled:
         * there is nothing to cancel, the seeding being a single gesture whose completion is the
         * whole point.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
