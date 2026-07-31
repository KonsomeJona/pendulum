plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `:algo` ne doit dependre de rien (docs/ALGO-v2.md §4) : l entree est l interface SampleBlock,
    // l adaptateur au-dessus de com.pendulum.format.DecodedBlock vit dans `:phone`.

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
