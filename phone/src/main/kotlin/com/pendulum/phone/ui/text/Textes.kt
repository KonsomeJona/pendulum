package com.pendulum.phone.ui.text

/**
 * Tous les textes de l'application, en anglais, a un seul endroit.
 *
 * ### Pourquoi un objet Kotlin et pas `strings.xml`
 *
 * Trois raisons, dans l'ordre du poids.
 *
 * 1. **Le fichier est verifiable par un test JVM.** `TextesTest` lit ce fichier source et echoue
 *    si l'un des verbes d'evolution proscrits y apparait. Le garde-fou 5 de `SPEC-v2.md` §3 exige
 *    exactement cela : « aucun verbe d'evolution dans les ressources de chaines — verifiable par
 *    un test unitaire sur le fichier de textes ». Un `strings.xml` serait tout aussi lisible,
 *    mais l'interpolation y est pauvre et le test devrait deviner l'encodage des pluriels.
 * 2. **Les textes longs de ce produit portent du raisonnement**, pas des etiquettes. Le refus
 *    d'agreger sous trois nuits est un paragraphe argumente et chiffre ; l'ecrire dans une
 *    ressource XML echappee est le moyen le plus sur de l'abimer a la premiere retouche.
 * 3. Application mono-utilisateur, mono-langue. Le cout d'une infrastructure de traduction
 *    n'aurait aucune contrepartie.
 *
 * ### La regle que ce fichier existe pour tenir
 *
 * Cette application ne dit jamais qu'un chiffre a *evolue* dans un sens ou dans l'autre. Elle
 * dit qu'un ecart est, ou n'est pas, distinguable de la variabilite d'une nuit a l'autre. La
 * nuance n'est pas cosmetique : sous le seuil de plus petite variation detectable, affirmer une
 * direction est une affirmation sans donnee, et c'est precisement le chiffre sur lequel
 * quelqu'un serait tente d'ajuster une dose.
 *
 * Ce fichier ne contient donc **aucun** des verbes proscrits, ni dans leur forme francaise ni
 * dans leur forme anglaise. La liste vit dans `TextesTest` — la mettre ici la ferait detecter
 * par son propre test.
 */
object Textes {

    // =====================================================================================
    // Avertissement permanent
    // =====================================================================================

    /**
     * L'avertissement, en toutes lettres.
     *
     * Il apparait au premier lancement en defilement bloquant, reste accessible en permanence
     * dans Reglages › A propos, et figure en tete de tout export.
     *
     * Ecrit pour etre compris a 7 h du matin : phrases courtes, aucun terme juridique, aucun
     * « le cas echeant ». Les quatre limites sont enoncees en clair parce qu'elles ne
     * disparaitront jamais, quelle que soit la qualite des nuits enregistrees — ce ne sont pas
     * des defauts de version, ce sont les bornes de la methode.
     */
    object Avertissement {
        const val TITRE = "What Pendulum cannot do"

        const val CORPS = """Pendulum estimates leg movements during sleep from the accelerometer of a watch worn at the ankle.

This is not an official health application. This is not a medical device. Pendulum makes no diagnosis, and no treatment decision should rest on what it displays. If a figure in this application worries or reassures you, the only useful next step is to discuss it with a sleep physician.

Four limits that will never go away, whatever the quality of your nights:

• Pendulum cannot diagnose restless legs syndrome. That diagnosis is clinical: it rests on the symptoms you feel while awake, not on a sensor. Movements during sleep are only a supporting criterion.

• Pendulum does not measure your breathing. It therefore cannot tell a periodic movement apart from one caused by an apnoea. If you have sleep apnoea, the figure is overstated, and the gap can reach several tens of movements per hour.

• Pendulum measures one leg only. Movements of the other leg are missed. This bias pulls the figure downwards. It does not offset the previous one: the two errors do not cancel out, they add to the uncertainty.

• The American Academy of Sleep Medicine explicitly recommends against replacing electromyography with actigraphy to diagnose periodic limb movement disorder.

What Pendulum does usefully: follow a rhythm across several nights, under stable conditions, and produce a document you can take to a sleep physician."""

        const val BOUTON = "I have read and understood"
        const val BOUTON_BLOQUE = "Scroll to the bottom"
        const val BOUTON_A_CONFIRMER = "Confirm the four limits above"
        const val RAPPEL = "This notice stays available in Settings › About."

        /**
         * Quatre confirmations actives, une par limite, **en plus** du defilement bloquant.
         *
         * ### Pourquoi le defilement seul ne suffit pas
         *
         * L'etude de reference sur la lecture des politiques de confidentialite (Obar &
         * Oeldorf-Hirsch, 543 participants) mesure une duree de lecture mediane de 73 secondes
         * la ou 29 a 32 minutes seraient necessaires pour lire le texte. Le geste observe n'est
         * pas la lecture, c'est le defilement au pouce. Un bouton qui s'active au bas du scroll
         * atteste donc d'un mouvement de doigt, pas d'une comprehension.
         *
         * ### Le pattern qui a des preuves
         *
         * Le *teach-back* de l'eConsent en recherche clinique — le module de consentement de
         * Sage Bionetworks, base de celui de ResearchKit — fait re-enoncer chaque point plutot
         * que de le faire signer en bloc. Un essai randomise de 2026 lui donne une comprehension
         * non inferieure au consentement recueilli en face a face. Quatre tapes deliberees, une
         * par limite, valent donc mieux qu'un defilement, et constituent en prime une trace de
         * consentement d'une autre nature — on sait ce qui a ete acquitte, pas seulement que
         * l'ecran a ete parcouru.
         *
         * Les quatre phrases reprennent mot pour mot les quatre limites de [CORPS]. Ce n'est pas
         * de la redite : ce sont ces phrases-la que l'utilisateur acquitte, et elles doivent donc
         * dire exactement la meme chose que celles qu'il vient de lire.
         */
        object Confirmations {
            const val TITRE = "Confirm each limit"
            const val DIAGNOSTIC =
                "I understand that Pendulum cannot diagnose restless legs syndrome."
            const val RESPIRATION =
                "I understand that Pendulum does not measure breathing, and that with sleep " +
                    "apnoea the figure is overstated."
            const val UNE_JAMBE =
                "I understand that Pendulum observes one leg only, which pulls the figure down, " +
                    "and that the two biases do not cancel out."
            const val ACTIGRAPHIE =
                "I understand that the American Academy of Sleep Medicine recommends against " +
                    "actigraphy in place of electromyography."

            fun compte(faites: Int, sur: Int) = "$faites of $sur confirmed"
        }

        /** Version condensee, en tete de chaque export. Meme fond, un seul paragraphe. */
        const val BANDEAU_EXPORT =
            "Personal measurement by ankle accelerometer. This is not a medical examination, " +
                "this is not a medical device, and it makes no diagnosis. No treatment decision " +
                "should rest on this document."
    }

    // =====================================================================================
    // Premier lancement
    // =====================================================================================

    object Accueil {
        const val ETAPE = "Step %d of 5"

        object Besoins {
            const val TITRE = "What Pendulum needs"
            const val MONTRE_TITRE = "A Wear OS watch at the ankle"
            const val MONTRE_CORPS =
                "It records the accelerometer all night at 50 Hz. It is the watch that measures " +
                    "the movements. It must be charged to 100% at bedtime: one night draws " +
                    "between 40 and 70% of the battery."
            const val SOMMEIL_TITRE = "A source of sleep stages"
            const val SOMMEIL_CORPS =
                "A second watch on the wrist (Samsung Health), or Sleep as Android, writing the " +
                    "hypnogram into Health Connect. Without one, Pendulum knows when you move but not " +
                    "when you sleep: it then falls back on its own immobility mask, which is less " +
                    "reliable, and flags this on every night concerned."
            const val NUITS_TITRE = "Three nights minimum, five to seven preferably"
            const val NUITS_CORPS =
                "Pendulum refuses to compute a trend below three nights. This is not decorative " +
                    "caution: on a single night, the result is dominated by chance."
            const val BOUTON = "Continue"
        }

        /**
         * L'appairage, et les **trois** etats qu'il faut distinguer.
         *
         * Les confondre est l'erreur classique de cet ecran : « aucune montre appairee » et
         * « montre appairee, application absente » se reparent a deux endroits differents, et
         * envoyer quelqu'un au Play Store alors qu'aucune montre n'est appairee au telephone ne
         * peut rien produire d'autre qu'une installation qui ne se voit nulle part.
         */
        object Appairage {
            const val TITRE = "Watch pairing"
            const val RECHERCHE = "Looking for the watch…"
            const val RECHERCHE_SOUS_TITRE =
                "Open Pendulum on your watch. Both devices must be paired in the Watch app."

            // --- etat 1 : aucun noeud connecte -------------------------------------------
            const val AUCUNE_MONTRE_TITRE = "No watch is paired with this phone"
            const val AUCUNE_MONTRE_CORPS =
                "Pendulum sees no watch at all, so there is nothing to install onto yet. Pair " +
                    "the watch first, in the companion application that came with it — Pixel " +
                    "Watch, Galaxy Wearable, or your manufacturer's equivalent."
            const val OUVRIR_COMPAGNON = "Open the watch companion app"
            const val COMPAGNON_INTROUVABLE =
                "No companion application was found on this phone. Open the one supplied with " +
                    "your watch, pair it, then come back to this screen."

            // --- etat 2 : montre appairee, capacite absente -------------------------------
            const val APP_ABSENTE_TITRE = "Watch paired, Pendulum not answering on it"
            const val APP_ABSENTE_CORPS =
                "The watch is known to this phone, but the Pendulum watch application is not " +
                    "answering. Either it is not installed, or the watch is out of Bluetooth " +
                    "range right now."
            const val INSTALLER_SUR_MONTRE = "Install on the watch"
            const val INSTALLATION_OUVERTE =
                "The store page was opened on the watch. Finish the installation there."
            const val INSTALLATION_ECHOUEE =
                "The store page could not be opened on the watch. Open the Play Store on the " +
                    "watch and search for Pendulum."
            const val ATTENTE_AUTOMATIQUE =
                "This screen ticks itself as soon as the watch application answers. Nothing to " +
                    "press, and no need to stay on it."

            // --- etat 3 : capacite trouvee -----------------------------------------------
            const val TROUVEE_TITRE = "Watch found"
            const val INCONNU = "—"

            /**
             * Ligne de verification du capteur. C'est un **controle**, pas de la decoration : si
             * la montre annonce autre chose que 50 Hz, ou un FIFO plus court que prevu, tout le
             * budget d'erreur de la campagne change.
             *
             * Elle n'est pas encore alimentee : la montre ne publie aucun `DataItem` decrivant
             * son capteur, donc l'ecran affiche [VERIFICATION_ABSENTE] plutot qu'un tiret muet.
             * Cette fonction est la forme que la ligne prendra le jour ou la montre l'enverra —
             * la garder evite de la reinventer, et de la reinventer autrement.
             */
            fun verification(hz: Int, fifo: Int, wakeUp: Boolean) =
                "Accelerometer: $hz Hz requested · FIFO ${espaceMilliers(fifo)} events · " +
                    "wake-up sensor: ${if (wakeUp) "yes" else "no"}"

            const val VERIFICATION_ABSENTE =
                "Accelerometer: — · FIFO: — · wake-up sensor: —"
            const val VERIFICATION_ABSENTE_NOTE =
                "The watch does not report its sensor configuration yet. Rather than print " +
                    "figures nobody measured, this line stays empty."

            const val SANS_MONTRE = "Continue without a watch for now"
            const val BOUTON = "Continue"
        }

        /**
         * La source de sommeil, et la question la plus deroutante du produit : « pourquoi mon
         * application de sommeil ne suffit-elle pas ? ».
         *
         * La reponse qui passe est une reponse de **role**, pas de plomberie : la montre a la
         * cheville mesure les jambes, elle ne sait pas quand on dort ; un deuxieme appareil sert
         * de juge independant du sommeil. La justification complete — la circularite entre ce
         * qu'on compte et ce par quoi on divise — tient derriere un « en savoir plus », parce
         * qu'elle est vraie mais qu'elle ne se lit pas debout, un telephone a la main.
         */
        object SourceSommeil {
            const val TITRE = "Where do your sleep stages come from?"
            const val AUTORISER = "Allow Health Connect"
            const val SOURCES_DETECTEES = "Sources detected"
            const val PREFEREE = "preferred source"
            const val CHOISIR = "Use this source"
            const val SANS_STADES =
                "This source does not provide detailed stages. Pendulum will be able to compute " +
                    "total sleep time, but not to break movements down by stage."
            const val SANS_HYPNOGRAMME = "Continue without a hypnogram"
            const val BOUTON_BLOQUE = "Allow Health Connect first"

            // --- le role du deuxieme appareil --------------------------------------------
            const val ROLE_TITRE = "Why a second device"
            const val ROLE_CORPS =
                "The watch at your ankle measures your legs. It does not know when you are " +
                    "asleep. A second device — a wrist wearable, a ring, a mattress sensor — " +
                    "acts as an independent judge of sleep, and writes that judgement into " +
                    "Health Connect."
            const val ROLE_PLUS = "Why can it not be the same device?"
            const val ROLE_PLUS_CORPS =
                "The clinical index is movements per hour of sleep: the movements are the " +
                    "numerator, sleep time is the denominator. Both read from the same " +
                    "accelerometer would make the figure circular — the signal processing that " +
                    "drops a movement lowers the numerator and, in the same gesture, raises the " +
                    "denominator, because fewer movements look like more sleep. A treatment " +
                    "doing nothing at all could then display as a large drop. Two devices, two " +
                    "chains, and only one of them depends on both."

            // --- Health Connect indisponible ---------------------------------------------
            const val SDK_ABSENT_TITRE = "Health Connect is not on this phone"
            const val SDK_ABSENT_CORPS =
                "On Android 13 and earlier, Health Connect is an application from the Play " +
                    "Store, not a part of the system. Install it, open your sleep application " +
                    "once so that it writes into it, then come back here."
            const val INSTALLER_HEALTH_CONNECT = "Install Health Connect"
            const val MISE_A_JOUR_TITRE = "Health Connect needs updating"
            const val MISE_A_JOUR_CORPS =
                "The version installed on this phone is older than what Pendulum reads. Update " +
                    "it from the Play Store, then come back here."
            const val METTRE_A_JOUR = "Update Health Connect"
            const val FOND_INDISPONIBLE_TITRE = "Background reading unavailable"
            const val FOND_INDISPONIBLE_CORPS =
                "This phone's Health Connect cannot serve reads while Pendulum is closed. The " +
                    "sleep session will only be read when you open the application, so end your " +
                    "night from the home screen."

            // --- aucune source detectee ---------------------------------------------------
            const val AUCUNE_TITRE = "No sleep session found over the last 7 days"
            const val AUCUNE_CORPS =
                "Nothing wrote a sleep session into Health Connect. That is not a Pendulum " +
                    "failure, and it is not permanent: it means no application has published " +
                    "one yet. Two things to check — that a sleep application is installed and " +
                    "worn at night, and that it is allowed to write sleep into Health Connect."
            const val APPLICATIONS_CONNUES_TITRE = "Applications known to write sleep sessions"
            const val APPLICATIONS_CONNUES_CORPS =
                """  • Samsung Health, with a Galaxy Watch;
  • Google Fit / Fitbit, with a Pixel Watch or a Fitbit band;
  • Sleep as Android, with a phone or a watch;
  • Withings Health Mate, with a Sleep Analyzer under the mattress;
  • Garmin Connect and Oura, through their Health Connect option.

Any application publishing a sleep session works. Pendulum reads the session, never the vendor score."""
            const val OUVRIR_HEALTH_CONNECT = "Open Health Connect"

            fun couverture(nuits: Int, sur: Int, stades: Boolean) =
                "$nuits nights out of $sur · " + if (stades) "detailed stages (REM, light, deep)" else "total duration only"
        }

        object Notifications {
            const val TITRE = "Notifications and measurement conditions"
            const val AUTORISER = "Allow notifications"
            const val RAISON =
                "One notification per night, on waking, when the analysis is ready. No others."
            const val CONDITIONS_TITRE = "Conditions to keep identical from one night to the next"
            const val CONDITIONS_CORPS = """The detection threshold adapts to the background noise of each night. If the mechanical conditions change, the nights are no longer comparable with one another.

Keep these identical:
  • the same strap, at the same strap hole;
  • the same leg;
  • the watch on the front of the shin, just above the malleolus;
  • the same mattress.

Note down the strap hole you use: Pendulum will remind you of it at bedtime."""
            const val CHAMP_REPERE = "Strap setting (e.g. “4th hole”)"
            const val BOUTON = "Finish"
        }
    }

    // =====================================================================================
    // L'accueil : trois cartes fixes
    // =====================================================================================

    /**
     * L'accueil, et les trois cartes qui ne bougent jamais.
     *
     * ### Pourquoi trois cartes et pas un ecran adaptatif
     *
     * L'accueil melangeait deux regimes cognitifs incompatibles : le geste quotidien, rapide et
     * memorise, et la lecture d'un resultat, lente et chargee. La carte « Ce soir » devait de
     * surcroit apparaitre entre 20 h et 4 h, donc **deplacer verticalement tout le contenu deux
     * fois par jour**. Une cible qui se deplace se cherche a nouveau a chaque fois, et la lecture
     * du chiffre en dessous devient un accident de parcours.
     *
     * Les trois cartes sont donc d'ordre et de position invariants. Une carte sans objet n'est
     * jamais retiree : elle est desactivee, et **elle porte son motif** (`BoutonMotive`), parce
     * qu'un bouton grise sans explication apprend a se mefier de tous les boutons.
     *
     * ### D'ou vient l'etat
     *
     * De la machine a etats persistee — `night_session.state`, le contexte scelle, l'analyse
     * faite ou non — et **pas de l'horloge**. L'heure ne sert que de departage quand deux
     * lectures sont egalement plausibles. C'est ce qui supprime la fenetre 20 h – 4 h en dur, et
     * avec elle le cas du travail poste et celui du decalage horaire.
     */
    object EcranAccueil {
        const val TITRE = "Home"

        object Preparer {
            const val TITRE = "Prepare the night"
            const val OCCUPE = "The watch is recording — nothing to prepare"
        }

        object Fin {
            const val TITRE = "End of night"

            /**
             * Ce que le bouton fait, en une phrase. Il ne se contente pas de « rafraichir » : il
             * demande a la montre de pousser ce qu'elle detient encore, puis lit la session de
             * sommeil, puis score la nuit. Dire « synchroniser » cacherait qu'un geste de dix
             * secondes en declenche trois.
             */
            const val CORPS =
                "Asks the watch to push everything it still holds, reads the sleep session, then " +
                    "scores the night. Nothing is deleted on the watch before receipt is confirmed."

            const val BOUTON = "End the night and collect the data"
            const val AUCUNE_SESSION = "No recording open"

            fun ouverteDepuis(heure: String) = "Recording open since $heure"
            fun enCoursDepuis(heure: String) = "The watch has been recording since $heure"
            fun analysee(date: String) = "Night of $date analysed"
        }

        object Historique {
            const val TITRE = "History"
            const val BOUTON = "Open the list"
            const val VIDE = "No night recorded yet"

            fun compte(nuits: Int, eligibles: Int) = "$nuits nights · $eligibles eligible"
        }

        /**
         * Garde-fou 2 : le resultat est masque par defaut au reveil, et le devoiler laisse une
         * trace horodatee qui part dans l'export.
         *
         * ### Un seul geste, et pas de peage
         *
         * Un bouton, pas de modale de confirmation, pas d'avertissement a accepter. La
         * justification est mesuree : sur environ 8 000 reponses de patients recevant leurs
         * resultats de laboratoire avant relecture medicale, 95,7 % veulent les recevoir
         * immediatement et 7,5 % seulement rapportent une inquietude accrue. Vouloir son chiffre
         * au reveil est donc la norme, pas l'exception ; la friction doit ralentir le geste, pas
         * le taxer.
         *
         * Ce qui reste non negociable est la **trace**, et elle est silencieuse : lire son chiffre
         * dans l'etat ou l'on est le moins capable de le juger est un choix legitime ; le faire
         * sans que cela figure dans le document remis au medecin ne l'est pas.
         */
        object Resultat {
            const val MASQUE_LIGNE = "result not shown"
            const val BOUTON = "Show the result"

            const val MASQUE_CORPS =
                "Pendulum does not put the figure of a single night in front of you at waking. " +
                    "The night was recorded and its quality was checked; that is what this screen " +
                    "says. When you show the figure, the time at which you did is written down and " +
                    "appears in the report for the physician."

            fun enregistree(date: String) = "Night of $date recorded · quality acceptable"
            fun devoileeLe(instant: String) = "Result shown on $instant"
        }
    }

    // =====================================================================================
    // Ce soir / scellement du contexte
    // =====================================================================================

    object CeSoir {
        const val TITRE = "TONIGHT"
        const val EN_COURS = "RECORDING"

        const val CONSIGNE_DEMARRAGE = "Press START on the watch."
        const val CONSIGNE_ARRET =
            "Stopping is done on the watch, or automatically as soon as it is on its charger."

        const val LIGNE_MONTRE = "Watch"
        const val LIGNE_BRACELET = "Strap"
        const val LIGNE_SOMMEIL = "Sleep"

        const val BATTERIE_BASSE = "98% recommended — one night draws 40 to 70%."

        /**
         * Le contexte du soir est la seule vraie porte du produit : la montre refuse de demarrer
         * tant qu'il n'est pas scelle. Le texte doit dire pourquoi, sinon la porte passe pour une
         * lourdeur administrative.
         */
        const val SCELLEMENT_TITRE = "Context for the night"
        const val SCELLEMENT_CORPS =
            "Evening dose, leg, strap, alcohol, coffee after 16:00, alone in bed. " +
                "This information is recorded before the night and cannot be changed afterwards: " +
                "entered after the fact, it would be swayed by the result."
        // --- le formulaire du soir ---------------------------------------------------------
        //
        // Les champs sont exactement les colonnes de `NightContextEntity`. Chaque libelle dit ce
        // que la donnee sert a decider, parce qu'un formulaire de sante qu'on remplit sans savoir
        // pourquoi se remplit mal.

        const val SECTION_PORT = "How the watch is worn"
        const val SECTION_CONTEXTE = "The evening"
        const val SECTION_DOSE = "Medication"

        const val JAMBE_GAUCHE = "Left leg"
        const val JAMBE_DROITE = "Right leg"

        const val CHAMP_BRACELET = "Strap and hole"
        const val CHAMP_BRACELET_AIDE =
            "The same strap at the same hole every night. Play in the strap changes the recorded " +
                "amplitude by a factor of 2 to 3, which makes two nights incomparable without " +
                "anything else showing it."
        const val CHAMP_BRACELET_MANQUANT = "Strap and hole required"

        const val CHAMP_SEUL = "Alone in bed"
        const val CHAMP_SEUL_AIDE = "A bed partner transmits their own movements through the mattress."
        const val CHAMP_CAFE = "Coffee after 16:00"
        const val CHAMP_EXERCICE = "Unusual exercise today"
        const val CHAMP_ALCOOL = "Alcohol, units"

        const val CHAMP_DOSE = "Dose taken this evening"
        const val CHAMP_DOSE_AIDE =
            "Free text, kept as typed. A structured field would need the list of molecules, their " +
                "units and their equivalences; a structured field that is half right is worth " +
                "less than a line a physician reads for themselves."
        const val CHAMP_NOTES = "Anything else worth noting"

        const val ANNULER = "Not now"
        const val RELIRE = "Read it again"

        const val SCELLEMENT_BOUTON = "Seal the context for tonight"
        const val SCELLEMENT_FAIT = "Context sealed — the watch can start recording."
        const val SCELLEMENT_MANQUANT =
            "Context not sealed — the watch cannot start recording."
        const val SCELLEMENT_CONFIRMATION =
            "Once sealed, this context can no longer be changed. Check the leg and the strap hole."
    }

    // =====================================================================================
    // Reveil : les cinq etats
    // =====================================================================================

    object Reveil {
        fun notification(date: String, nuits: Int) = "Pendulum — Night of $date analysed. $nuits nights available."

        object EnAttente {
            fun titre(date: String) = "Night of $date recorded on the watch"
            fun corps(mo: String, minutes: Int) =
                "$mo MB to transfer. The transfer starts when the watch is on its charger and " +
                    "within Bluetooth range. Allow $minutes minutes."
            const val ACTION = "Transfer now"
        }

        object Transfert {
            fun titre(recu: String, total: String) = "Transferring — $recu MB of $total MB"
            fun chunk(n: Int, m: Int) = "chunk $n/$m"
            const val CORPS = "You can close the app."
        }

        object Analyse {
            fun titre(date: String) = "Analysis of the night of $date"
            const val ETAPE_ASSEMBLAGE = "assembling the signal"
            const val ETAPE_DETECTION = "detecting movements"
            const val ETAPE_CROISEMENT = "cross-referencing with sleep"
            fun reste(secondes: Int) = "About $secondes seconds."
        }

        /**
         * Etat 4 : le **cas normal** au reveil, et le texte doit le dire.
         *
         * La synchronisation montre-de-poignet vers Health Connect obeit a la politique batterie
         * du fabricant, sans delai garanti — souvent plusieurs heures. Si l'interface presente
         * l'hypnogramme manquant comme une anomalie, l'utilisateur croit a une panne **tous les
         * matins**, et une application qui semble cassee chaque matin finit desinstallee. D'ou :
         * pas de rouge, pas d'icone d'alerte, le mot « provisoire » et l'explication du delai.
         */
        object Provisoire {
            fun titre(date: String) = "Night of $date — provisional result"
            const val CORPS =
                "The movements have been detected. The sleep stages from your wrist watch have " +
                    "not reached Health Connect yet: synchronisation often happens several hours " +
                    "after waking.\n\n" +
                    "In the meantime, Pendulum uses its own immobility mask. The figure will be " +
                    "recomputed automatically, with nothing to do on your part."
            fun tentatives(derniere: String, prochaine: String) =
                "Last attempt: $derniere · next: $prochaine"

            /**
             * La premiere tentative n'a pas encore eu lieu. Ecrire « last attempt: — » ferait
             * lire un echec la ou il n'y a qu'une attente qui commence.
             */
            fun premiereTentative(prochaine: String) = "First attempt at $prochaine"

            /**
             * L'abandon, **date**, et affiche des l'entree dans l'etat.
             *
             * C'est ce qui remplace l'indicateur d'avancement indetermine. Material 3 calibre
             * son *loading indicator* pour des attentes de moins de cinq secondes ; un cercle qui
             * tourne pendant six heures dit « panne », quel que soit le texte a cote. Une echeance
             * datee dit la meme chose — l'application travaille encore — sans la promesse d'un
             * resultat imminent qu'elle ne peut pas tenir.
             */
            fun abandonPrevu(instant: String) =
                "If nothing has arrived by $instant, Pendulum stops looking and the night keeps " +
                    "its “accelerometer mask” flag."

            const val ACTION = "Try again now"
            fun recalculee(date: String) = "Night of $date recomputed with the hypnogram."
            const val ABANDON =
                "Pendulum stopped looking for the hypnogram after 36 h. The night remains eligible and " +
                    "keeps the “accelerometer mask” flag for good."
        }

        object Echec {
            fun titre(date: String) = "Night of $date — analysis not possible"
            const val DETAIL_TECHNIQUE = "See the technical detail"
        }

        fun compteur(enregistrees: Int, eligibles: Int, ecartees: Int) =
            "$enregistrees nights recorded · $eligibles eligible · $ecartees excluded (see Nights)"
    }

    // =====================================================================================
    // Tendance
    // =====================================================================================

    object Tendance {
        const val TITRE = "Trend"

        // --- refus sous trois nuits
        fun compteurNuits(faites: Int, requises: Int) = "$faites nights out of $requises"

        const val REFUS_TITRE = "Pendulum computes no result before three nights."
        const val REFUS_CORPS =
            "The number of movements varies considerably from one night to the next, including in " +
                "people whose disorder is confirmed: the threshold of 15 " +
                "movements per hour is passed on about one night in three. A result shown now " +
                "would mislead you, one way or the other."
        const val REFUS_ACTION = "Record one more night."
        const val REFUS_LISTE = "Nights recorded"

        // --- la grandeur suivie (SPEC-v2 §5)
        const val RYTHME_LABEL = "Fundamental rhythm between movements, median across nights"
        const val COMPTE_LABEL = "Ankle periodic movement index, estimated, unvalidated"
        const val UNITE_SECONDES = "s"
        const val UNITE_PAR_HEURE = "/h"

        /** Ligne canonique : la valeur, son intervalle et son n, dans la meme phrase (P2). */
        fun intervalleEtN(bas: String, haut: String, nuits: Int) = "95% CI: $bas – $haut   ·   $nuits eligible nights"

        /**
         * La meme ligne quand l'intervalle **n'en est pas un a 95 %**, et qu'on refuse de
         * l'etiqueter ainsi. Voir [INTERVALLE_NON_CALIBRE] pour la mesure qui l'impose.
         */
        fun intervalleNonCalibre(bas: String, haut: String, nuits: Int) =
            "Interval $bas – $haut   ·   $nuits eligible nights   ·   not a calibrated 95% interval"

        const val INTERVALLE_NON_CALIBRE =
            "Below six nights this interval is not a 95% interval, and Pendulum does not label it " +
                "as one. Simulated on 10,000 draws, it holds the true value 75% of the time on " +
                "three nights and 88% on four, where the label promises 95%.\n\n" +
                "The cause is arithmetic rather than a fault. The interval is built by resampling " +
                "the nights you recorded, so it can never reach past your lowest and your highest " +
                "night; with three nights, those two bounds fall on either side of the true value " +
                "only three times in four. No variant of the method escapes that, so Pendulum " +
                "names the interval for what it is instead of widening it by an invented amount."

        /**
         * Le compte horaire au second rang. Il reste parce que c'est la langue des somnologues et
         * que les seuils publies reposent dessus, mais il n'est plus la grandeur suivie.
         */
        fun compteSecondRang(valeur: Int, bas: Int, haut: Int, nuits: Int) =
            "Hourly count: $valeur/h · 95% CI $bas–$haut · $nuits nights"

        const val COMPTE_NOTE =
            "The hourly count appears here and in the report for the physician, because that is " +
                "what a sleep specialist reads. It is not the quantity Pendulum follows from one " +
                "night to the next: it varies twelve times as much."

        /**
         * La periodicite ne s'affiche jamais nue entre 0 et 1. Un intervalle en secondes
         * s'imagine, un indice sans dimension ne veut rien dire pour personne — ni pour
         * l'utilisateur, ni pour le medecin qui recoit la feuille.
         */
        const val PERIODICITE_ELEVEE = "high periodicity"
        const val PERIODICITE_BASSE = "low periodicity"
        const val PERIODICITE_NOTE =
            "Periodicity measures the proportion of movements that occur at regular intervals. " +
                "It is described here in plain words rather than by its index: an index between " +
                "0 and 1 compares to nothing you can picture."

        // --- phrases de position (fonction pure, cinq issues, aucune autre formulation)
        fun provisoire(nuits: Int) =
            "Provisional result over $nuits nights. No category is offered before five nights."
        const val SOUS_SEUIL =
            "Below the threshold of 15/h used in clinical practice, including at the upper bound of the interval."
        const val AU_DESSUS_SEUIL =
            "Above the threshold of 15/h used in clinical practice, including at the lower bound of " +
                "the interval. To be discussed with a sleep physician."
        const val ENGLOBE_SEUIL =
            "The interval spans the threshold of 15/h: with these nights, Pendulum cannot say whether " +
                "you are above or below it. Recording more nights will narrow the interval."

        /**
         * Le rythme fondamental n'a **pas** de phrase de position equivalente, et on ne la
         * fabrique pas. Le seuil publie sur la periodicite (≈ 0,5, Ferri 2016) est sur une autre
         * echelle, avec d'autres preuves derriere lui, et il ne se transpose pas mecaniquement.
         * Dire qu'on ne sait pas est plus honnete qu'inventer une cinquieme phrase.
         */
        const val RYTHME_SANS_SEUIL =
            "There is no published threshold that applies to the rhythm measured at the ankle. " +
                "Pendulum shows the measured interval and its spread, without placing it against a " +
                "reference value."

        /**
         * Le bandeau « resultat provisoire », et le nombre de nuits qu'il faudrait.
         *
         * Ce nombre **depend de la position de l'intervalle**, ce qui est le seul endroit du
         * produit ou une regle ajoute de la severite plutot qu'en retirer. La raison est dans
         * [NUITS_REQUISES_REGIME_BAS] : la fiabilite nuit a nuit s'effondre sous le seuil, donc
         * l'application etait jusqu'ici plus assuree quand elle rassurait que quand elle alertait.
         */
        fun bandeauProvisoire(nuits: Int, requises: Int) =
            "Provisional result — $nuits of $requises nights. The interval below will stay wide " +
                "until you have more of them."

        const val NUITS_REQUISES_REGIME_HAUT =
            "Three nights are enough here. Above 15/h, the index agrees with itself from one " +
                "night to the next at an intraclass correlation of 0.90 from three nights " +
                "(Aritake-Okada 2014, ankle actigraph against polysomnography, 41 participants)."

        const val NUITS_REQUISES_REGIME_BAS =
            "Fourteen nights are asked for here, and that figure is a compromise Pendulum does " +
                "not hide. Below 15/h the same study measures far weaker night-to-night " +
                "agreement: 26 nights would be needed to reach the intraclass correlation of " +
                "0.90 that three nights give above the threshold. Twenty-six is what the " +
                "measurement says. Fourteen is what this application asks for, and nothing " +
                "published supports that number — asking for 26 would make the screen unusable, " +
                "asking for 3 would make it confident exactly where it has the least reason to be."

        const val NUITS_REQUISES_A_CHEVAL =
            "Seven nights are asked for here. The interval spans 15/h, so it has to narrow before " +
                "it can fall on one side or the other."

        fun dispersion(valeur: String, unite: String) = "Your nights vary by ±$valeur $unite around their median."

        // --- reglages actifs affiches sur l'ecran
        const val REGLE_COMPTAGE = "Counting rule"
        const val MASQUE_SOMMEIL = "Sleep mask"
        const val MOUVEMENTS_EVEIL = "Movements while awake"
        const val PERIODICITE = "Periodicity"
        const val TAUX_MANQUES = "Estimated missed rate"

        const val SNACKBAR_REGLES =
            "WASM 2016 requires at least 10 s between movements; AASM v3 accepts 5 s. " +
                "The figures are not comparable between the two rules."

        const val COMPARER = "Compare two periods"
        const val COMPARER_SOUS_TITRE = "Before / after a change of treatment"
        const val QUESTIONNAIRE = "Screening questionnaire"
        const val RAPPORT = "Prepare a report for the physician"

        const val PERIODE_7 = "Last 7 nights"
        const val PERIODE_14 = "Last 14 nights"
        const val PERIODE_30 = "Last 30 nights"
        const val PERIODE_TOUT = "All"
        const val PERIODE_PERSO = "Custom period…"

        /**
         * Deux hashs de parametres en base = un rescore inacheve. La tendance refuse alors de
         * melanger, et le dit, au lieu d'afficher une courbe amputee sans prevenir.
         */
        const val HASHS_MELANGES =
            "Some nights in this period were computed with different parameters. The " +
                "recomputation of every night is not finished; the trend does not mix two sets " +
                "of parameters."

        const val PARAMS_PERSONNALISES =
            "Custom parameters active (profile “%s”). These values are not comparable to the " +
                "reference values."
    }

    // =====================================================================================
    // Comparaison de deux periodes
    // =====================================================================================

    object Comparaison {
        const val TITRE = "Compare two periods"
        const val PERIODE_A = "Period A"
        const val PERIODE_B = "Period B"
        const val PIVOT = "Before / after a change of treatment"

        const val INDISPONIBLE_TITRE = "Comparison unavailable"
        fun indisponibleCorps(periode: String, nuits: Int) =
            "$periode: $nuits eligible nights. At least 5 are needed in each period.\n" +
                "With fewer nights, the measured difference would be dominated by the ordinary " +
                "variability from one night to the next, and not by a real effect."

        /**
         * Premiere ligne, toujours, avant tout chiffre (P3). L'ordre de composition est le
         * message : un lecteur presse lit la premiere ligne et s'arrete, donc c'est la premiere
         * ligne qui doit porter la reserve, pas une note en bas.
         */
        const val NON_CONCLUANTE = "Inconclusive"
        const val DISTINGUABLE = "Difference larger than the night-to-night variability"

        const val ZERO_DANS_INTERVALLE =
            "The interval of the difference contains zero: with these data, a real change cannot " +
                "be told apart from ordinary fluctuation between nights."

        const val SOUS_MDC =
            "The difference is smaller than the smallest change this method can detect on your " +
                "nights. It is not distinguishable from measurement noise."

        fun nuitsNecessaires(n: Int) = "To settle it, about $n nights per period would be needed."
        const val HORS_DE_PORTEE =
            "With variability this large, an effect of this size is not measurable by this method."

        /** Meme quand l'ecart est distinguable, l'application ne revendique aucune cause. */
        const val AUCUNE_CAUSE =
            "This difference exceeds the usual night-to-night variability. Pendulum cannot say " +
                "what caused it: a change of treatment, of sleep, of alcohol, of iron, of bedding " +
                "or of watch position would produce the same effect on screen."

        const val DIFFERENCE = "Difference"
    }

    // =====================================================================================
    // Liste et detail des nuits
    // =====================================================================================

    object Nuits {
        const val TITRE = "Nights"
        const val FILTRE_TOUTES = "All"
        const val FILTRE_ELIGIBLES = "Eligible"
        const val FILTRE_ECARTEES = "Excluded"

        const val ETAT_ELIGIBLE = "eligible"
        const val ETAT_PROVISOIRE = "provisional"
        const val ETAT_ECARTEE = "excluded"

        const val VALEUR_UNE_NUIT = "single-night value"
        const val VALEUR_UNE_NUIT_LONG =
            "A single night supports no conclusion. This figure exists to check the measurement, " +
                "not to read anything into it. See the trend."
        const val VOIR_TENDANCE = "See the trend"

        /** Motifs d'exclusion : traduction des identifiants de `ComparabilityRule`. */
        fun motif(code: String): String = when (code) {
            "NO_CONTEXT" -> "context for the evening not sealed"
            "LEG_CHANGED" -> "leg other than the reference night"
            "STRAP_CHANGED" -> "different strap or different strap hole"
            "NOT_ALONE" -> "not alone in bed"
            "CAL_GAIN_UNKNOWN" -> "gain calibration unknown"
            "CAL_GAIN_OUT_OF_TOLERANCE" -> "gain calibration out of tolerance"
            "TOO_SHORT" -> "under 4 h analysable"
            "DST_NIGHT" -> "clock change during the night"
            else -> "not comparable"
        }

        /**
         * Les puces de qualite d'une nuit. Courtes par necessite — au plus trois tiennent sur une
         * ligne de liste — et **factuelles** : chacune porte la valeur mesuree, pas un jugement.
         * « gap 47 s » se verifie ; « signal de mauvaise qualite » ne se verifie pas.
         *
         * Ce ne sont pas des erreurs. Une nuit avec trois puces reste une nuit exploitable ; les
         * puces disent dans quelles conditions son chiffre a ete obtenu, ce qui est exactement ce
         * qu'il faut pour decider si deux nuits se comparent.
         */
        object Drapeaux {
            const val MASQUE_ACCELERO = "accel mask"
            const val TRONQUEE = "truncated"

            fun trous(nombre: Int, secondes: Long): String = "gap ${secondes}s ×$nombre"
            fun batterie(pct: Int): String = "battery $pct%"

            /**
             * Le taux de manques vient de la deconvolution harmonique, qui le mesure au lieu de
             * le supposer. Il est affiche parce qu'il est le critere de comparabilite entre deux
             * nuits : deux nuits dont les taux de manques different beaucoup ne mesurent pas tout
             * a fait la meme chose.
             */
            fun manques(taux: Double): String = "missed %d%%".format(Math.round(taux * 100))
        }

        const val PAS_DE_BOUTON_EXCLURE =
            "Nights are excluded by criteria set before the computation: same leg, same " +
                "strap, alone in bed, stable calibration, and at least 4 h of analysable data. Pendulum offers " +
                "no way to exclude a night manually — removing a night after seeing its figure " +
                "would skew the trend."

        object Detail {
            fun titre(date: String) = "Night of $date"
            const val EVENEMENTS = "Events detected"
            const val MOUVEMENTS = "Movements detected"
            const val DONT_SOMMEIL = "of which in sleep (PLMS)"
            const val DONT_EVEIL = "of which awake (PLMW)"
            const val ECARTES_POSTURE = "excluded — posture"
            const val ECARTES_DUREE = "excluded — duration outside 0.5–10 s"
            const val SERIES = "Series (≥ 4 movements)"
            const val IMI_MEDIAN = "Median onset-to-onset interval"
            const val RYTHME_FONDAMENTAL = "Estimated fundamental rhythm"
            const val TOUS_EVENEMENTS = "All events"

            const val QUALITE = "Quality of the night"
            const val COUVERTURE = "Signal coverage"
            const val PLUS_GRAND_TROU = "Largest gap"
            const val CUMUL_TROUS = "Gaps in total"
            const val FREQUENCE = "Measured frequency"
            const val BATTERIE_FIN = "Battery at end of night"
            const val PORTE = "Worn (off-body)"
            const val SOMMEIL_TOTAL = "Total sleep"
            const val SOURCE_SOMMEIL = "Sleep source"

            /**
             * Le taux de manques estime par la deconvolution harmonique. Il est **mesure** et non
             * suppose : un mouvement manque fusionne deux intervalles de 21 s en un de 42 s, et
             * le modele de melange qui separe ces harmoniques rend le taux au passage.
             */
            const val TAUX_MANQUES = "Estimated missed movements"
            const val REGLE_APPLIQUEE = "Rule applied"

            /**
             * « Pourquoi ce chiffre » — et le seul « pourquoi » qui soit legitime ici.
             *
             * ### Generatif, jamais attributif
             *
             * Ce bloc montre **le chemin de calcul** : ce qui a ete mesure, ce qui a ete retenu,
             * par quoi on a divise. Il ne classe pas des facteurs par importance et n'affirme
             * jamais qu'un trou de signal ou une dose « explique » la valeur.
             *
             * Ce n'est pas de la prudence de facade. Les methodes d'attribution par contribution
             * — Shapley et ses derives — ne distinguent pas correlation et causalite, et
             * sur-attribuent des que les variables sont correlees entre elles, ce qui est
             * exactement le cas ici : la duree de sommeil, le nombre de mouvements et le taux de
             * manques bougent ensemble. Un classement de facteurs affiche sous un chiffre de
             * sante se lit comme une cause, et c'est le mode de defaillance identifie comme
             * critique dans ce domaine.
             *
             * D'ou la derniere ligne, qui fait tout le travail de ce bloc : *ce tableau montre
             * comment le chiffre est obtenu, il n'indique pas ce qui a cause ces mouvements*.
             */
            object Pourquoi {
                fun titre(valeur: String) = "Why $valeur"

                const val SOMMEIL_ANALYSABLE = "Analysable sleep"
                const val MOUVEMENTS_RETENUS = "Movements counted"
                const val REGLE = "Counting rule"
                const val MASQUE = "Sleep mask"
                const val DENOMINATEUR = "Denominator"
                const val TAUX_MANQUES = "Estimated missed rate"
                const val ENCADREMENT_RESPI = "Respiratory bracket"

                fun surEnregistre(enregistre: String) = "of $enregistre recorded"

                const val MASQUE_HYPNOGRAMME = "full hypnogram"
                const val MASQUE_ACCELERO = "accelerometer immobility"

                const val DENOMINATEUR_INDEPENDANT = "independent of the numerator"
                const val DENOMINATEUR_CIRCULAIRE = "same sensor as the numerator"

                /** `-2.1 /h at worst` : la borne basse si tous les mouvements suspects sortaient. */
                fun bornePessimiste(delta: String, unite: String) = "$delta$unite at worst"

                const val ENCADREMENT_RESPI_NOTE =
                    "Pendulum does not measure breathing. This line is the value the index would " +
                        "take if every movement that could belong to a respiratory event were " +
                        "removed. The true figure lies between the two."

                const val AVERTISSEMENT =
                    "This table shows how the figure is obtained. It does not indicate what " +
                        "caused these movements."
            }

            const val PARAMS_AVANCES = "Advanced parameters"
            const val RECALCULER_TOUTES = "Apply to every night"
            const val RECALCUL_CONFIRMATION =
                "Every night will be recomputed with these parameters. The previous results are " +
                    "kept in the log. Nights computed with different parameters are not " +
                    "comparable with one another."

            /**
             * Il n'existe pas de bouton « recalculer cette nuit ». Un reglage par nuit permet de
             * choisir le seuil qui donne le chiffre attendu, nuit par nuit : le graphe mesure
             * alors le reglage, pas le dormeur.
             */
            const val PAS_DE_REGLAGE_PAR_NUIT =
                "A parameter is not set night by night. Changing it recomputes every night from " +
                    "the raw signal, so that the trend stays comparable with itself."
        }
    }

    // =====================================================================================
    // Questionnaire
    // =====================================================================================

    object Questionnaire {
        const val TITRE = "Screening questionnaire"
        const val QUESTION_UNIQUE =
            "When you try to relax in the evening or to sleep, do you sometimes feel an " +
                "irresistible urge to move your legs, or unpleasant sensations in your legs, " +
                "that are relieved when you move?"
        const val OUI = "Yes"
        const val NON = "No"
        const val REPONSE_NON =
            "This single question rules out restless legs syndrome in the great majority of " +
                "cases. The detailed questionnaire stays available if you want to fill it in " +
                "anyway."
        const val ENTETE_DETAILLE =
            "This questionnaire is about what you feel while awake. It is independent of the " +
                "measurement made by the watch: the two go together, neither replaces the other."

        const val ISSUE_COMPATIBLE = "Answers consistent with restless legs syndrome"
        const val ISSUE_NON_COMPATIBLE = "Answers not consistent with restless legs syndrome"
        const val ISSUE_INCOMPLET = "Questionnaire incomplete"
        const val ISSUE_CORPS =
            "This questionnaire is a screening tool. It makes no diagnosis: the five diagnostic " +
                "criteria must be checked by a physician, in particular to rule out the " +
                "conditions that mimic RLS (cramps, neuropathy, positional discomfort, " +
                "drug-induced akathisia)."
        const val ISSUE_EXPORT = "Your answers are included in the exportable report."
        const val REVOIR = "Review my answers"
        const val NON_REMPLI = "not filled in"
        fun rempliDepuis(mois: Int) = "filled in $mois months ago — due again"
    }

    // =====================================================================================
    // Reglages
    // =====================================================================================

    object Reglages {
        const val TITRE = "Settings"
        const val MESURE = "Measurement"
        const val REGLE_AASM = "AASM v3 (5–90 s)"
        const val REGLE_WASM = "WASM 2016 (10–90 s)"
        const val SOURCE_PREFEREE = "Preferred sleep source"
        const val MASQUE_ACCELERO_SEUL = "Accelerometer mask alone"

        /**
         * Aucune source n'a encore ete identifiee — a distinguer de « pas de source » : on n'a
         * pas cherche, ou la lecture n'a rien rendu, ce qui n'est pas la meme chose que savoir
         * qu'il n'y en a pas.
         */
        const val SOURCE_INCONNUE = "sleep source not identified"
        const val PROFIL_PARAMS = "Parameter profile"
        const val REPERE_PORT = "Wearing reference"
        const val ARRET_AUTO = "Automatic stop"

        const val APPAREILS = "Devices"
        const val MONTRE = "Watch"
        const val SYNCHRONISER = "Synchronise now"
        const val HEALTH_CONNECT = "Health Connect"
        const val GERER_HC = "Manage in Health Connect"

        const val DONNEES = "Data"
        const val ESPACE_OCCUPE = "Space used"
        const val PURGER = "Purge raw signals older than 90 days"
        const val PURGER_NOTE = "The results and the charts are kept; only the raw signal is deleted."
        const val JOURNAL = "Technical log"
        const val EFFACER = "Erase all data"
        const val EFFACER_CONFIRMATION = "Type ERASE to confirm."

        const val APPARENCE = "Appearance"
        const val THEME_SYSTEME = "System"
        const val THEME_SOMBRE = "Dark"
        const val THEME_CLAIR = "Light"

        const val A_PROPOS = "About"
        const val VERSION_APP = "Application version"
        const val VERSION_ALGO = "Algorithm version"
        const val RELIRE_AVERTISSEMENT = "Read the notice again"
        const val SOURCES_SCIENTIFIQUES = "Scientific sources"

        const val MASQUE_ACCELERO_INTERDIT =
            "The accelerometer mask alone cannot carry the main result: the denominator would be " +
                "computed from the same signal as the numerator. It is still computed and shown " +
                "as a second arm, never as the result."
    }

    // =====================================================================================
    // Export
    // =====================================================================================

    object Export {
        const val TITRE = "Report for the physician"
        const val FORMAT_PDF = "PDF report (1 to 2 pages)"
        const val FORMAT_PDF_NOTE = "Meant to be printed and read in consultation."
        const val FORMAT_CSV = "CSV data"
        const val FORMAT_CSV_NOTE = "Three zipped files: nights.csv, events.csv, params.csv."

        const val INCLURE_QUESTIONNAIRE = "Include the questionnaire"
        const val INCLURE_ECARTEES = "Include the excluded nights, with their reason"
        const val INCLURE_ECARTEES_NOTE =
            "On by default: hiding failed nights from a physician is misleading."

        const val PARTAGER = "Share"
        const val PAS_DE_RESEAU =
            "The file stays on your phone until you share it. " +
                "Pendulum declares no network access permission."

        fun indisponible(minimum: Int) = "Export unavailable — $minimum nights minimum"

        const val METHODE = "Method"
        const val LIMITES = "Limits"
    }

    // =====================================================================================
    // Porte P1 — le rapport de faisabilite materielle
    // =====================================================================================

    /**
     * Les textes du rapport de la porte P1.
     *
     * Le registre est celui du reste du produit : une valeur mesuree, son seuil, et rien qui
     * qualifie l'ensemble d'un mot. L'ecran ne felicite pas et ne s'alarme pas — il dit combien
     * de nuits d'affilee ont tenu les trois criteres, et laisse la decision qui suit reposer sur
     * ce chiffre.
     */
    object P1 {
        const val TITRE = "P1 — hardware feasibility"
        const val SOUS_TITRE = "Sensor coverage, battery, sampling rate"

        const val INTRO =
            "P1 is the blocking gate of the project: no line of algorithm is meant to be written " +
                "until a watch has held three consecutive nights within the three criteria below. " +
                "Coverage is counted on the differences between sensor timestamps, never on the " +
                "time samples reached the phone — with FIFO batching, an arrival-time rule fires " +
                "on every single night and measures nothing."

        const val NUITS = "Nights"
        const val CONCLUSION = "Campaign"

        /** `at least 99.0%` — le seuil, ecrit a cote de la valeur et jamais ailleurs. */
        fun auMoins(seuil: String) = "at least $seuil"

        fun batterieSeuil(pct: Int, heures: Int) = "at least $pct% at $heures h"

        fun frequenceSeuil(nominalHz: Int, tolerance: String) = "$nominalHz Hz ± $tolerance"

        const val CONFORME = "meets P1"
        const val NON_CONFORME = "outside P1"
        const val INDETERMINE = "not decidable"

        fun serie(atteinte: Int, requises: Int) =
            "Longest run of consecutive nights inside the three criteria: $atteinte of $requises"

        fun etendueSerie(debut: String, fin: String) = "$debut → $fin"

        fun comptes(conformes: Int, examinees: Int) = "$conformes of $examinees nights inside P1"

        const val FRANCHIE = "Gate passed."
        const val NON_FRANCHIE = "Gate not passed."

        const val AUCUNE_NUIT =
            "No night recorded yet. The gate stays closed: it is answered by measurements, not " +
                "by the absence of them."

        /**
         * La phrase qui dit a quoi sert le fichier. Elle est ici et pas seulement dans la
         * documentation, parce qu'un rapport circule sans son contexte — et celui-ci porte le
         * choix de materiel.
         */
        const val POURQUOI =
            "This report decides whether the project carries on with a Wear OS watch or moves to " +
                "a dedicated logger such as an Axivity AX3. That decision should rest on a file, " +
                "not on a memory."

        /**
         * Ce que le telephone ne peut pas afficher est dit, pas passe sous silence. Une ligne absente laisse
         * croire que le controle n'existe pas ; un tiret explique dit qu'il n'est pas transmis.
         */
        const val NON_TRANSMIS =
            "Two figures are missing and are shown as a dash. The largest single gap is measured " +
                "on the watch, but only the total reaches the phone. Battery use over the night " +
                "cannot be scaled to eight hours either: only the level at the end of the night " +
                "is stored, never the level at the start — so a night shorter than eight hours " +
                "answers the battery criterion only when it is already under the threshold."

        const val EXPORTER = "Export as CSV"
        const val NOM_FICHIER = "pendulum-p1.csv"
    }

    // =====================================================================================
    // Erreurs (§7 de UX.md) — titre neutre, une phrase de cause, une phrase d'action
    // =====================================================================================

    /**
     * Aucun de ces messages n'emploie le mot « erreur » quand rien n'est casse. Une nuit sans
     * hypnogramme, une nuit courte, une montre restee sur la table sont des **situations**. Elles
     * s'affichent en ambre. Le rouge est reserve a ce qui est reellement casse : transfert,
     * permission, integrite de fichier, stockage.
     */
    object Erreurs {
        const val NIGHT_01_TITRE = "no sleep stages"
        const val NIGHT_01_CAUSE =
            "No sleep session covering this night was found in Health Connect, even 36 h after " +
                "waking. Your wrist watch may not have recorded it, or not synchronised it."
        const val NIGHT_01_ACTION =
            "Pendulum used its own immobility mask. The result is kept and counts towards the trend, " +
                "but it is marked “accelerometer mask”: sleep time is then estimated, not measured."

        const val NIGHT_02_TITRE = "excluded, night too short"
        const val NIGHT_02_CAUSE =
            "Computing the number of movements per hour requires at least 4 h of sleep: over a " +
                "shorter span, a handful of clustered movements is enough to inflate the figure " +
                "out of all proportion."
        const val NIGHT_02_ACTION =
            "This night stays open to view in detail, but it does not enter the trend. No action needed."

        const val NIGHT_03_TITRE = "recording interrupted"
        const val NIGHT_03_CAUSE = "The watch stopped for want of battery before the end of the night."
        const val NIGHT_03_ACTION =
            "As movements tend to cluster in the second half of the night, the figure is probably " +
                "underestimated. Charge the watch to 100% before bedtime."

        const val NIGHT_04_TITRE = "signal interrupted"
        const val NIGHT_04_CAUSE =
            "The sensor stopped sending data for several minutes. This happens when the system " +
                "suspends the watch more deeply than expected."
        const val NIGHT_04_ACTION =
            "If it happens again, force continuous mode in Settings › Measurement. The battery " +
                "will not last as long."
        const val NIGHT_04_BOUTON = "Force continuous mode"

        const val NIGHT_05_TITRE = "the watch does not appear to have been worn"
        const val NIGHT_05_CAUSE =
            "The off-body sensor reports “not worn” over most of the night, and the signal stayed " +
                "flat, with a noise floor far under that of your other nights."
        const val NIGHT_05_ACTION =
            "This night is excluded. A recording made on a bedside table produces a figure close " +
                "to zero that would distort your trend. Check that the watch is firmly tightened " +
                "at the ankle before starting."
        const val NIGHT_05_BOUTON = "Keep it anyway as a control"
        const val NIGHT_05_NOTE =
            "A night on the table is exactly the negative control the validation protocol calls " +
                "for: if kept, it is labelled “negative control” and stays out of the trend."

        const val NIGHT_06_TITRE = "abnormally high amplitudes"
        const val NIGHT_06_CAUSE =
            "The noise floor is several times that of your other nights. A loose strap or a " +
                "different tightening point produces exactly this signature."
        const val NIGHT_06_ACTION =
            "The night is excluded. The detection threshold adapts to noise, but not to a change " +
                "of mechanics. Go back to the usual setting."

        const val NIGHT_07_TITRE = "incomplete transfer"
        const val NIGHT_07_CAUSE =
            "Some of the files were not received. The missing files are still on the watch: they " +
                "are deleted only once receipt is confirmed."
        const val NIGHT_07_ACTION = "Bring the phone closer to the watch and start it again."
        const val NIGHT_07_BOUTON = "Resume the transfer"

        const val NIGHT_08_TITRE = "offset between the watch and the phone"
        const val NIGHT_08_CAUSE =
            "The timestamps from the watch drift against the phone. Cross-referencing with the " +
                "hypnogram would be offset by the same amount, which would move movements from " +
                "one sleep stage to another."
        const val NIGHT_08_ACTION =
            "Movements are counted as usual; the breakdown by stage is hidden for this night. " +
                "Restart the watch to resynchronise its clock."

        const val PAIR_01_TITRE = "Watch not found"
        const val PAIR_01_CAUSE = "No Wear OS device running Pendulum answered within 30 seconds."
        const val PAIR_01_ACTION =
            "Check that the watch is paired in the Watch app, that Bluetooth is on, and that " +
                "Pendulum is installed on the watch."
        const val PAIR_01_BOUTON = "Search again"

        const val HC_01_TITRE = "No sleep source found"
        const val HC_01_CAUSE =
            "Health Connect holds no sleep session over the last 7 days. No app writes your " +
                "nights to it, or the permission is denied."
        const val HC_01_ACTION =
            "You can carry on: Pendulum will estimate sleep from the immobility measured at the " +
                "ankle. The result will be less precise and every night concerned will be marked."
        const val HC_01_BOUTON = "Open Health Connect"

        const val HC_02_TITRE = "Sleep access revoked"
        const val HC_02_CAUSE = "The permission to read sleep has been withdrawn from Pendulum."
        const val HC_02_ACTION =
            "The nights already analysed are kept; new ones will use the accelerometer mask."
        const val HC_02_BOUTON = "Restore the permission"

        const val HC_03_TITRE = "Two sleep sources for this night"
        const val HC_03_CAUSE =
            "Two applications report different durations. Pendulum cannot combine them without " +
                "risking counting the same minutes twice."
        const val HC_03_ACTION = "The preferred source was used."
        const val HC_03_BOUTON = "Change the preferred source"

        const val ANA_01_TITRE = "Analysis not possible"
        const val ANA_01_CAUSE = "The signal for the night could not be reconstructed: some files failed their integrity check."
        const val ANA_01_ACTION =
            "The raw data is kept. You can run the analysis again; if it fails once more, export " +
                "the technical log."
        const val ANA_01_BOUTON = "Run the analysis again"

        const val STO_01_TITRE = "Not enough space"
        const val STO_01_CAUSE = "One night takes about 300 MB during the analysis."
        const val STO_01_ACTION =
            "Purge the old raw signals: the results and the charts are kept, only the raw signal " +
                "is deleted."

        const val EXP_01_TITRE = "Export unavailable — 3 nights minimum"
        const val EXP_01_CAUSE = "The selected period holds fewer than three eligible nights."
        const val EXP_01_ACTION =
            "A report built on fewer than three nights would mislead the physician who reads it. " +
                "Widen the period or record one more night."
    }

    // =====================================================================================
    // Graphes : descriptions accessibles et libelles d'axes
    // =====================================================================================

    object Graphes {
        const val VALEURS = "Values"
        const val VALEURS_NOTE = "The same content as a table."
        const val AXE_Y_NUIT = "amplitude ÷ noise floor (log₂ scale)"
        const val AXE_Y_TENDANCE_RYTHME = "fundamental rhythm (seconds)"
        const val AXE_Y_TENDANCE_COMPTE = "movements per hour of sleep"
        /**
         * Le seuil de 15/h, **etiquete**, et pas pose nu.
         *
         * ### Pourquoi l'etiquette change quelque chose de mesurable
         *
         * Sur 1 618 adultes a qui l'on montrait un resultat de laboratoire a peine hors norme,
         * ajouter une mention du type « beaucoup de medecins ne s'inquietent pas avant cette
         * valeur » fait tomber la demande de contact urgent de 55,8 % a 34,7 % sur l'ALT et de
         * 56,7 % a 35,2 % sur la creatinine, p < .001 (Zikmund-Fisher et al., JMIR 2018). Une
         * ligne nue a 15 sur un graphe est exactement le meme dispositif qu'une borne de
         * reference nue sur un compte rendu de biologie.
         *
         * ### Et pourquoi le second membre existe
         *
         * `Aggregat.SEUIL_CLINIQUE_PAR_HEURE` transpose un seuil de polysomnographie sur un
         * accelerometre de cheville, ce qui n'est pas la meme mesure. La validation du PAM-RL —
         * actimetre de cheville, le meme montage que celui de Pendulum — contre la PSG donne un
         * equivalent actimetrique de 16,0/h pour le 15/h polysomnographique (Aritake-Okada et
         * al., *Sleep Medicine* 2014, n = 41). Un seul article, une seule cohorte : on
         * **documente** l'ecart, on ne substitue pas la valeur.
         */
        const val LEGENDE_SEUIL_15 =
            "15/h — above this, periodic limb movements are usually counted as frequent in " +
                "polysomnography (ICSD-3). Many physicians are not concerned below it. Measured " +
                "at the ankle by actigraphy, which is what Pendulum does, the matching threshold " +
                "is closer to 16/h (Aritake-Okada 2014, ankle actigraph against polysomnography, " +
                "41 participants). Pendulum keeps 15/h and states the gap rather than swapping in " +
                "a figure that rests on one cohort."
        const val HYPNO_INDISPONIBLE = "Hypnogram unavailable — accelerometer immobility mask used."
        const val VOIE_MASQUE = "Accelerometer mask"
        const val VOIE_HYPNO = "Hypnogram"
        const val VOIE_DESACCORD = "Disagreement"

        fun descriptionNuit(date: String, evenements: Int, debut: String, fin: String) =
            "Chart of the night of $date, $evenements movements detected between $debut and $fin. " +
                "Values button for the table."

        /**
         * Le resume lu par TalkBack, et non une etiquette de bloc.
         *
         * Un `contentDescription` global du type « graphe de tendance sur 9 nuits » annonce
         * l'existence d'un objet et rien de son contenu : le lecteur d'ecran atteint le graphe,
         * apprend qu'il y a un graphe, et repart. Le resume ci-dessous rend les grandeurs qui
         * font la lecture — combien de points, sur quelle periode, la mediane, l'etendue — plus
         * le fait que **les points ne sont pas relies**, qui est une decision de conception et
         * pas un detail de rendu.
         *
         * Le tableau de `DataTableSheet` reste le chemin principal : aucun resume ne remplace des
         * donnees. Il est le premier element focalisable apres le graphe, et c'est le bon ordre.
         */
        fun descriptionTendance(
            points: Int,
            debut: String,
            fin: String,
            mediane: String,
            minimum: String,
            maximum: String,
            unite: String,
        ) = "Trend, $points points, from $debut to $fin, median $mediane $unite, values from " +
            "$minimum to $maximum $unite, points not joined. Values button for the table."

        /** Le meme graphe quand aucun point n'est traçable. Un resume vide serait un mensonge. */
        const val DESCRIPTION_TENDANCE_VIDE = "Trend, no point to plot."
    }

    // --- utilitaires de formatage --------------------------------------------------------

    /** Virgule comme separateur de milliers, convention anglaise. */
    fun espaceMilliers(n: Int): String =
        n.toString().reversed().chunked(3).joinToString(",").reversed()
}
