plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pendulum.sleepwriter"
    compileSdk = 36

    defaultConfig {
        // **Volontairement different de `com.pendulum`.**
        //
        // `:phone` et `:wear` partagent le meme `applicationId` parce que le Wearable Data Layer
        // n'echange qu'entre applications de meme identifiant et de meme signature. Ce module ne
        // participe pas au Data Layer : lui donner le meme identifiant empecherait purement et
        // simplement son installation a cote de Pendulum, et le remplacerait sur l'appareil.
        //
        // Il y a une seconde raison, plus importante : Health Connect fixe le `dataOrigin` d'un
        // enregistrement a partir du **paquet qui l'ecrit**. C'est cette valeur que
        // `SleepSourceSelector` arbitre. Ecrire depuis `com.pendulum` fabriquerait une origine
        // qui ment sur ce que le banc pretend simuler — une application tierce.
        applicationId = "com.pendulum.sleepwriter"
        // Aligne sur `:phone`, qui est le lecteur : rien ne sert d'ecrire sur une version
        // d'Android ou Pendulum ne peut pas lire.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // **Deux paquets, parce que `E-HC-03` n'est pas simulable autrement.**
    //
    // Le scenario « deux sources en conflit » demande deux `dataOrigin` distincts sur la meme
    // nuit. L'origine etant le nom de paquet de l'ecrivain, il faut deux APK reellement
    // installables cote a cote — un drapeau d'execution, une preference ou un champ de
    // `Metadata` ne changeraient rien a ce que Health Connect enregistre.
    //
    // Meme code, meme manifeste, meme signature de debug : seul l'identifiant differe, plus une
    // etiquette lisible dans les journaux et sur l'icone.
    flavorDimensions += "source"
    productFlavors {
        create("sourceA") {
            dimension = "source"
            applicationIdSuffix = ".a"
            resValue("string", "app_name", "SleepWriter A")
            buildConfigField("String", "SOURCE_LABEL", "\"A\"")
        }
        create("sourceB") {
            dimension = "source"
            applicationIdSuffix = ".b"
            resValue("string", "app_name", "SleepWriter B")
            buildConfigField("String", "SOURCE_LABEL", "\"B\"")
        }
    }

    buildFeatures {
        // `BuildConfig.SOURCE_LABEL` et `BuildConfig.APPLICATION_ID` sont ecrits dans chaque
        // ligne de journal : sans eux, deux APK qui logguent sous le meme tag sont
        // indistinguables dans `adb logcat`, ce qui est exactement le cas du scenario de conflit.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        java.srcDirs("src/main/kotlin")
    }
    sourceSets.getByName("test") {
        java.srcDirs("src/test/kotlin")
    }

    testOptions {
        // Meme plateforme que les autres modules : JUnit 5. La cible est `Hypnogramme`, qui est
        // pur JVM exactement pour pouvoir etre teste sans emulateur.
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

// **Ce module n'a pas de variante release, et c'est une garantie mecanique.**
//
// L'objet de `:sleepwriter` est d'ecrire dans Health Connect. Il declare donc `WRITE_SLEEP`, que
// Pendulum s'interdit — l'absence de permission d'ecriture cote `:phone` est un argument de
// conception verifiable par `aapt dump permissions`, et un APK signe de release portant ce nom
// affaiblirait l'argument aupres de quiconque le trouverait.
//
// Desactiver la variante plutot que l'ecrire dans un document : `./gradlew assembleRelease` a la
// racine ne produira jamais d'artefact ici, et l'oubli devient impossible au lieu d'etre
// simplement deconseille.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // `lifecycleScope`, qui porte aussi kotlinx-coroutines-android transitivement.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.health.connect.client)
    // `ComponentActivity` et `registerForActivityResult`, sans Compose. Version geree ici et non
    // dans `gradle/libs.versions.toml` pour ne pas toucher a un fichier que d'autres modules
    // editent en parallele — meme precedent que `:wear` pour `compose-foundation`. Elle suit
    // celle d'`activityCompose` du catalogue, qui resout la meme `androidx.activity`.
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
