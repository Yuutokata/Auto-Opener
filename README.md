# Auto-Opener

Auto-Opener is a Kotlin/Ktor backend that manages authenticated WebSocket connections between services (bots) and end users. It issues JWTs, validates identities, stores known users in MongoDB, and delivers real-time messages to active user sessions.

## Key Features

- JWT-based authentication for user and service clients
- WebSocket endpoints for user listeners and bot/service connections
- HTTP APIs for token issuance and user synchronization
- MongoDB-backed user validation and caching
- Rate limiting for token, service, and WebSocket traffic
- Structured logging and Micrometer/Prometheus metrics integration
- Environment-variable based configuration

## Tech Stack

- Kotlin (JVM 21)
- Ktor 3 (Netty)
- MongoDB (KMongo)
- Gradle (Kotlin DSL)
- Docker (optional runtime)

## Getting Started

### Prerequisites

- JDK 21
- MongoDB instance

### 1) Configure environment variables

Create a `.env` file in the project root (or export variables directly):

```bash
cp .env.example .env
```

At minimum, update these values for non-local usage:

- `JWT_SECRET`
- `BOT_TOKEN` (comma-separated if multiple)
- `MONGO_URI`
- `MONGO_DB`

### 2) Run locally

```bash
./gradlew run
```

The server binds to `HOST` and `SERVER_PORT` (`0.0.0.0:8080` by default when not overridden).

## API Overview

### Health

- `GET /health` - basic liveness response

### Token issuance

- `POST /token`
  - Request body:
    ```json
    {
      "token": "<user_id_or_service_token>",
      "role": "user"
    }
    ```
  - `role` can be `user` or `service`.
  - Returns a signed JWT on success.

### User management (service-authenticated)

These routes require a valid **service JWT**:

- `POST /users` - bulk synchronize users
- `POST /users/add` - add or update one user
- `POST /users/remove` - remove one user

### Message dispatch (service-authenticated)

- `POST /send_message?user_id=<discord_user_id>`
  - Requires service JWT
  - Sends payload to active WebSocket sessions of the target user

## WebSocket Endpoints

- `GET /listen/{userId}`
  - User endpoint
  - Requires a valid **user JWT**
- `GET /bot`
  - Service/bot endpoint
  - Requires a valid **service JWT**

JWTs can be provided via:

- `Authorization` header (JWT)
- `Sec-WebSocket-Protocol` header (token value)

## Configuration

Configuration is loaded from environment variables (and optionally `.env` via dotenv). See `.env.example` for the full list.

| Variable | Purpose |
| --- | --- |
| `HOST` | Server bind host |
| `SERVER_PORT` | Server port |
| `MONGO_URI` | MongoDB connection URI |
| `MONGO_DB` | MongoDB database name |
| `BOT_TOKEN` | Comma-separated allowed service tokens |
| `JWT_SECRET` | JWT signing secret |
| `JWT_ISSUER` | JWT issuer |
| `JWT_AUDIENCE` | JWT audience |
| `RATE_LIMIT_TOKEN` / `RATE_LIMIT_TOKEN_DURATION` | Token endpoint rate limit |
| `RATE_LIMIT_WS` / `RATE_LIMIT_WS_DURATION` | WebSocket rate limit |
| `RATE_LIMIT_SERVICE` / `RATE_LIMIT_SERVICE_DURATION` | Service endpoint rate limit |
| `HEALTH_CHECK_INTERVAL` | Connection health check interval |
| `PONG_TIMEOUT` | WebSocket pong timeout |
| `INACTIVITY_THRESHOLD` | Inactivity threshold |
| `WS_TIMEOUT` | WebSocket timeout |
| `WS_MAX_FRAME_SIZE` | Maximum WebSocket frame size |
| `WS_MASKING` | Enable WebSocket masking |
| `NETWORK_PARALLELISM`, `DATABASE_PARALLELISM`, `PROCESSING_PARALLELISM`, `MONITORING_PARALLELISM`, `WEBSOCKET_PARALLELISM`, `HEARTBEAT_PARALLELISM` | Dispatcher sizing |
| `MONITORING_INTERVAL_SECONDS` | Monitoring loop interval |

## Docker

Build and run:

```bash
docker build -t auto-opener .
docker run --env-file .env -p 8080:8080 auto-opener
```

## Security Notes

- Use strong, unique values for `JWT_SECRET` and `BOT_TOKEN`
- Terminate TLS at your edge/proxy in production
- Rotate service tokens regularly
- Tune rate limits for your traffic profile

## License

MIT (see `LICENSE`)
