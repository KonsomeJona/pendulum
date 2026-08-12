plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pendulum.wear"
    compileSdk = 36

    defaultConfig {
        // The Data Layer only delivers events between applications with the **same package name
        // and the same signature**: `applicationId` is therefore shared with the `phone` module,
        // only the `namespace` differs. Direct consequence: no relative class name (`.Xxx`) in the
        // manifest any more, since relative names resolve against `applicationId`.
        applicationId = "com.pendulum"
        minSdk = 33
        targetSdk = 35
        // See the root `build.gradle.kts`: a single definition for both applications, injected
        // from the Git tag at publication. Both modules must carry the same value — the Data Layer
        // only exchanges between packages with the same `applicationId`, and a watch newer than its
        // phone is a situation we want to be able to read.
        versionCode = rootProject.extra["pendulumVersionCode"] as Int
        versionName = rootProject.extra["pendulumVersionName"] as String

        // Instrumented tests. They did not exist here, and the bench added them through a patch
        // applied to this file on the fly — a "do not commit" patch, therefore a patch one
        // forgets. What they check is checked nowhere else: that a permission required by a
        // component really exists on the device (`ComponentPermissionsTest`).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing.
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
    // The field is declared **only** in the `debug` block. That is what gives the
    // `src/release/.../TimeScaling.kt` twin its guarantee: the release variant compiles against a
    // `BuildConfig` that does not carry this field, so the time compression code does not exist
    // there — it is not disabled there, it is not compiled there.
    //
    // On the bench: `./gradlew -Ppendulum.temps.diviseur=600 :wear:assembleDebug`. The value passed
    // to `:wear` and to `:phone` must be the same, which only the single invocation guarantees.
    val timeDivisor = (project.findProperty("pendulum.temps.diviseur") as String?)
        ?.toLongOrNull()
        ?.also { require(it >= 1L) { "pendulum.temps.diviseur must be at least 1, got $it" } }
        ?: 1L

    // The bench drives the watch over USB, therefore on charge, therefore `StopConditions` stops it
    // after a minute. That is the right rule at night and the only one that prevents a plugged-in
    // run.
    // `./gradlew -Ppendulum.banc.ignorer.chargeur=true :wear:assembleDebug`
    val ignoreCharger = (project.findProperty("pendulum.banc.ignorer.chargeur") as String?)
        ?.toBooleanStrictOrNull() ?: false

    buildTypes {
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            // Personal sideload: no minification, a readable exception trace is worth more than
            // the few hundred kilobytes saved.
            isMinifyEnabled = false
        }
        debug {
            buildConfigField("long", "TEMPS_DIVISEUR", "${timeDivisor}L")
            buildConfigField("boolean", "BANC_IGNORER_CHARGEUR", "$ignoreCharger")
        }
    }

    buildFeatures {
        compose = true
        // Only for `TEMPS_DIVISEUR` above.
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
        // Same platform as `:format`, `:algo` and `:phone`: JUnit 5. The targets are the pure
        // classes of `record/` (SensorStrategy, GapMonitor, StopConditions), whose KDoc explicitly
        // promise JVM coverage.
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":format"))

    // **`debugImplementation`, and not `implementation`.** `:wear` only depends on `:format` — this
    // is not a habit, it is an architectural choice, and it keeps holding where it was stated: in
    // the published artefact. A `debugImplementation` dependency is neither on the compile path nor
    // on the runtime path of the release variant.
    //
    // Why generate rather than replay a file dropped on the device: `NightSynth` is deterministic
    // up to the seed — same `seed`, **bit-identical** output (test T13 of `:algo`). That is what
    // makes the bench's central assertion possible, "the end-to-end result must equal the JVM
    // result on the same signal": the two sides agree on an integer, not on an eleven-megabyte file
    // that would have to be transported, versioned and trusted. The ground truth also remains
    // available on the watch side.
    //
    // The cost, to be measured and not assumed: the generator holds the whole night in memory —
    // three `DoubleArray(n)`, that is ~35 MB for 8 h at 50 Hz, plus ~17 MB of emitted blocks. If a
    // device cannot hold them, it is the length of the night that gets shortened; the seam itself
    // does not change.
    debugImplementation(project(":algo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.wear.remote.interactions)
    // Already pulled in transitively by WorkManager and Compose; declared explicitly because the
    // service and the workers depend on it directly.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    // Version managed by the Compose BOM; not in the catalogue, added here so as not to touch
    // `gradle/libs.versions.toml`, which other modules edit in parallel.
    implementation("androidx.compose.foundation:foundation")
    // `collectAsStateWithLifecycle` and `LifecycleResumeEffect`: the first guarantees that
    // collection stops when the screen goes off — the "zero recomposition between going to bed and
    // waking" criterion — and the second re-runs the preflight when the user comes back from the
    // settings.
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // JUnit 4 and not 5: the Android orchestrator does not run the JUnit 5 platform. These
    // dependencies do not go into the published APK — `androidTestImplementation` is neither on the
    // compile path nor on the runtime path of the ordinary variants.
    //
    // `:format` and the Data Layer client are here for the `tools/banc/` probes, which land in this
    // source set: having them here avoids touching this file at every bench deployment, which was
    // the only known way to commit a bench patch by accident.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":format"))
    androidTestImplementation(libs.play.services.wearable)
}
