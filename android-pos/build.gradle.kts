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
}
