pluginManagement {
    includeBuild("build-logic")
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }

rootProject.name = "squaretoast"
include(":backend", ":backend:domain", ":backend:payment-adapters", ":android-pos", ":web-kds-qr")
