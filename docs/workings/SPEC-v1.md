# Pendulum — Estimating the PLM Index from an ankle accelerometer (Wear OS)

> ## Historical document. Do not use it as a reference.
>
> **This is the project's initial plan, kept as a trace of the starting point.** It was reviewed
> point by point by [`CRITICAL-REVIEW.md`](CRITICAL-REVIEW.md) — 38 defects, 6 of them blocking — then
> superseded by [`SPEC-v2.md`](SPEC-v2.md) everywhere the two contradict each other. No decision is
> taken by reading it.
>
> **Three things this document says that the project no longer says.** It announces a "**screening**
> indicator": the product screens for nothing, it measures, and
> [`../01-overview.md`](../01-overview.md) §4 explains why the distinction is structural and not
> rhetorical. It makes the hourly count the quantity being tracked: that has changed to the
> fundamental rhythm ([`SPEC-v2.md`](SPEC-v2.md) §5). And it puts figures on a DSP chain — RMS window
> of 0.15 s, spectral content 10–15 Hz — which [`ALGO-v2.md`](ALGO-v2.md) §0 identifies as two
> transposition errors, not as parameters.
>
> What remains true and exists only here: the prior-art search, and the table of assumptions with
> their confidence level at the date it was written.

## Context

**The need.** Detect periodic leg movements during sleep (PLMS) with a Wear OS watch worn on the ankle, cross-reference the result with the sleep stages measured at the wrist, and produce a **screening** indicator oriented towards restless legs syndrome (RLS) / PLMD — over several nights, for personal use.

**Why it is worth doing.** The prior-art search finds **no consumer app and no open source project** doing this. The only precedents are medical and out of reach (SOMNOwatch, 1 862 € incl. tax, sold to labs), dead (Philips' PAM-RL, Actiwatch discontinued at the end of 2022, RestEaZe whose domains no longer resolve), or academic on a single subject (iPhone prototype, N=1).

**Why it is feasible.** Spektor et al. (*Clocks & Sleep* 2024) validate a **unilateral ankle** tri-axial accelerometer against polysomnography: r = 0.98, sensitivity 86.7 % / specificity 92.3 % at the event level, and 85.7 % / 95.2 % for classifying a PLMI ≥ 15/h. That is exactly the configuration being targeted. The published failures (ActiGraph GT3X, "very low similarity with PSG") are due to aggregated proprietary *activity counts* and a sampling rate that is too low — not to a physical limit of the sensor.

**Expected outcome.** A PLMI estimated per night over ≥ 3 nights (ideally 5-7), a Ferri Periodicity Index as a guard rail, a validated RLS questionnaire, and an exportable report that can be presented to a sleep physician.

---

## Assumptions and confidence

| Assumption | Confidence | Note |
|---|---|---|
| Raw 50 Hz accelerometer is reachable for 8 h on a Pixel Watch 3 | **Medium** | Health Services exposes no raw accelerometer → `SensorManager` is the only route (verified fact). The real battery life is documented nowhere: Google gives 8-10 h under continuous GPS as an anchor point. **To be measured in phase 1.** |
| A watch on the ankle is seen as "worn" | **Low** | Off-body detection relies on the PPG/capacitive sensor. Users report deep standby off the wrist. Not documented for the ankle. **To be measured in phase 1.** |
| The hardware FIFO preserves samples across suspend | **Low** | Highly variable across OEMs. Fallback strategy planned (wake lock), with no constraint here since we are not publishing on Play. |
| The AASM/WASM algorithm transposed to the accelerometric envelope yields a useful PLMI | **Medium** | Extrapolation by analogy from S-PLMAD (EMG). The only direct empirical support is Spektor 2024, whose algorithm is proprietary. |

**Decisions recorded:** Pixel Watch 3 on the ankle · sleep stages from the Galaxy Watch 5 on the wrist (Samsung Health → Health Connect) or Sleep as Android · **personal use, sideload only** · hybrid sleep mask · Kotlin · post-processing on waking.

**What sideloading changes:** no MDR (the regulation applies to placing on the market), no Health apps declaration form, no *Battery Technical Quality Enforcement*, wake lock unrestricted. As soon as publication is contemplated, a disease risk score very probably tips the app into a class IIa medical device (MDR Annex VIII rule 11) and disclaimers do not protect — the regulator judges the claimed use, not the legal notice.

---

## Project structure

Four Gradle modules. Guiding rule: **anything that can be tested on the JVM leaves the Android modules.**

```
pendulum/
├─ settings.gradle.kts            :algo :format :wear :phone
├─ gradle/libs.versions.toml      version catalog (greenfield → we modernise)
├─ algo/    kotlin("jvm")         DSP + scoring, zero android.* imports
├─ format/  kotlin("jvm")         binary chunk codec, shared wear/phone
├─ wear/    com.android.application
└─ phone/   com.android.application
```

Packages: `com.pendulum.algo.{dsp,detect,mask,model,synth}` · `com.pendulum.format` · `com.pendulum.wear.{record,transfer,ui}` · `com.pendulum.phone.{ingest,health,db,work,ui,quiz}`.

Versions: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21 (`compose-compiler` plugin), JVM 17, `compileSdk 35`, wear `minSdk 33`, phone `minSdk 30`.

Dependencies: wear → `androidx.wear:wear:1.3.0`, `androidx.wear.compose:compose-material:1.4.1`, `play-services-wearable:19.0.0`, `work-runtime-ktx:2.10.0`, `lifecycle-service:2.8.7` · phone → `androidx.health.connect:connect-client:1.1.0`, `room:2.6.1` + KSP, `work-runtime-ktx`, `play-services-wearable`, `material3:1.3.1`, `navigation-compose:2.8.5` · algo/format → stdlib only, tests `kotlin-test` + `junit5` + `assertj`.

---

## `wear` module — capture

Classes: `RecordingService` (foreground) · `SensorPipeline` · `ChunkWriter` · `SessionStore` · `OffBodyLogger` · `BatteryLogger` · `BootReceiver` · `TransferWorker` · `RecordScreen`.

**Foreground service.** `foregroundServiceType="health"` — Android 15's 6 h/24 h timeout only concerns `dataSync` and `mediaProcessing`, `health` has none. Manifest: `FOREGROUND_SERVICE_HEALTH`, `ACTIVITY_RECOGNITION` (runtime, required by the `health` type), `HIGH_SAMPLING_RATE_SENSORS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`. `START_STICKY`, `onTaskRemoved` stops nothing.

**FIFO strategy, decided at runtime and written into the chunk header:**

```kotlin
val sensor = getDefaultSensor(TYPE_ACCELEROMETER, true) ?: getDefaultSensor(TYPE_ACCELEROMETER)
val fifo   = max(sensor.fifoReservedEventCount, sensor.fifoMaxEventCount)
mode = when {
  sensor.isWakeUpSensor && fifo >= 500  -> BATCHED_WAKEUP(latency = 0.5*fifo/50 s, clamp 10..60 s)
  sensor.isWakeUpSensor && fifo in 1..499 -> BATCHED_WAKELOCK(...) + PARTIAL_WAKE_LOCK
  else                                  -> CONTINUOUS_WAKELOCK(latency = 0) + PARTIAL_WAKE_LOCK
}
```

Auto-degradation: if a gap > 3 s is detected 3 times in 10 min, we take the wake lock back and log it. **Never start an `ExerciseClient`** — documented Samsung trap: it forces the accelerometer to 100 Hz and overrides the requested rate.

**Chunk format** (append-only, self-delimiting blocks, ~1 % overhead → 306 B/s, ≈ 8.8 MB for 8 h):

- 64 B header: `"PENDCHNK"`(8) | u16 formatVersion | u16 nominalRateHz | u32 chunkIndex | 16 B sessionUuid | i64 startWallMs | i64 startElapsedRealtimeNs | i64 firstEventTimestampNs | f32 resolution | f32 maxRange | u16 fifoMaxEventCount | u16 modeFlags | reserved.
- Block (N ≤ 512): `"BLK!"`(4) | u16 N | i64 tFirstNs | i64 tLastNs | u16 flags (bit0 FIFO flush boundary, bit1 suspected gap, bit2 off-body) | u16 crc16 | 6 B reserved | N × {i16 x, i16 y, i16 z} little-endian, unit 1/2048 g (exactly ±16 g).
- Per-sample timestamps = linear interpolation tFirst→tLast (valid: the FIFO samples uniformly).

Rotation every 30 min or 8 MB. `flush()` per block, `fd.sync()` every 10 s. A block with an invalid CRC is skipped on reading — never the whole night. A single 8 h file would be an unacceptable single point of failure.

**Recovery after crash/reboot.** `active_session.json` (uuid, startWallMs, last chunkIndex, mode); `BootReceiver` + `ACTION_MY_PACKAGE_REPLACED` restart the service if the marker exists and `now < startWallMs + 14 h`, with `bit1 gap` on the first block. One `sidecar.json` per session accumulates the metrics (gaps, battery, off-body, measured fs).

**UI**: a single static `RecordScreen` — full-screen START/STOP, duration, number of samples, MB written, battery %, gap counter, active FIFO mode. 30 s poll, no animation (recompositions wake the SoC).

---

## Watch → phone transfer

`ChannelClient` from the Wearable Data Layer (`MessageClient` caps at 100 KB). Bluetooth only — no public API to force WiFi. Trigger: `TransferWorker` under `requiresCharging` constraints + a reachable node (`CapabilityClient`), plus a "Sync now" button.

Four-step idempotent protocol: (1) wear sends `/pendulum/manifest` {sessionId, chunks:[{idx,size,crc32}], sidecar} → (2) phone replies `/pendulum/need` with the missing or corrupt indices → (3) one channel per chunk, `openChannel` + `sendFile` → (4) phone checks size + CRC32, inserts the `chunk` row, replies `/pendulum/ack`; **the file is only deleted on the watch after the ack**. Watch disk quota 200 MB, FIFO purge of acked sessions.

---

## `phone` module — ingestion, Health Connect, analysis

**Health Connect.** `android.permission.health.READ_SLEEP` permission + mandatory rationale activity (intent-filter `ACTION_SHOW_PERMISSIONS_RATIONALE` + `<meta-data health-permissions>`), otherwise Health Connect refuses to display the app. `SleepReader.read(startMs-2h, endMs+2h)` → `SleepSessionRecord` + stages (`STAGE_TYPE_` AWAKE, AWAKE_IN_BED, SLEEPING, OUT_OF_BED, LIGHT, DEEP, REM), deduplicated by `dataOrigin` with the preferred source as a setting.

**The late-sync trap.** The session only reaches Health Connect **after** the watch's Bluetooth sync to Samsung Health or Sleep as Android — so not on waking. `SleepFetchWorker` scheduled at T+30 min then backoff 1 h/2 h/4 h/8 h, giving up at T+36 h; success = a session covering ≥ 50 % of the window. **The night remains immediately analysable** thanks to the accelerometer mask; `RescoreWorker` adds the second PLMI when HC answers.

WorkManager chain: `IngestWorker` (assembly + continuity) → `AnalyzeWorker` → `SleepFetchWorker` → `RescoreWorker`.

**Room, 7 tables**: `night_session`(startWallMs, endWallMs, fsMeasured, sampleCount, gapCount, gapTotalMs, batteryStart/End, offBodyOnPct, mode, transferState, algoVersion) · `chunk`(sessionId, idx, path, size, crc32, tFirstNs, tLastNs, valid) · `sleep_window`(sessionId, source `ACCEL_MACRO|HEALTH_CONNECT`, stage, startMs, endMs) · `clm_event`(onsetMs, durationMs, peakAmp, noiseFloor, threshold, postural, inSeriesAasm, inSeriesWasm, stageAtOnset, duringWake) · `plm_result`(ruleSet `AASM_V3|WASM_2016`, maskSource, tstMin, plmsCount, plmi, plmw, periodicityIndexFerri, plmiFirstHalf, plmiSecondHalf, paramsJson) — **4 rows per night** (2 rule sets × 2 masks) · `questionnaire_response` · `param_profile`.

**Screens**: night list (PLMI-A/PLMI-B badges, TST, quality flags) · detail (RMS envelope + adaptive threshold plotted, CLM markers, series bands, hypnogram, Ferri's PI, parameter sliders + recompute) · multi-night trend (5/10/15 lines, blocking message under 3 nights) · questionnaire · settings + CSV export · **mandatory warning on first launch**.

---

## `algo` module — pure functions

```kotlin
class Biquad(coeffs)                                          // stateful, streaming
fun highpassButter2(fs: Double, fc: Double = 0.3): Biquad     // on X, Y, Z SEPARATELY
fun magnitudeL2(x, y, z): FloatArray                          // invariant to strap rotation
fun rmsEnvelope(sig, fs, winSec = 0.15): FloatArray
fun noiseFloor(env, fs, winSec = 25.0): FloatArray            // sliding median, iterative exclusion
fun detectClm(env, floor, fs, cfg): List<Clm>                 // onset 8×, offset 2×, duration 0.5..10 s, merge < 0.5 s
fun tagPostural(clms, gravityLowpass): List<Clm>
fun buildSeries(clms, rule: SeriesRule): List<PlmSeries>      // AASM_V3(IMI 5..90s) | WASM_2016(10..90s), ≥4 CLM
fun periodicityIndexFerri(clms): Double
fun deriveImmobilityMask(env, fs, cfg): List<SleepWindow>     // 30 s epochs, Cole-Kripke type rule
fun mergeMasks(accel, hc: List<SleepWindow>?): SleepMask
fun computePlmi(series, mask): PlmiResult                     // PLMS / hours of SLEEP
```

No wall clock, no I/O, `fs` always explicit.

**Clinical points to be respected literally.** CLM duration **0.5–10 s** (the 0.5–5 s bound is AASM v1 from 2007, obsolete since v2.0 in 2012 — many reviews still cite it wrongly). Onset-to-onset interval **[5 s, 90 s]** in AASM v3, **[10 s, 90 s]** in WASM 2016: implement both and report which one is active, the difference in result is major. Series of **≥ 4** CLM. **Adaptive** threshold pinned to the noise floor, never fixed in g: that is the lesson of Yang et al. 2013 (the PAM-RL default parameters severely under-detect). Interpretation threshold PLMI > 15/h in adults (ICSD-3), but the PLMI alone is never sufficient for a diagnosis.

**Questionnaires.** "Single Question for Rapid Screening of RLS" (100 % sens / 96.8 % spec) as the entry filter, then Cambridge-Hopkins CH-RLSq (87.2 % / 94.4 %, PDF freely available at the Cambridge repository). **Exclude the IRLS severity scale**: under IRLSSG copyright.

**Limits to display in the app.** Without a respiratory channel, it is impossible to exclude *respiratory-related leg movements* → PLMI structurally **overestimated** in the case of sleep apnoea (up to 42/h of RRLM depending on the rule). The AASM has a **strong** recommendation against actigraphy as a replacement for EMG to diagnose PLMD (Smith et al., *JCSM* 2018). An ankle sensor cannot diagnose RLS — the diagnosis is purely clinical (5 IRLSSG criteria, symptoms while awake) and PLMS are only a **supporting** criterion.

---

## Verification — validation without polysomnography

This is the heart of the problem: there is no ground truth. Six levels, from the most controlled to the most ecological.

1. **Synthetic signal with injected ground truth** (`algo/src/test`) — background noise extracted from a real quiet night + synthetic CLM (10-15 Hz Gaussian-windowed packets, durations 0.5-10 s, amplitudes 1.5× to 30× the floor), series with known intervals, plus distractors: isolated movements, non-periodic clusters, gravity steps, FIFO gaps, clipping. Metrics: event-level sens/spec/F1, absolute PLMI error, PI error. Thresholds as regression assertions.
2. **Protocol of timed voluntary movements** — a "tap-logger" screen on the phone; 5 series of 4 dorsiflexions of 1 s spaced 20 s apart, then 10 min of complete immobility. Real ground truth **with the real strap-and-ankle mechanics**. Requires 0 false positives over the 10 quiet minutes.
3. **Negative controls** — watch placed on the bedside table (PLMI ≈ 0 expected), watch on the wrist (a different signature expected).
4. **Parametric robustness** — replay a night at ±20 % on each parameter. **If the screening decision flips at ±10 %, the figure is not usable.**
5. **Consistency** — split-half (1st vs 2nd half of the night), between-night ICC over 5-7 nights, kappa of the accelerometer mask vs the HC hypnogram, distribution of CLM by stage (biological plausibility: concentration expected in N1/N2).
6. **Golden files** — 5 min of real signal in resources + expected output JSON; 50 → 25 Hz decimation test (the PLMI must stay stable).

---

## Phases and objective criteria

- **P0** (0.5 d) — 4-module skeleton, sideload wear + phone. *Criterion: the 2 APKs install and display their screen.*
- **P1 — BLOCKING** (3-5 nights) — sensor-only spike: 50 Hz accelerometer + chunks + off-body log + battery/60 s + gap detector. **Not one line of algorithm before validation.** *Criteria: (a) ≥ 97 % of the 8 h × 50 Hz received, largest gap < 5 s, cumulative < 2 min; (b) battery > 20 % after 8 h; (c) off-body "worn" > 95 % of the time on the ankle; (d) reproducible over 3 nights.* Escalation on failure: batched → wake lock → 25 Hz (Nyquist remains amply sufficient for events of 0.5-10 s).
- **P2** — format + robustness. *Criterion: `kill` the process in the middle of the night → session resumed, all chunks CRC-valid, JVM re-read with ≤ 1 chunk lost.*
- **P3** — transfer. *Criterion: 8.8 MB CRC-verified in < 5 min with a Bluetooth cut in the middle and resumption; deletion only after the ack.*
- **P4** — algorithm in TDD on synthetic data. *Criterion: F1 ≥ 0.90 at SNR ≥ 4, 0 false positives over 10 min of noise, PLMI error < 10 %.*
- **P5** — hybrid mask + Health Connect + Room + WorkManager. *Criterion: a real night produces the 4 `plm_result`, kappa reported, HC retry proven (watch in airplane mode then resync).*
- **P6** — phone UI + questionnaires + disclaimers + CSV export. *Criterion: cold run-through night → charger → result displayed, with no manual intervention.*
- **P7** — 7-night campaign + voluntary-movement protocol. *Criterion: trend report, ICC, parametric sensitivity curve.*

---

## Anticipated traps

1. **`SensorEvent.timestamp` is not guaranteed to equal `elapsedRealtimeNanos`** — some OEMs exclude suspend time. Anchor a triplet (event.timestamp, elapsedRealtimeNanos, currentTimeMillis) on every chunk to detect the drift, otherwise the merge with the hypnogram is off by several minutes.
2. **Real fs ≠ requested fs** (50 → 50.3 or 52.6 Hz) — compute it from the timestamps; a wrong fs shifts the filters and the CLM durations.
3. **High-pass transient** — filtering block by block without preserving the biquad state creates a periodic artefact every N values. Stateful streaming implementation mandatory.
4. **Posture change = gravity step** → residual burst of 1-2 s indistinguishable from a CLM. Tag `postural` via a lasting jump of the low-passed gravity vector within ±2 s, excluded by default.
5. **Self-contaminated noise floor** — if the sliding window already contains the series, the threshold rises and cuts at the 3rd CLM → series < 4 → PLMI collapsed. MAD with iterative exclusion, or a lagged causal window.
6. **Merge rule for close CLM** (< 0.5 s = a single movement) — forgetting it doubles the count.
7. **PLMW vs PLMS** — do not count movements during wakefulness in the PLMI; report them separately (a high PLMW is in itself an indication of RLS).
8. **Unilateral measurement** — downward bias (opposite leg missed) which opposes the upward RRLM bias. Do not claim that they cancel out.
9. **45 mm strap on an ankle** — mechanical play, amplitudes ×2-3, resonances. Keep the same strap and the same tightness between nights, otherwise the adaptive threshold changes meaning. The bedding also amplifies the signal (×2-2.3 measured on other technologies).
10. **Daylight saving / time zone** — store UTC epoch + offset; the night of the clock change duplicates or removes an hour.
11. **Auto-stop** — watch put back on the charger on waking without a stop → the recording pollutes the night. Cut off on charging detection or a time limit.
12. **Doze on the phone side** — `requiresCharging` Workers may never run if the phone is not charged. Plan for `setExpedited` + a guaranteed manual trigger.
13. **Between-night variability** — in confirmed RLS patients, the 15/h threshold is exceeded on only ~34 % of individual nights (52 % for 10/h, 70 % for 5/h). Over 5 nights, the probability of exceeding it at least once rises to 63 %. A single night means **nothing** — the UI must refuse it, not merely warn about it.

---

## Critical files

- `algo/src/main/kotlin/com/pendulum/algo/detect/PlmDetector.kt` — AASM/WASM state machine
- `algo/src/main/kotlin/com/pendulum/algo/dsp/Envelope.kt` — high-pass, magnitude, RMS, adaptive floor
- `algo/src/test/kotlin/com/pendulum/algo/synth/SyntheticNight.kt` — ground truth generator
- `format/src/main/kotlin/com/pendulum/format/ChunkCodec.kt` — shared binary codec
- `wear/src/main/kotlin/com/pendulum/wear/record/RecordingService.kt` — foreground service + FIFO
- `phone/src/main/kotlin/com/pendulum/phone/health/SleepReader.kt` — Health Connect + retry
- `phone/src/main/kotlin/com/pendulum/phone/work/AnalyzeWorker.kt` — orchestration
- `gradle/libs.versions.toml`

## Build constraints (global rules)

Builds delegated via `delegate-android-build` / `run-remote` — Lenovo host first, Mac as fallback, local only if both are unreachable. Prefix heavy builds with `flock /tmp/build.lock`. Gradle outputs on the D drive. Set `note add "WIP: Pendulum <phase>"` at the root when starting, `note rm` at the end of a phase.

---

## Credible alternative set aside

**A dedicated sensor rather than the watch** — Axivity AX3 or GENEActiv (~250-400 €, 100 Hz, weeks of battery life, validated in research for ankle actigraphy). Advantages: no battery-life problem, no off-body problem, no strap play, raw data guaranteed without gaps. Disadvantages: hardware to buy, no real time, no automatic cross-referencing with Health Connect, and above all no app to write — so not the project that was asked for. **To be seriously reconsidered if phase 1 fails** on battery life or off-body, rather than persisting with the watch.

## Check this yourself

- **Phase 1 is a real go/no-go, not a formality**: if the Pixel Watch 3 drops to 5 % after 6 h or goes into deep standby on the ankle, the whole rest of the plan is dead. Do not code the algorithm before that.
- **US patent 10 335 085** "Device and method for detection of periodic leg movements" explicitly mentions the accelerometer of a device held on the leg by a strap. Justia returned a 403 to me: holder, claims and maintenance status **not verified**. Of no consequence for strictly personal use, but to be read before any distribution.
- **The FIFO's behaviour across suspend on your specific watch** — read `fifoMaxEventCount` and `isWakeUpSensor` at runtime before choosing the strategy; my thresholds (500 events) are an engineering choice, not a sourced value.
- **The licence of the Cambridge-Hopkins CH-RLSq** before integrating it, even for personal use — and confirm that the IRLS stays excluded.
- **That the Galaxy Watch 5 does write *stages* into Health Connect**, not just the total duration: it is documented for Samsung Health, but check it on your device before building the cross-referencing on top of it.
