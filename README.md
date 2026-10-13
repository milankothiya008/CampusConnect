# CampusConnect (Spring Boot + React)

University club and event platform: verified university accounts, clubs with per-club roles and faculty
mentors, events with approval workflows, registrations with waitlists, teams and QR tickets, check-in and
certificates, results and hackathons, recruitment drives, a social feed with stories and galleries, live chat
and anonymous club elections.

This is the Spring Boot rewrite of the original MERN application. It keeps the **same features and the same
REST API**, so the React frontend is reused, and it is built to serve **5,000 concurrent users**.

| | |
|---|---|
| Backend | Java 25, Spring Boot 4.1 (virtual threads), Spring Security (JWT), Spring Data MongoDB, Redis (Lettuce, Spring Cache + Caffeine), RabbitMQ (jobs + STOMP relay), Bucket4j, ShedLock, OpenPDF, ZXing, ical4j |
| Frontend | React 19, Vite 8, TanStack Query, STOMP over WebSocket, skeleton loading states, installable PWA |
| Data | MongoDB 8 replica set (Atlas in production) |
| Edge | Nginx (TLS, HTTP/2, gzip, caching, WebSocket proxy) |
| Deployment | Docker Compose on a VPS: web + 3 API replicas + Redis + RabbitMQ (+ optional Prometheus/Grafana) |

Read first: [`docs/HLD.md`](docs/HLD.md) (architecture and scaling), [`docs/REALTIME.md`](docs/REALTIME.md)
(chat protocol), [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) and [`docs/CORE_API.md`](docs/CORE_API.md)
(how the code is organised), [`docs/PORTING_NOTES.md`](docs/PORTING_NOTES.md) (where behaviour differs from
the Node version, and open questions), [`deploy/README.md`](deploy/README.md) (production).

## Repository layout

```
backend/        Spring Boot API (Maven; ./mvnw)
  src/main/java/com/campusconnect/
    core/       security, errors, caching, rate limits, notifications, e-mail, push, media, realtime, jobs
    domain/     MongoDB documents and repositories
    feature/    one package per API area (auth, club, event, registration, chat, ...)
  src/test/java/...   unit, integration (Testcontainers) and architecture tests
frontend/       React app (npm)
deploy/         docker-compose.yml, Nginx, RabbitMQ and monitoring config, .env.example
load-tests/     Gatling simulation (5,000 concurrent users)
system-tests/   Playwright end-to-end tests against the Docker stack
docs/           design documents
```

## Run locally

Requirements: Java 25, Node 22, Docker.

```bash
# 1. MongoDB (replica set), Redis and RabbitMQ on localhost
docker compose -f deploy/docker-compose.dev.yml up -d

# 2. API on http://localhost:8080 (dev profile: in-memory STOMP broker, jobs in-process, e-mails in the dev inbox)
cd backend
export JWT_ACCESS_SECRET=$(openssl rand -base64 48) JWT_REFRESH_SECRET=$(openssl rand -base64 48)
export BOOTSTRAP_ADMIN_EMAIL=admin@ddu.ac.in BOOTSTRAP_ADMIN_PASSWORD='ChangeMe#2026'
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Web app on http://localhost:3000 (proxies /api, /uploads and /ws to the API)
cd frontend && npm install && npm run dev
```

Without an e-mail provider, verification codes appear in the development inbox at
`http://localhost:3000/dev/inbox`. API documentation (OpenAPI) is served at `http://localhost:8080/swagger-ui.html` (development only).

## Tests

| Level | Command |
|---|---|
| Backend unit + integration + architecture (Docker needed for Testcontainers) | `cd backend && ./mvnw verify` |
| Frontend unit/component tests, lint, build | `cd frontend && npm test && npm run lint && npm run build` |
| End-to-end (full Docker stack) | see [`system-tests/README.md`](system-tests/README.md) |
| Load (5,000 concurrent users) | see [`load-tests/README.md`](load-tests/README.md) |

## Production

`cd deploy && cp .env.example .env && docker compose up -d --build` — details, sizing, TLS, backups and the
security checklist are in [`deploy/README.md`](deploy/README.md).
