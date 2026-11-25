import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    "errorprone"(lib("errorprone-core"))
    "errorprone"(lib("nullaway"))
    "implementation"(lib("jspecify"))
    "testImplementation"(platform(lib("spring-boot-bom")))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("org.assertj:assertj-core")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val errorproneArgs = rootProject.file("gradle/errorprone.args").readLines()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    options.errorprone.errorproneArgs.addAll(errorproneArgs)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Jackson 2 compile fence (CLAUDE.md invariant 2): only :backend:payment-adapters may compile
// against com.fasterxml.jackson databind/core. jackson-annotations stays allowed: Jackson 3 uses it.
// Runtime classpaths are untouched (third-party libs such as Flyway may need Jackson 2 internally).
if (path != ":backend:payment-adapters") {
    configurations.matching { it.name.endsWith("ompileClasspath") }.configureEach {
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "com.fasterxml.jackson.dataformat")
        exclude(group = "com.fasterxml.jackson.datatype")
        exclude(group = "com.fasterxml.jackson.module")
        exclude(group = "org.springframework.boot", module = "spring-boot-jackson2")
    }
}
