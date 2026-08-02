package com.pendulum.wear.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.pendulum.wear.record.RecordingService

/**
 * L'unique activite. Elle n'enregistre rien, ne calcule rien et ne survit a rien : tout ce qui
 * compte vit dans le service. Son seul role est d'afficher six compteurs et de porter deux
 * boutons — et de ne pas etre allumee la nuit.
 *
 * Pas d'`AmbientModeSupport`, pas de `keepScreenOn`, pas d'always-on. Une activite always-on
 * laisserait l'ecran en mode ambiant toute la nuit, eclairerait la cheville sous la couette,
 * serait tuee a la premiere pression memoire, et couterait l'ecran en plus du capteur — pour
 * une protection *inferieure* a celle d'un service de premier plan `health`.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La demande de `POST_NOTIFICATIONS` vivait ici, avec un rappel vide. Elle est descendue
        // dans `RecordRoute`, ou son resultat peut relancer le preflight : accorder la permission
        // laissait le bloqueur affiche, et il fallait tuer l'application pour qu'il disparaisse.
        // Le moment de la demande n'a pas change — a l'ouverture, et non au START.
        setContent {
            PendulumTheme {
                RecordRoute(
                    onStart = { send(RecordingService.ACTION_START) },
                    onStop = { send(RecordingService.ACTION_STOP) },
                    onOpenSettings = { openAppSettings() },
                )
            }
        }
    }

    private fun send(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).setAction(action),
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
