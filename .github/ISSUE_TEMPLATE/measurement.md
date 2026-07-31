---
name: A measurement
about: You measured something the project needs and cannot measure for itself
title: 'Measurement: '
labels: measurement
---

<!--
This is the most valuable kind of issue this project can receive, and it is listed first
deliberately. Several quantities the algorithm depends on are simply absent from the published
literature — nobody has measured them, so they were guessed, and the guesses are marked as such in
docs/03-algorithm.md.

The open ones, from docs/references.md:

  - How much movement a mattress transmits to an ankle sensor. Unquantified anywhere.
  - Whether breathing is visible at the ankle at all. Measured at the chest only.
  - Whether a given person's movements alternate between legs, and whether that is stable from one
    night to the next. This one decides whether the tracked metric works at all: a unilateral
    sensor sees a doubled interval when movements alternate.
-->

**What you measured**

**How** — device, placement, sampling rate, duration, and anything about the setup that would change
the result if someone repeated it.

**What you found** — numbers, with their spread. Raw data attached if you can.

**What you think it means for the algorithm**, if anything. A measurement with no interpretation is
still worth having.
