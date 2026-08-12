package com.pendulum.wear.time

import com.pendulum.format.time.TimeScale
import com.pendulum.format.wire.WireProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The test that makes the scale **exhaustive** rather than careful.
 *
 * It does not check a list of durations that would have to be kept up to date: it builds two
 * catalogues, one at 1 and the other at 250, and compares **every** property of the class by
 * reflection. A duration added to `Durations` without going through [TimeScale.ms] comes out
 * identical at both scales and makes the test fail the very day it is written — not three months
 * later, in front of a bench where a single path would fail to speed up and nobody would know
 * which one.
 *
 * Its twin `DurationsInventoryTest` covers the other half of the problem: a duration that would
 * never have been added here at all.
 */
class DurationsTest {

    private val divisor = 250L

    /** The instance accessors of [Durations]: every catalogue duration has exactly one. */
    private fun accessors(): List<Method> = Durations::class.java.declaredMethods
        .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
        .filter { it.parameterCount == 0 && it.name.startsWith("get") }
        .sortedBy { it.name }

    /** Equality by content: `LongArray.equals` is reference identity. */
    private fun sameValue(a: Any?, b: Any?): Boolean =
        if (a is LongArray && b is LongArray) a.contentEquals(b) else a == b

    @Test
    fun `every duration in the catalogue is scaled`() {
        val real = Durations(TimeScale.REAL_TIME_DIVISOR)
        val compressed = Durations(divisor)

        for (accessor in accessors()) {
            when (val nominal = accessor.invoke(real)) {
                is Long -> assertThat(accessor.invoke(compressed) as Long)
                    .`as`("%s: scaled by %d", accessor.name, divisor)
                    .isEqualTo(TimeScale.ms(nominal, divisor))

                is LongArray -> {
                    val obtained = accessor.invoke(compressed) as LongArray
                    assertThat(obtained)
                        .`as`("%s: scaled term by term by %d", accessor.name, divisor)
                        .isEqualTo(TimeScale.ms(nominal, divisor))
                }

                // A duration is a number of milliseconds. Another type in this catalogue is either
                // a value that has no business being there, or a duration expressed some other way
                // — and in both cases the decision must be read again before going any further.
                else -> throw AssertionError(
                    "${accessor.name} returns ${nominal?.javaClass?.name}: " +
                        "`Durations` only holds durations in milliseconds",
                )
            }
        }
    }

    @Test
    fun `no duration survives compression unchanged`() {
        // An **inverted** assertion, in the spirit of T11. It states that compression really does
        // change something everywhere. The day it fails, it is because a duration has become so
        // short that the one-millisecond floor makes it indifferent to the scale — in which case
        // it is no longer a duration being compressed, it is a null delay in disguise, and the
        // question "why does this delay exist at all?" is open again.
        val real = Durations(TimeScale.REAL_TIME_DIVISOR)
        val compressed = Durations(divisor)

        for (accessor in accessors()) {
            assertThat(sameValue(accessor.invoke(real), accessor.invoke(compressed)))
                .`as`("%s must change at divisor %d", accessor.name, divisor)
                .isFalse()
        }
    }

    @Test
    fun `the catalogue is not empty`() {
        // Without this bound, deleting every property of `Durations` would turn the two tests above
        // green on an empty catalogue. A test that can no longer fail at anything is worse than a
        // missing test: it reassures.
        assertThat(accessors()).hasSizeGreaterThanOrEqualTo(8)
    }

    @Test
    fun `every duration carries the Ms suffix`() {
        // The naming convention **is** the rule that divides them: a `...Ms` is wall-clock time and
        // gets scaled; a `...Ns` or `...Us` is sensor or hardware time and never enters it; a name
        // in bytes has no business here at all. A `getRotationBytes` accessor turning up in this
        // catalogue would be the exact way the 92 160-byte ceiling would end up compressed without
        // anyone having decided it.
        for (accessor in accessors()) {
            assertThat(accessor.name)
                .`as`("name of a catalogue duration")
                .endsWith("Ms")
        }
    }

    @Test
    fun `the telemetry rate is the one of the minute tick`() {
        // Night telemetry has **no** clock of its own: it is emitted by
        // `RecordingService.minuteTick`, that is, one service tick out of six. The protocol, for
        // its part, announces a nominal rate of one minute to the phone, and it is against that
        // announcement that it will count the missing points.
        //
        // The two statements must remain one and the same. The day the service tick changes period,
        // this test fails — otherwise the phone would go on expecting one point a minute from a
        // watch emitting one every thirty seconds, and would conclude there were extra points
        // rather than a change of rate.
        assertThat(WireProtocol.TELEMETRY_PERIOD_MS)
            .`as`("the period announced to the phone must equal six service ticks")
            .isEqualTo(6 * Durations(TimeScale.REAL_TIME_DIVISOR).serviceTickMs)
    }

    @Test
    fun `the active catalogue follows the compiled variant`() {
        assertThat(Durations.ACTIVE.chunkRotationMs)
            .isEqualTo(Durations(TimeScaling.DIVISOR).chunkRotationMs)
    }
}
