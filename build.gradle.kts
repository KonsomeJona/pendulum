plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// The version, in a single place for both applications.
//
// It used to be hard-coded — `versionCode = 1`, `versionName = "0.1.0"` — in both modules, and the
// release workflow did not inject it: it merely **renamed the files** with the Git tag. Every
// delivery therefore carried the same version. Three consequences, none of them visible in the
// repository: the Settings screen displayed "0.1.0" whatever the installed artefact — while
// `buildConfig` was enabled precisely to avoid that particular lie; nothing, neither in the
// database nor in the chunks, said which version had produced a figure, for an application whose
// whole value is comparability over time; and an update over a version with the same
// `versionCode` is refused by the system.
//
// `-Ppendulum.versionName=1.2.0 -Ppendulum.versionCode=10203` when releasing; the default values
// below serve local builds, where the version has no meaning.
val pendulumVersionName: String =
    (providers.gradleProperty("pendulum.versionName").orNull ?: "0.0.0-dev")
val pendulumVersionCode: Int =
    (providers.gradleProperty("pendulum.versionCode").orNull?.toIntOrNull() ?: 1)

extra["pendulumVersionName"] = pendulumVersionName
extra["pendulumVersionCode"] = pendulumVersionCode

// Lint, configured once for the three Android modules.
//
// Three modules configuring lint each on their own side diverge at the first setting added to only
// one of the three, and the divergence is silent since it is read in a report nobody compares.
// Here there is only one place to look.
//
// `abortOnError` stays at `true`: it is what surfaced the one real lint error this project ever
// had. `checkAllWarnings` stays at `false` — it enables hundreds of rules disabled by default, and
// a report people stop reading no longer protects anything.
//
// No `lint-baseline.xml`, and that is deliberate: a baseline is a generated file, with no room for
// the **why**. This repository has already invented the right shape for that problem — the
// `tolerances` map of `DurationsInventoryTest`, where every exception carries its reason in one
// sentence. The `disable` below is the same gesture; a baseline would be its opposite.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                disable += setOf(
                    // `targetSdk` 35 under `compileSdk` 36 is the intended setting, not an
                    // oversight: targeting 35 exempts the application from the Android 16
                    // behaviour changes, which is exactly what is wanted of a foreground service
                    // that must survive eight hours. To be revisited the day a store requires 36.
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

// Global rule: every build output lands on the D drive.
// The redirection only happens if the root is really reachable, so that the project
// stays buildable on a machine without D (CI, remote Mac).
val buildRoot: String? = (providers.gradleProperty("pendulum.buildRoot").orNull)
    ?.takeIf { File(it).parentFile?.isDirectory == true || File(it).isDirectory }

if (buildRoot != null) {
    allprojects {
        layout.buildDirectory.set(File(buildRoot, project.path.replace(':', '/').trim('/').ifEmpty { "root" }))
    }
}
