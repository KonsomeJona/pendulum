plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// La version, en un seul endroit pour les deux applications.
//
// Elle etait ecrite en dur — `versionCode = 1`, `versionName = "0.1.0"` — dans les deux modules, et
// le workflow de publication ne l'injectait pas : il se contentait de **renommer les fichiers** avec
// le tag Git. Toutes les livraisons portaient donc la meme version. Trois consequences, dont aucune
// n'etait visible dans le depot : l'ecran Reglages affichait « 0.1.0 » quel que soit l'artefact
// installe — alors que `buildConfig` a justement ete active pour eviter ce mensonge-la ; rien, ni en
// base ni dans les chunks, ne disait quelle version avait produit un chiffre, pour une application
// dont toute la valeur est la comparabilite dans le temps ; et une mise a jour par-dessus une
// version de meme `versionCode` est refusee par le systeme.
//
// `-Ppendulum.versionName=1.2.0 -Ppendulum.versionCode=10203` en publication ; les valeurs par
// defaut ci-dessous servent aux compilations locales, ou la version n'a pas de sens.
val pendulumVersionName: String =
    (providers.gradleProperty("pendulum.versionName").orNull ?: "0.0.0-dev")
val pendulumVersionCode: Int =
    (providers.gradleProperty("pendulum.versionCode").orNull?.toIntOrNull() ?: 1)

extra["pendulumVersionName"] = pendulumVersionName
extra["pendulumVersionCode"] = pendulumVersionCode

// Lint, configure une fois pour les trois modules Android.
//
// Trois modules qui configurent lint chacun de leur cote divergent au premier reglage ajoute dans un
// seul des trois, et la divergence est silencieuse puisqu'elle se lit dans un rapport que personne
// ne compare. Ici il n'y a qu'un endroit ou regarder.
//
// `abortOnError` reste a `true` : c'est lui qui a fait remonter la seule erreur reelle que ce projet
// ait eue de lint. `checkAllWarnings` reste a `false` — il active des centaines de regles
// desactivees par defaut, et un rapport qu'on cesse de lire ne protege plus rien.
//
// Pas de `lint-baseline.xml`, et c'est delibere : une baseline est un fichier genere, sans place
// pour le **pourquoi**. Ce depot a deja invente la forme juste pour ce probleme — la carte
// `tolerances` de `RecensementDureesTest`, ou chaque exception porte sa raison en une phrase. Le
// `disable` ci-dessous est le meme geste ; une baseline en serait le contraire.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                disable += setOf(
                    // `targetSdk` 35 sous `compileSdk` 36 est le reglage voulu, pas un oubli :
                    // cibler 35 exempte l'application des changements de comportement d'Android 16,
                    // ce qui est exactement ce qu'on veut d'un service de premier plan qui doit
                    // survivre huit heures. A relire le jour ou un magasin exigera 36.
                    "OldTargetApi",
                )
                warningsAsErrors = false
                abortOnError = true
                htmlReport = true
                xmlReport = true
            }
        }
    }
}

// Regle globale : toutes les sorties de build atterrissent sur le disque D.
// On ne redirige que si la racine est reellement accessible, pour que le projet
// reste buildable sur une machine sans D (CI, Mac distant).
val buildRoot: String? = (providers.gradleProperty("pendulum.buildRoot").orNull)
    ?.takeIf { File(it).parentFile?.isDirectory == true || File(it).isDirectory }

if (buildRoot != null) {
    allprojects {
        layout.buildDirectory.set(File(buildRoot, project.path.replace(':', '/').trim('/').ifEmpty { "root" }))
    }
}
