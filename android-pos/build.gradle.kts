plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.squaretoast.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.squaretoast.pos"
        minSdk = 26 // CLAUDE.md: minSdk >= 26; Tap to Pay itself is gated to API 33+ at runtime
        targetSdk = 36
        versionCode = 1
        versionName = "0.5.0"
        buildConfigField("String", "CLOUD_BASE_URL", "\"https://api.squaretoast.app\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric reads the merged manifest
    }

    // The shared JSON vectors are the contract with the backend's ports of the same rules.
    sourceSets["test"].resources.directories.add(rootProject.file("shared-test-vectors").path)
    // Exported Room schemas, so migration tests can build each old version exactly.
    sourceSets["test"].resources.directories.add(file("schemas").path)

    // Local device testing, debug builds only: -Psquaretoast.localCloudUrl=https://localhost:8443 points the POS at a
    // backend on this computer (reached over `adb reverse`) and trusts the local CA from scripts/local-dev. Release
    // builds never see this: they keep the production URL and the system trust store.
    providers.gradleProperty("squaretoast.localCloudUrl").orNull?.let { url ->
        require(url.startsWith("https://")) { "the cloud API is https only" }
        buildTypes["debug"].buildConfigField("String", "CLOUD_BASE_URL", "\"$url\"")
        sourceSets["debug"].manifest.srcFile("local-dev/AndroidManifest.xml")
        sourceSets["debug"].res.directories.add(file("local-dev/res").path)
    }
}

kotlin { jvmToolchain(21) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.android.core.ktx)
    implementation(libs.android.activity.compose)
    implementation(libs.android.lifecycle.viewmodel.compose)
    implementation(libs.android.hilt.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.stripe.terminal.core)
    implementation(libs.stripe.terminal.taptopay)
    implementation(libs.timber)
    implementation(libs.zxing.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

// The KDS web bundle (plan S6-11/S6-12): built by :web-kds-qr and packed into a generated assets folder, so the
// LAN hub can serve it at /kds. Nothing built is committed under src/.
abstract class CopyKdsBundle : DefaultTask() {
    @get:InputDirectory
    abstract val bundle: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:javax.inject.Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(bundle)
            into(outputDir.dir("kds"))
        }
    }
}

val copyKdsBundle = tasks.register<CopyKdsBundle>("copyKdsBundle") {
    dependsOn(":web-kds-qr:webBuild")
    bundle.set(rootProject.layout.projectDirectory.dir("web-kds-qr/dist"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyKdsBundle, CopyKdsBundle::outputDir) }
}
