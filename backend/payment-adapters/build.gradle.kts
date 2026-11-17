plugins {
    `java-library`
    id("squaretoast.java-conventions")
}

dependencies {
    api(project(":backend:domain"))
}
