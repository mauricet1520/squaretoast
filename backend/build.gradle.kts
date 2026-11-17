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
