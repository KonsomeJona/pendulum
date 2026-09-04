# `:sleepwriter` — the bench's hypnogram writer

A tiny APK, **debug only, never published**, whose sole role is to write `SleepSessionRecord`s into
Health Connect so that the bench can exercise the "denominator" half of the measurement.

## Why it exists

Pendulum reads a sleep session written by an **independent third-party application** — ring, wrist
watch, under-mattress sensor. The product rests on that data coming from a sensor other than its
own: if both came from the same accelerometer, a processing step that removes movement would lower
the numerator and raise the denominator in the same stroke (`docs/01-overview.md` §1).

Pendulum therefore **cannot** write what it reads, and must never be able to: it only declares
`READ_SLEEP`, `READ_HEALTH_DATA_IN_BACKGROUND` and `READ_HEALTH_DATA_HISTORY`, and the absence of a
write permission is a design argument verifiable with `aapt dump permissions`. This module is not a
way around that rule, it is its faithful counterpart: another application, another package, another
origin.

**It is never published.** Its release variant is disabled in `build.gradle.kts`
(`androidComponents { beforeVariants... }`): `./gradlew assembleRelease` at the root produces no
artefact here, and forgetting is mechanically impossible rather than merely discouraged.

## Two packages, because `E-HC-03` cannot be simulated otherwise

Health Connect sets a record's `dataOrigin` from the **package that writes it**. The "two
conflicting sources" scenario, which `phone/.../health/SleepSourceSelector.kt` knows how to
arbitrate, therefore requires two genuinely distinct APKs.

| Variant | `applicationId` | Gradle task |
|---|---|---|
| `sourceA` | `com.pendulum.sleepwriter.a` | `:sleepwriter:assembleSourceADebug` |
| `sourceB` | `com.pendulum.sleepwriter.b` | `:sleepwriter:assembleSourceBDebug` |

Same code, same manifest, same debug signature. The base `applicationId`
(`com.pendulum.sleepwriter`) is deliberately different from that of `:phone` and `:wear`
(`com.pendulum`, shared between them because the Wearable Data Layer only exchanges between
applications with the same identifier and the same signature).

---

## Build and install

```bash
./gradlew :sleepwriter:assembleSourceADebug :sleepwriter:assembleSourceBDebug

adb -s <serial> install -r sleepwriter/build/outputs/apk/sourceA/debug/sleepwriter-sourceA-debug.apk
adb -s <serial> install -r sleepwriter/build/outputs/apk/sourceB/debug/sleepwriter-sourceB-debug.apk
```

Check that the two really carry distinct packages — an `applicationId` collision shows up as a
silent replacement, not as an error:

```bash
aapt dump badging sleepwriter/build/outputs/apk/sourceA/debug/sleepwriter-sourceA-debug.apk | head -1
aapt dump badging sleepwriter/build/outputs/apk/sourceB/debug/sleepwriter-sourceB-debug.apk | head -1
```

---

## Granting `WRITE_SLEEP` — the sequence, and why it is not a `pm grant`

`pm grant` **does not work** for health permissions: they are managed by a Mainline module with its
own consent interface. You must therefore go through the Health Connect screen, which can be driven
deterministically over `adb`.

**1. First ask whether it is already done.** On an emulator reloaded from a snapshot the permission
is already granted and the whole interface sequence can be skipped:

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity --es mode permissions
adb -s <serial> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
# RESULT mode=permissions status=OK ... sdk=AVAILABLE write=GRANTED read=GRANTED
```

**2. Otherwise, open the consent dialog** (the application's button triggers it; it can also be
triggered by a `mode write`, which will return `status=PERMISSION_MISSING` and then nothing):

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity
adb -s <serial> shell input tap ...   # see step 3: do not tap blind
```

**3. Find the button by its text, never by hard-coded coordinates.** The Health Connect window
changes layout depending on the module version and the screen density, and a tap that misses
**produces no error** — it produces a failure further on, for an unrelated reason.

```bash
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> pull /sdcard/ui.xml /tmp/ui.xml
# in /tmp/ui.xml, look for the node whose text is "Allow all" / "Allow" / "Autoriser tout",
# read its bounds="[x1,y1][x2,y2]", tap the centre:
adb -s <serial> shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
```

**4. Check what you did**, on screen and in the log:

```bash
adb -s <serial> exec-out screencap -p > /tmp/screen.png
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity --es mode permissions
adb -s <serial> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
```

To be redone for `com.pendulum.sleepwriter.b`: each of the two packages has its own consent.

---

## Driving it

General form — **the extras come after the component**:

```bash
adb -s <serial> shell am start -n <package>/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode <write|verify|permissions|purge> \
  --es scenario <full-night|duration-only|none|conflict> \
  --el startMs <epoch ms> --el endMs <epoch ms> \
  --el delayMs <ms> --el marginMin <minutes> \
  --ez finish <true|false>
```

### The trap in the typing of the extras

`am start` types the extras by the flag and not by the value:

| Flag | Type | To be used for |
|---|---|---|
| `--es` | string | `mode`, `scenario` |
| `--ei` | 32-bit integer | **never here** |
| `--el` | 64-bit long | `startMs`, `endMs`, `delayMs`, `marginMin` |
| `--ez` | boolean | `finish` |

An epoch instant in milliseconds **exceeds 2^31**: passed as `--ei`, it makes the command fail;
passed as `--es`, it arrives but in the wrong type. The activity defensively reads the long then
falls back on the string, but better to write `--el` in the first place.

No value contains a space, so no escaping is needed in `adb shell`.

### The defaults

Without `startMs` or `endMs`, the window is **the last eight hours**: always in the past, therefore
always acceptable to Health Connect, and enough for a smoke test. The default scenario is
`full-night`, the read-back margin 180 minutes, and the activity finishes on its own.

Building a precise window:

```bash
END=$(( $(date -d '07:00' +%s) * 1000 ))          # this morning at 7 am
START=$(( END - 8 * 3600 * 1000 ))                # eight hours earlier
```

### The window written is not the denominator

Pendulum clips every external sleep window to the span it actually recorded
(`NightAnalyzer.clipToSignal`): sleep that continues past the last sample contributes nothing to the
numerator, so letting it into `tstMin` would divide the published index by up to two. A run that
replays twenty minutes of signal under an eight-hour session therefore reports twenty minutes of
sleep, and that is the right answer rather than a fault in the writer. A session that does not
overlap the recorded span at all leaves the night with no Health Connect mask, exactly as if nothing
had been written. Match the window to the signal being replayed whenever the run is about the index
rather than about the plumbing.

---

## The four scenarios

### 1. Full night with stages

Cycles of about 90 minutes, deep sleep concentrated in the first half of the night and decreasing,
REM increasing in the second, end-of-cycle micro-wakings, sleep-onset latency and a final waking.
The stages cover the window **with no hole**: the hole policy changes the denominator, so the index,
and deserves a scenario of its own rather than being mixed into the nominal case. The invariants
(never waking -> deep, never deep -> REM) are checked by `HypnogramTest`.

Health Connect does not know N1/N2/N3: N1 and N2 fall into `LIGHT`, N3 into `DEEP`. No point looking
for an N2 in the data read back.

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode write --es scenario full-night --el startMs $START --el endMs $END
```

### 2. Duration only, without stages

What many consumer devices actually write. Pendulum then computes a total sleep time but cannot
break it down by stage, and it must **say so**: `SleepSourceSelector` returns the `STAGES_MISSING`
verdict.

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode write --es scenario duration-only --el startMs $START --el endMs $END
```

### 3. Nothing at all — `E-HC-01`

Scenario by omission: there is nothing to write. It exists so that the bench script stays symmetric
and so that the trace attests that this emptiness was intended rather than suffered.

```bash
# first purge whatever a previous scenario may have left (only deletes our own records: Health
# Connect does not allow erasing those of another application)
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode purge --el startMs $START --el endMs $END
adb -s <serial> shell am start -n com.pendulum.sleepwriter.b/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode purge --el startMs $START --el endMs $END

adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode write --es scenario none --el startMs $START --el endMs $END
```

### 4. Two conflicting sources — `E-HC-03`

The same command on both packages. Variant A writes the full night with its stages; variant B a
duration-only session, shifted 45 minutes at the start and shortened by 30 at the end. The durations
differ **on purpose**: two identical sources would not prove which one was retained.
`SleepSourceSelector` must pick A through the "more distinct stage types" criterion.

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode write --es scenario conflict --el startMs $START --el endMs $END
adb -s <serial> shell am start -n com.pendulum.sleepwriter.b/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode write --es scenario conflict --el startMs $START --el endMs $END
```

### The hypnogram that arrives after waking

This is the **normal** case, not a failure: the wrist watch does not transfer its night on waking
but when its battery policy decides to. `delayMs` is a wait **in real time** before the write — the
recorded window stays that of the night. The bench compresses six hours into a few seconds.

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode write --es scenario full-night --el startMs $START --el endMs $END --el delayMs 30000
# a PENDING line goes out immediately, the RESULT line thirty seconds later
```

The wait lives in the activity's process: beyond a few tens of seconds, it is better to launch the
command later from the script than to lengthen `delayMs`.

---

## Reading the result

One single line per action, a stable tag, no space in any value.

```bash
adb -s <serial> logcat -c                     # before the action
adb -s <serial> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
```

```
RESULT mode=write status=OK scenario=full-night source=A pkg=com.pendulum.sleepwriter.a
  startMs=1754000000000 endMs=1754028800000 start=2025-07-31T21:33:20Z end=2025-08-01T05:33:20Z
  durationMin=480 records=1 stages=34 writtenStartMs=... writtenEndMs=... writtenDurationMin=480 ids=<uuid>
```

(the real line fits on a single line; it is broken up here for readability)

| Prefix | Meaning |
|---|---|
| `RESULT` | definitive result — this is what a script waits for |
| `PENDING` | announcement of a deferred write, **never** an outcome |

| `status` | What to do |
|---|---|
| `OK` | nothing |
| `NOTHING_TO_WRITE` | expected for the `none` scenario; in `verify`, means no session was found |
| `PERMISSION_MISSING` | replay the consent sequence above |
| `HC_UNAVAILABLE` | Health Connect absent or too old on this emulator image |
| `BAD_PARAM` | `endMs <= startMs` |
| `ERROR` | the `err` field carries the exception type and message |

### The `verify` mode, which is the bench's oracle

It reads back **every** source in the window, not only its own: reading back only its own records
would be enough to say "the write succeeded", not to say what Pendulum is going to see.

```bash
adb -s <serial> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.SleepWriterActivity \
  --es mode verify --el startMs $START --el endMs $END --el marginMin 180
adb -s <serial> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
```

```
RESULT mode=verify status=OK ... records=2 stages=34 sumDurationMin=915 mine=1
  origins=com.pendulum.sleepwriter.a:1:34;com.pendulum.sleepwriter.b:1:0
```

`origins` carries `package:records:stages` per source, separated by `;`. That is exactly the input
of `SleepSourceSelector`, and it is what lets you check that a conflict scenario really produced
**two** origins and not a single one written twice.

---

## What this module does not do

- **It does not generate an accelerometer signal.** The hypnogram is independent of the signal; the
  night generator in `algo/synth/` is not a dependency here, and must not become one.
- **It proves nothing about the sensor or about energy.** See the honest boundary of the bench
  plan: this bench validates the distributed orchestration and the algorithmic correctness against
  synchronisation edge cases, neither the power consumption, nor the sensor, nor the behaviour of
  the system under constraint.
- **It does not write into the future.** A session whose end goes beyond the current time is
  flagged by a `warn=window-in-the-future` field and the write is attempted anyway: hiding Health
  Connect's real error behind a home-made refusal would make diagnosis harder.
