plugins {
    `java-library`
    id("squaretoast.java-conventions")
}

dependencies {
    api(libs.jspecify)
    // NullAwayEnforcementTest compiles canary sources in-process with the real build flags.
    testImplementation(libs.errorprone.core)
    testImplementation(libs.nullaway)
}

tasks.test {
    jvmArgs(
        listOf("api", "file", "main", "model", "parser", "processing", "tree", "util")
            .map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" } +
        listOf("code", "comp").map { "--add-opens=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" }
    )
    systemProperty("squaretoast.errorproneArgs", rootProject.file("gradle/errorprone.args").absolutePath)
}
