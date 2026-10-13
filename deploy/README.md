# Deploying CampusConnect (Docker Compose on a VPS)

One VPS runs everything except the database: Nginx (TLS, static app, proxy), three stateless API replicas,
Redis and RabbitMQ. MongoDB is a managed **MongoDB Atlas** cluster (or the bundled replica set for a
self-hosted trial). See `../docs/HLD.md` for the architecture.

## 1. Size the server

| Load | VPS | MongoDB |
|---|---|---|
| ≤ 1,000 concurrent users | 2 vCPU / 4 GB, `API_REPLICAS=2` | Atlas M0/M10 |
| 5,000 concurrent users (target) | 4 vCPU / 8 GB, `API_REPLICAS=3` | Atlas M10+ (same region as the VPS) |
| More | add replicas (`--scale api=N`) and CPU; Redis and RabbitMQ stay small | Atlas M20+ |

Sign-in is the one expensive request: checking a password (bcrypt, as in the Node version) costs about 250 ms of
CPU, so a replica takes about 4 sign-ins per second per hashing thread. A replica hashes on one CPU fewer than it
has (one thread at `API_CPUS=1.5`, two at `API_CPUS=3`), keeping the rest free for everything else, so the default
3 replicas take about 12 sign-ins per second. Everything a signed-in student does afterwards is cheap, and sessions
renew with the refresh cookie, not the password. If thousands must sign in within minutes (start of term, a load
test), raise `API_CPUS` or add replicas. Don't raise `PASSWORD_HASH_THREADS` to the CPU count: measured at
`API_CPUS=1.5`, two hashing threads made every other request about 4 times slower without speeding up sign-in.

Requirements: Docker Engine 24+ with the Compose plugin 2.20+, ports 80/443 open, a DNS `A` record for your
domain pointing at the server.

## 2. Configure

```bash
git clone <repo> campusconnect && cd campusconnect/deploy
cp .env.example .env
# fill in: JWT_ACCESS_SECRET, JWT_REFRESH_SECRET, REDIS_PASSWORD, RABBITMQ_PASSWORD (openssl rand -base64 48),
# MONGODB_URI, CLIENT_URL, SERVER_NAME, BOOTSTRAP_ADMIN_*, mail (BREVO_API_KEY or SMTP_*), CLOUDINARY_*, VAPID_*
chmod 600 .env
```

In Atlas, allow the VPS's public IP in *Network Access*, and create a database user with read/write on the
`campusconnect` database. The URI must include `retryWrites=true&w=majority`.

## 3. HTTPS certificates (Let's Encrypt)

The web container serves HTTPS automatically when `deploy/certs/fullchain.pem` and `privkey.pem` exist,
and plain HTTP otherwise (useful behind Cloudflare or another TLS proxy).

```bash
# First certificate (port 80 must be free, so before the first `up`):
docker run --rm -p 80:80 -v "$PWD/certs-le:/etc/letsencrypt" certbot/certbot certonly --standalone \
  -d campus.example.edu --agree-tos -m admin@campus.example.edu --non-interactive
mkdir -p certs && cp certs-le/live/campus.example.edu/{fullchain,privkey}.pem certs/
```

Renewal (cron, monthly) uses the webroot that Nginx already serves at `/.well-known/acme-challenge/`:

```bash
docker run --rm -v "$PWD/certs-le:/etc/letsencrypt" -v campusconnect_certbot-www:/var/www/certbot \
  certbot/certbot renew --webroot -w /var/www/certbot \
  && cp certs-le/live/campus.example.edu/{fullchain,privkey}.pem certs/ && docker compose exec web nginx -s reload
```

## 4. Start

```bash
docker compose up -d --build            # with MongoDB Atlas
docker compose --profile local up -d --build   # with the bundled MongoDB replica set instead
docker compose ps                       # all services "healthy"
curl -s https://campus.example.edu/api/health
```

On first start the API creates the departments, the last six batches and the default venues, and the
university admin from `BOOTSTRAP_ADMIN_*` (only if no admin exists yet). Change that password after signing in.

## 5. Operate

| Task | Command |
|---|---|
| Logs | `docker compose logs -f api` (JSON-file logs are rotated: 5 × 20 MB per container) |
| Scale | `docker compose up -d --scale api=5` — Nginx picks up new replicas within 10 s |
| Update | `git pull && docker compose up -d --build` |
| Monitoring | `docker compose --profile monitoring up -d`, then Grafana on `http://127.0.0.1:3001` (tunnel with `ssh -L 3001:127.0.0.1:3001`); import dashboards 4701 (JVM Micrometer) and 10991 (RabbitMQ) |
| E-mail queue | RabbitMQ UI: `ssh -L 15672:localhost:15672` is not published by default — use `docker compose exec rabbitmq rabbitmqctl list_queues` (`cc.mail`, `cc.mail.dead`, …) |
| Backups | Atlas: enable continuous backups. Local profile: `docker compose exec mongo mongodump --archive --gzip > backup.gz` |

Nothing but Nginx is published to the internet: Redis, RabbitMQ and the API containers are only reachable on the
Compose network. `/actuator/*` is blocked at the edge; Prometheus scrapes the replicas internally.

## 6. Security checklist

- Firewall: allow only 22, 80 and 443 (`ufw allow OpenSSH && ufw allow 80,443/tcp && ufw enable`).
- Secrets live only in `deploy/.env` (mode 600); rotate `JWT_*` to sign everyone out, rotate provider keys if leaked.
- Containers run as non-root with read-only file systems (uploads and temp folders are the only writable mounts).
- Keep images patched: rebuild monthly (`docker compose build --pull && docker compose up -d`); CI scans images with Trivy.
- Atlas: IP allow-list, TLS (default), least-privilege database user.
