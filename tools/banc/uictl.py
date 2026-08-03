#!/usr/bin/env python3
"""Pilotage d'ecran par dump-et-tap, pour le banc instrumente.

Pourquoi ce script plutot qu'un `input tap` a des coordonnees en dur : la fenetre de
consentement de Health Connect et l'assistant d'appairage changent de disposition selon la
version du module et la densite de l'ecran. Un tap a l'aveugle qui rate ne produit **aucune
erreur** — il produit un echec plus loin, pour une raison sans rapport. Le dump rend le geste
deterministe et verifiable : on peut redumper apres le tap et confirmer que l'ecran a change.

Usage :
    uictl.py <serie> dump                     # ecrit l'arbre sur la sortie standard
    uictl.py <serie> find  <motif>            # rend les elements dont le texte/id contient le motif
    uictl.py <serie> tap   <motif>            # tape au centre du premier element trouve
    uictl.py <serie> presse <motif> [ms]      # appui long (defaut 900 ms)
    uictl.py <serie> wait  <motif> [timeout]  # attend l'apparition d'un element (defaut 30 s)
    uictl.py <serie> shot  <fichier.png>      # capture d'ecran
    uictl.py <serie> cocher [n]               # coche les n premieres cases non cochees

Le motif est cherche, sans distinction de casse, dans les attributs `text`,
`content-desc` et `resource-id`.

Code de sortie 0 si trouve, 1 sinon. Chaque commande emet un marqueur sur la sortie standard
(`UICTL_OK` / `UICTL_FAIL`) : ssh via Tailscale ne propage pas les codes de sortie, donc
l'appelant distant doit lire le marqueur et non `$?`.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.join(
    os.path.expanduser("~"), "Library/Android/sdk/platform-tools/adb"
)
if not os.path.exists(ADB):
    ADB = "adb"

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(serial, *args, binary=False):
    cmd = [ADB, "-s", serial] + list(args)
    out = subprocess.run(cmd, capture_output=True, timeout=120)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def dump(serial):
    """Rend l'arbre d'interface. uiautomator ecrit parfois sur /sdcard, parfois sur stdout
    selon la version : on tente d'abord la voie fichier, qui est la plus fiable."""
    adb(serial, "shell", "rm", "-f", "/sdcard/uictl.xml")
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/uictl.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/uictl.xml")
    if "<hierarchy" not in xml:
        xml = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty")
    start = xml.find("<hierarchy")
    return xml[start:] if start >= 0 else ""


def motif_exact(pattern):
    """Un motif prefixe de `=` demande une egalite, pas une inclusion.

    Appris sur l'ecran d'enregistrement de la montre : `presse STOP` a attrape
    « Long press to stop », qui contient le mot, qui est au-dessus du bouton et qui n'est pas
    cliquable. L'appui long s'est execute sans erreur et n'a rien fait — le mode de defaillance
    habituel de cet ecran. La priorite « texte exactement egal » du tri ne suffit pas quand le
    vrai bouton est **sous le pli** et donc absent du dump : le tri classe ce qu'il a, et ce
    qu'il a est le mauvais element. Avec `=STOP`, l'element est introuvable tant qu'on n'a pas
    fait defiler, ce qui est la verite.
    """
    return (pattern[1:], True) if pattern.startswith("=") else (pattern, False)


def nodes(xml, pattern):
    """Elements dont le texte, la description ou l'identifiant contient le motif."""
    found = []
    brut, exact = motif_exact(pattern)
    pat = brut.lower()
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return found
    for node in root.iter("node"):
        attrs = [node.get(a, "") for a in ("text", "content-desc", "resource-id")]
        hay = " ".join(attrs).lower()
        if (any(a.strip().lower() == pat for a in attrs) if exact else pat in hay):
            m = BOUNDS.match(node.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            found.append(
                {
                    "w": x2 - x1,
                    "h": y2 - y1,
                    "text": node.get("text", ""),
                    "desc": node.get("content-desc", ""),
                    "id": node.get("resource-id", ""),
                    "clickable": node.get("clickable", "false"),
                    "checkable": node.get("checkable", "false"),
                    "checked": node.get("checked", ""),
                    "cls": node.get("class", ""),
                    "cx": (x1 + x2) // 2,
                    "cy": (y1 + y2) // 2,
                    "bounds": node.get("bounds", ""),
                }
            )
    return found


def area(n):
    return n["w"] * n["h"]


def describe(n):
    return (
        f"text={n['text']!r} desc={n['desc']!r} id={n['id']!r} "
        f"clickable={n['clickable']} bounds={n['bounds']} centre=({n['cx']},{n['cy']})"
    )


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    serial, cmd = sys.argv[1], sys.argv[2]
    arg = sys.argv[3] if len(sys.argv) > 3 else None

    if cmd == "dump":
        print(dump(serial))
        print("UICTL_OK")
        return 0

    if cmd == "shot":
        data = adb(serial, "exec-out", "screencap", "-p", binary=True)
        with open(arg, "wb") as f:
            f.write(data)
        print(f"UICTL_OK {arg} {len(data)} octets")
        return 0

    if cmd == "clic":
        # Les elements reellement actionnables. A utiliser quand `tap <texte>` echoue :
        # dans Compose, l'etiquette d'une case a cocher est un noeud distinct et non
        # cliquable, et taper dessus ne coche rien sans produire la moindre erreur.
        found = nodes(dump(serial), "")
        for n in found:
            if n["clickable"] == "true" or n["checkable"] == "true":
                print(f"{n['cls']} coche={n['checked']} {describe(n)}")
        print("UICTL_OK")
        return 0

    if cmd == "cocher":
        # Coche les cases une par une, en redumpant entre chaque : les bornes se decalent des
        # qu'une case cochee change la hauteur d'un bloc, et un lot de taps calcules sur un seul
        # dump tape a cote a partir de la deuxieme. Prealable non negociable, appris en §7.4 :
        # l'ecran doit etre reveille (`svc power stayon true`), sinon `input tap` s'execute sans
        # erreur et ne coche rien.
        want = int(arg) if arg else 1
        done = 0
        for _ in range(want * 3):
            if done >= want:
                break
            boxes = [
                n
                for n in nodes(dump(serial), "")
                if n["checkable"] == "true" and n["checked"] == "false"
            ]
            if not boxes:
                break
            n = boxes[0]
            adb(serial, "shell", "input", "tap", str(n["cx"]), str(n["cy"]))
            time.sleep(1.5)
            done += 1
        print(f"UICTL_OK {done} case(s) cochee(s)" if done else "UICTL_FAIL aucune case a cocher")
        return 0 if done else 1

    if cmd == "find":
        found = nodes(dump(serial), arg)
        for n in found:
            print(describe(n))
        print("UICTL_OK" if found else "UICTL_FAIL aucun element")
        return 0 if found else 1

    if cmd == "tap":
        found = nodes(dump(serial), arg)
        # Ordre de priorite, appris en tapant a cote : (1) texte exactement egal au motif —
        # sans quoi « Allow » attrape le titre « Allow Pendulum to send you notifications? »,
        # qui contient le mot et occupe la moitie de l'ecran ; (2) element cliquable ; (3) le
        # plus petit, car un bouton est plus petit que le conteneur qui le porte.
        pat = motif_exact(arg)[0].lower()
        found.sort(
            key=lambda n: (
                n["text"].strip().lower() != pat,
                n["clickable"] != "true",
                area(n),
            )
        )
        if not found:
            print(f"UICTL_FAIL motif absent: {arg}")
            return 1
        n = found[0]
        adb(serial, "shell", "input", "tap", str(n["cx"]), str(n["cy"]))
        print(f"UICTL_OK tap ({n['cx']},{n['cy']}) sur {describe(n)}")
        return 0

    if cmd == "presse":
        # Appui long, par un `swipe` sans deplacement : `input` n'a pas de verbe pour ca et
        # `motionevent DOWN/UP` ne produit rien sur les boutons Compose (§7.4). Le STOP de la
        # montre est deliberement un appui long suivi d'une confirmation — deux gestes, aucun
        # des deux involontaire — donc le banc doit savoir en produire un.
        duree = int(sys.argv[4]) if len(sys.argv) > 4 else 900
        found = nodes(dump(serial), arg)
        pat = motif_exact(arg)[0].lower()
        found.sort(
            key=lambda n: (
                n["text"].strip().lower() != pat,
                n["clickable"] != "true",
                area(n),
            )
        )
        if not found:
            print(f"UICTL_FAIL motif absent: {arg}")
            return 1
        n = found[0]
        adb(
            serial, "shell", "input", "swipe",
            str(n["cx"]), str(n["cy"]), str(n["cx"]), str(n["cy"]), str(duree),
        )
        print(f"UICTL_OK appui long {duree} ms ({n['cx']},{n['cy']}) sur {describe(n)}")
        return 0

    if cmd == "wait":
        timeout = float(sys.argv[4]) if len(sys.argv) > 4 else 30.0
        deadline = time.time() + timeout
        while time.time() < deadline:
            found = nodes(dump(serial), arg)
            if found:
                for n in found:
                    print(describe(n))
                print("UICTL_OK")
                return 0
            time.sleep(2)
        print(f"UICTL_FAIL absent apres {timeout} s: {arg}")
        return 1

    print(f"UICTL_FAIL commande inconnue: {cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
