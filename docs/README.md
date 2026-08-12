# Pendulum documentation

> **Pendulum** measures periodic limb movements in sleep from a smartwatch worn at the ankle, and
> tracks the *rhythm* between them rather than their number. It measures; it does not
> interpret — a real measurement to take to a physician, not a diagnosis and not a medical device — see
> [what this is, and what it is not](../README.md#what-this-is-and-what-it-is-not).

**This page is the entrance.** Every other file in this directory answers one question well and
refers you elsewhere for the rest; this one exists to tell you which door is yours, and to say what
you will find behind it before you spend the click.

If you are not an engineer, the [project site](https://konsomejona.github.io/pendulum/) is written
for you and this directory is not. It has an interactive demonstration and no code. Come back here
when you want the reasoning.

**What these documents are.** Almost all of them are *explanation*: not a manual for operating the
software, but the record of why it computes what it computes and why it refuses to compute the rest.
That is deliberate. A number that may reach a consultation has to carry its reasoning with it, and
the reasoning does not fit in a tooltip. So expect arguments, rejected alternatives, and long
passages about what is not known. The only two files that behave like ordinary documentation are
[`09-release.md`](09-release.md), which is a procedure, and [`05-devices.md`](05-devices.md) §6,
which is a runbook for one evening's verification.

---

## Which door is yours

### You have restless legs, or someone you sleep beside does

Start with the [project site](https://konsomejona.github.io/pendulum/), then, if you want the
substance:

1. [`02-science.md`](02-science.md) — what these movements are, how a sleep laboratory scores them,
   and why this application tracks the interval between them instead of counting them per hour. It
   ends with a section headed *what is genuinely unknown*, which is the honest part.
2. [`09-release.md`](09-release.md) §1 — what to install, and the **second device** you will need
   for sleep timing. One sensor cannot honestly measure both the movements and the sleep they happen
   in, and [`05-devices.md`](05-devices.md) §5 ranks the options, including the one that costs
   nothing because you probably already own it.

**Read before installing:** the *Limits* section of the [project README](../README.md). Not a
formality. The number this produces is on a different scale from a laboratory one, and
[`02-science.md`](02-science.md) §4 explains why that is a property of ankle geometry rather than a
defect to be fixed in a later version.

### You are a physician, or you were handed an exported report

1. [`02-science.md`](02-science.md) — what is claimed, what is inferred, and which figures in this
   project come from a paper that was read in full rather than cited second-hand. The table headed
   *which numbers in this project are inferences rather than citations* is the one to check first.
2. [`07-validation.md`](07-validation.md) — the adversarial account of the testing. Its §4.3 is the
   one that matters: the rhythm estimator, measured under the miss rate the detector actually
   produces, is wrong by 11 % where it answers at all and refuses to answer on 18 synthetic nights
   out of 20. **Nothing here has been validated against polysomnography, and no night of real sleep
   has ever been recorded with this software.**
3. [`references.md`](references.md) — every source with its access status: read in full, abstract
   only, or known second-hand. Entries marked *none* are where this project is weakest.

### You want to read or change the code

1. [`01-overview.md`](01-overview.md) — the problem, one night end to end, the decisions and their
   reasons, the guard rails, and §5, which states plainly that the plan was departed from and that
   the hardware gate P1 has still not been passed.
2. [`04-architecture.md`](04-architecture.md) — module layout, the watch capture service, the binary
   chunk format byte by byte, the transfer protocol, and what survives a night that is interrupted.
3. [`03-algorithm.md`](03-algorithm.md) — every stage from raw acceleration to published index, with
   a fully worked numeric example in §7 and the parameter tables in §8. Read
   [`02-science.md`](02-science.md) first if you intend to touch a clinical rule: several constants
   that look arbitrary are not.
4. [`06-interface.md`](06-interface.md) and [`08-screens.md`](08-screens.md) as the work requires —
   the first for the rules the interface obeys, the second for what it currently looks like on real
   hardware.
5. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) before opening a pull request. It carries the
   contributor licence agreement, the three rules that are not negotiable in `algo`, and a short list
   of changes that will be refused whatever their quality.

### You want to judge whether the method holds

1. [`02-science.md`](02-science.md), then [`07-validation.md`](07-validation.md).
2. [`03-algorithm.md`](03-algorithm.md) §5 on the sleep mask and the circularity problem, and §6 on
   the rhythm estimator. Those two sections carry the load.
3. [`references.md`](references.md), to check the sources rather than take this project's word for
   them.

The sections you want are the ones headed *what is genuinely unknown*, *confidence*, and *the limits
of all this*. They exist because burying uncertainty in a footnote is how a plausible number becomes
a wrong decision.

### You are trying to reproduce a measurement on real devices

[`workings/BENCH-LOG.md`](workings/BENCH-LOG.md) is the bench log: every command run on real
hardware with the output it produced, dated, including the several occasions on which a proposed fix
turned out to be wrong. It is the most frequently changed document in the repository and nothing
condenses it. Its §9 and §12.7 hold the commands, reusable as they stand.

---

## The documents, one line each

| Document | Who should read it, and why |
|---|---|
| [`01-overview.md`](01-overview.md) | Anyone. What the project is, how a night flows through it end to end, which decisions were taken and why, and what exists today. Start here for anything technical. |
| [`02-science.md`](02-science.md) | A curious reader, or a sleep physician. What periodic limb movements are, how they are scored in a laboratory, why Pendulum tracks a rhythm rather than an hourly count, and what an ankle accelerometer can and cannot see. It lists, explicitly, what is not known. |
| [`03-algorithm.md`](03-algorithm.md) | Anyone implementing or auditing the signal chain. Every stage from raw acceleration to published index: formulas, default parameters, rejected alternatives, the sleep mask and the circularity problem, and a fully worked numeric example. |
| [`04-architecture.md`](04-architecture.md) | Anyone building or modifying the software. Module layout, the watch capture service, the binary chunk format, the transfer protocol, and what survives an interrupted night. |
| [`05-devices.md`](05-devices.md) | Anyone choosing hardware, or wondering why a second device is required. Why the raw sensor rules out most wearables, and how the sleep period is obtained through Health Connect. |
| [`06-interface.md`](06-interface.md) | Anyone working on the phone application. Interface principles, screens, data visualisation, and the rules that keep uncertainty legible rather than compressed into one large coloured number. |
| [`07-validation.md`](07-validation.md) | Anyone evaluating whether the method is sound. The adversarial review: the defects found, the ways the number can be wrong while looking credible, and what would have to be measured to trust it. |
| [`08-screens.md`](08-screens.md) | Anyone who wants to see what the interface actually looks like. Screenshots from emulators and from real devices, with the defects that only a real render exposed — and the reasoning behind each screen's shape. |
| [`09-release.md`](09-release.md) | Anyone installing a build, or cutting the next release. What is in a release, why both halves must come from the same one, and the signing setup. |
| [`references.md`](references.md) | Full bibliography, with the access status of each source — read in full, abstract only, or cited second-hand. |

---

## The working documents

[`workings/`](workings/) holds the documents the decisions were actually made in. They are
considerably more detailed than the numbered files above: the full parameter tables, the energy
calculations, the arithmetic behind each decision, and the things that were checked and turned out to
be wrong. The numbered files are condensations — shorter, current, and readable in order; the files
in `workings/` are the workings.

They were written in French and translated in August 2026. The translation moved the language and
nothing else: where a working document is out of date, contradicts a numbered file, or reaches a
conclusion later measurement overturned, it still does, and the table below says which text to act on.
A document that was wrong in French is wrong in English.

| Working document | What it is | Condensed into |
|---|---|---|
| [`workings/SPEC-v2.md`](workings/SPEC-v2.md) | The decision log: what changed from v1, why, and where the detail lives | `01-overview.md` |
| [`workings/ALGO-v2.md`](workings/ALGO-v2.md) | The algorithm specification, parameter tables, synthetic generator | `02-science.md`, `03-algorithm.md` |
| [`workings/CAPTURE-ARCHITECTURE.md`](workings/CAPTURE-ARCHITECTURE.md) | Watch capture, transfer protocol, energy budget, Wear OS traps | `04-architecture.md` |
| [`workings/SLEEP-SOURCES.md`](workings/SLEEP-SOURCES.md) | Choosing and verifying the sleep source; Health Connect integration | `05-devices.md` |
| [`workings/UX.md`](workings/UX.md) | Interface design: principles, screen-by-screen reasoning, the exact copy | `06-interface.md` |
| [`workings/CRITICAL-REVIEW.md`](workings/CRITICAL-REVIEW.md) | The adversarial review of v1: 38 defects, 9 paths to self-deception | `07-validation.md` |
| [`workings/BENCH-LOG.md`](workings/BENCH-LOG.md) | The bench log on emulators and on real devices, with §14 the screen-by-screen review | **Nothing.** No condensed counterpart, and the most frequently updated file here |
| [`workings/SPEC-v1.md`](workings/SPEC-v1.md) | **Historical.** The initial plan, kept as a trace of the starting point | None. Superseded throughout; it carries a warning at its head |

### Which document makes the decision, and on what

This table used to read "where they disagree, the French text is correct and the English one has a
bug" — back when the two differed by language and the French was assumed to be the original.
That has not been true for some time, and the repository says so in three places on its own:
[`06-interface.md`](06-interface.md) records a substantive change made *after* `workings/UX.md`;
[`08-screens.md`](08-screens.md) states that three strings "are not in the specification — `workings/UX.md`
predates the decision and never wrote the screen"; and the colour values of `workings/UX.md` §5.1 would
fail the contrast test that the values in [`06-interface.md`](06-interface.md) pass.

Authority is by subject, and by nothing else — not by which file is longer, not by which came first.
**This is the only such table in the repository**, and it should stay that way —
`workings/SPEC-v2.md` carried a second one that had drifted into contradicting this one, which is
precisely the failure this project has paid for more than once.

| Subject | Authoritative document | Why |
|---|---|---|
| Algorithm parameter values, clinical rules | [`workings/ALGO-v2.md`](workings/ALGO-v2.md) §6 | The code cites these tables by name |
| Algorithm derivations, the rhythm estimator, the worked example | [`03-algorithm.md`](03-algorithm.md) | The estimator the product tracks has no section in `workings/` at all; §8.7 is its only parameter table |
| Capture, transfer protocol, energy budgets | [`workings/CAPTURE-ARCHITECTURE.md`](workings/CAPTURE-ARCHITECTURE.md) | The wire schemas and the mAh arithmetic are there |
| The binary chunk format as it stands today | [`04-architecture.md`](04-architecture.md) §3 | The only document that states the current byte layout; the working documents state only the deltas |
| Measured test results | [`07-validation.md`](07-validation.md) §4 | Measurements exist in no working document, and several of them falsify one |
| Interface, screens, navigation, states | [`06-interface.md`](06-interface.md) | `workings/UX.md` predates the decisions and says so |
| Interface copy, palette, spacing, chart conventions | [`workings/UX.md`](workings/UX.md) §4, §5, §7 | The exact strings and hex values exist nowhere else |
| Scope, guard rails, roadmap, status | [`01-overview.md`](01-overview.md) | `workings/SPEC-v2.md` §4 still presents the plan as followed; §5 here records that it was not |
| Sleep source, Health Connect integration | [`workings/SLEEP-SOURCES.md`](workings/SLEEP-SOURCES.md) §6 | 13 explicitly unverified items, tracked one by one in its §7 |
| Bench, real hardware, screen review | [`workings/BENCH-LOG.md`](workings/BENCH-LOG.md) | Only document; nothing condenses it |
| Bibliography | [`references.md`](references.md) | Single list. Nothing else may keep a parallel one |

---

## Where the documents are known to disagree

*Reviewed 12 August 2026.* Listed rather than quietly fixed, because most of these are not typing
errors: they are places where one document learned something and the other was never told, and the
repair is a decision rather than an edit. Each line says which text to act on. Where the divergence
is now **flagged in place**, the older document carries a note at the exact paragraph — the reader
who lands there from a search engine has to be warned there, not here.

| Divergence | Act on |
|---|---|
| `workings/ALGO-v2.md` §5.5 commissions T6 (`F1 ≥ 0.90`, index error ≤ 0.10). Its denominator was changed after measurement, T22 was added to publish the cost, and T12 was never written at all. *Flagged in place* | [`07-validation.md`](07-validation.md) §3 and §4.1 |
| `workings/SPEC-v2.md` §5.3 states the harmonic deconvolution is "well posed even at `p = 0.39`". Measured on the model's own best case, it returns 2 valid fits out of 20. *Flagged in place* | [`07-validation.md`](07-validation.md) §4.3 |
| `workings/UX.md` §2.6 specifies per-night parameter sliders and a per-night recompute. That is the exact artefact the third guard rail exists to prevent | [`01-overview.md`](01-overview.md) §4 |
| `workings/UX.md` §2.2 has no evening seal; `workings/SPEC-v2.md` §1 attributes the seal gate to `workings/CAPTURE-ARCHITECTURE.md` §5, which does not contain it. The seal is specified in the numbered files only | [`06-interface.md`](06-interface.md) §2.2 |
| `workings/UX.md` §6.1, §2.8 and `workings/CAPTURE-ARCHITECTURE.md` §3.4, §5.3 give three different battery warning thresholds and two different automatic-stop times | [`04-architecture.md`](04-architecture.md) §2.6 for the stop conditions; the watch thresholds are genuinely unsettled |
| `workings/UX.md` §2.6 and §7 quote quality thresholds (97 %, 5 s, 120 s) that the adversarial review showed to be mutually inconsistent | [`01-overview.md`](01-overview.md) §5, phase P1 |
| `workings/SLEEP-SOURCES.md` §4 concluded that the absence of Health Connect "is not blocking"; `workings/SPEC-v2.md` §2.3 adjudicated the point and the ruling had never been carried back. *Now quoted in place* | `workings/SPEC-v2.md` §2.3 |
| `workings/ALGO-v2.md` §3.5 proposed gating the report behind an apnoea screen. Apnoea screening was removed from the project, and the reason is worth reading. *Flagged in place* | [`02-science.md`](02-science.md), *Pendulum does not screen for sleep apnoea* |
| `workings/BENCH-LOG.md` §14.7 is a dated list of twelve screen defects. Five are fixed; one looks fixed from its commit messages and is not | `workings/BENCH-LOG.md` §14.9 |

Two rules keep this list from growing back. **One bibliography** — a source goes in
[`references.md`](references.md) and nowhere else, because two lists is how the Ferri 2016 citation
came to be published with two different volume numbers. **One authority table** — the one above.

---

## What is not documented here

Stated so that silence is not read as coverage.

- **No night of real sleep has been recorded.** Every runtime figure in
  [`04-architecture.md`](04-architecture.md) is an engineering estimate, and the hardware gate P1
  has not been passed. [`01-overview.md`](01-overview.md) §5 is explicit about it.
- **There is no user manual.** [`09-release.md`](09-release.md) §1 covers installation and first run;
  beyond that the interface is meant to be legible without one, which is a claim nobody has tested on
  a second person.
- **The Compose implementation guide exists in one place only**, [`workings/UX.md`](workings/UX.md)
  §8 — and that is the file which is otherwise superseded on interface questions.
- **Nothing here is a clinical document.** Restless legs syndrome is diagnosed on five criteria
  concerning waking symptoms. No leg sensor changes that, and no document in this directory should
  be read as though it might.
