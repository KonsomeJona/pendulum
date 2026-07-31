plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
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
