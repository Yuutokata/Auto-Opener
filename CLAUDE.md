# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Kotlin/Ktor 3 (Netty, JVM 21) relay: a Discord bot (the Keyword Pinger, separate repo) sends product links over a service WebSocket or HTTP, and the server forwards them to the live WebSocket connections of the target user (a Chrome extension maintained by third parties, which we cannot change). `README.md` has the API; `.env.example` lists every variable.

**Compatibility rule:** the extension and the bot are outside this repo. Endpoints, paths, JSON shapes, status codes, token transport and close behavior are a contract. Anything incompatible must be opt-in (env flag, default off) and documented in `docs/extension-changes.md` (extension) or `docs/keyword-pinger-prompt.md` (bot). Keep these two files in sync when the contract changes. All files in the repo are written in English.

## Commands

Use `gradlew.bat` on Windows, `./gradlew` elsewhere.

- Run: `./gradlew run` (needs `BOT_TOKEN`, `JWT_SECRET`, `MONGO_URI`; reads env vars, then a local `.env`)
- Build + tests: `./gradlew build`; tests only: `./gradlew test`; one test: `./gradlew test --tests "de.yuuto.autoOpener.TokenRoutesTest"`
- Distribution: `./gradlew installDist` (output `build/install/Auto-Opener`)
- No linter is configured.

## Architecture

Package `de.yuuto.autoOpener`, wiring in `AutoOpener.kt` (`Application.module(config, users?, registry?)`). Tests call `module` with an `InMemoryUserRepository`; production builds a `CachedUserRepository(MongoUserRepository)`.

- `config/AppConfig`: typed settings parsed from env, fails fast (`ConfigurationException`) on missing/invalid values. Add new settings here and to `.env.example`.
- `auth/`: two JWT schemes, `auth-user` (claim `token` = Discord id, must exist in the user store) and `auth-service` (claim `token` = SHA-256 of a configured service token, see `ServiceTokens`). Token comes from `Authorization: Bearer` or `Sec-WebSocket-Protocol` (echoed back in `configureWebSockets`, browsers require that). Failures answer 401.
- `users/`: `UserRepository` interface; the Mongo implementation uses the official Kotlin driver with `bson-kotlinx` and writes via `updateOne` upserts (never replaces `_id`). Secrets are stored only as SHA-256 hash (`UserSecrets`); a null hash on upsert keeps the stored one.
- `routes/`: HTTP routes. `/token` implements the optional per-user secret (`REQUIRE_USER_SECRET`).
- `websocket/`: `ConnectionRegistry` (live connections, per-user limit with oldest-first eviction), `ConnectionManager` (register, read frames, unregister; ping/pong/close handling), `KeepAlive` (app-level pings, closes silent connections; Ktor's own ping is off), `UserNotifier` (parallel delivery with timeout), `BotMessageHandler` (frames from the bot).
- `plugins/`: content negotiation, default security headers, `StatusPages` (generic error bodies), per-client rate limits (`RateLimiting.kt`: key = client IP for `/token`, JWT subject elsewhere, IP taken from `CLIENT_IP_HEADER`, last entry).

Two delivery formats are intentional: messages from the bot socket reach the extension as the JSON object `{"message","url"}`, while `POST /send_message` sends the bare URL.

## Conventions

- Logging is plain SLF4J text (`logback.xml`, level via `LOG_LEVEL`). Put ids in the message; never log tokens, secrets or JWTs.
- Dependency versions live in `gradle/libs.versions.toml`.
- Tests use Ktor `testApplication` against the in-memory repository (`src/test/.../TestSupport.kt`); WebSocket tests wait for registration with `awaitConnections`.
