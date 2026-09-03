# ALGORITHM SPECIFICATION v2 — PLMS detector from a 50 Hz ankle accelerometer

Module `algo`. Pure functions, zero I/O, explicit `fs`. Replaces the "Module algo" section of
[`SPEC-v1.md`](SPEC-v1.md), which is historical.

> **Where this document is authoritative, and where it no longer is.** **§6, the final parameter
> table**, is the reference: the code cites these tables by name, and the values there survive word
> for word. **§1** and **§2** carry the reasoning that produces them, and that is what exists nowhere
> else.
>
> On the other hand, **this document precedes the measurements**. §5.5 states regression thresholds
> to assert; they have since been written, run, and one of them was overturned by what it showed.
> **The measured state is in [`../07-validation.md`](../07-validation.md) §3 and §4**, which says how
> many tests pass, what getting them to pass cost, and what the measurement falsifies right here. Two
> known divergences are listed at the point where they arise, in §5.5 and §6.1.
>
> **This document has no section on the rhythm estimator.** The metric the product tracks changed
> after it ([`SPEC-v2.md`](SPEC-v2.md) §5): it is now the fundamental period in seconds, obtained by
> harmonic deconvolution, and its specification lives in
> [`../03-algorithm.md`](../03-algorithm.md) §6, its parameters in §8.7. Nothing here mentions it, and
> that is the biggest hole in the file.
>
> For the watch-side implementation and the transfer, see [`CAPTURE-ARCHITECTURE.md`](CAPTURE-ARCHITECTURE.md).
> For the bibliography, [`../references.md`](../references.md) — the only list in the repository.

---

## 0. Executive summary — what changes relative to v1

Three findings from the literature review invalidate choices made in v1, and a fourth shifts the centre of gravity of the problem.

**(a) The spectral content of an accelerometric PLMS is sub-6 Hz, not 10–15 Hz.** v1 specifies a synthetic generator with "Gauss-windowed 10-15 Hz packets". That is physically wrong and it would have produced a detector validated against a phantom. Every published band brackets 0.1–20 Hz with the useful energy at the bottom: Athavale et al. (SLEEP 2019) obtain 87.9 % Se / 94.1 % Sp for PLMI ≥ 15 with a **low-pass** at 0.4 Hz (stopband 1.6 Hz); Gschliesser 2009 shows that the Actiwatch, the only device that high-passes at 3 Hz, **massively under-counts** (PLMI 21.2 ± 25.6 vs 34.4 ± 30.7 in PSG, p < 0.001) whereas the PAM-RL (0.3 Hz corner) over-counts (63.6 ± 39.3 vs 37.0 ± 33.5, p = 0.009).

**(b) The 0.15 s RMS window is a bug, not a parameter.** It is taken from S-PLMAD, which operates on EMG at 512 Hz in a 10–300 Hz band. For a signal useful at 1–5 Hz, 0.15 s = 7.5 samples does not even average half a period of a 2 Hz component. The envelope ripples at 2f with a modulation depth close to 100 %, the comparator chatters around the threshold and **fragments a single CLM into several**. Neither reviewer saw it.

**(c) The choice of sleep mask dominates every other source of error.** The only work comparing wrist and ankle on the same subject (n = 29 children, PMC12215244) gives, for TST vs PSG: Cole-Kripke at the ankle **+43 min [20, 66]** (against −8 min [−28, 13] at the wrist), van Hees/GGIR at the ankle **−89 min [−116, −63]**. On a 420 min night, that shifts the PLMI by **−9.3 %** in one case and **+26.8 %** in the other, a gap of **36 % between two equally defensible choices of mask algorithm**. That is more than anything the detector itself can gain or lose. v1 treated the mask as an accessory; it is the largest item in the error budget.

**(d) The accelerometer does not measure the same quantity as EMG.** Terrill et al. (EMBC 2013, PMID 24111321): **39.0 %** of the movements scored on EMG (and 54.9 % of those scored on the piezo) come with **no** movement detectable by accelerometry. This is not an algorithm error, it is mechanics: a pure ankle dorsiflexion does not displace a sensor located above the joint axis. Design consequence: the accelerometric PLMI is **a different scale** from the EMG PLMI, not a noisy estimator of it. The ICSD-3 threshold of 15/h is not transposable as such, and the synthetic ground truth must carry **two sets of labels** (`emgTruth` / `accelTruth`).

To these are added four clinical-rule errors in v1, detailed in §1.5, and a structural circularity of the PLMI dealt with in §3.6.

---

## 1. Reasoned critique

### 1.1 Proposal 1 — absolute floor: **ACCEPTED**, with a different value and a corrected line of reasoning

**Confirmed in principle, with a justification the reviewer did not give.** The calculation:

- Typical noise density of a low-power MEMS: 150–300 µg/√Hz. Take 200 µg/√Hz.
- Useful band 0.5–8 Hz → BW = 7.5 Hz → RMS noise per axis = 200 µg × √7.5 = **548 µg**.
- Quantisation noise of the format: LSB = 1/2048 g = 488 µg, RMS = LSB/√12 = 141 µg spread over 25 Hz, i.e. 28.2 µg/√Hz → **87 µg** in band. Negligible next to the analogue part: **the resolution of the format is not the limiting factor**, which is a good point of v1 to keep as it is.
- L2 norm of three i.i.d. Gaussian axes → Maxwell distribution: E[‖v‖] = 2σ√(2/π) = **1.596 σ**, i.e. ≈ 875 µg.
- Onset threshold at 8× that floor = **7.0 mg**.

But the published detection thresholds at the ankle are: Sicbaldi et al. (Sci Rep 2025, Axivity AX6, 100 Hz, 0.1–10 Hz) **15 mg**; NeuroMetrix patents US9731126/US10335595 (50 Hz, high-pass 0.5 Hz — the configuration closest to ours) **20/30 mg**; Actiwatch **50 mg**; PAM-RL (Sforza et al. 2005, 40 Hz, 0.3–20 Hz) **200 mg at onset / 100 mg at offset**. The purely relative threshold is therefore **2 to 30 times too low** in silence. Critique confirmed.

**But the reviewer's reasoning is wrong in the detail.** The factor 8 is not "hypersensitive": for a Rayleigh-type envelope, P(E > 8·median) = exp(−0.693 × 64) ≈ e⁻⁴⁴ — strictly zero thermal false positives over a night. The problem is not the factor, it is that **the floor estimator collapses towards the thermal floor** when the night is mechanically silent, and that the product 8 × (collapsed floor) falls below the threshold of physical plausibility. The correction therefore applies to the floor, not to the factor.

**Value adopted: Θ_abs = 0.020 g, range 0.010–0.050 g** — and not 0.05 g as proposed. Justification: 50 mg is the *sensitivity threshold* of the Actiwatch, the device that under-counts by 38 %; 15–30 mg is the range of the systems that work (Sicbaldi, NeuroMetrix). A floor at 50 mg would kill the small CLMs. Sicbaldi also measures a typical movement magnitude during sleep of **377 ± 63 mg** — a floor at 20 mg leaves a dynamic ratio of 19:1, a floor at 50 mg reduces it to 7.5:1.

**Formula adopted** (three terms, not two):

```
Θ_on(t) = max( k_on · floor(t) ,  Θ_abs ,  f_cal · gainCal )
```

The third term is the inter-night normalisation (§3.3). The reviewer did not mention it, and yet it is the term that makes the PLMI comparable from one night to the next.

### 1.2 Proposal 2 — 0.5–10 Hz band-pass: **ACCEPTED, but for a reason other than the one advanced, and with a reservation**

**The upper corner is the better of the two arguments, and the reviewer did not justify it.** Going from "no low-pass" (band 0.5–25 Hz, BW 24.5 Hz) to a low-pass at 8 Hz (BW 7.5 Hz) reduces the in-band RMS noise by a factor √(24.5/7.5) = **1.81**, i.e. **+5.1 dB of SNR for free**, without touching the useful signal (§5.1: the spectral peak of a CLM is at 1.6–5.3 Hz). That is a given, with no downside. I adopt **8 Hz** rather than 10 (range 6–12 Hz); the extra gain from 10 → 8 Hz is 0.6 dB, marginal, but 8 Hz still sits at ≥ 1.5× the spectral peak of the fastest CLM (T_rise = 0.15 s → 5.3 Hz).

**The lower corner: the critique is right in form, but the premise is not verified.** No publication quantifies the amplitude of the respiratory artefact **at the ankle** — the literature only does so for the thorax, and marginally the wrist. The reviewer postulates a problem whose existence is not established at this measurement site. That said, the remedy is almost free:

| Filter | \|H\| at 0.25 Hz (respiration) | \|H\| at 0.7 Hz (slow CLM) | Ringing on a gravity step |
|---|---|---|---|
| Butterworth HP order 2, fc = 0.3 Hz (v1) | 0.570 (−4.9 dB) | 0.984 | ≈ 2 s |
| **Butterworth HP order 2, fc = 0.5 Hz (adopted)** | **0.243 (−12.3 dB)** | **0.891** | **≈ 2 s** |
| Butterworth HP order 4, fc = 0.5 Hz | 0.062 (−24.1 dB) | 0.968 | ≈ 4 s |

Going from order 2 at 0.3 Hz to order 2 at 0.5 Hz gains **7.4 dB (a factor of 2.35)** of respiratory rejection for −0.9 dB on a CLM component at 0.7 Hz. That is a good trade.

> **Correction of 2026-07-31.** The 0.7 Hz column carried **0.915** (fc = 0.5 Hz) and **0.982** (fc = 0.3 Hz), and the trade was announced as −0.6 dB. The exact magnitude of an order-2 Butterworth high-pass is `(f/fc)² / √(1 + (f/fc)⁴)`, i.e. `1.96/2.2004 = 0.891` and `5.4444/5.5355 = 0.984`; the real trade is −0.9 dB. The 0.25 Hz column and the whole order-4 row were correct. The conclusion does not change, but a table others copy from must not carry a wrong value: `FiltersTest` now asserts the exact values.

**I reject order 4 on the other hand**, which the reviewer's logic would naturally call for (19.2 dB of gain, a factor of 9.1): the step response of an order-4 Butterworth rings **twice as long** (≈ 4 s against 2 s). And a posture change produces a gravity step that can reach **1 g** — 50 times a typical CLM. A 4 s ring falls squarely inside the duration window of a CLM (0.5–10 s), where a 2 s ring is already troublesome. We would be trading a hypothetical artefact (respiration at the ankle) for an aggravation of a certain and enormous one (posture). **Order 2 by default, order 4 behind a parameter.**

**Important reservation, to be documented.** Athavale's success with a *low* cut at 0.4 Hz proves that a real part of the discriminating information lives **below** 0.5 Hz (the net displacement of the limb, not the acceleration burst). A high-pass at 0.5 Hz throws that information away. I do not recover it through a second detection channel — that would reopen the door to respiration — but **through a per-event feature extracted from the gravity channel** (`tiltExcursionDeg`, §2.3 and §3.1), which measures exactly the net displacement of the segment. We get Athavale's information without his lower corner.

### 1.3 Proposal 3 — low percentile on a lagged causal window: **PRINCIPLE ACCEPTED, IMPLEMENTATION REJECTED FOR THE DEFINITIVE PASS**

**The principle is right, but the mechanism described is quantitatively wrong.** A PLMS series barely self-contaminates a median. Mean duration of a PLM measured at the ankle: **4.2 ± 0.14 s** in RLS (Sforza 2005), mean IMI 31.3 ± 1.3 s → duty cycle **13–18 %**. A median only breaks down beyond 50 % contamination. Concretely, with 18 % purely high contamination, the overall median sits at percentile 0.50/0.82 = **61st** of the clean distribution; for a Rayleigh-type envelope, Q(0.61)/Q(0.50) = **+16.6 %**. The floor rises by 17 %, not by a factor that "cuts off at the 3rd CLM".

What really breaks the median is **gross body movements**: a 20 s turn inside a 25 s window is 80 % contamination — complete breakdown. Trap no. 5 of v1 names the right consequence for the wrong cause.

**The low percentile on its own is a weak remedy.** Still in Rayleigh, at 18 % contamination, p10 moves to percentile 0.122 → **+11.1 %**. We gain 5.5 points out of 16.6. That is not an order of magnitude.

**I reject the lagged causal window [T−65 s, T−5 s] for the definitive pass, for three reasons.**

1. **It solves a problem we do not have in this pass.** v1 records the "post-processing on waking" decision: the whole night is in memory. Causality costs nobody anything and loses precision.
2. **It introduces a 35 s lag bias.** The estimator is centred 35 s in the past. But the mechanical floor **changes in jumps** at every posture change (the strap-ankle-bedding coupling changes). After a jump, the threshold is wrong for at least 35 s. A series of 4 CLMs at IMI 22 s lasts 66 s: **an entire series** can be manufactured or destroyed by a single estimator lag.
3. **It is dominated by a better method already named in v1**: iterative exclusion.

**Nuance brought by the move to 5 min chunks (§3.7).** The reviewer's proposal becomes **exactly the right answer for the incremental mode**, where causality is imposed by the physics of the problem. It is therefore kept, not as the reference estimator but as the documented causal variant, with the window lengthened to [T−125 s, T−5 s] to align it with `floorWinSec = 120 s`. The definitive mode stays non-causal. See §3.7.

**Adopted for the definitive pass — three-pass estimator, non-causal, segmented:**

```
Pass 1 : floor₀(t) = p25 of env over a BILATERAL window of W = 120 s, truncated at segment bounds
Pass 2 : mask M = { i : env[i] > k_excl · floor₀[i] }    (k_excl = 4, by default)
Pass 3 : floor(t)  = median of env over the same window, restricted to {i ∉ M}
          if |{i ∉ M} ∩ window| < 0.25·W·fs  →  floor(t) = floor₀(t), flag FLOOR_EXTRAPOLATED
Then   : floor(t) ← max(floor(t), Θ_abs / k_on)     (consistency with the absolute floor)
```

Two protections that neither v1 nor the reviewer provides for:

- **The window never crosses a posture-change boundary nor a segment boundary** (§3.1, §3.4). The floor is re-estimated on either side. That is what correctly handles the coupling jump, where the lagged window fails.
- **W = 120 s and not 25 s.** At 25 s, a single 20 s turn carries the estimator away; at 120 s it weighs 17 %. The cost is a lower temporal resolution, of no importance since the mechanical floor varies on the scale of posture (minutes), not of the second.

### 1.4 Proposal 4 — TKEO: **REJECTED**, with two quantitative arguments — but the aim behind it is legitimate and is handled another way

The discrete operator Ψ[x[n]] = x[n]² − x[n−1]·x[n+1] equals **exactly** A²·sin²(Ω) for x[n] = A·cos(Ωn+φ), Ω in rad/sample (the usual A²Ω² expression is the small-Ω approximation, exact to within 1 % up to Ω ≈ 0.35 rad/sample, to within 10 % at Ω ≈ 1.1). The TKEO therefore weights energy by **sin²(Ω)**.

**Argument 1 — the TKEO amplifies exactly what we want to get rid of.** At fs = 50 Hz:

| Component | f | Ω = 2πf/fs | sin²Ω | Relative weight |
|---|---|---|---|---|
| PLMS signal | 2 Hz | 0.251 | 0.0619 | 1.00 |
| Top-of-band MEMS noise | 20 Hz | 2.513 | 0.3455 | **5.59** |

The TKEO lifts the noise at 20 Hz by **5.6× in energy (2.4× in amplitude)** relative to the signal at 2 Hz, compared with a classical RMS envelope. For a signal whose useful energy is at the bottom of the band, that is the opposite of what we want. This is no accident: the only two published applications of the TKEO to accelerometry (Aubol & Milner, IEEE TBME 2019, gait event detection) do it preceded by a **1–20 Hz band-pass** precisely because, the paper says, the TKEO amplifies high-frequency noise. Our useful band being narrower and lower still, the weighting ratio is more unfavourable.

**Argument 2 — the gain aimed at has no clinical value here.** The documented benefit of the TKEO is onset timing precision. Solnik et al. (Eur J Appl Physiol 2010, PMC2945630): pooled mean error on gait EMG **124 ms → 55 ms**, SNR 12.3 → 357.7. Excellent — and beside the point. Our clinical criteria have a granularity of **500 ms** (minimum duration of a CLM) and of **5 000 ms** (lower bound of the IMI). Gaining 69 ms on the onset does not move a single event across a class boundary. We would be paying 5.6× the noise for a precision no rule uses.

Attribution note, useful because the secondary literature gets it wrong: the often-cited figure "40 ± 99 ms vs 229 ± 356 ms, p = 0.023" is **not** from Li, Zhou & Aruin 2007 (whose abstract contains no numerical result and whose full text is paywalled) but from Solnik et al. 2008, Acta Bioeng Biomech.

**The reviewer's aim is nevertheless a good one** — clean onsets/offsets — and v1 fails at it because of the 0.15 s RMS window (§0-b). **Solution adopted instead: a two-scale envelope.**

- **Coarse envelope, W_c = 0.5 s** (25 samples) for the **decision**: a rectangular window of duration exactly 1/f cancels the residual at frequency f; at 0.5 s it places an exact zero on the 2 Hz ripple of a 1 Hz signal and a sinc attenuation on the higher ripples. Stable, does not chatter.
- **Fine envelope, W_f = 0.15 s** for the **refinement** of the onset and the offset once the candidate is confirmed: we walk back from the coarse crossing to the last passage below Θ_off of the fine envelope.

Cost: two convolutions instead of one (O(n) each with a running sum). Benefit: the stability of a long window and the sharpness of a short one, with no noise amplification. This is what the TKEO promised, obtained by a means that does not fight the physics of the signal.

### 1.5 Clinical-rule errors in v1, not caught by the reviewer

**(i) v1 applies the 0.5–10 s duration bound identically to both rule sets. That is wrong for WASM 2016.** WASM 2016 rule 3.2.1: "LM now have no maximum length. **A LM > 10 s now ends a PLM sequence.**" A 12 s movement is not "ignored", it **breaks the series in progress**. v1 would have let the series continue over it. On a night with 40 turns, that materially changes the series count.

**(ii) v1 reduces the AASM/WASM difference to the lower IMI bound. That is the less important of the two points.** Ferri et al. (Sleep Med 2015, PMID 26429751, 107 RLS + 63 controls) isolated the two effects: "Alt1" = the 5 → 10 s bound alone, "Alt2" = Alt1 + series break on a short IMI. Result: "**only the Alt2 algorithm provided significantly different results**". In other words, **raising the lower bound changes almost nothing; it is the break rule that changes everything.** v1 implements the parameter with no effect and omits the one that counts. WASM 2016 rule 3.3.6: "Long (> 90 s) and short (< 10 s) CLM IMIs **end a sequence**." The AASM, for its part, is **silent** on the subject; the WASM 2006 convention ("if the interval is < 5 s, the later movement is ignored and the period is computed up to the next candidate") is the default interpretation adopted, explicitly labelled as an interpretation.

**(iii) v1 misses the WASM 2016 morphology threshold** (rule 3.2.1-d, new in 2016, with no AASM equivalent): the event must contain a period ≥ 0.5 s whose **median amplitude** exceeds the offset threshold. Its original function is to exclude polymyoclonic bursts; transposed to accelerometry, it is **the best filter against mattress-transmitted vibration** available in the corpus of rules (§3.2). It is free to implement and I adopt it for **both** rule sets.

**(iv) The v1 rule "merge CLMs less than 0.5 s apart" is a confusion.** In WASM 2016, the offset-to-onset < 0.5 s threshold is the **bilateral combination** rule (merging left leg and right leg into a bilateral LM). The AASM has a *different* bilateral rule: onset-to-onset < 5 s. **We measure a single leg: neither applies.** The only merging effect in the unilateral case is already contained in the definition of the offset ("stays below the threshold for 0.5 s"), which mechanically prevents two events from being separated by less than 0.5 s. v1 therefore introduces a redundant step, and trap no. 6 ("forgetting it doubles the count") is wrong as written: correctly implemented, the offset does it on its own.

**(v) v1 says nothing about gross body movements.** A turn lasts 2–20 s and reaches 0.3–2.5 g (Sicbaldi: 2 506 ± 240 mg when awake). A 5 s turn satisfies **exactly** the duration criteria of a CLM and will be counted. An explicit classifier is needed (§2, step 5) plus a refractory period.

**(vi) v1 fixes Ferri's PI without giving its bounds; the value often copied around (10–50 s) is wrong.** The bounds are **10–90 s**, and the condition is **≥ 3 consecutive qualifying intervals** (i.e. ≥ 4 LM). A validity condition is also needed: the PI is uninterpretable below ~10 LM/h in total (Drakatos et al., J Thorac Dis 2021), the denominator then being made up of a handful of intervals.

---

## 2. The v2 chain — step by step

Convention: `fs` is the real frequency, estimated then brought back to exactly 50.000 Hz (§3.4); all durations are in seconds; all amplitudes in **g**.

### Step −1 — Integrity checks at the input (the algo trusts nothing)

The CRC16 of the format covers **only the payload**: `count`, `tFirstNs`, `tLastNs` and `flags` of the block header are not protected. A corrupted time base therefore passes the decoder silently, and the whole v2 chain rests on the timestamps (`fs` estimation, resampling, durations, IMI). **The `algo` module must therefore revalidate temporal consistency itself**, independently of what `:format` accepted. This is a design requirement, not a precaution: without it, a corrupted block shifts the whole downstream timeline.

Checks, in this order, all O(n):

| # | Check | Rejection |
|---|---|---|
| 1 | `1 ≤ N ≤ MAX_SAMPLES_PER_BLOCK` and `x.size == y.size == z.size == N` | block |
| 2 | `tFirstNs ≤ tLastNs`; both strictly positive | block |
| 3 | `fs_block = (N−1)·1e9/(tLastNs−tFirstNs)` within ±20 % of `nominalRateHz` | block |
| 4 | **Inter-block monotonicity**: `tFirstNs(b+1) ≥ tLastNs(b)` | block b+1 |
| 5 | **Non-overlap**: `tFirstNs(b+1) − tLastNs(b) ≥ 0.5/fs` | block b+1 |
| 6 | **Plausible gap**: `tFirstNs(b+1) − tLastNs(b) ≤ maxGapNs` (default 14 h — beyond that, a clock reset) | session cut |
| 7 | `chunkIndex` strictly increasing, `sessionUuid` identical on all chunks | chunk |
| 8 | **Impossible jerk**: `‖a[i] − a[i−1]‖ > 8 g` between two consecutive samples at 50 Hz is physically impossible at the ankle → decoding corruption (typically a saturation overflow or a faulty `toRaw`) | block |
| 9 | **Gravitational plausibility**: over static windows, `median(‖a‖)` must be within [0.80 ; 1.20] g | session marked `DECODE_SUSPECT` |
| 10 | **Saturation**: a block in which > 5 % of the samples equal ±32767 LSB is corrupted, not saturated (the ankle does not reach ±16 g) | block |
| 11 | Consistency of `FLAG_GAP_BEFORE` with the gap actually measured; inconsistency → flag, not rejection | report |

Every rejected block is converted into a **gap** of its nominal duration and handled by the gap policy of step 0. The number of blocks rejected per check is published in `IntegrityReport` and must appear in the night's quality report: a rejection rate > 1 % invalidates the night.

Note on `ChunkFormat.toRaw`: the compilation defect reported (`Math.round(Double)` returns a `Long`, and `Long.coerceIn(Int, Int)` does not exist) is a bug of the `:format` module, not of `:algo`. But it has a consequence for us: **a badly implemented saturation produces a sign wrap** (±16 g flipping from one extreme to the other between two neighbouring samples). Check no. 8 detects it independently of the fix for that bug — which is exactly why it is there. Do not remove it once `:format` is fixed.

### Step 0 — Timeline reconstruction and resampling

**Input**: list of decoded blocks `(tFirstNs, tLastNs, flags, x[], y[], z[])` that passed step −1.

**Formulas.**

```
fs_block(b)  = (N_b − 1) · 1e9 / (tLast_b − tFirst_b)
fs_session   = N_b-weighted median of the fs_block values, after rejecting the blocks
               such that |fs_block − med| / med > 0.05
t_sample(b, i) = tFirst_b + round(i · (tLast_b − tFirst_b) / (N_b − 1))
```

Then linear interpolation onto a uniform grid at `fs_target = 50.000 Hz`. The point-by-point attenuation introduced by linear interpolation at 3 Hz is **at most 1.8 %** — negligible, verified by `TimelineTest`.

> **Correction of 2026-07-31.** This sentence announced "below 0.2 % for a resampling ratio < 1.06". That is wrong, and it is not the ratio that governs the attenuation but the **source sampling period**. For a fractional offset `μ ∈ [0,1]`, the magnitude of the interpolator is `√(1 − 4μ(1−μ)·sin²(πfT))`, minimal at `μ = 0.5` where it reduces to `|cos(πfT)|`: at 3 Hz that gives **1.6 %** for `T = 1/52 s` and **1.8 %** for `T = 1/50 s`, closed-form bound `(πfT)²/2`. Even averaged over uniform `μ` — the only reading that could have justified 0.2 % — the attenuation remains ≈ **1.1 %**. The engineering conclusion holds: less than 2 % at 3 Hz is negligible next to the 5.2 % bias on durations that resampling removes.

**Parameters**: `targetFsHz = 50.0` (fixed); `fsOutlierTol = 0.05` (range 0.02–0.10); `gapMicroSec = 0.10`, `gapSegmentSec = 2.0`.

**Justification.** An `fs` wrong by 5.2 % (50 → 52.6 Hz) moves the filter corner only from 0.500 to 0.526 Hz — no effect — but falsifies **every duration by 5.2 %**: a CLM measured at 10.0 s really lasts 9.5 s, an IMI of 90 s is really 85.5 s. Events at the bounds switch class. Bringing everything onto a fixed grid makes the filter coefficients constant, the durations exact, and the handling of gaps explicit. It is free and it removes an entire class of bugs.

**Edge behaviour.**

- Gap < 0.10 s (≤ 5 samples): silent linear interpolation, no flag. Shorter than the fastest feature of a CLM (T_rise ≥ 0.15 s).
- Gap 0.10–2.0 s: the missing samples are marked `NaN`. The movement channel treats them as zeros, the gravity channel **holds the last value**. A **blind zone** is marked over [gap_start − T_settle, gap_end + T_settle] with `T_settle = 2.0 s` (settling time of an order-2 Butterworth at 0.5 Hz). No CLM may begin or end in a blind zone; a CLM that straddles one is rejected (`IN_BLIND_ZONE`).
- Gap > 2.0 s: **hard segment boundary**. All filter states are reset, any open PLM series is terminated. This is exactly WASM rule 3.3.3: "The prior IMI is not measured … for the first CLM after starting or **re-starting the recording**" — we are not inventing it, we are applying it.
- Block with an invalid CRC or rejected at step −1: treated as a gap of its nominal duration.
- Accounting: blind time and gap time are **removed from the PLMI denominator**. With the P1 criterion of v1 (cumulative gaps < 2 min over 8 h = 0.42 %) the effect is nil; on a degraded night with 30 min of gaps, not doing it would deflate the PLMI by 7 %.

### Step 1 — Gravity / movement separation (two parallel paths)

```
ĝ(t)      = ButterLP(order 2, fc_g = 0.15 Hz) applied separately to x, y, z
a_lin(t)  = ButterBP(0.5 Hz – 8.0 Hz, order 2 per section) applied separately to x, y, z
```

**Parameters**: `fc_g = 0.15 Hz` (range 0.08–0.25); `fc_hp = 0.50 Hz` (range 0.30–0.70); `fc_lp = 8.0 Hz` (range 6–12); `hpOrder = 2` (2 or 4).

**Justification.** Two paths and not a subtraction, even though `a − LP(a)` is mathematically a valid high-pass: the point is not mathematical, it is that **ĝ becomes a first-class signal**, used for posture detection (§3.1), for the per-event `tilt` feature (§2, step 5), for the immobility mask (§3.6) and for autocalibration (§3.3). `fc_g = 0.15 Hz` and not 0.25: a 10 s CLM has a fundamental at 0.1 Hz; a gravity low-pass that is too fast would "swallow" the slow part of long CLMs and falsify `tiltExcursion`.

**Edge behaviour.** Each biquad section is initialised to the steady state corresponding to the mean of the **first 2 seconds** of the segment (instead of a zero state, which would produce a transient the size of gravity, i.e. ~1 g). The **first 5 seconds** of each segment are nevertheless excluded from the analysis and from the denominator (`warmupSec = 5.0`, range 3–10). This is trap no. 3 of v1, handled correctly: **stateful streaming** implementation mandatory, a single pass over the whole segment, never block by block.

### Step 2 — Magnitude and two-scale envelope

```
m(t)      = √(a_lin,x² + a_lin,y² + a_lin,z²)          [invariant under a constant rotation of the strap]
env_c(t)  = √( running_mean( m², W_c = 0.50 s ) )      [decision]
env_f(t)  = √( running_mean( m², W_f = 0.15 s ) )      [onset/offset refinement]
```

**Parameters**: `W_c = 0.50 s` (range 0.35–0.80); `W_f = 0.15 s` (range 0.10–0.25).

**Justification.** See §1.4. A statistical point to know and not to "fix": `m` on pure noise follows a Maxwell distribution of mean 1.596 σ and coefficient of variation 42 % — **the L2 magnitude is not zero-mean**, it has a pedestal. That is not a problem as long as the floor is estimated **on the same quantity** (which is what step 3 does): the `k_on` ratio is then self-consistent. One simply has to know that "8× the floor" means 8× a Maxwell mean, not 8× a per-axis standard deviation (= 12.8 σ).

**Edge behaviour.** Windows truncated over the first and last `W/2` seconds of each segment, with normalisation by the number of valid samples; these zones are included in the `warmup` already excluded.

### Step 3 — Adaptive noise floor

Three-pass estimator, non-causal, segmented — full formula in §1.3. Causal variant for the incremental mode in §3.7.

**Parameters**: `floorWinSec = 120.0` (range 60–240); `floorP1 = 25` (percentile of pass 1, range 15–40); `k_excl = 4.0` (range 3–6); `minValidFrac = 0.25` (range 0.15–0.40).

**Edge behaviour.** Bilateral window truncated at segment boundaries **and at posture-change boundaries**. If less than 40 s of valid data are available in the window, the floor is taken from the nearest valid value and the interval is marked `FLOOR_EXTRAPOLATED`; the CLMs detected in these intervals carry the flag and are counted separately in the quality report. The end of a truncated night is systematically in this case over the last 60 seconds (§3.7).

### Step 4 — Thresholds

```
Θ_on(t)  = max( k_on  · floor(t),  Θ_abs,        f_cal · gainCal )
Θ_off(t) = max( k_off · floor(t),  Θ_abs · 0.31,  f_cal · gainCal · 0.31 )
```

The factor 0.31 = k_off/k_on = 2.5/8.0 preserves the hysteresis whichever term dominates. Hysteresis ratio = 3.2.

**Parameters**: `k_on = 8.0` (range 5–12); `k_off = 2.5` (range 2.0–4.0); `Θ_abs = 0.020 g` (range 0.010–0.050); `f_cal = 0.12` (range 0.08–0.20).

**Justification of the choice of k_on.** The factor is **not** dictated by thermal noise: for a Rayleigh-type envelope, with ~2 independent samples per second after the 0.5 s window, i.e. 5.76 × 10⁴ draws over 8 h, a factor of **4.8 on the median** would already be enough to guarantee fewer than 0.01 thermal false positives per night. The factor 8 is therefore entirely an **anti-artefact** budget, not an anti-noise one. This matters: it must not be tuned by looking at noise, it must be tuned by looking at real nights (§3.3, test T11).

### Step 5 — LM detection, then classification into CLM

State machine on `env_c`, refinement on `env_f`:

1. **Provisional onset**: first `t` such that `env_c(t) ≥ Θ_on(t)`.
2. **Provisional offset**: first `t' > t` such that `env_c` stays `< Θ_off` for **at least `offHoldSec = 0.50 s`** continuously. The offset is dated at the **start** of that period (literal AASM/WASM rule: "the START of a period lasting at least 0.5 s during which the EMG does not exceed 2 µV above resting").
3. **Refinement**: move the onset back to the last passage of `env_f` below `Θ_off` before the provisional onset; move the offset forward to the last passage of `env_f` above `Θ_off` before the provisional offset.
4. **Morphology threshold (WASM 3.2.1-d, applied to both rule sets)**: reject if the event contains no 0.50 s window whose **median** of `env_f` is ≥ `Θ_off`. Rejection `MORPHOLOGY`.
5. **Classification**:
   - `duration < 0.5 s` → rejection `TOO_SHORT` (it is not an LM).
   - `0.5 s ≤ duration ≤ 10 s` → **CLM**.
   - `duration > 10 s` → **long LM**: never a CLM; kept in the list with the `LM_LONG` flag because it **breaks the series** (WASM 3.3.6, and optionally in AASM).
6. **Gross body movement (GBM)**: if `peak ≥ k_gbm · floor` OR `|tiltChange| > postureDeg` OR `duration > 10 s` → classified `GROSS_BODY`, excluded from the CLMs, and a **refractory period** of `refractorySec` is applied on either side (no CLM is accepted there; the floor is already protected there by iterative exclusion).
7. **Posture**: see §3.1.

**Features extracted from ĝ for each event** (they carry the information the 0.5 Hz high-pass throws away, cf. §1.2):

```
tiltChangeDeg    = angle( ĝ_u(offset + 2 s), ĝ_u(onset − 2 s) )                  // net, persistent change
tiltExcursionDeg = max over [onset, offset] of angle( ĝ_u(t), ĝ_u(onset − 2 s) ) // transient excursion
```

**Parameters**: `offHoldSec = 0.50` (fixed, clinical); `clmMinSec = 0.50` (fixed, clinical); `clmMaxSec = 10.0` (fixed, clinical); `morphoWinSec = 0.50` (range 0.3–0.8); `k_gbm = 40.0` (range 25–60); `refractorySec = 2.0` (range 1–4).

**Edge behaviour.** An LM whose onset precedes the start of the analysable segment, or whose offset is not reached before the end of the segment, is marked `TRUNCATED`: it is not counted as a CLM (its duration is unknown) but it **breaks the series** like a long LM, which is the conservative behaviour.

### Step 6 — Series construction

Two literal implementations, never a single parameter.

**AASM_V3** — onset-to-onset IMI ∈ [5, 90] s; ≥ 4 consecutive CLMs; **at least a portion of each CLM must fall within an epoch of sleep** (this is the v3 change, cf. Summary of Updates v3); IMI < 5 s: the later movement is ignored and the period is measured up to the next candidate (WASM 2006 convention, for want of an AASM rule — labelled `INTERPRETED`); `breakOnLongLm = false` by default in order to stay literal.

**WASM_2016** — IMI ∈ [10, 90] s; ≥ 4 CLMs (= 3 IMIs); **an IMI < 10 s or > 90 s breaks the series** (3.3.6); **an LM > 10 s breaks the series**; a series may **cross** a wake/sleep transition (2.4.4); `breakOnLongLm = true`.

**Output**: `plmsCount` (CLMs in a series, during sleep), `plmwCount` (CLMs in a series, during wake within the SPT — a WASM metric, not defined by the AASM), `isolatedCount` (CLMs outside any series), `shortImiCount` (CLMs with an IMI < the lower bound).

**Edge behaviour.** A series truncated by the start or the end of the recording remains valid if it already counts ≥ 4 CLMs; otherwise it is dropped and counted in `truncatedSeriesDropped` (to be reported: it is a measurable downward bias, and it increases on an interrupted night).

### Step 7 — Indices

```
PLMI = plmsCount / TST_analysable_heures
PLMW = plmwCount / WASO_analysable_heures
```

`TST_analysable` = TST ∩ (valid segments) ∩ (outside blind zones) ∩ (outside off-body) ∩ (outside warmup).

**Ferri's Periodicity Index**, over the CLMs during sleep:

```
IMI_k = onset_{k+1} − onset_k                      k = 1..N−1
qualifying(k)  ⟺  10 < IMI_k ≤ 90    (seconds)
Cut the sequence of IMIs into maximal runs of consecutive qualifying intervals.
PI = ( Σ length(R) over every run R of length ≥ 3 ) / (N − 1)
piValid = ( N / TST_h ≥ 10 )
```

Documented convention: **strict lower bound, inclusive upper bound**, and **numerator in intervals** (Ferri also publishes a form in movements, `PLMS_alt / LMS_total`, slightly higher; both exist in his own papers, and they must never be mixed). Reference threshold ≈ **0.50** (Ferri et al., Sleep Med 2016;22:97-99). Reference values: RLS 0.601 ± 0.189; controls 0.092 ± 0.152 (Ferri, JCSM 2022) — but 0.220 ± 0.229 for controls in Mogavero 2024, the instability of the control group being exactly the effect of the small denominator.

Also produced: `plmiSpt` (denominator = SPT, see §3.6), `plmiFirstHalf` / `plmiSecondHalf` (split-half), the **IMI histogram** (2 s bins from 0 to 100 s — the 2–4 s / 22–26 s bimodality is the raw diagnostic information and must be displayed), and the bracket `[plmiLowerBound, plmiUpperBound]` coming from the two masks (§3.6).

---

## 3. Explicit handling of the identified problems

### 3.1 Gravity, movement, and posture changes

**The problem, quantified.** A turn changes the projection of gravity onto an axis by up to **1 g** in 0.5–3 s. Passed through the high-pass, that step produces a transient of initial amplitude ≈ the amplitude of the step and of time constant τ = 1/(2π·f_c) = **0.32 s** at 0.5 Hz, i.e. noticeable ringing over ~2 s (order 2). A typical CLM is 30–200 mg. **The posture artefact is therefore 5 to 30 times larger than a real CLM and lasts exactly the right duration to be counted.** It is, by far, the leading source of false positives.

**Posture detector** (operates on ĝ, not on the envelope):

```
ĝ_u(t)    = ĝ(t) / ‖ĝ(t)‖
Δφ(t, τ)  = arccos( clamp(ĝ_u(t+τ) · ĝ_u(t−τ), −1, 1) ) · 180/π

Posture change at t  ⟺  Δφ(t, τ_p) > postureDeg
                        AND  ĝ_u stays within a cone of stableDeg around ĝ_u(t+τ_p)
                             for at least stableSec
```

Candidate CLMs whose onset falls within `[t − guardSec, t + guardSec]` are marked `POSTURAL` and **excluded by default** (but kept in the database, with the flag, so that a recomputation is possible).

**Parameters**: `τ_p = 2.0 s`; `postureDeg = 20.0°` (range 12–30); `stableDeg = 10.0°`; `stableSec = 10.0 s` (range 5–20); `guardSec = 2.5 s` (range 1.5–4).

Two side effects handled: (a) posture boundaries **cut the floor-estimation windows** (§1.3); (b) they also define the segments over which the mechanical gain is homogeneous (§3.3).

**Discrimination through the tilt features.** They separate three classes: `tiltExcursion ≈ 0` → transmitted vibration or noise; moderate excursion with a small `tiltChange` → genuine CLM (the limb moves and comes back); large and persistent `tiltChange` → posture.

### 3.2 Mattress-transmitted artefacts

**State of knowledge: there is no published quantification** of the transmission of a partner's movement through a mattress to a body-worn sensor. No amplitude in mg, no transfer function, no frequency characterisation, no ankle-specific study. That is a real hole. Every value given here is an engineering assumption to be measured, not a datum.

**Three defences, in order of expected effectiveness.**

1. **The WASM 3.2.1-d morphology threshold** (step 5.4). A transmitted vibration is a **brief ring**: impulse + decay over 0.05–0.4 s, at the resonant frequency of the mattress/strap pair. Requiring a 0.50 s window whose envelope **median** exceeds Θ_off eliminates by construction anything without a half-second plateau. A 0.3 s ring has a high peak but a low median over 0.5 s. It is the only filter in this catalogue that is a published clinical rule and not an invention.

2. **The `tiltExcursionDeg` feature**. A movement of the leg itself displaces the segment: ĝ_u rotates. A transmitted vibration shakes the sensor without reorienting the segment: ĝ_u does not rotate. Rule: mark `TRANSMITTED_SUSPECT` if `tiltExcursionDeg < minExcursionDeg` (default **1.5°**, range 0.5–4) **and** `duration < 1.5 s`. **Reported, not excluded by default** — because an isolated dorsiflexion can also produce a near-zero excursion at the sensor (that is Terrill's mechanism, §0-d), and excluding those events would worsen the downward bias already present. We would have two errors pointing the same way.

3. **The adaptive floor itself.** A continuous stream of micro-vibrations raises the local floor, which raises the threshold: the system immunises itself against a restless partner, at the price of reduced sensitivity that night. That is the desirable behaviour, and the flag to display is the **ratio of the night's median floor to the inter-night median**; beyond +50 %, the night must be marked "noisy environment, reduced sensitivity".

**Measurement protocol to carry out** (it does not exist in the literature, it has to be produced): a control night with the watch at the ankle and nobody else in the bed, then an awake session where a third party deliberately turns over 30 times at a known distance. That gives the real amplitude and spectrum of the transmission for **this** mattress — and that is in any case the only value that counts, since transmission depends entirely on the construction of the mattress (springs vs memory foam: several orders of magnitude apart).

### 3.3 Inter-night normalisation — strap tightness and case position

This is the most serious problem of the project after the sleep mask, because it attacks comparability directly, and comparability is **the whole value** of a 5–7 night campaign. v1 makes do with "keep the same strap and the same tightness", which is not a solution but a wish.

**Two parts, independent and cumulative.**

**Part A — static sensor autocalibration (corrects the sensor, not the coupling).**

Procedure, in the manner of the GGIR/van Hees autocalibration, entirely derived from the night itself, with no intervention:

```
1. Static windows: every 10 s window where sd(x), sd(y), sd(z) < 13 mg.
2. Sphere coverage: require  max(gᵢ) − min(gᵢ) ≥ 0.30 g  for each of the 3 axes
   over the set of static windows. Otherwise → ABANDON, we keep the previous night's
   calibration and raise the flag CALIB_INSUFFICIENT_COVERAGE.
3. Iterative least squares on (offset o ∈ R³, diagonal gain S ∈ R³):
        minimise   Σ_f ( ‖ S ⊙ (a_f − o) ‖ − 1 )²
   3 to 5 Gauss-Newton iterations are enough; initialise at o = 0, S = 1.
4. Reject the calibration if  ‖o‖ > 0.10 g  or  max|S − 1| > 0.05  (suspect sensor).
5. Apply  a ← S ⊙ (a_raw − o)  before any other step.
```

This removes the offset and the gain of the MEMS, which drift with temperature and ageing. It does **nothing** against strap tightness.

**Part B — mechanical calibration ritual (corrects the coupling). This is the part that counts.**

Watch screen, 70 s, at bedtime, before `START` of the night:

```
Phase 1 — 30 s: complete stillness, leg at rest.         → floorCal
Phase 2 — 10 voluntary "comfortable" dorsiflexions, guided by a visual
          metronome at 3 s intervals (total duration 30 s). → gainCal
Phase 3 — 10 s: stillness.                               → check of the return to rest
```

Extraction:

```
floorCal = p50( env_c ) over phase 1
gainCal  = median of the 10 peak amplitudes of env_c in the windows [tᵢ, tᵢ + 2 s]
           (i = 1..10, tᵢ given by the metronome — the ground truth is known)
snrCal   = gainCal / floorCal
```

**Why this works.** `gainCal` is the **direct measurement of the gain of the ankle → strap → case → MEMS mechanical chain for that night**, for a reference physiological gesture whose amplitude is reasonably reproducible from one night to the next in the same subject. It is exactly the variable that tightness moves, and it is measured, not assumed. It is the third term of the threshold (step 4): `f_cal · gainCal`, with `f_cal = 0.12` — a CLM is declared if it reaches 12 % of the amplitude of a comfortable voluntary dorsiflexion.

**Inter-night quality check.** If a night's `gainCal` deviates by more than **±35 %** from the median of the preceding nights of the same campaign, the night is marked `CALIB_OUTLIER`, the value stays on display but **is excluded from the trend and from the ICC**, and the UI shows "strap tightness probably different — retighten and redo". Without this guard rail, we compare apples and oranges while believing we are measuring biological variability.

**Fallback if the ritual is not performed** (the user forgets, the screen does not appear): use as the internal gain reference the **median of the peak amplitudes of the night's gross body movements**. Turns are a physiologically stereotyped event, frequent (20–60/night) and of relatively stable amplitude (Sicbaldi: 377 ± 63 mg during sleep, a CV of 17 % across subjects). It is a poorer internal standard than the ritual, but much better than nothing. The field `gainSource ∈ {RITUAL, GROSS_BODY, NONE}` must accompany **every** published PLMI.

**Associated regression test (T11)**: multiply the whole movement channel of a synthetic night by 0.6 then 1.8 (loose / tight strap). Calibration on → PLMI deviation ≤ 10 %. Calibration off → expected deviation > 40 %. This is the test that **proves** that part B is good for something; if it does not show that gap, part B is folklore and must be removed.

### 3.4 Drift of the real sampling frequency

Handled in full in steps −1 and 0. Three points to remember:

- **Never trust `nominalRateHz`.** The field exists in the header for traceability, not for computation. `fs` is recomputed from `(tFirstNs, tLastNs, N)` of each block — after revalidation, since those fields are not covered by the CRC (step −1).
- **Resample onto a fixed grid rather than adapting the coefficients.** Adapting the filters to a varying `fs` forces the biquads to be recomputed during the session, which causes transients at every change — we would be replacing a 5 % problem with localised artefacts. Resampling costs one linear interpolation and makes everything else exact.
- **An aberrant block `fs` is a symptom of corrupted timestamps**, not of drift: the block is rejected beyond ±5 % of the session median, and accounted for as a gap.

The clock triplet in the header (`startWallMs`, `startElapsedRealtimeNs`, `firstEventTimestampNs`) serves a distinct and equally important purpose: **detecting the drift between the `SensorEvent.timestamp` scale and the wall clock** — some OEMs exclude suspend time. The drift is estimated by regressing `(startWallMs − startWallMs[0])` on `(firstEventTimestampNs − firstEventTimestampNs[0])` across the chunks of the session. A slope departing from 1 by more than 1e-4 (i.e. > 2.9 s over 8 h) must raise `CLOCK_DRIFT`: **the fusion with the Health Connect hypnogram would be offset**, which moves CLMs from one stage to another and falsifies the PLMS/PLMW split. The cross-correlation realignment (§3.6) recovers part of it, but one has to know.

**Benefit of the move to 5 min chunks.** The number of chunks per night goes from ~16 to ~96, so the number of anchor points of the drift regression is multiplied by 6: the uncertainty on the slope improves by a factor **√6 ≈ 2.4**. That is a free and not negligible gain for the fusion with the hypnogram. The arithmetic of the format checks out: 5 min × 60 × 50 Hz × 6 B = 90 000 B, plus the header and the block overhead ≈ **91 kB**, consistent with the `DataItem` constraint.

### 3.5 Respiratory-related leg movements (RRLM) without a respiratory channel

**What we cannot do, and it must be said plainly.** The two published RRLM exclusion rules are temporal and require the respiratory channel: the AASM excludes any LM "from 0.5 s before an apnoea/hypopnoea/RERA to 0.5 s after" — the **whole** event is bracketed, which the AASM confirmed explicitly in the FAQ (item M.5) — whereas WASM 2016 recommends two alternative rules, including the one from Manconi et al. (Sleep 2015): **−2.0 s to +10.25 s around the end** of the respiratory event. Without a respiratory channel, neither is computable. Full stop.

**Periodicity saves nothing.** One might hope to separate PLMS from RRLM by their IMI. The mode of the PLMS IMI is at **22–26 s** (Ferri); the apnoeic cycle in OSA is typically **25–45 s**. **The distributions overlap.** Any rule based on the IMI alone would confuse the two. Ferri's PI saves nothing either, for the same reason: **RRLMs are periodic**. This has to be said, rather than letting people believe that the periodicity guard rail covers that risk.

**The magnitude of the bias is enormous, not marginal.** On the same cohort, the WASM rule (−2/+10.25 s) classifies **90.7 ± 112.1 events/h** as respiration-related, against **50.5 ± 70.2** for the AASM rule (±0.5 s) — a factor of 1.8 **between two official definitions**, with the respiratory channel available (Sleep Breath 2023, PMC10163289). The uncertainty introduced by the mere choice of exclusion window **exceeds by an order of magnitude** the detector's own error. Claiming ±10 % on the PLMI of an apnoeic subject would be dishonest.

**What we can do, concretely, in order of solidity.**

1. ~~**Lock the report behind OSA screening.**~~ `RespiratoryConfidence ∈ {HIGH, MEDIUM, LOW}`, derived from a STOP-BANG in the questionnaire, from the AHI if Health Connect exposes one, and from the RLS questionnaire flag; under `LOW`, the UI would not have displayed a PLMI.

   > **Abandoned, and the reason must be stated rather than the line struck out.** This lock was written, and
   > **nothing ever fed it**: no source filled `RespiratoryConfidence`, so every night came out at the
   > same level, so the gate could never close. **A protection that never triggers is worse than an
   > absent one, because it documents itself as a protection.** The lock and the confidence level were
   > removed from the product, and with them any claim to apnoea screening: Pendulum measures leg
   > movements in the context of restless legs syndrome, and nothing else. What remains is point 2
   > below — a bracket that detects nothing and claims nothing. See
   > [`../02-science.md`](../02-science.md), section *Pendulum does not screen for sleep apnoea, and
   > will not*.

2. **Publish a bracket, never a point.** Compute and display `plmiRaw` and `plmiRespWorstCase`, where the worst case removes all CLMs belonging to series whose median IMI falls in the apnoeic band 25–45 s. This is not an RRLM exclusion — it is **a guaranteed lower bound**. The true value lies between the two. The gap between the two bounds is in itself the uncertainty indicator to display.

3. **Display the IMI histogram.** A clear bimodality with a secondary mode at 30–40 s is a visual RRLM signal that a sleep physician can interpret, where a scalar is not. That is information handed over, not a decision taken in their place.

4. **Respiratory surrogate derived from the ankle accelerometer — EXPERIMENTAL, disabled by default.** There is **no published evidence** that an ankle accelerometer picks up respiration; the corpus documents it only at the thorax and marginally at the wrist. The lead: look, in the 0.15–0.50 Hz band of the signal *before* the high-pass, for a cyclic amplitude modulation of 25–45 s (the crescendo-decrescendo signature of periodic breathing), then test the correlation of its phase with the CLM onsets. This must be implemented behind a flag, produce a dedicated metric, and **never influence the PLMI** as long as no validation exists.

**Direction of the bias, to be recalled in the report.** The RRLM bias is **upward**; the unilateral-measurement bias and the Terrill bias (39 % of EMG LMs invisible to accelerometry) are **downward**. They do not cancel out — they depend on different subjects and different mechanisms, and their variances add. v1 was right to write it; it must be kept word for word.

### 3.6 Accelerometric sleep mask, circularity of the denominator, and fusion with the hypnogram

#### 3.6.1 Rejection of Cole-Kripke

Three reasons.

1. **It does not take g as input**, but *activity counts* — a proprietary ActiGraph transformation (filtering, rectification, thresholding, integration per epoch). There is no published conversion from raw g to counts. Any "Cole-Kripke" implementation on raw g is an invention that borrows Cole's coefficients without borrowing the preprocessing they were fitted for. The open-source implementations themselves agree neither on that preprocessing (`actigraph.sleepr` divides by 100 and clips at 300; BioPsyKit does nothing) nor on the coefficients (central weight at 30 s: 121 in BioPsyKit, 12 in pyActigraphy).
2. **It is validated at the wrist.** At the ankle, it overestimates TST by **+43 min [20, 66]**, against −8 min [−28, 13] at the wrist.
3. **The direction of the bias is the worst possible for us.** An overestimated TST **deflates** the PLMI: +43 min on 420 min → PLMI × 0.907. A true PLMI of 15.0 displays as **13.6** — below the screening threshold. The algorithm that is most "accurate" at the wrist is the one that makes the diagnosis be missed at the ankle.

#### 3.6.2 Adopted: van Hees-type sustained inactivity rule, adapted to the ankle and made orientation-invariant

Reason for the choice: in the same study, the GGIR algorithms (van Hees, Galland) are the **only** ones to show **no significant wrist/ankle difference** — exactly the property we need, since the position of the case varies from one night to the next.

Original rule (van Hees 2015, PLOS ONE 10(11):e0142533): 5 s rolling median per axis, arm angle `atan(a_z / √(a_x² + a_y²))·180/π`, mean per 5 s epoch, **sustained inactivity = no angle change > 5° for ≥ 5 consecutive minutes**. Validated at 83 % accuracy vs PSG (n = 28) with a **31 min overestimation**.

Adaptation adopted:

```
ĝ_k        = mean of ĝ_u over epoch k (duration epochSec = 5 s)
Δφ_k       = arccos( ĝ_k · ĝ_{k−1} ) · 180/π                  // orientation-invariant
amp_k      = p95( env_c ) over epoch k

immobile(k) ⟺ Δφ_k ≤ angleDeg  AND  amp_k < k_move · floor(k)

Sustained inactivity = run of consecutive immobile epochs of duration ≥ sustainedMin
SPT = from the start of the first run ≥ sptMinMin to the end of the last
WASO = non-immobile epochs inside the SPT
TST  = SPT − WASO
```

**Parameters**: `epochSec = 5.0`; `angleDeg = 5.0` (range 3–8); `k_move = 6.0` (range 4–10); `sustainedMin = 5.0` (range 3–10); `sptMinMin = 15.0` (range 10–30).

Two improvements on van Hees: the **Δφ of the unit gravity vector** replaces the angle on a named axis (`angle_z` assumes a known anatomical orientation, which is not our case); the **additional amplitude criterion** catches vibratory movements without reorientation, invisible to a purely angular criterion.

Known bias to be accepted: van Hees at the ankle underestimates TST by **−89 min [−116, −63]** in the only study available (children, n = 29), which **inflates** the PLMI by ~27 %. We are therefore trading a bias of −9 % (Cole-Kripke) for a bias of +27 % (van Hees), gaining site invariance. **Neither is acceptable uncorrected** — hence the fusion in §3.6.4.

#### 3.6.3 The circularity of the denominator — statement, severity, and a codable answer

**Precise statement.** `PLMI = numerator(movement) / denominator(TST deduced from the absence of movement)`. Both terms come out of the same signal, and they are **anti-correlated by construction**. The more PLMS the subject has, the more movement the immobility mask sees, the less sleep it scores, the lower the TST, the higher the PLMI. **This is positive feedback: the metric amplifies itself.**

**The severity is far worse than a mere bias of a few percent.** The van Hees rule requires **≥ 5 consecutive minutes** without movement. A PLMS series at IMI 22 s places ~13 movements in any 5 min window. **The probability that a 5 min window is free of all movement during a series is nil.** Consequence: applied naively, the mask scores **the entire series period as wake**. Two contradictory and both catastrophic effects follow on:

- the TST collapses → the PLMI explodes;
- and simultaneously the AASM v3 rule "at least a portion of the CLM in an epoch of sleep" **removes the CLMs themselves**, since they are now in wake.

The sickest subject is the one for whom the algorithm behaves worst. That is a disqualifying failure mode if it is not dealt with.

**Answer, in three layers, all of them codable.**

**Layer 1 — make the mask blind to the CLMs.** The immobility mask must be built on **evidence of mobility from which the CLMs are excluded**. Clinically, that is the only tenable position: a PLMS is *by definition* a movement **during** sleep; using it as evidence of wake is a category error. The AASM scores PLMS *within* epochs of sleep; a leg movement does not make the epoch a wake epoch except under a cortical arousal criterion, which we cannot assess without EEG.

```
ImmobilityMask.build(gravity, env, floor, segments, offBody, ignoreIntervals = clmIntervals, cfg)
```

The intervals passed in `ignoreIntervals` are **neutralised**: the epoch containing them is rescored from the neighbouring epochs (nearest-neighbour immobile/mobile interpolation), and not counted as mobile. What remains as evidence of wake: **gross body movements**, **posture changes**, and periods of sustained mobility longer than `epochSec` not attributable to a CLM. That is exactly the right discrimination, and it is already available: the GBM classifier (step 5.6) and the posture detector (§3.1) produce those labels.

**Layer 2 — fixed point bounded to 2 iterations.**

```
1. Provisional mask M₀ : immobility WITHOUT ignoreIntervals (degraded, but it bounds the SPT)
2. Detection C₀        : CLMs with M₀ (the AASM "portion in sleep" constraint is NOT
                         yet applied at this iteration)
3. Mask M₁             : immobility with ignoreIntervals = intervals of C₀
4. Detection C₁ + series: this time with M₁ and all the rules
5. Convergence check   : |TST(M₁) − TST(M₀)| must be < 25 % of TST(M₀)
                         otherwise → flag MASK_NON_CONVERGENT
```

**Two iterations, never more.** A fixed point iterated without a bound on a non-monotonic criterion can oscillate; two passes are enough because layer 1 removes the feedback loop, it does not merely attenuate it. Non-convergence is a signal, not an error to be hidden: it indicates a night on which movement and immobility do not separate, and that night must not produce a publishable PLMI.

**Layer 3 — prefer an independent denominator whenever one exists.** This is the decisive argument in favour of Health Connect, and it is not only "HC brings the stages": **HC is independent**. Another wrist, another sensor, another algorithm, another device. No circularity. When HC answers, the circularity disappears entirely, and that is the best reason to prioritise it.

#### 3.6.4 Fusion with Health Connect, in four steps

```
1. TEMPORAL REALIGNMENT. Build s_accel(k) ∈ {0,1} (immobile) and s_hc(k) ∈ {0,1} (SLEEP*)
   on the same 5 s grid. Search for the offset λ ∈ [−10 min, +10 min] maximising agreement.
   Apply λ. If |λ| > 5 min → flag HC_LAG_SUSPECT.
   Reason: two devices, two clocks, plus a sleep-onset latency that genuinely differs
   between wrist and ankle. Without this realignment, we assign CLMs to the wrong stage.
2. AGREEMENT. Compute Cohen's κ between s_accel and s_hc after realignment, and ΔTST.
   Reported systematically (criterion P5 of v1).
3. LEARNED CORRECTION. On the nights that have both masks (≥ 3 required),
   fit  TST_HC ≈ α · TST_accel + β  (robust regression, or simply the median of the
   ratio if n < 5). On the nights without HC, apply the correction and mark TST_CORRECTED.
   If n < 3 → no correction, only the bracket of point 4.
4. BRACKET. Always produce the FOUR PlmiResult values (2 rules × 2 masks) and publish
   plmiLowerBound = min, plmiUpperBound = max over the two masks, at a fixed rule.
   The UI displays the interval, not the point.
```

When HC answers, the **HEALTH_CONNECT** mask is primary (it brings the stages, hence the N1/N2/N3/REM distribution of the CLMs, which is the level 5 biological plausibility check of v1, **and** it breaks the circularity). When HC does not answer, the corrected **ACCEL_IMMOBILITY** mask becomes primary, `stage = SLEEP` undifferentiated, and the per-stage distribution is not produced.

A rule point not to be missed: AASM v3 requires that **at least a portion of each CLM fall within an epoch of sleep**, whereas WASM 2016 explicitly allows a series to cross a sleep/wake transition (2.4.4). The two implementations must therefore consume the mask **differently**; a single mask wired to a parametric `SeriesRule` would produce a wrong result for one of the two.

#### 3.6.5 When Health Connect NEVER answers — on any night

This is the case that has to be handled explicitly, because layer 3 disappears and only layers 1 and 2 remain, which attenuate the circularity without removing it. Four answers, in order of preference.

**(a) The manual sleep diary — the cheapest and the best solution.** Two fields in the phone UI: bedtime, rise time. It is a denominator **totally independent of the signal**, hence strictly non-circular. Reminder: van Hees 2015 himself **required** a diary; the version without a diary (van Hees 2018) is a later and less accurate extension. Ten lines of code on the `phone` side, an optional `DiaryWindow(bedTimeMs, riseTimeMs)` on the `algo` side, and the structural problem disappears. **This is the recommendation.**

**(b) Switch the denominator to the SPT rather than the TST.** The SPT depends only on the **two extreme transitions** of the night (first and last inactivity run ≥ 15 min), which are far from the PLMS-dense core. It is therefore almost insensitive to the circularity, where the TST is directly exposed to it via the WASO. `plmiSpt = plmsCount / SPT_h` is produced systematically and becomes **the primary index in the absence of HC**. It is numerically lower than a PLMI/TST (the SPT being longer than the TST) — it must therefore **never** be compared with the 15/h threshold, which must be written in the UI and not only in the code.

**(c) Make Ferri's PI the primary index.** The PI is a **ratio of intervals over intervals**: numerator and denominator both come from the "movement" side. **It has no temporal denominator, hence no circularity, and no dependence on the sleep mask.** It is structurally the most robust metric we have, and the literature confirms this independently: its inter-night variability is **6.5 times lower** than that of the PLMI in RLS and 2 times lower in PLMD (Ferri et al., Sleep Med 2013;14:293-296). Reference threshold 0.50. Validity condition: ≥ 10 LM/h. In the definitive absence of HC, **the report must put the PI first and the PLMI second**, which is also the better scientific choice, not merely a fallback.

**(d) Refuse the comparison with the threshold.** If (a) is not provided, the output is: `piValue`, the raw `plmsCount` per night, `plmiSpt` with its bracket — and **no display of "PLMI = X, threshold = 15"**. The field `denominatorIndependence ∈ {INDEPENDENT_HC, INDEPENDENT_DIARY, SPT_QUASI_INDEPENDENT, CIRCULAR}` accompanies every result and directly drives what the UI is allowed to display.

### 3.7 Incremental processing and truncated night (5 min chunks)

The move to 5 min chunks pushed every ~15 min changes the execution contract: the analysis **can** be incremental, and the night **can** stop abruptly at 3 in the morning (battery, crash, watch removed).

#### 3.7.1 What the v2 chain requires and what it tolerates

**The v2 chain does NOT require the whole night — but its reference result does.** Two modes must be distinguished, and they must never be confused in the database.

| Step | Streamable? | Constraint |
|---|---|---|
| −1 integrity | yes | purely local, except the inter-block checks (window of 1 block) |
| 0 timeline | yes | `fs_session` must be re-estimated and **frozen after ~15 min** of data, otherwise the grid changes retroactively |
| 1 gravity / movement | **yes, natively** | biquads already `stateful streaming` (v1 requirement, trap no. 3) |
| 2 envelopes | yes | running sums, latency `W_c/2 = 0.25 s` |
| 3 floor | **no in bilateral** | causal variant required (below) |
| 4–5 CLM detection | yes, with latency | latency = `floorWinSec/2 + settle` in bilateral, `settle` alone in causal |
| 6 series | yes | incremental state machine; a series stays "open" until `imiMaxSec` of silence |
| 3.1 posture | yes | latency `τ_p + stableSec = 12 s` |
| 3.3 autocalibration | **no** | requires sphere coverage, hence several postures: available only at the end of the night |
| 3.6 mask | **no** | the SPT requires the last inactivity run of the night |
| 3.6.3 fixed point | no | requires the mask |

**Architectural conclusion: two modes, a single reference result.**

- **PROVISIONAL MODE (incremental, every ~15 min).** Filters, envelopes, posture, CLM detection and the series state machine run in a stream with a **causal floor**. Produces: CLM count, closed-series count, provisional PI, and a provisional `plmiSpt` computed over the elapsed time. Intended **for real-time display only** and for quality diagnosis during the night ("sensitivity has collapsed, the strap has moved"). **Never written into `plm_result`.**
- **DEFINITIVE MODE (on waking, over the whole session).** Replays the entire chain from the chunks, with the bilateral floor, the full autocalibration, the full sleep mask, the 2-iteration fixed point and the HC fusion. **The only mode that produces the 4 `plm_result` rows.**

The full replay costs O(n) over 1.44 × 10⁶ samples: a few hundred milliseconds on a phone. **There is no reason to keep an incremental result as the final result**, and the temptation to do so to "save" something would be a methodological regression.

**Causal variant of the floor (provisional mode only).** This is where the reviewer's proposal 3 finds its legitimate place:

```
Pass 1 : floor₀(t) = p25 of env over a LAGGED CAUSAL window [t − 125 s, t − 5 s]
Pass 2 : mask M = { i : env[i] > k_excl · floor₀[i] }           (same k_excl)
Pass 3 : floor(t)  = median of env over the same window, restricted to {i ∉ M}
Then   : max with Θ_abs / k_on, as in the bilateral case.
```

The 5 s lag prevents the event in progress from contaminating its own floor; the 120 s window preserves robustness. The 35 s lag bias described in §1.3 remains — it is **acceptable in provisional mode, unacceptable in definitive mode**, which is exactly why the two modes exist. The field `floorMode ∈ {BILATERAL, CAUSAL_LAGGED}` accompanies every result.

#### 3.7.2 What happens if the night stops at 3 in the morning

Five consequences, each with a rule.

1. **The SPT has no end.** The last inactivity run is open. Rule: `SPT_end = last valid sample`, flag `TRUNCATED_NIGHT`. Do not extrapolate.
2. **The PLMI of a truncated night is biased UPWARD, and not correctably so.** PLMS are not uniformly distributed: they concentrate in N1/N2 and in the first half of the night. A night cut at 3 h preferentially samples the rich part. That is precisely what the v1 split-half `plmiFirstHalf / plmiSecondHalf` already measures — on a truncated night, that ratio is the best indicator of the size of the bias and must be displayed next to the figure.
3. **Publication gates, hard-coded**:
   - `analysableTstMin ≥ 240` (4 h) → PLMI published normally.
   - `180 ≤ analysableTstMin < 240` → PLMI published with `TRUNCATED_NIGHT`, **excluded from the multi-night trend, from the ICC and from the computation of the TST correction** (§3.6.4 point 3).
   - `analysableTstMin < 180` (3 h) → **no PLMI published.** What is published: `plmsCount`, `piValue` if `piValid`, and the quality report. The PI remains valid because it has no temporal denominator — one more point for §3.6.5(c).
4. **The last 60 seconds have a truncated floor** (bilateral window cut short) → `FLOOR_EXTRAPOLATED` systematically at the end of a truncated night. No further action: the mechanics of step 3 handle it and flag it.
5. **The series in progress at the moment of the cut** is subject to the rule of step 6: kept if it already counts ≥ 4 CLMs, otherwise dropped and counted in `truncatedSeriesDropped`. On a truncated night, this counter must be displayed: it quantifies the downward bias that partially opposes the upward bias of point 2. The two do not cancel out and it must not be claimed that they do.

**Collateral benefit of the 5 min chunks, to note**: a crash costs at most 5 min of data instead of 30, which makes the P1 criterion of v1 ("largest gap < 5 s, cumulative < 2 min") markedly easier to meet in the case of a restart after a reboot.

---

## 4. Public API of the `algo` module

Pure functions, no I/O, no wall clock, `fs` always explicit, no `import android.*`. **`:algo` must not depend on `:format`**: the input is a minimal interface, the adapter lives in `:phone`. That keeps `:algo` testable without the codec, allows synthetic data to be injected, and — an important point given step −1 — allows `algo` to revalidate the timestamps without inheriting the (absent) guarantees of the `:format` CRC.

### 4.1 `com.pendulum.algo.model`

```kotlin
package com.pendulum.algo.model

/** Minimal input. Implemented by an adapter on top of com.pendulum.format.DecodedBlock. */
interface SampleBlock {
    val tFirstNs: Long
    val tLastNs: Long
    val flags: Int
    val x: FloatArray   // g
    val y: FloatArray
    val z: FloatArray
}

/** Tri-axial signal on a uniform grid. NaN = missing sample. */
class TriAxial(
    val fsHz: Double,
    val t0Ns: Long,
    val x: FloatArray, val y: FloatArray, val z: FloatArray,
) {
    val n: Int get() = x.size
    fun tNs(i: Int): Long = t0Ns + Math.round(i * 1e9 / fsHz)
    fun tMsRel(i: Int): Long = Math.round(i * 1000.0 / fsHz)
}

class Signal1D(val fsHz: Double, val t0Ns: Long, val v: FloatArray) { val n: Int get() = v.size }

data class DualEnvelope(
    val coarse: Signal1D, val fine: Signal1D,
    val coarseWinSec: Double, val fineWinSec: Double,
)

enum class GapKind { MICRO, BLIND, SEGMENT_BREAK }
data class Gap(val fromIdx: Int, val toIdx: Int, val kind: GapKind, val durationSec: Double)

/** Continuous, analysable interval. Bounds as indices into the uniform grid. */
data class Segment(val fromIdx: Int, val toIdx: Int) { val length: Int get() = toIdx - fromIdx }

// --- Integrity (step −1) ---

enum class IntegrityViolation {
    BAD_COUNT, BAD_TIMESTAMP_ORDER, IMPLAUSIBLE_RATE, NON_MONOTONIC, OVERLAP,
    IMPLAUSIBLE_GAP, BAD_CHUNK_INDEX, IMPOSSIBLE_JERK, SATURATED, GRAVITY_IMPLAUSIBLE,
    FLAG_INCONSISTENT,
}

data class IntegrityReport(
    val blocksTotal: Int, val blocksRejected: Int,
    val byViolation: Map<IntegrityViolation, Int>,
    val rejectedFraction: Double,
    val decodeSuspect: Boolean,
) { val acceptable: Boolean get() = rejectedFraction <= 0.01 && !decodeSuspect }

data class IntegrityConfig(
    val maxRateDeviation: Double = 0.20,
    val maxGapNs: Long = 14L * 3600 * 1_000_000_000,
    val maxJerkG: Float = 8.0f,
    val saturationFraction: Double = 0.05,
    val gravityRangeG: ClosedFloatingPointRange<Float> = 0.80f..1.20f,
)

data class FsEstimate(
    val fsSessionHz: Double,
    val fsNominalHz: Double,
    val blocksRejected: Int,
    val clockDriftPpm: Double,          // drift of SensorEvent.timestamp vs the wall clock
    val clockDriftSuspect: Boolean,
)

data class Timeline(
    val signal: TriAxial,               // uniform grid at targetFsHz, NaN inside the gaps
    val gaps: List<Gap>,
    val segments: List<Segment>,
    val blindZones: List<Segment>,
    val fs: FsEstimate,
    val offBody: List<Segment>,
    val integrity: IntegrityReport,
    val analysableSec: Double,
    val truncated: Boolean,             // the session stops without a clean end marker
)

// --- Events ---

object ClmFlags {
    const val POSTURAL             = 1 shl 0
    const val GROSS_BODY           = 1 shl 1
    const val LM_LONG              = 1 shl 2   // > clmMaxSec: breaks the series, never a CLM
    const val TRUNCATED            = 1 shl 3
    const val IN_BLIND_ZONE        = 1 shl 4
    const val FLOOR_EXTRAPOLATED   = 1 shl 5
    const val TRANSMITTED_SUSPECT  = 1 shl 6
    const val ABS_FLOOR_LIMITED    = 1 shl 7   // the threshold was dominated by Θ_abs
    const val CAL_FLOOR_LIMITED    = 1 shl 8   // the threshold was dominated by f_cal·gainCal
    const val DURING_WAKE          = 1 shl 9
    const val OFF_BODY             = 1 shl 10
    const val RESP_SUSPECT         = 1 shl 11  // series whose median IMI falls in the apnoeic band
}

enum class ClmRejectReason { TOO_SHORT, MORPHOLOGY, POSTURAL, GROSS_BODY, BLIND_ZONE, OFF_BODY, TRUNCATED }

data class Clm(
    val onsetIdx: Int, val offsetIdx: Int,
    val onsetMsRel: Long, val durationMs: Int,
    val peakAmpG: Float, val medianAmpG: Float,
    val noiseFloorG: Float, val thresholdOnG: Float, val thresholdOffG: Float,
    val tiltChangeDeg: Float, val tiltExcursionDeg: Float,
    val flags: Int,
    val reject: ClmRejectReason?,
) {
    val isClm: Boolean get() = reject == null && (flags and ClmFlags.LM_LONG) == 0
}

data class PostureChange(val atIdx: Int, val atMsRel: Long, val deltaDeg: Float, val settleMs: Int)

enum class SeriesRule { AASM_V3, WASM_2016 }
enum class ShortImiPolicy { BREAK_SERIES, SKIP_LATER }

data class PlmSeries(
    val rule: SeriesRule,
    val clmIndices: IntArray,          // indices into the list of retained Clm
    val imiSec: FloatArray,            // size = clmIndices.size - 1
    val truncatedAtStart: Boolean, val truncatedAtEnd: Boolean,
    val duringSleepFraction: Float,
)

// --- Mask ---

enum class Stage { WAKE, SLEEP, LIGHT, DEEP, REM, AWAKE_IN_BED, OUT_OF_BED, UNKNOWN }
enum class MaskSource { ACCEL_IMMOBILITY, HEALTH_CONNECT, DIARY, FUSED }

/** Denominator: where does it come from, and is it circular? Drives what the UI is allowed to display. */
enum class DenominatorIndependence { INDEPENDENT_HC, INDEPENDENT_DIARY, SPT_QUASI_INDEPENDENT, CIRCULAR }

data class SleepWindow(val startMsRel: Long, val endMsRel: Long, val stage: Stage)

/** Manual sleep diary: denominator totally independent of the signal (§3.6.5-a). */
data class DiaryWindow(val bedTimeMsRel: Long, val riseTimeMsRel: Long)

data class SleepMask(
    val windows: List<SleepWindow>,
    val source: MaskSource,
    val sptMin: Double, val tstMin: Double, val wasoMin: Double,
    val analysableTstMin: Double,      // TST ∩ valid segments ∩ outside blind zones ∩ outside off-body
    val analysableSptMin: Double,
    val corrected: Boolean,
    val lagAppliedMs: Long,
    val independence: DenominatorIndependence,
    val fixedPointConverged: Boolean,
)

data class MaskAgreement(val kappa: Double, val tstDeltaMin: Double, val overlapPct: Double, val bestLagMs: Long)

// --- Calibration ---

data class SensorCalibration(val offsetG: FloatArray, val scale: FloatArray, val residualG: Float, val valid: Boolean)

enum class GainSource { RITUAL, GROSS_BODY, NONE }

data class NightCalibration(
    val sensor: SensorCalibration?,
    val gainCalG: Float, val floorCalG: Float, val snrCal: Float,
    val gainSource: GainSource,
    val outlierVsBaseline: Boolean,
)

// --- Results ---

enum class RespiratoryConfidence { HIGH, MEDIUM, LOW }
enum class FloorMode { BILATERAL, CAUSAL_LAGGED }
enum class PublicationGate { FULL, TRUNCATED_NO_TREND, NO_PLMI }

data class PiResult(
    val periodicityIndex: Double, val valid: Boolean,
    val totalIntervals: Int, val lmRatePerHour: Double,
)

data class PlmiResult(
    val rule: SeriesRule, val maskSource: MaskSource,
    val plmsCount: Int, val plmwCount: Int, val isolatedCount: Int, val shortImiCount: Int,
    val tstMin: Double, val analysableTstMin: Double, val sptMin: Double, val wasoMin: Double,
    val plmi: Double, val plmiSpt: Double, val plmw: Double,
    val pi: PiResult,
    val plmiFirstHalf: Double, val plmiSecondHalf: Double,
    val imiHistogram: IntArray, val imiBinEdgesSec: FloatArray,
    val truncatedSeriesDropped: Int,
    val plmiRespWorstCase: Double,
    val respiratoryConfidence: RespiratoryConfidence,
    val independence: DenominatorIndependence,
    val gate: PublicationGate,
    val floorMode: FloorMode,
    val paramsHash: String,
)

data class QualityReport(
    val analysableFraction: Double, val gapCount: Int, val gapTotalSec: Double, val longestGapSec: Double,
    val postureChanges: Int, val grossBodyMovements: Int,
    val medianFloorG: Float, val floorVsBaselineRatio: Double,
    val offBodyFraction: Double, val clockDriftSuspect: Boolean,
    val integrity: IntegrityReport,
    val truncatedNight: Boolean, val maskNonConvergent: Boolean,
    val warnings: List<String>,
)

data class NightAnalysis(
    val timeline: Timeline,
    val calibration: NightCalibration,
    val clms: List<Clm>, val postures: List<PostureChange>,
    val masks: Map<MaskSource, SleepMask>, val agreement: MaskAgreement?,
    val results: List<PlmiResult>,      // 4 rows: 2 rules × 2 masks
    val plmiLowerBound: Double, val plmiUpperBound: Double,
    val quality: QualityReport,
    val algoVersion: String,
)
```

### 4.2 `com.pendulum.algo.dsp`

```kotlin
package com.pendulum.algo.dsp

/** Transposed direct form II biquad. Stateful, streaming — never reset per block. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun reset()
    fun resetToDc(dcValue: Float)     // steady state for a constant input: avoids the 1 g transient
    fun step(x: Float): Float
    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray
    fun snapshot(): DoubleArray       // for the incremental mode
    fun restore(state: DoubleArray)
}

class BiquadCascade(private val stages: List<Biquad>) {
    fun reset(); fun resetToDc(dcValue: Float); fun step(x: Float): Float
    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray
    fun snapshot(): Array<DoubleArray>; fun restore(state: Array<DoubleArray>)
    val settlingTimeSec: Double
}

object Filters {
    fun butterHighpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade
    fun butterLowpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade
    fun butterBandpass(fsHz: Double, fLowHz: Double, fHighHz: Double, order: Int = 2): BiquadCascade
}

/** Step −1: the algo trusts neither the CRC nor the block header. */
object Integrity {
    fun check(blocks: List<SampleBlock>, nominalHz: Double,
              cfg: IntegrityConfig = IntegrityConfig()): Pair<List<SampleBlock>, IntegrityReport>
}

object Rate {
    fun estimate(blocks: List<SampleBlock>, nominalHz: Double, outlierTol: Double = 0.05): FsEstimate
    fun clockDrift(wallMs: LongArray, eventNs: LongArray): Double
}

data class TimelineConfig(
    val targetFsHz: Double = 50.0,
    val gapMicroSec: Double = 0.10, val gapSegmentSec: Double = 2.0,
    val settleSec: Double = 2.0, val warmupSec: Double = 5.0,
    val fsOutlierTol: Double = 0.05,
    val integrity: IntegrityConfig = IntegrityConfig(),
)
object TimelineBuilder {
    fun build(blocks: List<SampleBlock>, nominalHz: Double, cfg: TimelineConfig = TimelineConfig()): Timeline
}

data class GravitySplit(val gravity: TriAxial, val linear: TriAxial)
object Gravity {
    fun split(raw: TriAxial, segments: List<Segment>, fcGravityHz: Double = 0.15,
              fcHpHz: Double = 0.50, fcLpHz: Double = 8.0, hpOrder: Int = 2): GravitySplit
    fun unitVectors(gravity: TriAxial): TriAxial
    fun angleDeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float
}

object Envelope {
    fun magnitudeL2(t: TriAxial): Signal1D
    fun rms(s: Signal1D, winSec: Double, segments: List<Segment>): Signal1D
    fun dual(m: Signal1D, segments: List<Segment>, coarseSec: Double = 0.50, fineSec: Double = 0.15): DualEnvelope
}

data class NoiseFloorConfig(
    val winSec: Double = 120.0, val pass1Percentile: Int = 25,
    val excludeFactor: Double = 4.0, val minValidFraction: Double = 0.25,
    val mode: FloorMode = FloorMode.BILATERAL,
    val causalLagSec: Double = 5.0,          // used only if mode == CAUSAL_LAGGED
)
object NoiseFloor {
    /** boundaries: segment AND posture-change boundaries — the window never crosses them. */
    fun estimate(env: Signal1D, segments: List<Segment>, boundaries: IntArray,
                 cfg: NoiseFloorConfig = NoiseFloorConfig()): Pair<Signal1D, BooleanArray>
}

object Calibration {
    fun autocalibrate(raw: TriAxial, segments: List<Segment>,
                      staticWinSec: Double = 10.0, sdThresholdG: Float = 0.013f,
                      minSphereSpanG: Float = 0.30f): SensorCalibration
    fun apply(raw: TriAxial, c: SensorCalibration): TriAxial
    /** Guided ritual: the metronome instants are the ground truth. */
    fun fromRitual(env: Signal1D, stillFromMs: Long, stillToMs: Long, kickOnsetsMs: LongArray): NightCalibration
    fun fromGrossBodyMovements(clms: List<Clm>): NightCalibration
}
```

### 4.3 `com.pendulum.algo.detect`

```kotlin
package com.pendulum.algo.detect

data class ThresholdConfig(
    val kOn: Double = 8.0, val kOff: Double = 2.5,
    val absFloorG: Float = 0.020f, val calFraction: Double = 0.12,
)
data class ClmConfig(
    val thresholds: ThresholdConfig = ThresholdConfig(),
    val offHoldSec: Double = 0.50, val minDurSec: Double = 0.50, val maxDurSec: Double = 10.0,
    val morphologyWinSec: Double = 0.50, val grossBodyFactor: Double = 40.0,
    val refractorySec: Double = 2.0, val minExcursionDeg: Double = 1.5,
)
data class PostureConfig(
    val tauSec: Double = 2.0, val postureDeg: Double = 20.0,
    val stableDeg: Double = 10.0, val stableSec: Double = 10.0, val guardSec: Double = 2.5,
)

object PostureDetector {
    fun detect(gravity: TriAxial, segments: List<Segment>, cfg: PostureConfig = PostureConfig()): List<PostureChange>
}

object ClmDetector {
    fun detect(env: DualEnvelope, floor: Signal1D, floorExtrapolated: BooleanArray,
               gravity: TriAxial, segments: List<Segment>, blindZones: List<Segment>,
               postures: List<PostureChange>, calibration: NightCalibration,
               cfg: ClmConfig = ClmConfig(), postureCfg: PostureConfig = PostureConfig()): List<Clm>
}

data class SeriesConfig(
    val rule: SeriesRule,
    val imiMinSec: Double, val imiMaxSec: Double = 90.0, val minClmPerSeries: Int = 4,
    val shortImiPolicy: ShortImiPolicy, val breakOnLongLm: Boolean, val requirePortionInSleep: Boolean,
) {
    companion object {
        fun aasmV3()   = SeriesConfig(SeriesRule.AASM_V3,   5.0,  90.0, 4, ShortImiPolicy.SKIP_LATER,   false, true)
        fun wasm2016() = SeriesConfig(SeriesRule.WASM_2016, 10.0, 90.0, 4, ShortImiPolicy.BREAK_SERIES, true,  false)
    }
}
object SeriesBuilder { fun build(events: List<Clm>, mask: SleepMask, fsHz: Double, cfg: SeriesConfig): List<PlmSeries> }

data class PeriodicityConfig(
    val imiLowExclusiveSec: Double = 10.0, val imiHighInclusiveSec: Double = 90.0,
    val minRunLength: Int = 3, val minLmRatePerHour: Double = 10.0,
)
object Periodicity {
    fun ferriIndex(clms: List<Clm>, mask: SleepMask, fsHz: Double,
                   cfg: PeriodicityConfig = PeriodicityConfig()): PiResult
}

data class PlmiConfig(
    val respSuspectImiLowSec: Double = 25.0, val respSuspectImiHighSec: Double = 45.0,
    val imiHistogramBinSec: Double = 2.0, val imiHistogramMaxSec: Double = 100.0,
    val minTstFullMin: Double = 240.0, val minTstAnyMin: Double = 180.0,
)
object Plmi {
    fun compute(clms: List<Clm>, series: List<PlmSeries>, mask: SleepMask, fsHz: Double,
                rule: SeriesRule, respiratory: RespiratoryConfidence,
                pi: PiResult, floorMode: FloorMode, truncated: Boolean,
                cfg: PlmiConfig = PlmiConfig()): PlmiResult
}
```

### 4.4 `com.pendulum.algo.mask`

```kotlin
package com.pendulum.algo.mask

data class ImmobilityConfig(
    val epochSec: Double = 5.0, val angleDeg: Double = 5.0, val moveFactor: Double = 6.0,
    val sustainedMin: Double = 5.0, val sptMinMin: Double = 15.0,
    val maxFixedPointIterations: Int = 2, val convergenceTstFraction: Double = 0.25,
)

object ImmobilityMask {
    /**
     * @param ignoreIntervals intervals (typically the detected CLMs) NEUTRALISED as
     *   evidence of mobility. Layer 1 of the answer to the circularity (§3.6.3): a PLMS is
     *   by definition a movement DURING sleep, not evidence of wake.
     */
    fun build(gravity: TriAxial, env: Signal1D, floor: Signal1D,
              segments: List<Segment>, offBody: List<Segment>,
              ignoreIntervals: List<Segment> = emptyList(),
              diary: DiaryWindow? = null,
              cfg: ImmobilityConfig = ImmobilityConfig()): SleepMask
}

data class FusionConfig(val maxLagMs: Long = 600_000, val lagStepMs: Long = 5_000, val minCoverage: Double = 0.50)
data class TstCorrection(val alpha: Double, val beta: Double, val nNights: Int, val valid: Boolean)

object MaskFusion {
    fun align(accel: SleepMask, hc: List<SleepWindow>, cfg: FusionConfig = FusionConfig()): MaskAgreement
    fun fuse(accel: SleepMask, hc: List<SleepWindow>?, diary: DiaryWindow?,
             cfg: FusionConfig = FusionConfig()): SleepMask
    fun fitCorrection(pairs: List<Pair<Double, Double>>): TstCorrection      // (TST_accel, TST_HC)
    fun applyCorrection(accel: SleepMask, c: TstCorrection): SleepMask
}
```

### 4.5 Entry points — definitive mode and incremental mode

```kotlin
package com.pendulum.algo

data class AlgoParams(
    val timeline: TimelineConfig = TimelineConfig(),
    val gravityFcHz: Double = 0.15, val hpFcHz: Double = 0.50, val lpFcHz: Double = 8.0, val hpOrder: Int = 2,
    val coarseEnvSec: Double = 0.50, val fineEnvSec: Double = 0.15,
    val noiseFloor: NoiseFloorConfig = NoiseFloorConfig(),
    val clm: ClmConfig = ClmConfig(), val posture: PostureConfig = PostureConfig(),
    val immobility: ImmobilityConfig = ImmobilityConfig(), val fusion: FusionConfig = FusionConfig(),
    val periodicity: PeriodicityConfig = PeriodicityConfig(), val plmi: PlmiConfig = PlmiConfig(),
) { fun hash(): String }

data class RitualMarkers(val stillFromMs: Long, val stillToMs: Long, val kickOnsetsMs: LongArray)

/** DEFINITIVE MODE. The only mode that produces plm_result rows. Replays the whole session. */
object PendulumPipeline {
    fun analyse(
        blocks: List<SampleBlock>,
        nominalRateHz: Double,
        hcWindows: List<SleepWindow>? = null,
        diary: DiaryWindow? = null,
        ritual: RitualMarkers? = null,
        baselineGainG: Float? = null,
        tstCorrection: TstCorrection? = null,
        respiratory: RespiratoryConfidence = RespiratoryConfidence.MEDIUM,
        params: AlgoParams = AlgoParams(),
    ): NightAnalysis
}

/**
 * PROVISIONAL MODE. Causal floor, no autocalibration, no full mask.
 * Output intended for REAL-TIME DISPLAY only — never persist it in plm_result.
 */
data class PartialAnalysis(
    val elapsedSec: Double,
    val clms: List<Clm>, val closedSeries: List<PlmSeries>, val openSeriesClmCount: Int,
    val piProvisional: PiResult,
    val plmiSptProvisional: Double,
    val quality: QualityReport,
    val floorMode: FloorMode,               // always CAUSAL_LAGGED
) { val isProvisional: Boolean get() = true }

/** Opaque, serialisable state carried from one incremental iteration to the next. */
class StreamState internal constructor(/* biquad states, running sums, series state machine */)

object PendulumStream {
    fun start(nominalRateHz: Double, params: AlgoParams = AlgoParams()): StreamState
    fun push(state: StreamState, newBlocks: List<SampleBlock>): PartialAnalysis
}
```

---

## 5. Synthetic signal generator with ground truth

`com.pendulum.algo.synth`. Deterministic: same `seed` → bit-identical output (test T13).

### 5.1 Physical model of a CLM at the ankle

A PLMS is a **triple flexion response**: dorsiflexion of the foot, flexion of the knee, sometimes of the hip. The sensor is at the ankle, a few centimetres above the talocrural joint. Two contributions, of very different natures:

**(1) Rotation of the shank segment about the knee or the hip.** The sensor, at a distance `r` from the centre of rotation, undergoes a tangential acceleration `r·θ̈`, plus a centripetal acceleration `r·θ̇²` (higher order, kept in the model), plus a rotation of the gravity vector as seen in the sensor frame.

**(2) Pure rotation of the ankle.** The sensor being on the leg **above** the axis, it barely moves at all: `r_eff ≈ 0`. **This is the physical mechanism behind the miss rate of Terrill et al.** The model must represent it explicitly, failing which the ground truth overestimates what the sensor can see.

**Angular profile — order 5 minimum-jerk polynomial** (the standard ballistic gesture in biomechanics):

```
s(u)   = 10u³ − 15u⁴ + 6u⁵          u ∈ [0,1]
θ(t)   = θ_max · s(t / T_rise)                        flexion phase
       = θ_max − d · s(v)                             sustained hold, duration T_hold
       = θ_max · (1 − s((t − t₂)/T_fall))             return phase

θ̈(t)   = (θ_max / T_rise²) · (60u − 180u² + 120u³)    BIPOLAR impulse
```

The extrema of `θ̈` are at `u = (3 ± √3)/6 = 0.2113 and 0.7887`, of value `± 5.7735 · θ_max / T_rise²` (exact value).

**Accelerometric signature of a complete CLM: quadripolar** — one bipolar pair at the flexion, one bipolar pair at the return, separated by the hold phase. That phase is not silent: see "Hold phase" below.

**Spectral content.** The peak of the bipolar impulse is at `f_peak ≈ 0.8 / T_rise`. With `T_rise ∈ [0.15 ; 0.50] s`, that gives **f_peak ∈ [1.6 ; 5.3] Hz**. This is consistent with all the published bands and **incompatible with the "10-15 Hz packets" of v1**, which must be removed.

**Amplitude, in g:**

```
a_tang(t) = r · θ̈(t) / 9.80665
a_grav(t) = sin(θ(t) − θ₀) − sin(θ₀)          gravity projection component, in g
a_cent(t) = r · θ̇(t)² / 9.80665
```

Calibration check of the model against the literature:

| Case | θ_max | T_rise | r | tangential peak |
|---|---|---|---|---|
| Small CLM | 4° | 0.45 s | 0.15 m | **30 mg** |
| Medium CLM | 10° | 0.35 s | 0.22 m | **184 mg** |
| Large CLM | 20° | 0.25 s | 0.30 m | **985 mg** |
| Ankle only (invisible) | 15° | 0.30 s | 0.02 m | **34 mg** — and 0 mg if the case is on the tibia |

The model therefore naturally produces the **15–600 mg** range which brackets Sicbaldi's detection threshold (15 mg) and the typical movement magnitude during sleep (377 ± 63 mg), and it also produces the invisible events. **The physics lines itself up with the published figures** — that is the best validity argument for the model available in the absence of a published PLMS spectrum.

Note that for large angles the `a_grav` component **dominates**: at 20°, `sin(20°) = 0.342 g` over ~0.25 s, i.e. content around 1.6 Hz of amplitude 342 mg, comparable to the tangential term. Both must be modelled — and that is exactly what makes the detector's `tiltExcursionDeg` feature informative.

**Durations.** Total duration of a CLM drawn from a log-normal distribution of mean **4.2 s** (Sforza 2005, RLS) — to be compared with the **4.5 ± 0.4 s** of Sicbaldi 2025 (sleep movements) — truncated to [0.5 ; 10] s. The ~2–3 s mode sometimes put forward is **confirmed by no published histogram** — do not code it as established.

> **Correction of 2026-07-31 — the dispersion is not published.** This paragraph carried "4.2 ± 1.4 s" while `02-science.md`, `references.md` and the tables carried "4.2 ± 0.14 s". Back to the source: §2.5 of Sforza says "*results in the text and in the tables are expressed as mean ± standard error of the mean*", and the RLS group has 11 patients. **The ± 0.14 is a standard error, not a standard deviation**; the between-patient standard deviation of the individual means is `0.14 × √11 ≈ 0.46 s`, and the **event-by-event** dispersion is published nowhere. Neither figure was therefore "the right value". The generator keeps **σ = 1.4 s** while declaring it for what it is — a **modelling choice** (CV = 0.33, ±2σ quantiles at ~1.9–8.0 s, inside Coleman's 0.5–10 s scoring window) — and not a datum from Sforza. A σ of 0.14 s would make the distribution a Dirac mass, incompatible with the very existence of that window and with the 3.2 s mean of the PLMD group in the same article.

**Hold phase — it is not motionless.** With a duration of 4.2 s and `T_rise ∈ [0.15 ; 0.50] s`, a hold at a strictly constant angle would last ~3.6 s. During that time `θ̇ = θ̈ = 0` and the gravity term is a continuous plateau: after the 0.5 Hz high-pass, **the signal is zero**. The model therefore drew ~2.3 s of accelerometric silence in the middle of every movement, and the detector — applying `offHoldSec = 0.50 s` literally — emitted two events. That was the root cause of the T6 failure (see `07-validation.md` §4.1 and §5.5).

**The source of the duration forbids that shape.** Sforza measures his 4.2 s **with the PAM-RL**: entry threshold 200 mg, decay threshold 100 mg, and a **drop-out time of 1 s** (§2.4 of the article) — a "kick" only ends after a full second below 100 mg. An event containing 2.3 s of silence would have been split by the PAM-RL itself, and the published mean duration would have been of the order of half. The same reasoning holds if one reads the 4.2 s as an EMG burst duration in Coleman's sense: 4.2 s of burst is 4.2 s of active contraction. And the corpus of rules says so itself: the 0.50 s offset rule exists only because a leg movement is a **train of activations** separated by less than half a second.

The hold is therefore modelled as a **sustained flexion**: the angle oscillates around `θ_max` in successive dips, each half-dip being a minimum-jerk profile like the flexion itself. Two constants, neither of them tuned on a test:

| Quantity | Value | Where it comes from |
|---|---|---|
| Period of a dip | `2 · T_rise` | The movement's own ballistic scale. Spectral peak of a half-dip = `0.8/T_rise`, i.e. the 1.6–5.3 Hz band already published above; repetition rate `1/(2·T_rise)` ∈ [1.0 ; 3.3] Hz, the clonic band |
| Depth of the dips | `0.50 · θ_max` | Gives a hold acceleration peak equal to **0.50 ×** the ballistic peak — exactly the 100 mg / 200 mg ratio of the PAM-RL, the minimum an event must sustain to have been counted as a single kick |

The joins are **C²** (`s'(0) = s'(1) = s''(0) = s''(1) = 0`): no velocity jump and no acceleration jump between flexion, dip and return, hence no broadband artefact introduced. The calibration table above depends only on `θ_max`, `T_rise` and `r`: it is **unchanged**, and `MovementModelTest` now asserts it.

**Amplitudes.** Log-normal distribution, median 80 mg, σ_log = 0.6 (a factor of ~1.8 per standard deviation), truncated to [10 ; 800] mg. The σ_log = 0.6 is an engineering choice, not a published datum: it is calibrated so that the 5 % quantile falls below the detection threshold and the 95 % quantile above 300 mg. It directly drives the slope of the sensitivity curve (test T5) and must therefore be a parameter of the generator, not a constant.

### 5.2 Structure of a synthetic night

```
NightSpec
  durationH = 8.0            fsRealHz = 50.0 | 50.3 | 52.6 | linear drift ±0.5 %
  trueSeries : list of (nSeries, clmPerSeries, imiMeanSec, imiCvPct)
  isolatedClmPerHour, ankleOnlyFraction, gainMultiplier
  truncateAtH : Double?      // night cut at 3 in the morning (test T18)
  distractors : DistractorSpec
  noise : NoiseSpec
```

**Distractors to generate (12 families).** The first six are indispensable; the next six cover the failure modes identified in §1 and §3.

| # | Distractor | Parameters | What it tests |
|---|---|---|---|
| 1 | Posture changes | 15–40/night, rotation of ĝ by 20–120° in 0.5–3 s | §3.1, the leading source of FP (transient up to 1 g) |
| 2 | Gross body movements | 20–60/night, 2–20 s, 300–2500 mg | GBM classifier + refractory period; contamination of the floor |
| 3 | Respiratory artefact | 0.20–0.33 Hz sinusoid, 1–10 mg, amplitude-modulated | Lower corner of the high-pass (§1.2) |
| 4 | Mattress vibration | 50–500/night, transients 0.05–0.4 s, 3–40 mg, damped ringing 8–20 Hz, **tilt unchanged** | WASM 3.2.1-d morphology threshold (§3.2) |
| 5 | MEMS noise + quantisation | 150–300 µg/√Hz + LSB 1/2048 g | Absolute floor Θ_abs (§1.1) |
| 6 | FIFO gaps | 20–60 gaps of 0.1–5 s + one long gap of 30–120 s | Step 0, blind zones, series break |
| 7 | fs drift | 50.0 / 50.3 / 52.6 Hz + linear drift | §3.4, test T8 |
| 8 | Off-body | watch on the table for 10 min, constant gravity + noise only | Exclusion from the numerator **and** the denominator |
| 9 | Hypnagogic foot tremor / ALMA | 0.3–4 Hz bursts of 10–15 s, 2–8/night | A real clinical distractor, must **never** be counted |
| 10 | Non-periodic clusters | volleys of 5–10 CLMs at random IMI 1–8 s | AASM/WASM divergence on the series break (§1.5-ii) |
| 11 | RRLM | series at IMI 25–45 s coupled to the respiratory modulation of distractor 3 | Measurement of the residual bias (§3.5) |
| 12 | Mechanical gain jump | multiplication of the movement channel by 0.7 at mid-night | The strap loosening during the night (§3.3) |

**Format corruption to inject (new — tests step −1)**: blocks with `tFirstNs > tLastNs`, overlapping blocks, `N` inconsistent with the payload size, a 3 h clock jump, and a sign wrap at ±16 g simulating the `toRaw` bug. These cases do not go through `NightSpec` but through a post-generation mutator, `Corrupt.inject(blocks, spec, seed)`.

### 5.3 Ground truth — two sets of labels

This is the direct consequence of Terrill: a single set of labels would make every score wrong.

```kotlin
data class TruthEvent(
    val onsetMsRel: Long, val durationMs: Int,
    val peakG: Float, val thetaMaxDeg: Float, val tRiseSec: Float, val radiusM: Float,
    val ankleOnly: Boolean,            // r_eff ≈ 0 → invisible to accelerometry
    val kind: TruthKind,               // PLM_IN_SERIES | ISOLATED | RRLM | GROSS_BODY | POSTURE | ALMA | MATTRESS
    val seriesId: Int?,
)

data class GroundTruth(
    val emgTruth: List<TruthEvent>,     // every generated movement — the EMG scale
    val accelTruth: List<TruthEvent>,   // subset mechanically visible to the sensor
    val postures: List<Long>, val gaps: List<Gap>,
    val expectedPlmiAasm: Double, val expectedPlmiWasm: Double,
    val expectedPlmw: Double, val expectedPi: Double,
    val expectedTstMin: Double, val expectedSptMin: Double,
    val gainMultiplierApplied: Float, val fsRealHz: Double,
    val truncatedAtMs: Long?,
)

class SynthNight(val blocks: List<SampleBlock>, val truth: GroundTruth, val seed: Long)
object NightSynth { fun generate(spec: NightSpec, seed: Long): SynthNight }
```

`accelTruth` = `emgTruth` minus the `ankleOnly` events and those whose simulated peak falls below a physical visibility threshold (default 8 mg, i.e. ~0.4× the absolute floor). `ankleOnlyFraction` defaults to **0.39**, set from Terrill (39.0 % of EMG LMs with no detectable movement), range 0.25–0.55.

**All the detector metrics are scored against `accelTruth`.** `emgTruth` serves one purpose only, but an essential one: to measure and **report** the conversion factor between the accelerometric scale and the EMG scale, that is, the structural downward bias of the measured PLMI. It is the figure that forbids comparing our PLMI directly with the ICSD-3 threshold of 15/h.

### 5.4 Matching and metrics

```kotlin
data class MatchResult(val tp: Int, val fp: Int, val fn: Int,
                       val sensitivity: Double, val precision: Double, val f1: Double,
                       val onsetBiasMs: Double, val onsetSdMs: Double)
object Scoring {
    fun match(detected: List<Clm>, truth: List<TruthEvent>, toleranceSec: Double = 1.0): MatchResult
    fun plmiError(actual: PlmiResult, truth: GroundTruth): Double
    fun sensitivityCurve(nights: List<SynthNight>, params: AlgoParams): List<Pair<Double, Double>>  // (amp/floor ratio, Se)
}
```

Greedy matching in chronological order, onset tolerance **1.0 s**. Justification of the tolerance: the finest clinical granularity is the lower IMI bound (5 s), and the 0.5 s coarse envelope introduces an onset bias bounded at ~0.25 s. A tolerance of 1.0 s is wide relative to the bias and narrow relative to the rule.

### 5.5 Regression thresholds to assert

Each test runs on ≥ 20 seeds; the threshold applies to the median, with a secondary assertion on the worst case where indicated.

> **This table is the order, not the report. Do not read it as a state.** **T1 to T7 and T22 exist
> today. T8 to T21 do not.** [`../07-validation.md`](../07-validation.md) §3 keeps the living list,
> and three things must be known before citing a row from here.
>
> **T6 no longer sits on the same denominator.** It used to be measured against the whole `accelTruth`;
> it is now measured against `accelTruth` **restricted to the events above the onset threshold**, and
> the price of that restriction is published alongside, under the number **T22**: about **70 %** of the
> mechanically visible truth falls below the threshold, and once the rule of four consecutive movements
> is applied, only **6 %** of the true index survives. Moving the denominator without publishing T22
> would have been moving the goalposts; it is the pair that makes it a measurement.
> [`../07-validation.md`](../07-validation.md) §4.1 is the full account, including the two
> successive diagnoses that were themselves wrong.
>
> **T8 to T11 and T13 to T17 are not written.** Nothing in `algo/src/test` asserts fs invariance,
> decimation, gaps, mechanical gain, determinism, rule divergence, ALMA, a mid-night gain jump or a
> golden file. This paragraph used to name T12 alone, which read as a statement that the nine
> others ran. The generator knobs they would need exist and no test calls them: `Resample.decimate`,
> `NightSpec.fsDriftPct`, `NightSpec.truncateAtH`, `DistractorSpec.gainStep`, and the
> `fsRealHz` / `gainMultiplier` parameters of `nominalNight` in `RegressionSupport.kt`.
>
> **T12 is not written.** The ±20 % sweep described here does not exist as an assertion. What exists
> is two `@Disabled` parametric sweeps, run by hand ([`../07-validation.md`](../07-validation.md)
> §4.1 and §4.4). The distinction matters, because one of those sweeps contradicts a figure this
> project carried "pending T12" for its whole history.
>
> T18 to T21 below are not written either. That is also the reason why the next test
> is called T22 and not T18: reusing the number would have created a silent collision in
> a table that several documents cite.

| ID | Scenario | Assertion |
|---|---|---|
| T1 | MEMS noise only, 30 min | **0 CLM**. Non-negotiable, worst case included. |
| T2 | Respiration only (8 mg at 0.25 Hz), 30 min | **0 CLM** |
| T3 | Mattress vibrations only, 300 transients, 30 min | **≤ 2 CLM** (≤ 0.7 % FP) |
| T4 | 40 posture changes only | **0 CLM not tagged `POSTURAL`**; recall of the posture detector **≥ 0.95** |
| T5 | Isolated CLMs, increasing amplitude | Se **≤ 0.05** at 4× the floor; Se **≥ 0.95** at 16×; Se ∈ [0.35 ; 0.65] at 8× (the threshold, by construction) |
| T6 | Nominal night: true PLMI 25/h, true PI 0.60, all distractors | F1 **≥ 0.90** vs `accelTruth`; \|ΔPLMI\|/PLMI **≤ 0.10**; \|ΔPI\| **≤ 0.05**; onset bias **≤ 300 ms**, standard deviation **≤ 400 ms** |
| T7 | Negative night: true PLMI 2/h | Estimated PLMI **≤ 5/h** (no false positive on the screening decision) |
| T8 | fs invariance: 50.0 / 50.3 / 52.6 Hz, same events | ΔPLMI **≤ 2 %** between the three |
| T9 | Decimation 50 → 25 Hz | ΔPLMI **≤ 5 %** |
| T10 | 2 % of scattered gaps + one 90 s gap | ΔPLMI **≤ 3 %** vs the same night without gaps |
| T11 | Mechanical gain ×0.6 and ×1.8 | Calibration on: ΔPLMI **≤ 10 %**. Calibration off: ΔPLMI **> 40 %** (inverted assertion — if it fails, part B of §3.3 is good for nothing and must be removed) |
| T12 | Parametric sensitivity, ±20 % on each parameter | No parameter makes the PLMI vary by **> 15 %**; at ±10 %, the PLMI ≷ 15 decision flips for **no** parameter |
| T13 | Determinism | Same seed → **bit-identical** output |
| T14 | Rule divergence, non-periodic cluster | PLMI_AASM > PLMI_WASM on nights with clusters; the two equal to within ±2 % on a purely periodic night at IMI 22 s |
| T15 | ALMA / foot tremor | **0 CLM** attributed to those bursts |
| T16 | Gain jump at mid-night (×0.7) | \|PLMI_1st half − PLMI_2nd half\| **≤ 20 %** — tests the adaptivity of the floor |
| T17 | Golden file | 5 min of real signal + expected JSON, exact comparison of the CLMs and of the PLMI |
| **T18** | **Night truncated at 3 h** (`truncateAtH = 3.0`) | `gate == NO_PLMI`; `piValue` still produced and \|ΔPI\| **≤ 0.08**; no exception; `truncatedSeriesDropped` filled in |
| **T19** | **Circularity**: night with true PLMI 60/h, accelerometric mask alone | With `ignoreIntervals`: TST estimated to within **≤ 15 %** of the true TST. **Without** `ignoreIntervals`: true TST/2 or worse (inverted assertion — demonstrates that layer 1 of §3.6.3 is necessary) |
| **T20** | **Integrity**: corrupted blocks injected (overlap, jerk ±16 g, `tFirst > tLast`) | 100 % of the corrupted blocks rejected; **0 %** of the sound blocks rejected; PLMI unchanged to within **≤ 3 %** vs the uncorrupted night |
| **T21** | **Incremental / definitive equivalence** | The CLMs produced by `PendulumStream` in causal mode overlap **≥ 90 %** of those of the definitive mode; the `plmiSpt` deviation is **≤ 15 %**. Serves as a guard rail: beyond that, the provisional mode misleads the user during the night |

Note on T6: the F1 ≥ 0.90 of v1 is kept, **but it is now measured against `accelTruth`**, not against the set of all generated movements. Against `emgTruth`, the F1 would mechanically cap at ~0.76 (Se ≤ 0.61 by Terrill), and a threshold of 0.90 would be impossible to reach. That distinction is what prevents criterion P4 of v1 from being unreachable for a reason that is not the algorithm's fault.

---

## 6. Final parameter table

The ±20 % impacts are **engineering estimates to be confirmed by test T12**, not measured values. They are given in order to prioritise the validation effort.

### 6.1 Integrity and preprocessing

| Parameter | Default | Unit | Range | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `maxRateDeviation` | 0.20 | — | 0.10–0.30 | Check no. 3 of step −1 | < 1 % |
| `maxJerkG` | 8.0 | g/sample | 4–16 | Check no. 8: detects the saturation wrap independently of the `toRaw` bug | < 1 % |
| `saturationFraction` | 0.05 | — | 0.02–0.10 | Check no. 10 | < 1 % |
| `targetFsHz` | 50.000 | Hz | fixed | Resampling grid; makes coefficients and durations exact | n/a |
| `fsOutlierTol` | 0.05 | — | 0.02–0.10 | Rejection of blocks with corrupted timestamps | < 1 % (sound nights) |
| `gapMicroSec` | 0.10 | s | 0.05–0.20 | < the minimum T_rise (0.15 s): interpolable without artefact | < 1 % |
| `gapSegmentSec` | 2.0 | s | 1.0–5.0 | = the settling time of the filter; beyond that, series break (WASM 3.3.3) | < 2 % |
| `settleSec` | 2.0 | s | 1.5–4.0 | Settling of an order-2 Butterworth at 0.5 Hz | < 2 % |
| `warmupSec` | 5.0 | s | 3–10 | ≈ 2.5× settle; over 8 h, ~0.03 % of the denominator | < 0.5 % |
| `fcGravityHz` | 0.15 | Hz | 0.08–0.25 | Below the fundamental of a 10 s CLM (0.1 Hz) without swallowing respiration into ĝ | 2–5 % (via `tilt`) |
| `fcHpHz` | **0.50** | Hz | 0.30–0.70 | NeuroMetrix patents (50 Hz, 0.5 Hz); +7.4 dB of rejection at 0.25 Hz vs 0.3 Hz, **−0.9 dB** at 0.7 Hz (§1.2, corrected on 2026-07-31; this cell still carried −0.6 dB) | 3–8 %; **> 15 % if respiration is present** |
| `hpOrder` | 2 | — | 2 or 4 | Order 4: +19 dB at 0.25 Hz but posture ringing 2 s → 4 s (§1.2) | discrete, test both |
| `fcLpHz` | **8.0** | Hz | 6–12 | +5.1 dB of SNR (BW 24.5 → 7.5 Hz); ≥ 1.5× the peak of the fastest CLM (5.3 Hz) | < 3 % |

### 6.2 Envelope and floor

| Parameter | Default | Unit | Range | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `coarseEnvSec` | **0.50** | s | 0.35–0.80 | ≥ 1 period at 2 Hz; cancels the ripple at 2f. Replaces the 0.15 s of v1 (§0-b) | 3–6 % (durations, fragmentation) |
| `fineEnvSec` | 0.15 | s | 0.10–0.25 | Onset/offset refinement only (§1.4) | < 2 % (onset ±30 ms) |
| `floorWinSec` | **120** | s | 60–240 | A 20 s turn weighs 17 % instead of 80 % at 25 s (§1.3) | < 2 % |
| `floorP1` | 25 | percentile | 15–40 | Robust pass 1 before iterative exclusion | < 2 % |
| `excludeFactor` | 4.0 | × | 3–6 | Pass 2 masking; below `k_on` so as to cover sub-threshold CLMs | 2–4 % |
| `minValidFraction` | 0.25 | — | 0.15–0.40 | Below this threshold, extrapolated floor + flag | < 1 % |
| `floorMode` | `BILATERAL` | — | + `CAUSAL_LAGGED` | Definitive vs incremental (§3.7) | see T21: ≤ 15 % |
| `causalLagSec` | 5.0 | s | 3–10 | Prevents the event in progress from contaminating its own floor (causal mode) | 3–6 % (causal mode only) |

### 6.3 Detection thresholds

| Parameter | Default | Unit | Range | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `kOn` | **8.0** | × floor | 5–12 | **Anti-artefact budget, not anti-noise**: 4.8 would suffice against the thermal case (§2, step 4). v1 value kept for want of better | **10–15 % — dominant parameter** |
| `kOff` | 2.5 | × floor | 2.0–4.0 | Hysteresis 3.2; transposition of the AASM 8 µV / 2 µV ratio | 3–6 % |
| `absFloorG` | **0.020** | g | 0.010–0.050 | NeuroMetrix 0.02/0.03 g; Sicbaldi 15 mg. The relative threshold alone would fall to 7 mg (§1.1) | 0 % on a normal night; **up to 15 % on a very quiet night** |
| `calFraction` | 0.12 | × gainCal | 0.08–0.20 | 12 % of a comfortable voluntary dorsiflexion (§3.3) | 5–10 % if this term dominates |
| `offHoldSec` | 0.50 | s | **fixed** | Literal AASM/WASM rule | do not vary |
| `minDurSec` | 0.50 | s | **fixed** | AASM VII / WASM 3.3.1 | do not vary (< 3 % if it is done) |
| `maxDurSec` | 10.0 | s | **fixed** | AASM: bound of the CLM. WASM: beyond it, a long LM that **breaks** the series | do not vary |
| `morphologyWinSec` | 0.50 | s | 0.3–0.8 | WASM 3.2.1-d; best available anti-mattress filter (§3.2) | 2–5 %; **> 20 % on a night with a noisy mattress** |
| `grossBodyFactor` | 40 | × floor | 25–60 | Sicbaldi: 2 506 mg awake vs ~380 mg asleep vs ~15 mg floor | 2–4 % |
| `refractorySec` | 2.0 | s | 1–4 | Filter ringing after a GBM | 2–4 % |
| `minExcursionDeg` | 1.5 | ° | 0.5–4.0 | `TRANSMITTED_SUSPECT` marker — **reported, not excluded** | 0 % (non-exclusive) |

### 6.4 Posture

| Parameter | Default | Unit | Range | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `postureTauSec` | 2.0 | s | 1.0–3.0 | Half-window for comparing ĝ | 2–4 % |
| `postureDeg` | 20.0 | ° | 12–30 | Threshold for a persistent rotation | 3–6 % |
| `stableDeg` | 10.0 | ° | 6–15 | Post-transition stability cone | 2–3 % |
| `stableSec` | 10.0 | s | 5–20 | Distinguishes a lasting change from a movement | 2–4 % |
| `guardSec` | 2.5 | s | 1.5–4.0 | Exclusion window around the transition | 3–5 % |

### 6.5 Clinical rules (not tunable — the variation is test T14, not T12)

| Parameter | AASM_V3 | WASM_2016 | Source |
|---|---|---|---|
| `imiMinSec` | **5.0** | **10.0** | AASM ISR / WASM 3.3.4 |
| `imiMaxSec` | 90.0 | 90.0 | Identical |
| `minClmPerSeries` | 4 | 4 (= 3 IMI) | AASM / WASM 3.3.5 |
| `shortImiPolicy` | `SKIP_LATER` *(interpreted)* | **`BREAK_SERIES`** | AASM silent → WASM 2006 convention; WASM 3.3.6. **This is the parameter that changes everything** (Ferri 2015) |
| `breakOnLongLm` | false | **true** | WASM 3.3.6 |
| `requirePortionInSleep` | **true** | false | New in v3 / WASM 2.4.4 (crossing series allowed) |
| `piImiLow / piImiHigh` | 10 (exclusive) / 90 (inclusive) | same | Ferri 2006; **not 10–50 s** |
| `piMinRunLength` | 3 intervals (= 4 LM) | same | Ferri 2006 |
| `piMinLmRatePerHour` | 10 | same | Drakatos 2021: below this rate, the PI is uninterpretable |

### 6.6 Sleep mask, circularity, publication

| Parameter | Default | Unit | Range | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `epochSec` | 5.0 | s | fixed | van Hees 2015 | n/a |
| `angleDeg` | 5.0 | ° | 3–8 | van Hees 2015 (5°/5 min) | **8–15 % — 2nd most sensitive parameter** |
| `moveFactor` | 6.0 | × floor | 4–10 | Additional amplitude criterion (our addition) | 4–8 % |
| `sustainedMin` | 5.0 | min | 3–10 | van Hees 2015 | 5–10 % |
| `sptMinMin` | 15.0 | min | 10–30 | Bounding of the SPT | 3–6 % |
| `maxFixedPointIterations` | 2 | — | **fixed** | §3.6.3 layer 2; more than 2 can oscillate | n/a |
| `convergenceTstFraction` | 0.25 | — | 0.15–0.40 | Threshold for `MASK_NON_CONVERGENT` | n/a (flag) |
| `maxLagMs` | 600 000 | ms | 300–900 k | Cross-realignment accel ↔ HC (§3.6.4) | 0 % (a search, not a threshold) |
| `minTstFullMin` | 240 | min | 210–300 | Full publication gate (§3.7.2) | n/a (gate) |
| `minTstAnyMin` | 180 | min | 150–240 | Gate below which no PLMI is published | n/a (gate) |

**Two parameters to watch first: `kOn` (10–15 %) and `angleDeg` (8–15 %).** The first is brought under control by the mechanical calibration (§3.3), the second by the correction learned against Health Connect (§3.6.4) or, better, by the manual diary (§3.6.5-a). These are the two calibration loops of the system, and they are not optional: without them, verification criterion no. 4 of v1 ("if the decision flips at ±10 %, the figure is not usable") will most likely not be met.

---

## 7. Assumptions, confidence, and what remains unverified

| Item | Confidence | Note |
|---|---|---|
| Numerical AASM v3 / WASM 2016 rules | **High** | WASM 2016 and 2006 read verbatim in full PDF. AASM v3 **not read** (paywalled): the figures are triangulated from the official Summary of Updates v3 (which lists exactly two changes, none numerical), the AASM FAQ, the Sleep ISR help, and peer-reviewed restatements |
| AASM IMI = 5–90 s (and not 10–90) | **High** | Three independent sources, including the AASM's own ISR help |
| Ferri's PI: 10–90 s, ≥ 3 intervals | **High** on the bounds, **medium** on strict vs inclusive | Ferri 2006 not read (paywalled); formula reconstructed from three verbatim citations, including a methods section co-signed by Ferri |
| Sub-6 Hz PLMS energy | **Medium** | **No spectral analysis of accelerometric PLMS is published.** Inference from Athavale's filter (0.4/1.6 Hz, 87.9 % Se) and Gschliesser's Actiwatch/PAM-RL gap. The physical model of §5.1 arrives at it independently |
| Amplitudes in g at the ankle | **Medium-low** | No published measurement of the peak of a PLMS. Bracketed by Sicbaldi (floor 15 mg, sleep movement 377 ± 63 mg) and the commercial thresholds |
| Mattress transmission | **None** | No published quantification. Every value in §3.2 is an assumption to be measured |
| Respiration at the ankle | **None** | Quantified at the thorax only. The correction of §1.2 is cheap insurance, not an answer to a measured problem |
| Wrist/ankle mask bias | **Medium** | A single study (n = 29, **children**). I have found no adult wrist-vs-ankle validation. And yet it is the largest error item — which is uncomfortable |
| Miss rate of 39 % (Terrill) | **Medium** | EMBC abstract only, full text inaccessible. Consistency with the mechanics (§5.1) makes it plausible |
| Severity of the circularity (§3.6.3) | **High** on the mechanism, **medium** on the magnitude | The calculation "13 movements per 5 min window at IMI 22 s" is arithmetic and certain. The magnitude of the TST collapse depends on the actual settings and is measurable only through T19 |

### Main sources

> **This is not the project bibliography, and it must not be added to.** The bibliography is
> [`../references.md`](../references.md), and it alone carries what matters about a source: whether
> the full text was read or only the abstract, what the project takes from it, and the reservations
> attached. A second list is exactly what produced the citation of Ferri 2016 under two different
> paginations in this repository. **A new source goes into `references.md`; this list
> may be pruned down to the normative documents alone.**

- AASM Summary of Updates in Version 3 (2023) — https://aasm.org/wp-content/uploads/2023/02/Summary-of-Updates-v3.pdf
- AASM Sleep ISR, Scoring Limb Movements — https://isr.aasm.org/helpv5/ScoringLimbMovementsL.html
- AASM Scoring Manual FAQ (items M.4, M.5) — https://aasm.org/resources/pdf/faqsscoringmanual.pdf
- WASM 2016, Ferri et al., Sleep Med 2016;26:86-95 — https://www.irlssg.org/wp-content/uploads/2025/05/WASM-2016-Standards-for-Recording-and-Scoring-Leg-movements-2016.pdf
- WASM 2006, Zucconi et al., Sleep Med 2006;7(2):175-183 — https://worldsleepsociety.org/wp-content/uploads/2018/06/PIIS1389945706000049.pdf
- Ferri et al., Sleep Med 2015;16:1229-1235 (Alt1/Alt2) — https://pubmed.ncbi.nlm.nih.gov/26429751/
- Ferri et al., Sleep Med 2016;22:97-99 (thresholds, PI ≈ 0.50) — https://pubmed.ncbi.nlm.nih.gov/26922620/
- Ferri et al., Sleep Med 2013;14:293-296 (inter-night stability of the PI)
- Manconi et al., Sleep 2015;38(2):295-304 (RRLM −2.0/+10.25 s) — https://pmc.ncbi.nlm.nih.gov/articles/PMC4288611
- Sleep Breath 2023 (AASM vs WASM RRLM, 50.5 vs 90.7 /h) — https://pmc.ncbi.nlm.nih.gov/articles/PMC10163289/
- Athavale et al., SLEEP 2019 (25 Hz, LP 0.4/1.6 Hz, 87.9 %/94.1 %)
- Sicbaldi et al., Sci Rep 2025 (Axivity AX6, 0.1-10 Hz, 15 mg ankle threshold, 377 ± 63 mg) — https://pmc.ncbi.nlm.nih.gov/articles/PMC12770513/
- Sforza et al., Sleep Med 2005;6:407-413 (PAM-RL, 40 Hz, 0.3-20 Hz, 200/100 mg, mean duration 4.2 s; the ± 0.14 is a **standard error over 11 patients**, not a standard deviation — see §5.1) — https://worldsleepsociety.org/wp-content/uploads/2018/06/Sleep-Medicine-6-2005-407%E2%80%93413.pdf
- Gschliesser et al. 2009 (Actiwatch under-counts, PAM-RL over-counts) — https://pubmed.ncbi.nlm.nih.gov/18656421/
- NeuroMetrix patents US9731126 / US10335595 (50 Hz, HP 0.5 Hz, 0.02/0.03 g)
- Terrill et al., EMBC 2013 (39.0 % / 54.9 % of LMs with no detectable movement) — https://pubmed.ncbi.nlm.nih.gov/24111321/
- van Hees et al., PLOS ONE 2015;10(11):e0142533 (5°/5 min rule) — https://pmc.ncbi.nlm.nih.gov/articles/PMC4646630/
- Wiedemann et al. (wrist vs ankle, Cole-Kripke +43 min, van Hees −89 min) — https://pmc.ncbi.nlm.nih.gov/articles/PMC12215244/
- Solnik et al., Eur J Appl Physiol 2010;110:489-498 (TKEO EMG) — https://pmc.ncbi.nlm.nih.gov/articles/PMC2945630/
- Aubol & Milner, IEEE TBME 2019 (TKEO accelerometry, BP 1-20 Hz mandatory) — https://pubmed.ncbi.nlm.nih.gov/31150328/
- Drakatos et al., J Thorac Dis 2021 (PI uninterpretable below 10 LM/h) — https://pmc.ncbi.nlm.nih.gov/articles/PMC8662505/
- Marino et al., Sleep 2013;36(11):1747 (actigraphy Se 0.965 / Sp 0.329) — https://pubmed.ncbi.nlm.nih.gov/24179309/
- ICSD-3 / AASM CPG 2025 (PLMI > 15/h in adults, > 5/h in children) — https://aasm.org/wp-content/uploads/2024/03/Treatment-of-RLS-and-PLMD-CPG.pdf

---

## Check this yourself

1. **Buy the AASM v3 manual and read chapter VII section B directly.** Three precise points: that the 5–90 s IMI is still written there as such; the exact wording of "at least a portion … in an epoch of sleep" and its interaction with a series crossing a wake/sleep boundary; the current wording of the RRLM note after the removal of "sleep-disordered breathing event" in v2.4. The whole of §6.5 depends on it.
2. **Obtain the PDF of Ferri 2006 (Sleep 29:759-769)** in order to settle two things that shift every computed PI value: strict lower bound (`> 10`) or inclusive (`≥ 10`), and numerator in **intervals** or in **movements** (both forms exist in Ferri's own publications). Choose, document, never mix. The PI becoming the primary index in the absence of Health Connect (§3.6.5-c), this point rises in priority.
3. **Measure the mattress transmission and respiration at the ankle yourself.** These are two complete holes in the literature, and the two control nights needed cost less than any additional bibliographic search. Without those measurements, §3.2 remains parameterised conjecture.
4. **Test T11 is a falsification test, not a conformance test.** If it does not show > 40 % deviation without calibration, the ritual of §3.3 is folklore: remove it instead of keeping it "just in case". Same logic for T19 and layer 1 of §3.6.3.
5. **The choice of sleep mask weighs more than the whole detector.** Before investing in tuning `kOn`, measure κ and ΔTST between the accelerometric mask and Health Connect over your 5–7 nights. If κ < 0.4, the PLMI is not comparable from one night to the next however much care goes into the detection, and that has to be sorted out first.
6. **Add the two sleep-diary fields before writing a line of sophisticated mask code.** It is ten lines of UI and it structurally removes the circularity of the denominator (§3.6.5-a), where all the engineering of §3.6.2–3.6.4 only attenuates it. The cost/benefit ratio is comparable to nothing else in this document.
7. **The ICSD-3 threshold of 15/h is not transposable to this measurement.** It is defined on bilateral EMG. With a structural downward bias (unilateral + 39 % of mechanical misses) and an upward bias (RRLM not excluded), the app must display an inter-night trend and an interval, never a figure compared with 15.
8. **Fix `ChunkFormat.toRaw` in `:format`, but do not remove check no. 8 of step −1.** The jerk check is an independent defence against any future regression of the codec, and it costs one subtraction per sample.
