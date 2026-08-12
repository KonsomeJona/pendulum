# Translation glossary — Pendulum

Binding for every agent working on the French-to-English pass. If a term is not
here and you must invent one, say so in your report so the others can follow.

## False friends — these have burned projects before

| French | English | NOT |
|---|---|---|
| `motif` | `reason` (why a night is excluded) | ~~pattern~~ |
| `controle` | `check` (a quality check with value/threshold/state) | ~~control~~ |
| `portee` | `scope` (coroutine scope) | ~~range~~, ~~reach~~ |
| `reveil` | `waking` / `wake` (the morning, not an alarm) | ~~alarm~~ |
| `porte` (P1) | `gate` | ~~door~~ |
| `banc` | `bench` (test bench) | ~~bank~~ |
| `recensement` | `inventory` | ~~census~~ |
| `repere` (de serrage) | `reference` (strap-hole reference) | ~~marker~~, ~~landmark~~ |
| `sensible` | `significant` / `noticeable` | ~~sensible~~ |
| `actuel` | `current` | ~~actual~~ |
| `eventuellement` | `possibly` | ~~eventually~~ |

## Domain terms

| French | English |
|---|---|
| nuit / nuits | night / nights |
| cle de nuit | night key |
| soir / soiree | evening |
| accueil | home |
| tendance | trend |
| reglages | settings |
| ecran | screen |
| carte | card |
| bande | band |
| graphe | chart |
| dessin / dessiner | drawing / draw |
| apercu | preview |
| montre | watch |
| jambe | leg |
| sommeil | sleep |
| sante | health |
| scellement / sceller | sealing / seal |
| demarrage / demarrer | start |
| effacement / effacer | erasure / erase |
| rejeu | replay |
| publication / publier | publication / publish |
| duree / durees | duration / durations |
| echelle de temps | time scale |
| horloge | clock |
| seuil | threshold |
| mesure / mesurer | measurement / measure |
| mesureur | measurer |
| etat | state |
| situation | situation |
| libelle | label |
| texte | text |
| chemin de calcul | computation path |
| ordre | order |
| eligible | eligible |
| ecartee | excluded |
| provisoire | provisional |
| fiabilite | reliability |
| questionnaire | questionnaire |
| assistant (premier lancement) | onboarding |
| derogation | override |
| garde-fou | guard rail |
| bloqueur | blocker |
| avertissement | warning |

## Rules that matter more than the table

1. **Translate the reasoning, not the words.** These comments explain *why* a
   decision was taken, often with a measurement or a past failure. That
   explanation is the most valuable thing in the repository. A paraphrase that
   keeps the sentence shape and loses the argument is a regression, even if it
   reads well.
2. **Keep every number, unit, citation, PMID, file path, commit hash and
   identifier reference exactly as it is.** If a comment says "1,06:1 mesure",
   the English must carry the same figure.
3. **Keep the register.** Sober, precise, no marketing, no enthusiasm. This
   product refuses to claim what it cannot support; its prose does too.
4. **No emoji**, anywhere. Not in code, comments, tests, or docs.
5. Backtick test names become English sentences that still describe the
   invariant, not the mechanism: `` `une nuit non analysee n'est pas hors P1` ``
   becomes `` `an unanalysed night is not outside P1` ``, not `` `test P1 null` ``.
6. When a comment references another file or symbol by name, and that name is
   being renamed by another agent, use the **new** English name from this
   glossary.
