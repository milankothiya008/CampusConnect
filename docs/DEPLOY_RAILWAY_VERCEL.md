# Deploy on Railway (API and data) and Vercel (web app)

The same split as the original MERN site: Vercel serves the React app, Railway runs the API and its data
services. After the one-time setup below, every `git push` to `main` redeploys automatically: Vercel rebuilds the
web app, and Railway rebuilds the API only when something under `backend/` changed.

```
browser ──https──▶ Vercel  (React app; /api/* and /uploads/* are rewritten to the API, so cookies stay first-party)
   │
   └──wss──▶ Railway: api (/ws live chat; Vercel can't proxy WebSockets)
                 │  private network
                 ├── Mongo Single Replica  (MongoDB 8, single-node replica set: transactions work)
                 ├── Redis                 (caches, rate limits, presence, scheduler locks)
                 └── rabbitmq              (e-mail and push queues with retries)
External: Brevo (e-mail over HTTPS — Railway blocks SMTP below the Pro plan), Cloudinary (photos and videos).
```

The Docker Compose deployment in [`deploy/`](../deploy/README.md) is the alternative for a single VPS.

## What it costs

Railway bills what the services use: RAM $10 per GB per month, CPU $20 per vCPU per month (see
[railway.com/pricing](https://railway.com/pricing)). Tuned as below, the four services use about 0.7 GB of RAM
together (API ≈ 400 MB, MongoDB ≈ 150 MB, RabbitMQ ≈ 120 MB, Redis ≈ 10 MB) and little CPU while traffic is low:
roughly **$7–9 per month**.

| Railway plan | Works? |
|---|---|
| Free ($1 credit a month, 0.5 GB per service) | No: the credit covers about four days of this stack, and Railway refuses to create the services |
| Trial ($5 one-time credit, 30 days, 1 GB per service) | Yes, for about three weeks |
| Hobby ($5 a month, includes $5 of usage) | Yes; usage above $5 is billed |

Vercel's Hobby plan is free for this app.

## 1. Before you start

- This repository on GitHub (Railway and Vercel both deploy from it).
- A Railway account on the Trial or Hobby plan, with GitHub connected (Account → Integrations).
- A Vercel account with GitHub connected.
- A [Brevo](https://www.brevo.com) API key and a verified sender address (sign-up codes are e-mailed).
- [Cloudinary](https://cloudinary.com) credentials (cloud name, API key, API secret): Railway's disk is wiped on
  every deploy, so uploads must not stay on it.

## 2. Railway

Create a project, then add the services in this order.

1. **Redis**: *New → Template → Redis* (Railway's verified template).
2. **MongoDB**: *New → Template → "MongoDB Single Replica"* (Railway's own template, source
   `railwayapp-templates/mongo-replica-set`). It creates `Mongo Single Replica` and a one-off
   `Init Single Replica` job that turns it into a replica set; the job stops once it has run.
3. **RabbitMQ**: *New → Docker Image → `rabbitmq:4.1-alpine`*, name it `rabbitmq`, with these variables:

   | Variable | Value |
   |---|---|
   | `RABBITMQ_DEFAULT_USER` | `campusconnect` |
   | `RABBITMQ_DEFAULT_PASS` | a long random string (`openssl rand -base64 24`) |
   | `RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS` | `+S 1:1 +sbwt none +sbwtdcpu none +sbwtdio none` (one scheduler, no busy-waiting: less CPU billed) |

4. **API**: *New → GitHub Repo → this repository*, name it `api`. In its **Settings**:

   | Setting | Value |
   |---|---|
   | Root Directory | `/backend` (Railway then builds `backend/Dockerfile`) |
   | Watch Paths | `/backend/**` |
   | Healthcheck Path | `/actuator/health/readiness` (ready once MongoDB and Redis answer), timeout 300 s |
   | Restart Policy | On failure, 5 retries |
   | Networking | *Generate Domain* — this is the API address used below, e.g. `api-production-xxxx.up.railway.app` |

5. **API variables** (*api → Variables*). The `${{…}}` references are resolved by Railway, so the database
   passwords are never copied by hand.

   | Variable | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod,railway` ([`application-railway.yml`](../backend/src/main/resources/application-railway.yml): health is the only public actuator endpoint, visitor IPs from `X-Forwarded-For`, in-process chat broker for one instance, smaller pools) |
   | `MONGODB_URI` | `mongodb://${{Mongo Single Replica.MONGO_INITDB_ROOT_USERNAME}}:${{Mongo Single Replica.MONGO_INITDB_ROOT_PASSWORD}}@${{Mongo Single Replica.RAILWAY_PRIVATE_DOMAIN}}:27017/campusconnect?replicaSet=rs0&authSource=admin` |
   | `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `${{Redis.REDISHOST}}` / `${{Redis.REDISPORT}}` / `${{Redis.REDISPASSWORD}}` |
   | `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `${{rabbitmq.RAILWAY_PRIVATE_DOMAIN}}` / `${{rabbitmq.RABBITMQ_DEFAULT_USER}}` / `${{rabbitmq.RABBITMQ_DEFAULT_PASS}}` |
   | `JOBS_MODE` | `rabbit` |
   | `CLIENT_URL` | the Vercel address from step 3, e.g. `https://campusconnect-java.vercel.app` (CORS, WebSocket origin, links in e-mails) |
   | `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` | two different random strings of at least 32 bytes (`openssl rand -base64 48`) |
   | `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | push notification keys (`npx web-push generate-vapid-keys`) and `mailto:<your address>` |
   | `BREVO_API_KEY`, `MAIL_FROM` | your Brevo key and verified sender, e.g. `CampusConnect <you@example.com>` |
   | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | your Cloudinary credentials |
   | `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_NAME` | the first admin account, created at the first start |
   | `UNIVERSITY_DOMAIN`, `UNIVERSITY_NAME`, `UNIVERSITY_TZ_OFFSET` | e.g. `ddu.ac.in`, `Dharmsinh Desai University`, `+05:30` |
   | `JAVA_TOOL_OPTIONS` | `-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=96m -Xss512k -XX:+ExitOnOutOfMemoryError` (about 400 MB under load instead of up to 75 % of the container) |

   Railway sets `PORT`; the API listens on it.

The first deploy takes a few minutes (Maven build, then the image). It is live when the healthcheck passes:
`https://<api-domain>/api/health` answers `{"success":true,…}`.

## 3. Vercel

1. In [`frontend/vercel.json`](../frontend/vercel.json), replace `YOUR-API-DOMAIN.up.railway.app` (three places:
   the `/api` and `/uploads` rewrites and the Content-Security-Policy's `connect-src`) with your API domain. Commit
   and push.
2. *Add New → Project → import this repository*. Set **Root Directory** to `frontend`; `vercel.json` pins the
   framework (Vite) and the output directory.
3. **Environment Variables** (Production and Preview):

   | Variable | Value |
   |---|---|
   | `VITE_WS_URL` | optional: on Vercel builds the chat address is taken from the `/api` rewrite in `vercel.json` (`wss://<api-domain>/ws`); set it only to override that |
   | `VITE_UNIVERSITY_DOMAIN` | `ddu.ac.in` |
   | `VITE_UNIVERSITY_TIMEZONE` | `Asia/Kolkata` |
   | `VITE_UNIVERSITY_TZ_OFFSET` | `+05:30` |

4. Deploy. Copy the production address (e.g. `https://campusconnect-java.vercel.app`) into the API's `CLIENT_URL`
   on Railway; the API redeploys with it.

## 4. Check it

- `https://<vercel-domain>/api/health` → `success: true`, and its `ip` is your own address (not a proxy's): per-IP
  rate limits work.
- Sign in as the bootstrap admin, open **Admin** (dashboard) and **Messages** (the chat connects; a message sent
  from another account appears without reloading).
- Register a student with a university e-mail: the 6-digit code arrives through Brevo.
- Post a story or event poster: the image is served from `res.cloudinary.com`.

## 5. Day to day

- **Deploy**: `git push` to `main`. Vercel rebuilds the web app on every push; Railway rebuilds the API only when
  something under `backend/` changed, and switches over once the new instance passes its healthcheck.
- **Logs and metrics**: Railway → `api` → Deployments / Metrics; Vercel → Deployments.
- **Secrets**: rotate them in the platforms' Variables pages; never commit them (`.env` files are git-ignored).
- **Backups**: MongoDB data lives on the `Mongo Single Replica` volume. Take dumps with `mongodump` through a
  temporary TCP proxy, or move to MongoDB Atlas by changing `MONGODB_URI`.

## 6. Growing past one instance

1. Run RabbitMQ with the STOMP plugin (the Compose stack's [`deploy/rabbitmq`](../deploy/rabbitmq) config) and set
   `REALTIME_BROKER=relay` on the API, so chat messages reach users on every replica.
2. Raise the API's replica count and memory (`JAVA_TOOL_OPTIONS -Xmx`), and Redis and MongoDB sizes, in their
   Settings.
3. Sign-ins are the expensive request (bcrypt, about 4 per second per hashing thread, see
   [deploy/README.md](../deploy/README.md)); more CPUs per replica raise that.

## Notes

- **Visitor IPs**: through Vercel, `X-Forwarded-For` starts with the visitor's address, and the `railway` profile
  trusts it so rate limits are per visitor. Requests sent straight to the Railway address can set that header
  themselves, but the per-account limits still hold: 10 wrong passwords per account per 15 minutes, 5 tries per
  e-mailed code.
- **Preview deployments** on Vercel use other addresses than `CLIENT_URL`, so live chat (which checks the
  WebSocket origin) only connects on the production address.
