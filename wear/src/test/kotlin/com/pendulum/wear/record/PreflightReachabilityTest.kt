package com.pendulum.wear.record

import com.google.android.gms.wearable.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What `PHONE_UNREACHABLE` really measures, written down once and for all.
 *
 * Three bench sessions revolved around this predicate, and two of them proposed a fix that the
 * third measured to be wrong. What none of them could do, for want of a predicate separated from
 * its GMS call, was to **pin down the current behaviour** — its defects included — so that a future
 * change is a choice and not a side effect.
 *
 * The test therefore does not say "this is correct". It says what it is, with the measurement
 * beside it: `BENCH-LOG.md` §7.1, §11.3 and §12.2.
 */
class PreflightReachabilityTest {

    private fun node(name: String, nearby: Boolean) = object : Node {
        override fun getId(): String = name
        override fun getDisplayName(): String = name
        override fun isNearby(): Boolean = nearby
    }

    @Test
    fun `no node, phone unreachable`() {
        assertThat(Preflight.phoneReachable(emptyList())).isFalse()
    }

    @Test
    fun `a nearby node, phone reachable`() {
        assertThat(Preflight.phoneReachable(listOf(node("Pixel 10 Pro Fold", nearby = true))))
            .isTrue()
    }

    /**
     * **The case that decides, and it is not the one we thought.**
     *
     * `isNearby=false` does not mean "unreachable": measured in §12.2, with Bluetooth off and both
     * devices on the same WiFi, a `DataItem` published by the phone reaches the watch in under 45 s
     * and a deletion in under 60 s. Returning `false` here — the fix proposed by §11.3 — would
     * display "phone unreachable" while synchronisation is happening.
     *
     * The day this test fails, someone has applied `nodes.any { it.isNearby }`. Let them read the
     * measurement before changing the test.
     */
    @Test
    fun `a node out of proximity range stays reachable`() {
        assertThat(Preflight.phoneReachable(listOf(node("Pixel 10 Pro Fold", nearby = false))))
            .`as`("the Data Layer switches to WiFi: isNearby=false is not a dead transport")
            .isTrue()
    }

    /**
     * The false green that remains, named rather than left unsaid.
     *
     * On the emulator, with the phone emulator killed, `connectedNodes` still returns a node (§7.1)
     * — so this predicate answers "reachable" for a phone that no longer exists. It has not been
     * reproduced on real hardware, for want of being able to make the phone unreachable without
     * losing the `adb` link used to measure it. The test pins down the state of affairs: the only
     * thing this predicate can tell apart is "a watch has been paired at least once".
     */
    @Test
    fun `a paired node is enough, even if nothing is running on the other side any more`() {
        val nodes = listOf(node("phone switched off", nearby = false))
        assertThat(nodes).`as`("the list stays non-empty: that is the false green").isNotEmpty()
        assertThat(Preflight.phoneReachable(nodes)).isTrue()
    }
}
