# Pendulum documentation

> **Pendulum** measures periodic limb movements in sleep from a smartwatch worn at the ankle, and
> tracks the *rhythm* between them rather than their number. It measures; it does not
> interpret — a real measurement to take to a physician, not a diagnosis and not a medical device — see
> [what this is, and what it is not](../README.md#what-this-is-and-what-it-is-not).


For a visual introduction with an interactive demonstration, see the
[project site](https://konsomejona.github.io/pendulum/). This directory is the detailed version.

Pendulum estimates the rhythm and rate of periodic limb movements in sleep from a consumer smartwatch
worn at the ankle. It measures rather than interprets — a real measurement to take to a
physician, and not a diagnosis or a medical device. The project README
states the limits; these documents state the reasoning.

## The documents

| Document | Who should read it, and why |
|---|---|
| [`01-overview.md`](01-overview.md) | Anyone. What the project is, how a night flows through it end to end, which decisions were taken and why, and what exists today. Start here. |
| [`02-science.md`](02-science.md) | A curious reader, or a sleep physician. What periodic limb movements are, how they are scored in a laboratory, why Pendulum tracks a rhythm rather than an hourly count, and what an ankle accelerometer can and cannot see. It also lists, explicitly, what is not known. |
| [`03-algorithm.md`](03-algorithm.md) | Anyone implementing or auditing the signal chain. Every stage from raw acceleration to published index: formulas, default parameters, rejected alternatives, the sleep mask and the circularity problem, and a fully worked numeric example. |
| [`04-architecture.md`](04-architecture.md) | Anyone building or modifying the software. Module layout, the watch capture service, the binary chunk format, the transfer protocol, and what survives an interrupted night. |
| [`05-devices.md`](05-devices.md) | Anyone choosing hardware, or wondering why a second device is required. Why the raw sensor rules out most wearables, and how the sleep period is obtained through Health Connect. |
| [`06-interface.md`](06-interface.md) | Anyone working on the phone application. Interface principles, screens, data visualisation, and the rules that keep uncertainty legible rather than compressed into one large coloured number. |
| [`07-validation.md`](07-validation.md) | Anyone evaluating whether the method is sound. The adversarial review: the defects found, the ways the number can be wrong while looking credible, and what would have to be measured to trust it. |
| [`08-screens.md`](08-screens.md) | Anyone who wants to see what the interface actually looks like. Real screenshots of the running application on emulators, with the three defects that only a real render exposed — and the reasoning behind each screen's shape. |
| [`09-release.md`](09-release.md) | Anyone installing a build, or cutting the next release. What is in a release, why both halves must come from the same one, and the signing setup. |
| [`references.md`](references.md) | Full bibliography, with the access status of each source — read in full, abstract only, or cited second-hand. |

## Suggested reading orders

**To understand what is measured** — `01-overview.md`, then `02-science.md`, then `07-validation.md`.
The first tells you what happens to a night, the second what the numbers mean, the third why you
should not over-read them.

**To build or modify the software** — `01-overview.md`, then `04-architecture.md`, then
`03-algorithm.md`, then `05-devices.md` and `06-interface.md` as the work requires. Read
`02-science.md` before changing any clinical rule or threshold; several constants that look
arbitrary are not.

**To judge whether the method is sound** — `02-science.md` first, then `07-validation.md`, then
`03-algorithm.md` §5 on the sleep mask and §6 on the rhythm estimator, then `references.md` to check
the sources yourself. The sections you want are the ones headed *what is genuinely unknown* and
*confidence*.

## On the French documents

[`fr/`](fr/) holds the original working documents. They are considerably more detailed than these
English ones: they carry the full parameter tables, the energy calculations, the arithmetic behind
each decision, and the things that were checked and turned out to be wrong.

| French document | English derivative |
|---|---|
| [`fr/SPEC-v2.md`](fr/SPEC-v2.md) | `01-overview.md` |
| [`fr/ALGO-v2.md`](fr/ALGO-v2.md) | `02-science.md`, `03-algorithm.md` |
| [`fr/ARCHI-CAPTURE.md`](fr/ARCHI-CAPTURE.md) | `04-architecture.md` |
| [`fr/SOURCES-SOMMEIL.md`](fr/SOURCES-SOMMEIL.md) | `05-devices.md` |
| [`fr/UX.md`](fr/UX.md) | `06-interface.md` |
| [`fr/REVUE-CRITIQUE.md`](fr/REVUE-CRITIQUE.md) | `07-validation.md` |
| [`fr/BANC-ESSAI.md`](fr/BANC-ESSAI.md) | **None.** The bench log: every measurement taken on real hardware, and §14 the screen-by-screen review of both devices. It has no English derivative and it is the most frequently updated document in the repository. |
| [`fr/SPEC-v1.md`](fr/SPEC-v1.md) | Historical. Superseded by `fr/SPEC-v2.md` wherever the two conflict. |

### Which one is authoritative, by subject

This used to read "where they disagree, the French text is correct and the English one has a bug".
That rule is no longer true, and the repository says so in three places on its own: `06-interface.md`
records a substantive change made *after* `fr/UX.md`, `08-screens.md` states that three strings "are
not in the specification — `fr/UX.md` predates the decision and never wrote the screen", and the
colour values of `fr/UX.md` §4.4 would fail the contrast test that the English values pass.

Authority is by subject, not by language:

| Subject | Authoritative document | Why |
|---|---|---|
| Algorithm parameters, clinical rules, energy budgets | `fr/ALGO-v2.md`, `fr/ARCHI-CAPTURE.md` | The code cites them by name for its parameter tables |
| Interface, screens, navigation, states | `06-interface.md` (English) | `fr/UX.md` predates the decisions and says so |
| Scope, guard rails, roadmap, status | `01-overview.md` (English) | `fr/SPEC-v2.md` lacks the rewritten §5 |
| Bench, real hardware, screen review | `fr/BANC-ESSAI.md` | Only document; no derivative |
| Bibliography | `references.md` | Single list; `03-algorithm.md` must not keep a parallel one |
