FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Resolve dependencies first so that source changes do not invalidate this layer.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /app/build/install/Auto-Opener ./

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT:-8080}/health" || exit 1

CMD ["/app/bin/Auto-Opener"]
