plugins {
    `java-library`
    `java-test-fixtures`
    id("squaretoast.java-conventions")
}

dependencies {
    api(project(":backend:domain"))
    implementation(platform(libs.spring.boot.bom))
    // D3: Stripe over declarative @HttpExchange + RestClient, no vendor SDK; responses read with Jackson 3.
    implementation("org.springframework:spring-web")
    runtimeOnly("org.springframework:spring-context") // HttpServiceProxyFactory: AOP proxy + conversion service
    implementation("tools.jackson.core:jackson-databind")
    testFixturesImplementation(libs.jspecify)
}
