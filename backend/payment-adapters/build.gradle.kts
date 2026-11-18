plugins {
    `java-library`
    `java-test-fixtures`
    id("squaretoast.java-conventions")
}

dependencies {
    api(project(":backend:domain"))
    testFixturesImplementation(libs.jspecify)
}
