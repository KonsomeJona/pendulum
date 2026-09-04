package com.pendulum.phone.ingest

import com.pendulum.format.ChunkFormat
import com.pendulum.format.ChunkHeader

/**
 * The conversion between the wall clock and the sensor timeline.
 *
 * ### Why this is not a subtraction
 *
 * Three clocks live side by side, and the chunk header records all three precisely because they
 * are not interchangeable:
 *
 *  - `startWallMs` — wall clock, subject to daylight-saving changes and to NTP resynchronisation;
 *  - `startElapsedRealtimeNs` — time since boot, monotonic;
 *  - `firstEventTimestampNs` — `SensorEvent.timestamp`, which is **not** guaranteed to equal
 *    `elapsedRealtimeNanos`: some manufacturers exclude suspend time from it.
 *
 * Everything `:algo` produces is timestamped in milliseconds **relative to the sensor timeline**.
 * Health Connect, for its part, timestamps in UTC wall clock. The two only come together through
 * the anchoring below. Taking the difference of two wall clocks — for instance "start of the sleep
 * session minus `startWallMs`" — would give a silent offset of several minutes as soon as the
 * phone's clock resynchronises during the night, and an offset of a whole hour on the night of the
 * daylight-saving change. A hypnogram shifted by an hour raises no exception: it simply produces a
 * wrong aPLM-i.
 *
 * ### Which instant `startWallMs` describes
 *
 * The anchor reads `startWallMs` and `firstEventTimestampNs` as one and the same instant. Until
 * 4 September 2026 they were not: the watch stamped the wall clock when the first **block** reached
 * the disk and the sample stamp at the first **sample**, and a chunk opens from the first FIFO
 * flush — in `WAKEUP 30 s`, the mode measured on the Pixel Watch 3, that flush is up to thirty
 * seconds after the sample it carries. Every wall clock converted here — the hypnogram, the
 * bedtime journal — therefore landed 10 to 60 s too early on the timeline, which is the same thing
 * as every movement landing that much too late against the hypnogram: one 30 s sleep epoch,
 * varying from one night to the next with the phase between `registerListener` and the first
 * burst. A movement at a wake/sleep boundary changed epoch, hence AASM status, silently.
 *
 * The watch now stamps all three clocks at the first sample, so a header it produces carries no
 * age. The age is still readable from a header that predates that change — a night already
 * recorded, a bench file — because the boot clock and the sample stamp share a base:
 * `startElapsedRealtimeNs - firstEventTimestampNs` is how old the first sample was when the chunk
 * opened. [firstSampleWallMs] pulls the wall clock back by it. On a header the watch has already
 * pulled back, that difference is zero and nothing is subtracted twice — the two corrections are
 * compatible only because the watch moves **both** its clocks, and whoever changes that side to
 * "raw `startElapsedRealtimeNs`" again would make this side subtract the age a second time.
 *
 * The age is only subtracted when it is plausible, within the same window as on the watch: some
 * OEMs exclude suspend time from `SensorEvent.timestamp`, so two hours into the night the
 * difference no longer measures a burst, and on the bench the sample stamps run ahead of the boot
 * clock. Outside the window the anchor stays merely late, as it was, rather than moved by hours.
 *
 * @param startWallMs wall clock at the opening of the **first** chunk.
 * @param firstEventTimestampNs `SensorEvent.timestamp` of the first sample of the first chunk.
 * @param timelineT0Ns `tFirstNs` of the first block actually decoded. It can differ from
 *   [firstEventTimestampNs] if the very first blocks were rejected by the integrity check — hence
 *   two fields and not one.
 * @param startElapsedRealtimeNs `elapsedRealtimeNanos` at the opening of the first chunk, from the
 *   same header. Equal to [firstEventTimestampNs] on a header the current watch writes — no age —
 *   and later than it on a header that predates the pull-back. [of] reads all four fields from the
 *   header; [SessionReassembler] builds every anchor through it.
 */
data class TimeAnchor(
    val startWallMs: Long,
    val firstEventTimestampNs: Long,
    val timelineT0Ns: Long,
    val startElapsedRealtimeNs: Long,
) {

    /** How old the first sample was when the chunk opened, or zero when the two clocks disagree. */
    private val burstAgeMs: Long
        get() {
            val ageNs = startElapsedRealtimeNs - firstEventTimestampNs
            return if (ageNs in 0..MAX_BURST_AGE_NS) ageNs / 1_000_000L else 0L
        }

    /** Wall clock (epoch ms) of the **first sample**, which is what [startWallMs] was meant to be. */
    val firstSampleWallMs: Long get() = startWallMs - burstAgeMs

    /** Offset, in milliseconds, between the origin of the timeline and the first sample. */
    private val t0OffsetMs: Long get() = (timelineT0Ns - firstEventTimestampNs) / 1_000_000L

    /** Wall clock (epoch ms) -> milliseconds relative to the timeline. */
    fun toMsRel(wallMs: Long): Long = (wallMs - firstSampleWallMs) - t0OffsetMs

    /** Relative milliseconds -> wall clock. For display and export only. */
    fun toWallMs(msRel: Long): Long = firstSampleWallMs + msRel + t0OffsetMs

    companion object {

        /** @see ChunkFormat.MAX_BURST_AGE_NS — the watch applies the same bound when it writes. */
        const val MAX_BURST_AGE_NS: Long = ChunkFormat.MAX_BURST_AGE_NS

        /**
         * The anchor of a night, from the header of its first decoded chunk.
         *
         * @param timelineT0Ns `tFirstNs` of the first block actually decoded from that chunk.
         */
        fun of(header: ChunkHeader, timelineT0Ns: Long): TimeAnchor = TimeAnchor(
            startWallMs = header.startWallMs,
            firstEventTimestampNs = header.firstEventTimestampNs,
            timelineT0Ns = timelineT0Ns,
            startElapsedRealtimeNs = header.startElapsedRealtimeNs,
        )
    }
}
