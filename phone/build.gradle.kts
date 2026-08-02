plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pendulum.phone"
    compileSdk = 36

    defaultConfig {
        // **Partage avec le module `wear`.** Le Wearable Data Layer n'echange qu'entre
        // applications de meme `applicationId` et de meme signature : si les deux modules
        // declarent des identifiants differents, la montre et le telephone ne se voient tout
        // simplement jamais, sans le moindre message d'erreur. Seul le `namespace` differe.
        // Consequence : plus aucun nom relatif (`.Xxx`) dans le manifeste, puisqu'un nom
        // relatif se resout sur `applicationId` et non sur `namespace`.
        applicationId = "com.pendulum"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Pas de minification pour l'instant : Room + KSP + reflexion des workers
            // demandent des regles de conservation qu'on n'ecrira pas avant d'en avoir besoin.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        // `BuildConfig.VERSION_NAME` alimente la ligne « version » de l'ecran Reglages. Elle y
        // etait ecrite en dur (« 0.1.0 »), donc juste jusqu'au premier bump et fausse ensuite —
        // exactement le genre de valeur qu'un utilisateur cite dans un rapport de defaut.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // `:format` et `:algo` sont testes en JUnit 5 ; les tests JVM de `:phone` partagent
        // leurs fixtures, donc la meme plateforme.
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    // Les schemas Room sont **versionnes** : ce sont eux qui rendent une migration verifiable.
    // Sans export de schema, `fallbackToDestructiveMigration` devient la seule issue quand une
    // migration se revele fausse — et c'est precisement ce qu'on s'interdit ici, parce que les
    // chunks bruts sont la seule chose qui permettra de rescorer quand l'algorithme changera.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":format"))
    implementation(project(":algo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.health.connect.client)
    implementation(libs.play.services.wearable)
    // `RemoteActivityHelper` : ouvrir la fiche Play Store **sur la montre** depuis l'assistant.
    // C'est le seul chemin qui ne se fasse pas bloquer, parce que le lancement est execute
    // la-bas par les services Google Play et non par notre processus. La bibliotheque etait
    // deja au catalogue, utilisee par `:wear` dans l'autre sens.
    implementation(libs.androidx.wear.remote.interactions)
    // `startRemoteActivity` rend un `ListenableFuture`, et cette classe **n'est pas** sur le
    // chemin de compilation de `:phone` : `androidx.health.connect` tire Guava complet, ce qui
    // fait remplacer `com.google.guava:listenablefuture` par l'artefact
    // `9999.0-empty-to-avoid-conflict-with-guava`, un jar litteralement vide. Le symptome est
    // « Cannot access class ListenableFuture » alors que la meme ligne compile dans `:wear`, ou
    // Guava complet est absent et ou l'artefact reel est donc conserve.
    //
    // `compileOnly` et pas `implementation` : Guava est **deja** dans l'APK par la voie de
    // Health Connect, donc le declarer ici ne coute pas un octet de plus — il ne fait que rendre
    // le type visible au compilateur. La version suit celle que resout deja le chemin d'execution.
    compileOnly("com.google.guava:guava:31.1-android")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Tests instrumentes. Ils tournent sur appareil et valident ce qu'aucun test JVM ne peut
    // atteindre : que les garde-fous tiennent dans l'application reellement assemblee, avec sa
    // navigation, son theme et sa base SQLite. JUnit 4 et non 5 : l'orchestrateur Android
    // n'execute pas la plateforme JUnit 5.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
