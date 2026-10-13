# Differences from the Node version

The Spring Boot API keeps the Node API's paths, JSON shapes, messages and error codes, so the React app works
unchanged. This is checked by `tools/check_api_contract.py` (every frontend call has a route) and by the ported
integration tests (one JUnit test per original `test(...)`). This page lists where behaviour deliberately
differs, what was not ported, and the questions still open for the product owner.

## Everywhere

- **Real time**: Socket.IO is replaced by STOMP over `/ws`, with the same event names and payloads
  ([REALTIME.md](REALTIME.md)). New: after every (re)connect the open chat reloads its latest messages, so
  messages sent while the socket was connecting or down are no longer missing until the chat is reopened (the
  Node client had the same gap).
- **Race safety**: where Node read a document and saved it back, the Java code uses conditional (atomic) writes,
  optimistic locking or a short Redis lock. Two clicks at once now get the same 409 a repeated click gets,
  instead of duplicate offers, results or members. New 409 messages appear only after repeated clashes, which
  is practically unreachable.
- **Sign-in limits**: besides the per-address limit (as in Node), wrong passwords are counted per account, 10 per
  15 minutes from any address (`SIGN_IN_FAILURE_LIMIT`); after that even the right password gets a 429 until the
  window passes. Guessing can't be spread over many (or forged) addresses.
- **Validation corner cases** (same status codes; the frontend never sends these):
  - a missing required field reports only its own message (Node also added "Invalid value");
  - a wrongly typed JSON value, or a missing body where fields are required, gives "Malformed JSON body";
  - an explicit JSON `null` counts as "not sent".
- **JSON details**: no `__v`; decimal numbers such as scores and averages are written as `31.0` (the same number
  in JavaScript); date-times without an offset are read as UTC; times keep milliseconds.
- **Notifications and e-mails** go through RabbitMQ in batches: same titles, bodies and dedupe keys, but the
  order of notifications can differ. Failed e-mails are retried with back-off, then parked in a dead-letter queue.

## By area

- **Clubs and memberships**: venue changes now also clear the cached events feed (Node's Venue hook did; its
  service did not); the reference-data cache is keyed by `active=true` or not (random query strings can't fill
  it); clubs stored without roles show `"roles": null`; the participation record PDF is laid out with OpenPDF
  tables (same content, fonts and colours); `/users/people?q=a&q=` reads the text "a," (Node answered 400).
- **Club group chat**: someone who joins (added directly, made president, or accepting a recruitment offer) is in
  the club chat at once; Node refreshed the member list only when someone left or was removed.
- **Chat and elections**: admin handover keeps the stored member order; two admins stepping down at once can't
  leave a group without one; an election edit applies only while the election is still scheduled; a resend
  racing itself returns the existing message; link previews are attached only if the message wasn't unsent
  meanwhile, and the SSRF guard blocks more reserved address ranges and checks the address actually connected to.
- **Recruitment**:
  - bug fix: `GET /api/recruitment` now computes `eligible` (Node never loaded the club's status, so it was always
    `false` and "available drives" on My Applications was always empty);
  - accepting an offer checks the role's openings under a per-role lock, so racing reserve offers can't over-fill
    a role; the student who loses the race sees "The X seat has already been filled" or "All N X seats have
    already been filled";
  - `setOutcomes` validates every decision before writing any; a decision without `applicationId` is skipped
    (in Node it could update an arbitrary candidate);
  - an offline interview with `venue: ""` gives "Choose an available venue" (Node: a cast error); a drive whose
    club is gone gives 404 (Node: 500).
- **Results, hackathons and certificates**: result writes retry on concurrent edits instead of last-write-wins
  (a round can't be published twice); judges' scores are replaced one at a time, so judges scoring at once don't
  lose each other's marks; hackathon problems with a team limit are chosen under a short Redis lock; the
  certificate's QR code is drawn as vector squares (same position and colours, text positions within 0.01 pt of
  the Node PDF); two inputs that crashed Node with a 500 now get a 400.
- **Dashboard**: the same sections for every role; the independent reads run in parallel, like the Node
  service's `Promise.all`.

## Not ported

- The demo-content scripts `seed:demo`, `seed:showcase` and `seed:hackathon-demo` (development/demo tools,
  not product features). The reference data and the first admin (`npm run seed` in Node) are created
  automatically at startup by `StartupSeeder`; `LoadTestSeeder` creates load-test data on staging.

## Open questions

1. **Privacy**: the public results page shows award winners' e-mail addresses, exactly as Node does. Should
   `email` be dropped from the public view?
2. **Early merit notice**: winners are told their merit certificate is ready when final results are published,
   even while the event is still running; the certificate itself becomes available only after the event ends
   (as in Node).
3. **Wording** of the new seat-limit messages on recruitment offers (see Recruitment above).
4. **Duplicate elections**: two "live" elections for the same role created at the same instant are not
   prevented (the same race exists in Node). A partial unique index would close it.
5. **Shared helpers in feature packages**: `RequestChecks`, `JsValues` and `DocumentViews` (club), `JsValues`
   and `BodyCheck` (chat), the form-question checks (hackathon, reused by registration forms), `WholeNumber` and
   `PathIds` (result) are used across features and could move to `core`.
6. **Indexes**: an extra index on `eventmedias` was suggested but not added, to keep the index set identical to
   the Node models (checked by `IndexDefinitionsTest`); the queries it would speed up are cached for 30 s.
