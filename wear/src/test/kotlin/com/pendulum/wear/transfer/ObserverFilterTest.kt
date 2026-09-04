package com.pendulum.wear.transfer

import com.pendulum.format.wire.WirePaths
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every path `AckObserver` handles must be named by a filter in the manifest.
 *
 * ### The failure this exists to catch
 *
 * Play Services only delivers `DATA_CHANGED` for the paths an `intent-filter` names. A branch added
 * to `onDataChanged` for a path nobody declared therefore compiles, reads well, is covered by its
 * own unit tests — and never runs. The item is put by the phone, replicated to the watch, and
 * delivered to no one. There is no exception, no log line, nothing: a feature that quietly does
 * nothing.
 *
 * It has already happened here in its sibling form. Both `WearableListenerService` once carried an
 * `android:permission` that Play Services could not satisfy, so the system refused to start them and
 * **the whole of ingestion was dead**, with a single `Permission Denial` line in the system log as
 * its only trace. `ComponentPermissionsTest` guards that side; this guards the other. Both say the
 * same thing: on this platform the manifest can silently disarm correct code.
 *
 * It caught its first defect immediately. The erase order — the item that tells the watch to disown
 * a night the phone has erased, so the night does not come back at its close — was handled in
 * `onDataChanged` while the only `DATA_CHANGED` filter named `/pendulum/ack`.
 *
 * ### Why it reads the source manifest
 *
 * A JVM test, not an instrumented one: what it checks is a text file, and it is worth nothing if it
 * only runs on the days someone boots a watch emulator. That also means it reads the **source**
 * manifest rather than the merged one, so a filter that a library contributed would be invisible
 * here — acceptable, because the paths at stake are ours and are declared here.
 */
class ObserverFilterTest {

    /**
     * The paths `AckObserver.onDataChanged` dispatches on, and which must therefore be delivered.
     *
     * Kept as a literal list rather than derived from the code: the point is to state, in one place
     * a human reads, what this component expects to receive. A branch added without a line here is
     * exactly the omission being guarded against — so adding the line is part of adding the branch.
     */
    private val handledPaths = listOf(
        WirePaths.ACK_PREFIX,
        WirePaths.ERASE,
    )

    @Test
    fun `every path the observer handles is declared in the manifest`() {
        val manifest = manifestFile()
        assertThat(manifest).`as`("wear/src/main/AndroidManifest.xml").isNotNull
        val xml = manifest!!.readText()

        // The `android:path` / `android:pathPrefix` values of the DATA_CHANGED filters. A prefix
        // covers everything under it, which is why the acknowledgement is declared as a prefix and
        // the erase order — which carries no identifier — as an exact path.
        val declared = Regex("""android:path(?:Prefix)?="([^"]+)"""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()

        val missing = handledPaths.filterNot { handled ->
            declared.any { handled == it || handled.startsWith(it) }
        }
        assertThat(missing)
            .`as`("paths handled by AckObserver that no intent-filter delivers: %s", missing)
            .isEmpty()
    }

    private fun manifestFile(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/AndroidManifest.xml"),
                File(dir, "wear/src/main/AndroidManifest.xml"),
            )) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }
}
