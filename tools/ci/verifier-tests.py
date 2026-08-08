#!/usr/bin/env python3
"""Décide si une exécution de tests est un succès, à partir des rapports XML de Gradle.

**Pourquoi ce fichier existe.** Ce contrôle vivait en trois copies : deux dans `ci.yml` (job JVM
et job Android) et une dans `release.yml`. Elles faisaient la même chose et avaient déjà divergé
sur les détails qui comptent — le glob, le format du nom d'un test en échec, le message d'erreur.
Trois copies d'une garde, c'est trois occasions de corriger la garde à un seul endroit ; et c'est
précisément l'absence de la garde dans la copie de `release.yml` qui avait laissé une publication
partir sans que ses tests aient tourné.

**Ce que la garde protège.** Le code de retour de Gradle ne suffit pas : une étape de CI qui le
neutralise (`|| true`, `continue-on-error`) pour aller lire les rapports laisse passer une erreur
de **compilation** — dans ce cas Gradle n'écrit aucun rapport, la liste d'échecs sort vide, et
l'absence de résultat se lit comme un succès. D'où la règle non négociable ci-dessous : **zéro
rapport est un échec**, jamais un succès silencieux.

Usage :
    verifier-tests.py [glob ...]

Sans argument, le glob par défaut couvre **toutes** les variantes de tâche de test
(`test`, `testDebugUnitTest`, `testReleaseUnitTest`, variantes de saveur…). Un glob nommant une
seule variante est exactement ce qui avait laissé plus de deux cents tests hors CI sans que rien
ne le dise. On ne passe un glob explicite que pour **restreindre** le périmètre d'un job dont on
sait qu'il ne produit qu'une partie des rapports.
"""

import glob
import sys
import xml.etree.ElementTree as ET

GLOB_PAR_DEFAUT = "*/build/test-results/*/*.xml"


def main(argv: list[str]) -> int:
    motifs = argv[1:] or [GLOB_PAR_DEFAUT]

    chemins = sorted({p for motif in motifs for p in glob.glob(motif)})

    total = 0
    echecs: list[str] = []
    for chemin in chemins:
        racine = ET.parse(chemin).getroot()
        total += int(racine.get("tests", 0))
        for cas in racine.iter("testcase"):
            if cas.find("failure") is not None or cas.find("error") is not None:
                echecs.append(f"{cas.get('classname')}.{cas.get('name')}")

    # La garde. Zéro rapport ne veut pas dire zéro échec : cela veut dire que rien n'a tourné,
    # et c'est le cas où continuer serait le plus grave — c'est celui d'une release non testée.
    if total == 0:
        print(f"Aucun rapport de test pour {' '.join(motifs)} —", file=sys.stderr)
        print(
            "la compilation a échoué avant d'arriver aux tests, ou le périmètre est vide.",
            file=sys.stderr,
        )
        return 1

    print(f"{total} tests, {len(echecs)} failure(s), {len(chemins)} report(s)")
    for e in echecs:
        print(f"  {e}")

    # Aucun test n'est toléré rouge. L'exception nommée `T6` qui a existé ici n'existe plus
    # depuis que son dénominateur a été redéfini (docs/07-validation.md §4.1) ; ne pas la
    # réintroduire sous une autre forme.
    return 1 if echecs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
