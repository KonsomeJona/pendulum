# Pendulum — Adversarial critical review of SPEC-v1

Scope: [`SPEC-v1.md`](SPEC-v1.md), `format/src/main/kotlin/com/pendulum/format/ChunkFormat.kt`, `ChunkCodec.kt`, `format/src/test/kotlin/com/pendulum/format/ChunkCodecTest.kt`.
Stance: I am trying to make the spec fail, not to validate it. What follows assumes that the real objective is **a figure that can be defended in front of a sleep physician**, not an app that runs.

**This document is dated and it keeps its value as such: it is the log of the 38 defects, not of
their progress.** What it triggered is recorded elsewhere — the decisions in
[`SPEC-v2.md`](SPEC-v2.md), the corrected chain in [`ALGO-v2.md`](ALGO-v2.md), and the honest state of
the test in [`../07-validation.md`](../07-validation.md), which takes up its central argument: two
estimators drawn from the same movement do not validate each other (F-20).

Method note: the `format` module has plainly **never been compiled** (its dependencies are not in the local Gradle cache, the KSP plugin is not resolvable offline). Defects F-01 and following therefore come from a byte-by-byte reading, not from an execution. F-01 must be confirmed by a `./gradlew :format:test`.

---

## 1. Table of defects

| # | Severity | Section | Defect |
|---|---|---|---|
| F-01 | BLOCKING | `ChunkFormat.toRaw` | Does not compile: `Long.coerceIn(Int, Int)` has no overload. The whole module is dead. |
| F-02 | BLOCKING | `ChunkCodec` block | The CRC covers only the payload. `count`, `tFirstNs`, `tLastNs`, `flags` are protected by nothing. |
| F-03 | BLOCKING | Format §chunk + `DecodedBlock.timestampNs` | The linear interpolation is wrong as soon as a block straddles two FIFO flushes; nothing prevents that case. |
| F-04 | BLOCKING | `computePlmi` + `deriveImmobilityMask` | Circular denominator: the sleep time is derived from the signal whose movements are being counted. |
| F-05 | BLOCKING | Room `plm_result` (4 rows/night) + UI sliders | Four competing figures, no pre-registered primary criterion, parameters modifiable after the fact. |
| F-06 | BLOCKING | Wear §auto-degradation + P1 | Gap detection is not defined (arrival vs `SensorEvent.timestamp`) → in batched mode it always fires, the wake lock is always taken, P1 tests something other than what it believes. |
| F-07 | MAJOR | Spec §format vs `HEADER_SIZE` | Spec: 64 B header. Code and test: 80 B. The spec's own field list already sums to 68 B excluding the reserved area → 64 is arithmetically impossible. |
| F-08 | MAJOR | File header | The header is protected by no CRC. A corrupted byte in `startWallMs` dates the whole night wrongly, with no detection. |
| F-09 | MAJOR | Header + Room `night_session` | No time zone / UTC offset field despite trap no. 10; nothing forbids computing durations by a difference of wall clocks. |
| F-10 | MAJOR | `MODE_DEGRADED` | A header flag in an append-only format: impossible to write when the auto-degradation happens in the middle of a chunk (up to 30 min labelled with a wrong mode). |
| F-11 | MAJOR | `toRaw` | Silent clipping at ±16 g, with no flag and no counter. The "clipping" distractor of §Verification is undetectable on re-reading. |
| F-12 | MAJOR | `ChunkFile.truncatedTail` | A benign truncated tail and a desynchronisation in the middle of the file produce the same state; `corruptBlocks = 1` can mask thousands of lost blocks. |
| F-13 | MAJOR | Transfer §protocol | Nothing forbids putting the chunk **currently being written** into the manifest: the phone acks a partial file, the watch deletes it. |
| F-14 | MAJOR | `ChunkWriter.writeBlock` / `ChunkReader` | No temporal validation: `tLast < tFirst` accepted (no exception), `n=100, tFirst=0, tLast=1` accepted — the tests themselves lock in this absurd behaviour. |
| F-15 | MAJOR | `buildSeries` / WASM rules | The WASM 2016 grouping rule is not implemented: only the IMI window changes. The "two rule sets" are not two. |
| F-16 | MAJOR | `plm_result.plmw` | PLMW stored without a denominator, and without `AWAKE_IN_BED` it includes getting up and walking → a wrong and alarming figure. |
| F-17 | MAJOR | `deriveImmobilityMask` | Cole-Kripke is calibrated on the **wrist**, on 1 min epochs of *activity counts*. Transposed to the ankle on 30 s RMS, its coefficients no longer mean anything. |
| F-18 | MAJOR | FIFO strategy | `max(fifoReservedEventCount, fifoMaxEventCount)` takes the optimistic one: `fifoMax` is shared between all apps, `fifoReserved` is the only guaranteed one. |
| F-19 | MAJOR | Latency formula | `0.5*fifo/50` then `clamp 10..60 s`: at fifo = 500 the clamp raises the latency back to 10 s = 500 events = 100 % of the FIFO, cancelling the 0.5 margin exactly at the branch's threshold. |
| F-20 | MAJOR | Verification §5 | "Between-night ICC" on N=1 is not an ICC; "kappa of the accelerometer mask vs HC" compares two estimators derived from the same movement. Two reassuring and empty figures. |
| F-21 | MAJOR | P4 | Circular validation (synthetic data generated from the detector's own assumptions) + "0 FP over 10 min" bounds nothing (compatible with ~144 FP/night at 95 %). |
| F-22 | MAJOR | P1 criterion (a) | "≥ 97 %" and "cumulative < 2 min" (= 99.6 %) are inconsistent; "largest gap < 5 s" is incompatible with a batch latency of up to 60 s. |
| F-23 | MAJOR | P2 criterion | "≤ 1 chunk lost" = up to 30 min, whereas the whole justification of the format is to bound the loss to one block (10 s). |
| F-24 | MAJOR | Trend UI + §clinical | The 15/h threshold is a PLMD criterion, but PLMD **gives way** to RLS. In this subject, comparing against 15/h has no diagnostic meaning. |
| F-25 | MAJOR | Context / Expected outcome | The subject is **already treated**: without an untreated baseline, no "effect of the treatment" is measurable. The spec says this nowhere. |
| F-26 | MAJOR | Header | `fifoMaxEventCount.toShort()` / `nominalRateHz.toShort()` truncate to u16 with no `require`. A FIFO > 65535 is recorded modulo 65536. |
| F-27 | MAJOR | `ChunkReader.read` | Materialises the whole night as a `List<DecodedBlock>` (~17 MB of useful data + ~8400 arrays) whereas the algorithm is explicitly streaming. |
| F-28 | MAJOR | Durability | `out.flush()` on a `FileOutputStream` is a no-op; the writer has no access to the `FileDescriptor`. The comment "bounds the loss to a single block" is not guaranteed by this code. |
| F-29 | MAJOR | Table of assumptions | The "Medium" confidence granted to the transfer of validity from Spektor 2024 is overestimated (dedicated sensor, proprietary algorithm, enriched cohort, between-subject r ≠ event-level accuracy). |
| F-30 | MAJOR | P1 criterion (c) | "off-body worn > 95 %" is not falsifiable: `TYPE_LOW_LATENCY_OFFBODY_DETECT` may be absent, and its state has no bearing on the service's survival. |
| F-31 | MINOR | `ChunkReader.readFully` | Infinite loop if `read()` returns 0 without EOF (a real case as soon as a Data Layer `Channel` is plugged in). |
| F-32 | MINOR | Header | Version rejected strictly, and no `headerSize` field: impossible to evolve without breaking the re-reading of the archives. |
| F-33 | MINOR | Spec §format | "exactly ±16 g" is wrong by one LSB on the positive side (32767/2048 = 15.9995 g). |
| F-34 | MINOR | CRC16 | 1/65536 non-detection per block × ~2800 blocks/night ≈ 4 % chance of letting a corrupted block through **if** corruption is frequent. Inconsistent with the CRC32 of the transfer. |
| F-35 | MINOR | `ChunkFile` | The corrupted blocks are not located (offset, time range): impossible to turn "3 blocks lost" into "30 s missing at 3:12". |
| F-36 | MINOR | Spec §throughput | "306 B/s": the real computation gives 303.1 B/s (300 × 1.0104). Of no consequence, but the figure was put down without being verified. |
| F-37 | MINOR | Format | No end-of-file marker: impossible to distinguish "complete chunk" from "chunk in progress". Root cause of F-13. |
| F-38 | MINOR | Data model | No definition of "the night of DD/MM" and no handling of naps: two sessions on the same day break the trend. |

### Detail of the defects that require code

**F-01 — does not compile.**
`Math.round(ms2 / G_IN_MS2 * LSB_PER_G)`: `Float / Double → Double`, so `Math.round(Double) → Long`. Then `lsb.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())`: neither the `Long.coerceIn(Long, Long)` overload nor the generic `<T : Comparable<T>>` unifies with (Long, Int, Int) — Kotlin does not widen Int to Long implicitly. Fix:

```kotlin
fun toRaw(ms2: Float): Short {
    if (!ms2.isFinite()) return 0                    // otherwise Math.round(NaN) = 0 silently
    val lsb = Math.round(ms2 / G_IN_MS2 * LSB_PER_G) // Long
    return lsb.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
}
```
Corollary: a `NaN` returned by the sensor becomes a perfectly plausible 0 (free fall) without any trace. To be counted and flagged.

**F-02 — the CRC protects the wrong field.**
The 6 bytes of a sample are locally redundant (near-identical neighbours); a corruption there is benign. `tFirstNs`/`tLastNs` are unique and non-redundant: corrupting them shifts the whole time base of the night, hence the merge with the hypnogram, hence the attribution of CLM to stages — **with no detection at all**. Worse, corrupting `count` falsifies the payload length that is read: the CRC fails, the code does a `continue` while commenting "we keep the sync", when that is exactly the case where the sync is lost (a shift of `(count_true − count_read) × 6` bytes). Fix:
- CRC computed over `blockHeader[0..24)` ‖ payload, the `crc` field being written last (offset 24);
- on CRC failure, **resynchronise** by looking for the next `BLK!` instead of a blind `continue`, and count the bytes skipped;
- a dedicated test: corrupt `count`, corrupt `tLastNs`, check that both are rejected (no current test corrupts the block header — the gap is visible in `ChunkCodecTest`).

**F-03 — the interpolation is wrong across a flush.**
`ChunkFormat` states that "the hardware FIFO samples uniformly". True **inside** a batch, false **between** two batches separated by a suspend. But `MAX_SAMPLES_PER_BLOCK = 512` = 10.24 s at 50 Hz, for a batch latency of 10 to 60 s: a block can therefore contain the end of one batch and the beginning of the next, with a gap in the middle. `timestampNs(i)` then spreads the interval linearly: **all** the block's samples are dated wrongly, the error possibly reaching several seconds, and `FLAG_GAP_BEFORE` marks only the block boundary, not the internal one. Fix:
- writer contract: `require` that a block contain a single batch only; the producer cuts the block at every flush boundary **and** at every `SensorEvent.timestamp` delta > 1.5 / fs;
- reader contract: reject/flag `SUSPECT_TIMEBASE` if `|(tLast − tFirst)/(N−1) − 1e9/fs| > 20 %`;
- test: a block containing a 3 s gap → must be refused at write time.

**F-06 — gap detection is not specified, and the natural default is the wrong one.**
In `BATCHED_WAKEUP`, samples arrive in packets 10 to 60 s apart. If "gap > 3 s" is measured on the **arrivals**, the rule "3 times in 10 min → wake lock" fires at the third batch, always. Cascading consequences: the batched strategy is never really tested, the wake lock is systematically taken, P1's battery criterion (b) fails for a purely software reason, and the "batched → wake lock → 25 Hz" escalation fires on a false signal. The gap must be measured **exclusively** on the `SensorEvent.timestamp` deltas between consecutive samples (> 3/fs), never on the arrival clock. To be written into the spec, and to be tested with a synthetic batched stream before the first night.

**F-04 / F-05 / F-17**: dealt with in §3, these are the defects that produce a figure that is wrong and credible.

---

## 2. What is missing

**Between-night calibration.** Trap no. 9 identifies the problem (strap, tightness, ×2-3 in amplitude) and draws no mechanism from it. Without a physical reference, the adaptive threshold absorbs the change of coupling and the PLMI changes without anything having changed in the subject. To be added: a **coded** calibration ritual, run before every night, 60 s in total — 20 s with the watch placed on the mattress (bedding noise), 20 s with the leg immobile (worn noise floor), 3 dorsiflexions marked by a tap (response to a standard stimulus) — from which a `calibrationGain` and a `noiseFloorRef` per night, stored. A night whose gain departs by more than ±25 % from the reference night is **excluded from the trend by the code**, not by the user's judgement. Also add mandatory fields: leg (L/R), strap identifier, tightness notch.

**Bed partner.** A mattress transmits. A partner's periodic movements — or a cat's — can be scored as one's own, and there is no way to separate them with a single sensor. Nothing in the spec. To be added: a mandatory "alone / with someone" field in the pre-sleep form, exclusion of accompanied nights from the primary comparison (or stratification), and an explicit control night "watch on the mattress on the partner's side, not on the ankle" to measure the crosstalk floor. Without that control, any PLMI measured with two people in a bed has an unknown component.

**Bedding artefacts.** The spec quotes ×2-2.3 of amplification but never records it. Heavy duvet / sheet alone / legs outside the duvet change the coupling and the floor. To be added to the pre-sleep form (3 options), and to be treated as a comparability criterion on the same footing as the strap.

**Clock change.** Trap no. 10 names it, no field implements it: neither the binary header nor `night_session` stores a time zone. To be added: `zoneId` (the IANA identifier, not the offset) + offset at the start and at the end, in the header's 12 reserved bytes and in Room. Absolute rule to be enforced by tests: **every duration is computed on `SensorEvent.timestamp` or `elapsedRealtime`, never by a difference of `startWallMs`** — this also covers the case that is more frequent than DST, the NTP resynchronisation in the middle of the night that moves the wall clock by several seconds.

**Algorithm versioning and retroactive re-scoring.** `algoVersion` exists on `night_session` but nothing uses it. Missing: a `paramsHash` on **every** `plm_result`; an SQL view `comparable_night` that every trend query must go through; the structural ban on plotting a curve that mixes two `(algoVersion, paramsHash)`; and a `RescoreAllWorker` triggered by any parameter change, which recomputes **all** the nights from the raw chunks. The "recompute" slider in the night detail, as specified, produces exactly the forbidden artefact: one night rescored with new parameters compared against nights scored with the old ones.

**Health data protection on the phone.** Nothing in the spec. Missing: `android:allowBackup="false"` + `dataExtractionRules` (otherwise the database goes into the Google backup by default), no `INTERNET` permission declared at all (the Data Layer does not need one — the absence of the permission is a verifiable guarantee, unlike a promise), a biometric lock on opening, `FLAG_SECURE` on the result screens, export through SAF to a chosen location and not to `Downloads`, no analytics or crash reporting SDK, and a "delete everything" button that also erases the raw chunks. The data read from Health Connect must never leave the app other than through the explicit export.

**Backup / export.** 8.8 MB/night × 60 nights ≈ 530 MB of raw chunks, with no retention policy, no off-device archiving, no re-import. Yet the raw chunks are the **only** thing that will make it possible to rescore when the algorithm changes — and it will change. To be added: export of one bundle per night (chunks + sidecar + database rows) to external storage, tested re-import, and a test that proves that a database rebuilt from a bundle gives a bit-identical PLMI.

**Partially lost nights.** No rule. To be coded: the denominator is not the TST but the **analysable sleep time** = sleep epochs ∩ epochs actually covered by valid blocks (bitmap of 30 s epochs rebuilt on re-reading). Refusal rule: fewer than 4 h analysable, or coverage < 85 % of the TST, or a single gap > 20 min → **no figure displayed**, the night is marked unusable and does not enter the trend. Never a figure with an asterisk: the asterisk does not survive the trip to the doctor's office.

**Automatic stop on waking.** Trap no. 11 names it without designing it. To be coded: stop on being put on charge (`ACTION_POWER_CONNECTED`), or on sustained off-body > 5 min, or at `startWallMs + 11 h`, whichever comes first; the reason for stopping written into the sidecar; and the 10 min preceding the stop excluded from the analysis by default. Symmetrically — and this is the heaviest gap — the **manual start** is the most likely failure mode: forgetting to launch the recording is not random, it is forgotten on the evenings when one is tired, tipsy, or away from home, that is to say evenings correlated with the result. To be added: a scheduled reminder, and an explicit "missing night" record so that the trend shows its holes instead of closing them up.

**Also missing**, outside the requested list: no Room migration planned (risk of `fallbackToDestructiveMigration` = loss of the history); no handling of the automatic revocation of Health Connect permissions after 30 days of inactivity (the `SleepFetchWorker` will fail silently); no snapshot of what Health Connect returned (Samsung Health rewrites its sessions retroactively — `metadata.lastModifiedTime` must be stored and a rescore run when it changes, keeping both versions); no estimate of the clock offset between the two watches, when a 2 min offset moves events from one side of sleep onset to the other.

---

## 3. Risk of self-deception

This project produces a number that will influence a treatment decision. The question is therefore not "is the number pretty" but "by what precise paths can it be wrong while looking solid". There are nine of them.

**A-1. Circular denominator (the most serious).** The PLMI is `PLMS / hours of sleep`, and in the absence of Health Connect the "sleep" is estimated from the **same** accelerometer that supplies the numerator. The more he moves, the fewer epochs are classified as "sleep", the more the denominator shrinks. The treatment reduces the movements: the numerator falls **and** the denominator grows, in the same motion. The apparent effect is therefore mechanically amplified — a null effect can be displayed as a clear improvement.
Guard rail to code: `maskSource = ACCEL_MACRO` **forbidden** for the primary result (a constraint at the DAO level, not at the UI level); always store and display the numerator and the denominator separately; systematically compute a second index normalised by **time in bed** (independent of the algorithm) and refuse any conclusion if the two indices diverge in direction.

**A-2. The adaptive threshold makes the nights non-comparable by construction.** The threshold is 8 × the noise floor estimated on the night itself. A quieter night lowers the floor, lowers the threshold, and takes micro-movements that were previously invisible over the bar: **less movement can produce more events**. A noisy night (partner, thick duvet) does the opposite. The figure moves without the subject having changed.
Guard rail to code: an absolute floor, `threshold = max(8 × nightFloor, calibratedAbsoluteThreshold)`; storage of the median floor of each night; an automatic comparability predicate on that floor (±30 %) that excludes the night from the trend; and above all a **second scoring arm with a fixed absolute threshold**, computed for every night — if the two arms do not vary in the same direction, the app displays "effect not robust" and plots nothing.

**A-3. Expectancy effect because he knows his evening dose.** He knows what he took, so: he records more faithfully the nights he judges interesting; he looks at the result on waking and forms an expectation for the following night; he moves a slider and stops when the curve tells the expected story; he discards after the fact the "failed" nights, whose quality flags are correlated with the amount of movement, hence with the dose.
Guard rails to code, in this order of effectiveness:
1. **The evening dose and the context are entered and sealed BEFORE the watch agrees to start** (the START button is refused as long as the evening form has not been submitted on the phone); the record is append-only, not modifiable, timestamped.
2. **Result hidden by default**: on waking, the app displays "night recorded, quality OK" and nothing else. The result is revealed only at the end of a block of N nights declared in advance, or by an explicit gesture that **logs the reveal** — the number of glances is itself a datum to be exported.
3. **No per-night slider.** A parameter change is global, bumps the `paramsHash`, and triggers a rescore of all the nights. The trend filters on a single hash and refuses mixtures.
4. **No "exclude this night" button.** Exclusions are deterministic predicates evaluated before the computation; excluded nights stay visible, greyed out, with their reason, never erased.
5. **Analysis log** (version, hash, timestamp, number of rescores) included in the export: a physician must be able to see how many times the data has been reprocessed.

**A-4. Comparison of non-comparable nights.** Strap, tightness, leg, bedding, partner, alcohol, caffeine, illness, heat, bedtime, sleep duration — each of them moves the figure as much as the treatment does. Guard rail: the `comparable_night` view mentioned in §2 as the sole gate into the trend, with an explicit and tested predicate (same leg, same strap, alone, calibration gain within tolerance, ≥ 4 h analysable, not a clock-change night). Whatever does not pass the predicate exists in the database, appears in the list, but never enters a comparison.

**A-5. Over-interpretation of a variation inside the noise.** The spec itself quotes the figure that kills: in confirmed RLS patients, the 15/h threshold is crossed on only ~34 % of individual nights. The within-subject night-to-night variability is therefore of the same order as the signal being sought, before even adding the measurement error of this device. Comparing "my night yesterday" with "my night last week" has no informational content.
Guard rails to code:
- a `minimumDetectableChange(sd)` function in `algo`: `MDC95 = 1.96 × √2 × SEM` estimated on the comparable nights already collected;
- the UI **never** displays a difference between two nights; it displays points, an MDC band, and an explicit text "change indistinguishable from night-to-night variability" as long as the difference is inside the band;
- refusal to plot a trend line below 10 comparable points; refusal to display an A/B comparison as long as the number of nights per arm is below the one computed from the observed standard deviation for the minimum difference declared in advance;
- no verb of change ("improvement", "worsening", "it is going down") in the string resources — this is verifiable by a unit test on the strings file, and that test is a serious one.

**A-6. Data quality biases the figure in an unknown direction.** Corrupted blocks skipped, FIFO gaps, off-body periods, watch left on charge on the bedside table: all of them reduce the numerator without reducing the denominator, or the reverse. Guard rail: denominator = **analysable** sleep time (cf. §2), a test assertion "sum of the covered epochs == decoded samples / fs", and a refusal to produce a figure below 85 % coverage.

**A-7. The series rule amplifies any detection error non-linearly.**

> **Correction of 29/07/2026 (verified on PubMed and by calculation).** The initial statement of this paragraph — "a single missed event cuts a series of 6 into two series of 3" — is **false in the typical regime**. Under AASM, the onset-onset interval must fall within [5, 90] s: an IMI of 21 s doubled by a miss comes to 42 s, stays inside the window, and **the series is not broken** — only the count drops by one. The break happens only if the merged interval exceeds 90 s, so for IMI greater than 45 s, or after three consecutive misses at 25 s. The real effect of a miss rate is therefore a **deflation of the count**, not a fragmentation. Which does not make the problem benign: see `SPEC-v2.md` §5, where this miss rate (39 % for mechanical reasons, more if the movements alternate between the legs) becomes the central argument for the change of primary metric.

Still true, and it is the substance of the paragraph: requiring 4 consecutive CLM makes the count non-linear with respect to detection error, and that non-linearity worsens at long IMI, precisely where the most fragile series are found. The figure's sensitivity to the threshold is therefore far more violent than the detector's. Guard rails: always report the raw CLM count (robust) **alongside** the count of PLMS in series (fragile), and require agreement in direction between the two; turn the ±20 % parametric sweep of §Verification into a **per-night** artefact (`plmiMin`, `plmiMax` stored), so that the UI plots a band and never a point.

**A-8. The wrist hypnogram moves with the treatment.** A gabapentinoid treatment modifies sleep architecture (more slow waves, fewer awakenings) and reduces movements; but the Galaxy Watch 5 estimates its stages **from movement and heart rate**. Under treatment, it will mechanically report more deep sleep and more TST — which will lengthen the PLMI denominator and confirm the expected story, without any independent measurement having established it. Guard rail: read the two available sources (Samsung Health and Sleep as Android) and treat their divergence in TST as the honest error bar of the denominator; a flag if the difference exceeds 30 min; never present a per-stage PLMI (deep/REM) as anything other than exploratory.

**A-9. The name of the number.** Calling "PLMI" an index produced by a watch strap on an ankle, with a home-made algorithm never confronted with a polysomnography, guarantees that it will be read as a laboratory PLMI — by the user first, by the physician next. Guard rail: rename the metric in **the code and the export** (for example `aPLM-i`, "index of periodic ankle movements, estimated, not validated"), and make the "methods and limits" block structurally inseparable from the export (a test that fails if the export does not contain it). The only defensible objective of this report is to obtain a real examination, not to replace it.

**Methodological corollary, to be said clearly once:** the user is **already on a treatment**. Without an untreated reference period, no data from this app can measure the effect of the treatment; it can only measure the variability under treatment, and possibly the effect of a dose change **if** that change is decided by the physician, applied in sufficiently long blocks, and declared beforehand. Any other reading is a retrospective reconstruction.

---

## 4. Verdict on (a) to (e)

**(a) Movement chart sent to the phone, real time or batch — REFUSED as formulated, ACCEPTED in another form.**
The justification put forward ("lose nothing if the watch stops in the middle of the night") does not hold: that is already the job of the append-only format with `fsync`, which survives a crash, a reboot and an empty battery. Streaming raw data all night over Bluetooth only protects against the physical destruction of the watch, and costs radio wake-ups on both sides — that is to say precisely the risk that phase 1 has to measure. What the real need expresses is "knowing that the recording is alive".
Recommended compromise, three distinct channels:
1. **Health heartbeat** every 5 min via `DataClient`: ~40 bytes (samples, gaps, battery, mode, bytes written), ~96 items per night, negligible cost, and the Data Layer resynchronises on its own when Bluetooth comes back. The phone raises a **silent** alert if the heartbeat stops for > 15 min — above all not an alarm, which would destroy the very night we are trying to measure; the finding is read on waking.
2. **1 Hz envelope preview** (decimated RMS), pushed in 5 min blocks: ~30 KB for the whole night. That is the "movement chart" he wants to see, available in near real time, for a cost in no way comparable to that of the raw data.
3. **Raw chunks in batch only**, over `ChannelClient`, under a charging constraint, with the idempotent protocol already specified — imperatively excluding the chunk currently being written (F-13).

**(b) Capture by a background service rather than an app on screen — ACCEPTED, it is already the spec, but the safety net is incomplete.**
A point of vocabulary that has consequences: Android does not allow a *background* service to sample a sensor continuously; a *foreground* service with a persistent notification is required, which the spec correctly provides for (`foregroundServiceType="health"`, no 6 h/24 h timeout unlike `dataSync`). What is missing: `START_STICKY` does not reliably restart a service killed by the low-memory killer during Doze. Add (i) a 15 min `PeriodicWorkRequest` as a watchdog, which re-reads `active_session.json` and restarts the service if it is dead, (ii) the battery optimisation exemption — unrestricted when sideloading, (iii) a Wear OS Ongoing Activity so that the system considers the session active, (iv) the triggering of the three recovery paths (boot, `MY_PACKAGE_REPLACED`, watchdog) tested by actually provoking it, not by assuming it.

**(c) Very simple watch app, just the tracking — ACCEPTED without reservation, with two non-negotiable additions.**
A single static view, no animation, no always-on: it is the right choice, and for the right reason (every recomposition wakes the SoC). Two additions: START must be **blocked** as long as the evening form is not sealed on the phone (guard rail A-3), and STOP must require a long press with confirmation — an accidental STOP at 3 in the morning costs an entire night. The screen must show at a glance: duration, samples, gaps, FIFO mode, battery. Nothing else. **No result, no PLMI figure on the watch**, ever.

**(d) Phone app that fetches deep/REM via Health Connect — ACCEPTED for total sleep, REFUSED for per-stage use.**
Health Connect is the right channel, and the app must read it. But the validity stops quickly: the stages of a consumer watch are estimated from movement and heart rate, and their epoch-by-epoch agreement with polysomnography for deep sleep and REM is poor. Defensible use: **TST and the wake/sleep mask**, that is to say the denominator of the PLMI. Indefensible use: a per-stage PLMI, or any claim of the kind "my PLMS occur mostly in N2". To implement in addition: a snapshot of what HC returned, with `dataOrigin` and `lastModifiedTime` (Samsung rewrites its sessions after the fact); detection of the automatic revocation of permissions after 30 days; estimation and storage of the clock offset between the two watches; reading of **both** sources (Samsung Health and Sleep as Android) in parallel, their divergence serving as the displayed error bar. And check on the device that the Galaxy Watch 5 does write `stages` and not just a duration: the whole cross-referencing rests on that.

**(e) "Beautiful, easy to use and to understand" — ACCEPTED for beautiful and easy to use, FIRMLY QUALIFIED on "easy to understand".**
The care put into the interface is useful: a tiresome app will not be used regularly, and irregularity is the first bias (A-3). But "easy to understand" means "compressed", and compressing a non-validated estimate into one large coloured figure is exactly the failure mechanism of this project. The design rule must be inverted: **the interface's job is to make the uncertainty legible**, not to file it away in a footnote. In practice: never a figure without its band (A-7); the analysable hours displayed with the same visual weight as the index; the quality flags in first class, not as a discreet icon; no normal/abnormal colour coding and no 15/h line in the charts; a panel "why this figure may be wrong tonight" that lists the flags actually raised, and not a generic disclaimer that the eye learns to skip within three days.

---

## 5. Check this yourself

1. **That the `format` module compiles and that its tests pass** (`./gradlew :format:test`). F-01 is a near-certain compilation error, and the fact that the question arises means that the `ChunkCodecTest` test suite has never been run — hence that none of the guarantees it claims to lock in is acquired, including the 80 header bytes that contradict the spec.
2. **Phase 1 as a real go/no-go, with a protocol written before the first night**: starting state of the battery, airplane mode or not, suspend genuinely provoked (`adb shell dumpsys deviceidle force-idle`), and gap detection computed on `SensorEvent.timestamp`. Without that, three "successful" nights prove nothing, and the dedicated-sensor path (Axivity AX3 / GENEActiv) must stay open rather than be set aside out of stubbornness.
3. **That the Galaxy Watch 5 does write *stages* into Health Connect, and not only a duration** — and at what delay after waking. The whole cross-referencing, hence the denominator of the PLMI, hence the entire figure, rests on this point. To be checked on your device, before writing a line of the `phone` module.
4. **The WASM 2016 rules against the text of Ferri et al., and the AASM v3 criteria against the manual itself**: my reading is that WASM's structuring difference is not the interval window but the rule for grouping close movements (F-15), and I assert it only with medium confidence. If I am wrong, your "two rule sets" implementation is even more hollow than I say, since it would then differ by one bound only.
5. **The medical framing, before the first night and not after the seventh**: ask the sleep physician what he expects from a measurement at home, and whether there is a usable untreated reference period. If there is none, admit from the outset that this device does not measure the effect of the treatment (F-25, corollary A-9), and reposition the objective on what it can really produce: evidence that something periodic is happening at night, enough to obtain a real examination. And never adjust a dose on this figure.
