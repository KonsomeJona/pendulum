package com.pendulum.phone.health

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView

/**
 * The Health Connect permissions rationale screen.
 *
 * Health Connect sends the user here from its own settings, by two different paths depending on
 * the Android version (see the manifest: an `activity` **and** an `activity-alias`). Without it,
 * the application does not appear in the Health Connect list — and the symptom is an absence, not
 * an error.
 *
 * Written in `View` and not in Compose, deliberately: this package must owe nothing to
 * `com.pendulum.phone.ui`, which is being written in parallel. A three-paragraph screen does not
 * justify a layer dependency.
 *
 * The text is hard-coded here rather than in `strings.xml` for the same reason — string resources
 * belong to the interface, and the rule "no verb of change in the string resources" (guard rail 5)
 * is verified by a test on that file, which must not be polluted with texts that do not talk about
 * results.
 */
class PermissionsRationaleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            movementMethod = ScrollingMovementMethod()
            textSize = 16f
            text = RATIONALE
        }
        setContentView(text)
    }

    private companion object {
        val RATIONALE = """
            Why Pendulum reads your sleep data

            Pendulum counts leg movements with a watch worn at the ankle. To turn them into an
            index per hour of sleep, it needs to know how many hours you slept.

            That duration cannot be inferred from the ankle watch: the application counts
            movements there, and a movement-based sleep algorithm would declare "wake" precisely
            during the periods where there are the most movements to count. The figure would be
            distorted twice, in the same direction.

            Pendulum therefore reads only the sleep sessions written by your watch or by your usual
            sleep application.

            Read only. Pendulum writes nothing into Health Connect.

            Reading in the background is necessary because your watch does not transfer its night
            on waking, but when its own battery policy decides to — sometimes several hours later,
            phone locked.

            This data does not leave your phone. The application does not declare the Internet
            access permission: it technically cannot send it anywhere else.
        """.trimIndent()
    }
}
