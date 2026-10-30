# Auto-Opener

Kotlin/Ktor backend that connects a Discord bot (the Keyword Pinger) with users of a Chrome extension. The extension keeps a WebSocket open; when the bot finds a matching product it sends the link here and the server forwards it to every live connection of that user, so the extension can open it immediately.

## Features

- JWT authentication for users (extension) and services (bot), optional per-user secret
- WebSocket endpoints for users and the bot, with application-level keep-alive
- HTTP API for token issuance, user synchronization and message dispatch
- MongoDB-backed user store with an in-memory cache
- Per-client rate limiting
- Plain console logging, configured through environment variables

## Getting started

Requirements: JDK 21 (Gradle downloads the toolchain if needed) and a MongoDB instance.

```bash
cp .env.example .env   # then fill in BOT_TOKEN, JWT_SECRET and MONGO_URI
./gradlew run          # gradlew.bat on Windows
./gradlew test
```

The server refuses to start when `BOT_TOKEN`, `JWT_SECRET` or `MONGO_URI` are missing or invalid.

## HTTP API

| Endpoint | Auth | Purpose |
| --- | --- | --- |
| `GET /health` | none | Liveness |
| `POST /token` | none | Issue a JWT. Body: `{"token": "<discord id or service token>", "role": "user" \| "service", "secret": "<user secret>"}` |
| `POST /users` | service | Synchronize all users (adds missing, removes absent) |
| `POST /users/add` | service | Add or update one user; optional `secret` (64 hex characters) is stored as a hash |
| `POST /users/remove` | service | Remove a user and disconnect their sockets |
| `POST /send_message?user_id=<id>` | service | Send `{"message", "url"}`; the extension receives the bare URL |

Tokens are sent as `Authorization: Bearer <jwt>` or, for browsers, in the `Sec-WebSocket-Protocol` header.

## WebSocket endpoints

- `GET /listen/{userId}`: user socket, requires a user JWT whose id matches the path. Up to `MAX_CONNECTIONS_PER_USER` connections per user; the oldest is closed with code 1008 when the limit is exceeded.
- `GET /bot`: service socket. The bot sends `{"userId": "...", "message": {"message": "...", "url": "..."}}` and receives a status reply per message.

The server pings every connection every `PING_INTERVAL` seconds and closes connections that stay silent for `PONG_TIMEOUT` seconds afterwards.

## Configuration

All settings are environment variables (a local `.env` is read as fallback). See `.env.example` for the complete list with comments.

Behind a reverse proxy set `CLIENT_IP_HEADER` (for example `X-Forwarded-For`), otherwise all clients share the proxy's address for rate limiting.

### Per-user secrets

1. The bot generates a 32-byte hex secret per user and registers it with `POST /users/add`.
2. The extension sends it to `POST /token`.
3. Once everyone migrated, set `REQUIRE_USER_SECRET=true`.

See `docs/keyword-pinger-prompt.md` (bot side) and `docs/extension-changes.md` (extension side).

## Docker

```bash
docker build -t auto-opener .
docker run --env-file .env -p 8080:8080 auto-opener
```

The image is multi-stage, runs as a non-root user and has a health check on `/health`.

## License

[MIT License](LICENSE)
