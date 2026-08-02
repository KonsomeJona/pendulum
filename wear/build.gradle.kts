plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pendulum.wear"
    compileSdk = 36

    defaultConfig {
        // Le Data Layer ne livre les evenements qu'entre applications de **meme nom de paquet et
        // de meme signature** : `applicationId` est donc partage avec le module `phone`, seul le
        // `namespace` differe. Consequence directe : plus aucun nom de classe relatif (`.Xxx`)
        // dans le manifeste, puisque les noms relatifs se resolvent sur `applicationId`.
        applicationId = "com.pendulum"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Signature de publication.
    //
    // Le keystore n'est **jamais** dans le depot : la CI le materialise depuis un secret encode en
    // base64, et en local il est simplement absent. Dans ce cas on ne bascule pas silencieusement
    // sur la cle de debug — un artefact signe en debug qui se fait passer pour une release est
    // exactement le genre de confusion qui finit par etre publie. `assembleRelease` echoue donc
    // proprement en local, et seule la CI produit des artefacts signes.
    val keystoreFile = rootProject.file("release.keystore")
    val hasKeystore = keystoreFile.exists() &&
        System.getenv("PENDULUM_KEYSTORE_PASSWORD") != null

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("PENDULUM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PENDULUM_KEY_ALIAS") ?: "pendulum"
                keyPassword = System.getenv("PENDULUM_KEY_PASSWORD")
                    ?: System.getenv("PENDULUM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            // Sideload personnel : pas de minification, la trace d'exception lisible vaut plus
            // que les quelques centaines de kilo-octets economises.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        java.srcDirs("src/main/kotlin")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":format"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.wearable)
    // Deja tiree transitivement par WorkManager et Compose ; declaree explicitement parce que
    // le service et les workers en dependent directement.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    // Version geree par le BOM Compose ; pas dans le catalogue, ajoutee ici pour ne pas
    // toucher a `gradle/libs.versions.toml` que d'autres modules editent en parallele.
    implementation("androidx.compose.foundation:foundation")
    // `collectAsStateWithLifecycle` et `LifecycleResumeEffect` : le premier garantit que la
    // collecte s'arrete quand l'ecran s'eteint — le critere « zero recomposition entre le coucher
    // et le reveil » — et le second relance le preflight quand l'utilisateur revient des reglages.
    implementation(libs.androidx.lifecycle.runtime.compose)
}
