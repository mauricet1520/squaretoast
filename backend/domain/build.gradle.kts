plugins {
    `java-library`
    id("squaretoast.java-conventions")
}

dependencies {
    api(libs.jspecify)
    // NullAwayEnforcementTest compiles canary sources in-process with the real build flags.
    testImplementation(libs.errorprone.core)
    testImplementation(libs.nullaway)
    // Reads the JSON vectors shared with the Android POS (test classpath only; domain stays dependency-free).
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("tools.jackson.core:jackson-databind")
}

sourceSets["test"].resources.srcDir(rootProject.file("shared-test-vectors"))

tasks.test {
    jvmArgs(
        listOf("api", "file", "main", "model", "parser", "processing", "tree", "util")
            .map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" } +
        listOf("code", "comp").map { "--add-opens=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" }
    )
    systemProperty("squaretoast.errorproneArgs", rootProject.file("gradle/errorprone.args").absolutePath)
}
