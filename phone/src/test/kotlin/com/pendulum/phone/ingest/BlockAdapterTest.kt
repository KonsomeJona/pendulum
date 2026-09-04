package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.DecodedBlock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The adapter converts m/s² (what `ChunkReader` returns) into g (what `SampleBlock` expects).
 *
 * Forgetting that division causes no error at all: the chain runs and produces figures. It is
 * simply that every absolute threshold of the algorithm is crossed by a factor of 9.8 — noise
 * floor at 0.020 g, gravity tolerance 0.80-1.20 g, impossible jerk at 8 g. The symptom is "the
 * detector finds nothing" or "everything is rejected", and nothing points at a unit. Hence this
 * test, which matters more than it looks.
 */
class BlockAdapterTest {

    private fun block(vararg valuesMs2: Float): DecodedBlock {
        val n = valuesMs2.size
        return DecodedBlock(
            tFirstNs = 1_000L,
            tLastNs = 1_000L + (n - 1) * 20_000_000L,
            flags = ChunkFormat.FLAG_GAP_BEFORE,
            x = FloatArray(n) { valuesMs2[it] },
            y = FloatArray(n) { 0f },
            z = FloatArray(n) { ChunkFormat.G_IN_MS2.toFloat() },
        )
    }

    @Test
    // `point` spelled out: a Kotlin backticked name cannot contain `.`, and `9,8` would read
    // as a thousands separator in English.
    fun `gravity is 1 g after conversion, not 9 point 8`() {
        val adapted = BlockAdapter.copyOf(block(0f, 0f, 0f))
        assertThat(adapted.z[0]).isCloseTo(1.0f, within(1e-5f))
        assertThat(adapted.z[2]).isCloseTo(1.0f, within(1e-5f))
    }

    @Test
    fun `the copy does not touch the source block`() {
        val source = block(9.80665f, -9.80665f)
        BlockAdapter.copyOf(source)
        assertThat(source.x[0]).isEqualTo(9.80665f)
        assertThat(source.x[1]).isEqualTo(-9.80665f)
    }

    @Test
    fun `adoption in place converts the arrays of the source block`() {
        val source = block(9.80665f, -19.6133f)
        val adapted = BlockAdapter.adoptInPlace(source)

        assertThat(adapted.x[0]).isCloseTo(1.0f, within(1e-5f))
        assertThat(adapted.x[1]).isCloseTo(-2.0f, within(1e-4f))
        // The contract: the arrays are shared, the source block must not be read any more.
        assertThat(adapted.x).isSameAs(source.x)
    }

    @Test
    fun `the two routes give the same result`() {
        val values = floatArrayOf(0f, 1f, -3.5f, 40f, -40f, 0.001f)
        val byCopy = BlockAdapter.copyOf(block(*values))
        val byAdoption = BlockAdapter.adoptInPlace(block(*values))
        for (i in values.indices) {
            assertThat(byAdoption.x[i]).isCloseTo(byCopy.x[i], within(1e-6f))
        }
    }

    @Test
    fun `the timestamps and the flags cross the adapter unchanged`() {
        val source = block(0f, 0f, 0f)
        val adapted = BlockAdapter.copyOf(source)
        assertThat(adapted.tFirstNs).isEqualTo(source.tFirstNs)
        assertThat(adapted.tLastNs).isEqualTo(source.tLastNs)
        // The flags carry FLAG_GAP_BEFORE, which `:algo` needs in order to break its segments.
        assertThat(adapted.flags).isEqualTo(ChunkFormat.FLAG_GAP_BEFORE)
    }

    @Test
    fun `the epoch offset shifts both timestamps, and the nominal rate comes through`() {
        // Across a reboot of the watch, `SessionReassembler` shifts every post-reboot block by the
        // same offset. Shifting `tFirstNs` alone would leave the block's own duration negative
        // and step -1 would reject it; and the rate is read per chunk because it changes in
        // flight, so the value the header carries has to reach the block, not a default.
        val adapted = BlockAdapter.adoptInPlace(
            block(0f, 0f, 0f),
            nominalHz = 25.0,
            offsetNs = 7_000_000_000L,
        )
        assertThat(adapted.tFirstNs).isEqualTo(1_000L + 7_000_000_000L)
        assertThat(adapted.tLastNs).isEqualTo(1_000L + 2 * 20_000_000L + 7_000_000_000L)
        assertThat(adapted.nominalHz).isEqualTo(25.0)
    }

    @Test
    fun `a block of a single sample gets through`() {
        val adapted = BlockAdapter.copyOf(block(9.80665f))
        assertThat(adapted.x).hasSize(1)
        assertThat(adapted.x[0]).isCloseTo(1.0f, within(1e-5f))
    }
}
