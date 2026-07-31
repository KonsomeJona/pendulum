# Pendulum — restless legs, measured at the ankle

**[Project site](https://konsomejona.github.io/pendulum/)** · **[Download a release](https://github.com/KonsomeJona/pendulum/releases/latest)** · **[Documentation](docs/)**

**Pendulum tracks leg movements during sleep, using a smartwatch worn at the ankle, and measures the
rhythm between them.** If your legs jerk or twitch at night — the thing a bed partner notices and you
sleep through — this records how often it happens and how regular it is. Those movements are the
motor sign that sleep physicians look for in **restless legs syndrome** (RLS) and **periodic limb
movement disorder** (PLMD), and most people who have them never know, because they happen during
sleep.

It **measures**; it does not interpret. It can show you that your legs move in a regular rhythm at
night, and it cannot tell you what that means. That distinction is the whole design. A sleep physician diagnoses restless legs
syndrome from your waking symptoms, using five clinical criteria; the movements Pendulum measures are
a supporting criterion, not the diagnosis. What this application can honestly give you is a real
measurement, taken over several nights, that you can put in front of a doctor instead of a
description from memory.

The sleep period itself comes from a **second, independent** device through Health Connect, because
one sensor cannot honestly measure both the movements and the sleep they happen in.

### Why that name

A pendulum's period does not depend on how far it swings. Huygens proved it in 1656, and it is why
pendulums became clocks: the *amplitude* decays, the *period* holds.

The same split runs through this project. The conventional measure of these movements is a count per
hour of sleep, and it is unstable — 43 % variation from one night to the next. The interval *between*
movements varies by 3.6 %. Twelve times less, and with no denominator to argue about, since an
interval is computed from the movement times alone. So Pendulum tracks the period and treats the
count as something to hand a physician, not something to follow.

> ### What this is, and what it is not
>
> **It is** a real measurement. The accelerometer readings are real, the processing chain follows
> published scoring rules, and the numbers mean something. Used over several nights, it can give you
> a well-founded reason to book an appointment — or a well-founded reason not to worry.
>
> **It is not** a medical device, an official health application, or a diagnosis. It has not been
> reviewed or approved by any health authority, it is not affiliated with any medical body, and it
> has never been validated against a sleep study. Its numbers are on a different scale from the ones
> a laboratory produces, for reasons explained in [Limits](#limits).
>
> **So:** take the result to a physician. Do not take a treatment decision from it, and do not let a
> reassuring number stop you from seeing someone if you have symptoms.

**Status: pre-alpha.** All four modules build. The two pure-JVM modules (binary format, signal
processing) carry 195 unit tests; the phone and watch applications run on emulators and are covered by
instrumented tests of the guard rails. What does not exist yet is a single night of real data: no
accelerometer has been worn at an ankle by this software, and nothing has been validated against
polysomnography — nor is there a plan that would make that possible for an individual.

Screens: [`docs/08-screens.md`](docs/08-screens.md).

---

## What it looks like

| | |
|---|---|
| <img src="docs/images/screenshots/trend-numbers.png" width="290" alt="The trend screen: fundamental rhythm of 21 seconds with its confidence interval, hourly count in second place"> | <img src="docs/images/screenshots/night-detail.png" width="290" alt="One night in detail: counts of detected, excluded and in-series movements, median interval, estimated rhythm"> |
| **The rhythm leads.** 21 s with its interval and the number of nights it rests on, all in one line. The hourly count sits below, labelled *estimated, unvalidated* — it goes in the report for the physician, not in the trend. The boxed sentence is the one that matters: the interval spans the 15/h threshold, so the application says it cannot tell you which way. | **One night, including what undermines it.** Movements detected, of which in sleep and awake, and — deliberately next to them — the ones excluded for posture or duration. The estimated rhythm carries its own caveat: a sensor on one leg sees a doubled interval when movements alternate. |
| <img src="docs/images/screenshots/trend-chart.png" width="290" alt="Trend chart: nightly points with a dashed band for the minimum detectable change, Y axis anchored at zero"> | <img src="docs/images/screenshots/nights-list.png" width="290" alt="List of nights, each with its state and quality flags; one night excluded with its value struck through and its reason"> |
| **Points, not a line.** Joining nightly values would assert a continuity the measurement does not have. The dashed band is the smallest change this method can detect; anything inside it is reported as inconclusive before any number is shown. The Y axis starts at zero. | **Excluded nights stay visible.** With their value struck through and the criterion that excluded them. There is no button to exclude a night by hand — removing one after seeing its figure would skew the trend, and that is a machine for finding the result you expected. |
| <img src="docs/images/screenshots/watch.png" width="290" alt="The watch screen: Ready, battery, free space, and a message in red saying recording will not start until the evening context is sealed"> | <img src="docs/images/screenshots/waking-provisional.png" width="290" alt="Waking screen showing a provisional result while the hypnogram has not yet reached Health Connect"> |
| **The watch refuses to start.** One static screen, no animation — every recomposition wakes the processor. Recording will not begin until the evening context is sealed on the phone, because a night recorded without its context cannot be compared to any other. | **Provisional is the normal case.** The hypnogram arrives hours after waking, since the vendor's sync follows its own battery policy. The card says so plainly and states that the figure will be recomputed on its own. It is not an error state. |

Full set with commentary: [`docs/08-screens.md`](docs/08-screens.md).

## What you need

Pendulum deliberately requires **two** devices, and this is not a limitation that can be engineered away.

| Where | What | Why |
|---|---|---|
| **Ankle** | A Wear OS watch (developed against a Pixel Watch 3) | The only class of consumer device whose **raw** accelerometer a third-party app can read at 50 Hz for eight hours. Fitbit, Oura, Whoop and Garmin expose aggregated vendor metrics, never the raw sensor. |
| **Wrist, finger, or under the mattress** | Any source that writes sleep sessions to **Health Connect** | It provides the denominator. Consumer wearables estimate *total sleep time* well and sleep *stages* poorly — and total sleep time is all the index needs. |

It is the **accelerometer** that does the work, not a gyroscope. A gyroscope costs 3 to 40 times
more current depending on mode and adds nothing for a movement lasting 0.5 to 10 seconds.

## What it measures, and why it is not the usual number

The clinical convention is the PLM index — movements per hour of sleep. Pendulum computes it, and puts
it in the report you would hand to a physician, because that is the number a sleep specialist reads.

But it is **not** the number the app tracks over time, for three reasons:

- **Stability.** Night-to-night variability of the hourly count is 43.2 % ± 37.1; of the mean log
  inter-movement interval, 3.6 % ± 3.7 (Skeba et al., *Sleep Med* 2016, PMID 26847989). Twelve times less.
- **No denominator.** The inter-movement interval and the periodicity index are computed from the
  movement onsets alone. The hourly count needs hours of sleep — and when that estimate is derived
  from the same accelerometer that supplies the movements, the metric becomes circular and
  self-amplifying.
- **Scale.** 39 % of movements scored on EMG produce no detectable motion at an ankle-worn sensor
  (Terrill et al., EMBC 2013, PMID 24111321). An accelerometric count is a different quantity, not a
  noisy estimate of the EMG one, so the 15/h clinical threshold does not transfer.

![Two panels over fourteen nights. The hourly count scatters widely; the fundamental rhythm stays close to its median.](docs/images/why-the-rhythm.svg)

*Fourteen nights, synthetic — the scatter is drawn from the published night-to-night variability, not
from recorded data. Same person, same disorder, both panels: the count says something different every
night, the rhythm says the same thing. That is the whole argument.*

So the tracked quantity is the **fundamental rhythm in seconds**, recovered by deconvolving the
harmonics of the inter-movement interval distribution. A missed movement merges two 21 s intervals
into one 42 s interval — a harmonic, not noise. The mixture model that separates them also returns
the **estimated miss rate**, which doubles as a night-comparability check.

## Architecture

```
pendulum/
├─ format/   Kotlin JVM   append-only chunk codec, CRC-protected, wire structures
├─ algo/     Kotlin JVM   integrity, timeline, DSP, detection, sleep mask, indices, synthetic truth
├─ wear/     Android      foreground capture service, incremental sync
└─ phone/    Android      ingestion, Health Connect, storage, Compose UI
```

Everything testable on a JVM lives outside the Android modules. `algo` depends on nothing at all —
not on Android, not even on `format` — so it can be exercised against synthetic signals with
injected ground truth.

### Build

```bash
./gradlew :format:test :algo:test          # 195 unit tests, pure JVM
./gradlew :wear:assembleDebug :phone:assembleDebug
./gradlew :phone:connectedDebugAndroidTest # guard rails, needs a device or emulator
```

Requires JDK 17 and an Android SDK with platform 36. On WSL, if your build directory sits on a `drvfs`/9p Windows mount, Gradle will
fail on `chmod`; either remount with the `metadata` option or point `-Ppendulum.buildRoot` at a native
filesystem path.

## Documentation

Documentation lives in [`docs/`](docs/). Start with [`docs/README.md`](docs/README.md), which is a
short index with three suggested reading orders.

| Document | Contents |
|---|---|
| [`docs/01-overview.md`](docs/01-overview.md) | What the project is, how a night flows through it, design decisions, guard rails, roadmap |
| [`docs/02-science.md`](docs/02-science.md) | The phenomenon, the scoring rules, why periodicity rather than a count, and what is genuinely unknown |
| [`docs/03-algorithm.md`](docs/03-algorithm.md) | The processing chain stage by stage, rejected alternatives, a worked numerical example, full parameter tables |
| [`docs/04-architecture.md`](docs/04-architecture.md) | Module map, capture on the watch, chunk format, transfer protocol, energy budget |
| [`docs/05-devices.md`](docs/05-devices.md) | Choosing the hardware and the sleep source; Health Connect integration |
| [`docs/06-interface.md`](docs/06-interface.md) | Interface principles, screens, how an uncertain number is displayed |
| [`docs/07-validation.md`](docs/07-validation.md) | How it is tested, the synthetic ground truth, and the current test status including what fails |
| [`docs/08-screens.md`](docs/08-screens.md) | Screenshots of the running application, and the three defects only a real render exposed |
| [`docs/09-release.md`](docs/09-release.md) | Installing a release, and cutting the next one |
| [`docs/references.md`](docs/references.md) | Bibliography, marked by whether each source was read in full, as an abstract, or not at all |

The original working documents are in French under [`docs/fr/`](docs/fr/). They are more detailed
than the English set and remain the authoritative record; where the two disagree, the French text
is correct.

## Limits

These are not disclaimers added for form. They are the reasons the output must not be read as a
diagnosis.

- **A leg sensor cannot diagnose restless legs syndrome.** The diagnosis is clinical — five IRLSSG
  criteria based on waking symptoms. Periodic limb movements are a supporting criterion, nothing more.
- **The AASM issues a strong recommendation against actigraphy** as a replacement for EMG in
  diagnosing periodic limb movement disorder (Smith et al., *JCSM* 2018).
- **Without a respiratory channel**, respiratory-related leg movements cannot be excluded. In the
  presence of sleep apnoea the index is structurally **overestimated**.
- **A unilateral sensor** misses movements of the opposite leg, biasing the count downward. This
  does not cancel the previous bias; do not assume the two compensate.
- **A single night means nothing.** In confirmed patients, the 15/h threshold is exceeded on only
  about a third of individual nights. The interface refuses to draw a trend below three nights, by
  design and not by warning.

## Licence

Pendulum is **dual-licensed**.

- **Source code** is available under the **GNU Affero General Public License v3.0** — see
  [`LICENSE`](LICENSE). You may use, study, modify and redistribute it freely, including for
  research, provided derivative works remain under the AGPL, and provided that a modified version
  offered to users over a network makes its complete source available to those users.
- **Documentation** in `docs/` is licensed under
  **[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)** — see [`LICENSE-docs`](LICENSE-docs).

**Commercial licensing.** If you want to incorporate Pendulum into a closed-source or commercial
product without the obligations of the AGPL, a separate commercial licence is available. Open an
issue titled `commercial licence` to start the conversation.

Being honest about what this does and does not achieve: a licence cannot compel anyone to share
revenue. It can only make a commercial user's cheapest legal path lead through a negotiation.
Nothing here prevents a company from reading the specification and reimplementing the method
independently — copyright protects the expression, not the algorithm.

## Contributing

Contributions are welcome, and require signing a Contributor Licence Agreement — see
[`CONTRIBUTING.md`](CONTRIBUTING.md). Without it the project would lose the ability to offer
commercial licences at all, since that requires holding the rights to the whole work.

## Prior art

No consumer application or open-source project performing this measurement was found. The
precedents are medical and out of reach (SOMNOwatch), discontinued (Philips PAM-RL, Actiwatch),
or single-subject academic prototypes. The feasibility argument rests on Spektor et al.,
*Clocks & Sleep* 2024, which validates a unilateral ankle triaxial accelerometer against
polysomnography — with the important caveat that its scoring was **manual**, not algorithmic.

Note also US patent 10,335,085 (Johns Hopkins), which mentions an accelerometer held on the leg by
a strap for detecting periodic leg movements. Its claims and status have not been examined. This is
raised as a fact worth knowing, not as legal advice.
