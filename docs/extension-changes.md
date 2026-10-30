# Changes for the Chrome extension developers

The Auto-Opener server was hardened. Nothing in this list is required immediately: the server stays compatible with the current extension until the operator sets `REQUIRE_USER_SECRET=true`. Please plan the first item before that date.

## 1. Send a per-user secret to `POST /token` (required before enforcement)

Until now anyone who knew a Discord user id could request a token for that user. Every user now gets a secret from the Keyword Pinger bot (64 lowercase hex characters, shown once to the user).

Request body of `POST /token`:

```json
{ "token": "<discord user id>", "role": "user", "secret": "<64 hex characters>" }
```

- Add a settings field where the user pastes the secret and store it like the Discord id.
- If `secret` is sent it must match, otherwise the server answers `401` with `{"error": "Invalid user secret"}`.
- After enforcement a missing secret also leads to `401` with the same body. Show a message that points the user to the bot command that generates a secret.

## 2. Handle `401` for failed authentication

Failed authentication used to answer `200` with a plain-text body. It now answers `401`.

- For `POST /token` the body is JSON: `{"error": "..."}`. Read the `error` field.
- For the WebSocket handshake the browser only reports a failed connection (as before).
- The HTTP error texts of `/token` are unchanged where they existed (`User ID not found in database, ...`).

## 3. Expect close codes on `/listen/{userId}`

| Close code / reason | Meaning | What to do |
| --- | --- | --- |
| `1008` `Connection limit reached` | The user opened more than `MAX_CONNECTIONS_PER_USER` (default 3) connections; the oldest was closed. | Do not reconnect in a tight loop from the closed device. |
| `1008` `User removed` | The user was removed by the bot. | Stop reconnecting and ask the user to re-register. |
| `1008` `User ID mismatch` / `Invalid credentials` | The path user id does not match the token. | Fix the URL or fetch a new token. |
| `1001` `Ping timeout` | The server saw no frame from the client for `PONG_TIMEOUT` seconds after a ping. | Reconnect with backoff. |

Several devices per user are now supported at the same time.

## 4. Rate limits are now per client

Limits apply per client (IP for `/token`, user id for the WebSocket) instead of one shared bucket. A `429` still means "slow down": reconnect with exponential backoff and jitter instead of immediately. After a server restart all clients reconnect at once, so jitter matters.

## 5. Questions for the extension team

- Is the JWT cached or requested again for every session? Tokens are valid for 72 hours (`JWT_TTL_HOURS`); an open connection is not affected by expiry, but reconnecting with an expired token fails.
- Does the extension reconnect on every close code? If so, please apply the rules from section 3.
- Does anything depend on the exact text of `{"error": ...}` responses?

## Unchanged

Endpoints, the WebSocket paths, the token transport (`Authorization: Bearer` or the `Sec-WebSocket-Protocol` header) and the message format delivered to the extension (a JSON object `{"message", "url"}` for messages sent by the bot over its WebSocket, the bare URL for `POST /send_message`) stay as they are.
