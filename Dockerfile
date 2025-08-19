# Dockerfile für Auto-Opener

# Build-Stage mit passender JDK-Version (Kotlin JVM 21)
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle build --no-daemon

# Runtime-Stage mit passender JDK-Version
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# .env ist optional, ENV-Variablen können auch direkt gesetzt werden
EXPOSE 8080

# Starte die App, .env wird von der App automatisch geladen falls vorhanden
CMD ["java", "-jar", "app.jar"]
