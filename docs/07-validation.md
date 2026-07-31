# Validation: how the software is tested, and the honest state of that testing

> **Pendulum** measures periodic limb movements in sleep from a smartwatch worn at the ankle, and
> tracks the *rhythm* between them rather than their number. It is a screening aid: a real
> measurement to take to a physician, not a diagnosis and not a medical device — see
> [what this is, and what it is not](../README.md#what-this-is-and-what-it-is-not).

This document describes what is actually verified about Pendulum, how, and what the verification does not
establish. It ends with one failing test, a full account of why it fails, and the record of a
diagnosis that was itself wrong and had to be corrected.

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

Levels 1, 4, 5 and 6 exist today. Levels 2 and 3 are scheduled for the seven-night campaign at the
end of the roadmap ([`01-overview.md`](01-overview.md) §5, phase P7) and have not been run.

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
| **T6** | Nominal night: true index 25/h, true periodicity 0.60, all distractors active | F1 **≥ 0.90** vs `accelTruth`; relative index error **≤ 0.10**; periodicity error **≤ 0.05**; onset bias **≤ 300 ms**, sd **≤ 400 ms** | The end-to-end test |
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

### T11 is inverted, and that is the point

T11 asserts that **without** calibration the spread must **exceed 40 %**. It is not a typo and it is
not a lower standard: it is a test that the calibration ritual is doing something.

The reasoning is that a guard rail whose removal changes nothing is not a guard rail. If a mechanical
gain change of ×0.6 to ×1.8 — a strap moved by one hole — did not move the index by more than 40 %
with calibration disabled, then the 70-second calibration ritual imposed on the user every night
would be pure ceremony. **If that assertion ever fails, the ritual must be removed, not the test.**
The failure mode this defends against is a project that accumulates rituals because each of them
sounds prudent.

Four further assertions were added after the ones above and follow the same numbering: truncated
nights must produce a rhythm but refuse an hourly index; the circularity test (also inverted, and for
the same reason as T11) must show that the sleep mask collapses when the anti-circularity layer is
disabled; injected format corruption must be rejected at 100 % with no healthy block rejected; and
the incremental pass must cover at least 90 % of the movements found by the definitive pass. They are
specified in [`fr/ALGO-v2.md`](fr/ALGO-v2.md) §5.5.

---

## 4. Current status, stated plainly

**142 tests in the `algo` module. 141 pass. One fails: T6, with a median F1 of 0.424 against a
threshold of 0.90.**

T5 now passes. It failed for the same root cause as T6 — a defect in the *generator's* movement model,
not in the detector — and that defect has been found, traced to its source, and corrected; §5.5 is the
account. T6 improved from 0.267 to 0.424 with it and still fails, for a **second and different**
reason that the previous edition of this document did not identify: most of the events in the truth
set are below the detector's own onset threshold. §4.1 is that measurement.

Neither the old diagnosis nor the new one is a crash, a flake, or a threshold set aspirationally. But
the old one was partly wrong, and it is worth saying why plainly: it reasoned to a ceiling of 0.76
from a doubling of candidates, while the measured value was 0.267. A number half the size of its own
predicted ceiling should have been treated as evidence that the account was incomplete. It was not,
and that is the mistake this revision corrects.

### 4.1 T6 — the movement model was the cause, it has been fixed, and the test still fails for a second and different reason

The previous edition of this section attributed T6 entirely to a **doubling** of candidates caused by
the generator's static plateau, and computed a ceiling of F1 ≤ 0.76 from it. The first half of that
was right and has been acted on. The arithmetic was not: the measured F1 was **0.267**, far below the
0.76 the doubling alone would have permitted, so something else was always doing most of the damage.
Both parts are now measured rather than reasoned about.

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

#### Why T6 still fails: 68 % of `accelTruth` is below the detector's own onset threshold

The remaining gap is not precision, it is recall — 0.31 and 0.22 on those two seeds — and its cause is
measurable in one table. On the nominal night the effective noise floor is **6.7 mg**, so the onset
threshold `Θ_on = max(k_on·floor, Θ_abs, f_cal·gainCal)` is dominated by its relative term and sits at
**53.7 mg**. The coarse-envelope peaks of the events in `accelTruth` are distributed like this:

| p10 | p25 | **p50** | p75 | p90 |
|---|---|---|---|---|
| 19 mg | 25 mg | **37 mg** | 60 mg | 82 mg |

The **median event in the truth set is at 0.70 × the detection threshold.** Restricting the truth set
to the events the detector is actually configured to find:

| Truth set | n | Sensitivity | Precision | **F1** |
|---|---|---|---|---|
| `accelTruth`, all (visibility cut 8 mg) | 237 / 219 | 0.308 / 0.224 | 0.924 / 0.860 | 0.462 / 0.355 |
| `accelTruth` ∩ envelope ≥ `Θ_abs` (20 mg) | 211 / 185 | 0.346 / 0.265 | 0.924 / 0.860 | 0.503 / 0.405 |
| `accelTruth` ∩ envelope ≥ `Θ_on` (53.7 mg) | 77 / 53 | **0.922 / 0.925** | 0.899 / 0.860 | **0.910 / 0.891** |

**On the events above its own threshold, the detector meets the criterion.** The entire shortfall is
the population between the generator's 8 mg visibility cut and the detector's 53.7 mg onset
threshold.

#### This is the same error the project already fixed once, one level down

§2.3 introduces `accelTruth` precisely so that F1 is not capped "for a reason that is not the
algorithm's fault" — scoring against `emgTruth` would cap sensitivity at 0.61 by mechanics alone. The
measurement above shows the identical failure mode surviving that fix: `accelTruth` is defined as the
events **mechanically rendered into the signal** (peak above 8 mg, "about 0.4× the absolute noise
floor"), which is not the same set as the events **the detector is configured to detect**. An event at
30 mg is genuinely present in the signal and genuinely below `Θ_on`; counting it as a miss measures
`k_on`, not detector fidelity — and `k_on = 8.0` is described in the parameter table as an
anti-artefact budget carried over from v1 "for want of anything better".

So the honest statement is: **T6 as written measures the threshold policy at least as much as it
measures the detector**, and the justification given in §2.3 for the `accelTruth` denominator does not
in fact achieve what it claims.

#### The open question, restated with the measurement in hand

The four options in the previous edition were framed around the doubling. Three of them are now moot;
the live question is different, and narrower.

| # | Change | Argument for | Argument against |
|---|---|---|---|
| 1 | ~~**The movement model**~~ | — | **Done.** See §5.5. It was a real defect, it was the right one to attack first, and it moved F1 from 0.267 to 0.424 and precision from 0.48 to 0.92 |
| 2 | **`offHoldSec`** | — | Untouched, and it should stay untouched: it is a scored clinical constant, and the measurement now shows the detector was applying it *correctly* to a signal the generator had mis-shaped |
| 3 | **The matching rule** | — | Moot. With the model corrected there is no longer a surplus of candidates to forgive: 176 candidates for 237 truth events |
| 4 | **The visibility cut of `accelTruth`** — raise it from 8 mg to the detector's onset threshold, or report F1 on both denominators | Makes F1 measure the detector rather than `k_on`, which is what §2.3 already argued for once | Hides the downward bias of the count behind a friendlier number. The gap between the two denominators *is* the under-count, and it is the single most important thing this project has to be honest about |

**Option 4 is the only live one, and it is deliberately left open.** It is not a bug fix; it is a
decision about what the published F1 is allowed to mean, and it has the same shape as the choice
between `emgTruth` and `accelTruth` that §2.3 already made once. Making that call silently — while
fixing a defect, in the same change — would be exactly the kind of quiet goalpost move this document
exists to prevent. What is recorded instead is the number under all three denominators, so that
whoever makes the decision makes it with the measurement in front of them.

Confidence: **high** for the model defect and its removal (measured before and after, on the same
seeds, with an inverted assertion guarding the regression); **high** for the threshold-population
diagnosis (the three-denominator table is a direct measurement, not an inference).

### 4.2 T5 — the three stated criteria now pass; the guard rail beside them does not

T5 asserts that sensitivity is ≤ 0.05 at 4× the effective floor, in [0.35, 0.65] at 8×, and ≥ 0.95 at
16×. All three now hold: **0.000 / 0.407 / 1.000** over the 20 seeds. The previous diagnosis — that
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
