# Pendulum — Interface specification (v1)

Interface design document: the principles, the screen-by-screen reasoning, and the trade-offs that
were weighed and then settled. It repeats neither the architecture nor the algorithm.

> **This document is no longer authoritative on the screen, and it matters in which direction.** It
> was written before several decisions the code has taken since, and it says so itself in three
> places: the "Tonight" card that appears and disappears between 20:00 and 04:00 no longer exists,
> the trend screen is no longer the start destination, and the colours of §5.1 would fail the
> contrast test that the ones in the code pass.
>
> **The current state of the interface is in [`../06-interface.md`](../06-interface.md)**, which
> derives from this file and records every deviation with its reason. What exists only here and keeps
> all of its value: the options for representing the figure, compared one by one (§3), the candidate
> texts written out in full, and the trace of the reasoning — the *why* of which `06-interface.md`
> keeps only the conclusion.
>
> Its original sentence — "on any contradiction between the two: `SPEC-v1.md` is authoritative on the
> technical side" — pointed at a document that has itself been replaced since. The map of authorities
> is in [`../README.md`](../README.md).

**A single user**: an adult, technical, who suspects a sleep-related movement disorder and who wants to
know whether his treatment changes anything, and to be able to show a report to a sleep physician.
He consults the app at 7 in the morning, foggy, one-handed, in the dark.

**Central design constraint**: the measurement is uncertain, and the main source of uncertainty is not
the sensor — it is night-to-night variability. In confirmed RLS patients, the 15/h threshold is exceeded
on only ~34 % of individual nights. One night says nothing. The interface must **refuse to conclude on a
single night**, not settle for a warning next to a large figure.

---

## 1. Design principles

Seven principles. Each one is a constraint that can be checked in a screen review: yes or no can be
answered by looking at the mock-up or at the code, with no argument about taste.

### P1 — No aggregation below 3 eligible nights

The Trend screen computes, displays and exports **no** aggregate value (median, category, verdict,
curve) as long as fewer than 3 eligible nights exist. It shows a collection state instead.
*Check: force the database to 0, 1, 2 nights → no aggregate figure appears, no plot line is drawn,
export is disabled with a reason displayed.*

### P2 — Every aggregate figure carries its uncertainty, in the same line of text

Displaying a median without its interval and its n is forbidden. The canonical format is
`22/h · 95 % CI 14–31 · 6 nights`. No asterisk, no footnote, no tooltip:
the uncertainty is in the sentence, at the same font size as the figure or at most one step below.
*Check: grep the composables — no instance of `MetricHeadline` without non-null `interval` and
`nightCount` parameters.*

### P3 — A variation indistinguishable from noise is named as such before it is quantified

When the confidence interval of the difference between two periods contains zero, the first line
displayed is "Inconclusive variation". The figure for the difference comes after, never before, never
larger. The words "improvement", "worsening", "it works", "effective" exist nowhere in the app's
strings.
*Check: instrumented test on a null-effect data set → the string "inconclusive" precedes the value in
composition order.*

### P4 — At most three pieces of information above the fold, on waking

The morning landing screen fits into a two-second glance: (a) where last night stands, (b) the aggregate
figure if it exists, (c) a single action. Nothing else is visible without scrolling. No decision to
take, no multiple choice, no form.
*Check: screenshot of the Trend screen at 411×891 dp → count the interactive elements visible without
scrolling: ≤ 3, of which at most 1 action button.*

### P5 — No truncated scale, no mean hiding an extremum

The Y axis of every result plot starts at 0. The night signal envelope is decimated
min/max per pixel column, never averaged — a 2 s peak within 8 h must stay visible. A data point
clipped by the axis bounds is marked explicitly (chevron ▲ + value in plain text).
*Check: unit test on the decimator — an isolated single-sample peak survives a decimation factor of
4096.*

### P6 — Colour is never the sole carrier of information

Every distinction encoded in colour is doubled by a distinction of shape, of position, of dash pattern
or of label. Green and red never encode a clinical result (they serve only technical state:
transfer succeeded / failed). Sleep stages are distinguished first by their vertical position.
*Check: convert every screenshot to greyscale → all the information stays legible.*

### P7 — The per-night figure is available but never foregrounded

The user is technical: the value of a night is not hidden from him. But it appears only at
"body text" scale, in secondary colour, in the night detail or the list, always accompanied by the
mention "single-night value — not interpretable on its own". It never appears as a title, never
in a notification, never in a widget, never in the summary of the export.
*Check: the `metricXL` style is used only in `TrendHeadline`. A single occurrence in the whole
tree.*

---

## 2. Screen-by-screen walkthrough

Root navigation: a `NavigationBar` with three destinations, **Trend as the start destination**.

| Tab | Icon | Route |
|---|---|---|
| Trend | `Icons.Outlined.ShowChart` | `trend` |
| Nights | `Icons.Outlined.Nightlight` | `nights` |
| Settings | `Icons.Outlined.Tune` | `settings` |

The questionnaire, the night detail and the export are stacked destinations, without the navigation bar.

---

### 2.1 First launch

Five steps, not skippable, in a non-swipable `HorizontalPager` (progression by button only, so that the
warning cannot be skimmed past). Progress indicator: a thin `LinearProgressIndicator` at the top,
1/5 → 5/5. No "Skip" button.

#### Screen 1/5 — Warning

> **Title**: What Pendulum cannot do

Body (full text, scrollable; the button stays disabled until the scroll has reached the bottom):

```
Pendulum estimates the number of leg movements per hour of sleep from the accelerometer of a watch
worn at the ankle. It is a personal measuring instrument. It is not a medical device and it is not a
diagnosis.

Four limits that will never go away, whatever the quality of your nights:

• Pendulum cannot diagnose restless legs syndrome. That diagnosis is clinical: it rests on your
  symptoms while awake, not on a sensor. Movements during sleep are only a supporting criterion.

• Pendulum does not measure your breathing. It therefore cannot distinguish a periodic movement from a
  movement caused by an apnoea. If you have sleep apnoea, the figure is overestimated, and the gap can
  reach several tens of movements per hour.

• Pendulum measures only one leg. The movements of the other leg are missed. This bias pulls the figure
  down. It does not compensate for the previous one: the two errors do not cancel each other out, they
  add to the uncertainty.

• The American Academy of Sleep Medicine explicitly recommends not replacing electromyography with
  actigraphy to diagnose a periodic limb movement disorder.

What Pendulum does usefully: track a trend over several nights, under stable conditions, and produce a
document that you can take to a sleep physician.
```

Primary button: **I have read and understood** (disabled until the bottom of the scroll).
Below the button, in tertiary caption: `This warning stays available under Settings › About.`

#### Screen 2/5 — What Pendulum needs

> **Title**: Two watches, or a watch and an app

Three `RequirementRow` blocks (icon + title + description):

1. **A Wear OS watch at the ankle**
   `It records the accelerometer all night at 50 Hz. It is the one that measures the movements. It must
   be charged to 100 % at bedtime: one night uses between 40 and 70 % of the battery.`
2. **A source of sleep stages**
   `A second watch on the wrist (Samsung Health) or Sleep as Android, writing the hypnogram into Health
   Connect. Without it, Pendulum knows when you move but not when you sleep: it then uses its own
   immobility mask, less reliable, and flags this on every night concerned.`
3. **Three nights minimum, five to seven preferably**
   `Pendulum refuses to compute a trend below three nights. This is not decorative caution: on an
   isolated night, the result is dominated by chance.`

Button: **Continue**.

#### Screen 3/5 — Pairing the watch

Initial state: `Looking for the watch…` with a `CircularProgressIndicator` and the sub-text
`Open Pendulum on your watch. The two devices must be paired in the Watch app.`

Success: a card with the node name (`Pixel Watch 3`), the watch app version, battery, free space.
Verification line: `Accelerometer: 50 Hz requested · FIFO 1 024 events · wake-up sensor: yes`
(values read at runtime — this is a check, not decoration).

Failure after 30 s: see §7, message `E-PAIR-01`.

Button: **Continue** (enabled only if a node has answered).
Secondary link: `Continue without a watch for now` → allows the app to be explored, disables
recording, displays a persistent banner on Trend.

#### Screen 4/5 — Sleep source

> **Title**: Where do your sleep stages come from?

Primary button **Allow Health Connect** → triggers the `READ_SLEEP` permission request.
(The rationale activity required by Health Connect displays: `Pendulum reads your sleep sessions and
stages in order to report leg movements per hour of real sleep, and not per hour spent in bed. No data
leaves the phone.`)

After authorisation, Pendulum reads the last 7 days and displays the list of sources found:

```
Sources detected
  Samsung Health        6 nights out of 7 · detailed stages (REM, light, deep)   ● preferred source
  Sleep as Android      2 nights out of 7 · detailed stages
```

If a source provides only the total duration without stages:
`This source does not provide detailed stages. Pendulum will be able to compute total sleep time, but
not to break the movements down by stage.`

If there is no source: message `E-HC-01` (§7) + button **Continue without a hypnogram**.

#### Screen 5/5 — Notifications and measurement conditions

Button **Allow notifications** (`POST_NOTIFICATIONS`), with the reason:
`One notification per night, on waking, when the analysis is ready. No others.`

Then a block **Conditions to keep identical from one night to the next**, presented as an instruction,
not as a setting:

```
The detection threshold adapts to the background noise of each night. If the mechanical conditions
change, the nights are no longer comparable with one another.

Keep identical:
  • the same strap, at the same tightness hole;
  • the same leg;
  • the watch on the front face of the tibia, just above the malleolus;
  • the same mattress.

Note down the tightness hole you use: Pendulum will remind you of it at bedtime.
```

Short `OutlinedTextField`: `Strap-hole reference (e.g. "4th hole")` — stored, redisplayed every evening.

Final button: **Finish**.

---

### 2.2 The bedtime ritual

Going to bed is a two-handed ritual: the watch starts, the phone confirms. Nothing else.

#### On the watch

A single screen (see §6). The user opens Pendulum and presses **START**. Two seconds.

#### On the phone — the "Tonight" card

Visible at the top of the Trend screen **between 20:00 and 04:00 only**. Outside that window it
disappears completely (P4: nothing useless on waking).

State A — recording not started:

```
┌──────────────────────────────────────────────┐
│ TONIGHT                                      │
│                                              │
│ Watch        98 %  ·  1.2 GB free     ✓      │
│ Strap        4th hole, right leg      ⓘ      │
│ Sleep        Samsung Health, active   ✓      │
│                                              │
│ Press START on the watch.                    │
└──────────────────────────────────────────────┘
```

If the watch battery is < 85 %: the line turns amber with the text
`98 % recommended — one night uses 40 to 70 %.` Nothing is blocked: it is advice, not a gate.

State B — recording in progress:

```
┌──────────────────────────────────────────────┐
│ RECORDING IN PROGRESS                        │
│                                              │
│ Since 23:12  ·  2 h 47 min                   │
│ 498 200 samples  ·  50.2 Hz measured         │
│ Watch battery 71 %  ·  0 gaps                │
│                                              │
│ Stopping happens on the watch, or            │
│ automatically as soon as it is on the dock.  │
└──────────────────────────────────────────────┘
```

Refresh: 60 s, only when the phone screen is on and the app is in the foreground.
No service, no persistent notification on the phone side.

---

### 2.3 Waking

There is only one notification per night, sent **after** the analysis, never before:
`Pendulum — Night of 12 March analysed. 6 nights available.` No figure in the notification (P7).

The Trend screen displays a status strip (`StatusStrip`) at the top, which takes one of the following
states. One state at a time, one possible action.

#### State 1 — Waiting for transfer

```
Night of 12 March recorded on the watch
8.8 MB to transfer. The transfer starts when the watch is on its dock and within
Bluetooth range. Allow 4 minutes.
                                                       [ Transfer now ]
```

#### State 2 — Transfer in progress

```
Transfer in progress — 4.1 MB of 8.8 MB
[████████░░░░░░░░]  chunk 8/17
You can close the app.
```
The transfer resumes where it stopped; never display a percentage that goes backwards.

#### State 3 — Analysis in progress

```
Analysing the night of 12 March
[████████████░░░░]  detecting movements
About 40 seconds.
```
Three step labels only: `assembling the signal`, `detecting movements`,
`cross-referencing with sleep`. No technical log here (it is in Settings › Log).

#### State 4 — Partial analysis (the most frequent case on waking)

The accelerometer analysis is finished, the hypnogram has not yet arrived in Health Connect. This is
**normal** and the text must say so, otherwise the user believes something has broken every morning.

```
Night of 12 March — provisional result
The movements are detected. The sleep stages from your wrist watch have not arrived in
Health Connect yet: this synchronisation often happens several hours after waking.

In the meantime, Pendulum uses its own immobility mask. The figure will be recomputed
automatically, with nothing to do on your part.

Last attempt: 07:12 · next: 08:12
                                                     [ Retry now ]
```

The night appears in the list and in the trend, flagged `accel mask` (a flag, not an error).
After recomputation, a `Snackbar`: `Night of 12 March recomputed with the hypnogram.`

Giving up at T+36 h: the night stays eligible but keeps the `accel mask` flag permanently.

#### State 5 — Failure

A single card, explicit reason, explicit action. The exact texts are in §7. Structure:

```
Night of 12 March — analysis impossible
<what happened, one sentence>
<what to do, one sentence>
                             [ Repair action ]   [ See the technical detail ]
```

A failed night is **never** silently excluded: it appears in the night list, struck through,
with its reason. The "eligible nights" counter always explains where the difference comes from:
`7 nights recorded · 5 eligible · 2 excluded (see Nights).`

---

### 2.4 Trend — the main screen

This is the screen designed first, and the start destination. Everything else in the product is a
detail beside it.

#### Case A — fewer than 3 eligible nights: refusal to aggregate

No plot. No median. No category. A single full-width card:

```
┌────────────────────────────────────────────────────────┐
│                                                        │
│                   2 nights out of 3                    │
│              ●        ●        ○                       │
│                                                        │
│  Pendulum computes no result before three nights.      │
│                                                        │
│  On an isolated night, the number of movements varies  │
│  enormously from one night to the next, including in   │
│  people whose disorder is confirmed: the threshold of  │
│  15 movements per hour is exceeded on only about one   │
│  night in three. A result displayed now would mislead  │
│  you, in one direction or the other.                   │
│                                                        │
│  Record one more night.                                │
│                                                        │
│  Nights recorded                                       │
│   11 March   7 h 42 of sleep   acceptable quality   →  │
│   12 March   6 h 58 of sleep   acceptable quality   →  │
│                                                        │
└────────────────────────────────────────────────────────┘
```

The three dots are filled/empty, never a progress bar (a bar suggests a score that climbs).
Export is disabled, with the reason displayed on the button itself:
`Export unavailable — 3 nights minimum`.

The link to a night's detail stays active: the technical user can inspect his signal. It is the
**detail** that shows a per-night figure, not the trend (P7).

#### Case B — 3 or 4 nights: provisional result

Identical to case C, with two differences:
- a banner above the figure: `Provisional result — 4 nights. Five to seven nights are recommended;
  the interval below will stay wide until you have more of them.`;
- **no textual category is displayed** (see §3), whatever the interval.

#### Case C — 5 nights or more: the full screen

```
┌────────────────────────────────────────────────────────┐
│  Trend                                   [7 nights ▾]  │   ← period picker
├────────────────────────────────────────────────────────┤
│                                                        │
│   22 /h                                                │   metricXL, tabular
│   95 % CI: 14 – 31   ·   6 eligible nights             │   body, secondary
│                                                        │
│   Periodic movement index, median across nights        │   caption, tertiary
│                                                        │
│   ┌──────────────────────────────────────────────┐     │
│   │ Above the 15/h threshold used in clinical    │     │
│   │ practice. The interval remains compatible    │     │
│   │ with a lower value. To be discussed with a   │     │
│   │ sleep physician.                             │     │
│   └──────────────────────────────────────────────┘     │
│                                                        │
├────────────────────────────────────────────────────────┤
│   [ TREND CHART ]           see §4.2                   │
│                                                        │
├────────────────────────────────────────────────────────┤
│  Compare two periods                                →  │
│  Before / after a change of treatment                  │
├────────────────────────────────────────────────────────┤
│  Counting rule            AASM v3 (5–90 s)          ▾  │
│  Sleep mask               Health Connect (5/6 nights)  │
│  Movements while awake    PLMW 9/h                     │
│  Periodicity (Ferri)      0.71                         │
├────────────────────────────────────────────────────────┤
│  7 nights recorded · 6 eligible · 1 excluded        →  │
├────────────────────────────────────────────────────────┤
│  [ Screening questionnaire ]        not filled in   →  │
├────────────────────────────────────────────────────────┤
│         [ Prepare a report for the physician ]         │
└────────────────────────────────────────────────────────┘
```

Content notes:

- The period picker offers: `Last 7 nights`, `Last 14 nights`, `Last 30 nights`,
  `All`, `Custom period…`. Changing the period recomputes the median and the interval. The number of
  eligible nights is **always** redisplayed next to the figure: it is the guard rail against the
  illusion of precision produced by a long period containing few nights.
- The boxed block under the figure is the **position statement** (§3). It is generated by a
  deterministic function, not written on the fly, and exactly five variants of it exist (§3.4).
- Switching the rule AASM v3 / WASM 2016 recomputes everything on screen, immediately, and the gap
  between the two is substantial: it is meant to be visible in a single gesture. A `Snackbar` recalls:
  `WASM 2016 requires an interval of at least 10 s between two movements; AASM v3 accepts 5 s. The
  figures are not comparable between the two rules.`

#### Comparing two periods (stacked screen)

Two date-range pickers (`Period A`, `Period B`), with a preset
`Before / after a change of treatment` that simply asks for a pivot date.

Below 5 eligible nights in either of the two periods:
```
Comparison unavailable
Period A: 3 eligible nights. At least 5 are needed in each period.
With fewer nights, the measured difference would be dominated by the normal
night-to-night variability, and not by a real effect.
```

Otherwise, the result in three lines, in this mandatory order (P3):

```
Inconclusive variation
Period A  31 /h   (95 % CI 19 – 44)   6 nights   1–15 February
Period B  22 /h   (95 % CI 14 – 31)   6 nights   1–15 March
Difference  −9 /h  (95 % CI −24 to +5)

The interval of the difference contains zero: with these data, a real change
cannot be distinguished from an ordinary fluctuation between nights. Your own
nights vary by ±12/h around their median.

To settle it, about 11 nights per period would be needed.
```

The estimate of the number of nights needed is computed by simulation from the observed dispersion,
and presented as an order of magnitude (`about`, rounded up). If the computation exceeds
30 nights per period: `With variability this large, an effect of this size is not measurable by
this method.` — saying that it is out of reach is more honest than displaying "about 84 nights".

When the interval of the difference excludes zero, the first line becomes
`Difference larger than the night-to-night variability` — never "improvement".

---

### 2.5 Night list

A `LazyColumn` of `NightRow`, grouped by month (`stickyHeader`). Reverse-chronological order.

One row:

```
 12 March   Fri                                                 →
 23:12 → 06:58 · 6 h 58 of sleep (Health Connect)
 24 /h   single-night value                        ● eligible
```

- The date in `titleM`, the figure in `body` in secondary colour (P7).
- State pill on the right: `● eligible` (accent), `◐ provisional` (amber, hypnogram missing),
  `○ excluded` (tertiary, the figure struck through) + a short reason underneath
  (`excluded: 2 h 10 of sleep`).
- Quality flags as compact `AssistChip`s below the row, at most 3 visible then `+2`:
  `accel mask`, `gap 47 s`, `off-body 12 %`, `battery 8 %`, `posture ×14`.

Filter at the top: `All` / `Eligible` / `Excluded`.

---

### 2.6 Night detail

Stacked screen, vertical scroll, five sections.

**Section 1 — Header**
```
Night of 12 March
23:12 → 06:58  ·  7 h 46 in bed  ·  6 h 58 of sleep
```
Then, immediately, the value and its warning merged into a single block:
```
24 /h   PLMI for this night
A single night allows no conclusion. This figure exists to check the measurement,
not to interpret it. See the trend.
                                                            [ See the trend → ]
```

**Section 2 — Night chart + hypnogram** (specified in §4.1 and §4.3)
The two charts share the same horizontal axis and the same cursor. A readout strip under the finger:
`02:14:38 · amplitude ×9.2 · threshold ×8.0 · N2 · CLM #143, series 12`.

**Section 3 — Detected events**
```
Movements detected                     412
  of which asleep (PLMS)               278
  of which awake (PLMW)                 64
  excluded — posture                    57
  excluded — duration outside 0.5–10 s  13

Series (≥ 4 movements)                  31   covering 3 h 12
Median onset-onset interval           23.4 s
Periodicity index (Ferri)              0.71
```
Every line is clickable and filters the chart above (the markers not concerned drop to 20 %
opacity). A list `All events →` opens a table: `#, start, duration, amplitude/floor,
stage, series, exclusion reason`.

**Section 4 — Night quality**
A list of checks, each with its measured value, its threshold, and a state:
```
Signal coverage           99.2 %   threshold 97 %     ✓
Largest gap                1.8 s   threshold 5 s      ✓
Cumulative gaps            11 s    threshold 120 s    ✓
Measured frequency        50.21 Hz  expected 50 Hz    ✓
Battery at end of night     34 %   threshold 20 %     ✓
Worn (off-body)           96.4 %   threshold 90 %     ✓
Total sleep             6 h 58     threshold 4 h      ✓
Sleep source            Health Connect (Samsung Health)
Rule applied            AASM v3 · algo 1.4.0 · "default" profile
```
A single failed check is enough to make the night ineligible; it is then displayed at the top, in
amber, with the exact label from §7.

**Section 5 — Parameters and recomputation** (collapsed by default, `Advanced parameters`)
Sliders: onset threshold (4–12 × floor), offset threshold (1–4 ×), min/max CLM duration,
noise-floor window (10–60 s), merging of close CLMs (0–1 s), inclusion of postural movements.
Button **Recompute this night** + **Apply to all nights** (with confirmation:
`All the nights will be recomputed with these parameters. The previous results are kept in the
log. Nights computed with different parameters are not comparable with one another.`).

A permanent banner appears as soon as a non-default profile is active, on Trend **and** in the export:
`Custom parameters active (profile "threshold 6×"). The values are not comparable with the reference
values.`

---

### 2.7 Screening questionnaire

Two steps, with no overall score displayed large.

**Step 1 — Single rapid RLS screening question**
```
When you try to relax in the evening or to fall asleep, do you sometimes feel
an irresistible urge to move your legs, or unpleasant sensations in your legs,
which are relieved when you move?

                          [ Yes ]        [ No ]
```
Answer "No":
`This single question rules out restless legs syndrome in the very great majority of cases. The
detailed questionnaire remains available if you want to fill it in anyway.`
Answer "Yes" → step 2.

**Step 2 — Cambridge-Hopkins CH-RLSq**
One question per screen, `LinearProgressIndicator`, going back is possible.
At the top: `This questionnaire is about what you feel while awake. It is independent of the
measurement made by the watch: the two complement each other, neither replaces the other.`

Result, with no score figure as a title:
```
Answers compatible with a restless legs syndrome
This questionnaire is a screening tool. It does not make a diagnosis: the five diagnostic
criteria must be verified by a physician, in particular to rule out the conditions that
mimic RLS (cramps, neuropathy, positional discomfort, drug-induced akathisia).

Your answers are included in the exportable report.
                                                        [ Review my answers ]
```
Three possible outcomes: `compatible` / `not compatible` / `incomplete`. No severity scale (the IRLS
is under copyright and excluded). No alarming colouring.

The date it was filled in is displayed on Trend; beyond 6 months: `filled in 8 months ago — to be
redone`.

---

### 2.8 Settings

Plain `ListItem` sections, no search, no deep sub-menus.

**Measurement**
- Counting rule: `AASM v3 (5–90 s)` / `WASM 2016 (10–90 s)` — with the non-comparability note.
- Preferred sleep source: list of the `dataOrigin`s detected + `Accel mask only`.
- Parameter profile: `default` / saved profiles / `Manage…`.
- Wearing reference: strap, tightness hole, leg (recalled at bedtime).
- Automatic stop: `On charging` (default) and `Cut-off time` (default 11:00).

**Devices**
- Watch: name, version, battery, space, last contact. Button `Sync now`.
- Health Connect: state of the permissions, button `Manage in Health Connect`.

**Data**
- Space used, `Purge raw signals older than 90 days` (the results are kept).
- `Technical log`: timestamped list of workers, gaps, HC retries, errors. Copyable.
- `Erase all data` (double confirmation, typing the word `ERASE`).

**About**
- App version, algorithm version, `Read the warning again`, scientific sources
  (list of references, offline).

### 2.9 Exporting a report for the physician

Stacked screen, preview at the top, options at the bottom.

Two formats, checkboxes:
- **PDF report (1 to 2 pages)** — meant to be printed and read in consultation.
- **CSV data** — three zipped files: `nights.csv`, `events.csv`, `params.csv`.

Options: date range, counting rule (both can be included side by side),
include the questionnaire (yes/no), include the excluded nights (yes, listed with their reason — **yes**
by default, because hiding the failed nights from a physician is misleading).

**Mandatory content of the PDF** — this order, this content, in the high-legibility light theme:

1. **Header banner**: `Personal measurement by ankle accelerometer — is not a medical examination.`
2. Minimal identity: optional first name (free text field), period, number of nights recorded/eligible.
3. **Result**: `Median PLMI 22/h · 95 % CI 14–31 · 6 nights · AASM v3 rule · Health Connect mask
   (5 nights) / accel (1 night)`. Median PLMW. Median Ferri periodicity index.
4. **Trend chart** (vector, §4.2).
5. **Per-night table**: date, times, TST, mask source, PLMI, PLMW, PI, number of series, flags,
   eligibility and exclusion reason.
6. **One night chart + hypnogram** for the median night (§4.1, §4.3), full-width landscape.
7. **Method**, in plain language and in six lines: sensor, sampling rate, filtering, adaptive threshold
   set on the noise floor, series rule, source of the sleep time, algorithm version.
8. **Limits**, a literal reprise of the four limits from screen 1/5 of the first launch.
9. Questionnaire: outcome + detailed answers, if included.

Button **Share** → `Intent.ACTION_SEND` (FileProvider). No network send, ever.
Below the button: `The file stays on your phone until you share it.`

If the export is impossible: the button stays visible but disabled, with the reason written on it
(`3 nights minimum`), never an active button that fails.

---

## 3. The problem of representing the figure

### 3.1 The options

**Option A — the bare figure.** `24/h` in large type, per night and in the trend.
Advantages: instantly readable, no computation, no explanation.
Disqualifying defects: it gives a precision that does not exist; it invites daily tracking, exactly the
behaviour that night-to-night variability makes absurd; it has a symbolic threshold at 15 crossed one
evening in three without anything having changed. On this measurement, the bare figure is a lie by
omission.

**Option B — figure + interval per night.** `24/h (18–31)` for one night.
Advantage: it looks rigorous.
Defect: the interval would be manufactured. We have no defensible model of the uncertainty *inside*
a night — no ground truth, no legitimate resampling (the events are not independent, they are periodic
by definition). An invented interval is worse than no interval:
it moves the false confidence one notch without removing it. **Excluded.**

**Option C — confidence band over the multi-night trend.** Per-night points, the median of the period,
and the confidence interval of that median obtained by bootstrap over the nights.
Decisive advantage: the uncertainty displayed is **measured on the user's own real data**, and it
captures the dominant error source — the variation from one night to the next. It narrows naturally
when he records more nights, which makes the right behaviour (record more) visible and
rewarded without gamification.
Defect: it needs at least 3 nights, ideally 5. That defect is acceptable: it is also the truth.

**Option D — category alone.** `High` / `Intermediate` / `Low`.
Advantage: no false precision, very little cognitive load.
Defects: the physician needs the number; the boundary at 15/h is precisely the least stable point,
so a blunt category there is as arbitrary as a bare figure; and a category has a whiff of
diagnosis that the figure does not have.

### 3.2 Recommendation

**Option C as the main one, option D as secondary and conditional, option A relegated to the night detail.**

Concretely, the display hierarchy is fixed:

| Level | Content | Style | Where |
|---|---|---|---|
| 1 | Median of the period | `metricXL` | Trend, at the top |
| 2 | `95 % CI: a – b · n nights` | `body`, secondary | same card, next line |
| 3 | Position statement (§3.4) | `body`, boxed | same card |
| 4 | Scatter of points + band | chart | §4.2 |
| 5 | PLMI of one night | `body`, secondary | night detail, list, CSV |

The argument: the uncertainty displayed must be **estimated, not decorative**. The confidence band on the
trend is the only one of the four options whose displayed number is computed from observed data.
It also has the right pedagogical property: it shrinks as more is collected, which teaches the
user — without lecturing him — that the measurement is a statistic and not an instrument reading.

**Computation, to be implemented exactly like this:**
```
estimator       = median of the PLMIs of the eligible nights of the period
CI              = 95 % percentile bootstrap, 2 000 resamples with replacement over the nights
                  (fixed seed derived from the set of sessionIds → same screen = same CI, reproducible)
dispersion      = MAD of the per-night PLMIs × 1.4826  (displayed as "your nights vary by ±X/h")
rounding        = median and bounds to the integer; never a decimal on a PLMI
```
The fixed seed is an interface requirement, not a statistical one: an interval that moves on every
recomposition destroys trust.

### 3.3 Behaviour below 3 nights

**Refusal, not warning.** Below 3 eligible nights:
- no median is computed, nor stored, nor exported;
- the trend chart is not rendered (not even empty with axes: an empty axis invites imagining a
  curve);
- no category, no position statement;
- the screen shows the collection state of §2.4 case A, with the reason given in figures ("about one
  night in three"), because the user is technical and a reason in figures is more convincing than an
  instruction;
- export is disabled with its reason visible;
- a night's detail stays entirely accessible, figure included, so that the measurement can be
  checked.

From 3 to 4 nights: aggregation allowed, **category forbidden**, "provisional result" banner.
From 5 nights on: the full screen.

### 3.4 A variation indistinguishable from noise

Five possible position statements, generated by a pure function
`positionStatement(median, ciLow, ciHigh, n): PositionStatement`. No other wording exists.

| Condition | Text |
|---|---|
| `n < 3` | *(no statement — refusal screen)* |
| `n ∈ [3,4]` | `Provisional result over n nights. No category is offered before five nights.` |
| `ciHigh < 15` | `Below the 15/h threshold used in clinical practice, including at the top of the interval.` |
| `ciLow > 15` | `Above the 15/h threshold used in clinical practice, including at the bottom of the interval. To be discussed with a sleep physician.` |
| `ciLow ≤ 15 ≤ ciHigh` | `The interval spans the 15/h threshold: with these nights, Pendulum cannot say which side you are on. Recording more nights will narrow the interval.` |

Every statement ends without an exclamation mark, without an emoji, without a semantic background colour
(the frame is neutral in all five cases — colouring the frame red when `ciLow > 15` would turn a
measurement into a verdict).

For the **comparison of two periods**, the rule is the one in §2.4, with this non-negotiable order:
1. the distinguishability verdict;
2. the two estimates with their intervals;
3. the difference with its interval;
4. the user's own night-to-night dispersion, as the yardstick for the noise;
5. the number of nights that would be needed.

One further point, often forgotten: when the difference is distinguishable, the app still does not say
that the treatment is what caused it. Mandatory text:
`This difference exceeds the usual variability between your nights. Pendulum cannot say what caused
it: a change of treatment, of sleep, of alcohol, of iron, of bedding or of watch position would
produce the same effect on screen.`

---

## 4. Data-viz

Rules common to the three charts:

- **Rendering in a pure drawing function**, not in a composable:
  `fun DrawScope.drawNightChart(spec: NightChartSpec, tokens: ChartTokens)`. The composables are thin
  wrappers. The same function is called from a Compose `Canvas` (screen) and from the canvas
  of a `PdfDocument.Page` (export) → one rendering code path, one risk of divergence.
- **Screen = dark theme. Export = light theme.** A dark PDF is illegible when printed. The chart tokens
  are therefore parameterised, never hard-coded.
- Minimum stroke width: 1.5 dp on screen, 0.6 pt in the export. Axis font: 11 sp / 8 pt.
- All numeric values in tabular figures.
- Every chart exposes a `Values` button which opens a `ModalBottomSheet` holding the same content in
  table form. It is both the accessible alternative (TalkBack) and the "I want the exact
  figure" mode. The Canvas `contentDescription` sums it up in one sentence:
  `Chart of the night of 12 March, 412 movements detected between 23:12 and 06:58. Values button for the
  table.`

### 4.1 Night chart

**What is drawn** — six layers, from back to front:

1. **Out-of-sleep bands**: solid rectangles, `surfaceMuted`, over the intervals classified as
   awake/out-of-bed by the active mask. They say visually "nothing is counted here".
2. **Signal gaps**: hatched rectangles (lines at 45°, 2 dp spacing, `outline`), with the duration
   written above if > 5 s.
3. **Noise floor**: fine dotted line (`dash 2, 3`), colour `textTertiary`.
4. **Adaptive onset threshold** (8 × floor): dashed line (`dash 6, 4`), colour `accent` at 55 %.
   The offset threshold (2 ×) is not drawn by default — it clutters; an option in the chart settings.
5. **RMS envelope**: solid stroke 1.5 dp, `accent`. Min/max decimation (see below).
6. **Event markers**, on a dedicated 14 dp band below the signal area, never superimposed on the
   curve:
   - counted CLMs: solid vertical tick 2 dp, `accent`;
   - CLMs excluded for posture: fine cross, `textTertiary`;
   - CLMs while awake (PLMW): hollow vertical tick (outline), `accent`;
   - PLM series: continuous horizontal rectangle 4 dp below the markers, `accent` at 35 %, with the
     series number above it when the zoom allows.

**X axis.** Local wall-clock time, from the start to the end of the session. Origin and end fixed on the
local time recorded with its UTC offset — the night of a clock change displays a `+1 h` or `−1 h` mark
on the axis rather than lying about the duration. Major ticks on the round hour (`00:00`, `01:00`), minor
ones every 15 min. Horizontal pinch zoom from ×1 (whole night) to ×480 (1 minute visible),
one-finger panning, double-tap = back to ×1. Y never zooms.

**Y axis.** Amplitude **relative to the noise floor**, dimensionless: `×1, ×2, ×4, ×8, ×16, ×32`, a
**log₂** scale, axis floor at ×0.5. Justification: the useful amplitudes spread from 1.5× to 30×; on a
linear scale the small events are invisible, on a log scale they stay legible and the threshold at ×8
becomes a constant straight line, which makes the detector's logic immediately understandable. The axis
title is written out in full: `amplitude ÷ noise floor (log₂ scale)`. A
`log₂ / linear` switch exists in the chart options; on the linear scale the axis starts at 0 and the top
is never truncated.
A value beyond ×32 is not cut off: the axis extends to the next ×2. If a single sample forces the scale
to double, it is extended anyway and annotated (`peak ×61 at 03:12`) — never silent clipping.

**Decimation.** 8 h × 50 Hz = 1.44 M samples for ~1 100 pixel columns. A
**min/max pyramid** (successive levels of factor 2, `FloatArray` of pairs) is built once at load time,
off the main thread. When drawing, the level giving ≈ 1 pair per column is chosen and one vertical
`min→max` segment is drawn per column via `drawPoints(PointMode.Lines, prebuiltFloatArray)`. Never a mean:
a 40 ms peak must survive the whole night being displayed (P5, unit-tested).

**Interaction.** Long press or drag → vertical cursor + readout strip (§2.6). The cursor is
shared with the hypnogram. A tap on a marker opens an event sheet:
`CLM #143 · 02:14:38 · duration 2.4 s · amplitude ×9.2 · floor 0.0031 g · series 12, position 3/6 · N2`.

### 4.2 Trend chart

The most important chart. It must read in two seconds and stand up to a physician's scrutiny.

**X axis — calendar, not ordinal.** The nights are positioned at their real date. A week without a
measurement leaves a visible gap; that is information, not a defect. Ticks: every day if ≤ 14 days,
otherwise every Monday. Labels `12/03` in short format.

**Y axis — PLMI, start at 0 mandatory.** Maximum = `max(20, ceil(1.15 × highest value / 5) × 5)`.
Ticks every 5/h.

**Layers** (back → front):

1. **15/h reference line**: dashed `dash 6, 4`, `textTertiary`, labelled `15/h` on the right. A note in
   the legend, outside the chart: `Threshold used in clinical practice in adults (ICSD-3).` The 5/h and
   10/h lines are available as an option, in a lighter dotted style, not displayed by default.
2. **95 % CI band of the median**: horizontal rectangle covering the whole period, `accent` at 14 %,
   top and bottom borders dashed 1 dp. It is the representation of the uncertainty level chosen in §3.
3. **Median line**: solid horizontal stroke 2 dp, `accent`, across the extent of the period, with the
   value written on the right (`22/h`).
4. **Per-night points**:
   - eligible night: filled disc 5 dp, `accent`;
   - eligible night but accel mask only: filled disc 5 dp `accent` **with an amber ring** of 1.5 dp;
   - excluded night: hollow circle 5 dp, `textTertiary`, positioned at its value, **excluded** from the
     computations — its visual presence is deliberate: hiding the failed nights would distort the
     overall reading.
   The points are **not** joined by a line. A polyline between nights suggests a continuous trajectory
   and a causality that do not exist. If a monotone trend is really visible, it will be visible
   from the position of the points.
5. **Life-event markers** (optional, entered by the user): fine vertical line over the full
   height + a chip at the bottom (`treatment 150→225 mg`). Useful for comparing periods; never
   interpreted by the app.

**Comparison mode**: two median + CI bands side by side, separated by a vertical line at the pivot
date, each labelled `A` / `B`. The difference is written as text under the chart, never drawn as
an arrow (a downward arrow reads as "things are better").

**Interaction**: tap on a point → the night's `ModalBottomSheet` (date, PLMI, TST, flags, button
`Open the detail`). No zoom: the period is chosen with the picker, not by pinching.

### 4.3 Hypnogram

Placed directly under the night chart, same width, same X transform, height 96 dp.

**Lanes (top to bottom)**:

| Lane | Height | Content |
|---|---|---|
| Accel mask | 12 dp | two states: `moving` / `still`, solid `textTertiary` bar on still |
| HC hypnogram | 72 dp | 5 levels: Awake, REM, N1, N2, N3 (from top to bottom) |
| Disagreement | 6 dp | segments where the two masks diverge, amber hatching |

**Discrete Y**, in the conventional order of sleep laboratories (awake at the top, deep sleep at the
bottom). Drawn as a staircase (`drawPath` with horizontal + vertical segments), 2 dp stroke, optional
filling of the steps up to the awake line at 10 % opacity.

**REM**: it is the only stage that deserves a distinct visual treatment (few PLMS are expected there). It
is distinguished by **a diagonal hatch** in its step, not only by a colour, and by its lane
label. The hatching is drawn with `clipRect` + a `drawLine` loop, not with a stroke `PathEffect`.

**Without a hypnogram**: the HC lane is not drawn empty; it is replaced by a band of the same
height, `surfaceMuted`, carrying the centred text
`Hypnogram unavailable — accelerometric immobility mask used.` The "disagreement" lane disappears.

**Legend**: under the hypnogram, a row of chips with shape + label (never colour dots
alone). A statistics line: `6 h 58 of sleep · awake 48 min · REM 1 h 24 · N3 1 h 02 ·
source Samsung Health via Health Connect · agreement with the accel mask: κ = 0.71`.

### 4.4 Colours, contrast, accessibility of the charts

- **Dark mode by default** (`isSystemInDarkTheme()` ignored at first launch: Pendulum forces dark and
  leaves a `System / Dark / Light` setting). Reason: consultation at night and in the morning, often in
  the dark.
- **Chart colour roles** — four hues maximum, all distinguishable under deuteranopia and
  protanopia (blue / amber / purple / grey; no red-green pair):

| Role | Dark | Light | Use |
|---|---|---|---|
| Main data | `#6FB2FF` | `#1E63C8` | envelope, points, median |
| Quality / attention | `#E0A33E` | `#8A5D0B` | flags, disagreement, degraded mask |
| Second signal | `#8E7BEF` | `#4F3FB8` | REM, second rule overlaid |
| Structural neutral | `#7C8695` | `#666F7D` | floor, axes, excluded nights |

- Contrasts checked against the chart background (`#0E1116` / `#FFFFFF`): all these values are at
  ≥ 4.4:1, beyond the 3:1 requirement for graphical elements.
- **Mandatory redundancy**: solid / dashed / dotted stroke for the three lines of the night chart;
  filled disc / ringed disc / hollow circle for the three night states; vertical position for the
  stages; hatching for REM and for the gaps.
- **Acceptance test**: convert every screenshot to greyscale and check that nothing is lost.
- **Font scale**: the axis labels stay in `sp` and follow `fontScale` up to 1.3; beyond that,
  the tick density is halved rather than letting the labels overlap.
- **TalkBack**: the Canvas is `mergeDescendants` with a synthetic description + the `Values` button
  which gives the full table, the only path really usable without sight.

---

## 5. Visual system

Register: **sober and clinical**. A measuring instrument, not a fitness app. No score, no badge, no
streak of consecutive days, no congratulation, no illustration, no emoji in the interface. The only
"goal" the app encourages is to record enough nights, and it is represented by factual dots,
not by a gauge.

### 5.1 Palette

**Dark (default)**

| Role | Hex | Use |
|---|---|---|
| `background` | `#0E1116` | screen background |
| `surface` | `#161A21` | cards |
| `surfaceElevated` | `#1E232C` | modal sheets, menus |
| `surfaceMuted` | `#12161C` | inactive bands in the charts |
| `outline` | `#2A313C` | card borders, separators |
| `textPrimary` | `#E6EAF0` | titles, figures |
| `textSecondary` | `#A3ADBB` | body, secondary values |
| `textTertiary` | `#7C8695` | captions, axes, disabled |
| `accent` | `#6FB2FF` | data, primary actions |
| `onAccent` | `#0B1017` | text on a filled button |
| `attention` | `#E0A33E` | degraded quality, provisional |
| `secondSignal` | `#8E7BEF` | REM, second rule |
| `error` | `#E5736A` | **technical** failure only |
| `success` | `#5FBF8C` | successful transfer **only** |

**Light (and export)**

| Role | Hex |
|---|---|
| `background` | `#F7F8FA` |
| `surface` | `#FFFFFF` |
| `surfaceElevated` | `#FFFFFF` |
| `surfaceMuted` | `#EEF1F5` |
| `outline` | `#D8DDE4` |
| `textPrimary` | `#12161C` |
| `textSecondary` | `#4C5663` |
| `textTertiary` | `#666F7D` |
| `accent` | `#1E63C8` |
| `onAccent` | `#FFFFFF` |
| `attention` | `#8A5D0B` |
| `secondSignal` | `#4F3FB8` |
| `error` | `#B3352C` |
| `success` | `#1F7A4D` |

**Strict semantic rule**: `error` and `success` never describe a health result. A high PLMI
is not red. A low PLMI is not green. These two colours serve only the technical states
(transfer, permission, pairing).

### 5.2 Typography

Family: **Roboto Flex** (variable, provided by the system on Wear/Pixel), fallback `Roboto`. Tabular
figures enabled everywhere a value can change:
`fontFeatureSettings = "tnum"` — mandatory, otherwise the counters that increment jitter.

| Style | Size / weight | Line height | Use |
|---|---|---|---|
| `metricXL` | 44 sp / 300 / tnum / −0.5 | 48 | trend median, **a single use in the app** |
| `metricL` | 26 sp / 400 / tnum | 32 | section values |
| `titleL` | 22 sp / 500 | 28 | screen titles |
| `titleM` | 17 sp / 500 | 24 | card titles, dates |
| `body` | 15 sp / 400 | 22 | running text |
| `bodyEmph` | 15 sp / 500 | 22 | first lines of a verdict |
| `label` | 13 sp / 500 / +0.3 | 18 | chips, table headers |
| `caption` | 12 sp / 400 | 16 | captions, notices |
| `mono` | 13 sp monospace | 18 | technical log, raw values |

Line length of the explanatory paragraphs: capped at 60 characters by `widthIn(max = 340.dp)` — the
explanation blocks are long, they must stay readable.

### 5.3 Spacing, shapes, density

- Scale: `4 · 8 · 12 · 16 · 24 · 32 · 48` dp. Nothing else. Screen margin 16 dp, space between cards
  12 dp, internal card padding 16 dp.
- Radii: card 14 dp, field/chip 10 dp, button 10 dp, modal sheet 20 dp at the top.
  **No pill-shaped buttons**: the pill shape reads "consumer".
- **No shadows.** In dark, a card is defined by `surface` + a 1 dp `outline` border. In light,
  a 1 dp border as well, without elevation. This guarantees that a screenshot and the PDF look alike.
- Density: comfortable. List row height 72 dp (two lines of text + chips). Touch target
  48 dp minimum everywhere.
- **No content entry animation.** Navigation transitions: `tween(150)` fade only, no
  sliding. No animation of a figure counting up, no chart that draws itself progressively — an
  animated chart read at 7 in the morning is an illegible chart, and a curve that "climbs" tells a story.
  The only animated indicators allowed: the transfer and analysis progress bars.

### 5.4 Material 3: what is taken, what is set aside

**Taken**: `MaterialTheme` as the token carrier, `Scaffold`, `TopAppBar` (the `small` variant,
non-collapsing), `NavigationBar`, `Card`, `ListItem`, `AssistChip`/`FilterChip`, `Slider`,
`SegmentedButton`, `ModalBottomSheet`, `Snackbar`, `LinearProgressIndicator`, `AlertDialog`, the 48 dp
touch targets, the accessibility semantics.

**Set aside, and why:**

| Set aside | Reason |
|---|---|
| **Dynamic color / Material You** | The chart colours must be identical from one device to another and between screen and PDF. A screenshot shown to a physician cannot depend on the wallpaper. |
| **FAB** | There is no creation action on the phone. Recording starts on the watch. |
| **Stacked tonal elevation** | Illegible in dark, does not survive printing. Replaced by a 1 dp border. |
| **Pill-shaped buttons (`fullyRounded`)** | Consumer register. 10 dp radius. |
| **Badges, notification counters** | No content that has to be claimed urgently. |
| **`NavigationBar` with 4-5 items** | Three destinations are enough; more dilutes the hierarchy. |
| **Pronounced ripple, shared transitions** | Distraction; conflicts with "no animation". |
| **Sliders with the value in a floating bubble** | The algorithm parameters are displayed in plain text under the slider, with the unit. |
| **Filled icons (`Filled`)** | The `Outlined` set only, more sober and visually lighter. |

---

## 6. Watch interface

### 6.1 Single screen

A single `RecordScreen`, no navigation, no swipe, no list, no complication.
Compose for Wear (`androidx.wear.compose.material`), pure black background `#000000` (OLED: black does
not light the pixel — that is battery life, not style).

**STOPPED state** — full-screen button:
```
        ┌─────────────────┐
        │                 │
        │      START      │      circular Chip or button
        │                 │      height 88 dp, text 20 sp
        └─────────────────┘

        Battery 98 %
        Free 1.2 GB
        Right ankle · 4th hole
```
If battery < 60 %: the line turns amber, `Battery 54 % — not enough for a full night.`
The button stays active: it is the user who decides.

**RECORDING state**:
```
        03:14                     duration, 28 sp tabular

        564 300 samples
        50.2 Hz  ·  0 gaps
        Battery 71 %
        Batched FIFO mode 20 s
        Worn ✓

        ┌─────────────────┐
        │       STOP      │      secondary button, outlined
        └─────────────────┘
```
Stopping asks for a confirmation (`Stop the recording?` / `Stop` / `Continue`): an accidental
press at 3 in the morning loses the night.

**Refresh: exactly 30 s**, and only when the screen is on
(`LifecycleEventObserver` on `ON_RESUME`/`ON_PAUSE`). No recomposition in ambient mode.

### 6.2 Errors on the watch

Short texts (the screen is small and the user is lying down), always actionable:

| Situation | Text |
|---|---|
| Not enough space | `Not enough space. 60 MB left, 300 needed. Sync from the phone.` |
| Accelerometer unavailable | `Sensor unreachable. Restart the watch.` |
| Permission denied | `Physical activity permission required. Settings › Apps › Pendulum.` |
| Repeated gaps | `Signal interrupted 3 times. Switching to continuous mode — the battery will drain faster.` |
| Service killed and restarted | `Recording resumed after a 2 min interruption.` |
| Resumption after a reboot | `The watch restarted. Recording resumed, a gap is marked.` |
| Automatic stop | `Recording stopped: watch on the charger at 07:04.` |

### 6.3 Why no animation

Three reasons, in order of importance:

1. **Energy.** Every recomposition wakes the SoC. Over an 8 h night, a 60 fps animation, even a
   discreet one, costs more than the 50 Hz acquisition itself. The battery target (> 20 % after 8 h)
   does not survive an animated interface.
2. **Contamination of the measurement.** An interface that invites you to look at the screen invites you
   to move the leg. The watch screen is at the ankle: looking at it means bending over, therefore
   producing an artefact. The screen has to be boring on purpose.
3. **No benefit.** There is nothing to watch in real time. The figures displayed are checks
   that things are working, read at most twice a night.

Corollaries: no `AnimatedVisibility`, no `animate*AsState`, no indeterminate progress indicator
spinning, no vibration apart from the stop confirmation (a short `HapticFeedback`
at start and at stop — useful because the screen at the ankle is not necessarily visible).

---

## 7. Error states and exact copy

Mandatory format for every message: **a neutral title** (what happened) + **one sentence of cause** +
**one sentence of action** + **an action button** when an action exists. Never "Oops", never
"An error has occurred", never a code on its own.

Every error carries a stable identifier, present in the technical log and displayed in tertiary
`caption` under the message (the user is technical: the code is of use to him).

### Night and measurement

**`E-NIGHT-01` — No sleep data**
```
Night of 12 March — no sleep stages
No sleep session covering this night was found in Health Connect,
even 36 h after waking. Your wrist watch may not have recorded it
or may not have synchronised it.
Pendulum used its own immobility mask. The result is kept and counts
towards the trend, but it is flagged "accel mask": the sleep time is
then estimated, not measured.
                                        [ Retry ]   [ Choose another source ]
```

**`E-NIGHT-02` — Night too short**
```
Night of 12 March — excluded, 2 h 10 of sleep
Computing the number of movements per hour requires at least 4 h of sleep: over a
shorter duration, a few clustered movements are enough to blow the figure up.
This night can still be consulted in the detail, but it does not enter the trend.
Nothing to do.
```

**`E-NIGHT-03` — Watch out of battery during the night**
```
Night of 12 March — recording interrupted at 03:41
The watch stopped at 8 % battery, after 4 h 29 of recording out of the
7 h 40 spent in bed.
The recorded part is analysed and gives 4 h 12 of sleep, above the minimum
of 4 h: this night is therefore kept, with the "partial night" flag. As the
movements tend to concentrate in the second half of the night, the figure is
probably underestimated.
Charge the watch to 100 % before going to bed.
                                                             [ See the detail ]
```
If the recorded part is below 4 h, the message switches to `E-NIGHT-02` and the night is excluded.

**`E-NIGHT-04` — Gaps in the signal**
```
Night of 12 March — signal interrupted
The sensor stopped sending data for 4 min 12 in total, including one
break of 2 min 30 at 02:14. This happens when the system suspends the watch more
deeply than expected.
94.1 % of the night is usable — below the 97 % threshold. The night is excluded from
the trend so as not to bias it, and remains available to consult.
If this happens again, force continuous mode in Settings › Measurement. The battery
will last less long.
                                        [ Force continuous mode ]   [ Detail ]
```
Below 2 min of cumulative gaps and a largest gap < 5 s, the night stays eligible and the flag is purely
informative (`gap 47 s`), with no error card.

**`E-NIGHT-05` — Watch left on the table**
```
Night of 12 March — the watch does not appear to have been worn
The off-body sensor reports "not worn" over 87 % of the night, and the signal
stayed flat: 3 movements detected in 7 h, with a noise floor ten times
lower than the one of your other nights.
This night is excluded. A recording made on a bedside table produces a
PLMI close to zero that would pull your trend downwards.
Check that the watch is properly tightened at the ankle before starting.
                                              [ Keep it anyway as a control ]
```
The "keep as a control" option exists because this is exactly the negative control the validation
protocol calls for: the night is then labelled `negative control` and stays outside the trend.

**`E-NIGHT-06` — Saturated signal / loose strap**
```
Night of 12 March — abnormally high amplitudes
The noise floor is 4 times higher than the one of your other nights and 214
movements exceed the threshold, against 40 on average. A loose strap or a
different tightness point produces exactly this signature.
The night is excluded. The detection threshold adapts to the noise, but not to a
change of mechanics.
Go back to the usual reference: right ankle, 4th hole.
```

**`E-NIGHT-07` — Incomplete transfer**
```
Night of 12 March — incomplete transfer
15 files out of 17 were received. Bluetooth was cut off during the transfer.
The missing files are still on the watch: they are only deleted
once receipt has been confirmed.
Bring the phone closer to the watch and start again.
                                                       [ Resume the transfer ]
```

**`E-NIGHT-08` — Clock offset**
```
Night of 12 March — offset between the watch and the phone
The watch timestamps drift by 3 min 40 relative to the phone. The
cross-referencing with the hypnogram would be shifted by as much, which would move
movements from one sleep stage to another.
The movements are counted normally; the breakdown by stage is hidden for
this night. Restart the watch to resynchronise its clock.
```

### Devices and permissions

**`E-PAIR-01` — Watch not found**
```
Watch not found
No Wear OS device with Pendulum answered within 30 seconds.
Check that the watch is paired in the Watch app, that Bluetooth is on,
and that Pendulum is indeed installed on the watch.
                                                              [ Search again ]
```

**`E-HC-01` — No sleep source**
```
No sleep source found
Health Connect contains no sleep session over the last 7 days.
No app is writing your nights there, or the permission is denied.
You can continue: Pendulum will estimate sleep from the immobility measured at
the ankle. The result will be less precise and every night concerned will be flagged.
                            [ Open Health Connect ]   [ Continue without a hypnogram ]
```

**`E-HC-02` — Permission revoked**
```
Sleep access revoked
The sleep read permission has been withdrawn from Pendulum. The nights already analysed
are kept; the new ones will use the accel mask.
                                                     [ Restore the permission ]
```

**`E-HC-03` — Multiple conflicting sources**
```
Two sleep sources for this night
Samsung Health reports 6 h 58 of sleep, Sleep as Android 7 h 24. Pendulum cannot
combine them without risking counting the same minutes twice.
The preferred source (Samsung Health) was used.
                                                     [ Change the preferred source ]
```

### Analysis and storage

**`E-ANA-01` — Analysis failed**
```
Analysis impossible
The signal for the night of 12 March could not be reassembled: 9 files out of 17
have an invalid integrity check.
The raw data is kept. You can run the analysis again; if it fails
again, export the technical log.
                            [ Run the analysis again ]   [ Export the log ]
```

**`E-STO-01` — Not enough space on the phone**
```
Not enough space
120 MB are left on the phone; one night takes about 300 MB during
the analysis.
Purge the old raw signals: the results and the charts are kept,
only the raw signal is deleted.
                        [ Purge signals older than 90 days ]  ( frees 2.1 GB )
```

**`E-EXP-01` — Export impossible**
```
Export unavailable — 3 nights minimum
The selected period contains 2 eligible nights. A report built on fewer than
three nights would mislead the physician reading it.
Widen the period or record one more night.
```

### Cross-cutting rule

No message uses the word "error" when nothing is broken. A night without a hypnogram, a short night,
a night when the watch was not worn are **situations**, not failures: they are displayed in `attention`
(amber) and not in `error` (red). Red is reserved for what is really broken: transfer, permission, file
integrity, storage.

---

## 8. Compose implementation

### 8.1 Composable tree — module `phone`

```
com.pendulum.phone.ui
├─ PendulumApp()                                   NavHost + NavigationBar + Snackbar host
├─ theme/
│   ├─ PendulumTheme(darkTheme, content)           ColorScheme M3 + PendulumColors + PendulumTypography
│   ├─ PendulumColors                              data class of the roles outside M3 (attention, secondSignal…)
│   ├─ ChartTokens                                 chart colours/widths/dash, screen|print
│   ├─ LocalPendulumColors / LocalChartTokens      CompositionLocal
│   └─ PendulumShapes, PendulumTypography, Spacing
├─ common/
│   ├─ PendulumScaffold(title, actions, content)
│   ├─ PendulumCard(content)                       surface + 1dp border, no shadow
│   ├─ SectionHeader(text)
│   ├─ MetricHeadline(value, unit, interval, nightCount, statement)   ← the only use of metricXL
│   ├─ InlineValue(label, value, note)
│   ├─ QualityChip(flag)                           AssistChip + Outlined icon
│   ├─ QualityChecklist(items)                     section 4 of the night detail
│   ├─ BlockingState(title, body, footer)          refusal < 3 nights, comparison impossible
│   ├─ ErrorCard(error: PendulumError)             title + cause + action + code (§7)
│   ├─ StatusStrip(state: WakeState)               §2.3, one state at a time
│   ├─ DataTableSheet(columns, rows)               accessible alternative to the charts
│   └─ ConfirmDialog(...)
├─ onboarding/
│   ├─ OnboardingPager()
│   ├─ DisclaimerPage()   RequirementsPage()   PairingPage()
│   ├─ SleepSourcePage()  NotificationsPage()
├─ trend/
│   ├─ TrendScreen(state)                          start destination
│   ├─ TonightCard(state)                          visible 20:00–04:00 only
│   ├─ CollectionProgress(done, required)          filled/empty dots
│   ├─ TrendChart(spec, onNightClick)              ← non-trivial component no. 2
│   ├─ PeriodPicker(...)
│   └─ compare/ ComparePeriodsScreen(), ComparisonVerdict(result)
├─ nights/
│   ├─ NightListScreen(...)  NightRow(...)
│   └─ detail/
│       ├─ NightDetailScreen(...)
│       ├─ NightSignalChart(spec, transform, onCursor)   ← non-trivial component no. 1
│       ├─ Hypnogram(spec, transform, onCursor)          ← non-trivial component no. 3
│       ├─ CursorReadout(sample)
│       ├─ EventBreakdown(...)  EventTableSheet(...)
│       └─ ParamsPanel(profile, onRecompute)
├─ quiz/  ScreeningQuizScreen(), QuestionPage(), QuizOutcome()
├─ settings/ SettingsScreen(), DeviceSection(), DataSection(), LogScreen()
└─ export/ ExportScreen(), ExportPreview(), ReportRenderer (outside Compose, PdfDocument)
```

**State**: one `ViewModel` per screen, exposing a single sealed `StateFlow<XxxUiState>`
(`Loading | Blocked(reason) | Ready(data) | Failed(error)`). The screen composables take only
`state` + lambdas; no Room/Health Connect access from the UI. The chart `Spec`s are computed in the
ViewModel (and therefore testable without a screen): the composable only draws.

### 8.2 Theme

```kotlin
@Composable
fun PendulumTheme(
    mode: ThemeMode = ThemeMode.Dark,     // dark by default, not isSystemInDarkTheme()
    render: RenderTarget = RenderTarget.Screen,
    content: @Composable () -> Unit,
) {
    val dark = mode == ThemeMode.Dark ||
               (mode == ThemeMode.System && isSystemInDarkTheme())
    val pendulum = if (dark) PendulumColors.Dark else PendulumColors.Light
    CompositionLocalProvider(
        LocalPendulumColors provides pendulum,
        LocalChartTokens provides ChartTokens.of(pendulum, render),
        LocalSpacing provides Spacing,
    ) {
        MaterialTheme(
            colorScheme = pendulum.toMaterialScheme(),   // no dynamicColorScheme()
            typography  = PendulumTypography,
            shapes      = PendulumShapes,
            content     = content,
        )
    }
}
```

`RenderTarget.Print` forces the light palette and widths in points: the export reuses exactly the
same drawing functions.

### 8.3 The three non-trivial components

Common principle, to be respected for all three: **the drawing is an extension function of `DrawScope`,
not a composable.** That makes it callable from a Compose `Canvas` and from a PDF canvas, and testable
by image capture.

```kotlin
fun DrawScope.drawNightChart(spec: NightChartSpec, t: ChartTokens, x: XTransform)
fun DrawScope.drawHypnogram(spec: HypnogramSpec, t: ChartTokens, x: XTransform)
fun DrawScope.drawTrendChart(spec: TrendChartSpec, t: ChartTokens)
```

`XTransform` (scale + offset + bounds, hoisted into the parent) is shared between the night chart and the
hypnogram: that is what guarantees the alignment of the axes and of the cursor.

#### (1) `NightSignalChart` — 1.44 M points

**Data structure.** An `EnvelopePyramid` built once, off the main thread, in the
ViewModel:

```kotlin
class EnvelopePyramid(base: FloatArray) {          // RMS envelope at the measured fs
    // level k: minmax[k] = FloatArray(2 * ceil(n / 2^k)), [min0, max0, min1, max1, …]
    val levels: List<FloatArray>                    // ~19 levels for 1.44 M
    fun levelFor(columnCount: Int): Int
    fun readInto(level: Int, from: Int, to: Int, out: FloatArray)
}
```
Bottom-up construction: level k+1 aggregates the pairs of level k with `min`/`max`. Cost O(2n),
memory ≈ 2 × the base in `Float` (≈ 11 MB for 8 h — acceptable, otherwise a `ShortArray` quantised in log₂).

**Drawing.** For `w` pixel columns, `level = levelFor(w)` is chosen, a pre-allocated (`remember`)
`FloatArray` of `4 × w` coordinates `x, yMin, x, yMax` is filled, and
`drawPoints(points, PointMode.Lines, color, strokeWidth)` is called once. **Zero allocation per frame**,
zero `Path` for the heaviest layer.

Noise floor and threshold (sampled at ~1 Hz, a few thousand points): the `Path` is rebuilt
only when `XTransform` changes, memoised by `remember(spec, transform)`.

Event markers: one `drawLine` per visible event, with an index sorted by time and a binary
search over the visible window — never a loop over the full 412 events.

Axis text: `rememberTextMeasurer()` + a memoised `Map<String, TextLayoutResult>` cache (measuring text
per frame is the first bottleneck of a Compose Canvas).

**Gestures.**
```kotlin
Modifier.pointerInput(Unit) {
    detectTransformGestures { centroid, pan, zoom, _ ->
        transform.applyPinch(centroid.x, pan.x, zoom)   // X only, clamped 1f..480f
    }
}.pointerInput(Unit) {
    detectTapGestures(
        onDoubleTap = { transform.reset() },
        onTap = { hitTestEvent(it)?.let(onEventClick) },
    )
}.pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { onCursor(transform.timeAt(it.x)) },
        onDrag = { c, _ -> onCursor(transform.timeAt(c.position.x)) },
    )
}
```
`XTransform` is a `@Stable` with `mutableFloatStateOf`: only the Canvas layer is invalidated, not the
hierarchy.

#### (2) `TrendChart` — few points, heavy semantic load

Little data (≤ 60 nights), so no performance problem: all the work is in the correctness.

- Y bounds computed by a tested pure function: `yMax = max(20f, ceil(1.15f * maxValue / 5f) * 5f)`,
  `yMin = 0f` **hard-coded, not configurable** (P5 — it is a product invariant, not an option).
- CI band: a translucent `drawRect` + two dashed `drawLine`s via
  `PathEffect.dashPathEffect(floatArrayOf(6f, 4f))`.
- Points: filled `drawCircle` / `drawCircle(style = Stroke(1.5.dp))` for the excluded nights /
  filled + `drawCircle(style = Stroke)` in `attention` for the accel mask.
- **No polyline between points** — the absence of a `Path` joining the points is a design decision
  to be commented in the code, otherwise a future contributor will "fix" what he takes for an oversight.
- X position: `x = padStart + (date - firstDate) / (lastDate - firstDate) * plotWidth`, hence calendar:
  the missing nights hollow out a visible void.
- Hit test: search for the nearest point by Euclidean distance, acceptance radius 24 dp, returning
  the `sessionId` to the callback.

#### (3) `Hypnogram` — discrete staircase, two lanes, strict alignment

- Five lanes of equal height; `laneY(stage) = top + stageOrder(stage) * laneHeight`.
  `stageOrder`: `AWAKE=0, REM=1, LIGHT/N1=2, N2=3, DEEP/N3=4`.
- The staircase path is built **only once per data set** (`remember(spec)`), in normalised
  `0..1` coordinates on X, then transformed at draw time by `withTransform { scale/translate }` — zooming
  never rebuilds the `Path`.
- REM hatching: `clipRect(rect)` around each REM step then a loop
  `for (i in -h..w step 6.dp) drawLine(from = Offset(i, h), to = Offset(i + h, 0f))`. A `PathEffect` on the
  stroke does not produce a hatched fill; you have to clip and loop.
- The "accel mask" lane (12 dp) and the "disagreement" lane (6 dp) are drawn as sequences of merged
  `drawRect`s: the contiguous intervals are pre-merged in the `Spec` to avoid thousands of
  30 s rectangles.
- The cursor is the same object as the night chart's; the hypnogram handles no gesture, it receives
  `cursorTimeMs` as a parameter. A single owner of the interaction avoids misalignments.

#### Tests expected on these three components

- `EnvelopePyramidTest`: a single-sample peak survives every level (P5).
- `TrendScaleTest`: `yMin == 0` whatever the data; `yMax ≥ 20`.
- `PositionStatementTest`: the five branches of §3.4, including the exact bounds `ciLow == 15`.
- `BootstrapCiTest`: fixed seed → the same interval over 100 runs.
- Capture tests (Roborazzi or `captureToImage`) on the three charts, in dark and in light, with a
  reference data set; greyscale comparison to validate P6.

---

## 9. What is still to be settled

- **The PDF format** assumes that the physician will accept a non-standard document. To be validated with
  him before investing in the fine layout.
- **The min/max pyramid in `Float`** costs ~11 MB of heap per open night. If profiling shows memory
  pressure, quantise into a `ShortArray` (log₂ × 512); do not optimise before measuring.
- **The number of nights needed** displayed in the comparison rests on a simulation from the
  observed dispersion, itself estimated on few nights. It is an order of magnitude, and the text must say
  so — check that it is never read as a promise.
