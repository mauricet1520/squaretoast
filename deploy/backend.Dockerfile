# The jar is built on the developer's PC (./gradlew :backend:bootJar) and copied in, so the small server never runs Gradle.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 squaretoast
WORKDIR /app
COPY app.jar /app/app.jar
USER squaretoast
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
