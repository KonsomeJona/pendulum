pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pendulum"

include(":algo")
include(":format")
include(":wear")
include(":phone")
// Outil de banc, debug uniquement, jamais publie : il declare `WRITE_SLEEP` que Pendulum
// s'interdit. Sa variante release est desactivee dans son `build.gradle.kts`.
include(":sleepwriter")
