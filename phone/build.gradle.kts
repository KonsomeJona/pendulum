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
        // **Shared with the `wear` module.** The Wearable Data Layer only exchanges between
        // applications with the same `applicationId` and the same signature: if the two modules
        // declare different identifiers, the watch and the phone simply never see each other,
        // without the slightest error message. Only the `namespace` differs.
        // Consequence: no more relative names (`.Xxx`) in the manifest, since a relative name
        // resolves against `applicationId` and not against `namespace`.
        applicationId = "com.pendulum"
        minSdk = 30
        targetSdk = 35
        // Injected by the publication workflow from the Git tag, development default otherwise.
        // The detail is in the root `build.gradle.kts`, which defines them once for both
        // applications — they must stay identical, the Data Layer exchanging only between
        // packages with the same `applicationId`.
        versionCode = rootProject.extra["pendulumVersionCode"] as Int
        versionName = rootProject.extra["pendulumVersionName"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Publication signing.
    //
    // The keystore is **never** in the repository: CI materialises it from a base64-encoded
    // secret, and locally it is simply absent. In that case we do not silently fall back on the
    // debug key — a debug-signed artefact passing itself off as a release is exactly the kind of
    // confusion that ends up being published. `assembleRelease` therefore fails cleanly locally,
    // and only CI produces signed artefacts.
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

    // Time divisor of the instrumented bench.
    //
    // The field is declared **only** in the `debug` block, exactly as in `:wear`. That is what
    // gives the `src/release/.../TimeScaling.kt` twin its guarantee: the release variant compiles
    // against a `BuildConfig` that does not carry this field.
    //
    // On the bench: `./gradlew -Ppendulum.temps.diviseur=600 :wear:assembleDebug :phone:assembleDebug`,
    // in a single invocation — nothing in the code can establish that the two halves received the
    // same value.
    val timeDivisor = (project.findProperty("pendulum.temps.diviseur") as String?)
        ?.toLongOrNull()
        ?.also { require(it >= 1L) { "pendulum.temps.diviseur must be at least 1, got $it" } }
        ?: 1L

    buildTypes {
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            // No minification for now: Room + KSP + worker reflection call for keep rules that
            // we will not write before we need them.
            isMinifyEnabled = false
        }
        debug {
            buildConfigField("long", "TEMPS_DIVISEUR", "${timeDivisor}L")
        }
    }

    buildFeatures {
        compose = true
        // `BuildConfig.VERSION_NAME` feeds the "version" line of the Settings screen. It used to
        // be hard-coded there ("0.1.0"), so correct until the first bump and wrong afterwards —
        // exactly the kind of value a user quotes in a defect report.
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
        // `:format` and `:algo` are tested with JUnit 5; the JVM tests of `:phone` share their
        // fixtures, hence the same platform.
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    // The Room schemas are **versioned**: they are what makes a migration verifiable. Without
    // schema export, `fallbackToDestructiveMigration` becomes the only way out when a migration
    // turns out to be wrong — and that is precisely what we forbid ourselves here, because the raw
    // chunks are the only thing that will make it possible to rescore when the algorithm changes.
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
    // `RemoteActivityHelper`: opening the Play Store listing **on the watch** from the onboarding.
    // It is the only path that does not get blocked, because the launch is carried out over there
    // by Google Play Services and not by our process. The library was already in the catalogue,
    // used by `:wear` in the other direction.
    implementation(libs.androidx.wear.remote.interactions)
    // `startRemoteActivity` returns a `ListenableFuture`, and this class is **not** on the
    // compilation path of `:phone`: `androidx.health.connect` pulls in the full Guava, which makes
    // `com.google.guava:listenablefuture` be replaced by the
    // `9999.0-empty-to-avoid-conflict-with-guava` artefact, a literally empty jar. The symptom is
    // "Cannot access class ListenableFuture" while the same line compiles in `:wear`, where the
    // full Guava is absent and the real artefact is therefore kept.
    //
    // `compileOnly` and not `implementation`: Guava is **already** in the APK by way of Health
    // Connect, so declaring it here does not cost one more byte — it only makes the type visible
    // to the compiler. The version follows the one the runtime path already resolves.
    compileOnly("com.google.guava:guava:31.1-android")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Instrumented tests. They run on device and validate what no JVM test can reach: that the
    // guard rails hold in the application as it is actually assembled, with its navigation, its
    // theme and its SQLite database. JUnit 4 and not 5: the Android orchestrator does not run the
    // JUnit 5 platform.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Raises Espresso above what the Compose BOM pins. The detail of the defect thereby avoided is
    // in `libs.versions.toml`. It is declared here and not in `:wear` because this is the only
    // module whose instrumented tests go through Compose, hence the only one that touches Espresso.
    androidTestImplementation(libs.androidx.test.espresso.core)
    // `room-testing` was removed on 7 August 2026: it brought nothing but `MigrationTestHelper`,
    // which even the old `MigrationTest` did not use, and it has had no purpose since the database
    // restarted at v1 with no migration. It will come back with the first real migration — it is
    // what makes it possible to rebuild a database of an earlier version from the exported
    // schemas, and that is why `exportSchema` stays at `true`.
    // For the probes of `tools/banc/`, which land in this source set. Having them here avoids the
    // build fix applied on the fly by `datalayer.sh`, which carried the note "not to be committed"
    // — that is, an instruction that only an attentive human applies.
    androidTestImplementation(project(":format"))
    androidTestImplementation(libs.play.services.wearable)
    debugImplementation(libs.compose.ui.test.manifest)
}
