# Backend conventions (read before writing code)

The Java backend is a faithful port of the Node/Express backend in `D:\PROJECTS\UniSphere\backend`
(read-only reference). The React frontend is reused unchanged, so **the HTTP contract is the
specification**: same paths, methods, status codes, JSON field names (`_id`!), messages and error codes.
When unsure how something behaves, read the Node service and its integration test in
`UniSphere/backend/tests/integration`.

Before writing any code, ask: *is there a library (or a Spring feature) for this?* Use it if it is
maintained and compatible (see `docs/HLD.md` for the chosen versions). Do not add dependencies without
checking compatibility with Spring Boot 4.1.1 / Java 25.

## Packages

```
com.campusconnect
├─ core.*        cross-cutting: config, web, error, security, ratelimit, cache, util, audit, access,
│                notify, mail, push, jobs, media, realtime, pdf, calendar, tokens
├─ domain.*      MongoDB documents, embedded types, enums and repositories, one sub-package per area:
│                common, user, academic, venue, club, event, result, recruitment, social,
│                notification, chat, election, audit
└─ feature.*     one package per API area: controller(s), service(s), dto/ (request + view records)
```

Feature packages may use `core` and `domain`, and call other features' services through their public
service classes. Controllers never touch repositories directly (enforced by ArchUnit).

## Domain documents

* Lombok `@Getter @Setter @NoArgsConstructor`; extend `domain.common.BaseDocument`
  (`_id` + `createdAt`/`updatedAt` via Mongo auditing). Collection names = Mongoose's plural lower-case
  names (`users`, `clubs`, `clubmemberships`, `eventregistrations`, …).
* **Field names are identical to the Mongoose schema** (the JSON the frontend reads).
* `_id`: `BaseDocument.id` is serialised as `"_id"`. Embedded sub-documents that had an `_id` in
  Mongoose extend `domain.common.SubDocument` (generates an ObjectId hex string on construction).
* References (`ref: "User"` etc.) are `String` fields stored as ObjectId:
  `@Field(targetType = FieldType.OBJECT_ID)` (also on `List<String>` of refs).
* Enums for every status/type/role field — same constant names as `constants/*.js` — stored by name.
* Defaults equal Mongoose defaults (`""`, `false`, `0`, empty lists, `null`), so JSON matches.
* Mongoose `select: false` fields and secrets: `@JsonIgnore` (never serialised).
* Boolean fields whose name starts with `is` (`isActive`, `isEmailVerified`, …): keep the field name (MongoDB
  name) and write the getter by hand annotated `@JsonProperty("isActive")`, otherwise Jackson publishes
  `active`. See `domain.user.User`.
* Indexes: `@Indexed` / `@CompoundIndex` (incl. `unique`, `sparse`, `partialFilter`, TTL `expireAfter`)
  mirroring the Mongoose `schema.index(...)` calls, **always with Mongoose's index name** (`email_1`,
  `club_1_user_1`, …): MongoDB refuses the same keys under another name (error 85), which would stop the app
  starting on a database the Node app created. `auto-index-creation` is on.
* No `_class` field is written (see `MongoConfig`): documents look exactly like the Mongoose ones.
* Repositories: `interface XRepository extends MongoRepository<X, String>` in the same package. Use
  `MongoTemplate` (inject `MongoOperations`) for atomic conditional updates (`findAndModify`,
  `updateFirst` with criteria) — never read-modify-write for counters, seats, votes, locks.

## JSON

* Jackson 3 (`tools.jackson.*`); annotations stay `com.fasterxml.jackson.annotation.*`.
* **Nulls are included** (the frontend checks `!== null`). Therefore never serialise a document that was
  loaded with a field projection — build a view record instead.
* Instants serialise as ISO-8601 strings.
* "Populated" references (Mongoose `.populate("club", "name logo")`) become small view records with
  exactly the selected fields plus `_id` (e.g. `UserRef`, `ClubRef` in `core.web.Refs`). Load related
  documents in **one batch query per type** (`Refs` helpers) — never one query per row.

## HTTP

* Success: `ApiResponse.ok(message, data)` / `ApiResponse.ok(message, data, meta)` /
  `ResponseEntity.status(201).body(ApiResponse.ok(...))` →
  `{ "success": true, "message": "...", "data": ..., "meta": {...} }` (`data`/`meta` omitted when null,
  exactly like `utils/ApiResponse.js`). Pagination meta: `core.web.Pagination` (`page`, `limit`, `total`,
  `totalPages`) — same parsing rules as `utils/Query.js`.
* Errors: throw `core.error.AppException` (factories: `badRequest`, `unauthorized`, `forbidden`,
  `notFound`, `conflict`, plus `of(status, message, ErrorCode)` and `.withDetails(...)`). The global
  handler renders `{ success:false, status:"fail"|"error", message, errorCode, details? }`.
* Validation: request bodies are records with Jakarta annotations whose `message` texts equal the
  express-validator messages; the handler joins messages with `". "` (HTTP 400, `VALIDATION_ERROR`).
  Invalid ObjectIds in paths → 400 like `mongoIdParam` (`core.web.ObjectIds.require(...)`).
* Access annotations on controller methods or classes (checked by `core.security.AccessPolicyInterceptor`):
  * no annotation → signed in **and** e-mail verified (`protect, requireVerified`);
  * `@AuthenticatedOnly` → signed in, verification not required (`protect` only);
  * `@OptionalAuth` → public, `actor` is `null` when anonymous; a bad token is still 401;
  * `@PublicApi` → public, token ignored;
  * `@RequireRole(GlobalRole.ADMIN, …)` → `restrictTo(...)`.
  The current user is injected with `@AuthenticationPrincipal AuthUser actor` (nullable for optional).
* Rate limits: global per-user/per-IP limit on every `/api` call; add `@RateLimited(RateLimitPolicy.AUTH)`
  for `authLimiter` routes and `@RateLimited(RateLimitPolicy.UPLOAD)` for `uploadLimiter` routes.
* Caching headers like `cacheFor(60)`: `ResponseEntity.ok().cacheControl(...)`.

## Services

* Signature style: `public View doThing(AuthUser actor, String id, Request body)`.
* Side effects (notifications, e-mail, push, audit) must never undo the main action: call them after the
  write; failures are logged, not thrown (mirror `safely(...)` / try-catch in the Node code).
* Cross-replica correctness: no in-memory state that must be shared — use Redis (`core.cache`,
  `core.realtime`) or MongoDB.
* Scheduled jobs: `@Scheduled` + `@SchedulerLock(name = "...")` (ShedLock on Redis).
* Time: store `Instant`; format for people with `core.util.CampusTime` (university timezone).

## Tests

* Unit tests: plain JUnit 6 + Mockito + AssertJ in `src/test/java/.../unit/...`.
* Integration tests: extend `support.IntegrationTest` (Spring Boot + Testcontainers MongoDB replica set +
  Redis, MockMvc, in-memory STOMP broker, captured mail/push) in `src/test/java/.../it/...`; use the
  factory helpers in `support.TestData`. Port the scenarios of the matching Node integration test file.
* Every feature package ships with integration tests covering its routes' happy paths and the
  permission / validation failures the Node tests check.
