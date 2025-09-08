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

# Baue die Anwendung
RUN ./gradlew build --no-daemon

# Debug: Zeige den Inhalt des build-Verzeichnisses
RUN find build -name "*.jar" -type f

# Kopiere das JAR robuster
RUN find build -name "*.jar" -type f -exec cp {} app.jar \; || \
    (echo "No JAR found, trying alternative locations..." && \
     find . -name "*.jar" -type f -exec cp {} app.jar \;)

EXPOSE 8080

# Starte die Anwendung mit Java
CMD ["java", "-jar", "app.jar"]
