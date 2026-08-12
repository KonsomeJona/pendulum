package com.pendulum.sleepwriter

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView

/**
 * The Health Connect permissions rationale screen.
 *
 * It exists for a mechanical reason and not an editorial one: without the two manifest declarations
 * pointing at it, Health Connect **does not show the application in its list**, and the symptom is
 * an absence, not an error. Yet that list is how `WRITE_SLEEP` gets granted by hand the day the
 * bench's UiAutomator sequence fails.
 *
 * The text says what this tool is, so that whoever finds it installed on a device understands in
 * three lines that it is a bench simulator and not a real sleep source.
 */
class PermissionsRationaleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                movementMethod = ScrollingMovementMethod()
                textSize = 16f
                text = RATIONALE
            }
        )
    }

    private companion object {
        val RATIONALE = """
            SleepWriter ${BuildConfig.SOURCE_LABEL} — test bench tool

            This application is not a product and measures nothing. It writes fabricated sleep
            sessions into Health Connect, to check that Pendulum can read them.

            It exists because Pendulum cannot write what it reads: the sleep duration must come
            from a device independent of the ankle watch, otherwise the measurement becomes
            circular. Pendulum therefore only declares read permissions, and this tool is the
            counterpart that plays the role of the third-party application.

            If you find this application installed on a device that is not a test bench,
            uninstall it: it writes false nights.
        """.trimIndent()
    }
}
