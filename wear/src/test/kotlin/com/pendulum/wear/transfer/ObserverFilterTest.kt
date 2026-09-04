package com.pendulum.wear.transfer

import com.pendulum.format.wire.WirePaths
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList

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

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val OBSERVER = "com.pendulum.wear.transfer.AckObserver"
        const val DATA_CHANGED = "com.google.android.gms.wearable.DATA_CHANGED"
    }

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
        val declared = dataChangedPaths(manifest!!)

        // A bare `pathPrefix="/"` would deliver everything and turn this check into a tautology:
        // the list above is meant to be read against the manifest, one filter per path.
        assertThat(declared.filter { it.prefix }.map { it.value })
            .`as`("catch-all prefix on the DATA_CHANGED filters of AckObserver")
            .doesNotContain("/")

        val missing = handledPaths.filterNot { handled ->
            declared.any { if (it.prefix) handled.startsWith(it.value) else handled == it.value }
        }
        assertThat(missing)
            .`as`("paths handled by AckObserver that no DATA_CHANGED filter delivers: %s", missing)
            .isEmpty()
    }

    /** One `<data>` element: an `android:pathPrefix` covers everything under it, an `android:path`
     *  is exact. */
    private data class Declared(val value: String, val prefix: Boolean)

    /**
     * The `<data>` paths of the `DATA_CHANGED` filters of the `AckObserver` service — those and no
     * others. The manifest is parsed, not grepped: a first version collected every `android:path`
     * in the file, which proved only that the string appeared somewhere. `/pendulum/erase` moved
     * under a `MESSAGE_RECEIVED` filter, or into another component, stayed green — and a filter
     * landing in the wrong block while the manifest is reshuffled is the most plausible way this
     * regresses.
     */
    private fun dataChangedPaths(manifest: File): List<Declared> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val service = document.getElementsByTagName("service").elements()
            .singleOrNull { it.getAttributeNS(ANDROID_NS, "name") == OBSERVER }
        assertThat(service).`as`("<service android:name=\"%s\">", OBSERVER).isNotNull

        return service!!.getElementsByTagName("intent-filter").elements()
            .filter { filter ->
                filter.getElementsByTagName("action").elements()
                    .any { it.getAttributeNS(ANDROID_NS, "name") == DATA_CHANGED }
            }
            .flatMap { it.getElementsByTagName("data").elements() }
            .mapNotNull { data ->
                data.getAttributeNS(ANDROID_NS, "path").takeIf { it.isNotEmpty() }
                    ?.let { Declared(it, prefix = false) }
                    ?: data.getAttributeNS(ANDROID_NS, "pathPrefix").takeIf { it.isNotEmpty() }
                        ?.let { Declared(it, prefix = true) }
            }
    }

    private fun NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

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
