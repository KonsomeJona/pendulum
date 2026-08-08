# Validation: how the software is tested, and the honest state of that testing

> **Pendulum** measures periodic limb movements in sleep from a smartwatch worn at the ankle, and
> tracks the *rhythm* between them rather than their number. It measures; it does not
> interpret — a real measurement to take to a physician, not a diagnosis and not a medical device — see
> [what this is, and what it is not](../README.md#what-this-is-and-what-it-is-not).

This document describes what is actually verified about Pendulum, how, and what the verification does not
establish. Its centre of gravity is §4.1: a test that failed for three editions, the parametric sweep
that finally settled why, and the record of two successive diagnoses that were themselves wrong and
had to be corrected. The test now passes; what it cost to make it pass is published as a number.

**None of what follows is validation against polysomnography.** No amount of synthetic testing
substitutes for it. What is described here bounds the behaviour of the software; it says nothing
about whether the quantity the software computes corresponds to the quantity a sleep laboratory
would score. That is stated once at the top and repeated at the bottom, because it is the single most
important sentence in the document.

---

## 1. The validation problem

There is no ground truth available to an individual. Polysomnography with surface EMG of *tibialis
anterior* is the reference, and it is not obtainable by someone building an application at home. Nor
is there a defensible substitute: a second consumer device measures the same thing with the same
class of error, and agreement between two estimators derived from the same movement is not evidence
of accuracy. The adversarial review ([`fr/REVUE-CRITIQUE.md`](fr/REVUE-CRITIQUE.md), F-20) rejected
exactly that kind of reassuring, empty figure.

What remains is a ladder of partial checks. None of them is validation; each of them can *falsify*
something. They are listed here from the most controlled to the most ecological, which is also the
order from "proves the least about reality" to "proves the least about the algorithm".

| # | Level | What it can establish | What it cannot |
|---|---|---|---|
| 1 | **Synthetic signal with injected ground truth** | Detector sensitivity, precision, onset timing, index error, invariance to sampling rate, gaps, gain changes — all against labels that are known exactly | Nothing about whether the generated signal resembles a real ankle at night. The generator embodies the same physical assumptions as the design; agreement is partly circular |
| 2 | **Timed voluntary movements** | That a real limb, a real strap and the real hardware produce a detection at a time the subject chose. Onset bias and dispersion on genuine mechanics | Nothing about sleep. Voluntary dorsiflexions are larger, cleaner and more stereotyped than PLMS |
| 3 | **Negative controls** | An upper bound on false positives with no subject at all: the watch on the bedside table, the watch on the mattress beside a sleeping partner, a night on the opposite leg. The last two are the only way to measure the mattress cross-talk floor | Nothing about sensitivity. A detector that never fires passes every negative control |
| 4 | **Parametric robustness** | Whether the published index survives ±20 % on each parameter, and whether the ≷ 15/h decision flips at ±10 %. If it flips, the number is not usable regardless of how good the detector is | Nothing about which parameter value is right |
| 5 | **Internal consistency** | That the pipeline does not contradict itself: covered epochs equal decoded samples over the sampling rate; a night rebuilt from an exported bundle reproduces the index bit for bit; the incremental and definitive passes agree within a stated bound | Nothing about correctness. A consistently wrong pipeline passes |
| 6 | **Golden files** | That a fixed recording produces the same output today as it did last month. This is the only check that survives a refactor of everything else | Nothing at all about the truth of that output — it locks in whatever was there when the file was recorded |

Levels 1, 5 and 6 exist today as assertions that run on every build. Level 4 exists only as two
`@Disabled` sweeps run by hand (§4.1 and §4.4); its non-regression assertion, T12, is not written.
Levels 2 and 3 are scheduled for the seven-night campaign at the end of the roadmap
([`01-overview.md`](01-overview.md) §5, phase P7) and have not been run — nor has the hardware gate
that precedes all of it, P1.

---

## 2. The synthetic night generator

`com.pendulum.algo.synth`. Deterministic: the same seed produces a bit-identical output, and that is
itself an assertion (T13).

### 2.1 The physical model

A movement is not a rectangle. It is a **minimum-jerk fifth-order angular profile** — flexion,
sustained hold, return — with three modelled acceleration contributions: tangential (`r·θ̈`), gravity
re-projection (`sin(θ − θ₀) − sin θ₀`), and centripetal (`r·θ̇²`). The full derivation is in
[`03-algorithm.md`](03-algorithm.md) §7.5 and is not repeated here.

Three properties of that model matter for validation:

- **It calibrates itself against the literature.** With published joint angles and segment radii it
  produces peaks of 30 mg, 184 mg and 985 mg for small, medium and large movements, bracketing
  Sicbaldi's 15 mg detection floor and the 377 ± 63 mg typical sleep movement. Nobody tuned it to do
  that. In the absence of any published spectrum of accelerometric PLMS, this is the strongest
  available argument that the generator is not simply the detector's assumptions written backwards.
- **It produces the invisible events on purpose.** A pure ankle rotation moves a sensor mounted
  *above* the talocrural axis by almost nothing: `r_eff ≈ 0`. That is the mechanism behind Terrill's
  miss rate, and it has to be in the model, or the ground truth over-states what the sensor can see.
- **Its hold phase is sustained, not static.** The angle dips and recovers through the hold rather
  than freezing at `θ_max`. This was the correction that resolved T6; the reasoning, and the two
  constants it rests on — both read off Sforza's PAM-RL specification rather than tuned — are in
  §5.5.

### 2.2 The twelve distractor families

The first six are indispensable; the last six cover failure modes identified during the algorithm
review.

| # | Distractor | What it tests |
|---|---|---|
| 1 | Posture changes, 15–40 per night, gravity vector rotated 20–120° over 0.5–3 s | The first source of false positives; transients up to 1 g |
| 2 | Gross body movements, 20–60 per night, 2–20 s, 300–2500 mg | The gross-movement classifier, the refractory period, and noise-floor contamination |
| 3 | Respiratory artefact, 0.20–0.33 Hz sinusoid, 1–10 mg, amplitude-modulated | The low corner of the high-pass filter |
| 4 | Mattress vibration, 50–500 per night, 0.05–0.4 s transients, 3–40 mg, damped 8–20 Hz ringing, **tilt unchanged** | The WASM morphology criterion |
| 5 | MEMS noise and quantisation, 150–300 µg/√Hz plus a 1/2048 g LSB | The absolute noise floor `Θ_abs` |
| 6 | FIFO gaps, 20–60 gaps of 0.1–5 s plus one long gap of 30–120 s | Timeline reconstruction, blind zones, series breaking |
| 7 | Sampling-rate drift, 50.0 / 50.3 / 52.6 Hz plus a linear drift | Rate invariance |
| 8 | Off-body, watch on the table for 10 min | Exclusion from the numerator **and** the denominator |
| 9 | Hypnagogic foot tremor / ALMA, 0.3–4 Hz bursts of 10–15 s, 2–8 per night | A genuine clinical distractor that must **never** be counted |
| 10 | Non-periodic clusters, bursts of 5–10 movements at random 1–8 s intervals | The AASM/WASM divergence on series breaking |
| 11 | Respiratory-related leg movements, series at 25–45 s coupled to distractor 3 | The residual bias with no respiratory channel |
| 12 | Mechanical gain jump, movement channel multiplied by 0.7 at mid-night | The strap that loosens during the night |

Format-level corruption is injected separately, by a post-generation mutator rather than through the
night specification: blocks with `tFirst > tLast`, overlapping blocks, a sample count inconsistent
with the payload length, a three-hour clock jump, and sign wrap at ±16 g simulating an encoder bug.

### 2.3 The two label sets — the conceptual point

This is the part of the harness that carries the most weight, and it is a direct consequence of
Terrill et al. (EMBC 2013): **39.0 % of EMG-scored leg movements produce no detectable acceleration
at an ankle sensor.**

```kotlin
data class GroundTruth(
    val emgTruth: List<TruthEvent>,     // every movement generated — the EMG scale
    val accelTruth: List<TruthEvent>,   // the subset mechanically visible to the sensor
    ...
)
```

`accelTruth` is `emgTruth` minus the events flagged `ankleOnly` (pure ankle rotation, `r_eff ≈ 0`)
and minus those whose simulated peak falls below a physical visibility threshold (8 mg by default,
about 0.4× the absolute noise floor). The `ankleOnly` fraction defaults to **0.39**, calibrated on
Terrill, with a permitted range of 0.25–0.55.

**Every detector metric is scored against `accelTruth`.** Scoring against `emgTruth` would cap
sensitivity at 0.61 by mechanics alone, and therefore F1 at roughly 0.76, making a target of 0.90
unreachable **for a reason that is not the algorithm's fault**. That distinction is what makes the
criterion honest rather than merely strict.

The same argument, applied one step further, moved the denominator of T6 a second time — to
`accelTruth` restricted to the events above the detector's own onset threshold — and the cost of that
restriction is published as T22 rather than absorbed. §4.1 is the measurement and the decision; it
should be read before quoting any F1 from this document.

`emgTruth` is kept for exactly one purpose, and it is essential: to measure and **report** the
conversion factor between the accelerometric scale and the EMG scale — the structural downward bias
of the published count. That factor is what forbids comparing this project's count with the ICSD-3
threshold of 15/h. The nominal-night test asserts that it stays between 0.40 and 0.65.

### 2.4 Matching and metrics

Greedy chronological matching, one-to-one, with an onset tolerance of **1.0 s**. The tolerance is
justified rather than chosen: the finest clinical granularity is the lower bound of the
inter-movement interval, 5 s, and the coarse 0.5 s envelope introduces an onset bias bounded at about
0.25 s. One second is wide relative to the bias and narrow relative to the rule.

The matcher returns true positives, false positives, false negatives, sensitivity, precision, F1,
onset bias and onset standard deviation. The one-to-one constraint is load-bearing, and §4.2 is about
what happens when the detector emits more candidates than there are truth events.

---

## 3. The non-regression suite

Each assertion runs over at least 20 seeds; the threshold applies to the median, with a secondary
worst-case assertion where indicated.

| ID | Scenario | Assertion | What it protects |
|---|---|---|---|
| **T1** | MEMS noise alone, 30 min | **0 movements**, worst case included | The absolute floor. A detector that fires on thermal noise invents a disorder |
| **T2** | Respiration alone, 8 mg at 0.25 Hz, 30 min | **0 movements** | The low corner of the high-pass |
| **T3** | Mattress vibration alone, 300 transients in 30 min | **≤ 2 movements** (≤ 0.7 % false positives) | The morphology criterion — the only anti-mattress filter in the published rule corpus |
| **T4** | 40 posture changes alone | **0 movements** untagged as postural; posture-detector recall **≥ 0.95** | The largest single source of false positives, with transients up to 1 g |
| **T5** | Isolated movements of increasing amplitude | Sensitivity **≤ 0.05** at 4× the floor, **∈ [0.35, 0.65]** at 8×, **≥ 0.95** at 16× | The slope of the detection curve. Too steep and the detector is a comparator that any gain drift shifts; too shallow and the threshold means nothing |
| **T6** | Nominal night: true index 25/h, true periodicity 0.60, all distractors active | F1 **≥ 0.90** vs `accelTruth` **restricted to events above `Θ_on`**; relative index error **≤ 0.10** against the same denominator; periodicity error **≤ 0.05**; onset bias **≤ 300 ms**, sd **≤ 400 ms** | The end-to-end test. §4.1 is why the denominator says what it says, and T22 is the price of it |
| **T7** | Negative night: true index 2/h | Estimated index **≤ 5/h** | The screening test. A detector producing 12/h on a healthy subject manufactures a diagnosis |
| **T8** | Rate invariance: 50.0 / 50.3 / 52.6 Hz, same events | Index spread **≤ 2 %** | That the number is a property of the subject, not of the clock |
| **T9** | Decimation 50 → 25 Hz | Index change **≤ 5 %** | The fallback capture mode |
| **T10** | 2 % scattered gaps plus one 90 s gap | Index change **≤ 3 %** vs the same night without gaps | That the denominator is analysable time, not recorded time |
| **T11** | Mechanical gain ×0.6 and ×1.8 | Calibration active: change **≤ 10 %**. Calibration inactive: change **> 40 %** — **inverted assertion** | See below |
| **T12** | Parametric sensitivity, ±20 % on each parameter | No parameter moves the index by **> 15 %**; at ±10 % the ≷ 15/h decision flips for **no** parameter | Whether the published number survives its own tuning |
| **T13** | Determinism | Same seed → **bit-identical** output | That every other assertion is reproducible at all |
| **T14** | Rule divergence on non-periodic clusters | AASM index > WASM index on cluster nights; the two equal within ±2 % on a purely periodic night | That "two rule sets" are genuinely two, and not one rule with a different bound |
| **T15** | ALMA / hypnagogic foot tremor | **0 movements** attributed to those bursts | A real clinical distractor that would inflate the index |
| **T16** | Gain jump ×0.7 at mid-night | \|first-half index − second-half index\| **≤ 20 %** | That the noise floor adapts within a night |
| **T17** | Golden file | 5 min of real signal plus expected JSON, exact comparison of movements and index | Regression across refactors |
| **T22** | Nominal night, threshold policy | Fraction of `accelTruth` below `Θ_on`: **0.70 ± 0.07**. Raw retained count before any series rule: **0.30 ± 0.06**. Share of the true index that survives the threshold: **0.06 ± 0.03** | The under-count itself, as a published number. It is what makes T6's restricted denominator honest rather than a moved goalpost, and the middle row is the differential diagnosis of the last one |
| `RhythmMeasurementTest` | Rhythm under the real miss rate: nominal night, and the true train thinned at imposed rates. Unnumbered — it is not in the specification's table | Measurements, printed. Three **inverted** assertions: at most a quarter of fits are valid, the fundamental's error at least doubles between 30 % and 70 % missed, and the KS statistic *falls* as the miss rate rises | §4.3. The metric the product is actually built on, checked outside the regime the module was designed for |

T22 is numbered 22 and not 18 because [`fr/ALGO-v2.md`](fr/ALGO-v2.md) §5.5 already assigns T18–T21
to the four assertions described below, none of which is written yet. Reusing T18 would have created
a silent collision in a table several documents quote.

**One row of that table is a specification and not a test: T12 is not written.** The ±20 % sweep it
describes does not exist as an assertion; what exists are the two parameter sweeps of §4.1 and §4.4,
both `@Disabled` measurements run by hand rather than guards that go red. The distinction matters
because §4.1 uses one of those sweeps to contradict a figure that had been "awaiting T12" for the
project's whole history.

### T22 is a published number, not a pass/fail

There is nothing to repair when T22 goes red. It watches two quantities the project has to state out
loud, and its only job is that neither moves without somebody noticing. Both bands are two-sided for
the same reason: a rising under-count means the published number is drifting further from the true
one, and a falling one means the sub-threshold population has thinned — because the generator's
amplitude law changed, or because the threshold moved — in which case today's F1 is no longer
comparable with yesterday's. Neither direction is "better", so neither is left unguarded.

### T11 is inverted, and that is the point

T11 asserts that **without** calibration the spread must **exceed 40 %**. It is not a typo and it is
not a lower standard: it is a test that calibration is doing something.

The reasoning is that a guard rail whose removal changes nothing is not a guard rail. If a mechanical
gain change of ×0.6 to ×1.8 — a strap moved by one hole — did not move the index by more than 40 %
with calibration disabled, then calibrating would be pure ceremony. The failure mode this defends
against is a project that accumulates rituals because each of them sounds prudent.

> **What T11 now covers, and what it no longer does.** This paragraph used to end with *"if that
> assertion ever fails, the ritual must be removed, not the test"*, and referred to a guided 70-second
> ritual — 30 s still, ten metronome-paced dorsiflexions, 10 s calm — performed on the watch every
> evening. **That ritual was removed on 2026-08-05**, not because T11 failed but because it had never
> been wired: the function was written and tested, no production caller ever invoked it, and the watch
> screen that would have guided the user did not exist.
>
> T11 therefore now measures the only calibration that ships: `fromGrossBodyMovements`, which
> estimates the gain from body turns during the night. That is a **subdued** gesture rather than an
> imposed one, so it is a weaker standard than the one this test was written against. Two consequences
> worth stating plainly: inter-night comparability now rests on `GROSS_BODY` plus the instruction
> *same strap, same hole, same leg* — which v1 of this project called a wish rather than a solution —
> and the synthetic generator still renders an idealised ritual gain (`NightSynth.renderRitualGain`),
> so **the whole regression suite runs on a cleaner calibration than the application actually
> obtains**. Measuring that gap is open work; moving twenty thresholds to make it disappear is not.

Four further assertions were added after the ones above and follow the same numbering: truncated
nights must produce a rhythm but refuse an hourly index; the circularity test (also inverted, and for
the same reason as T11) must show that the sleep mask collapses when the anti-circularity layer is
disabled; injected format corruption must be rejected at 100 % with no healthy block rejected; and
the incremental pass must cover at least 90 % of the movements found by the definitive pass. They are
specified in [`fr/ALGO-v2.md`](fr/ALGO-v2.md) §5.5.

---

## 4. Current status, stated plainly

**148 tests in the `algo` module. 145 run and all 145 pass**; the other three are long-running
parametric measurements kept `@Disabled` and run by hand. **T6 no longer fails — because its denominator was changed, deliberately and with the
measurement in hand.** §4.1 is the whole account: what was measured, what it overturned, what was
decided, and what is now known to be wrong elsewhere in this repository as a result.

The short version, because the long one is easy to lose: on the events above its own onset threshold,
the detector reaches F1 = 0.908 and meets every T6 criterion. Below that threshold sits **70 % of the
mechanically visible truth**, and once the AASM four-in-a-row series rule is applied, only **6 %** of
the true index survives. T6 now scores the first sentence; **T22 publishes the second**, and the second
is the one that matters clinically. Restricting T6's denominator without T22 beside it would have been
a moved goalpost; the pair is what makes it a measurement.

**§4.3 is the harder half, and it is newer.** The hourly index is not the metric this project intends
to publish day to day — the fundamental rhythm in seconds is. Measured for the first time under the
miss rate the detector actually produces, the rhythm is wrong by 11 % where it answers at all, and the
deconvolution refuses to answer on 18 nights out of 20. The refusal is the part that works.

**§4.4 asks whether one parameter can undo all of it, and answers no.** `calFraction` sets the
threshold on 99 % of the night, and sweeping it improves everything — valid rhythm fits go from 2/20
to 11/20, the published index from 5 % of truth to 47 %, with no measured cost in precision. But it
**saturates against `Θ_abs` = 20 mg** before the miss rate reaches 0.50, stopping at 0.546. Under
`Θ_abs` lies Terrill's mechanical 39 %, which no parameter reaches. The margin available to the entire
threshold policy is a miss rate between 0.39 and 0.55, and identifiability needs below 0.50.

**§4.5 re-runs the whole suite at the recommended value so the recommendation arrives complete.** At
`f_cal` = 0.06, **142 of 145 tests pass**. None of the artefact defences — T1 to T4 — is among the
three, which is the result that mattered. Of the three, one is a tripwire on a published constant
doing its job, one is an inverted assertion whose failure is the good news, and one is a genuine
regression in a *relative* fidelity bound that coincides with a ninefold improvement in the *absolute*
number. **The default is unchanged**; the decision goes to whoever owns it, with the list in hand.

The previous edition of this section stopped one step short. It correctly identified that most truth
events sit below `Θ_on`, and then attributed that threshold to the relative term `k_on · floor`, with
`k_on = 8.0` described as an anti-artefact budget carried over from v1 "for want of anything better".
A parametric sweep of `k_on`, which phase P7 had specified and nobody had written, shows that
attribution to be **wrong**: on a calibrated night `Θ_on` does not depend on `k_on` at all.

### 4.1 T6 — the denominator was the open question, and the sweep that answered it overturned the diagnosis as well

This section has been rewritten twice. The first edition blamed a **doubling** of candidates from the
generator's static plateau and reasoned to a ceiling of F1 ≤ 0.76; the measured value was 0.267, and
a number half the size of its own predicted ceiling should have been read as evidence that the account
was incomplete. The second edition found the real remainder — most truth events sit below the
detector's onset threshold — left the decision explicitly open, and named `k_on` as the parameter
responsible. **That last attribution is now measured, and it is wrong.** This edition records the
measurement, the decision it supports, and the three places in this repository that the measurement
falsifies.

#### What the plateau actually did — and what removing it bought

Measured on two seeds of the nominal night, before and after the model correction described in §5.5:

| | Static plateau (before) | Sustained hold (after) |
|---|---|---|
| Median detected duration | **1.14 s / 1.22 s** | **4.58 s / 4.50 s** |
| Median true duration | 4.09 s / 4.09 s | 4.09 s / 4.09 s |
| Raw candidates vs 237 / 219 truth events | 226 / 159 | 176 / 119 |
| Rejected on morphology | 28 / 27 | **0 / 0** |
| Precision | **0.485 / 0.479** | **0.924 / 0.860** |
| F1, that seed | 0.294 / 0.234 | 0.462 / 0.355 |
| **F1, median over 20 seeds** | **0.267** | **0.424** |

The detected duration is the line that matters: the detector was not emitting *two* candidates per
movement, it was emitting roughly **four**, each about 1.1 s long, and a quarter of those then died
on the morphology criterion because a 1.1 s fragment barely contains one 0.5 s window. That is why
precision sat at 0.48 and why the measured F1 was half the doubling ceiling. After the correction the
detected duration matches the truth to within 12 %, morphology rejects nothing, and precision is
0.86–0.92. **The generator's plateau was a real defect and it is gone.**

#### 70 % of `accelTruth` is below the detector's own onset threshold

The remaining gap is not precision, it is recall. On the nominal night the onset threshold
`Θ_on = max(k_on·floor, Θ_abs, f_cal·gainCal)` sits at **53.7 mg**. The coarse-envelope peaks of the
4 716 `accelTruth` events across the 20 seeds are distributed like this:

| p10 | p25 | **p50** | p75 | p90 |
|---|---|---|---|---|
| 17.8 mg | 25.6 mg | **38.7 mg** | 58.5 mg | 85.3 mg |

The **median event in the truth set is at 0.72 × the detection threshold**, and the per-seed medians
span only 34.8–42.8 mg, so this is a property of the generator's amplitude law and not of one draw.
Restricting the truth set to the events the detector is actually configured to find (medians over the
20 seeds):

| Truth set | n | Sensitivity | Precision | **F1** |
|---|---|---|---|---|
| `accelTruth`, all (visibility cut 8 mg) | 235 | 0.276 | 0.917 | 0.424 |
| `accelTruth` ∩ envelope ≥ `Θ_abs` (20 mg) | 205 | — | — | 0.482 |
| `accelTruth` ∩ envelope ≥ `Θ_on` (53.7 mg) | 72 | **0.908** | 0.903 | **0.908** |

**On the events above its own threshold, the detector meets the criterion.** The entire shortfall is
the population between the generator's 8 mg visibility cut and the detector's 53.7 mg onset threshold.

The restricted denominator is not a friendlier scoring rule in disguise: it is *conservative on
precision*. A detection that correctly answers a 40 mg event — below the threshold, but found anyway —
loses its counterpart in the restricted truth and becomes a false positive. That is why precision
falls slightly, from 0.917 to 0.903, when the denominator is tightened.

#### The sweep phase P7 specified, and the two things it showed

[`01-overview.md`](01-overview.md) §5 lists "a parametric sensitivity curve" as a P7 deliverable. It
did not exist, so the decision above would have been taken on a single value of `k_on`. It exists now
(`ThresholdPolicySweepTest`, `@Disabled` for its 24-minute runtime): `k_on` swept over [4, 12] in
steps of 1.0, 20 seeds each, reporting F1 under all three denominators **and** the three T5 criteria,
because T5 states its measurement points as multiples of the effective floor `Θ_on / k_on` and
therefore moves when `k_on` moves.

| `k_on` | `Θ_on` | F1 all | F1 ≥ `Θ_abs` | Se ≥ `Θ_on` | Pr ≥ `Θ_on` | **F1 ≥ `Θ_on`** | below `Θ_on` | Se 4× | Se 8× | Se 16× | T5 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 4 | 53.7 mg | 0.395 | 0.445 | 0.900 | 0.768 | 0.819 | 0.697 | 0.250 | 1.000 | 1.000 | no |
| 5 | 53.7 mg | 0.398 | 0.450 | 0.905 | 0.817 | 0.852 | 0.697 | 0.000 | 1.000 | 1.000 | no |
| 6 | 53.7 mg | 0.405 | 0.459 | 0.901 | 0.853 | 0.870 | 0.697 | 0.000 | 1.000 | 1.000 | no |
| 7 | 53.7 mg | 0.412 | 0.467 | 0.904 | 0.862 | 0.884 | 0.697 | 0.000 | 1.000 | 1.000 | no |
| **8** | 53.7 mg | 0.424 | 0.482 | 0.908 | 0.903 | **0.908** | 0.697 | 0.000 | **0.407** | 1.000 | **yes** |
| 9 | 53.7 mg | 0.422 | 0.480 | 0.910 | 0.917 | 0.911 | 0.697 | 0.000 | 0.000 | 1.000 | no |
| 10 | 53.7 mg | 0.425 | 0.479 | 0.910 | 0.924 | 0.912 | 0.697 | 0.000 | 0.000 | 1.000 | no |
| 11 | 53.7 mg | 0.428 | 0.482 | 0.905 | 0.945 | 0.918 | 0.697 | 0.000 | 0.000 | 1.000 | no |
| 12 | 53.7 mg | 0.423 | 0.470 | 0.899 | 0.956 | 0.921 | 0.697 | 0.000 | 0.000 | 1.000 | no |

**First: the `Θ_on` column does not move.** 53.7 mg from `k_on` = 4 to `k_on` = 12, and with it the
sub-threshold fraction, frozen at 0.697. On a *calibrated* night it is the third term,
`f_cal · gainCal`, that sets the threshold — measured, it dominates **99 % of the night**. The
"effective noise floor of 6.7 mg" quoted in the previous edition was never a noise floor: it was
`Θ_on / k_on` = 53.7/8, which is the same number by construction and says nothing about the noise.
The real median floor is below 4.5 mg, which is why the relative term never reaches the threshold
anywhere in the swept range. **`k_on` is not the parameter behind the under-count. `calFraction` is.**

**Second: T5 holds at `k_on` = 8.0 and nowhere else** — sensitivity at the "8×" point is 1.000 below
and 0.000 above, with no plateau in between. But the reason has to be stated in full, because it is
not to T5's credit. T5's abscissa is the effective floor `Θ_on / k_on`; on its quiet, uncalibrated
probe night it is `Θ_abs` that sets the threshold, so "8× the effective floor" evaluates to
`8 · Θ_abs / k_on`, which equals the threshold **if and only if `k_on` = 8**. T5's middle criterion —
"[0.35, 0.65] at 8× the floor (the threshold, by construction)" — is therefore an identity at 8.0 and
a comparison against the wrong amplitude everywhere else. **T5 does not validate `k_on` = 8.0; it
encodes it in its own statement.** That is a reason not to move `k_on` without rewriting T5. It is not
evidence that 8.0 is right.

What `k_on` *does* change on a calibrated night is visible in the precision column: 0.768 → 0.956
while sensitivity stays flat at ~0.90. That is the gross-body-movement criterion, whose reference
amplitude is `Θ_on / k_on` — a larger `k_on` means a smaller reference, a lower GBM bar, and more
false candidates rejected. `k_on` acts here as a gross-movement rejection setting, which is the
opposite of how the parameter table reads it.

#### The decision

**T6 now scores against `accelTruth` ∩ envelope ≥ `Θ_on`, on every one of its criteria, and it passes.**
The restriction was extended from F1 to the index error for a reason that only became visible once the
F1 assertion stopped failing first: against the full `accelTruth`, the **relative index error is 0.95** —
the published count falls to about 5 % of the true one. That is not a detection defect. It is the same
sub-threshold population passed through the AASM series rule, which requires **four consecutive** CLM:
removing 70 % of the events does not divide the count by three, it deletes most series outright.
AssertJ stops at the first failed assertion, so this number had been invisible for the project's whole
history behind the F1 line above it.

**That is exactly the number T22 now publishes**, and publishing it is the condition that makes the
restriction honest rather than a moved goalpost. T22 asserts two bands over the 20 seeds:

| Published quantity | Median | Per-seed range | Band | What a move would mean |
|---|---|---|---|---|
| Fraction of `accelTruth` below `Θ_on` | **0.697** | 0.655 – 0.784 | 0.70 ± 0.07 | The threshold policy or the generator's amplitude law changed |
| Share of the true index that survives `Θ_on` | **0.061** | 0.000 – 0.148 | 0.06 ± 0.03 | The same, amplified by the series rule — the clinically important one |

The second row's per-seed range reaching zero is not a flaw in the measurement: on some nights every
series loses at least six of its nine movements and no four-in-a-row survives anywhere. The band is
asserted on the median for that reason, and it is stated absolutely rather than relatively because a
relative band on a quantity that legitimately touches zero would be meaningless.

`k_on` stays at **8.0**, and its KDoc now says why in the terms above rather than "an anti-artefact
budget carried over for want of anything better": moving it would buy nothing (the threshold does not
depend on it here) and would break T5 (which pins it by construction). Both halves are needed; either
one alone would be misleading.

The CI exception that tolerated a failing test named `T6` has been removed. The guard that fails the
job when Gradle produces no report at all is kept — that one was never about T6.

#### What this measurement falsifies elsewhere in the repository

Recorded here rather than fixed silently, per the rule in §5.4:

- [`03-algorithm.md`](03-algorithm.md) §8.3 lists `kOn` as "**the dominant parameter**, 10–15 %" effect
  at ±20 %. On a calibrated night the measured effect on `Θ_on` is **zero** across [4, 12]; what
  residual effect exists runs through the GBM criterion, not the threshold. The figure was an
  engineering estimate awaiting T12, and T12 is still unwritten, but the sweep already contradicts it.
- The same table's justification for `kOn` — "carried over from the first specification for want of
  better evidence" — is superseded by the KDoc of `ThresholdConfig`.
- This document's own previous edition described `Θ_on` as "dominated by its relative term". It is
  dominated by the calibration term, 99 % of the night.

#### What remains open

- **`calFraction` = 0.12 has never been swept, and it is the parameter that governs the under-count.**
  It is described in §8.3 as "12 % of a comfortable voluntary dorsiflexion — an engineering choice, no
  published equivalent". It sets the published index on every calibrated night. That sweep is the
  obvious next P7 deliverable, and the harness for it now exists.
- **T5's abscissa should arguably be `Θ_on` itself rather than `Θ_on / k_on`.** As written, T5 cannot
  measure anything about a `k_on` other than 8.0. Rewriting it would decouple the two and make the
  sweep informative in a way it currently is not. It is not done here because T5 transcribes a
  published statement of the specification, and changing what a test asserts in the same change that
  moves another test's denominator is one goalpost too many.
- ~~**The two T22 numbers do not yet reach the physician's report.**~~ **Closed.** All three now do:
  `ReportExporter` carries a section stating the sub-threshold fraction (0.70), the raw retained
  count (0.30) and the share of the true index that survives (0.06), together with the sentence the
  third number exists for — that a low figure in this report cannot be read as "few movements",
  because discarding 70 % of the events does not dilute the series, it destroys them. The constants
  are transcribed rather than imported, since they live in a test source the application module does
  not compile; T22 is what guards them against drifting apart.

Confidence: **high** for the measurements (direct, 20 seeds, both the sweep and T22 reproducible on
demand). **High** for the decision on T6's denominator, conditional on T22 existing beside it — without
that, the same change would be indefensible. **Medium** for the claim that `calFraction` is the right
next lever: it follows from the dominance measurement, but nobody has swept it.

### 4.2 T5 — the three stated criteria now pass; the guard rail beside them does not

T5 asserts that sensitivity is ≤ 0.05 at 4× the effective floor, in [0.35, 0.65] at 8×, and ≥ 0.95 at
16×. All three now hold: **0.000 / 0.407 / 1.000** over the 20 seeds — though §4.1 shows that the
middle one holds at `k_on` = 8.0 by construction of its own abscissa, which is a limitation of the
assertion rather than a property of the detector. The previous diagnosis — that
grazing bursts of about 0.6 s died on the morphology criterion — was correct in mechanism and is
resolved by the same model correction, for the same reason as T6: an event that lasts its full
published duration instead of fragmenting into 1.1 s pieces has room for the 0.5 s morphology window.
That diagnosis had been offered with "lower confidence than the T6 one"; it turned out to share the
T6 cause exactly.

What failed after that fix was the fourth assertion in the same test, a monotonicity guard on the
aggregated sensitivity curve, and it failed on **sampling noise**. The T5 sweep places every event at
exactly 4×, 8× or 16× the effective floor, so the three real bins hold about 1 080 events each and the
intermediate bins receive only the residue of the amplitude calibration: measured, **3 events out of
3 240** in the bin [6, 8), and 1 in [3, 4). The 3-event bin reported 2/3 and vetoed monotonicity
against a neighbouring bin of 1 077 events. `Scoring.sensitivityCurve` now takes a `minCount` and the
test passes 20, which is not a weakening of the assertion: comparing a rate estimated on three draws
to one estimated on a thousand tests the draw, not the detector.

### 4.3 The rhythm — the metric the product is actually built on, measured for the first time under the real miss rate

§4.1 ends with the index at 6 % of truth. That would not by itself sink the project, because the
**index is not the metric Pendulum publishes for day-to-day tracking**. That metric is the fundamental
rhythm in seconds, recovered by harmonic deconvolution (`Rhythm`), and the whole argument for it is
that it has no denominator and a night-to-night variability of 3.6 % against 43.2 % for the hourly
count — twelve times less. `Rhythm`'s own KDoc states an identifiability limit in passing (the
fundamental component carries weight `1 − p`) but never says where it breaks. Nobody had measured it,
and the detector operates far outside the regime the module was designed for.

#### First, the differential diagnosis: it is the series rule, not the detector

Before asking about the rhythm, T22 now publishes a third quantity: the **raw retained count**, before
any series rule, against the whole of `accelTruth`. Measured, median over 20 seeds:

| Quantity | Median | Per-seed range |
|---|---|---|
| Raw retained count / `accelTruth` | **0.301** | 0.225 – 0.349 |
| Index surviving the threshold | 0.061 | 0.000 – 0.148 |

0.30 raw against 0.06 on the index settles it. At a detection probability of 0.30 and a 22 s rhythm,
a perceived interval survives the 90 s bound with probability `p + (1−p)p + (1−p)²p = 0.657`; a
four-movement series needs three consecutive such intervals, `0.657³ = 0.283`; total `0.30 × 0.283 =
0.085`. Measured 0.061 — the same number to within the variance, and slightly worse because misses are
not independent of amplitude. **The AASM four-in-a-row rule acts as an exponential suppressor, not as
a diluter.** The generator and the denominator are exonerated; nothing else needs looking for.

#### Then the measurement that matters: the rhythm on the nominal night, 20 seeds

| Quantity | Median | Per-seed range |
|---|---|---|
| Injected fundamental | 22.13 s | 21.36 – 23.31 s |
| Estimated fundamental | 20.25 s | **16.69 – 26.03 s** |
| **Relative error on the fundamental** | **0.113** | 0.003 – 0.243 |
| `missRate` estimated | 0.835 | 0.434 – 0.900 |
| True miss rate, EMG train | 0.830 | 0.781 – 0.869 |
| True miss rate, accelerometric train | 0.727 | 0.660 – 0.777 |
| Intervals available to the fit | 32.5 | 17 – 45 |
| **Fits declared valid** | **2 / 20** | — |
| `alternationSuspect` raised | **14 / 20** | — |

Four readings, and only one of them is good news.

- **`missRate` is estimated well.** 0.835 against a true 0.830 on the EMG train. The deconvolution
  really does measure what it claims to measure, at a rate more than twice the one it was calibrated
  for. That is a genuine result and it deserves saying before the rest.
- **The fundamental does not survive.** Median error 11.3 %, up to 24.3 %. The injected value spans
  21.4–23.3 s across seeds — ± 4 % — while the estimate spans 16.7–26.0 s, ± 23 %. **The estimator
  adds five times more dispersion than the quantity it is estimating has.** The "3.6 % night-to-night
  variability" that justifies making this the headline metric is not reachable from here; it is not
  that the number is unstable, it is that it is wrong by three times the effect it is meant to track.
- **The module refuses, and that is the part that works.** 2 fits out of 20 are declared valid.
  Reject reasons: 5 `TOO_FEW_INTERVALS`, 5 `MISS_RATE_SATURATED`, 4 `DISTRIBUTION_MISFIT`,
  2 `GEOMETRIC_MISFIT`, 2 `NOT_CONVERGED`. The claim in `Rhythm`'s KDoc — invalidate rather than
  return a confident wrong number — holds on this data. **The product does not report a false rhythm;
  it reports nothing, on 90 % of nights.**
- **`alternationSuspect` is a false positive here, and a clinically loaded one.** It fires on 14 of
  20 seeds. There is no lateral alternation anywhere in the generator; the flag is calibrated to
  separate a true miss rate of 0.39 from one of 0.50, and at 0.83 it is meaningless. It says
  "movements may be alternating between the legs", which is a clinical statement, on the basis of a
  miss rate produced entirely by an amplitude threshold.

#### Where the deconvolution breaks, on the model's own best case

Same 20 nights, but the detector is removed: the true movement train is thinned with an imposed,
independent, exactly geometric miss probability — the model's ideal assumptions — and refitted.
Anything that degrades here is a **floor** on the real degradation, not an estimate of it.

| Imposed `p` | Relative error | `p` estimated | `σ` | `geometricMisfit` | KS | Valid | `alternationSuspect` |
|---|---|---|---|---|---|---|---|
| 0.00 | 0.067 | 0.098 | 0.392 | 0.023 | 0.116 | 0/20 | 0/20 |
| 0.10 | 0.047 | 0.134 | 0.425 | 0.014 | 0.104 | 0/20 | 0/20 |
| 0.30 | **0.031** | 0.213 | 0.437 | 0.006 | 0.089 | 2/20 | 0/20 |
| 0.50 | 0.083 | 0.333 | 0.501 | 0.004 | 0.079 | 3/20 | 4/20 |
| 0.70 | **0.201** | 0.777 | 0.439 | 0.013 | 0.071 | 4/20 | 18/20 |

**The break is between 0.5 and 0.7**, which confirms the "not identifiable much beyond 50 % missed"
reading — and the detector sits at 0.73–0.83. The error is 3.1 % at `p` = 0.30, the regime the module
was designed for, so the method is sound where it was meant to be used.

Two further things fall out of this table, and neither was expected.

- **`p` is under-estimated wherever it is not saturated** — 0.098 for a true 0.00, 0.213 for 0.30,
  0.333 for 0.50 — the opposite direction from the `+0.03` upward truncation bias `RhythmConfig`
  documents. The residual at `p` = 0 comes from the isolated and respiratory-related movements
  interleaved with the series, which are genuine intervals the mixture has to absorb somewhere.
  `alternationMinMissRate = 0.48` is calibrated on the wrong sign of bias.
- **The two adequacy statistics move the wrong way.** `geometricMisfit` and KS both *fall* as the
  miss rate rises — KS from 0.116 to 0.071 — while the error rises fourfold. More is accepted at
  `p` = 0.70 (4/20) than at `p` = 0.00 (0/20), where the estimate is three times more accurate.
  **The guard rail is anti-correlated with the error it exists to guard.** The mechanism is
  understandable in hindsight: thinning spreads the interval distribution out, and a wider, smoother
  histogram fits a wide log-normal mixture *better*, whatever it does to the location of the mode.
  Two inverted assertions in `RhythmMeasurementTest` now pin both of these facts, so that whoever
  fixes them is told to come back and rewrite this section.

#### What an adequacy statistic would have to be instead — proposed, not decided

The diagnosis is one sentence: **KS and `geometricMisfit` measure goodness of fit, and what needs
bounding is the identifiability of `μ`.** Those are different quantities, and at high `p` they move in
opposite directions. A mixture with a free `σ` can absorb almost any smooth spread of intervals; what
degrades is not how well the model matches the histogram but how sharply the likelihood pins the
location of the fundamental. Three candidates, in the order I would try them:

1. **The curvature of the profile log-likelihood in `μ`.** Its inverse is a standard error on `μ`,
   which converts directly into a confidence interval on `fundamentalSec`. This is the principled
   answer and it changes the shape of the output for the better: instead of a boolean `valid`, publish
   `fundamentalSec ± CI` and refuse when the interval is wider than the effect the metric claims to
   track — the 3.6 % night-to-night variability that justifies the metric in the first place. It is
   also the quantity that provably degrades as the fundamental component loses weight.
2. **A likelihood-ratio against the neighbouring harmonics.** The specific failure is the EM latching
   onto the wrong peak and reporting 2× or ½× the true period. Refit with `μ` pinned at `μ + ln 2` and
   `μ − ln 2` and compare log-likelihoods; a small margin means the mode is not identified. It targets
   the actual failure mode, and it costs two extra EM runs on a grid the module already builds.
3. **Split-half stability of `exp(μ)`.** Fit the first and second half of the night's intervals
   separately and compare. Non-parametric, needs no new theory, and it measures reproducibility —
   which is precisely the property the product claims. Two extra fits.

The existing statistics should stay as *diagnostics*; they are informative about the shape of the
miss process, which is what they were built for. What should stop is using them as the gate.
`TOO_FEW_INTERVALS` and `MISS_RATE_SATURATED` are doing all the useful refusing today and should be
kept: they are capacity guards, and capacity is genuinely what is short.

**Not decided here.** Which of the three, and where the acceptance bound goes, determines how often
Pendulum shows a number at all — a product decision with a clinical edge, and the same class of call
as T6's denominator. It is written down instead.

#### What this means, and what is deliberately not decided here

The honest summary is that **the two things the project publishes both fail on the nominal night, and
they fail differently.** The index collapses to 6 % of truth and says so through T22. The rhythm is
wrong by 11 % when it answers, and refuses to answer on 18 nights out of 20. Only the refusal is
working as designed.

The chain of causation runs end to end and through a single parameter: `calFraction` → `Θ_on` → a miss
rate of 0.73–0.83 → 30 % raw detections → 6 % of the index through the four-in-a-row rule, and 11 %
error on the rhythm. §4.4 sweeps that parameter, which is the only way to find out whether the chain
can be broken.

`calFraction`, the series rule and `RhythmConfig`'s acceptance thresholds are all left exactly as they
were, for the same reason §4.1 refused to move T6's denominator before the sweep existed. One thing
*was* changed, and only because it is not a calibration question: see §4.4 on `alternationSuspect`.

Confidence: **high** for the measurements — 20 seeds, deterministic, reproducible, and the thinning
experiment removes the detector entirely. **High** for the differential diagnosis of the index
collapse: the arithmetic predicts 0.085 and the measurement gives 0.061.

### 4.4 Sweeping `calFraction` — the answer is no, and the reason matters more than the answer

`calFraction` is swept over [0.03, 0.12] on the nominal night with all twelve distractor families
active, 20 seeds per value, medians. `p_true` is the miss rate on the EMG series train — the abscissa
of §4.3's breakage curve. Precision and false-positive count are measured against the whole of
`accelTruth`, because a lower threshold letting artefacts in is exactly the counterpart to watch for.

| `f_cal` | `Θ_on` | below | raw | `p_true` | `p` est. | idx ideal | idx measured | Precision | FP | rhythm err | valid | T5 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.03 | **20.0 mg** | 0.137 | 0.727 | **0.546** | 0.485 | 0.806 | 0.592 | 0.976 | 4 | 0.083 | 7/20 | yes |
| 0.04 | **20.0 mg** | 0.137 | 0.727 | **0.546** | 0.485 | 0.806 | 0.592 | 0.976 | 4 | 0.083 | 7/20 | yes |
| 0.06 | 26.8 mg | 0.272 | 0.641 | 0.598 | 0.532 | 0.591 | 0.468 | 0.983 | 3 | 0.066 | **11/20** | yes |
| 0.08 | 35.8 mg | 0.449 | 0.498 | 0.694 | 0.652 | 0.316 | 0.249 | 0.976 | 3 | 0.062 | **11/20** | yes |
| 0.10 | 44.7 mg | 0.593 | 0.385 | 0.773 | 0.789 | 0.120 | 0.102 | 0.945 | 6 | 0.043 | 2/20 | yes |
| **0.12** | 53.7 mg | 0.697 | 0.301 | 0.830 | 0.835 | 0.061 | 0.052 | 0.917 | 7 | 0.113 | 2/20 | yes |

#### The direct answer: no, and it stops at 0.546

**There is no value of `calFraction` that brings the miss rate under 0.50.** The best reachable is
**0.546**, and it is reached at `f_cal` ≈ 0.045 and does not improve below it. The reason is in the
`Θ_on` column: at `f_cal` = 0.04 the calibration term is 0.04 × 447 mg = 17.9 mg, which falls *below*
`Θ_abs` = 20 mg, and the absolute floor takes over. **`calFraction` saturates against `Θ_abs`.** Below
that point the parameter is disconnected from the threshold entirely — rows 0.03 and 0.04 are
identical to the last digit, which is the signature of a term that has stopped mattering.

So the attenuation is only partly parametric. Underneath `calFraction` there are two further floors,
and both are structural rather than tuned:

- **`Θ_abs` = 20 mg**, which cuts 13.7 % of `accelTruth` on its own and exists to stop the detector
  counting micro-vibration on a very quiet night — T1's whole purpose;
- **Terrill's 39 %**, the movements that are mechanically invisible to an accelerometer at any
  threshold. `p_true` can never go below 0.39 by any amount of tuning, and with `Θ_abs` in place the
  measured floor is 0.546.

  > **This 39 % was measured at the great toe, not at the ankle** (Terrill 2013, n = 9, range
  > 4.8–69.6 % — see `references.md`). The transposition is optimistic, and knowingly so: the toe
  > has the largest effective radius of the limb about the talocrural joint, an ankle-worn sensor
  > sits above that axis and has close to none. **The real floor at the ankle is higher than
  > 0.39**, by an unmeasured amount. The conclusion drawn below — that mechanics impose
  > `p ≥ 0.39` and leave a margin of 0.39 to 0.50 — therefore rests on a lower bound that is
  > certainly too low. If the true ankle floor exceeds 0.50, **the margin does not exist and the
  > deconvolution is never identifiable**. This section is open, not settled.

That is the "conclusion of a different weight": **at the current `Θ_abs`, the miss rate cannot be
brought into the region where the deconvolution is identifiable.** Getting there means moving
`Θ_abs` too, and `Θ_abs` is guarding a different failure — a detector that invents movements out of
thermal noise.

#### The expected trade-off does not appear, and that is a result in itself

Precision was supposed to be the price. It is not: it goes **0.917 → 0.976** as `f_cal` falls, and the
false-positive count goes **7 → 3**. Lowering the threshold made precision *better*. The reading is
that on this generator the amplitude threshold is not what defends against the twelve distractor
families — the morphology criterion, the posture detector and the gross-movement classifier are, and
they keep working when the threshold drops, while the extra detections that come in mostly have a real
counterpart to match.

**This must not be read as a clean bill of health, and the sweep does not establish one.** Precision
here is measured on the nominal night. The dedicated artefact tests — T1 (MEMS noise alone), T2
(respiration), T3 (mattress vibration), T4 (posture) — were **not** re-run at other `calFraction`
values, and T1 and T3 are the ones that would actually break. Re-running T1–T4 across the sweep is the
required next step before anyone changes the default, and it is cheap.

#### What the rhythm does about it

The best operating point for the metric the product is built on is `f_cal` ≈ 0.06–0.08: **11 valid
fits out of 20** against 2 today, with the fundamental's error around 0.062–0.066 against 0.113. The
index follows too — the measured index rises from 5 % of truth to 25–47 %.

Even at its best, that is a headline number available on **just over half of nights**, with a residual
error near 6 % against a metric whose entire claim is a 3.6 % night-to-night variability. `calFraction`
moves everything in the right direction and moves nothing far enough.

**T5 is unaffected at every value** — 0.000 / 0.407 / 1.000 throughout, all three criteria holding.
That was expected, because T5 runs with calibration disabled and `f_cal` is inert there by
construction, but expecting it and checking it are different things and the check was nearly free.

#### The one thing that was changed: `alternationSuspect`

This was not a calibration question, which is why it is the exception. The flag asserted something
clinical — *movements may be alternating between the legs* — from a half-line condition, `p ≥ 0.48`,
with no upper bound. At a miss rate of 0.83 caused entirely by an amplitude threshold, that condition
was satisfied for a reason having nothing to do with lateralisation, and the flag fired on **14 nights
out of 20** where the generator contains no alternation whatsoever. It was the only output of the
system that was actively wrong rather than merely absent.

`RhythmConfig` now carries `alternationMaxMissRate = 0.65`, making the condition a band. The value is
not chosen to pass a test: it is the one the module already stated two paragraphs earlier — beyond
`p` = 0.65 the truncated tail weighs 7.5 % and "the estimate of `p` is no longer reliable". A flag
cannot rest on a quantity the module itself declares unreliable. The count falls from 14/20 to **1/20**,
and `RhythmTest`'s existing assertion that a genuine stochastic lateralisation *does* raise the flag
still passes.

**It does not fix the flag, and the sweep is what shows why.** At `f_cal` = 0.06 — precisely where the
miss rate approaches 0.5 — the flag climbs back to **11/20**, still with no alternation anywhere. This
is not a tuning residue. `p` and the fundamental's share are *the same* whether half the movements are
missing because they fall under the threshold or because they are on the other leg; the module sees
only intervals, and the one quantity that would separate the two causes is the amplitude of what was
detected — lateralisation is amplitude-blind, a threshold is not. Symmetrically, at the miss rate the
flag exists for (0.50) it fires on only 3 of 20 realistic trains, because a train mixing series with
isolated and respiratory-related movements reads `p` = 0.333, under the 0.48 lower bound.

**False positive on one side, nearly blind on the other.** Three assertions in `RhythmMeasurementTest`
hold the measured ceilings so the state cannot drift unnoticed, and the flag is left as it is.

The reason it is left alone is worth stating precisely, because it is not caution: **the fix is an
interface change between two stages, not a setting.** `Rhythm` receives a list of intervals. The one
quantity that separates the two causes — the amplitude of the events that were detected, since a
lateralisation is amplitude-blind and a threshold is not — exists in `Clm.peakAmpG` and is discarded
at the boundary, by `Rhythm.intervalsOf`, which keeps only onsets. Making the flag correct therefore
means deciding that the rhythm estimator is entitled to amplitude, which changes what `indices`
depends on and what the incremental path has to carry. That is an architectural call with a clinical
consequence, and it belongs to whoever owns the stage boundary, not to a measurement commit.

#### What this supports

1. **`f_cal` ≈ 0.06 is the value to argue about**, not 0.12: it multiplies valid rhythm fits by five
   and the published index by nine, at no measured cost in precision on the nominal night. §4.5 is the
   full list of what it breaks, which is what such a recommendation has to arrive with.
2. **`Θ_abs` is the next question, and it is a harder one.** It is what stops the miss rate reaching
   the identifiable region, and it is guarding T1. That is a genuine conflict between two guard rails
   rather than a parameter with a free value.
3. **Below both of them sits Terrill's 39 %, which no parameter reaches.** If the identifiability
   requirement really is `p` < 0.50, and mechanics alone impose `p` ≥ 0.39, the margin available to
   the whole threshold policy is 0.39 to 0.50. That is the number that should frame any further
   discussion of what this device can measure at the ankle.

Confidence: **high** for the sweep itself. **High** for the saturation against `Θ_abs`, which is
visible as two identical rows. **High** for the non-identifiability of `alternationSuspect`, which is
an argument from what the module can and cannot see, confirmed at two separate operating points.

### 4.5 What `f_cal` = 0.06 actually breaks — the whole suite, re-run

A recommendation to move a parameter arrives with the list of what it costs, or it is half an
argument. The whole `algo` suite was re-run under `f_cal` = 0.06 and 0.08 — not only T1–T4, because
if something else breaks it is better to know now. The default is untouched; `RegressionSupport`
reads `-Palgo.calFraction` and falls back to the shipped value, so `./gradlew :algo:test
-Palgo.calFraction=0.06` reproduces the table below.

**Result: 142 of 145 pass at both values, against 145 today. The same three fail at both, and the
artefact defences are not among them.**

| | `f_cal` = 0.06 | `f_cal` = 0.08 | 0.12 (shipped) |
|---|---|---|---|
| **T1** MEMS noise, 0 CLM worst case | **pass** | **pass** | pass |
| **T2** respiration, 0 CLM | **pass** | **pass** | pass |
| **T3** 300 mattress rings, ≤ 2 CLM | **pass** | **pass** | pass |
| **T4** posture, 0 CLM + recall ≥ 0.95 | **pass** | **pass** | pass |
| **T5** sensitivity curve | **pass** | **pass** | pass |
| **T6** index error ≤ 0.10 | **fail, 0.193** | **fail, 0.170** | pass |
| **T7** negative night ≤ 5/h | **pass** | **pass** | pass |
| **T22** sub-threshold band | **fail, 0.272** | **fail, 0.449** | pass |
| Rhythm, valid fits ≤ 5/20 (inverted) | **fail, 11/20** | **fail, 11/20** | pass |
| everything else (139 tests) | pass | pass | pass |

Each of the three deserves its own reading, because they are three different kinds of failure and only
one of them is a regression.

**T22 fails as a tripwire, and that is what it is for.** Its three bands are the numbers the product
publishes — the fraction of movements the threshold discards, and what survives of the index — measured
at the shipped default. **They should stay constants and must not become a function of `calFraction`.**
A band that followed the parameter would make the project's most important honesty metric silently
self-adjusting: change the threshold, and the published under-count would re-centre itself on whatever
the new policy produces, with no test going red. That is exactly the moved goalpost T22 was created to
prevent. The correct behaviour when the default moves is for T22 to fail, for someone to re-measure,
and for the new numbers to be written down deliberately. At 0.06 they would become 0.27 / 0.64 / 0.59
against today's 0.70 / 0.30 / 0.06.

**The rhythm assertion fails because the situation improved, and it was written to.** `valid fits ≤ 5`
is an inverted assertion in the style of T11: its KDoc says that the day it fails, either the chain
finally detects enough movements or the guard was relaxed, and either way §4.3 must be reopened. It
fails with **11 valid fits out of 20** against 2 today. **This is the single strongest signal in the
matrix in favour of the change**, and it arrives dressed as a red test on purpose.

**T6 is the only genuine regression, and it is a real tension rather than a tautology.** The question
worth asking of every failure here — the one T5 turned out to answer badly for `k_on`, where the test
pinned the parameter by construction of its own abscissa — is whether T6 encodes the old threshold.
It does not: the expected index is recomputed against `Θ_on` as it stands (`truthResult(rule,
visible)`), so the criterion follows the parameter. What degrades is the detector's fidelity **to its
own policy**: at a lower threshold the marginal events just above it are precisely the ones detected
about half the time, so the measured index tracks the ideal-detector index less well — 0.19 against a
bound of 0.10.

And here is the tension, stated as plainly as it deserves: over the same move, the measured index goes
from **5 % of the true index to 47 %**. T6 bounds *relative* fidelity to the threshold policy; the
product cares about *absolute* accuracy; at `f_cal` = 0.06 the two point in opposite directions. The
strongest argument for staying at 0.12 is that T6 passes there — and that argument is weak, because
being faithful to a policy that discards 94 % of the index is not a virtue. Whether T6's 0.10 bound is
the right criterion at all is a question this measurement raises and does not answer.

**One hole remains, and it is in T3.** T3 passes at every value, but at the shipped default it may
have been passing vacuously: the mattress distractor draws peak amplitudes of 3–40 mg while `Θ_on`
sits at 53.7 mg, so no ring could cross the threshold at all and the WASM morphology criterion — the
thing T3 exists to measure — was never exercised. At 26.8 mg it is much closer to being exercised, and
T3 still passes, which is the encouraging reading. **It is not proof.** The coarse envelope is a 0.5 s
RMS of a 0.05–0.4 s ring, so the envelope of a 40 mg ring is well under 40 mg, and how many rings
actually produce a candidate at 26.8 mg was **not measured**. Counting raw candidates and morphology
rejections in T3 across the sweep is what would settle it, it is cheap, and it has not been done. Until
it is, "precision does not collapse" rests on the nominal night plus a T3 that may still be too easy.

Confidence: **high** for the pass/fail matrix, which is a direct re-run. **High** for the readings of
T22 and the rhythm assertion, which follow from what those tests were written to do. **Medium** for
T3 — it passes, but it has not been shown to be testing anything at either value.

---

## 5. What was fixed, and what was deliberately not

### 5.1 Three test assertions were corrected after being shown wrong

A failing test is not automatically right. Three assertions were wrong, and the code they accused was
correct.

| Assertion | The defect | The correction |
|---|---|---|
| Band-pass rejection at 0.25 Hz | The test demanded \|H\| < 0.07 on a filter built with `order = 2`. 0.07 is the **order-4** figure from the design table (0.062); the order-2 figure is 0.243. Order 4 was explicitly rejected during design because it stretches a 2 s posture transient to 4 s, and `hpOrder = 2` is the published parameter. The assertion quoted one row of the table against a filter built from another | Assert 0.243 ± 0.002, the value the published filter actually has |
| Band-pass rejection at 20 Hz | The test demanded \|H\| < 0.03. No cell of the design table quantifies 20 Hz; 0.03 was a round number, and the published filter does not meet it. The exact value for the prewarped bilinear second-order low-pass is `1/√(1 + (tan(π·20/50)/tan(π·8/50))⁴) = 0.0319` — not the analogue value of 0.158, because the bilinear transform's frequency warping near Nyquist works **in favour** of rejection here | Relax the bound to 0.035: loose enough to accommodate the real filter, tight enough that a design regression still shows |
| Mattress-ringing fixture | The fixture used a 0.25 s ring. The morphology criterion requires a 0.50 s window **contained within the event**, and the detected event is always about 0.24 s longer than the physical ring, because the coarse onset is dated on a centred 0.50 s window. For a 0.25 s ring the only available window is centred on the ring itself, the fine envelope is non-zero across most of it, and the median lands at 0.785× the peak — far above the 0.3125 hysteresis ratio. The criterion **could not fire, at any amplitude**: the test was unpassable by construction | Use a 0.15 s ring, which is still inside the 0.05–0.40 s range specified for that distractor family |

The third of these is not only a test fix; see §5.4.

### 5.2 One real production defect

The calibration path computed its envelope on the **unfiltered** signal, and so violated its own
contract.

The coarse-envelope amplitude scale is documented as "exactly the quantity the detector compares to
`Θ_on`". The detector never sees the raw rendering: it sees `RMS₀.₅ₛ(‖ButterBP₀.₅₋₈Hz(a)‖)`, each
axis filtered separately and the L2 norm taken afterwards. Measuring the amplitude on the unfiltered
rendering over-states any movement whose energy lives below 0.5 Hz — first among them the **gravity
re-projection term**, which is a near-DC plateau during the hold phase.

On the calibration ritual specifically (25° held for 0.4 s) the discrepancy reaches a **factor of 3**.
`gainCal` was over-stated by that factor, and with it the third term of the detection threshold,
`f_cal · gainCal`. The consequence was a detector that was silently too deaf on every night where
that term dominated — which is the calibrated case, i.e. the case the whole ritual exists to produce.

### 5.3 One generator defect

The posture distractor rotated the sensor about a **uniformly random axis** on the sphere. A rotation
of angle `δ` about an arbitrary axis rotates the gravity vector by only
`2·asin(sin(δ/2)·sin α)`, where `α` is the angle between the axis and gravity — strictly less than
`δ`, and exactly zero when the axis is parallel to gravity.

The distractor specification says "**rotation of ĝ** by 20–120°": what is drawn is the observable
reorientation, not the angle of a rotation about an arbitrary axis. Drawing a uniform axis produced
posture changes that were written into the ground truth but were physically almost invisible —
measured at 6.7° to 16.1° of real gravity rotation for a `δ` drawn from [20°, 120°]. The posture
detector could not find them, and its recall was capped near 0.89, below the 0.95 that T4 requires,
for a reason that was not its own.

The fix takes the component of the drawn axis orthogonal to gravity, which makes `δ` exactly the
rotation of ĝ. The azimuth within the orthogonal plane stays random — it carries the variety of
turning movements and has no effect on the magnitude of the reorientation.

### 5.4 The three findings that were recorded but not acted on — two are now corrected

Three findings had been written down and left in place. On review, two of them were **wrong numbers
in the design documents**, not design choices, and a wrong number in a table other people read off is
not a historical record worth preserving. Those two are corrected in the text, each with a dated
correction note that states what the text used to say. The third is a genuine limitation of a
published clinical rule and stays.

- **The morphology criterion cannot reject a mattress ring longer than about 0.17 s. — Still not
  fixed, and this is a choice.** This is the root cause behind the fixture correction in §5.1, and it
  is a real limitation of the detector, not of the test. The design document's claim — "a 0.3 s ring
  has a high peak but a low median over 0.5 s" — is true only below roughly 0.17 s once the
  coarse-envelope onset lead is accounted for. The 0.50 s morphology window is the published
  WASM 3.2.1-d rule, and the entire argument for using it is that it is published; shortening it to
  win a synthetic test would replace a citable rule with a tuned one. The honest response was to move
  the test to a ring the criterion can actually reject and to write down the range over which the
  defence does not work. It is bounded by distractor family 4 in the generator, and T3 measures the
  residual false-positive rate under it. **The reason to record it rather than fix it is that it is
  the rule that is limited, not the code.**
- **"Less than 0.2 % attenuation at 3 Hz" is not true of linear interpolation. — Corrected.** The
  figure is wrong under every reading, which is what changed the decision. For a fractional offset
  `μ` the interpolator's magnitude is `√(1 − 4μ(1−μ)·sin²(πfT))`, worst at `μ = 0.5` where it is
  `|cos(πfT)|`: **1.6 %** at `T = 1/52 s`, **1.8 %** at `T = 1/50 s`. Averaging over a uniform `μ` —
  the reading that might have justified 0.2 % — still gives ≈ **1.1 %**. `03-algorithm.md` §1.1 and
  `fr/ALGO-v2.md` §1.1 now state the correct bound and carry the correction note. `TimelineTest`
  continues to assert the verifiable bound (instantaneous error below 3 %) rather than the number.
- **The 0.7 Hz cell of the filter design table is wrong. — Corrected.** It read 0.915; the exact
  order-2 value is `(f/fc)²/√(1 + (f/fc)⁴) = 1.96/2.2004 = 0.891`. Re-checking the whole table while
  correcting it turned up a second, smaller error in the same column: the order-2 filter at
  fc = 0.3 Hz reads 0.982 where the exact value is `5.4444/5.5355 = 0.984`. The 0.25 Hz column and the
  entire order-4 row are exact to three decimals, as previously recorded. Both cells are corrected in
  `03-algorithm.md` §1.2 and `fr/ALGO-v2.md` §1.2, and the derived claim "−0.6 dB at 0.7 Hz" becomes
  **−0.9 dB**; the design conclusion is unchanged. `FiltersTest` now asserts the exact value
  `0.891 ± 0.002` instead of the loose bound `> 0.88` it had to use while the table disagreed with the
  filter.

The revised rule is narrower than the old one and worth stating: **when a published *rule* and an
implementation disagree, the disagreement is documented at the point of the test. When a published
*number* is simply miscalculated, it is corrected, and what it used to say is recorded next to it.**
The first case is a real tension between clinical and engineering authority; the second is
arithmetic, and preserving it helps nobody.

### 5.5 The movement model — the plateau, and the duration figure underneath it

This is the change that resolved the T6 root cause and, with it, T5. It began with a documentation
discrepancy that had to be settled first, and settling it required going back to the source.

#### The dispersion of the duration law is not published at all

The generator drew durations from a law of mean 4.2 s and standard deviation **1.4 s**. Every other
document quoted **4.2 ± 0.14 s**, a factor of ten smaller, and [`references.md`](references.md)
recorded the Sforza paper as read in full. The two figures imply different fixes, so the paper was
re-read.

Neither figure was a standard deviation. Sforza et al. 2005 §2.5: *"Results in the text and in the
tables are expressed as mean ± standard error of the mean."* The RLS group is **11 patients**. So
**± 0.14 s is a standard error over patients**; the between-patient standard deviation of the
per-patient mean duration is `0.14 × √11 ≈ 0.46 s`, and the **event-to-event** dispersion — the only
one a generator can use — is not published anywhere. The only bound on it is the 0.5–10 s scoring
window of the Coleman criteria the paper applies.

The correction is therefore not "pick the right number" but "stop attributing the number to Sforza".
`02-science.md`, `03-algorithm.md`, `references.md` and `fr/ALGO-v2.md` §5.1 now state the mean as
4.2 s and label the ± 0.14 as a standard error. The generator keeps **σ = 1.4 s**, relabelled in
`DurationSpec` as what it is — a modelling choice, CV = 0.33, ±2σ quantiles at roughly 1.9–8.0 s,
comfortably inside the scoring window. A σ of 0.14 s would make the law a point mass, which is
incompatible with the existence of that window and with the 3.2 s mean the same paper reports for its
PLMD group.

#### The same paper forbids the plateau

Three specifications of Sforza's PAM-RL are quoted in §2.4 of the paper: a 200 mg onset threshold, a
100 mg decay threshold, and a **1 s drop-out time** — a kick ends only after the signal has stayed
below the decay level for a full second. The last of those was not previously recorded in this
repository, and it is the one that settles the question.

A movement containing 2.3 s of accelerometric quiet would have been **split by Sforza's own device**,
and the mean single-event duration it reported would have been roughly half of 4.2 s. The published
duration therefore carries an implicit claim that the sensor keeps moving through what the model drew
as a static hold. The same conclusion follows from the other reading of the figure: if 4.2 s is a
Coleman EMG burst duration, it is 4.2 s of *active contraction*. And the rule corpus says it directly
— the 0.50 s offset rule exists **because** a leg movement is a train of activations separated by less
than half a second. A model that draws a 2.3 s silence inside one movement contradicts the rule the
detector is implementing.

#### What was changed, and where its two constants come from

The hold is now a **sustained flexion**: the angle dips and recovers through it, each half-dip being a
minimum-jerk profile like the flexion itself, so every junction is C² and no acceleration step — and
so no broadband click — is introduced. Two constants, neither of them tuned against a test:

| Quantity | Value | Source |
|---|---|---|
| Dip period | `2 · T_rise` | The movement's own ballistic timescale. A half-dip peaks at `0.8/T_rise`, the same 1.6–5.3 Hz band §5.1 already publishes; repetition rate `1/(2·T_rise)` ∈ [1.0, 3.3] Hz |
| Dip depth | `0.50 · θ_max` | Makes the hold-phase angular acceleration peak **0.50 ×** the ballistic peak — the PAM-RL decay/onset ratio, 100 mg / 200 mg |

The 0.50 is conservative in a knowable direction. It is what a *threshold-grazing* event must sustain
to have been scored as one kick; for an event of typical magnitude (377 mg, Sicbaldi) against the same
100 mg decay level the requirement is only 0.26. The model therefore claims more sustained activity
than the source strictly forces, and less than a full re-flexion train would give.

**A larger value scores better, and was not adopted for that reason.** With the dip depth at
`1.00 · θ_max` — a full re-flexion each cycle, which the 0.50 s offset rule would also license —
F1 measures 0.566 / 0.515 on the two diagnostic seeds against 0.462 / 0.355 at 0.50, and sensitivity
0.405 / 0.356 against 0.308 / 0.224. Choosing 1.00 on that evidence would be tuning the generator
against the test it is meant to judge. The value with a published anchor was kept and the alternative
is recorded here instead.

The §5.1 calibration table (30 / 184 / 985 mg) depends only on `θ_max`, `T_rise` and `r` and is
**unchanged**; `MovementModelTest` now asserts it, which nothing did before. That test also asserts
that no interior interval of the band-passed 0.5 s envelope falls below the detector's hysteresis
ratio for as long as the 0.50 s offset rule, and — as an inverted assertion in the style of T11 — that
the old static plateau exceeds two seconds. If that inverted assertion ever passes, the sustained hold
is doing nothing and should be removed rather than kept out of caution.

### 5.6 Two smaller corrections in the test harness itself

- **`Fixtures.rms` was offset by one sample from the production chain.** The detector fixtures built
  their envelopes with `halfLeft = w/2` while `Numeric.halfLeft` — the convention every production
  module shares, and which exists precisely so that the envelope, the floor and the mask cannot drift
  apart — is `(w−1)/2`. For an even window the extra sample belongs on the right. Every timing
  assertion written against those fixtures therefore carried a one-sample bias without saying so. The
  fixtures now call `Numeric.halfLeft`.
- **`AmplitudeScale.PEAK` documented itself as something it is not.** Its KDoc claimed it was "the
  quantity of the §5.1 calibration table (30 / 184 / 985 mg)". It is not: it is the peak of the
  **norm** of the rendered movement, all three contributions summed, whereas the table quantifies the
  **tangential term alone** — as §5.1 itself makes clear two paragraphs later, where it notes that the
  gravity term dominates at large angles. `MovementKinematics.peakTangentialG` computes the table's
  quantity and was dead code. The KDoc is corrected and the function is now the thing
  `MovementModelTest` calls to assert the table.

---

## 6. The limits of all this

None of this is validation.

- **Level 1 is partly circular.** The generator and the detector share their physical assumptions. A
  detector tuned against a generator built on the same model of a movement will agree with it, and
  that agreement is not evidence about ankles. The strongest counter-argument available is that the
  generator's amplitudes fall out of published joint angles and segment radii without being tuned to
  do so — which is a real argument, and much weaker than a measurement.
- **The metrics are scored against `accelTruth`, which is itself a modelling assumption.** The 39 %
  miss rate rests on a conference abstract whose full text was not obtainable. If that figure is
  wrong, every sensitivity number in this document moves.
- **No level tests the denominator.** Total sleep time comes from a separate device, and its error
  against polysomnography — sensitivity 0.954, specificity 0.524 for a consumer wrist device — is
  larger than anything the detector contributes. There is no synthetic test for that, because there
  is nothing to inject ground truth into.
- **Nothing here has been run on a real night.** The Android modules now build and run on emulators,
  and instrumented tests cover the interface guard rails, but no accelerometer has been worn at an
  ankle by this software. Level 2 and level 3 checks have not been performed, and the hardware
  feasibility gate itself (phase P1) has not been passed.

**And no amount of synthetic testing substitutes for polysomnography.** The AASM issues a strong
recommendation against actigraphy as a replacement for EMG in diagnosing periodic limb movement
disorder, and this project does not dispute it. The defensible purpose of the output is to help
someone decide whether to ask for a real examination. A test suite, however precise, does not change
that.

---

*The adversarial review that motivated most of the guard rails described here is
[`fr/REVUE-CRITIQUE.md`](fr/REVUE-CRITIQUE.md): 38 defects, and nine specific paths by which the
number can be wrong while looking credible. The interface consequences are in
[`06-interface.md`](06-interface.md); the algorithmic ones in [`03-algorithm.md`](03-algorithm.md).*
