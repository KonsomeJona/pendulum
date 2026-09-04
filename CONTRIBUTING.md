# Contributing to Pendulum

Contributions are welcome. Before you spend time on a change, please read this — in particular the
section on the Contributor Licence Agreement, which is unusual enough that you should know about it
before, not after, opening a pull request.

## Contributor Licence Agreement

Pendulum is dual-licensed: AGPL-3.0 for everyone, and a separate commercial licence for anyone who wants
to use it without the AGPL's obligations. Offering that second licence requires holding the rights
to the entire work. If a contribution arrives under the AGPL alone, that part of the code can no
longer be included in a commercially licensed build — and the dual-licensing model breaks for the
whole project.

So contributions require a CLA. You **keep your copyright**; you grant the maintainer a perpetual,
worldwide, irrevocable licence to sublicense your contribution under other terms, including
proprietary ones. This is the same arrangement used by Qt, Grafana, and most dual-licensed projects.

A bot will ask you to accept it on your first pull request.

If you would rather not sign, that is entirely reasonable — open an issue describing the change and
it can be reimplemented independently. Bug reports, measurements, and reviews need no agreement at
all, and are just as valuable.

## What is most useful

In rough order of value to the project:

1. **Real measurements.** The specification lists several quantities that are simply absent from the
   published literature: how much a mattress transmits movement to an ankle sensor, whether
   breathing is visible at the ankle at all, and whether a given person's movements alternate
   between legs from one night to the next. Two control nights are worth more than any amount of
   further reading.
2. **Corrections to the clinical rules.** The AASM v3 numbers used here were triangulated from
   secondary sources because the manual is paywalled. The Periodicity Index has two contradictory
   formulations circulating in its own author's publications. If you have the primary texts and the
   implementation is wrong, that is the most valuable bug report possible.
3. **Independent implementations of the detector**, to compare against. Divergence between two
   honest implementations is information.
4. Code, in the usual sense.

## Ground rules for code

- **`algo` depends on nothing.** No Android, no `format`, no third-party library. Its input is the
  `SampleBlock` interface. This is what makes it testable against synthetic signals, and it is not
  negotiable.
- **Pure functions, explicit sampling rate, no wall clock, no un-seeded randomness.** The whole
  module must be reproducible bit-for-bit from the same input. That is a rule the module is written
  to, not a property anything asserts end to end: T13, the determinism row of the non-regression
  table, is not written (`docs/07-validation.md` §3). What is checked is one stage of it —
  `ImmobilityMaskTest` builds the same mask twice and compares. So the rule is yours to keep; no
  test will catch you breaking it.
- **Parameters come from the tables in `docs/03-algorithm.md` §8 (or `docs/workings/ALGO-v2.md` §6, the authoritative original)**, with their published justification.
  A parameter without a reason recorded next to it will be questioned.
- **Every clinical rule cites its source.** AASM v3 and WASM 2016 differ in ways that matter — most
  importantly in how a series is broken — and code that silently picks one is a defect.
- Comments explain *why*, not *what*. Match the density of the surrounding code, which is
  deliberately heavy on rationale and light on restating the obvious. Comments are in English,
  throughout.

## Tests

```bash
./gradlew :format:test :algo:test
```

That is the fast half. CI also runs the Android modules' own unit tests —
`:wear:testDebugUnitTest`, `:phone:testDebugUnitTest`, `:sleepwriter:testSourceADebugUnitTest` and
the two release-variant guard rails — and it requires **each of those tasks to have executed at
least one test**, so a test source set that no longer compiles fails the job by its own empty
report instead of passing under cover of the hundreds of tests the other modules did run. The
instrumented tests run on top of that, on an emulator.

A change to the detector must come with a regression case in `algo/src/test/kotlin/com/pendulum/algo/regression/`. The
suite is built on a synthetic night generator with injected ground truth, precisely so that
detector changes can be judged against something other than intuition.

Note that ground truth comes in **two** label sets: `emgTruth`, everything generated, and
`accelTruth`, the subset mechanically visible to an ankle sensor. Detector metrics are scored
against `accelTruth`. Scoring against `emgTruth` would make the target unreachable for reasons that
have nothing to do with the algorithm.

## What will be refused

- Anything that presents the estimate as a diagnosis, or compares it to the 15/h clinical threshold
  as though the two were the same quantity. They are not; the reasoning is in `docs/02-science.md`.
- Per-night adjustable parameters in the interface. Parameters are global, versioned by a hash, and
  a change re-scores every night from the raw data. The alternative is a machine for finding the
  result you expected.
- Analytics, crash reporting, or any network dependency in the phone application. The app declares
  no `INTERNET` permission, and the absence of that permission is a verifiable guarantee in a way
  that a privacy policy is not.
