package com.pendulum.sleepwriter

import android.util.Log

/**
 * The machine-readable output of the tool: **one line, one stable tag, no space in any value**.
 *
 * ### Why this format and not prose
 *
 * This application is driven by `adb` and read by `adb logcat`. What makes it useful is not what it
 * shows on screen but the fact that a script can know, without looking at the screen, whether the
 * write succeeded. A prose sentence forces a fragile regular expression; a `key=value` line is read
 * with `grep` then `tr ' ' '\n'`.
 *
 * Three rules, each with a reason:
 *  - **one single line per action.** `logcat` interleaves processes and cuts long lines at 4 kB; a
 *    result spread over three lines cannot be reassembled reliably;
 *  - **never a space inside a value**, lists included (separator `;`), otherwise splitting on
 *    spaces no longer holds;
 *  - **the `RESULT` prefix is only used for a definitive result.** A deferred write first emits a
 *    `PENDING` line, so a script looking for `RESULT` cannot mistake the announcement for the
 *    outcome.
 *
 * Reading it from a script:
 * `adb -s <serial> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1`
 */
object BenchLog {

    /** `logcat` tag. Stable: scripts depend on it. Eleven characters, under the limit. */
    const val TAG = "SLEEPWRITER"

    const val RESULT = "RESULT"
    const val PENDING = "PENDING"

    /**
     * Possible statuses. They are ASCII and unaccented because scripts compare them, and distinct
     * from one another because they are not repaired the same way: a missing permission is fixed by
     * the UiAutomator sequence, an absent Health Connect by choosing another emulator image, an
     * invalid parameter by the command itself.
     */
    object Status {
        const val OK = "OK"
        const val NOTHING_TO_WRITE = "NOTHING_TO_WRITE"
        const val PERMISSION_MISSING = "PERMISSION_MISSING"
        const val HC_UNAVAILABLE = "HC_UNAVAILABLE"
        const val BAD_PARAM = "BAD_PARAM"
        const val ERROR = "ERROR"
    }

    /**
     * Emits a line and returns its text, so the caller can show it on screen without rebuilding a
     * second format — the screen and the log must say exactly the same thing, otherwise we debug
     * two truths.
     */
    fun emit(prefix: String, fields: List<Pair<String, Any?>>): String {
        val line = buildString {
            append(prefix)
            for ((key, value) in fields) {
                if (value == null) continue
                append(' ').append(key).append('=').append(sanitise(value.toString()))
            }
        }
        Log.i(TAG, line)
        return line
    }

    /** Spaces would break the splitting; an empty value would break reading a pair. */
    private fun sanitise(value: String): String =
        value.replace(Regex("\\s+"), "_").ifEmpty { "-" }
}
