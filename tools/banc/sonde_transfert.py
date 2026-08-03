#!/usr/bin/env python3
"""Echantillonnage simultane des deux moities du transfert, pour dater l'invariant.

L'invariant a mesurer est un **ordre** : aucun fichier de chunk n'est efface de la montre avant
que le telephone n'ait accuse sa reception. Un ordre ne se lit pas dans un etat final — a la fin,
les fichiers ont disparu des deux cotes de la question. Il faut donc echantillonner les deux
disques **pendant** la fenetre, et assez vite pour qu'un aller-retour du Data Layer y tienne.

Deux fils, un par appareil, parce que les deux liens n'ont pas la meme latence : la montre est en
USB (~200 ms par appel), le telephone en WiFi (~400 ms). Les faire alterner dans une seule boucle
donnerait au telephone le retard de la montre et rendrait tout ecart de 200 ms indistinguable
d'un artefact de mesure.

Chaque ligne porte **deux** horodatages, celui d'avant l'appel et celui d'apres : l'observation
est quelque part entre les deux, et c'est cet encadrement qu'il faut lire, pas un instant unique
qu'on n'a pas. Les deux sont en millisecondes depuis l'epoque, prises sur l'hote — donc dans une
seule horloge, ce qui evite d'avoir a corriger la derive entre les deux appareils.

Usage :
    sonde_transfert.py <serie_montre> <serie_telephone> <duree_s> [periode_ms]

Sortie, une ligne par echantillon :
    <ms_avant> <ms_apres> MONTRE n=<k> <idx:taille> ...
    <ms_avant> <ms_apres> TEL    n=<k> <idx:taille> ...
"""

import os
import subprocess
import sys
import threading
import time

ADB = os.environ.get("ADB") or os.path.join(
    os.path.expanduser("~"), "Library/Android/sdk/platform-tools/adb"
)
if not os.path.exists(ADB):
    ADB = "adb"

VERROU = threading.Lock()


def maintenant_ms():
    return int(time.time() * 1000)


def lister(serial):
    """Les fichiers de chunk de l'application, vus par `run-as`.

    `ls -lR` et non un glob : le glob serait developpe par le shell de l'appareil, qui tourne
    sous l'utilisateur `shell` et n'a pas le droit de lire le repertoire de l'application. La
    recursion, elle, se fait apres `run-as`, donc avec la bonne identite.
    """
    out = subprocess.run(
        [ADB, "-s", serial, "shell", "run-as", "com.pendulum", "ls", "-lR", "files/chunks"],
        capture_output=True,
        timeout=30,
    ).stdout.decode("utf-8", "replace")
    fichiers = []
    for ligne in out.splitlines():
        champs = ligne.split()
        if not champs or not champs[-1].endswith(".pendulum"):
            continue
        nom = champs[-1].rsplit(".", 1)[0]
        taille = champs[4] if len(champs) >= 6 else "?"
        fichiers.append(f"{nom}:{taille}")
    return sorted(fichiers)


def boucle(serial, etiquette, fin, periode):
    while time.time() < fin:
        avant = maintenant_ms()
        try:
            f = lister(serial)
            corps = f"n={len(f)} " + " ".join(f)
        except Exception as e:  # un appel adb qui expire ne doit pas arreter la sonde
            corps = f"ERREUR {e}"
        apres = maintenant_ms()
        with VERROU:
            print(f"{avant} {apres} {etiquette} {corps}", flush=True)
        reste = periode - (apres - avant) / 1000.0
        if reste > 0:
            time.sleep(reste)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    montre, telephone = sys.argv[1], sys.argv[2]
    duree = float(sys.argv[3])
    periode = (float(sys.argv[4]) if len(sys.argv) > 4 else 400) / 1000.0
    fin = time.time() + duree
    fils = [
        threading.Thread(target=boucle, args=(montre, "MONTRE", fin, periode)),
        threading.Thread(target=boucle, args=(telephone, "TEL", fin, periode)),
    ]
    for f in fils:
        f.start()
    for f in fils:
        f.join()
    print("SONDE_FIN", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
