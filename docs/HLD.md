# CampusConnect (Java) — High-Level Design

CampusConnect is a university club and event platform: verified accounts, clubs with per-club roles, events
with approval workflows, registrations with waitlists and QR tickets, results, recruitment, a social feed,
live chat and anonymous club elections. This repository is the Spring Boot + React rewrite of the original
MERN application, with the **same features and the same REST API contract**, designed to serve
**5,000 concurrent users**.

## 1. Architecture

```
                 Browser / installed PWA  (React 19 + Vite, TanStack Query, skeleton UI)
                        │ HTTPS /api/*                      │ WSS /ws  (STOMP)
                ┌───────▼───────────────────────────────────▼────────┐
                │ Nginx: TLS, HTTP/2, gzip/brotli, static assets with  │
                │ immutable caching, reverse proxy, WebSocket upgrade  │
                └──────┬──────────────────┬──────────────────┬────────┘
                       │  round robin (Docker DNS, re-resolved every 10 s)
              ┌────────▼──────┐  ┌────────▼──────┐  ┌────────▼──────┐
              │ api replica 1 │  │ api replica 2 │  │ api replica 3 │   stateless Spring Boot 4.1
              │ virtual threads, Caffeine L1 cache, Bucket4j filter │   (Java 25)
              └──┬─────┬─────┬┘  └───────────────┘  └───────────────┘
                 │     │     │
       ┌─────────┘     │     └──────────────────────────┐
       ▼               ▼                                ▼
┌──────────────┐ ┌───────────────────────────┐  ┌────────────────────────────┐
│ Redis 8      │ │ RabbitMQ 4                │  │ MongoDB 8 replica set       │
│ L2 cache     │ │ • job queues: mail, push  │  │ (Atlas in production;       │
│ rate limits  │ │   (retries + dead letters)│  │  local single-node RS in    │
│ ShedLock     │ │ • STOMP broker relay for  │  │  development) — required    │
│ presence     │ │   live chat across nodes  │  │  for multi-document txns    │
│ cache-evict  │ └───────────────────────────┘  └────────────────────────────┘
│ pub/sub      │
└──────────────┘     External: Cloudinary (media), Brevo SMTP/API (e-mail), Web Push (VAPID)
```

### Why this sustains 5,000 concurrent users

| Concern | Design decision |
|---|---|
| CPU parallelism | Every replica uses all cores; Tomcat runs on **virtual threads** (Java 25 — `synchronized` no longer pins carrier threads, JEP 491), so tens of thousands of blocking requests cost only memory. |
| Horizontal scale | Replicas are **stateless**: sessions are JWTs, caches are shared through Redis, chat fan-out goes through RabbitMQ, scheduled jobs are guarded by ShedLock. Add replicas with `docker compose up --scale api=N`. |
| Hot reads | Two cache levels: **Caffeine** (per replica, microseconds) for the signed-in user, paused clubs and reference data; **Redis** (shared) for feed pages and the club directory. Writes evict both — local caches through a Redis pub/sub channel so every replica drops its copy. |
| Slow side effects | E-mail, web push and notification fan-out are **RabbitMQ jobs** with retries and a dead-letter queue, so requests never wait on SMTP or push services. E-mail obeys a cluster-wide rate (Bucket4j on Redis). |
| Correctness under contention | Seat booking, waitlist promotion, voting and ticket check-in use **atomic single-document updates** (`findAndModify` with conditions) — the fastest safe option. Multi-document steps use MongoDB transactions. Venue booking takes a short Redis lock. |
| Abuse protection | **Bucket4j + Redis** rate limits shared by all replicas: per user when signed in (a whole campus behind one NAT address must not share a quota), per IP for sign-in/sign-up. |
| Database load | Every frequent query has an index; list endpoints project only needed fields and batch-load related documents (no N+1); the driver's connection pool is sized per replica. |
| Payload size | Nginx compresses JSON (gzip/brotli); static assets are fingerprinted and cached for a year; the SPA is code-split per route. |

Capacity planning (verified by the Gatling simulation in `load-tests/`): 3 replicas × 2 vCPU,
Redis 1 GB, RabbitMQ 1 GB, MongoDB Atlas M10+ handle 5,000 concurrent users with headroom.

## 2. Technology choices (versions verified for mutual compatibility, October 2026)

| Layer | Choice | Version |
|---|---|---|
| Runtime | Java (LTS) | 25 |
| Framework | Spring Boot (Spring Framework 7.0, Spring Security 7.1, Spring Data 2026.0) | 4.1.1 |
| Database | MongoDB (driver 5.8 via Spring Data MongoDB) | 8.0 |
| Cache / locks / limits | Redis (Lettuce 7.5), Caffeine | 8 / 3.2 |
| Messaging | RabbitMQ (Spring AMQP 4.1) — job queues + STOMP relay | 4.1 |
| Rate limiting | Bucket4j (`bucket4j_jdk17-core`, `-lettuce`) | 8.21.0 |
| Scheduled-job locks | ShedLock (Redis provider) | 7.10.1 |
| API docs | springdoc-openapi | 3.1.1 |
| PDF / QR / iCalendar | OpenPDF / ZXing / ical4j | 3.0.5 / 3.5.4 / 4.4.0 |
| Media | Cloudinary Java SDK (Apache HttpClient 5) | 2.5.0 |
| Web Push | zerodep-web-push-java (JDK crypto, no extra BouncyCastle) | 2.1.5 |
| HTML parsing (link previews) | jsoup | 1.23.2 |
| Tests | JUnit 6, Mockito, AssertJ, Testcontainers 2, REST Assured, ArchUnit, Gatling | Boot-managed / 6.1.0 / 1.5.1 / 3.16 |
| Frontend | React 19, Vite 8, TanStack Query 5, @stomp/stompjs 7 | — |
| Edge | Nginx (mainline) | 1.29 |

### Deliberate deviations from the initial tool list

* **No HikariCP** — it pools JDBC connections; MongoDB has its own pooled driver (`maxPoolSize`, `minPoolSize`, `maxConnecting`), tuned per replica.
* **Atomic updates instead of `@Transactional` for seat booking** — one conditional update is faster than a transaction and equally safe; transactions are used only where several documents must change together.
* **RabbitMQ, not Kafka** — RabbitMQ serves both as the job queue *and* the STOMP relay for chat, one component instead of two; Kafka targets far larger event streams.
* **Spring Security OAuth2 Resource Server (Nimbus JOSE) for JWT** — battle-tested token verification instead of hand-written JWT code.

## 3. Security

* JWT access tokens (HS256, 15 min) in the `Authorization` header; refresh tokens are random, **hashed at rest**, rotated on every use, stored in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/auth`, and revocable. Reuse of a rotated token revokes the whole family.
* Passwords: BCrypt (cost 12). OTP codes: HMAC-SHA256 keyed with a server secret, 10-minute expiry, 5 attempts, 60 s resend cooldown.
* Authorization: global roles (STUDENT / FACULTY / ADMIN) plus **per-club permissions** checked in the service layer (`ClubAccess`), mirroring the original rules exactly (e.g. the admin has no club-level authority).
* Input validation with Jakarta Bean Validation on every request body; typed queries only (no string-built queries → no NoSQL injection); uploads validated by **magic bytes**, size and duration; link previews protected against **SSRF** (DNS resolution + private-range blocking).
* Rate limits (Bucket4j/Redis), CORS restricted to the client origin, security headers (HSTS, CSP, frame-deny, no-sniff, referrer policy) at Nginx and in Spring Security.
* HMAC-signed tickets, calendar-feed tokens and unsubscribe links; secret ballots (a ballot stores only *that* someone voted).
* Secrets only through environment variables; containers run as non-root with read-only file systems where possible; CI runs Trivy image scans and the OWASP dependency check.

## 4. Real-time (chat, presence, live updates)

STOMP over WebSocket at `/ws`. The client authenticates in the STOMP `CONNECT` frame
(`Authorization: Bearer …`). Server → client events arrive on `/user/queue/events` as
`{ "event": "message:new", "payload": { … } }`, keeping the original Socket.IO event names so the React
`ChatContext` is unchanged. Client → server: `/app/typing`, `/app/delivered`, `/app/viewing`,
`/app/presence.watch`. With several replicas, Spring's broker relay to RabbitMQ delivers a user's messages
whichever replica holds the connection; presence and "who is viewing which chat" live in Redis.

## 5. Background work

| Job | Mechanism |
|---|---|
| E-mail (OTP, approvals, announcements) | RabbitMQ `cc.mail` queue → SMTP / Brevo API, retries with back-off, dead-letter queue, cluster-wide rate limit, Redis de-duplication keys |
| Web push | RabbitMQ `cc.push` queue → VAPID push; expired subscriptions removed |
| Event / interview reminders, waitlist expiry, election open/close, story expiry, feedback requests, offer expiry | `@Scheduled` sweepers guarded by **ShedLock** (one replica runs each tick) |

## 6. Deployment

`docker compose` on a VPS: `nginx`, `api` (scaled to 3), `redis`, `rabbitmq`, and — for local/dev or
self-hosting — `mongo` (single-node replica set). Production points `MONGODB_URI` at MongoDB Atlas.
Optional `monitoring` profile: Prometheus + Grafana scraping `/actuator/prometheus` over the internal network.
See `deploy/README.md`.

## 7. Testing

| Level | Tooling | Location |
|---|---|---|
| Unit | JUnit 6, Mockito, AssertJ | `backend/src/test/java/**/unit` |
| Integration | `@SpringBootTest` + Testcontainers (MongoDB replica set, Redis, RabbitMQ) + MockMvc | `backend/src/test/java/**/it` |
| Architecture | ArchUnit layering rules | `backend/src/test/java/**/architecture` |
| Frontend | Vitest + React Testing Library | `frontend/src/**/*.test.jsx` |
| System (end-to-end) | Playwright against the full Docker stack | `system-tests/` |
| Load | Gatling (HTTP + WebSocket) — 5,000 concurrent users | `load-tests/` |
