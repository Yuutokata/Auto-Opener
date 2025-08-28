FROM openjdk:21-jdk-slim

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

LABEL "traefik.enable"="true" \
      "traefik.http.routers.myapp.rule"="Host(`yourdomain.com`)" \
      "traefik.http.routers.myapp.entrypoints"="websecure" \
      "traefik.http.routers.myapp.tls"="true" \
      "traefik.http.services.myapp.loadbalancer.server.port"="8080" \
      "traefik.http.middlewares.myapp-websocket-headers.headers.customRequestHeaders.Sec-WebSocket-Protocol"="{header.Sec-WebSocket-Protocol}" \
      "traefik.http.middlewares.myapp-websocket-headers.headers.customRequestHeaders.Sec-WebSocket-Key"="{header.Sec-WebSocket-Key}" \
      "traefik.http.middlewares.myapp-websocket-headers.headers.customRequestHeaders.Sec-WebSocket-Version"="{header.Sec-WebSocket-Version}" \
      "traefik.http.middlewares.myapp-websocket-headers.headers.customRequestHeaders.Sec-WebSocket-Extensions"="{header.Sec-WebSocket-Extensions}" \
      "traefik.http.routers.myapp.middlewares"="myapp-websocket-headers@docker" \
      "traefik.http.services.myapp.loadbalancer.server.scheme"="http" \
      "traefik.http.services.myapp.loadbalancer.sticky.cookie=true" \
      "traefik.http.services.myapp.loadbalancer.passHostHeader=true"

CMD ["./gradlew", "run", "--no-daemon"]
