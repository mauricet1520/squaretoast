plugins {
    id("squaretoast.java-conventions")
    alias(libs.plugins.spring.boot)
    `jvm-test-suite`
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":backend:domain"))
    implementation(project(":backend:payment-adapters"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // B1: Argon2id password hashes only (no Spring Security filter chain); BouncyCastle provides Argon2.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.archunit)
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":backend:payment-adapters")))
                implementation(project(":backend:domain"))
                implementation("org.springframework:spring-jdbc")
                implementation(libs.jspecify)
                implementation(platform(libs.spring.boot.bom))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:testcontainers-junit-jupiter")
                implementation("org.testcontainers:testcontainers-postgresql")
                implementation("org.flywaydb:flyway-core")
                implementation("tools.jackson.core:jackson-databind")
                runtimeOnly("org.flywaydb:flyway-database-postgresql")
                runtimeOnly("org.postgresql:postgresql")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
            targets.all { testTask.configure { shouldRunAfter(tasks.test) } }
        }
    }
}

// :backend:check covers the whole backend (compile + NullAway + JUnit); integration tests stay separate.
tasks.named("check") { dependsOn(":backend:domain:check", ":backend:payment-adapters:check") }
