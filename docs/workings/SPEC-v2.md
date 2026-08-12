# Pendulum — Specification v2 (decision log)

This document **supersedes `SPEC-v1.md` everywhere the two contradict each other**. It does not rewrite v1: it records what changed, why, and where to read the detail. `SPEC-v1.md` stays in the repository as a trace of the initial state.

> **This document used to carry a second index of authorities here.** There were two of them in the
> repository, this one and the one in [`../README.md`](../README.md), and they had started to
> contradict each other: this index still gave `UX.md` as authoritative on the interface, whereas
> `UX.md` itself records three decisions taken after it. Two maps of the same territory — that is the
> defect this project has already paid for several times. **Only one is left, in
> [`../README.md`](../README.md#which-document-makes-the-decision-and-on-what).**
>
> What this file keeps as its own: the lineage between the documents in this folder, which no other
> one writes down. `SPEC-v2.md` supersedes `SPEC-v1.md` where the two contradict each other;
> [`ALGO-v2.md`](ALGO-v2.md) supersedes its "`algo` module" section;
> [`CAPTURE-ARCHITECTURE.md`](CAPTURE-ARCHITECTURE.md) its "`wear` module" and "Transfer" sections;
> the defects that triggered all of this are in [`CRITICAL-REVIEW.md`](CRITICAL-REVIEW.md).

**Read §5 first.** It changes the product's primary metric, after the fact, on the basis of results pulled from PubMed that the five reviews did not have. Everything written elsewhere on the assumption that the metric is the hourly count must be re-read in its light — in particular `UX.md` §3 and §4.2, whose logic remains valid but whose plotted quantity changes.

---

## 1. What was asked, what was decided

| Request | Decision | Detail |
|---|---|---|
| Movement chart sent **in real time or in batch** so that nothing is lost | **Hybrid.** The raw data leaves in 5 min chunks via `DataClient` (replicated and persistent), one burst every 15 min → loss bounded to ~20 min instead of 8 h. A 1 Hz RMS envelope (900 B) travels in the same burst: that is the "real time" chart, at zero radio cost. `ChannelClient` is now only the catch-up. | `CAPTURE-ARCHITECTURE.md` §1-2 |
| Capture by a **background service** rather than an open app | **Confirmed**, with the distinction that matters: it is a *foreground service* with `type="health"` (no Android 15 timeout, unlike `dataSync`), plus a 15 min WorkManager watchdog, because `START_STICKY` is not enough against the low-memory killer. | `CAPTURE-ARCHITECTURE.md` §3 |
| Watch app **very simple, just the tracking** | **Accepted.** A single static screen, no animation, no result figure on the watch. Two non-negotiable additions: START refused as long as the evening form is not sealed, STOP by a confirmed long press. | `CAPTURE-ARCHITECTURE.md` §5, `UX.md` §6 |
| Sleep data (**deep, REM**) from another app via Google Health | **Accepted for total sleep time, refused for per-stage use.** Consumer watches estimate TST well (sensitivity ≈ 0.95) and stages badly (kappa 0.34–0.47). TST is precisely all that the PLMI needs. A per-stage PLMI will never be presented as anything other than exploratory. | `SLEEP-SOURCES.md` §1, §3 |
| **Beautiful, easy to use and to understand** | **Accepted, with the objective inverted**: the interface's job is to make the uncertainty *legible*, not to compress it into one large coloured figure. A clean and wrong figure is this project's principal failure mode. | `UX.md` §1, §3 |

---

## 2. Major corrections made to v1

### 2.1 Sensor: accelerometer, not gyroscope

The gyroscope is of no use here and costs, depending on the mode, **3 to 40 times** the current of the accelerometer alone (BMI270: 210 µA against 685 µA in normal mode; 10 µA against 420 µA in low power at 25 Hz). A periodic ankle movement of 0.5 to 10 s is entirely described by the acceleration. The real criterion for choosing a device is not "does it have a gyroscope" but **"can a third-party app read the raw sensor"** — which neither Fitbit, nor Oura, nor Whoop, nor Garmin allows, and which a Wear OS device does. (`SLEEP-SOURCES.md` §2)

### 2.2 Transport: `DataClient`, not `ChannelClient`

v1 ruled out `MessageClient` on its 100 KB cap and kept `ChannelClient` — **without considering `DataClient`**, which has the same cap but the semantics of a replicated and persistent store. By cutting the chunk to 5 min (≈ 91 KB), it fits in a `DataItem`. A chunk pushed at 2 h is already on the phone: the watch dying at 3 h no longer concerns it. It is that omission which made the batch on waking look "inevitable".

Cost measured to an order of magnitude: incremental sync **1 to 3.6 % of battery** over the night, against ~65 % for a partial wake lock held for 8 h. The streaming/batch debate was about 20 to 60 times less energy than the real item of expenditure.

### 2.3 The PLMI denominator is no longer derived from the signal being counted

Blocking defect F-04: in the absence of Health Connect, v1 estimated the hours of sleep from the **same** accelerometer that supplies the movements. The treatment reduces the movements → the numerator falls **and** the denominator grows, in the same gesture. A null effect can be displayed as a clear improvement.

v2 rules, applied at the DAO level and not at the interface level:
- `maskSource = ACCEL_MACRO` **forbidden** for the primary result;
- numerator and denominator always stored and displayed separately;
- a second index normalised by **time in bed** (independent of the algorithm) computed systematically; if the two indices diverge in direction, no conclusion is displayed;
- denominator = **analysable** sleep time (sleep epochs ∩ epochs actually covered by valid blocks), never the raw TST.

*Conflict settled here*: `SLEEP-SOURCES.md` §4 concludes that the absence of Health Connect is "not blocking" since an accelerometer mask remains computable. That is correct for **running** the app, false for **producing a figure comparable from one night to the next**. The two positions coexist as follows: the accelerometer mask is still computed and displayed as a second arm, but it can never carry the primary result nor feed the trend.

### 2.4 Sleep mask: van Hees, not Cole-Kripke

Cole-Kripke is calibrated on **wrist** *activity counts* in one-minute epochs, with a sensor (Motionlogger AMI, zero-crossing mode) that no longer has an equivalent — there exists no published conversion from raw g to counts, and the open source implementations agree neither on the preprocessing nor on the coefficients. On the ankle it overestimates TST by +43 min, which **deflates** the PLMI: a true PLMI of 15.0 shows as 13.6, below the screening threshold. The choice of the masking algorithm shifts the PLMI by ~36 % — **more than any error of the detector itself**. It is the first item of the error budget, to be dealt with before optimising the detection threshold.

**And the trap that disqualifies a naive implementation**: the van Hees rule adopted requires ≥ 5 consecutive minutes without movement. A series of movements 22 s apart puts ~13 movements in any 5 min window — the probability that a window is free during a series is **zero**. Applied as it stands, the mask therefore scores the whole period of the series as wakefulness: TST collapses, the PLMI explodes, and at the same time the AASM rule "at least part of the movement within a sleep epoch" **removes the movements themselves**. The most affected subject is the one for whom the algorithm behaves worst. Response adopted: the mask is built **blind to periodic movements** (only gross body movements and posture changes count as evidence of wakefulness), then a fixed point bounded to two iterations. (`ALGO-v2.md` §3.6)

### 2.5 DSP chain hardened

Adopted: **0.5–8 Hz** band-pass (the low corner rejects respiration at 0.2–0.33 Hz, the high corner gains ~5 dB of signal-to-noise ratio), coarse envelope of **0.5 s** instead of 0.15 s, noise floor by **low percentile over 120 s with iterative exclusion** instead of a self-contaminated 25 s median, and above all an **absolute floor of 0.020 g** without which the relative threshold becomes hypersensitive in silence.

Rejected after examination: **TKEO** (amplifies high-frequency noise for a timing gain of ~69 ms, of no value against a clinical granularity of 500 ms) and the lagged causal window (a 35 s bias that can manufacture or destroy an entire series; we analyse offline, not as a stream).

Two v1 errors corrected along the way, which no reviewer had seen:

- **The 0.15 s RMS window was a bug, not a parameter.** It comes from S-PLMAD, which deals with EMG at 512 Hz in a 10–300 Hz band. On a signal whose useful content is at 1–5 Hz, 0.15 s does not even average half a period at 2 Hz: the envelope ripples, the comparator chatters around the threshold and **fragments a single movement into several**. Raised to 0.50 s.
- **The accelerometer does not measure the same quantity as EMG.** 39 % of the movements scored on EMG come with **no** movement detectable by accelerometry (Terrill 2013) — a pure dorsiflexion does not displace a sensor located above the joint axis. This is not noise: it is a **different scale**. Consequence: the 15/h threshold is not transposable, and the synthetic ground truth carries two sets of labels (`emgTruth` / `accelTruth`) — without which v1's "F1 ≥ 0.90" criterion was unreachable for a reason that is not the algorithm's fault.

Also corrected: v1 assumed a spectral content of 10–15 Hz for a CLM — **no spectral analysis of accelerometric PLMS is published**, and the only defensible bounds are the WASM 2006 instruction (sensor linearity over 0.8–14 Hz) and the 5–8 Hz of ankle clonus. Any figure beyond that is an inference, not a citation.

### 2.6 The two clinical rule sets finally differ for real

v1 believed that AASM v3 and WASM 2016 were distinguished only by the lower bound of the interval (5 s against 10 s). That is false: the structuring difference is the **series-breaking rule** (WASM breaks the series on a short interval or on a long movement; AASM is silent). Without that rule, "implementing both rule sets" produced only the same figure twice, up to one bound.

### 2.7 Binary format

- `ChunkFormat.toRaw` **did not compile** (`Math.round(Double)` returns a `Long`, and `Long.coerceIn(Int, Int)` does not exist). **Fixed**, with a guard on non-finite values.
- The CRC covers only the payload: `count`, `tFirstNs`, `tLastNs`, `flags` are protected by nothing. A corrupted time base shifts the whole night **without detection**. To fix: CRC over `blockHeader[0..24)` ‖ payload, and resynchronisation on `BLK!` instead of a blind `continue`.
- A block of 512 samples (10.24 s) can straddle two FIFO flushes separated by a suspend: the linear interpolation then dates **all** of its samples wrongly. To be forbidden at write time.
- The header is **80 bytes** (the code is right, spec v1 was wrong: its own list of fields already summed to 68 B excluding the reserved area).
- The 12 reserved bytes take the IANA `zoneId` and the offset. Absolute rule: **every duration is computed on `SensorEvent.timestamp` or `elapsedRealtime`, never by a difference of wall clocks** — this covers the clock change and, more frequently, an NTP resynchronisation in the middle of the night.

---

## 3. The anti-self-deception guard rails (to be coded, not displayed)

This project produces a number that will influence a dosing decision. The six non-negotiable guard rails:

1. **The evening dose and the context are sealed before the watch agrees to start.** Append-only record, not modifiable.
2. **Result hidden by default on waking**: "night recorded, quality OK" and nothing else. The reveal is logged and exported.
3. **No per-night setting.** A parameter change is global, bumps the `paramsHash`, and triggers a rescore of **all** the nights from the raw data. The trend refuses to mix two hashes.
4. **No "exclude this night" button.** Exclusions are deterministic predicates evaluated before the computation (SQL view `comparable_night`: same leg, same strap, alone in the bed, calibration gain within tolerance, ≥ 4 h analysable). Excluded nights stay visible, greyed out, with their reason.
5. **`MDC95` (smallest detectable change) computed and plotted as a band.** The interface never displays the difference between two nights; below that threshold, it writes "change indistinguishable from night-to-night variability". No verb of change in the string resources — verifiable by a unit test on the text file.
6. **The metric is renamed** in the code and in the export: `aPLM-i`, "index of periodic ankle movements, estimated, not validated". Calling "PLMI" a figure produced by a watch strap on an ankle guarantees that it will be read as a laboratory PLMI.

### The honest framing, to be said once

The user is **already on a treatment**. Without an untreated reference period, this device **cannot measure the effect of the treatment** — it measures the variability under treatment, and possibly the effect of a dose change if that change is decided by the physician, applied in long blocks, and declared in advance. The only defensible objective of this project is **to obtain a real examination**, not to replace it. No dose is adjusted on this figure.

---

## 4. Revised work order

The v1 phase criteria were partly inconsistent (P1: "≥ 97 %" and "cumulative < 2 min" are not the same threshold; "largest gap < 5 s" is incompatible with a batch latency of 60 s). Corrected version:

| Phase | Content | Exit criterion |
|---|---|---|
| **P0** | 4-module skeleton, `format` compiling and its tests passing | `./gradlew :format:test` green, 2 APKs installed |
| **P1 — BLOCKING** | Sensor-only spike, 3 to 5 nights. Not one line of algorithm before that. | Coverage ≥ 99 % of the expected samples **measured on the `SensorEvent.timestamp` deltas** (never on the arrival clock, otherwise in batched mode the rule always fires); battery > 20 % at 8 h; reproducible over 3 nights. Failure ⇒ seriously consider a dedicated sensor (Axivity AX3, GENEActiv) rather than persisting. |
| **P2** | Format hardening: extended CRC, ban on straddling a flush, time zone, end marker, resynchronisation | Kill of the process in the middle of the night ⇒ session resumed, loss ≤ 1 block (10 s), not ≤ 1 chunk |
| **P3** | Incremental `DataClient` transfer + `ChannelClient` catch-up | Complete night received with Bluetooth cut in the middle; watch killed at 3 h ⇒ everything that was pushed is intact and the night is marked truncated |
| **P4** | Algorithm in TDD on a synthetic signal | The 17 regression assertions of `ALGO-v2.md` §5.5 |
| **P5** | Sleep mask + Health Connect + Room + WorkManager | Kappa mask/HC reported; a real night producing the results of both rule sets |
| **P6** | Phone interface, questionnaires, export | Cold run-through night → charger → result, with no intervention |
| **P7** | 7-night campaign + protocol of voluntary movements | MDC95 band, parametric sensitivity curve |

**Before P5, and ideally before P0**: run the verification procedure of `SLEEP-SOURCES.md` §5 to confirm that the Galaxy Watch 5 does write *stages* (and not just a duration) into Health Connect, and at what delay after waking. The whole denominator rests on that.

---

## 5. The primary metric changes: periodicity rather than hourly count

*Added on 29/07/2026 after directly querying PubMed. This is the heaviest decision in this document and it comes after the fact — the five reviews had all worked on the assumption that the metric was the hourly count.*

### 5.1 The three results that force it

| Source | Result |
|---|---|
| Skeba, Hiranniramol, Earley, Allen — *Sleep Med* 2016;17:138-43 (PMID 26847989) · 29 untreated RLS + 22 controls, 2 consecutive nights | Night-to-night variability, as a % of the mean of the two nights: **mean log IMI = 3.6 % ± 3.7** against **PLMS/h = 43.2 % ± 37.1** (p < 0.001). The IMI follows a log-normal distribution. The variability of the log IMI is also better than that of the Periodicity Index. |
| Ferri et al. — *Sleep Med* 2013;14(3):293-6 (PMID 23068780) | The Periodicity Index varies **more than 6.5 times less** than the PLMS index in RLS (2 times in PLMD). |
| Ferri et al. — *Sleep Med* 2016;22:97-9 (PMID 26922620) · 107 RLS + 48 controls | Optimal diagnostic thresholds: **15-16/h** (standard index), **~13/h** (alternative index), **~0.5** (Periodicity Index), similar areas under the ROC curve. Periodicity therefore has its own published threshold. |

To which is added a 2026 review signed by Ferri himself (*Sleep*, PMID 42213077) whose thesis is that "periodicity, aggregation into bursts, stage dependence and autonomic coupling carry more clinical information than event counts alone".

### 5.2 Why this solves three problems at once

1. **Twelve times less night-to-night variability.** That is the problem `UX.md` handled by refusing to conclude under three nights — a correct treatment for the count, but one that remains a sticking plaster.
2. **Neither the log IMI nor the Periodicity Index needs a denominator.** They are computed from the movement onset times alone. **The circularity described in §2.3 disappears for the follow-up metric**, and Health Connect becomes optional for it again (it remains necessary for the hourly count in the medical report).
3. **The 15/h threshold was not transposable anyway** to an accelerometric measurement (§2.5, Terrill: 39 % of EMG movements are mechanically invisible). The accelerometric count is on a different scale; periodicity, for its part, is a property of the rhythm, not of the amplitude.

### 5.3 What does not transpose — and has to be coded

**A miss rate biases the mean log IMI, strongly.** If the misses are independent with a probability `p`, an observed interval covers `N` true intervals, `N` geometric, and the bias is `E[ln N] = Σ p^(k−1)(1−p)·ln k`:

| `p` | Bias on the mean log IMI | Effect on the interval |
|---|---|---|
| 0.20 | +0.158 nats | ×1.17 |
| 0.30 | +0.255 nats | ×1.29 |
| **0.39** (Terrill, mechanical misses) | **+0.357 nats** | **×1.43** |
| 0.50 | +0.508 nats | ×1.66 |
| **0.70** (39 % mechanical **and** left/right alternation) | **+0.915 nats** | **×2.50** | ← corrected on 2026-08-05: +0.901 corresponded to a sum stopped around k = 15, the series converges slowly at p = 0.70

To be compared with the published night-to-night variability: 3.6 % of the mean log IMI, i.e. ≈ 0.11 nats for an IMI of 21 s. **The bias at 39 % is 3.3 times that variability.** The raw mean of the log IMI is therefore unusable as it stands.

**The laterality problem is the most serious one, and none of the five reviews had seen it from this angle.** PLMS can be bilateral simultaneous, alternating, or unilateral. A sensor on a single leg sees, in the case of perfect alternation, an exactly doubled interval — a pure harmonic, not noise. And if the laterality varies from one night to the next, the very stability that justifies the change of metric collapses. This is an open assumption: I have found no data on the within-subject stability of left/right symmetry.

**The answer: a mixture model over the harmonics.** We model the observed distribution as `log I_obs ~ Σ w_k · N(μ + ln k, σ²)` with `w_k = p^(k−1)(1−p)`, and estimate `(μ, σ, p)` by expectation-maximisation. The centroids are separated by `ln 2 ≈ 0.69` nats for a typical `σ` of 0.2 to 0.4: the deconvolution is well posed even at `p = 0.39`, where the fundamental peak still weighs 61 % and the first harmonic 24 %.

Three benefits, two of them unsought:
- `μ` is the **fundamental period**, rid of the miss rate;
- `p` is estimated, therefore **measured**: it is a free quality metric, and a `p` that jumps from one night to the next is exactly the non-comparability flag that §3 called for;
- a `p` close to 0.5 with a weak fundamental peak **is the signature of a left/right alternation** and becomes a clinical result in itself.

Honest reservation, formulated by the second opinion and accepted: the assumption that the misses are independent is probably false. The accelerometer misses low-amplitude movements first; if a burst decreases in amplitude, the misses cluster at the end of the series and the 2× peak will be under-populated relative to the geometric model. To be simulated in the synthetic generator (`ALGO-v2.md` §5) before believing the estimate of `p`.

> **"Well posed even at `p = 0.39`" has since been measured, and it is not supported.** Two runs,
> both in [`../07-validation.md`](../07-validation.md) §4.3. On the nominal night — where the
> detector's real miss rate is **0.73–0.83**, not the 0.39 this paragraph assumes — the
> expectation-maximisation returns **2 valid fits out of 20**, and the fundamental it does return is
> off by a median **11.3 %**. On the model's own best case, the true train thinned with an exactly
> geometric miss probability, the error stays small in the regime the module was designed for
> (**3.1 % at `p` = 0.30**, 8.3 % at `p` = 0.50) — but even there only **2 to 3 fits out of 20** are
> declared valid. The separation of the centroids by `ln 2` is a necessary condition, not a
> sufficient one: it says nothing about how many intervals survive the miss rate to identify three
> parameters. It is that document, not this paragraph, that states what the estimator does. Nothing
> else in §5 is affected: the case for tracking periodicity rather than an hourly count rests on
> §5.1 and §5.2, which the measurement does not touch.

### 5.4 Display decision

The hourly count **stays**, for a reason that is not technical: it is the language of sleep physicians, and the international thresholds rest on it. But it changes role.

| Use | Metric | Rationale |
|---|---|---|
| **Night-after-night follow-up** (the trend chart, the main screen) | Fundamental period `μ` in seconds, and Periodicity Index | Stable, without a denominator, without a threshold transposed improperly |
| **Report for the physician** (export) | Hourly count, with its explicit denominator, its error bars and the estimated miss rate | That is what a sleep physician knows how to read |
| **Result screen** | "Fundamental rhythm: 22 seconds · high periodicity", the count in second place | A stable figure in front, a familiar figure behind |

What must not be done: display a bare "0.58". Periodicity is presented as **a rhythm in seconds** — an interval is intuitive, a unitless index is not.

---

## 6. Open questions that belong to the user

1. **The medical framing before the first night**: ask the sleep physician what he expects from a measurement at home, and whether there is a usable untreated reference period.
2. **Compiling the `format` module**: the `toRaw` fix is done but could not be verified (no Gradle cache and no network on this machine). A `./gradlew :format:test` remains to be run.
3. **The FIFO's behaviour on the specific Pixel Watch 3**: read `fifoReservedEventCount` (the only guaranteed one — `fifoMaxEventCount` is shared between applications) at start-up, before choosing the strategy.
4. **The licence of the Cambridge-Hopkins CH-RLSq** before integration, even for personal use. The IRLS severity scale stays excluded (IRLSSG copyright).
5. **US patent 10 335 085** (Johns Hopkins, R. P. Allen) mentions the accelerometer held on the leg by a strap. Of no consequence for strictly personal use, to be read before any distribution.
6. **The stability of your laterality** (§5.3). If your movements alternate between the legs, a unilateral sensor sees a doubled interval; if the laterality changes from one night to the next, the stability that justifies the whole of §5 collapses. No data found on the within-subject stability of left/right symmetry. Two nights with the watch on the opposite leg would settle the question cheaply — and the miss rate estimated by the mixture model will say so too, provided it is not believed blindly.
