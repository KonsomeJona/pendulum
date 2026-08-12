package com.pendulum.phone.ingest

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
 * @param startWallMs wall clock at the opening of the **first** chunk.
 * @param firstEventTimestampNs `SensorEvent.timestamp` of the first sample of the first chunk.
 * @param timelineT0Ns `tFirstNs` of the first block actually decoded. It can differ from
 *   [firstEventTimestampNs] if the very first blocks were rejected by the integrity check — hence
 *   two fields and not one.
 */
data class TimeAnchor(
    val startWallMs: Long,
    val firstEventTimestampNs: Long,
    val timelineT0Ns: Long,
) {

    /** Offset, in milliseconds, between the origin of the timeline and `startWallMs`. */
    private val t0OffsetMs: Long get() = (timelineT0Ns - firstEventTimestampNs) / 1_000_000L

    /** Wall clock (epoch ms) -> milliseconds relative to the timeline. */
    fun toMsRel(wallMs: Long): Long = (wallMs - startWallMs) - t0OffsetMs

    /** Relative milliseconds -> wall clock. For display and export only. */
    fun toWallMs(msRel: Long): Long = startWallMs + msRel + t0OffsetMs
}
