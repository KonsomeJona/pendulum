plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `:algo` must depend on nothing (docs/workings/ALGO-v2.md §4): the input is the SampleBlock
    // interface, and the adapter over com.pendulum.format.DecodedBlock lives in `:phone`.

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    // `ThresholdPolicySweepTest` carries decision measurements, not assertions: they are
    // `@Disabled` because they take tens of minutes, not because they would be flaky.
    // `-Palgo.runDisabled` replays them on demand and lets their standard output through, which is
    // their entire product. Without the flag, nothing changes for CI.
    if (project.hasProperty("algo.runDisabled")) {
        systemProperty("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
        testLogging { showStandardStreams = true }
    }

    // Replays the regression suite under a different `calFraction`, without touching the product
    // default. See `RegressionSupport.REGRESSION_CAL_FRACTION` for why: a settings recommendation
    // is not presented without the list of what it breaks.
    project.findProperty("algo.calFraction")?.let { systemProperty("algo.calFraction", it.toString()) }
}
