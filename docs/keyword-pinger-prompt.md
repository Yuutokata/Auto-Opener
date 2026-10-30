# Prompt for the Keyword Pinger repository

Copy everything below the line into Claude Code, run from the Keyword Pinger (Discord bot) repository.
The contract described here is implemented by the Auto-Opener server on the `dev` branch; keep it in sync if the server changes.

---

You are working on the Keyword Pinger, a Discord bot (Python, discord.py, async) that notifies users about new products found by monitors and can forward product links to the Auto-Opener WebSocket server. Users who installed the Auto-Opener Chrome extension get those links opened automatically in their browser.

## Why this change

Today the Auto-Opener server issues a user token (JWT) to anyone who knows a valid Discord user ID. Discord IDs are public, so anyone could receive another user's links. The server is moving to a per-user secret that only the user and the bot know. The bot must generate, hand out and register these secrets.

## Contract with the Auto-Opener server (unchanged parts)

- Service authentication, `POST /token` with `{"token": "<service token>", "role": "service"}`, the `/bot` WebSocket, `POST /send_message?user_id=<id>` and `POST /users` (bulk sync) keep working exactly as they do today.
- The WebSocket message format and the `BotResponse` format are unchanged.
- Failed authentication will now return HTTP 401 instead of HTTP 200 with a plain-text body. Make sure the bot's token handling treats a non-2xx response as a failure and does not parse the body as a token.

## Contract changes

### `POST /users/add` (service JWT required)

Request body gets one new optional field:

```json
{
  "user": {
    "id": "123456789012345678",
    "name": "display name",
    "secret": "<64 lowercase hex characters>"
  }
}
```

- `secret` is optional. If present it must match `^[a-f0-9]{64}$` (32 random bytes, hex encoded, for example `secrets.token_hex(32)`).
- The server stores only a hash of the secret. It can never be read back, so the bot must show it to the user at generation time.
- If `secret` is omitted on an update, the stored secret is kept. Sending a new `secret` replaces the old one (this is how a user rotates their secret).
- `POST /users` (bulk sync) ignores `secret` and never overwrites stored secrets. Do not send secrets there.

### `POST /token` for end users (called by the extension, not by the bot)

```json
{ "token": "<discord user id>", "role": "user", "secret": "<the user's secret>" }
```

This is only for your information. The extension team handles it.

## Tasks

1. Add a way for a user to get their extension secret, for example a slash command (ephemeral response only, never in a public channel and never logged):
   - Generate a secret with `secrets.token_hex(32)`.
   - Register it with `POST /users/add` (send the user's existing `id` and `name` plus `secret`).
   - Show the secret once to the user, with a short note that it must be pasted into the extension settings and that running the command again replaces it.
2. Existing users already exist on the server without a secret. They must be able to run the same command at any time. Do not generate secrets for them automatically.
3. When a user is added through the existing onboarding flow, offer the same secret generation as part of it.
4. Do not store plaintext secrets in the bot's database or logs. Mask secrets in any logging of requests to the server.
5. Handle the new error responses of `/users/add`: `400` with `{"error": "..."}` for a malformed secret. Surface a friendly message to the user and log the status code only.
6. Add tests for secret generation (format and uniqueness) and for the request body sent to `/users/add`, using the project's existing test setup. Keep the change small and consistent with the existing code style.

## Rollout order (important)

1. Deploy the Auto-Opener server. By default the secret is optional (`REQUIRE_USER_SECRET=false`), so nothing breaks.
2. Deploy this bot change and let users generate their secrets.
3. The extension developers ship the update that sends the secret to `POST /token`.
4. Once most users migrated, the server operator sets `REQUIRE_USER_SECRET=true`. From then on users without a secret cannot connect.

Do not change anything else about how keywords, keyword groups, monitors or the auto-open setting work.
