# Deployment notes (Dokploy, Traefik, CrowdSec)

Recommendations for running Auto-Opener behind Traefik v3 on Dokploy. The Traefik and CrowdSec snippets are examples: check them against your Traefik version and the CrowdSec plugin documentation before use.

## Network layout

- The bot runs on the same Dokploy host. It should reach Auto-Opener over the internal `dokploy-network`, using the app name as host: `ws://<app-name>:8080/bot` and `http://<app-name>:8080/...`. No Traefik, no TLS and no public exposure for service traffic.
- Block the bot-only routes on the public domain (see below). Publicly reachable stays only `/token`, `/listen/*` and `/health`, which is all the extension needs.
- Do not publish a host port for the app. Keep MongoDB on a private network without a published port and give the application its own database user with least privilege.
- DNS stays DNS-only in Cloudflare (no proxying). If proxying is enabled later, set `CLIENT_IP_HEADER=CF-Connecting-IP` and restrict the origin to Cloudflare IP ranges.

## Environment

```env
CLIENT_IP_HEADER=X-Forwarded-For   # required behind Traefik, otherwise all clients share one rate limit
JWT_SECRET=<64+ random characters>
BOT_TOKEN=<64 random alphanumeric characters>
PING_INTERVAL=25                   # below Chrome's 30 s idle limit for extension service workers
PONG_TIMEOUT=20
HEALTH_CHECK_INTERVAL=5
MAX_CONNECTIONS_PER_USER=3
REQUIRE_USER_SECRET=false          # switch to true after the bot and the extension ship per-user secrets
```

Give the container at least 512 MB RAM and 1-2 vCPU. The image already sets `-XX:MaxRAMPercentage=75`.

## Traefik

Put this in the dynamic configuration of Traefik (Dokploy: Traefik file system). Dokploy keeps managing the base router for the domain; this router only takes over the bot paths. Use an explicit priority so it wins over the domain router.

```yaml
http:
  middlewares:
    deny-public:
      ipAllowList:
        sourceRange: ["127.0.0.1/32"]   # nothing public matches, requests get 403
    token-body-limit:
      buffering:
        maxRequestBodyBytes: 16384
    per-ip-connections:
      inFlightReq:
        amount: 50
        sourceCriterion:
          ipStrategy:
            depth: 0
  routers:
    auto-opener-bot-routes:
      rule: "Host(`your.domain`) && (PathPrefix(`/users`) || Path(`/send_message`) || Path(`/bot`))"
      priority: 1000
      middlewares: [deny-public]
      entryPoints: [websecure]
      service: noop@internal
      tls: {}
```

- Attach `per-ip-connections` to the domain router to stop a single client from holding hundreds of connections.
- Attach `token-body-limit` only to a router matching `Path(`/token`)`. Do not limit `/users`: the bulk sync can be large (and is internal-only anyway).
- The app already sets HSTS, `nosniff`, frame-deny and a CSP, so no extra security-header middleware is needed.
- Keep Traefik's default behavior of not trusting `X-Forwarded-For` from outside (do not add `forwardedHeaders.trustedIPs` while DNS-only). The app uses the last entry of that header, which is the address Traefik saw.

## CrowdSec

- Use the Traefik bouncer plugin in **stream** mode (`crowdsecMode: stream`). Decisions are cached locally, so there is no per-request call to the LAPI and no added latency. `live` mode asks the LAPI per request.
- Whitelist the Docker/host addresses you use for administration. The bot does not pass through Traefik when it uses the internal network.
- Useful collections: `crowdsecurity/traefik`, `crowdsecurity/http-cve`.
- AppSec adds latency per request: enable it for HTTP routes only, not for the WebSocket paths.
- After a server restart every extension reconnects at once and may produce 401/429 responses, which can trigger probing or brute-force scenarios and ban users behind shared IPs. Watch `cscli decisions list` after the first deploys and tune scenarios or whitelists if needed. The extension should reconnect with exponential backoff and jitter (see `extension-changes.md`).

## Latency

- The delivery path is fully in memory; MongoDB is only used for token issuance and the first lookup of a user (cached for 5 minutes).
- Prefer the persistent `/bot` WebSocket over `POST /send_message` for the bot: no per-message HTTP overhead and no HTTP rate limit.
- Traefik's default 180 s idle timeout is no issue because the server pings every 25 s.
- Place the server geographically close to the users; network distance dominates everything else.

## Rollout order

1. Deploy with `CLIENT_IP_HEADER` set and the bot routes blocked publicly.
2. Switch the bot to the internal address.
3. Roll out per-user secrets (bot, then extension), then set `REQUIRE_USER_SECRET=true`.
