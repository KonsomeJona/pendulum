# Play Store metadata

Store listing text and screenshots, in the layout Fastlane's `supply` expects, so that a listing can
be assembled without retyping anything. Nothing here uploads on its own — see below for why that is
deliberate.

```
fastlane/metadata/android/<locale>/
  title.txt              30 characters maximum
  short_description.txt  80 characters maximum
  full_description.txt   4000 characters maximum
  changelogs/<code>.txt  500 characters maximum, named after versionCode
  images/phoneScreenshots/
  images/wearScreenshots/
```

Locales: `en-US`, `fr-FR`. The French is written, not translated — the interface is French, and a
machine-translated listing above a hand-written interface reads exactly like what it is.

## Publishing is not automated, on purpose

The release workflow builds and signs the two AABs and attaches them to a GitHub release. It does not
push them to Play. Two reasons, and neither is laziness:

**A health application does not get published by a cron job.** Play requires a Health apps
declaration form, and the answers are a legal commitment about intended use. Someone has to read the
questions and answer them.

**This one probably should not be on Play at all in its current form.** Sideloading is outside the
scope of the EU Medical Device Regulation, which applies to placing a device on the market. A store
listing is placing it on the market. An application that produces a screening-oriented figure about a
sleep disorder plausibly falls under Annex VIII rule 11 as a class IIa device, and a disclaimer does
not change that — the regulator judges the claimed purpose, not the small print. The metadata here is
prepared because the work of writing it is real and worth keeping; using it is a separate decision,
and one that needs an answer about regulatory status first.

If that answer ever comes, the upload is:

```bash
fastlane supply --aab pendulum-phone-<version>.aab --track internal
```

## Store listing constraints already handled

- Both titles are under 30 characters, both short descriptions under 80. Checked, not assumed —
  the first drafts were over.
- Every listing states, in its first lines, what the application is (a screening aid that produces a
  real measurement) and what it is not (a medical device, an official health application, a
  diagnosis). Both halves are near the top, not at the bottom: Play truncates the full description in
  the list view, and a qualification below the fold is one nobody reads.
- The screenshots are real captures of the running application, not renderings. They come from
  `docs/images/screenshots/`, which is the same set the documentation shows.

## What is still missing before a listing could go live

- A **feature graphic**, 1024×500, which Play requires and which does not exist.
- An **application icon**, 512×512. The current launcher icon is a placeholder.
- A **privacy policy at a public URL**. The content is already written — the application declares no
  `INTERNET` permission and can therefore open no connection at all — but Play needs it hosted.
- The **Health apps declaration form**, discussed above.
- **Data safety** answers, which are unusually simple here: no data collected, none shared, none
  transmitted, because the application cannot transmit anything.
