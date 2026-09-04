# Pendulum — Guide to choosing the sleep data source

**Purpose.** Pendulum counts leg movements with a Pixel Watch 3 on the ankle. To turn that count into a **PLM Index (movements per hour of sleep)**, a denominator is needed: total sleep time (TST), and if possible the hypnogram. That denominator must come from **another** device, read through **Health Connect** on the Android phone. This document says which one to choose, why, and how to check before writing a line of code.

**Written on 29 July 2026.**

> **This document remains the reference on the Health Connect integration** — §6 (permissions, the
> double declaration of the rationale activity, the exact structure of `SleepSessionRecord`,
> deduplication by `dataOrigin`) and §7, which lists one by one the **thirteen unverified
> points**. Its counterpart among the numbered files, shorter, is
> [`../05-devices.md`](../05-devices.md).
>
> Two things have moved since. §4 concluded that the absence of Health Connect "is not
> blocking": that point has been explicitly settled elsewhere, and the ruling is now copied
> to the place where it arises, at the end of §4. And the check in §5, written to be done "BEFORE
> writing any code", was never done as such — the code exists. What the bench actually
> measured on real hardware is in [`BENCH-LOG.md`](BENCH-LOG.md) §11.4 and §7.3; what remains
> to be measured, starting with the real latency of your own chain, is unchanged.

### Method and limits of this verification

The session's web search quota was exhausted. All the checks were therefore done by **fetching primary pages directly** (Android/AndroidX documentation, androidx source code, manufacturer support pages, a scientific article) and by using a **search engine as a proxy** (DuckDuckGo lite) to locate the URLs. Consequence: the points backed by a primary page fetched in full are **solid**; those backed only by a search-result snippet are marked **"not verified"** and there are several of them. Section 7 lists them all. Do not build anything critical on a line marked "not verified" without checking it yourself.

---

## 1. Why Pendulum needs two distinct sensors

### The problem is a division

The PLMI is a fraction:

```
PLMI = (number of PLMS)  /  (hours of SLEEP)
```

The ankle watch produces the **numerator** very well. It cannot honestly produce the **denominator**. And that is a deeper problem than a mere lack of precision.

### Why the ankle watch cannot do both

**a) Circularity.** Classical actigraphy infers "asleep / awake" from a movement statistic: a lot of movement = wake, little movement = sleep. But the event that Pendulum is trying to count **is** a movement. If the same signal is used for the numerator and for the denominator, every burst of PLMS pushes the algorithm to declare "wake". Result: sleep time shrinks **exactly during the periods when there are the most movements to count**. The numerator goes up, the denominator goes down, and the error on the ratio is doubled in the same direction. This is a systematic bias, not noise — it cannot be averaged away over several nights.

**b) Sleep algorithms are calibrated for the wrist.** Cole-Kripke type rules, and the models in consumer watches, were fitted on **wrist** movement statistics. A sleeper's ankle moves differently: it is more often perfectly still, then produces far stronger accelerations (the SPEC itself notes amplitudes ×2 to ×3 depending on strap play, and ×2 to ×2.3 of amplification by the bedding). A threshold calibrated at the wrist and applied at the ankle gives a meaningless result.

**c) Stages cannot be read from movement alone.** Consumer watches classify light / deep / REM by combining movement **and** heart rate / heart rate variability, measured with PPG. PPG at the ankle is unreliable, and the SPEC already anticipates that "worn / not worn" detection (off-body, also PPG-based) is a risk to be validated in phase 1. With no usable cardiac signal, no stages.

**d) The ankle watch is already busy.** It records the raw accelerometer at 50 Hz all night through `SensorManager`, on a tight battery budget. Asking it to run a staging model on top of that is neither possible (Health Services does not give the raw accelerometer, and `ExerciseClient` is explicitly proscribed by the SPEC) nor desirable.

The SPEC nevertheless provides a backup mask `deriveImmobilityMask()` computed from the ankle accelerometer. **It is a safety net, not the main source.** It exists so that a night stays analysable immediately if Health Connect does not answer — and that is precisely why the PLMI is computed twice (accelerometer mask / Health Connect mask) and why both are reported. The gap between the two is itself information.

### What the stages actually bring — and what they do not

**What they do not bring: nothing about the PLMI figure itself.** The AASM definition of the PLMI is a simple count divided by hours of sleep, with no weighting by stage. A **binary** "asleep / awake" mask is enough to compute a correct PLMI. Going from binary to four stages does not make the index one jot more accurate.

**What they really bring, and it is far from nothing:**

1. **Separating PLMS from PLMW.** Movements while awake do not count in the PLMI and must be reported separately (trap no. 7 of the SPEC; a high PLMW is itself an indication of RLS). But a binary mask already does that.
2. **The biological plausibility check.** This is the main contribution, and it corresponds to verification level 5 of the SPEC. PLMS are supposed to cluster in N1/N2 and thin out in REM (muscle atonia of paradoxical sleep). If your distribution of CLM per stage is flat, or worse, dense in REM, your detector is probably catching something other than PLMS. **Without the stages, you have no way of detecting that error.** It is a regression test against the real world, where the synthetic can say nothing.
3. **The kappa between the accelerometer mask and the hypnogram**, planned in the SPEC. It tells you how reliable your safety net is, and therefore how much you can trust the nights where Health Connect returned nothing.
4. **Reading by a sleep physician.** A report that shows a hypnogram with the CLMs overlaid can be discussed; a report that just shows "PLMI = 22" cannot.

### The good news, and it is solid

What Pendulum **needs** (the TST, therefore the sleep/wake boundary) is precisely what consumer watches do **best**. What they do **badly** (the stages) is what Pendulum needs **least**.

Galaxy Watch 3 validation against polysomnography, N = 32 (Kim D, Joo EY, Choi SJ, *J Sleep Med* 2023;20(1):28-34, [doi:10.13078/jsm.230004](https://doi.org/10.13078/jsm.230004)):

| Measure | Result |
|---|---|
| Sleep detection sensitivity | **0.954** |
| Specificity (wake detection) | 0.524 |
| 4-stage classification accuracy | 0.651 |
| Cohen's kappa: wake / light / deep / REM | 0.47 / 0.34 / 0.34 / 0.39 |
| TST bias (watch − PSG) | **+9.5 min** |
| Light / deep / REM bias | −28.6 / +11.6 / +26.3 min |
| REM ICC | 0.153 (very poor) |

Reading: the TST is excellent (bias +9.5 min over a night of ~7 h, that is ~2 %), the stages are mediocre (kappa 0.34–0.47 = fair to moderate agreement). This result is consistent with Chinoy et al., *Sleep* 2021;44(5):zsaa291 ([academic.oup.com](https://academic.oup.com/sleep/article/44/5/zsaa291/6055610)), which concludes across seven consumer devices that sleep detection is good but that "device sleep stage assessments were inconsistent".

**The bias you have to know about.** A specificity of 0.524 means the watch declares "asleep" for close to one wake epoch in two. It therefore **overestimates** sleep time. Since the TST is in the denominator, your PLMI is structurally **underestimated** by this source. That runs against the upward RRLM bias described in the SPEC (overestimation in case of apnoea) — and, as with the unilateral measurement, **one must not claim that these biases cancel out**. They must be named separately in the report.

Careful: this study covers the **Galaxy Watch 3**, not the 5. Extrapolating to the GW5 is reasonable (same family of Samsung algorithms) but remains an unverified extrapolation.

---

## 2. Correcting two confusions

### 2.1 "A watch with a gyroscope" — no, it is the accelerometer that does the work

**What an accelerometer measures:** linear acceleration, in m/s², on three axes — plus gravity at all times. It answers the question "how fast is this point starting to move, and which way is it tilting?".

**What a gyroscope measures:** **angular** velocity, in degrees per second. It answers "how fast is this object rotating about itself?".

A PLMS is typically a dorsiflexion of the ankle and an extension of the big toe, lasting 0.5 to 10 s. Seen from the ankle, it is a **burst of acceleration**: the foot leaves, stops, comes back. The accelerometer records it directly. This is exactly the setup validated by Spektor et al. 2024 (*Clocks & Sleep*), cited as a foundation of the SPEC: a unilateral **tri-axial** accelerometer at the ankle, r = 0.98 against polysomnography. No gyroscope anywhere in the story.

The gyroscope would add the rotational component of the movement. And that is precisely redundant here, for two reasons:

1. The SPEC pipeline computes `magnitudeL2(x, y, z)`, that is the norm of the acceleration vector, which is **invariant to strap orientation**. The directional information was deliberately thrown away because it depends on how the watch is strapped on, not on the movement. The gyroscope would bring information in a space we discard anyway.
2. Detection relies on an **RMS envelope** compared to an adaptive noise floor. That is an energy measurement. An extra measurement axis does not improve the detection of an energetic event already well above the floor.

**The battery cost, costed.** Bosch BMI270 (an IMU explicitly intended for watches and wristbands), datasheet BST-BMI270-DS000-08, current consumption table:

| Mode | Typical current |
|---|---|
| Accelerometer + gyroscope, *performance*, max ODR | 970 µA |
| Accelerometer + gyroscope, *normal*, max ODR | 685 µA |
| Accelerometer + gyroscope, *low power*, 25 Hz | 420 µA |
| **Accelerometer only, *normal*, max ODR** | **210 µA** |
| **Accelerometer only, *low power*, 25 Hz** | **10 µA** |
| Suspend | 3.5 µA |

Sources: [BMI270 product page](https://www.bosch-sensortec.com/products/motion-sensors/imus/bmi270/) (confirms the 685 µA "in full ODR and aliasing free operation") and the datasheet PDF. **A reservation, for honesty:** the column/value alignment was reconstructed from a text extraction of the PDF; the order of the six values is coherent and corroborated by the product page for the 685 µA value, but check the table in a PDF reader if you want to cite these figures elsewhere.

Orders of magnitude to remember: at maximum rate, turning the gyroscope on **triples** the sensor's consumption (210 → 685 µA). In low-power mode at 25 Hz, it multiplies it by **~40** (10 → 420 µA). Over an 8 h night with a target of "battery > 20 % on waking" (the SPEC's blocking P1 criterion), that is the difference between a plan that holds and a plan that does not.

These figures concern the **sensor die**, not the whole watch — on a Pixel Watch 3, the consumption of the woken SoC, of the wake lock and of disk writing probably dominate. But the conclusion does not move: **the gyroscope is expensive and brings nothing here. Never turn it on.**

What really matters when choosing an ankle watch is therefore not "does it have a gyroscope" but: **can its raw accelerometer be read, at 50 Hz, for 8 h, from a third-party app?**

### 2.2 Accessible raw sensor vs aggregated ecosystem data — the real criterion

This is the most useful distinction in the whole document.

**Raw data.** A third-party app calls `SensorManager.getDefaultSensor(TYPE_ACCELEROMETER)` and receives `(x, y, z, timestamp)` samples at the requested rate. It sees the physical signal. It writes its own algorithm. The result is reproducible, debuggable, and testable against synthetic data. That is what Pendulum's `wear` module does.

**Aggregated data.** The manufacturer applies **its** proprietary processing and publishes only the result: "activity counts", "sleep score", "restlessness", "active minutes". The parameters are undocumented, change with firmware updates, and are not reproducible.

The SPEC already contains the proof by failure: the published attempts with the ActiGraph GT3X give "very low similarity with PSG" **not** because the sensor is bad, but because all that was accessible were proprietary *activity counts* aggregated at too low a rate. The sensor was good; the access was bad.

**And here is the reversal, which is the whole point of this document: the criterion flips depending on the device's role.**

| | ANKLE watch (numerator) | SLEEP source (denominator) |
|---|---|---|
| What is needed | **Raw** accelerometer, 50 Hz, 8 h | An **aggregated hypnogram** |
| So one needs | A platform with an open sensor API → **Wear OS** (or a research logger such as an Axivity AX3) | Any ecosystem **that exports to Health Connect** |
| What disqualifies | A ring, a closed wristband, any device that only returns scores | A device that **does not write** into Health Connect, or that only writes the duration there |
| Sensor quality | Critical | **Almost irrelevant** — see §1, the TST is reliable everywhere |

In other words: for the ankle, an Oura Ring with an excellent sensor is **unusable** because it is closed. For sleep, a device with a mediocre sensor is **perfectly acceptable** as soon as it pushes a `SleepSessionRecord` into Health Connect. The only criterion that counts for the second role is the **plumbing**, not the sensor.

---

## 3. Comparison table of hypnogram sources (state of play 2026)

Legend: `[v]` verified against a primary source · `[?]` probable but **not verified** · `[x]` no / excluded

| Source | Writes into Health Connect? | Stages or duration only? | Sync delay | Price | Subscription required? | Battery life | Published validation |
|---|---|---|---|---|---|---|---|
| **Samsung Health / Galaxy Watch 5** | `[v]` Yes, since Samsung Health 6.22.5 (Oct. 2022) | `[?]` **Stages** (Awake / Light / REM / Deep) — see note | Watch → phone: governed by the **watch's battery policy** (unspecified). Phone → HC: "as soon as data is created or changed" | Already owned | No | ~2-3 d (GW5) | `[v]` GW3: TST +9.5 min, 4 stages 0.651, kappa 0.34-0.47 |
| **Sleep as Android** (phone app, possibly + watch) | `[v]` Yes | `[v]` **Sleep phases** + resting HR + SpO2 | Written at the end of tracking, on waking (no manufacturer Bluetooth sync to wait for if tracked by the phone) | Paid app after a trial (unverified pricing not verified) | `[?]` not verified | n/a (phone on mains) | `[x]` The sonar/microphone mode alone has no known staging validation |
| **Pixel Watch + Google Health** (formerly Fitbit) | `[v]` "third-party connections through Android Health Connect" | `[?]` Stages **not confirmed** on an official page | `[?]` not verified | Watch already owned — **but busy on the ankle** | No for basic sleep; Google Health Premium (formerly Fitbit Premium) for advanced analysis | ~24 h (PW3) | Not found |
| **Garmin (Garmin Connect)** | `[v]` Yes, since ~July 2025 · **one-way** (Garmin writes, does not read) | `[?]` Sleep yes; **stages not confirmed** | `[?]` not verified | 200–1000 € | No (Connect+ optional) | 5-20 d | Not found |
| **Oura Ring** | `[v]` Yes, Android **only**, Gen2/Gen3+ | `[?]` The support page lists Activity / Body measurements / Vitals — **sleep and stages do not appear there explicitly** | `[?]` Documented trap: "might not import data if both apps were not opened before midnight", Background App Refresh required | ~350 € + | `[v]` **Yes, an active membership is required** | ~7 d | Not found |
| **Whoop** | `[v]` Yes (Recovery, Strain, Sleep) | `[?]` Stages not confirmed | `[?]` not verified | Hardware included in the subscription | `[v]` **Yes, 100 % subscription model** | ~4-5 d | Not found |
| **Polar (Polar Flow)** | `[v]` Yes | `[v]` **"Sleep: start time, end time, stages from Polar sleep phases"** — the only manufacturer to document it in black and white | `[?]` "continuously while the connection remains active" | 150–500 € | **No** | 3-7 d | Not found |
| **Withings (Health Mate)** — ScanWatch, Sleep Analyzer under the mattress | `[v]` Yes (a support article exists) | `[?]` Stages not confirmed; user reports of **incomplete** sleep sync | `[?]` Sleep Analyzer: on mains, Wi-Fi sync → potentially the fastest | ScanWatch ~250-350 € · Sleep Analyzer ~130 € | No | ScanWatch ~30 d · Sleep Analyzer: **mains, unlimited** | Not found |
| **Xiaomi / Amazfit — Zepp** | `[v]` Yes, extended in Jan. 2025 to **26 types**, **one-way** | `[?]` "sleep stats"; stages not confirmed | `[?]` not verified | 40–300 € | No | 7-20 d | Not found |
| **Apple Watch** | `[x]` **Excluded** | — | — | — | — | — | — |
| **Sleep Cycle** (phone app only) | `[?]` **Not confirmed** | Detects the stages internally, but HC export not verified | On waking | Freemium | `[?]` not verified | n/a | Not found |
| **Google Nest Hub 2 — Sleep Sensing** (Soli radar) | `[x]` **No path to Health Connect found** | — | — | ~100 € (unverified product status uncertain) | Free to date (the switch to Premium has been postponed several times) | Mains | Not found |

### Notes and traps by source

**Samsung Health — the nuance about the stages.** The official developer FAQ ([developer.samsung.com/health/health-connect-faq.html](https://developer.samsung.com/health/health-connect-faq.html)) says that "Activity data, such as steps and exercise, heart rate, and sleep, are synchronized between Samsung Health and Health Connect" — it says **sleep**, without specifying "stages". The Samsung developer blog ([Managing Sleep Data with Samsung Health and Health Connect](https://developer.samsung.com/health/blog/en/managing-sleep-data-with-samsung-health-and-health-connect)) explicitly works with the four stages **Awake, Light, REM, Deep** and describes the synchronisation of sessions **and of stages** in both directions. Reading the two together weighs heavily in favour of the stages, but **no page states it literally for the Samsung Health → Health Connect direction**. That is exactly why section 5 exists, and why the SPEC itself notes it under "check this yourself".

**Samsung Health — activation is mandatory.** It is not automatic. In Samsung Health: `Settings > Health Connect > App permissions`. Then `Settings > Sync with Samsung account > Sync now`.

**Apple Watch — why it is excluded, and this is not a value judgement.** HealthKit is an iOS API. There is no Apple Watch app for Android, pairing requires an iPhone, and there is no official HealthKit → Health Connect bridge. Even if the data were perfect, it would never reach the Android phone. Out of scope by construction.

**Pixel Watch — the inventory trap.** The Pixel Watch 3 probably writes good sleep data into Health Connect through Google Health. But in Pendulum **it is on the ankle**. It cannot measure sleep at the wrist and count movements at the ankle on the same night. It is counted in the table for the record, not as a real option — unless a second Pixel Watch is bought.

**The Fitbit → Google Health change, 2026.** Since **19 May 2026**, the Fitbit app has become the **Google Health** app ([support.google.com/googlehealth/answer/17068213](https://support.google.com/googlehealth/answer/17068213)): four tabs Today / Fitness / Sleep / Health, Fitbit Premium having become Google Health Premium, a 3-month trial. The page confirms third-party connections "through Android Health Connect, Apple Health, and other third-party apps". A migration of Fitbit accounts to Google accounts accompanied that switch (the switch-over and non-migrated-data deletion dates: **not verified**, the information came from a search engine with redirect URLs that could not be resolved).

**The three traps to spot in any source:**
1. **Duration only.** The app writes a `SleepSessionRecord` with an empty `stages` or a single `STAGE_TYPE_SLEEPING`. Technically compliant, useless for the plausibility check. Only reading through the API reveals it (§5).
2. **Stages behind a paywall.** The historical Fitbit Premium model. Check the state of your subscription **before** concluding that the source "does not work".
3. **Manual sync.** The Oura case, explicitly documented (both apps must have been opened, Background App Refresh active). A source that requires a user gesture every morning breaks the SPEC's P6 criterion ("cold path with no manual intervention").

---

## 4. Ranked recommendation

### Rank 1 — Galaxy Watch 5 + Samsung Health. The zero-purchase option.

**Why.** Already owned. Writes into Health Connect since 2022 over a proven chain. Stages almost certain. It is the only option whose staging accuracy is **quantified in the literature** (on the GW3). No spending, no additional app, a single box to tick.

**The night protocol.** Pixel Watch 3 on the ankle, Galaxy Watch 5 on the wrist, the same night. Two watches, yes. That is the constraint inherent to the project; it will not disappear with any source.

**The known risk.** The SPEC calls it "the late sync trap" and the Samsung FAQ confirms it in its own vocabulary: the watch → phone transfer is governed by the **watch's battery policy**, not by waking. Once on the phone, the write to Health Connect is immediate. The `SleepFetchWorker` counter-measure (T+30 min then backoff 1/2/4/8 h, giving up at T+36 h) is correctly sized. Opening Samsung Health in the morning speeds things up.

### Rank 2 — Sleep as Android. Zero-purchase option, fallback.

**Why as a fallback and not first.** It does write the **phases** into Health Connect ([official documentation](https://sleep.urbandroid.org/docs/services/health_connect.html)), which is confirmed. But the page states that the Health Connect service is still **in BETA**, and if you use it in sonar/microphone mode from the phone alone, the classification into stages has **no known published validation** — far weaker than the GW5.

**Where it is excellent, on the other hand:** it waits for no manufacturer Bluetooth sync. It writes on waking. If rank 1 fails **only** on latency, it is the natural replacement. It can also serve as a second source for cross-checking, provided deduplication is handled (§6) — and the kappa between the two sources would be an interesting quality datum in itself.

**Not verified:** its exact pricing model in 2026.

### Rank 3 — If you are willing to spend

In decreasing order of relevance for Pendulum:

**a) A Polar device (~150-250 € at entry level).** It is **the only manufacturer** whose support page literally writes "Sleep: start time, end time, **stages** from Polar sleep phases" ([support.polar.com](https://support.polar.com/en/flow-app-health-connect)). No subscription. If the decisive criterion is "I want documented certainty of having stages in Health Connect without paying a subscription", this is the one and it has no competitor.

**b) Withings Sleep Analyzer (~130 €), a mat under the mattress.** The only candidate that solves a real ergonomics problem: **nothing to wear, nothing to recharge, nothing to forget**. On mains power, Wi-Fi sync, so on the face of it the best latency profile in the whole list — a point that matters a great deal given the late sync trap. Two serious reservations: **stage** support in Health Connect is **not verified**, and users report incomplete sleep syncs. **Not to be bought before checking with Withings.**

**c) What should not be bought for this need.** Oura and Whoop: a mandatory subscription for strictly personal use, and for Oura the support page does not even explicitly list sleep among what goes to Health Connect. One would be paying a monthly subscription for a denominator that an already-owned watch provides for free.

### The switching criterion, to be applied literally

After **3 consecutive nights** with the rank 1 source:

```
SWITCH if, within the 36 h following each waking, Health Connect does NOT contain
a SleepSessionRecord which, all at once:
  (a) overlaps ≥ 50 % of the ankle watch's recording window                     ; AND
  (b) contains ≥ 2 distinct stage types other than STAGE_TYPE_UNKNOWN           ; AND
  (c) whose stages cover ≥ 80 % of the session duration
Failure on (a) alone    → LATENCY problem     → rank 2 (Sleep as Android).
Failure on (b) or (c)   → STAGES problem      → rank 3a (Polar).
```

### The guard rail that puts it all back in proportion

**Do not over-invest in this decision.** The SPEC architecture is already designed to survive the total absence of Health Connect: `deriveImmobilityMask()` produces an accelerometer mask that is usable immediately, `RescoreWorker` adds the second PLMI later, and each night produces **4 `plm_result` rows** (2 rule sets × 2 masks) precisely to make the gap visible rather than hide it. The sleep source is an **improvement in quality and interpretability**, not a prerequisite. If the check in section 5 fails, you lose the per-stage plausibility check — that is real, it is not blocking. Do not push back phase 1, which is the only real go/no-go of the project.

> **This paragraph has been settled against itself, and the ruling had never been brought back here.**
> [`SPEC-v2.md`](SPEC-v2.md) §2.3: "that is correct for **running** the app, false for **producing
> a figure comparable from one night to the next**". The two positions coexist that way, and this is
> the formulation to keep: the accelerometer mask is still computed and displayed as a second arm, but
> `maskSource = ACCEL_MACRO` **can never carry the main result nor feed the
> trend** — the constraint is applied in the data access layer, not in the interface.
> The reason is the circularity of the denominator: the treatment that reduces movements makes
> the numerator go down **and** the denominator go up in the same gesture, so much so that a null effect
> can show up as a clear improvement ([`ALGO-v2.md`](ALGO-v2.md) §3.6.3).
>
> **And a nuance that goes the other way, which arrived after this document.** The quantity tracked night
> after night is no longer the hourly count but the fundamental period in seconds, which is computed
> from the movement onset times alone and **needs no denominator at all**
> ([`SPEC-v2.md`](SPEC-v2.md) §5). Health Connect therefore becomes optional again *for the tracking*, and
> remains necessary *for the hourly count in the medical report*. The title of this document — "choosing
> the sleep source" — still describes a real decision, but no longer a blocking one.

---

## 5. Practical verification — to be done BEFORE writing any code

Goal: prove that your source writes **stages**, not just a duration. Two levels: the UI for a first verdict, the API for the verdict that is authoritative.

### Step 0 — prepare (evening of D-1)

1. Samsung Health → `Settings > Health Connect > App permissions` → enable **Sleep** for reading **and** writing.
2. Check that sleep tracking is active on the Galaxy Watch 5.
3. Wear the GW5 at night. Note the bedtime.

### Step 1 — on waking, force the sync

Open Samsung Health on the phone, then `Settings > Sync with Samsung account > Sync now`. **Note the exact time at which the night appears** in Samsung Health, then in Health Connect. That value directly calibrates the `SleepFetchWorker` backoff — it is a measurement, not a supposition.

### Step 2 — inspection in the Health Connect UI

Open Health Connect: `Android Settings` → search for "Health Connect". On Android 14+ it is built into the system (`Security and privacy > More privacy settings`); on Android 13 and earlier it is the separate app from the Play Store. Then, under `Permissions and data`, open `Browse health data` ([support.google.com/android/answer/12201872](https://support.google.com/android/answer/12201872)) → **Sleep** → today's date.

**What you must see in case of success:** a session with a start time, an end time, **and a breakdown by stage** with the durations (Light / Deep / Paradoxical or REM / Awake). The breakdown is the proof.

**What a failure looks like:**

| Symptom | Diagnosis |
|---|---|
| No "Sleep" entry at all | Permission not granted, or the sync has not arrived yet. Retry later before concluding. |
| An entry with just "7 h 12 min", no breakdown | **Duration only.** That is trap no. 1. |
| An entry is present, but the source app is not the expected one | Another app is writing too — a deduplication problem (§6). |
| A breakdown is present but only "Sleep" | A single `STAGE_TYPE_SLEEPING` = binary in disguise. Insufficient. |

Also check `Manage data > Data sources and priority`: the list of apps that write the Sleep type, in priority order. Remember that order, it is used in §6.

### Step 3 — the verification that is authoritative (API)

The UI can round off or hide things. Only a `readRecords` settles it. A throwaway probe to run before writing anything definitive:

```kotlin
val response = healthConnectClient.readRecords(
    ReadRecordsRequest(
        SleepSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(yesterday20h, today14h)
    )
)
for (r in response.records) {
    val types = r.stages.map { it.stage }.distinct()
    val coverage = r.stages.sumOf {
        Duration.between(it.startTime, it.endTime).toMinutes()
    }
    Log.i("Pendulum-HC", buildString {
        append("source=${r.metadata.dataOrigin.packageName} ")
        append("start=${r.startTime} end=${r.endTime} ")
        append("stageCount=${r.stages.size} distinctTypes=$types ")
        append("stageCoverage=${coverage}min ")
        append("sessionDuration=${Duration.between(r.startTime, r.endTime).toMinutes()}min")
    })
}
```

**How to read the output:**

| Output observed | Verdict |
|---|---|
| `stageCount` in the several tens, `distinctTypes` containing 4, 5, 6 (LIGHT, DEEP, REM) and 1 (AWAKE), coverage ≈ duration | `[v]` **A real hypnogram.** Green light. |
| `stageCount=0` or an empty list | `[x]` Duration only. |
| `distinctTypes=[2]` (only `STAGE_TYPE_SLEEPING`) | `[x]` Binary in disguise — usable for the TST, useless for per-stage plausibility. |
| `distinctTypes=[0]` (`STAGE_TYPE_UNKNOWN`) | `[x]` The source fills the field without populating it. |
| Several different `packageName`s for the same night | `[?]` Deduplication is mandatory before any computation — see §6. |
| `stageCoverage` far below `sessionDuration` | `[?]` A hypnogram with holes. Holes are allowed by the API; it is up to you to decide how to treat them in `mergeMasks()`. |

Repeat step 3 for **three nights** before settling it. One successful night may be a stroke of sync luck.

---

## 6. Technical integration — state of play 2026

### Library version

| | Version | Date |
|---|---|---|
| Stable | **`androidx.health.connect:connect-client:1.1.0`** | 8 October 2025 |
| Alpha | `1.2.0-alpha04` | 22 April 2026 |

Source: [developer.android.com/jetpack/androidx/releases/health-connect](https://developer.android.com/jetpack/androidx/releases/health-connect).

**The SPEC already pins `1.1.0` — that is the right version, nothing to change.** The "get started" guide suggests the alpha; do not follow it for this project, 1.2.0-alpha only brings "Matchmaking" APIs (`checkIfMatchmakingIsPossible()`, `createMatchmakingIntent()`) and Exercise enrichments unrelated to sleep.

### Recent API changes to know about

- **`SleepStageRecord` was REMOVED** in `1.1.0-alpha01`; the stages are now **nested inside `SleepSessionRecord`**. Any documentation or example that manipulates a separate `SleepStageRecord` is obsolete.
- **`isProviderAvailable()` and `isApiSupported()` are deprecated**, replaced by **`HealthConnectClient.getSdkStatus()`**.
- 1.1.0 additions: Personal Health Record (FHIR, experimental), Mindfulness Session, `SkinTemperatureRecord`. No impact here.
- **Background** read and **history** read permissions extended to Android 13 and earlier in `1.1.0-alpha11`.

### Permissions and manifest

```xml
<!-- Sleep read permission -->
<uses-permission android:name="android.permission.health.READ_SLEEP" />

<!-- ESSENTIAL if SleepFetchWorker reads outside the foreground (which is the case here) -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />

<!-- Only if you rescore nights older than 30 days -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY" />

<queries>
    <package android:name="com.google.android.apps.healthdata" />
</queries>
```

Exact strings confirmed on [the data types page](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types): `android.permission.health.READ_SLEEP` and `android.permission.health.WRITE_SLEEP`. Pendulum only needs the read.

**`READ_HEALTH_DATA_IN_BACKGROUND` is a gap in SPEC v1.** The SPEC only mentions `READ_SLEEP`, but the architecture rests on a `SleepFetchWorker` scheduled in WorkManager which, by construction, runs with the app closed. Without this permission, the background read fails. Check availability before scheduling the worker ([read-data doc](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)):

```kotlin
if (healthConnectClient.features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
) { /* schedule the worker */ }
```

**30-day history limit.** By default, an app only reads the 30 days preceding the granting of the permission. On Android 14+, no limit for the data the app wrote itself; a 30-day limit for other apps' data — so **the limit does apply to sleep coming from Samsung Health**. On Android 13 and earlier, 30 days for everything. Of no importance for a campaign of 5-7 nights, but **uninstalling/reinstalling the app resets the window to zero**: aggressive debugging can make you lose access to your own earlier nights. To go beyond: `HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY()`.

### The rationale activity — careful, TWO declarations are needed

The SPEC only mentions the pre-Android 14 form. With `phone minSdk 30`, **both paths must coexist**, otherwise Health Connect refuses to display the app on part of the installed base ([get-started](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started)).

```xml
<!-- Android 13 and earlier path (Health Connect APK) -->
<activity android:name=".PermissionsRationaleActivity" android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>

<!-- Android 14+ path (Health Connect built into the framework) -->
<activity-alias
    android:name="ViewPermissionUsageActivity"
    android:exported="true"
    android:targetActivity=".PermissionsRationaleActivity"
    android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
    <intent-filter>
        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
        <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
    </intent-filter>
</activity-alias>
```

Onboarding actions, if you add one: `androidx.health.ACTION_SHOW_ONBOARDING` (pre-14) and `android.health.connect.action.SHOW_ONBOARDING` (14+, with `android:permission="android.permission.health.START_ONBOARDING"`).

**A divergence with the SPEC worth flagging:** the SPEC mentions a `<meta-data health-permissions>`. The current "get started" documentation **asks for no meta-data** — permissions are declared as plain `<uses-permission>`. The meta-data belongs to an earlier approach. Leaving it in is most likely harmless, but it is no longer required. To be settled when the code is written.

### `SleepSessionRecord` — exact structure

Verified in the androidx source code ([SleepSessionRecord.kt](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/health/connect/connect-client/src/main/java/androidx/health/connect/client/records/SleepSessionRecord.kt)):

```kotlin
const val STAGE_TYPE_UNKNOWN      = 0
const val STAGE_TYPE_AWAKE        = 1
const val STAGE_TYPE_SLEEPING     = 2
const val STAGE_TYPE_OUT_OF_BED   = 3
const val STAGE_TYPE_LIGHT        = 4
const val STAGE_TYPE_DEEP         = 5
const val STAGE_TYPE_REM          = 6
const val STAGE_TYPE_AWAKE_IN_BED = 7

class Stage(
    val startTime: Instant,
    val endTime: Instant,
    @property:StageTypes val stage: Int
)   // requires startTime < endTime

class SleepSessionRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: Metadata,
    val title: String? = null,
    val notes: String? = null,
    val stages: List<Stage> = emptyList(),
) : IntervalRecord
```

The SPEC's eight constants are **exact**. Two documented constraints ([features/sleep-sessions](https://developer.android.com/health-and-fitness/health-connect/features/sleep-sessions)): the stages must be **sequential and non-overlapping**; **holes are allowed**. `mergeMasks()` must therefore handle a hypnogram with holes, not only full coverage.

**And a session longer than the recording, which is the ordinary case.** The two devices do not cover the same span: a `SleepSessionRecord` can begin before Pendulum's first sample and, far more often, carry on for hours after its last one — a watch whose battery died at 3 a.m. does not stop the phone that scores the sleep until morning. That overhang enters no numerator, since no movement can be detected where nothing was recorded, and admitting it into the denominator divides the index by up to two — in the reassuring direction, which is the one that ends a search instead of prompting a check. The windows read here are therefore clipped to the span actually recorded before anything is computed from them ([`ALGO-v2.md`](ALGO-v2.md) §3.6.4); a window straddling an edge is cut and kept, its recorded part being real sleep.

A useful note: `startZoneOffset` / `endZoneOffset` are provided by the source. They are your counter-measure to trap no. 10 of the SPEC (daylight saving time) — use them rather than recomputing a local offset.

### Deduplication by `dataOrigin` — the most counter-intuitive point

A fact verified on [the aggregation doc](https://developer.android.com/health-and-fitness/guides/health-connect/develop/aggregate-data):

- **`readRecords()` returns ALL the records from ALL the sources.** No deduplication. If Samsung Health and Sleep as Android both write the same night, you get **two overlapping sessions**, and if you naively concatenate their stages you get an incoherent hypnogram and a roughly doubled TST — hence a PLMI halved, without the slightest warning.
- **`aggregate()` does deduplicate, but only for Activity and Sleep**, and according to the app priority set by the user: "Only the Activity and Sleep data types are deduped by Health Connect, and the data totals shown are the values after the dedupe has been performed by the Aggregate API."
- But `aggregate()` can only return **`SLEEP_DURATION_TOTAL`**. **There is no aggregation that returns the stages.**

Hence a concrete two-stage strategy:

1. `aggregate(SleepSessionRecord.SLEEP_DURATION_TOTAL)` → a TST deduplicated by the system. It serves as a **cross-check** on your own computation. If your TST departs from it by more than ~10 %, your deduplication is wrong.
2. `readRecords()` + **your own deduplication** for the hypnogram. The SPEC already correctly plans a "preferred source as a setting": that is the right approach.

Golden rule: **choose one source and one only for a given night. Never merge the stages of two sources.** Suggested selection order: (1) the preferred source set by the user if it covers ≥ 50 % of the window; (2) otherwise the one with the most distinct `stages`; (3) on a tie, the longest coverage. Record the chosen `dataOrigin.packageName` in `sleep_window` — without that, an abnormal night is undebuggable. And it is **that** recorded origin, not the preference held in Settings, that the night list, the night detail and the report name as the night's sleep source. The preference says what should be read tonight; it says nothing about what was read for a night already scored, so labelling from it renamed the source of an entire campaign the day the user changed their mind, and could name an application that had never served.

On the user side, the priority is set in Health Connect (`Manage data`), but Google specifies that reading apps remain free to read everything and merge in their own way ([support.google.com/android/answer/13770384](https://support.google.com/android/answer/13770384)). **Do not count on the system priority to protect you**: that is Pendulum's job.

### The late sync trap — confirmed at the source

The SPEC anticipates it; the Samsung developer FAQ confirms it in the manufacturer's own terms: the watch → phone transfer is governed by "the watch's own policy due to battery considerations", **with no guaranteed delay**; whereas, once the data is on the phone, "Samsung Health inserts or updates its data to Health Connect as soon as data is created or changed".

Practical consequences:
- The bottleneck is **watch → phone**, not phone → Health Connect. Opening Samsung Health in the morning is the effective lever.
- The T+30 min / 1 h / 2 h / 4 h / 8 h backoff, giving up at T+36 h, is well sized — but **measure the real latency over 3 nights (§5, step 1) and re-calibrate it on your observed value** rather than keeping these figures.
- Watch out for trap no. 12 of the SPEC: a `SleepFetchWorker` under a `requiresCharging` constraint may never run. Combine it with `READ_HEALTH_DATA_IN_BACKGROUND`, `setExpedited`, and keep a manual trigger.
- Edge case: Samsung Health may **update** ("inserts or **updates**") a session already written. A night read at T+1 h may differ from the same night at T+8 h. `RescoreWorker` must be **idempotent** and overwrite cleanly, not pile up.

---

## 7. What I could not verify

An explicit list. Each line is a point where I extrapolate or where the source is indirect.

1. **That Samsung Health writes the *stages* (and not only the duration) to Health Connect.** Strongly suggested by the Samsung developer blog, never stated literally for the Samsung Health → Health Connect direction. **This is the most important point in the whole document** — hence section 5.
2. **The performance of the Galaxy Watch 5.** The validated study covers the **Galaxy Watch 3**. A reasonable transposition, not demonstrated.
3. **Quantified sync delays** for every source except Samsung (and even there, Samsung says "the watch's policy" without a figure).
4. **Stage support in Health Connect** for: Garmin, Oura, Whoop, Withings, Zepp/Amazfit, Google Health (formerly Fitbit). Confirmed only for **Polar**, **Sleep as Android**, and (strongly probable) **Samsung**.
5. **Oura:** the support page lists Activity / Body measurements / Vitals; **sleep does not appear there explicitly**. Either the list is incomplete, or Oura does not export sleep to Health Connect. Not settled.
6. **The Whoop support page** ([support.whoop.com/s/article/Google-Health-Integration-For-Android](https://support.whoop.com/s/article/Google-Health-Integration-For-Android)) and **the Withings support page** both returned HTTP 403. Content known only through search snippets.
7. **The official Garmin support page** ([faq JToBEy0jfe6pIygark2Ui5](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5)) did not render its content. The list of "15 data types" is second-hand.
8. **Sleep Cycle:** no confirmation of Health Connect support, in either direction.
9. **Nest Hub Sleep Sensing:** no path to Health Connect found, but absence of proof is not proof of absence. The commercial status of the Nest Hub 2 in 2026 is not verified.
10. **The dates of the migration of Fitbit accounts to Google** (switch-over, deletion of non-migrated data): taken from a search engine with redirect URLs that could not be resolved. **Not verified.**
11. **The pricing model of Sleep as Android in 2026.**
12. **The alignment of the BMI270 consumption table**: reconstructed from a text extraction of the PDF. The 685 µA value is corroborated by the product page; the other five are not.
13. **The prices given** in the §3 table are orders of magnitude from memory, not verified in 2026.

---

## Check this yourself

- **Do section 5 before anything else.** It is one evening of work and it decides the whole cross-check. The SPEC already notes it in the last line of its own "to check" list — it is the same point, developed.
- **Do not let this document delay phase 1.** The real go/no-go of the project is the battery life and the off-body behaviour of the Pixel Watch 3 on the ankle, not the choice of sleep source. If phase 1 fails, the question of the denominator no longer arises.
- **Add `READ_HEALTH_DATA_IN_BACKGROUND` to the SPEC.** It is a real gap, not a detail: without it, `SleepFetchWorker` reads nothing with the app closed.
- **Measure the real latency of your GW5 → Samsung Health → Health Connect chain over 3 nights** and re-calibrate the backoff on that value, instead of keeping the SPEC's figures.
- **Settle point 1 of section 7 through the API, not through the UI.** The Health Connect screen may show a breakdown while `stages` is not usable, and the other way round.
- **Decide explicitly what to do with hypnogram holes** (the API allows them). Counting a hole as sleep or as wake changes the denominator, hence the PLMI, hence potentially the screening decision. That choice deserves to be a parameter traced in `paramsJson`, not a hard-coded value.
