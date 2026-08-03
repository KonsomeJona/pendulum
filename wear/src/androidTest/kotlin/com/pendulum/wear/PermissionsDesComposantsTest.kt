package com.pendulum.wear

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Toute permission exigee par un composant doit exister sur l'appareil.**
 *
 * Ce test existe a cause d'une panne dont le mode de defaillance est le silence complet. Les deux
 * `WearableListenerService` du produit portaient
 * `android:permission="com.google.android.gms.permission.BIND_WEARABLE_LISTENER"`, une permission
 * qu'**aucun paquet ne definit** et que Play Services ne demande meme pas. Une permission non
 * detenue par l'appelant interdit la liaison ; une permission non definie n'est detenue par
 * personne. Consequence : Android refusait a Play Services de demarrer ces services a chaque
 * livraison, donc l'ingestion des chunks, la publication de l'accuse, son application, la demande
 * de demarrage et la demande de balayage etaient toutes mortes — et la seule trace etait une ligne
 * `W ActivityManager: Permission Denial` dans le journal systeme, que l'application ne voit jamais.
 * `BANC-ESSAI.md` §12.1 chiffre la mesure.
 *
 * La regle est generale et ne nomme aucune permission en particulier : une chaine ecrite dans un
 * manifeste d'apres une documentation qui a change ne produit pas d'erreur de compilation, pas
 * d'avertissement de lint, et pas d'exception a l'execution. Elle produit un composant que le
 * systeme n'ouvre plus a personne. Le seul endroit ou cela se voit est l'appareil, ce qui fait de
 * ce garde-fou un test instrumente par nature.
 *
 * Le jumeau de ce fichier est dans `:phone`, ou il tourne a chaque poussee sur l'emulateur de la
 * CI. Celui-ci n'a pas d'emulateur de montre en CI : il se lance a la main sur une montre reelle,
 * par `./gradlew :wear:connectedDebugAndroidTest`. C'est un ecart assume et non un oubli — la
 * ligne fautive etait sur les deux manifestes, et le garde-fou doit donc exister des deux cotes
 * meme si un seul est automatise.
 *
 * Le test est **exhaustif par construction** : il enumere les services, recepteurs et activites
 * declares par l'application assemblee, plutot qu'une liste tenue a la main qui perimerait au
 * premier composant ajoute.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsDesComposantsTest {

    @Test
    fun toute_permission_exigee_par_un_composant_existe_sur_l_appareil() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val paquet = pm.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_PROVIDERS,
        )

        val composants =
            paquet.services.orEmpty().map { "service ${it.name}" to it.permission } +
                paquet.receivers.orEmpty().map { "receiver ${it.name}" to it.permission } +
                paquet.activities.orEmpty().map { "activity ${it.name}" to it.permission } +
                paquet.providers.orEmpty().map { "provider ${it.name}" to it.writePermission }

        val introuvables = composants.mapNotNull { (nom, permission) ->
            if (permission == null) {
                null
            } else if (runCatching { pm.getPermissionInfo(permission, 0) }.isSuccess) {
                null
            } else {
                "$nom exige $permission, qu'aucun paquet ne definit sur cet appareil"
            }
        }

        // Le message porte la liste entiere : corriger une ligne pour decouvrir la suivante au
        // tour d'apres coute une execution d'emulateur par ligne.
        if (introuvables.isNotEmpty()) {
            throw AssertionError(
                "Permissions exigees par un composant et absentes de l'appareil — la liaison est " +
                    "alors refusee en silence :\n" + introuvables.joinToString("\n"),
            )
        }
    }
}
