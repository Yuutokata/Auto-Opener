FROM amazoncorretto:21-alpine-jdk

RUN apk --no-cache add curl

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY gradlew .
COPY gradlew.bat .
COPY gradle ./gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

RUN chmod +x ./gradlew

COPY src ./src
COPY src/main/resources ./src/main/resources

RUN ./gradlew build --no-daemon
RUN ./gradlew installDist --no-daemon

EXPOSE 8080

CMD ["./gradlew", "run", "--no-daemon"]