package com.pendulum.phone.ingest

import com.pendulum.algo.model.SampleBlock
import com.pendulum.format.ChunkFormat
import com.pendulum.format.DecodedBlock

/**
 * The `DecodedBlock` -> `SampleBlock` adapter. **It is what lets `:algo` depend on nothing**, and
 * it lives here for that precise reason: if `:algo` knew about `com.pendulum.format.DecodedBlock`,
 * the processing chain would be tied to a binary file format that has nothing to do with signal
 * processing, and the synthetic generator — which feeds the chain with a ground truth known by
 * construction — would have to manufacture files instead of manufacturing samples.
 *
 * ### The unit conversion, which is not a detail
 *
 * `DecodedBlock` gives back **m/s²**: `ChunkReader` dequantises with `ChunkFormat.toMs2`.
 * `SampleBlock` expects **g** ("Amplitudes in g", `Model.kt`). The factor is 9.80665.
 *
 * Forgetting this division causes no error: the chain runs, produces envelopes, detects events.
 * It is simply that every absolute threshold of the algorithm — the noise floor at 0.020 g, the
 * gravity tolerance 0.80-1.20 g, the impossible jerk at 8 g — is out by a factor of 9.8, that is,
 * never crossed in one direction and always crossed in the other. The symptom would be "the
 * detector finds nothing" or "everything is rejected", and nothing in the traces would point at a
 * unit. This is the kind of bug that costs a whole measurement campaign.
 */
object BlockAdapter {

    /** 1 g in m/s². The exact SI value, the same one used at quantisation. */
    const val G_IN_MS2 = ChunkFormat.G_IN_MS2

    /**
     * Defensive copy: the source block's arrays are left untouched.
     *
     * To be used in tests and everywhere the `DecodedBlock` is still needed afterwards. Over a
     * whole night, prefer [adoptInPlace]: 1.5 million samples x 3 axes x 4 bytes make ~19 MB, and
     * duplicating them doubles the memory peak for nothing.
     */
    fun copyOf(block: DecodedBlock, nominalHz: Double = 0.0): SampleBlock = AdaptedBlock(
        tFirstNs = block.tFirstNs,
        tLastNs = block.tLastNs,
        flags = block.flags,
        x = FloatArray(block.sampleCount) { (block.x[it] / G_IN_MS2).toFloat() },
        y = FloatArray(block.sampleCount) { (block.y[it] / G_IN_MS2).toFloat() },
        z = FloatArray(block.sampleCount) { (block.z[it] / G_IN_MS2).toFloat() },
        nominalHz = nominalHz,
    )

    /**
     * Converts the block's arrays **in place** and reuses them as they are.
     *
     * The contract, to be honoured on pain of dividing the values twice: the [DecodedBlock] passed
     * in here **must not be read again afterwards**. That holds by construction in the only real
     * caller, [SessionReassembler], which consumes the stream of `ChunkReader.forEachBlock` and
     * discards every block once it has adapted it.
     *
     * [offsetNs] shifts the block's time base. It is `0` for every chunk of a normal night and only
     * becomes non-zero across a **reboot of the watch**, where `SensorEvent.timestamp` restarts
     * from zero while the session carries on: see the bridging in [SessionReassembler]. The shift
     * belongs here rather than in `:algo` for the same reason as the unit conversion — `:algo` is
     * given samples on one continuous scale and knows nothing of chunks, headers, or boots.
     */
    fun adoptInPlace(
        block: DecodedBlock,
        nominalHz: Double = 0.0,
        offsetNs: Long = 0L,
    ): SampleBlock {
        val n = block.sampleCount
        val inv = (1.0 / G_IN_MS2).toFloat()
        for (i in 0 until n) {
            block.x[i] *= inv
            block.y[i] *= inv
            block.z[i] *= inv
        }
        return AdaptedBlock(
            block.tFirstNs + offsetNs, block.tLastNs + offsetNs,
            block.flags, block.x, block.y, block.z, nominalHz,
        )
    }

    /**
     * `suspectTimebase` is **not** carried over, and that is deliberate.
     *
     * `SampleBlock` has no field for it, and adding one would be a bad trade: `:algo` revalidates
     * the timebase anyway at step −1 (`Integrity.check`), on the same criteria, because it cannot
     * inherit a guarantee that a block CRC does not give. Carrying the flag up would suggest extra
     * information where there is only a duplicate — and would hide the fact that the revalidation
     * is done downstream.
     */
    private class AdaptedBlock(
        override val tFirstNs: Long,
        override val tLastNs: Long,
        override val flags: Int,
        override val x: FloatArray,
        override val y: FloatArray,
        override val z: FloatArray,
        override val nominalHz: Double = 0.0,
    ) : SampleBlock
}
