package com.pendulum.phone.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The three pairing states, on simulated inputs.
 *
 * The classification is the only part decidable without a device, and it is also the only one that
 * can be wrong without anything showing it: all three states display as a plausible screen.
 * Conflating "no watch paired" and "application missing" sends the user off to install Pendulum on
 * a watch that is paired to nothing — the installation succeeds, it shows up nowhere, and
 * onboarding goes on saying no.
 */
class WatchPairingTest {

    @Test
    fun `with no connected node, no watch is paired`() {
        val state = WatchPairing.classify(
            connectedNodes = emptySet(),
            capableNodes = emptySet(),
        )
        assertThat(state).isEqualTo(PairingState.NO_WATCH)
    }

    @Test
    fun `a watch paired without the capability means the application is missing or out of range`() {
        val state = WatchPairing.classify(
            connectedNodes = setOf("node-a"),
            capableNodes = emptySet(),
        )
        assertThat(state).isEqualTo(PairingState.APP_MISSING_OR_OUT_OF_RANGE)
    }

    @Test
    fun `a capability found validates the step`() {
        val state = WatchPairing.classify(
            connectedNodes = setOf("node-a"),
            capableNodes = setOf("node-a"),
        )
        assertThat(state).isEqualTo(PairingState.READY)
    }

    @Test
    fun `several watches paired, only one carries the application`() {
        // The case where the old watch stays paired. One is enough to validate the step: we do not
        // ask for Pendulum to be installed on every watch in the house.
        val state = WatchPairing.classify(
            connectedNodes = setOf("old", "ankle"),
            capableNodes = setOf("ankle"),
        )
        assertThat(state).isEqualTo(PairingState.READY)
    }

    @Test
    fun `a capable node wins over an empty node list`() {
        // The two reads are not atomic. A node that announces the capability *and* is declared
        // reachable is stronger evidence than a node list read one millisecond earlier and already
        // stale. Classifying that as "no watch" would send someone whose watch is answering off to
        // the companion application.
        val state = WatchPairing.classify(
            connectedNodes = emptySet(),
            capableNodes = setOf("ankle"),
        )
        assertThat(state).isEqualTo(PairingState.READY)
    }

    @Test
    fun `the two capabilities do not carry the same name`() {
        // Data Layer symmetry: each side announces its own and looks for the other's. Conflating
        // them would give a phone that detects itself and validates the step with no watch.
        assertThat(WatchPairing.PHONE_CAPABILITY)
            .isNotEqualTo(WatchPairing.WATCH_CAPABILITY)
    }
}
