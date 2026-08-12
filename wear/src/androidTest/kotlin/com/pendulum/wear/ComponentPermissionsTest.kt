package com.pendulum.wear

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Every permission required by a component must exist on the device.**
 *
 * This test exists because of a failure whose failure mode is complete silence. The product's two
 * `WearableListenerService` carried
 * `android:permission="com.google.android.gms.permission.BIND_WEARABLE_LISTENER"`, a permission
 * that **no package defines** and that Play Services does not even request. A permission not held
 * by the caller forbids the binding; a permission that is not defined is held by nobody.
 * Consequence: Android refused Play Services the right to start those services on every delivery,
 * so chunk ingestion, publication of the acknowledgement, its application, the start request and
 * the sweep request were all dead — and the only trace was a `W ActivityManager: Permission
 * Denial` line in the system log, which the application never sees. `BENCH-LOG.md` §12.1 puts
 * figures on it.
 *
 * The rule is general and names no permission in particular: a string written into a manifest from
 * documentation that has since changed produces no compilation error, no lint warning, and no
 * run-time exception. It produces a component the system no longer opens to anyone. The only place
 * where this shows is the device, which makes this guard rail an instrumented test by nature.
 *
 * The twin of this file is in `:phone`, where it runs on every push on the CI emulator. This one
 * has no watch emulator in CI: it is launched by hand on a real watch, via
 * `./gradlew :wear:connectedDebugAndroidTest`. That is an accepted discrepancy and not an
 * oversight — the faulty line was in both manifests, so the guard rail must exist on both sides
 * even if only one of them is automated.
 *
 * The test is **exhaustive by construction**: it enumerates the services, receivers and activities
 * declared by the assembled application, rather than a hand-maintained list that would go stale at
 * the first component added.
 */
@RunWith(AndroidJUnit4::class)
class ComponentPermissionsTest {

    @Test
    fun every_permission_required_by_a_component_exists_on_the_device() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val packageInfo = pm.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_PROVIDERS,
        )

        val components =
            packageInfo.services.orEmpty().map { "service ${it.name}" to it.permission } +
                packageInfo.receivers.orEmpty().map { "receiver ${it.name}" to it.permission } +
                packageInfo.activities.orEmpty().map { "activity ${it.name}" to it.permission } +
                packageInfo.providers.orEmpty().map { "provider ${it.name}" to it.writePermission }

        val missing = components.mapNotNull { (name, permission) ->
            if (permission == null) {
                null
            } else if (runCatching { pm.getPermissionInfo(permission, 0) }.isSuccess) {
                null
            } else {
                "$name requires $permission, which no package defines on this device"
            }
        }

        // The message carries the whole list: fixing one line to discover the next one on the
        // following round costs one emulator run per line.
        if (missing.isNotEmpty()) {
            throw AssertionError(
                "Permissions required by a component and absent from the device — the binding is " +
                    "then refused silently:\n" + missing.joinToString("\n"),
            )
        }
    }
}
