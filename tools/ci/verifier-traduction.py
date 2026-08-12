#!/usr/bin/env python3
"""Three mechanical checks on the French -> English translation.

It does not judge the quality of a sentence — no script can. It catches what a human proofread
misses precisely because it is boring: a French word forgotten in the middle of an English
paragraph, a false friend translated word for word, a KDoc cross-reference to a symbol that no
longer exists under that name.

Output: one line per finding, non-zero exit code if any blocking one remains.

### Two checks that block, one that advises

`FR` and `ACC` are decidable: a French word or an accent in a repository that has decided to write
without accents is a defect, full stop. They fail the run.

`AMI` is not. "pattern", "range" and "control" are legitimate English words, and this repository
uses them by the hundred; no script can tell the correct occurrence from the mistranslation. It is
therefore printed for proofreading and **does not fail the run**. Making it blocking would mean
either two hundred tolerance entries or an always-red check — and an always-red guard rail is a
guard rail nobody reads any more, which is how a test is lost.

### The tolerances table

Some French is deliberate and permanent: preference keys persisted on users' devices, log markers
quoted word for word in a dated notebook. [TOLERANCES] is where those exceptions live, each with
**its reason**. The table is checked in both directions — a tolerance with no reason is refused,
and a tolerance that no longer matches anything is reported, so that the table cannot rot into a
licence to ignore everything.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# ---------------------------------------------------------------------------
# 1. Is any French left?
#
# The words kept here are frequent in French and rare or non-existent in technical English.
# "pour", "dans", "est" would catch far too many false positives on proper nouns; these are safe.
#
# This list is French **by construction**: it is data, not prose. Emptying it to make the tool pass
# over itself would disarm the one check that keeps French from coming back.
# ---------------------------------------------------------------------------
FRENCH_WORDS = [
    "parce que", "c'est-a-dire", "c'est a dire", "n'est pas", "il faut",
    "chaque", "aucun", "aucune", "toujours", "jamais de", "plutot",
    "donc ", "ainsi que", "lorsque", "afin de", "au lieu de", "meme si",
    "qui ne", "que le ", "que la ", "des que", "tant que", "alors que",
    "nuit ", "nuits ", "montre ", "sommeil", "reglage", "ecran ",
]

# Accents are a surer signal than words: this repository writes without accents by convention, so
# their presence flags untreated text. Accented characters here are data too, for the same reason.
ACCENTS = re.compile(r"[àâäéèêëîïôöùûüçœÀÂÄÉÈÊËÎÏÔÖÙÛÜÇŒ]")

# ---------------------------------------------------------------------------
# 2. False friends: the word-for-word translation of a term whose meaning is something else.
#
# An occurrence cannot be proven wrong — "pattern" is a legitimate English word. These are flagged
# for proofreading, they do not fail the run, and the report must say which ones were judged good.
# ---------------------------------------------------------------------------
FALSE_FRIENDS = {
    r"\bpattern\b": "motif = reason (why a night is excluded), not pattern",
    r"\bcontrol\b(?!ler|led|s\b)": "controle = check (value/threshold/state), not control",
    r"\brange\b": "portee = scope; check this is not a coroutine scope",
    r"\balarm\b": "reveil = waking (the morning), not alarm",
    r"\bdoor\b": "porte P1 = gate, not door",
    r"\bbank\b": "banc = bench, not bank",
    r"\bcensus\b": "recensement = inventory, not census",
    r"\bactual\b": "actuel = current; \"actual\" is rarely what was meant",
    r"\beventually\b": "eventuellement = possibly, not eventually",
    r"\bsensible\b": "sensible = significant / noticeable",
}

# ---------------------------------------------------------------------------
# 3. What is allowed to stay in French, and why. The key is `FileName.ext:excerpt`.
#
# Same shape as the `tolerances` map of `DurationsInventoryTest`, and for the same reason: a
# generated baseline has no room for the **why**, and the why is the only thing that lets a later
# reader decide whether the exception still holds.
#
# Three families here, and a single rule to tell a real exception from laziness: **what a third
# party already reads cannot be renamed.** A preference key has been persisted on users' devices; a
# marker has been quoted in a dated log. Translating either would not translate anything, it would
# destroy a reading.
# ---------------------------------------------------------------------------
TOLERANCES = {
    # Persisted on users' devices. Renaming them would silently erase their settings.
    "Preferences.kt:source_sommeil_preferee":
        "DataStore key persisted on user devices; renaming it would erase their sleep source",

    # Bench markers, quoted word for word in the dated log. See tools/banc/TRAPS.md.
    "BancDataLayer-phone.kt:BANC_P1_NUIT":
        "log marker quoted verbatim in BENCH-LOG.md 12.6; renaming it would falsify the log",
    "BancDataLayer-phone.kt:BANC_NUIT hex=":
        "log marker quoted verbatim in BENCH-LOG.md 12.6; renaming it would falsify the log",
    "sonde_transfert.py:MONTRE n=":
        "sample label quoted verbatim in BENCH-LOG.md 11.4, where the ordering was measured",
    "transfert.sh:TRANSFERT_ETAT_ECRAN_MONTRE":
        "marker read by the remote caller, ssh not propagating exit codes; frozen interface",
    "transfert.sh:TRANSFERT_ETAT_CHUNKS_MONTRE":
        "marker read by the remote caller, ssh not propagating exit codes; frozen interface",

    # The dated log itself: it records what was typed and what came out, in the language of the day.
    "BENCH-LOG.md:UICTL_FAIL aucun element":
        "captured output of a tool run on a dated day; a log is a trace, it is not rewritten",
    "BENCH-LOG.md:MONTRE n=":
        "captured probe output, dated; the sample lines are the measurement itself",
    "BENCH-LOG.md:exception=aucune":
        "captured output of BANC_LIAISON_APPEL, dated",
    "BENCH-LOG.md:BANC_P1_NUIT":
        "captured output of the P1 gate probe, dated",
    "BENCH-LOG.md:qu'aucun paquet ne definit":
        "quoted French sentence of the 11.5.6 diagnosis, kept as written on the day",
    "BENCH-LOG.md:Arrêt automatique":
        "French UI string quoted as evidence of the very defect the row reports as corrected",
}

# `.claude/` carries the assistant's session logs, untracked by git: these are not product sources
# and translating them would make no sense.
EXCLUDED = ("/build/", "/.git/", "/.gradle/", "captures-ui/", "artefacts-test/", "/.claude/")

# Prose lives in Kotlin, Markdown and Gradle files, but also in the bench scripts and the CI
# workflows — leaving those out would have made "zero findings" true and meaningless for a third of
# the repository.
SUFFIXES = ("**/*.kt", "**/*.md", "**/*.kts", "**/*.py", "**/*.sh", "**/*.yml", "**/*.yaml")


def files() -> list[pathlib.Path]:
    out = []
    for pattern in SUFFIXES:
        for p in ROOT.glob(pattern):
            s = str(p)
            if any(x in s for x in EXCLUDED):
                continue
            if p.name == "GLOSSARY-TRANSLATION.md" or "verifier-traduction" in p.name:
                continue
            out.append(p)
    return sorted(out)


def tolerance_for(path: pathlib.Path, line: str) -> str | None:
    """The tolerance key covering this line, or `None`.

    Matched on the file **name** and not on its full path: a tolerance follows the file when it
    moves, which is what one wants of a reason attached to a content.
    """
    for key in TOLERANCES:
        name, excerpt = key.split(":", 1)
        if path.name == name and excerpt in line:
            return key
    return None


def main() -> int:
    blocking: list[str] = []
    advisory: list[str] = []
    tolerated = 0
    used: set[str] = set()

    # A tolerance with no written reason is refused. Without this, the table would accept a bare
    # key — that is to say a silencing with nothing to reread later, which is exactly the baseline
    # this repository refuses.
    unexplained = sorted(k for k, reason in TOLERANCES.items() if not reason.strip())
    for key in unexplained:
        blocking.append(f"TOL  {key}  tolerance with no reason written")

    for p in files():
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        rel = p.relative_to(ROOT)

        for i, line in enumerate(text.splitlines(), 1):
            lower = line.lower()

            found = None
            for word in FRENCH_WORDS:
                if word in lower:
                    found = f"FR   {rel}:{i}  « {word.strip()} » — {line.strip()[:90]}"
                    break
            if ACCENTS.search(line):
                accented = f"ACC  {rel}:{i}  accent — {line.strip()[:90]}"
            else:
                accented = None

            if found or accented:
                key = tolerance_for(p, line)
                if key is not None:
                    used.add(key)
                    tolerated += 1 + (1 if found and accented else 0)
                else:
                    blocking += [x for x in (found, accented) if x]

            for rx, reason in FALSE_FRIENDS.items():
                if re.search(rx, lower):
                    advisory.append(f"AMI  {rel}:{i}  {reason}")
                    break

    # The other direction of the check: a tolerance that no longer matches anything means the code
    # has changed underneath it, and that nobody knows any more what it was protecting. Reported,
    # and blocking — otherwise the table rots and becomes a licence to ignore everything.
    for key in sorted(set(TOLERANCES) - used - set(unexplained)):
        blocking.append(f"TOL  {key}  tolerance matching nothing any more — reread or remove it")

    for t in blocking:
        print(t)

    if advisory:
        print(f"\n--- {len(advisory)} false friend(s) to proofread (not blocking) ---")
        for t in advisory:
            print(t)

    print(
        f"\n{len(blocking)} blocking finding(s), {tolerated} tolerated, "
        f"{len(advisory)} to proofread"
    )
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
