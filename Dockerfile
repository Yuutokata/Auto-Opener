# Verwende OpenJDK 21 als Base Image
FROM openjdk:21-jdk-slim

# Installiere notwendige Pakete
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Setze Arbeitsverzeichnis
WORKDIR /app

# Kopiere Gradle Wrapper und Build-Dateien
COPY gradlew .
COPY gradlew.bat .
COPY gradle ./gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Mache Gradle Wrapper ausführbar
RUN chmod +x ./gradlew

# Kopiere Quellcode
COPY src ./src

# Kopiere resources
COPY src/main/resources ./src/main/resources

# Baue die Anwendung
RUN ./gradlew build --no-daemon

# Erstelle das JAR
RUN ./gradlew installDist --no-daemon
# Starte die Anwendung

EXPOSE 8080
CMD ["./gradlew", "run", "--no-daemon"]
