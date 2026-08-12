plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pendulum.sleepwriter"
    compileSdk = 36

    defaultConfig {
        // **Deliberately different from `com.pendulum`.**
        //
        // `:phone` and `:wear` share the same `applicationId` because the Wearable Data Layer only
        // exchanges between applications with the same identifier and the same signature. This
        // module does not take part in the Data Layer: giving it the same identifier would simply
        // prevent it from being installed alongside Pendulum, and would replace it on the device.
        //
        // There is a second, more important reason: Health Connect sets a record's `dataOrigin`
        // from the **package that writes it**. That value is what `SleepSourceSelector` arbitrates.
        // Writing from `com.pendulum` would manufacture an origin that lies about what the bench
        // claims to simulate — a third-party application.
        applicationId = "com.pendulum.sleepwriter"
        // Aligned on `:phone`, which is the reader: there is no point writing on an Android version
        // where Pendulum cannot read.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // **Two packages, because `E-HC-03` cannot be simulated any other way.**
    //
    // The "two conflicting sources" scenario requires two distinct `dataOrigin` values on the same
    // night. Since the origin is the writer's package name, two APKs really installable side by
    // side are needed — a runtime flag, a preference or a `Metadata` field would change nothing
    // about what Health Connect records.
    //
    // Same code, same manifest, same debug signature: only the identifier differs, plus a label
    // readable in the logs and on the icon.
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
        // `BuildConfig.SOURCE_LABEL` and `BuildConfig.APPLICATION_ID` are written into every log
        // line: without them, two APKs logging under the same tag are indistinguishable in
        // `adb logcat`, which is exactly the case in the conflict scenario.
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
        // Same platform as the other modules: JUnit 5. The target is `Hypnogram`, which is pure
        // JVM precisely so that it can be tested without an emulator.
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

// **This module has no release variant, and that is a mechanical guarantee.**
//
// The purpose of `:sleepwriter` is to write into Health Connect. It therefore declares
// `WRITE_SLEEP`, which Pendulum forbids itself — the absence of a write permission on the `:phone`
// side is a design argument verifiable with `aapt dump permissions`, and a signed release APK
// bearing this name would weaken that argument for anyone who came across it.
//
// Disabling the variant rather than writing it down in a document: `./gradlew assembleRelease` at
// the root will never produce an artefact here, and forgetting becomes impossible instead of
// merely discouraged.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // `lifecycleScope`, which also carries kotlinx-coroutines-android transitively.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.health.connect.client)
    // `ComponentActivity` and `registerForActivityResult`, without Compose. Version managed here
    // rather than in `gradle/libs.versions.toml` so as not to touch a file that other modules edit
    // in parallel — same precedent as `:wear` for `compose-foundation`. It follows the catalogue's
    // `activityCompose` version, which resolves the same `androidx.activity`.
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
