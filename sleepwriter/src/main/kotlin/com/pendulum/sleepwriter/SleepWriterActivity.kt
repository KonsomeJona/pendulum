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
 * The one and only activity: it reads its extras, acts, logs and finishes.
 *
 * ### Why an activity and not a service or a `BroadcastReceiver`
 *
 * The Health Connect permission request goes through
 * `PermissionController.createRequestPermissionResultContract()`, which requires an
 * `ActivityResultCaller` — so an activity. A service would have been cleaner for a scriptable tool,
 * but it would have needed an activity **on top** for the one gesture we cannot script reliably;
 * two components for thirty lines of logic were not worth it.
 *
 * ### What "drivable from the command line" imposes
 *
 * The activity is exported, reads everything from `Intent.getExtras`, and finishes by itself. The
 * result is not only shown on screen: it goes to `logcat` under the [BenchLog.TAG] tag, because the
 * bench does not look at the screen.
 *
 * ### The interface, and why it is this poor
 *
 * This is a tool, not a product. One button to grant the permission, one button to write a default
 * night, and the last log line displayed as it is. Anything added here would have to be maintained
 * without ever being tested.
 *
 * ### This module is never published
 *
 * It declares `WRITE_SLEEP`, which Pendulum forbids itself. Its release variant is disabled in
 * `build.gradle.kts`: it only exists in debug, and only on a bench.
 */
class SleepWriterActivity : ComponentActivity() {

    private lateinit var outputView: TextView

    private val permissionRequest =
        registerForActivityResult(SleepWriter.permissionContract()) {
            // The contract's return value does not reliably say what was granted: we read the state
            // back rather than believe the answer. It is also what the script expects — a fresh
            // `mode=permissions` line after the UiAutomator sequence.
            perform(Request(Mode.PERMISSIONS, Scenario.NONE, 0L, 0L))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(minimalUi())

        val request = requestFrom(intent)
        if (request == null) {
            // Launched from the icon: we do nothing on our own. A tool that writes to Health
            // Connect because someone touched its icon is a tool that leaves you, later, unable to
            // say where a night came from.
            outputView.text = HELP
            return
        }

        // `keepScreenOn` for the only reason that matters: a deferred write waits inside the
        // process, and a screen going off on an emulator ends up taking the activity with it.
        if (request.delayMs > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        perform(request, finishWhenDone = intent.getBooleanExtra(EXTRA_FINISH, true))
    }

    private fun perform(request: Request, finishWhenDone: Boolean = false) {
        lifecycleScope.launch {
            if (request.delayMs > 0) {
                // A line distinct from `RESULT`: a script looking for a result must not mistake
                // the announcement for the outcome.
                BenchLog.emit(
                    BenchLog.PENDING,
                    listOf(
                        "mode" to request.mode.key,
                        "scenario" to request.scenario.key,
                        "source" to BuildConfig.SOURCE_LABEL,
                        "pkg" to packageName,
                        "delayMs" to request.delayMs,
                    ),
                )
                delay(request.delayMs)
            }
            outputView.text = SleepWriter(applicationContext).execute(request)
            if (finishWhenDone) finish()
        }
    }

    /**
     * Translation of the `am start` extras into a [Request].
     *
     * ### The trap in the format of the extras
     *
     * `am start` types the extras by the flag and not by the value: `--es` string, `--ei` 32-bit
     * integer, `--el` long, `--ez` boolean. Epoch instants in milliseconds **exceed 2^31**: passed
     * as `--ei` they make the command fail, and passed as `--es` they arrive fine but in the wrong
     * type. On screen the two failures look alike.
     *
     * Hence the defensive reading below: a bare `getLongExtra` would return the default value for a
     * string-typed extra, so a silently wrong night. We read the long, then fall back on the
     * string, and we prefer an explicit default night to a false one.
     */
    private fun requestFrom(intent: Intent): Request? {
        val extras = intent.extras ?: return null
        val mode = Mode.from(extras.getString(EXTRA_MODE)) ?: return null
        val scenario = Scenario.from(extras.getString(EXTRA_SCENARIO)) ?: Scenario.FULL_NIGHT

        // Default window: the last eight hours. Always in the past, therefore always acceptable to
        // Health Connect, and enough for a smoke test without computing a date.
        val now = System.currentTimeMillis()
        val end = long(extras, EXTRA_END_MS) ?: now
        val start = long(extras, EXTRA_START_MS) ?: (end - 8 * 3_600_000L)

        return Request(
            mode = mode,
            scenario = scenario,
            startMs = start,
            endMs = end,
            delayMs = long(extras, EXTRA_DELAY_MS) ?: 0L,
            marginMin = long(extras, EXTRA_MARGIN_MIN) ?: 180L,
        )
    }

    // `Bundle.get` is deprecated in favour of the typed accessors, and it is nonetheless the only
    // one that fits here: we are not trying to read a long, we are trying to know **what type** the
    // extra we received is. `getLong` would return 0 on an extra passed as `--es` without
    // signalling anything, that is to say a false night instead of a rejected command.
    @Suppress("DEPRECATION")
    private fun long(extras: Bundle, key: String): Long? {
        if (!extras.containsKey(key)) return null
        return when (val value = extras.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun minimalUi(): ViewGroup {
        outputView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 32, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            addView(
                Button(context).apply {
                    text = "Grant sleep write access"
                    setOnClickListener { permissionRequest.launch(SleepWriter.PERMISSIONS) }
                }
            )
            addView(
                Button(context).apply {
                    text = "Write the last 8 hours"
                    setOnClickListener {
                        val end = System.currentTimeMillis()
                        perform(
                            Request(
                                Mode.WRITE,
                                Scenario.FULL_NIGHT,
                                end - 8 * 3_600_000L,
                                end,
                            )
                        )
                    }
                }
            )
            addView(outputView)
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

        val HELP = """
            SleepWriter ${BuildConfig.SOURCE_LABEL} — ${BuildConfig.APPLICATION_ID}

            Hypnogram writer for the Pendulum bench. Debug only, never published.

            Driving it: adb shell am start -n <package>/com.pendulum.sleepwriter.SleepWriterActivity
              --es mode write|verify|permissions|purge
              --es scenario full-night|duration-only|none|conflict
              --el startMs <epoch ms>  --el endMs <epoch ms>
              --el delayMs <ms>        --el marginMin <minutes>

            Result: adb logcat -d -s ${BenchLog.TAG}:I | grep ${BenchLog.RESULT}

            See sleepwriter/README.md.
        """.trimIndent()
    }
}
