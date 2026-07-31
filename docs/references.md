# References

> **Pendulum** measures periodic limb movements in sleep from a smartwatch worn at the ankle, and
> tracks the *rhythm* between them rather than their number. It is it measures, it does not
> interpret — a real measurement to take to a physician, not a diagnosis and not a medical device — see
> [what this is, and what it is not](../README.md#what-this-is-and-what-it-is-not).

Every source used across the Pendulum project, grouped by theme. For each entry: the citation, a stable
identifier where one exists, and a one-line note on what the project takes from it.

## How to read the access column

The distinction matters and is carried over from the French design documents, where several
conclusions rest on sources that were never read in full.

| Mark | Meaning |
|---|---|
| **full** | Full text or complete PDF retrieved and read |
| **abstract** | Abstract or record read; full text not obtained (paywall, no access) |
| **none** | Not accessed at all. The content is known only through secondary citation, and any figure taken from it should be verified before it is relied upon |

Where an identifier is marked *unverified*, it was not recorded in the project's working notes and
has been supplied from memory. Check it before citing Pendulum as a source for it.

---

## 1. Clinical scoring standards and diagnostic criteria

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| Ferri R. et al. **World Association of Sleep Medicine (WASM) 2016 standards for recording and scoring leg movements in polysomnograms.** *Sleep Med* 2016;26:86–95 | [PDF](https://www.irlssg.org/wp-content/uploads/2025/05/WASM-2016-Standards-for-Recording-and-Scoring-Leg-movements-2016.pdf) | **full** | The complete WASM rule set, read verbatim: interval 10–90 s, series-breaking on short and long intervals (3.3.6), a movement > 10 s ending the sequence, and the morphology criterion 3.2.1-d |
| Zucconi M. et al. **The official WASM standards for recording and scoring periodic leg movements in sleep (WASM 2006).** *Sleep Med* 2006;7(2):175–183 | [PDF](https://worldsleepsociety.org/wp-content/uploads/2018/06/PIIS1389945706000049.pdf) | **full** | The short-interval convention Pendulum adopts where the AASM manual is silent, explicitly labelled as an interpretation |
| **AASM Manual for the Scoring of Sleep and Associated Events, v3** (2023) | paywalled | **none** | Not read. The v3 numeric criteria used in Pendulum are triangulated from the three official sources below |
| **AASM Summary of Updates in Version 3** (2023) | [PDF](https://aasm.org/wp-content/uploads/2023/02/Summary-of-Updates-v3.pdf) | **full** | Confirms exactly two changes to limb-movement scoring in v3, neither numeric; the requirement that part of each movement fall in a sleep epoch |
| **AASM Sleep ISR — Scoring Limb Movements** | [isr.aasm.org](https://isr.aasm.org/helpv5/ScoringLimbMovementsL.html) | **full** | Independent confirmation that the AASM inter-movement interval is 5–90 s, not 10–90 s |
| **AASM Scoring Manual FAQ** (items M.4, M.5) | [PDF](https://aasm.org/resources/pdf/faqsscoringmanual.pdf) | **full** | Confirms that the respiratory exclusion window brackets the *entire* limb movement, not just its onset |
| **AASM Clinical Practice Guideline: Treatment of RLS and PLMD** (2025); ICSD-3 thresholds | [PDF](https://aasm.org/wp-content/uploads/2024/03/Treatment-of-RLS-and-PLMD-CPG.pdf) | **full** | The PLM index thresholds of > 15/h in adults and > 5/h in children |
| Smith M.T. et al. **Use of actigraphy for the evaluation of sleep disorders and circadian rhythm sleep-wake disorders: an AASM clinical practice guideline.** *J Clin Sleep Med* 2018;14(7):1231–1237 | doi:10.5664/jcsm.7230 *(unverified)* | **none** | The AASM's **strong recommendation against** actigraphy as a replacement for EMG in diagnosing PLMD. Quoted in the README and in the limits section |
| Smith R.C. **Relationship of periodic movements in sleep (nocturnal myoclonus) and the Babinski sign.** *Sleep* 1985;8(3):239–243 | PMID 4048739 *(unverified)* | **none** | The clinical morphology of the movement — dorsiflexion of ankle and toes, tonic/clonic pattern in about 75 % of cases. Taken from secondary citation only |

---

## 2. Periodicity, night-to-night variability and diagnostic thresholds

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| Skeba P., Hiranniramol K., Earley C.J., Allen R.P. **Inter-movement interval as a primary stable measure of periodic limb movements of sleep.** *Sleep Med* 2016;17:138–143 | [PMID 26847989](https://pubmed.ncbi.nlm.nih.gov/26847989/) | **abstract** | The decisive result: night-to-night variability of the mean log inter-movement interval 3.6 % ± 3.7 against 43.2 % ± 37.1 for PLMS/h. The reason Pendulum tracks the rhythm, not the count |
| Ferri R. et al. **Night-to-night variability of periodic leg movements during sleep in restless legs syndrome and periodic limb movement disorder.** *Sleep Med* 2013;14(3):293–296 | [PMID 23068780](https://pubmed.ncbi.nlm.nih.gov/23068780/) | **abstract** | The periodicity index varies more than 6.5× less than the PLMS index in RLS (about 2× in PLMD) |
| Ferri R. et al. **Diagnostic accuracy of the standard and alternative periodic leg movement during sleep indices.** *Sleep Med* 2016;22:97–99 | [PMID 26922620](https://pubmed.ncbi.nlm.nih.gov/26922620/) | **abstract** | Optimal cut-offs 15–16/h (standard index), ≈ 13/h (alternative index), ≈ 0.5 (periodicity index), with comparable ROC areas. The periodicity index therefore has its own published threshold |
| Ferri R. et al. **Scoring rule variants for leg movement series (Alt1 / Alt2).** *Sleep Med* 2015;16:1229–1235 | [PMID 26429751](https://pubmed.ncbi.nlm.nih.gov/26429751/) | **abstract** | The key finding that only the series-breaking rule, not the interval bound, changes results significantly. Corrects the most common secondary-source error about AASM vs WASM |
| Ferri R. et al. **Periodic leg movements during sleep: review.** *Sleep* 2026 | PMID 42213077 | **abstract** | The argument that periodicity, burst clustering, stage dependence and autonomic coupling carry more clinical information than event counts |
| Ferri R. et al. **The periodicity index.** *Sleep* 2006;29:759–769 | paywalled | **none** | The original definition. Not read; the formula Pendulum implements is reconstructed from three verbatim citations, one from a Ferri-co-authored methods paper. Two open questions remain: strict versus inclusive lower bound, and numerator in intervals versus movements |
| Ferri R. et al. *J Clin Sleep Med* 2022 — periodicity index reference values | identifier not recorded | **abstract** | Reference values: RLS 0.601 ± 0.189, controls 0.092 ± 0.152 |
| Mogavero M.P. et al. 2024 — periodicity index in controls | identifier not recorded | **abstract** | Controls at 0.220 ± 0.229, illustrating the instability of the index at low movement rates |
| Drakatos P. et al. *J Thorac Dis* 2021 | [PMC8662505](https://pmc.ncbi.nlm.nih.gov/articles/PMC8662505/) | **full** | The validity condition: the periodicity index is uninterpretable below about 10 limb movements per hour |

---

## 3. Validation of actigraphic and accelerometric PLM detection

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| Terrill P.I. et al. **Evaluation of an accelerometry-based device for sleep and PLM monitoring.** *Proc IEEE EMBC* 2013 | [PMID 24111321](https://pubmed.ncbi.nlm.nih.gov/24111321/) | **abstract** (full text inaccessible) | 39.0 % of EMG-scored movements, and 54.9 % of piezo-scored movements, produce no detectable accelerometric movement. The single most consequential number in the project: it makes the accelerometric count a different quantity, not a noisy estimate |
| Spektor E. et al. **Ankle-worn triaxial accelerometry validated against polysomnography.** *Clocks & Sleep* 2024 | identifier not recorded *(to be verified)* | **abstract** | The feasibility argument: r = 0.98, event-level sensitivity 86.7 % / specificity 92.3 %, and 85.7 % / 95.2 % for classifying PLMI ≥ 15/h, from a **unilateral** ankle sensor. Important caveat recorded in the critical review: the scoring was manual, the cohort enriched, and a between-subject correlation is not event-level accuracy |
| Sicbaldi M. et al. **Leg movement detection with a wrist- and ankle-worn accelerometer (Axivity AX6).** *Sci Rep* 2025 | [PMC12770513](https://pmc.ncbi.nlm.nih.gov/articles/PMC12770513/) | **full** | 100 Hz, 0.1–10 Hz band, 15 mg ankle detection threshold, typical sleep movement magnitude 377 ± 63 mg, waking movement 2506 ± 240 mg, movement duration 4.5 ± 0.4 s. The main amplitude anchor in the absence of a published PLMS peak |
| Sforza E. et al. **Determination of periodic leg movements with an ambulatory device (PAM-RL).** *Sleep Med* 2005;6:407–413 | [PDF](https://worldsleepsociety.org/wp-content/uploads/2018/06/Sleep-Medicine-6-2005-407%E2%80%93413.pdf) | **full** | 40 Hz, 0.3–20 Hz band, 200 mg onset / 100 mg offset thresholds, **1 s drop-out time** (a kick ends only after the signal stays below the 100 mg decay threshold for a full second); movement duration at the ankle 4.2 s and mean IMI 31.3 s in RLS — the duty-cycle figure that governs noise-floor contamination. **Correction, 2026-07-31:** the "± 0.14" and "± 1.3" formerly quoted here as dispersions are **standard errors of the mean** — §2.5 of the paper states "results in the text and in the tables are expressed as mean ± standard error of the mean", and the RLS group is 11 patients. Between-patient SD of the mean duration is thus ≈ 0.46 s; the event-level SD is not published. The 1 s drop-out time and the 100/200 mg hysteresis are the two figures that bound the intra-event structure of a 4.2 s movement (see `07-validation.md` §5.5) |
| Gschliesser V. et al. **PLM detection by actigraphy: comparison of two systems against PSG.** 2009 | [PMID 18656421](https://pubmed.ncbi.nlm.nih.gov/18656421/) | **abstract** | The Actiwatch (3 Hz high-pass) under-counts badly (21.2 ± 25.6 vs 34.4 ± 30.7, p < 0.001) while the PAM-RL (0.3 Hz corner) over-counts (63.6 ± 39.3 vs 37.0 ± 33.5, p = 0.009). Half of the inference that PLMS energy is low-frequency |
| Athavale Y. et al. **Automated PLM detection from accelerometry.** *SLEEP* 2019 | identifier not recorded | **abstract** | 87.9 % sensitivity / 94.1 % specificity for PLMI ≥ 15 using a low-pass filter at 0.4 Hz (stopband 1.6 Hz). The other half of the low-frequency inference — and evidence that real discriminative information lives *below* 0.5 Hz |
| Yang C.-K. et al. 2013 — PAM-RL default parameters | identifier not recorded | **abstract** | Default vendor parameters under-detect badly. The reason Pendulum uses an adaptive threshold anchored to a measured noise floor, never a fixed value in g |
| **S-PLMAD** — automated PLM detector for EMG | identifier not recorded | **abstract** | The source of the 0.15 s RMS window inherited by Pendulum v1. Identified as a transposition error: S-PLMAD operates on 512 Hz EMG in a 10–300 Hz band, so the window is meaningless for a 1–5 Hz accelerometric signal |
| Marino M. et al. **Measuring sleep: accuracy, sensitivity and specificity of wrist actigraphy compared to PSG.** *Sleep* 2013;36(11):1747 | [PMID 24179309](https://pubmed.ncbi.nlm.nih.gov/24179309/) | **abstract** | Actigraphy sensitivity 0.965, specificity 0.329 — very good at calling sleep, very poor at calling wake |
| **NeuroMetrix patents US9731126 / US10335595** | US9731126, US10335595 | **abstract** | 50 Hz sampling, 0.5 Hz high-pass, 0.02 / 0.03 g detection thresholds — the published configuration closest to the Pendulum one, and the main anchor for the absolute floor |

---

## 4. Respiratory-related leg movements

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| Manconi M. et al. **Respiratory-related leg movements.** *Sleep* 2015;38(2):295–304 | [PMC4288611](https://pmc.ncbi.nlm.nih.gov/articles/PMC4288611) | **full** | The WASM-recommended exclusion window of −2.0 s to +10.25 s around the end of the respiratory event |
| **Comparison of AASM and WASM RRLM definitions.** *Sleep Breath* 2023 | [PMC10163289](https://pmc.ncbi.nlm.nih.gov/articles/PMC10163289/) | **full** | 90.7 ± 112.1 events/h classified as respiratory-related under WASM against 50.5 ± 70.2 under AASM — a factor of 1.8 between two official definitions, with the respiratory channel available |

---

## 5. Sleep/wake scoring algorithms and consumer sleep sources

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| van Hees V.T. et al. **A novel, open-access method to assess sleep duration using a wrist-worn accelerometer.** *PLOS ONE* 2015;10(11):e0142533 | [PMC4646630](https://pmc.ncbi.nlm.nih.gov/articles/PMC4646630/) | **full** | The 5° / 5 min arm-angle rule that Pendulum adopts in place of Cole-Kripke, and the epoch and sustained-immobility parameters |
| Wiedemann et al. — wrist versus ankle placement, sleep-wake scoring in children (n = 29) | [PMC12215244](https://pmc.ncbi.nlm.nih.gov/articles/PMC12215244/) | **full** | Total sleep time bias against PSG: Cole-Kripke at the ankle +43 min [20, 66], van Hees/GGIR at the ankle −89 min [−116, −63]. Establishes the sleep mask as the project's largest single error term. **Sole study; children only; no adult equivalent found** |
| Kim D., Joo E.Y., Choi S.J. **Validation of the Galaxy Watch 3 against polysomnography.** *J Sleep Med* 2023;20(1):28–34 | [doi:10.13078/jsm.230004](https://doi.org/10.13078/jsm.230004) | **full** | Sleep-detection sensitivity 0.954, specificity 0.524, TST bias +9.5 min, four-stage accuracy 0.651, κ 0.34–0.47. Justifies using total sleep time and refusing per-stage indices. Applies to the Watch 3, not the Watch 5 — the transfer is an extrapolation |
| Chinoy E.D. et al. **Performance of seven consumer sleep-tracking devices compared with polysomnography.** *Sleep* 2021;44(5):zsaa291 | [academic.oup.com](https://academic.oup.com/sleep/article/44/5/zsaa291/6055610) | **full** | Corroborates the same pattern across seven devices: sleep detection good, stage assessment inconsistent |

---

## 6. Signal processing

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| Solnik S. et al. **Teager-Kaiser energy operator signal conditioning improves EMG onset detection.** *Eur J Appl Physiol* 2010;110:489–498 | [PMC2945630](https://pmc.ncbi.nlm.nih.gov/articles/PMC2945630/) | **full** | The documented TKEO benefit — onset error 124 ms → 55 ms — used as the argument for **rejecting** TKEO here: Pendulum clinical criteria have 500 ms granularity, so the gain buys nothing |
| Aubol K.G., Milner T.E. **Gait event detection from accelerometry using the Teager-Kaiser energy operator.** *IEEE Trans Biomed Eng* 2019 | [PMID 31150328](https://pubmed.ncbi.nlm.nih.gov/31150328/) | **abstract** | Confirms that TKEO applied to accelerometry requires a preceding 1–20 Hz band-pass because it amplifies high-frequency noise. The second argument for rejecting it |
| Solnik S. et al. *Acta Bioeng Biomech* 2008 | identifier not recorded | **none** | Attribution note: the widely quoted "40 ± 99 ms vs 229 ± 356 ms, p = 0.023" originates here, **not** from Li, Zhou & Aruin 2007, as secondary literature repeatedly claims |
| Li X., Zhou P., Aruin A.S. 2007 | paywalled | **abstract** | Checked only to establish the misattribution above; the abstract contains no numeric result |

---

## 7. Sensors, hardware and platform

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| **Android — sensor suspend mode** | [source.android.com](https://source.android.com/docs/core/interaction/sensors/suspend-mode) | **full** | Normative at HAL level: a wake-up sensor **must** wake the SoC before overflowing its FIFO; a non-wake-up sensor is explicitly permitted to drop the oldest samples. The whole capture strategy follows from this |
| **Android 15 behaviour changes** | [developer.android.com](https://developer.android.com/about/versions/15/behavior-changes-15) | **full** | The 6 h / 24 h foreground-service timeout applies to `dataSync` and `mediaProcessing` only; `health` has none, and `health` may be started from `BOOT_COMPLETED` |
| **Foreground service types** | [developer.android.com](https://developer.android.com/develop/background-work/services/fg-service-types) | **full** | Permission requirements for a `health` foreground service, and the while-in-use restriction on body-sensor permissions |
| **Wearable Data Layer — data items** | [developer.android.com](https://developer.android.com/training/wearables/data/data-items) | **full** | 100 KB payload ceiling per `DataItem`, buffering across disconnection, and the up-to-30-minute sync delay without `setUrgent()` |
| **Wearable Data Layer overview** | [developer.android.com](https://developer.android.com/training/wearables/data-layer) | **full** | Items sync when a connection is re-established; the Data Layer does not work with a watch paired to iOS |
| **Health Connect — data types, read, aggregate, get started, sleep sessions** | [developer.android.com](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types) · [read](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data) · [aggregate](https://developer.android.com/health-and-fitness/guides/health-connect/develop/aggregate-data) · [sleep](https://developer.android.com/health-and-fitness/health-connect/features/sleep-sessions) | **full** | Permission model, rationale-activity requirement, session and stage record structure |
| **`SleepSessionRecord` source** (androidx) | [android.googlesource.com](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/health/connect/connect-client/src/main/java/androidx/health/connect/client/records/SleepSessionRecord.kt) | **full** | Exact stage constants and the fact that gaps in the stage list are permitted — a choice Pendulum must make explicit |
| **Samsung Health — Health Connect integration** | [developer.samsung.com](https://developer.samsung.com/health/blog/en/managing-sleep-data-with-samsung-health-and-health-connect) · [FAQ](https://developer.samsung.com/health/health-connect-faq.html) | **full** | Strongly suggests but never states that Samsung Health writes sleep *stages* to Health Connect. Flagged as the single most important unverified point in the sleep-source document |
| **Sleep as Android — Health Connect** | [sleep.urbandroid.org](https://sleep.urbandroid.org/docs/services/health_connect.html) | **full** | Confirms stage export; written at wake time rather than after a vendor Bluetooth sync |
| **Polar Flow — Health Connect** | [support.polar.com](https://support.polar.com/en/flow-app-health-connect) | **full** | Confirmed stage support |
| **Garmin — Health Connect FAQ** | [support.garmin.com](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5) | **none** (page did not render) | Data-type list known second-hand only |
| **Whoop — Google Health integration** | [support.whoop.com](https://support.whoop.com/s/article/Google-Health-Integration-For-Android) | **none** (HTTP 403) | Known only from search snippets |
| **Google / Fitbit sleep support pages** | [12201872](https://support.google.com/android/answer/12201872) · [13770384](https://support.google.com/android/answer/13770384) · [17068213](https://support.google.com/googlehealth/answer/17068213) | **full** | Account migration and sleep-data availability. Migration dates specifically remain unverified |
| **Bosch BMI270 product page** | [bosch-sensortec.com](https://www.bosch-sensortec.com/products/motion-sensors/imus/bmi270/) | **full** | The 685 µA figure that anchors the accelerometer-versus-gyroscope power argument. The remaining rows of the current table were reconstructed from a PDF text extraction and are **not** corroborated |

---

## 8. Questionnaires

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| **Single Question for Rapid Screening of RLS** | identifier not recorded | **abstract** | 100 % sensitivity / 96.8 % specificity; used as the entry filter |
| **Cambridge-Hopkins RLS questionnaire (CH-RLSq)** | Cambridge repository, PDF freely available | **abstract** | 87.2 % sensitivity / 94.4 % specificity. **Licence to be checked before integration, even for personal use** |
| **IRLS severity scale** | IRLSSG | **none** | Deliberately **excluded** — under IRLSSG copyright |

---

## 9. Prior art and patents

| Source | Identifier | Access | What the project takes from it |
|---|---|---|---|
| **US 10,335,085 — Device and method for detection of periodic leg movements** (Johns Hopkins; R.P. Allen named) | US10335085 | **none** (Justia returned HTTP 403) | Explicitly mentions an accelerometer held on the leg by a strap. Assignee, claims and maintenance status **unverified**. Raised as a fact worth knowing, not as legal advice |
| **NeuroMetrix US9731126 / US10335595** | see §3 | **abstract** | Closest published sensing configuration to the Pendulum one |
| **SOMNOwatch** (SOMNOmedics) | vendor material | **none** | Medical-grade precedent, out of reach for individual use (~€1,862) |
| **Philips PAM-RL**, **Actiwatch**, **RestEaZe** | discontinued | **none** | Discontinued precedents; the Actiwatch and PAM-RL validation data are the ones cited in §3 |

---

## Access summary

Of the sources above, the ones that most deserve to be obtained and read in full — because a
project-level conclusion currently rests on a paywall or a secondary citation — are:

1. **AASM Scoring Manual v3, chapter VII section B.** Every numeric criterion in the AASM rule set is
   currently triangulated rather than read.
2. **Ferri 2006, *Sleep* 29:759–769.** The periodicity index formula is reconstructed. Two ambiguities
   remain unresolved (strict versus inclusive lower bound; numerator in intervals versus movements)
   and each shifts every computed value.
3. **Terrill 2013 full text.** The 39 % figure is the load-bearing number for the entire "different
   quantity, not a noisy estimate" argument, and only the abstract has been read.
