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

    // `ThresholdPolicySweepTest` porte des mesures de decision, pas des assertions : elles sont
    // `@Disabled` parce qu'elles durent des dizaines de minutes, pas parce qu'elles seraient
    // fragiles. `-Palgo.runDisabled` les rejoue a la demande et laisse passer leur sortie standard,
    // qui est tout leur produit. Sans le drapeau, rien ne change pour la CI.
    if (project.hasProperty("algo.runDisabled")) {
        systemProperty("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
        testLogging { showStandardStreams = true }
    }

    // Rejoue la suite de non-regression sous une autre valeur de `calFraction`, sans toucher au
    // defaut du produit. Voir `RegressionSupport.REGRESSION_CAL_FRACTION` pour le pourquoi : une
    // recommandation de reglage ne se presente pas sans la liste de ce qu'elle casse.
    project.findProperty("algo.calFraction")?.let { systemProperty("algo.calFraction", it.toString()) }
}
