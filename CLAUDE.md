# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Kotlin/Ktor 3 (Netty, JVM 21) backend that relays messages from service clients (Discord bots) to end users (a Chrome extension) over authenticated WebSockets. Users are stored in MongoDB. See `README.md` for the API and env var tables; `.env.example` lists every variable.

## Commands

Use `gradlew.bat` on Windows, `./gradlew` elsewhere.

- Run: `./gradlew run` (main class `de.yuuto.autoOpener.AutoOpenerKt`; reads env vars or a local `.env`)
- Build: `./gradlew build`; distribution: `./gradlew installDist` (Docker runs `build/install/Auto-Opener/bin/Auto-Opener`)
- Tests: none exist and no test dependencies are configured yet. Add JUnit/`ktor-server-test-host` to `build.gradle.kts` before writing any. A single test would run with `./gradlew test --tests "<fqcn>"`.
- No linter (ktlint/detekt) is configured.

## Architecture

**Wiring.** `AutoOpener.kt` holds a global `dependencyProvider` (`DispatcherProvider`, `MongoClient`, `WebSocketManager`) created at class-load time. `Application.module()` installs plugins in order: serialization, security (JWT), rate limit, security headers, monitoring, websockets, routing. Constructor injection is passed to routes, but `plugins/Security.kt` reaches into the global `dependencyProvider` directly. A JVM shutdown hook (not Ktor lifecycle events) closes sockets, the manager, Mongo and dispatchers.

**Auth.** Two JWT (HMAC256) schemes, both with the same issuer/audience from config:
- `auth-user`: claim `role=user`, `token` = a 15–20 digit Discord ID that must exist in the `users` collection (cache first, then DB).
- `auth-service`: claim `role=service`, `token` must be in the `BOT_TOKEN` list.
Tokens are minted by `POST /token` (`routes/generateToken.kt`). A JWT is read from `Authorization: Bearer` or, for browsers that cannot set headers on WebSockets, from `Sec-WebSocket-Protocol`. `Websocket.kt` echoes that header back in a pipeline interceptor so the handshake succeeds.

**WebSockets.** `plugins/Websocket.kt` defines `/listen/{userId}` (user, one live connection per user: older ones are cleaned up on reconnect) and `/bot` (service) using `webSocketRaw`, and hands off to `util/WebSocketManager.kt`. The manager is the core:
- Connection IDs encode identity: `<userId>_<uuid>` for users, `bot_<uuid>` for bots. `extractUserId`/`extractBotId` and the `startsWith("bot_")` checks depend on this format.
- `activeConnections`, `userSessions`, `botSessions` are separate maps that must stay consistent. A 60s loop (`verifySessions`, `verifySessionIntegrity`) repairs drift.
- Bot text frames are `WebsocketReceive` JSON (`userId` + `message`), forwarded to every active session of that user, and the bot receives a `BotResponse` status. `POST /send_message` (`routes/messageRoutes.kt`) is the HTTP equivalent.
- Keepalive is app-level: Ktor `pingPeriod` is disabled; `monitorConnections()` sends custom pings and closes connections that miss the pong timeout.

**Persistence.** `util/MongoClient.kt` wraps KMongo (coroutine) with an in-memory `userCache` that is write-through on add/remove and fully refreshed on demand. `POST /users` synchronizes by diffing the incoming list against the DB (adds missing, removes absent).

**Concurrency.** `DispatcherProvider` exposes separate sized dispatchers (network, database, processing, monitoring, websocket, heartbeat), configured via `*_PARALLELISM` env vars. Use the matching dispatcher with `withContext`.

**Config.** `util/Config.kt` is the single source for settings: `System.getenv` → `.env` → hardcoded default. Several defaults are insecure placeholders (`JWT_SECRET=secret`, `BOT_TOKEN=testtoken`); there is no fail-fast on missing values.

## Conventions and gotchas

- Logging is structured: set `MDC.put("event_type", ...)` plus context keys, log, then `MDC.clear()`. Follow this pattern; logback config is in `src/main/resources/logback.xml` (JSON/Loki appenders).
- Dependency versions are hardcoded in `build.gradle.kts` (no version catalog), updated by Renovate.
- `README.md` says the server defaults to port 8080 and the Dockerfile exposes 8080, but `.env.example` uses 5064; check `SERVER_PORT`.
- The Dockerfile is single-stage and runs as root; `PONG_TIMEOUT` is in seconds in `.env.example` but the code default is `30000`.
