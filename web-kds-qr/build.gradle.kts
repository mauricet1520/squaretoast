// Browser KDS and guest QR pages (plan §6, S6-10/S6-11). Gradle only drives npm: `check` runs Vitest and tsc,
// `assemble` builds dist/, which the Android POS packs into its assets for the LAN hub.
plugins { base }

val npm = if (System.getProperty("os.name").lowercase().contains("windows")) listOf("cmd", "/c", "npm") else listOf("npm")

val npmCi by tasks.registering(Exec::class) {
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
    commandLine(npm + listOf("ci", "--no-audit", "--no-fund"))
}

val sources = listOf("src", "public", "index.html", "vite.config.ts", "tsconfig.json", "package-lock.json")

val webTest by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    inputs.files(sources, rootProject.file("shared-test-vectors"))
    val stamp = layout.buildDirectory.file("webTest.ok")
    outputs.file(stamp)
    commandLine(npm + listOf("test"))
    doLast { stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok") } }
}

val typecheck by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    inputs.files(sources)
    val stamp = layout.buildDirectory.file("typecheck.ok")
    outputs.file(stamp)
    commandLine(npm + listOf("run", "typecheck"))
    doLast { stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok") } }
}

val webBuild by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    inputs.files(sources)
    outputs.dir("dist")
    commandLine(npm + listOf("exec", "--", "vite", "build"))
}

tasks.named("check") { dependsOn(webTest, typecheck) }
tasks.named("assemble") { dependsOn(webBuild) }
