# System tests (Playwright, full Docker stack)

These tests drive the real application the way people use it — browser → Nginx → API replicas → MongoDB,
Redis, RabbitMQ — on desktop and mobile viewports. E-mail goes to [Mailpit](https://mailpit.axllent.org), and
the tests read verification codes from its API.

```bash
cd system-tests
npm install
npx playwright install chromium
npm run stack:up        # builds and starts the stack on http://localhost:8088 (Mailpit UI: http://localhost:8025)
npm test                # runs every spec; `npm run report` opens the HTML report
npm run stack:down      # stops it and deletes its data
```

The stack uses `deploy/system-test.env` (published test secrets — never reuse them) and
`deploy/docker-compose.system-test.yml` (two API replicas, so every test also crosses replicas: refresh tokens,
caches, live chat through the RabbitMQ relay).

| Spec | Covers |
|---|---|
| `smoke.spec.js` | Edge: security headers, caching, SPA fallback, API 404s, hidden internals |
| `auth.spec.js` | Sign-up wizard → e-mailed code → verification → sign-in; wrong password; session refresh across reloads |
| `journeys.spec.js` | The admin dashboard; a direct message arriving live across API replicas (RabbitMQ relay) and a reply sent from the UI |
