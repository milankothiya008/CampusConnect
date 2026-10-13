# Core API for feature authors

Everything a feature package (`com.campusconnect.feature.*`) builds on. Read `docs/CONVENTIONS.md` first.
The Node backend in `D:\PROJECTS\UniSphere\backend` is the specification (services, routes, validators,
controllers, integration tests). Keep **paths, status codes, messages, error codes and JSON shapes identical**.

## HTTP layer

| Need | Use |
|---|---|
| Success body | `ApiResponse.ok(message)`, `ok(message, data)`, `ok(message, data, meta)`; 201 → `ResponseEntity.status(201).body(...)`. `data`/`meta` are omitted when null (like `sendSuccess`). |
| Errors | `throw AppException.badRequest/unauthorized/forbidden/notFound/conflict(msg[, ErrorCode])`, `AppException.of(status, msg, code)`, `.withDetails(obj)`. Codes: `core.error.ErrorCode` (same names as `constants/ErrorCodes.js`). |
| Validation | Request records with Jakarta annotations whose `message` = the express-validator message. `@TrimmedSize(min,max,optional,message)` = `.isString().trim().isLength()`. Errors are joined with `". "` in field order. Unannotated express-validator rules say `"Invalid value"`. |
| Path ids | `ObjectIds.require(id, "id")` → 400 `Invalid id` (mongoIdParam). Body ids: `ObjectIds.requireBody(value, "club")` → `A valid club is required`. |
| Pagination | `Pagination.of(page, limit, defaultLimit[, max=50])` → `skip()`, `limit()`, `meta(total)` → `{total,page,limit,totalPages}`. |
| Populate | `Refs` bean: `refs.users(ids, "name", "avatar")`, `refs.clubs(...)`, `refs.events(...)`, `refs.load(collection, ids, fields...)`, `refs.one(...)` → `Map<id, Map>` with `_id` + exactly those fields (missing ⇒ `null`). One query per call — never per row. |
| Access | Annotations on controller methods/classes: none = signed in + verified; `@AuthenticatedOnly` (protect only); `@OptionalAuth` (optionalAuth); `@PublicApi`; `@RequireRole(GlobalRole.ADMIN)`. Inject `@AuthenticationPrincipal AuthUser actor` (null when anonymous on `@OptionalAuth`). |
| Rate limits | Global per user/IP automatically. Add `@RateLimited(RateLimitPolicy.AUTH)` for `authLimiter`, `@RateLimited(RateLimitPolicy.UPLOAD)` for `uploadLimiter` routes. |
| Cache headers | `cacheFor(n)` → `ResponseEntity.ok().cacheControl(CacheControl.maxAge(n, SECONDS).cachePublic())`. |
| Uploads (multipart) | `@RequestParam("file") MultipartFile file`; size limits are checked by the services (see Media). |

`AuthUser` (record): `id, name, email, accountType, globalRole, emailVerified, departmentCode, batchCode, avatar, active, createdAt, phone` + `isAdmin() / isFaculty() / isStudent()`. After changing any of these fields on a user, call `AuthUserService.evict(userId)` (cached 30 s per replica).

## Authorization (`core.access`)

* `ClubAccess` (bean) — port of `AuthorizationService.js`:
  * static `isAdmin/isFaculty/isStudent(user)`, `assertAdmin(user)` ("University admin access required"), `assertFaculty`, `assertStudent(user[, msg])`, `assertVerified`, `isClubMentor(user, club)`;
  * `loadClub(id)` (404 "Club not found"), `membership(userId, clubId)` (APPROVED or null), `context(user, clubId|club)` → `ClubContext(club, membership, role, roleName, isAdmin, isMentor, isMember, permissions)` with `has(ClubPermission)`;
  * `assertPermission(user, clubId|club, permission[, message])`, `assertMentor(user, clubId[, message])`, `assertCanViewParticipants`, `canSeeMemberDirectory`, `canSeeFacultyContacts`;
  * `isApprovedMemberAnywhere(userId)`, `approvedMemberIds(clubId)`, `usersWithPermission(clubId, permission)` (= `clubUsersWithPermission`), `clubIdsWithAnyPermission(...)`.
* `ClubRoles` (static) — `defaultRoles()`, `rolesOf(club)`, `findRole`, `permissionsFor`, `has`, `roleName`, `keysWith`, `sortedRoles`, `roleRank`, `newRoleKey()`, constants `PRESIDENT/VICE_PRESIDENT/MEMBER`, `PRESIDENT_ONLY`, `GRANTABLE`.
* `ClubPermission` enum — same names as `constants/Permissions.js`.
* `PausedClubs` (bean) — `ids()` (cached 30 s), static `isPaused(club|status)`, `PAUSED`; **call `evict()` after any club status change**.
* `DepartmentScope` (bean) — `resolve(allDepartments, codes)`, static `describe`, `belongs`, `includes(code)` (Criteria), `assertMentorMatches(Person, Scope)`, `assertStudentsMatch(List<Person>, Scope, self, clubName)`.

## Side effects (never let them undo the main action)

* `AuditService.record(AuditAction, actorId, targetType, targetId[, from, to, reason], metadata)` — swallows failures.
* `NotificationService`:
  * `notify(userIds | userId, Notice)` — `Notice.builder().type(NotificationType.X).title(..).message(..).link(..).email(bool).emailCategory(EmailCategory.X).exclude(ids).push(PushTargets.ALL | NONE | only(ids)).build()`; inserts in-app notifications, queues push and (optionally) e-mail.
  * `notifyAllUsers(Broadcast.builder()...pushTo(ids).build())` — campus-wide, in-app only, fanned out in the background.
  * `emailUsers(userIds, EmailRequest.builder().category(..).compose(recipient -> EmailContent.builder()...build()).dedupeKey(recipient -> "...").exclude(ids).build())` — returns how many were queued.
* `EmailContent.builder()`: `category, subject, heading, paragraphs, details(List<Detail(label,value)>), image, action(Action(label,url)), reason, club(ClubLink(id,name)), code, qr(Qr(src,alt))`. Links may be relative (`/events/1`) — made absolute with `app.client-url`.
* `MailService.sendNow(MailMessage)` for one-off transactional mail; `MailService.absoluteUrl(path)`.
* `PushService.pushToUsers(ids, PushPayload(title, body, url, tag, icon, kind, conversationId))`.
* `CampusMailer` (`feature.audience`): `eligibleStudentIds(event)`, `sendEventLaunchEmails(event, club, actor)`, `sendResultEmails(event, result, club, actor)`, `sendRoundResultEmails(event, round, club, actor)`, `sendAnnouncementEmails(post, club, actor, audienceMode, recipients|null, link)`.
* `AudienceService` (`feature.audience`): `parse(club, payload)`, `resolve(club, audience)` → `Resolved(visibility, notifyTo|null=campus, pushTo, emailTo, recipients)`, `describe`, `count`, `eventAudience(event, clubId)`, `eventParticipantIds(eventId)`, `clubScopeAudience(club)`.
* `SubscriptionService` (`feature.subscription`, the club bell): `clubFollowerIds(clubId[, membersOnly])`, `optedOutIds`, `followedAmong(userId, clubIds)`, `followerCount`, `isSubscribed`, `getSubscription/setSubscription(actor, clubId[, enabled])` → `Summary(subscribed, emailsEnabled, followerCount)`, `listMySubscriptions`, `turnOffForUser`.
* `SystemPosts.create(new Post(type, club, event, result, author, title, body, image))` (`feature.feed`) = `FeedService.createSystemPost`.
* `LikeService` (`feature.like`): `setLiked`, `listLikers`, `likedAmong(user, LikeTargetType, ids)`.
* `VenueService` (`feature.venue`): `audienceOf(club[, departments])`, `venueFits`, `assertVenueFitsAudience`, `findConflicts`, `assertVenueAvailable(venueId, startAt, endAt, excludeEventId, excludeRoundId)`, `withVenueLock(venueId, supplier)` (Redis lock, all replicas), `getAvailableVenues(...)`, admin `createVenue/listVenues/updateVenue`.
* Cross-feature application events (`core.events`, synchronous `@EventListener`): `ClubMembershipChanged(clubId, userId)` — publish it where the Node code calls `ChatService.clubMembershipChanged`; `EventCertificatesDue(eventId)` — publish it where the Node code calls `CertificateService.announceCertificates(event)`.

## Real-time (`core.realtime`)

* `RealtimeGateway.emitToUsers(ids, "event:name", payload)` / `emitToUser` / `emitToSession` = `ChatRealtime.emitToUsers`.
* `PresenceRegistry`: `isOnline(userId)`, `onlineAmong(ids)`, `viewersOf(conversationId)`; `PresenceService.payload(...)`.
* Client frames are `@MessageMapping("typing")`-style methods in a `@Controller` (`/app/typing`); the principal is `StompPrincipal(userId)`. See `docs/REALTIME.md`.

## Media (`core.media`)

* `ImageStorage.storeImage(MultipartFile, folder)` → `Stored(url, type, size)` (StorageService.storeImage; folders `club-logos, club-covers, event-posters, feed, avatars`).
* `MediaStores.story() / gallery() / chat() / recruitment()` → `MediaStore`: `createUploadTicket(scopeId, userId, kind, ext)`, `saveLocalFile(bytes, originalName, scopeId, userId, hintKind, hintExt)`, `verifyUpload(Map media, scopeId, userId)` → `VerifiedMedia` (`toMediaFile()`), `mediaUrls(StoredMedia)` / `mediaUrls(kind, provider, key, version)` → `MediaUrls(url, poster, thumb)`, `deleteMediaQuietly(StoredMedia)`, `providerName()`, `maxBytesFor(kind)`, static `transformUrl`.
* `MediaTypes.detectImage/Video/Audio/Document(bytes[, ext])` — magic-byte checks.

## Data access

* Inject `MongoOperations`. Typed queries (`mongo.find(query, Event.class)`) convert ids and enums for you; untyped ones (`Document.class` + collection name) need `new ObjectId(id)` and `Enum.name()`.
* Atomic conditional updates (`findAndModify`, `updateFirst` with criteria) for counters, seats, locks, votes. Set `updatedAt` yourself on `Update`s (`.set("updatedAt", Instant.now())`), and `setOnInsert("createdAt", now)` on upserts.
* Clear a field with `Update.unset(...)` (e.g. `ticketCode`, so the partial unique index ignores it).
* Embedded rounds (`TimestampedSubDocument`) need `touch()` when changed.
* `FormQuestion.scope` must be set by services (`"MEMBER"` default for event forms), as `normalizeQuestions` did.
* Multi-document atomicity: `TransactionTemplate` bean (replica set) — only where the Node code used `utils/Transaction.js`.
* Caches: `SharedCaches.clearEventFeed()` / `clearClubDirectory()` after writes that change those lists (Node `clearOnWrite` on Event, Club, EventResult, Venue); `@Cacheable(CacheNames.X)` for shared caches; `LocalCaches` + `CacheEvictionBus` for per-replica caches.
* Time: `CampusTime.formatTime/formatDate/formatSchedule/toDateKey/dateKeyToInstant/formatDateKey/combineDateAndTime/intervalsOverlap`. Text: `Texts.search(field, value)` (escaped, case-insensitive regex), `Texts.clean/trimToNull/isBlank`. Phones: `Phones.normalize`. Tokens: `TicketTokens`, `CalendarTokens`, `UnsubscribeTokens`, `OtpCodes`. HMAC/random: `Hmacs`.
* Scheduled sweepers: `@Scheduled(fixedDelay = ...)` + `@SchedulerLock(name = "...", lockAtMostFor = "PT5M")`; keep the work in a public method tests can call directly (scheduling is off in tests).

## Tests

* Integration tests extend `com.campusconnect.support.IntegrationTest` (whole app, MongoDB + Redis in Docker, collections emptied before each test). Name them `*IntegrationTest` and put them next to the feature (`src/test/java/com/campusconnect/feature/<area>/`).
* `api.as(user).get/post/put/patch/delete(url[, body])` → `Response` with `status()`, `at("$.data.x")`, `string(path)`, `has(path)`, `message()`, `errorCode()`, `header(name)`, `cookie(name)`; `api.send(method, url, body, request -> request.file(...))` for multipart etc.
* `data` (`TestData`): `seedReferenceData()` → `Venues(auditorium, hall)`, `student()`, `student(s -> s.department("IT").batch("23").name("X").phone(null))`, `faculty()`, `faculty(dept, name)`, `admin()`, `activeClub(mentor, president[, name])`, `addMembership(club, user[, role])`, `TestData.eventPayload(club, venue[, overrides])`, `TestData.futureDate(days)`, `tokenFor(user)`; e-mails: `lastMailTo(email)`, `mailsTo(email)`, `mailCount()` (jobs run inline, so e-mails are "sent" immediately — no `flushEmails` needed).
* Unit tests: plain JUnit 6 + AssertJ (+ Mockito) for pure logic.
