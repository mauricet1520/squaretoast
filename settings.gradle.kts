pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "squaretoast"
include(":backend", ":backend:domain", ":backend:payment-adapters")
