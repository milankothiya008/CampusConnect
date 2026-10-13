# Load test: 5,000 concurrent users (Gatling)

`CampusConnectSimulation` signs N students in, one by one over the ramp (one sign-in each, as real sessions renew
with the refresh cookie), keeps each one's live-chat WebSocket open (STOMP over `/ws`) and lets them all browse
until the end of the hold period, with think times of 3–10 s: events feed, event pages, unread badges, clubs,
profile, own registrations, and some registrations (seat booking and waitlists under contention). All N are online
together for the whole hold.

Pass criteria (Gatling assertions, so a failed run fails the build):

- every request a signed-in student makes (WebSocket connect, feed, event page, badges, clubs, profile, own
  registrations, registering): p95 < 800 ms and p99 < 2 s, each request type on its own;
- fewer than 1 % failed requests;
- sign-in p95 < 3 s (`-DsignInP95Ms`). Sign-in has its own budget because checking a password is deliberately
  slow (bcrypt, about 250 ms of CPU): a stack takes about 4 sign-ins per second per hashing thread
  (one CPU fewer than the replica has: one at `API_CPUS=1.5`), so the default 3 replicas take about 12 per
  second. Choose the ramp so that `users / rampSeconds` stays below that, e.g. 5,000 users over 420 s, or give the
  replicas more CPU (their hashing threads follow) or add replicas.

## Run it

**Never against production** — the seeded accounts share a published password.

1. Start a staging stack (same images and sizing as production) with load-test data:
   ```bash
   cd deploy
   SEED_LOAD_TEST_USERS=5000 docker compose --profile local up -d --build
   ```
   The API then creates students `24celta000@ddu.ac.in` … (password `LoadTest#2026`), 20 clubs and 100 events.
   With the `prod` profile it also needs `SEED_LOAD_TEST_ALLOW_PRODUCTION=true` (staging only!). The default
   sign-in limit (`AUTH_RATE_LIMIT=100` per IP per 15 min) would stop a load generator that signs in 5,000 users
   from one address: raise it for the test, e.g. `AUTH_RATE_LIMIT=100000`.
2. From a separate machine (the load generator needs its own CPU):
   ```bash
   cd load-tests
   mvn gatling:test -DbaseUrl=https://staging.campus.example.edu -Dusers=5000 -DrampSeconds=420 -DholdSeconds=600
   ```
3. Open the HTML report printed at the end (`target/gatling/...`). Watch Grafana (`--profile monitoring`) during
   the run: JVM heap and GC, Tomcat threads, Mongo pool wait time, RabbitMQ queue depth.

A smaller smoke run: `-Dusers=200 -DrampSeconds=120 -DholdSeconds=60`.
