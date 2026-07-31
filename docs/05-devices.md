# Choosing the hardware

> **Pendulum** measures periodic limb movements in sleep from a smartwatch worn at the ankle, and
> tracks the *rhythm* between them rather than their number. It is a screening aid: a real
> measurement to take to a physician, not a diagnosis and not a medical device — see
> [what this is, and what it is not](../README.md#what-this-is-and-what-it-is-not).

Pendulum counts leg movements with a Wear OS watch at the ankle. To turn that count into an index it
needs a denominator — total sleep time — and that denominator has to come from a **different**
device, read through **Health Connect** on the Android phone.

This document says which device to use for that second role, why the choice matters much less than
it appears to, and — most importantly — **how to verify a candidate source before writing a single
line of integration code**.

The comparison reflects the state of things in mid-2026 and is translated from the project's French
design notes, keeping their verification marks intact. Several rows are marked unverified. That is
not hedging: it means the claim was never confirmed against a primary source, and §7 lists every
one of them.

---

## 1. Why two devices are structurally necessary

The index is a fraction:

```
index = (number of periodic limb movements) / (hours of SLEEP)
```

The ankle watch produces the **numerator** very well. It cannot honestly produce the
**denominator**, and the reason is deeper than a lack of precision.

**a) Circularity.** Classical actigraphy infers "asleep or awake" from a movement statistic: much
movement means wake, little movement means sleep. But the event Pendulum is counting *is* movement. Use
the same signal for numerator and denominator and every burst of periodic movements pushes the
algorithm towards scoring "awake". Sleep time then shrinks **exactly during the periods where there
are the most movements to count**: the numerator rises, the denominator falls, and the error in the
ratio is doubled in the same direction. That is a systematic bias, not noise — it does not average
out over nights.

**b) Consumer sleep algorithms are calibrated for the wrist.** Cole-Kripke-type rules, and the
models inside consumer watches, were fitted to *wrist* movement statistics. A sleeper's ankle
behaves differently: more often perfectly still, then producing far stronger accelerations. A
threshold calibrated at the wrist and applied at the ankle produces a number without meaning.

**c) Stages cannot be read from movement alone.** Consumer watches classify light, deep and REM by
combining movement **with** heart rate and heart-rate variability measured by PPG. PPG at the ankle
is unreliable — the same reason the project treats worn/not-worn detection at the ankle as an
unvalidated risk. Without a usable cardiac signal there are no stages.

**d) The ankle watch is already busy.** It records the raw accelerometer at 50 Hz all night through
`SensorManager`, on a tight battery budget. Asking it to also run a staging model is neither
possible (Health Services does not expose the raw accelerometer) nor desirable.

Pendulum does keep a fallback immobility mask computed from the ankle accelerometer. **That is a safety
net, not the primary source.** It exists so a night stays analysable immediately when Health Connect
returns nothing — which is precisely why the index is computed twice, once against each mask, and
both are reported. The gap between them is itself information.

## 2. What sleep stages actually buy you

Less than people expect.

**What they do not buy: anything about the index itself.** The clinical definition is a count
divided by hours of sleep, with no weighting by stage. A **binary** asleep/awake mask is enough to
compute it correctly. Going from binary to four stages does not make the index one iota more
accurate.

**What they do buy, which is far from nothing:**

1. **Separating movements in sleep from movements in wake.** Movements during wake do not count
   towards the index and must be reported separately — a high count of them is itself a clinical
   clue. But a binary mask already does this.
2. **The biological plausibility check.** This is the main gain. Periodic limb movements should
   concentrate in light sleep and thin out in REM, where muscle atonia applies. If your
   distribution of candidate movements across stages is flat, or worse, dense in REM, your detector
   is probably catching something else. **Without stages you have no way to detect that error.** It
   is a regression test against the real world, where synthetic data can say nothing.
3. **Kappa between the accelerometric mask and the hypnogram.** It tells you how far to trust the
   safety net, and therefore how far to trust the nights where Health Connect returned nothing.
4. **Readability by a sleep physician.** A report showing a hypnogram with movements overlaid can be
   discussed. A report showing "index = 22" cannot.

**And here is the good news, which is solid.** What Pendulum *needs* — total sleep time, i.e. the
sleep/wake boundary — is precisely what consumer watches do **best**. What they do **badly** —
stages — is what Pendulum needs **least**.

Galaxy Watch 3 validated against polysomnography, N = 32 (Kim D, Joo EY, Choi SJ, *J Sleep Med*
2023;20(1):28–34, [doi:10.13078/jsm.230004](https://doi.org/10.13078/jsm.230004)):

| Measure | Result |
|---|---|
| Sleep detection sensitivity | **0.954** |
| Specificity (wake detection) | 0.524 |
| Four-stage classification accuracy | 0.651 |
| Cohen's kappa: wake / light / deep / REM | 0.47 / 0.34 / 0.34 / 0.39 |
| Total sleep time bias (watch − PSG) | **+9.5 min** |
| Light / deep / REM bias | −28.6 / +11.6 / +26.3 min |
| REM intraclass correlation | 0.153 (very poor) |

Total sleep time is excellent — a +9.5 min bias on a seven-hour night is about 2 %. Stages are
mediocre: kappa 0.34–0.47 is fair-to-moderate agreement. This is consistent with Chinoy et al.,
*Sleep* 2021;44(5):zsaa291, which concludes across seven consumer devices that sleep detection is
good but "device sleep stage assessments were inconsistent".

**The bias you must know about.** A specificity of 0.524 means the watch calls nearly one wake
epoch in two "asleep". It therefore **overestimates** sleep time. Since sleep time is the
denominator, the index computed from this source is structurally **underestimated**. That runs
opposite to the overestimation caused by respiratory-related leg movements in the presence of sleep
apnoea — and, as with the unilateral-sensor bias, **do not claim the two cancel**. Name them
separately in the report.

Caveat: this study is on the **Galaxy Watch 3**, not the 5. Extrapolating to the GW5 is reasonable
(same family of Samsung algorithms) but remains an unverified extrapolation.

---

## 3. The distinction that actually decides a purchase

Not "does it have a gyroscope". Not "how good is the sensor". This one:

**Raw sensor accessible to a third-party application.** An application calls
`SensorManager.getDefaultSensor(TYPE_ACCELEROMETER)` and receives `(x, y, z, timestamp)` samples at
the requested rate. It sees the physical signal. It writes its own algorithm. The result is
reproducible, debuggable, and testable against synthetic data. That is what the `wear` module of
Pendulum does.

**Aggregated vendor metrics.** The manufacturer applies **its** proprietary processing and publishes
only the result: activity counts, sleep score, restlessness, active minutes. The parameters are
undocumented, change with firmware updates, and are not reproducible.

There is a published proof by failure: attempts with the ActiGraph GT3X report very low similarity
with polysomnography — **not** because the sensor is poor, but because only proprietary activity
counts, aggregated at too low a rate, were available. The sensor was good; the access was bad.

**And here is the reversal, which is the whole point of this document: the criterion inverts
depending on the device's role.**

| | **Ankle** watch (numerator) | **Sleep** source (denominator) |
|---|---|---|
| What is needed | **Raw** accelerometer, 50 Hz, 8 h | An **aggregated hypnogram** |
| So you need | A platform with an open sensor API → **Wear OS** (or a research logger such as an Axivity AX3) | Any ecosystem **that exports to Health Connect** |
| Disqualifying | A ring, a closed band, anything that returns only scores | A device that does **not write** to Health Connect, or writes only a duration |
| Sensor quality | Critical | **Almost irrelevant** — see §2, total sleep time is reliable everywhere |

In other words: for the ankle, an Oura Ring with an excellent sensor is **unusable** because it is
closed. For sleep, a device with a mediocre sensor is **perfectly acceptable** as long as it pushes
a `SleepSessionRecord` into Health Connect. The only criterion that counts for the second role is
plumbing, not sensing.

---

## 4. Hypnogram sources compared

Legend: ✅ verified against a primary source · ⚠️ probable but **not verified** · ❌ no / excluded

| Source | Writes to Health Connect? | Stages or duration only? | Sync latency | Price | Subscription required? | Battery life | Published validation |
|---|---|---|---|---|---|---|---|
| **Samsung Health / Galaxy Watch 5** | ✅ Yes, since Samsung Health 6.22.5 (Oct 2022) | ⚠️ **Stages** (Awake / Light / REM / Deep) — see note | Watch → phone: governed by the **watch's battery policy** (unspecified). Phone → HC: "as soon as data is created or changed" | Already owned | No | ~2–3 d (GW5) | ✅ GW3: TST +9.5 min, 4-stage accuracy 0.651, kappa 0.34–0.47 |
| **Sleep as Android** (phone app, optionally with a watch) | ✅ Yes | ✅ **Sleep phases** + resting HR + SpO2 | Written at the end of tracking, on waking (no vendor Bluetooth sync to wait for when tracked by the phone) | Paid after a trial (⚠️ pricing not verified) | ⚠️ not verified | n/a (phone on mains) | ❌ The sonar/microphone-only mode has no known staging validation |
| **Pixel Watch + Google Health** (formerly Fitbit) | ✅ "third-party connections through Android Health Connect" | ⚠️ Stages **not confirmed** on an official page | ⚠️ not verified | Watch already owned — **but it is on the ankle** | No for basic sleep; Google Health Premium (ex-Fitbit Premium) for advanced analysis | ~24 h (PW3) | Not found |
| **Garmin (Garmin Connect)** | ✅ Yes, since ~July 2025 · **one-way** (Garmin writes, does not read) | ⚠️ Sleep yes; **stages not confirmed** | ⚠️ not verified | €200–1000 | No (Connect+ optional) | 5–20 d | Not found |
| **Oura Ring** | ✅ Yes, Android **only**, Gen2/Gen3+ | ⚠️ The support page lists Activity / Body measurements / Vitals — **sleep and stages do not appear explicitly** | ⚠️ Documented trap: "might not import data if both apps were not opened before midnight"; Background App Refresh required | €350+ | ✅ **Yes, active membership required** | ~7 d | Not found |
| **Whoop** | ✅ Yes (Recovery, Strain, Sleep) | ⚠️ Stages not confirmed | ⚠️ not verified | Hardware included in the subscription | ✅ **Yes, subscription-only model** | ~4–5 d | Not found |
| **Polar (Polar Flow)** | ✅ Yes | ✅ **"Sleep: start time, end time, stages from Polar sleep phases"** — the only manufacturer to document it in as many words | ⚠️ "continuously while the connection remains active" | €150–500 | **No** | 3–7 d | Not found |
| **Withings (Health Mate)** — ScanWatch, Sleep Analyzer under-mattress mat | ✅ Yes (a support article exists) | ⚠️ Stages not confirmed; user reports of **incomplete** sleep sync | ⚠️ Sleep Analyzer: mains-powered, Wi-Fi sync → potentially the fastest of the list | ScanWatch ~€250–350 · Sleep Analyzer ~€130 | No | ScanWatch ~30 d · Sleep Analyzer: **mains, unlimited** | Not found |
| **Xiaomi / Amazfit — Zepp** | ✅ Yes, widened in Jan 2025 to **26 data types**, **one-way** | ⚠️ "sleep stats"; stages not confirmed | ⚠️ not verified | €40–300 | No | 7–20 d | Not found |
| **Apple Watch** | ❌ **Excluded** — see note | — | — | — | — | — | — |
| **Sleep Cycle** (phone app only) | ⚠️ **Not confirmed** | Detects stages internally, but Health Connect export not verified | On waking | Freemium | ⚠️ not verified | n/a | Not found |
| **Google Nest Hub 2 — Sleep Sensing** (Soli radar) | ❌ **No path to Health Connect found** | — | — | ~€100 (⚠️ product status uncertain) | Free to date (the shift to Premium has been postponed repeatedly) | Mains | Not found |

Prices are orders of magnitude recalled from memory and were **not verified** in 2026.

### Notes and traps per source

**Samsung Health — the nuance about stages.** The official developer FAQ
([developer.samsung.com/health/health-connect-faq.html](https://developer.samsung.com/health/health-connect-faq.html))
says that "Activity data, such as steps and exercise, heart rate, and sleep, are synchronized between
Samsung Health and Health Connect" — it says *sleep*, without specifying *stages*. The Samsung
developer blog ([Managing Sleep Data with Samsung Health and Health Connect](https://developer.samsung.com/health/blog/en/managing-sleep-data-with-samsung-health-and-health-connect))
works explicitly with the four stages Awake, Light, REM and Deep, and describes synchronising
sessions **and stages** in both directions. Read together this points strongly towards stages, but
**no page states it literally for the Samsung Health → Health Connect direction.** That is exactly
why §6 exists.

**Samsung Health — it is not automatic.** In Samsung Health: `Settings > Health Connect > App
permissions`, then `Settings > Sync with Samsung account > Sync now`.

**Apple Watch — why it is excluded, and it is not a value judgement.** HealthKit is an iOS API.
There is no Apple Watch app for Android, pairing requires an iPhone, and there is no official
HealthKit → Health Connect bridge. Even if the data were perfect it would never reach the Android
phone. Out of scope by construction.

**Pixel Watch — the inventory trap.** A Pixel Watch 3 probably writes good sleep data to Health
Connect through Google Health. But in Pendulum **it is on the ankle**. It cannot measure sleep at the
wrist and count movements at the ankle on the same night. It is listed for completeness, not as a
real option — unless you buy a second one.

**The Fitbit → Google Health change, 2026.** Since **19 May 2026** the Fitbit app has become the
**Google Health** app
([support.google.com/googlehealth/answer/17068213](https://support.google.com/googlehealth/answer/17068213)):
four tabs — Today / Fitness / Sleep / Health — Fitbit Premium becoming Google Health Premium with a
three-month trial. The page confirms third-party connections "through Android Health Connect, Apple
Health, and other third-party apps". A migration of Fitbit accounts to Google accounts accompanied
the change; the cut-over dates and the deletion date for unmigrated data are **not verified**.

### The three traps to look for in any source

1. **Duration only.** The app writes a `SleepSessionRecord` with an empty `stages` list, or a single
   `STAGE_TYPE_SLEEPING` spanning the night. Technically conformant, useless for the plausibility
   check. Only reading through the API reveals it (§6).
2. **Stages behind a subscription.** The historical Fitbit Premium model. Check the state of your
   subscription **before** concluding that a source "does not work".
3. **A source needing a manual sync every morning.** Explicitly documented for Oura: both apps must
   have been opened, Background App Refresh must be on. A source that requires a user gesture each
   morning breaks the requirement that the cold path run without manual intervention.

---

## 5. Ranked recommendation

### Rank 1 — Galaxy Watch 5 + Samsung Health. The zero-purchase option.

Already owned. Writes to Health Connect since 2022 over a well-worn chain. Stages near-certain. It
is the only option whose staging accuracy is **quantified in the literature** (on the GW3). No
expense, no extra app, one box to tick.

**The night protocol.** Pixel Watch 3 at the ankle, Galaxy Watch 5 at the wrist, the same night.
Two watches, yes. That is the constraint inherent to the project and no choice of source removes it.

**The known risk** is the late-sync trap: the watch → phone transfer is governed by the **watch's
battery policy**, not by your waking, and Samsung's own FAQ confirms it in those terms. Once on the
phone, the write to Health Connect is immediate. The mitigation — a fetch worker at T+30 min then
backing off 1/2/4/8 h, giving up at T+36 h — is correctly sized. Opening Samsung Health in the
morning speeds things up.

### Rank 2 — Sleep as Android. The zero-purchase fallback.

**Why fallback and not first.** It does write **phases** to Health Connect
([official documentation](https://sleep.urbandroid.org/docs/services/health_connect.html)), which is
confirmed. But that page states the Health Connect service is still in **beta**, and if used in
sonar/microphone mode from the phone alone, the stage classification has **no known published
validation** — much weaker than the GW5.

**Where it is excellent:** it waits for no vendor Bluetooth sync. It writes on waking. If rank 1
fails **only** on latency, this is the natural replacement. It can also serve as a second source for
cross-checking, provided deduplication is handled (§7) — and the kappa between the two sources would
be interesting quality data in its own right.

**Not verified:** its exact 2026 pricing model.

### Rank 3 — If you are willing to spend

**a) A Polar device (~€150–250 entry level).** The **only** manufacturer whose support page states
literally "Sleep: start time, end time, **stages** from Polar sleep phases"
([support.polar.com](https://support.polar.com/en/flow-app-health-connect)). No subscription. If your
decisive criterion is "documented certainty of stages in Health Connect without paying a
subscription", this is it, with no competitor.

**b) Withings Sleep Analyzer (~€130), an under-mattress mat.** The only candidate that solves a real
ergonomic problem: **nothing to wear, nothing to charge, nothing to forget**. Mains-powered, Wi-Fi
sync, so on the face of it the best latency profile in the list — which counts for a lot given the
late-sync trap. Two serious reservations: **stage** support in Health Connect is **not verified**,
and users report incomplete sleep syncs. **Do not buy before checking with Withings.**

**c) What not to buy for this need.** Oura and Whoop: a mandatory subscription for a strictly
personal use, and in Oura's case the support page does not even list sleep among what goes to Health
Connect. You would be paying monthly for a denominator that an already-owned watch supplies for
free.

### The switching criterion, to be applied literally

After **3 consecutive nights** with the rank 1 source:

```
SWITCH if, within 36 h of each waking, Health Connect does NOT contain
a SleepSessionRecord that simultaneously:
  (a) overlaps ≥ 50 % of the ankle watch's recording window          ; AND
  (b) contains ≥ 2 distinct stage types other than STAGE_TYPE_UNKNOWN ; AND
  (c) whose stages cover ≥ 80 % of the session duration

Failure on (a) alone     → a LATENCY problem → rank 2 (Sleep as Android).
Failure on (b) or (c)    → a STAGES problem  → rank 3a (Polar).
```

### The guard rail that puts this back in proportion

**Do not over-invest in this decision.** The architecture is designed to survive the total absence of
Health Connect: the accelerometric immobility mask gives a usable mask immediately, a rescore worker
adds the second index later, and every night produces **four result rows** (two rule sets × two
masks) precisely so that the discrepancy is visible rather than hidden. The sleep source is an
improvement in quality and interpretability, **not a prerequisite**. If the verification in §6 fails
you lose the per-stage plausibility check — that is real, and it is not blocking. Do not let it delay
the capture-feasibility phase, which is the project's only genuine go/no-go.

---

## 6. Verification procedure — run this before writing any integration code

The objective: prove that your source writes **stages**, not merely a duration. Two levels — the
user interface for a first verdict, the API for the verdict that counts.

### Step 0 — prepare (the evening before)

1. Samsung Health → `Settings > Health Connect > App permissions` → enable **Sleep** for both read
   and write.
2. Confirm sleep tracking is active on the Galaxy Watch 5.
3. Wear the GW5 overnight. Note the time you went to bed.

### Step 1 — on waking, force the sync

Open Samsung Health on the phone, then `Settings > Sync with Samsung account > Sync now`. **Note the
exact time at which the night appears** in Samsung Health, and then in Health Connect. That value
calibrates the fetch worker's backoff directly — it is a measurement, not a guess.

### Step 2 — inspect in the Health Connect interface

Open Health Connect: `Android Settings` → search for "Health Connect". On Android 14+ it is built
into the system (`Security & privacy > More privacy settings`); on Android 13 and earlier it is the
separate Play Store app. Then, under `Permissions and data`, open `Browse health records` → **Sleep**
→ today's date.

**What success looks like:** a session with a start time, an end time, **and a breakdown by stage**
with durations (Light / Deep / REM / Awake). The breakdown is the proof.

**What failure looks like:**

| Symptom | Diagnosis |
|---|---|
| No "Sleep" entry at all | Permission not granted, or the sync has not arrived yet. Retry later before concluding. |
| An entry showing just "7 h 12 min", with no breakdown | **Duration only.** This is trap 1. |
| An entry present, but the source app is not the expected one | Another app is writing too — a deduplication problem (§7). |
| A breakdown present, but only "Sleeping" | A single `STAGE_TYPE_SLEEPING` is binary in disguise. Insufficient. |

Also check `Manage data > Data sources and priority`: the list of apps writing the Sleep type, in
priority order. Remember that order; it matters in §7.

### Step 3 — the verdict that counts (API)

The interface may round or hide. Only a `readRecords` settles it. A throwaway probe to run before
writing anything permanent:

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
        append("nStages=${r.stages.size} distinctTypes=$types ")
        append("stageCoverage=${coverage}min ")
        append("sessionDuration=${Duration.between(r.startTime, r.endTime).toMinutes()}min")
    })
}
```

**How to read the output:**

| Observed | Verdict |
|---|---|
| `nStages` in the dozens, `distinctTypes` containing 4, 5, 6 (LIGHT, DEEP, REM) and 1 (AWAKE), coverage ≈ duration | ✅ **A genuine hypnogram.** Green light. |
| `nStages=0`, or an empty list | ❌ Duration only. |
| `distinctTypes=[2]` (only `STAGE_TYPE_SLEEPING`) | ❌ Binary in disguise — usable for total sleep time, useless for per-stage plausibility. |
| `distinctTypes=[0]` (`STAGE_TYPE_UNKNOWN`) | ❌ The source fills the field without populating it. |
| Several different `packageName`s for the same night | ⚠️ Deduplication is mandatory before any computation — see §7. |
| `stageCoverage` far below `sessionDuration` | ⚠️ A hypnogram with holes. The API permits holes; you must decide how to treat them. |

**Expected latency after waking:** unknown, and that is the point of step 1. The bottleneck is watch
→ phone, governed by the watch's battery policy with no guaranteed delay; phone → Health Connect is
immediate. The starting assumption is a backoff at T+30 min then 1/2/4/8 h with abandonment at
T+36 h — but **measure the real latency over three nights and re-tune the backoff to your observed
value** rather than keeping those figures.

Repeat step 3 for **three nights** before deciding. One successful night can be a lucky sync.

---

## 7. Health Connect integration notes

### Library version

| | Version | Date |
|---|---|---|
| Stable | **`androidx.health.connect:connect-client:1.1.0`** | 8 October 2025 |
| Alpha | `1.2.0-alpha04` | 22 April 2026 |

Source: [developer.android.com/jetpack/androidx/releases/health-connect](https://developer.android.com/jetpack/androidx/releases/health-connect).

Use `1.1.0`. The "get started" guide suggests the alpha; do not follow it here — `1.2.0-alpha` adds
only Matchmaking APIs and Exercise enrichments, nothing sleep-related.

### Recent API changes worth knowing

- **`SleepStageRecord` was REMOVED** in `1.1.0-alpha01`; stages are now **nested inside
  `SleepSessionRecord`**. Any documentation or sample manipulating a standalone `SleepStageRecord` is
  obsolete.
- **`isProviderAvailable()` and `isApiSupported()` are deprecated**, replaced by
  **`HealthConnectClient.getSdkStatus()`**.
- Background-read and history-read permissions were extended to Android 13 and earlier in
  `1.1.0-alpha11`.

### Permissions actually required

```xml
<!-- Read sleep -->
<uses-permission android:name="android.permission.health.READ_SLEEP" />

<!-- ESSENTIAL if the fetch worker reads outside the foreground — which it does -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />

<!-- Only if you rescore nights older than 30 days -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY" />

<queries>
    <package android:name="com.google.android.apps.healthdata" />
</queries>
```

The exact strings are confirmed on the
[data types page](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types).
Pendulum needs read access only.

**`READ_HEALTH_DATA_IN_BACKGROUND` is easy to miss and not optional.** The architecture relies on a
WorkManager-scheduled fetch worker which by construction runs with the app closed. Without this
permission the background read simply fails. Check availability before scheduling the worker
([read-data documentation](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)):

```kotlin
if (healthConnectClient.features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
) { /* schedule the worker */ }
```

**The 30-day history limit.** By default an app reads only the 30 days preceding the grant of
permission. On Android 14+ there is no limit for data the app wrote itself, and a 30-day limit for
data written by others — so the limit **does** apply to sleep coming from Samsung Health. On
Android 13 and earlier it is 30 days for everything. Irrelevant for a five- to seven-night campaign,
but note that **uninstalling and reinstalling resets the window**: aggressive debugging can cost you
access to your own earlier nights. To go further:
`HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY()`.

### The rationale activity — two declarations are required

With `minSdk 30` on the phone, **both paths must coexist**, or Health Connect refuses to show the app
on part of the fleet
([get-started](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started)).

```xml
<!-- Android 13 and earlier (Health Connect as an APK) -->
<activity android:name=".PermissionsRationaleActivity" android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>

<!-- Android 14+ (Health Connect in the framework) -->
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

Onboarding actions, if one is added: `androidx.health.ACTION_SHOW_ONBOARDING` (pre-14) and
`android.health.connect.action.SHOW_ONBOARDING` (14+, with
`android:permission="android.permission.health.START_ONBOARDING"`).

Note that the current "get started" documentation requires **no `<meta-data health-permissions>`
element** — permissions are declared as plain `<uses-permission>`. The meta-data belongs to an
earlier approach; leaving it in is probably harmless but it is no longer required.

### The record type

Verified in the androidx source
([SleepSessionRecord.kt](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/health/connect/connect-client/src/main/java/androidx/health/connect/client/records/SleepSessionRecord.kt)):

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

Two documented constraints
([features/sleep-sessions](https://developer.android.com/health-and-fitness/health-connect/features/sleep-sessions)):
stages must be **sequential and non-overlapping**, and **holes are permitted**. The mask-merging code
must therefore handle a hypnogram with holes, not only full coverage. Counting a hole as sleep or as
wake changes the denominator, therefore the index, therefore potentially the screening decision —
so make it an explicit, recorded parameter, not a hard-coded value.

`startZoneOffset` and `endZoneOffset` are supplied by the source. Use them rather than recomputing a
local offset; they are the defence against the daylight-saving trap.

### Deduplication by data origin — the least intuitive point

Verified on the
[aggregation documentation](https://developer.android.com/health-and-fitness/guides/health-connect/develop/aggregate-data):

- **`readRecords()` returns ALL records from ALL sources. No deduplication.** If Samsung Health and
  Sleep as Android both write the same night, you get **two overlapping sessions**, and naively
  concatenating their stages yields an incoherent hypnogram and a roughly doubled total sleep time —
  hence an index halved, with no warning whatsoever.
- **`aggregate()` does deduplicate, but only for Activity and Sleep**, according to the user's app
  priority setting: "Only the Activity and Sleep data types are deduped by Health Connect, and the
  data totals shown are the values after the dedupe has been performed by the Aggregate API."
- But `aggregate()` can only return **`SLEEP_DURATION_TOTAL`**. **There is no aggregation that
  returns stages.**

Hence a two-step strategy:

1. `aggregate(SleepSessionRecord.SLEEP_DURATION_TOTAL)` → a system-deduplicated total sleep time.
   Use it as a **cross-check** on your own computation. If yours departs from it by more than about
   10 %, your deduplication is wrong.
2. `readRecords()` + **your own deduplication** for the hypnogram.

Golden rule: **pick one source, and only one, for a given night. Never merge stages from two
sources.** Suggested selection order: (1) the user's preferred source if it covers ≥ 50 % of the
window; (2) otherwise the one with the most distinct stages; (3) on a tie, the longest coverage.
Record the chosen `dataOrigin.packageName` alongside the sleep window — without it, an anomalous
night is undebuggable.

Users can set priority in Health Connect (`Manage data`), but Google notes that reading apps remain
free to read everything and merge as they see fit
([support.google.com/android/answer/13770384](https://support.google.com/android/answer/13770384)).
**Do not rely on system priority to protect you** — that is Pendulum's job.

### The late-sync trap, confirmed at source

Samsung's developer FAQ confirms it in the manufacturer's own words: the watch → phone transfer is
governed by "the watch's own policy due to battery considerations", **with no guaranteed delay**;
whereas once the data is on the phone, "Samsung Health inserts or updates its data to Health Connect
as soon as data is created or changed".

Practical consequences:

- The bottleneck is **watch → phone**, not phone → Health Connect. Opening Samsung Health in the
  morning is the effective lever.
- The backoff at T+30 min / 1 h / 2 h / 4 h / 8 h with abandonment at T+36 h is well sized — but
  measure your real latency over three nights (§6, step 1) and re-tune it.
- A fetch worker constrained by `requiresCharging` may never run at all. Combine it with
  `READ_HEALTH_DATA_IN_BACKGROUND` and `setExpedited`, and keep a manual trigger.
- Edge case: Samsung Health may **update** ("inserts or **updates**") a session it already wrote. A
  night read at T+1 h may differ from the same night at T+8 h. Rescoring must therefore be
  **idempotent** and overwrite cleanly, not accumulate.

---

## 8. What was not verified

Every line here is a point where the source is indirect or the conclusion extrapolated.

1. **That Samsung Health writes *stages* (and not merely a duration) to Health Connect.** Strongly
   suggested by the Samsung developer blog, never stated literally for that direction. **This is the
   single most important item in the document** — hence §6.
2. **Galaxy Watch 5 performance.** The validated study is on the **Galaxy Watch 3**. A reasonable
   transposition, not a demonstrated one.
3. **Quantified sync latencies** for every source except Samsung — and even Samsung says "the
   watch's policy" without a figure.
4. **Stage support in Health Connect** for: Garmin, Oura, Whoop, Withings, Zepp/Amazfit, Google
   Health (ex-Fitbit). Confirmed only for **Polar**, **Sleep as Android**, and (strongly probable)
   **Samsung**.
5. **Oura:** the support page lists Activity / Body measurements / Vitals; **sleep does not appear
   explicitly**. Either the list is incomplete or Oura does not export sleep to Health Connect.
   Unresolved.
6. **The Whoop support page**
   ([support.whoop.com](https://support.whoop.com/s/article/Google-Health-Integration-For-Android))
   and **the Withings support page** both returned HTTP 403. Their content is known only through
   search-result extracts.
7. **The official Garmin support page**
   ([faq JToBEy0jfe6pIygark2Ui5](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5)) did
   not render. The list of "15 data types" is second-hand.
8. **Sleep Cycle:** no confirmation of Health Connect support in either direction.
9. **Nest Hub Sleep Sensing:** no path to Health Connect found, but absence of evidence is not
   evidence of absence. The commercial status of the Nest Hub 2 in 2026 is unverified.
10. **The dates of the Fitbit → Google account migration** (cut-over, deletion of unmigrated data):
    obtained from a search engine with unresolvable redirect URLs. **Not verified.**
11. **Sleep as Android's 2026 pricing model.**
12. **The prices** in §4 are orders of magnitude from memory, not verified in 2026.

### What to check for yourself

- **Do §6 before anything else.** It is an evening's work and it decides the whole cross-referencing
  design.
- **Do not let this document delay the capture-feasibility phase.** The project's real go/no-go is
  battery life and off-body behaviour of the ankle watch, not the choice of sleep source. If that
  phase fails, the question of the denominator does not arise.
- **Settle item 1 by the API, not by the interface.** The Health Connect screen can display a
  breakdown without `stages` being usable, and the reverse.
- **Measure the real latency of your own chain over three nights** and re-tune the backoff to it,
  instead of keeping the figures written here.
- **Decide explicitly what to do with hypnogram holes.** The API permits them, and the choice moves
  the index.
