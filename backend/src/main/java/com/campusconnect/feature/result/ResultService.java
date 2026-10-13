package com.campusconnect.feature.result;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.cache.CacheNames;
import com.campusconnect.core.cache.SharedCaches;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultEntry;
import com.campusconnect.domain.result.ResultRound;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.audience.AudienceService;
import com.campusconnect.feature.audience.CampusMailer;
import com.campusconnect.feature.certificate.CertificateService;
import com.campusconnect.feature.result.ResultDtos.AwardBody;
import com.campusconnect.feature.result.ResultDtos.EntryBody;
import com.campusconnect.feature.result.ResultDtos.NewRoundBody;
import com.campusconnect.feature.result.ResultDtos.ResultBody;
import com.campusconnect.feature.result.ResultDtos.ResultView;
import com.campusconnect.feature.result.ResultDtos.RoundChangesBody;
import com.campusconnect.feature.result.ResultDtos.Viewer;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Results work like a real competition board (services/ResultService.js):
 * <ul>
 *   <li>Rounds (screening, semi-final…) are published one by one, even while the event is running.</li>
 *   <li>Final results (summary + awards) can go out once the event has started — no need to wait for "completed".</li>
 *   <li>Anyone with MANAGE_RESULTS prepares drafts; only the president (PUBLISH_RESULTS) publishes, withdraws or
 *       corrects what the campus sees. Corrections are marked as such.</li>
 * </ul>
 *
 * <p>Writes change only the fields an action touches and are conditional on the document being unchanged since it
 * was read ({@code updatedAt} is bumped on every write and used as a version): two organisers saving at the same
 * moment never overwrite each other, and a round can't be published twice. Side effects come after the write and
 * never undo it.
 */
@Slf4j
@Service
public class ResultService {

    static final int MAX_ROUNDS = 20;
    static final int MAX_ENTRIES = 500;
    /** Conditional writes retried this often when someone else changed the results in between. */
    static final int WRITE_ATTEMPTS = 5;
    private static final String COLLECTION = "eventresults";
    private static final String CANNOT_MANAGE = "You cannot manage results for this club";
    /** What result routes need of the event (never serialised as such). */
    private static final String[] EVENT_FIELDS = {"title", "poster", "startAt", "endAt", "status", "club", "category", "registeredCount", "shortDescription", "certificatesEnabled"};

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final ResultViews views;
    private final Refs refs;
    private final AuditService audit;
    private final NotificationService notifications;
    private final CampusMailer mailer;
    private final AudienceService audience;
    private final SubscriptionService subscriptions;
    private final CertificateService certificates;
    private final SharedCaches caches;

    public ResultService(MongoOperations mongo, ClubAccess access, ResultViews views, Refs refs, AuditService audit, NotificationService notifications,
            CampusMailer mailer, AudienceService audience, SubscriptionService subscriptions, CertificateService certificates, SharedCaches caches) {
        this.mongo = mongo;
        this.access = access;
        this.views = views;
        this.refs = refs;
        this.audit = audit;
        this.notifications = notifications;
        this.mailer = mailer;
        this.audience = audience;
        this.subscriptions = subscriptions;
        this.certificates = certificates;
        this.caches = caches;
    }

    public static String resultsLink(String eventId) {
        return "/results/" + eventId;
    }

    // ---------------------------------------------------------------- loading and checks

    private Event findEvent(String eventId) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(eventId)));
        query.fields().include(EVENT_FIELDS);
        Event event = mongo.findOne(query, Event.class);
        if (event == null) {
            throw AppException.notFound("Event not found");
        }
        return event;
    }

    private EventResult findResult(String eventId) {
        return mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(eventId))), EventResult.class);
    }

    static boolean hasStarted(Event event) {
        return event.getStartAt() != null && !event.getStartAt().isAfter(Instant.now());
    }

    /** Results belong to events that are live on campus (published) or finished (completed). */
    static void assertAcceptsResults(Event event) {
        if (event.getStatus() != EventStatus.PUBLISHED && event.getStatus() != EventStatus.COMPLETED) {
            throw AppException.conflict("Results can only be added to published or completed events", ErrorCode.INVALID_STATE);
        }
    }

    /** Changing something already public is a correction: only the president may do it. */
    private static void assertMayEdit(ClubContext context, boolean published, String what) {
        if (published && !context.has(ClubPermission.PUBLISH_RESULTS)) {
            throw AppException.forbidden(what + " are already published; only the club president can correct them");
        }
    }

    private static ResultRound findRound(EventResult result, String roundId) {
        ResultRound round = result == null ? null : result.getRounds().stream().filter(item -> roundId.equals(item.getId())).findFirst().orElse(null);
        if (round == null) {
            throw AppException.notFound("Round not found");
        }
        return round;
    }

    static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean sameName(String a, String b) {
        return a != null && b != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private static Integer positiveIntOrNull(Number value, String label) {
        if (value == null) {
            return null;
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || Math.rint(number) != number || number < 1) {
            throw AppException.badRequest(label + " must be a positive whole number");
        }
        return (int) number;
    }

    // ---------------------------------------------------------------- normalising what organisers send

    private Set<String> registeredUserIds(String eventId, Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        Query query = Query.query(Criteria.where("event").is(new ObjectId(eventId))
                .and("user").in(userIds.stream().map(ObjectId::new).toList())
                .and("status").is(RegistrationStatus.REGISTERED.name()));
        query.fields().include("user");
        return mongo.find(query, Document.class, "eventregistrations").stream().map(row -> row.getObjectId("user").toHexString()).collect(Collectors.toSet());
    }

    private Map<String, String> namesOf(Collection<String> userIds) {
        Map<String, String> names = new HashMap<>();
        refs.users(userIds, "name").forEach((id, user) -> names.put(id, user.get("name") == null ? null : String.valueOf(user.get("name"))));
        return names;
    }

    private static Set<String> recipients(Collection<String> ids) {
        Set<String> unique = new LinkedHashSet<>();
        ids.stream().filter(id -> id != null && !id.isEmpty()).forEach(unique::add);
        return unique;
    }

    /** Award recipients who are CampusConnect users must have been registered for the event. */
    private List<ResultAward> normalizeAwards(Event event, List<AwardBody> input) {
        List<AwardBody> awards = input == null ? List.of() : input;
        Set<String> recipientIds = recipients(awards.stream().filter(Objects::nonNull).map(AwardBody::recipientUser).toList());
        Set<String> registered = registeredUserIds(event.getId(), recipientIds);
        Map<String, String> names = namesOf(recipientIds);

        List<ResultAward> normalized = new ArrayList<>();
        for (int index = 0; index < awards.size(); index++) {
            AwardBody award = awards.get(index) == null ? new AwardBody(null, null, null, null, null, null, null) : awards.get(index);
            String label = "Award #" + (index + 1);
            String recipientUser = text(award.recipientUser());
            if (text(award.title()) == null) {
                throw AppException.badRequest(label + ": add a title");
            }
            if (recipientUser != null && !registered.contains(recipientUser)) {
                throw AppException.badRequest(label + ": the selected student was not registered for this event");
            }
            if (recipientUser == null && text(award.recipientName()) == null && text(award.teamName()) == null) {
                throw AppException.badRequest(label + ": add a participant, name or team");
            }
            ResultAward out = new ResultAward();
            out.setTitle(award.title().strip());
            out.setPosition(positiveIntOrNull(award.position(), label + " position"));
            out.setRecipientUser(recipientUser);
            out.setRecipientName(text(award.recipientName()) != null ? text(award.recipientName()) : recipientUser != null ? names.get(recipientUser) : null);
            out.setTeamName(text(award.teamName()));
            out.setPrize(text(award.prize()));
            out.setRecognition(text(award.recognition()));
            normalized.add(out);
        }
        return normalized;
    }

    /** A round's standings: every line names a registered student, a person or a team, and nobody appears twice. */
    private List<ResultEntry> normalizeEntries(Event event, List<EntryBody> input) {
        List<EntryBody> entries = input == null ? List.of() : input;
        if (entries.size() > MAX_ENTRIES) {
            throw AppException.badRequest("A round can list at most " + MAX_ENTRIES + " entries");
        }
        Set<String> recipientIds = recipients(entries.stream().filter(Objects::nonNull).map(EntryBody::recipientUser).toList());
        Set<String> registered = registeredUserIds(event.getId(), recipientIds);
        Map<String, String> names = namesOf(recipientIds);
        Set<String> seen = new HashSet<>();

        List<ResultEntry> normalized = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            EntryBody entry = entries.get(index) == null ? new EntryBody(null, null, null, null, null, null, null) : entries.get(index);
            String label = "Row " + (index + 1);
            String recipientUser = text(entry.recipientUser());
            String teamName = text(entry.teamName());
            String recipientName = text(entry.recipientName());
            if (recipientUser != null && !registered.contains(recipientUser)) {
                throw AppException.badRequest(label + ": the selected student was not registered for this event");
            }
            if (recipientUser == null && recipientName == null && teamName == null) {
                throw AppException.badRequest(label + ": add a participant, name or team");
            }
            String identity = recipientUser != null ? "u:" + recipientUser : "n:" + (teamName != null ? teamName : recipientName).toLowerCase(Locale.ROOT);
            if (!seen.add(identity)) {
                String who = teamName != null ? teamName : recipientName != null ? recipientName : names.get(recipientUser);
                throw AppException.badRequest(label + ": " + (who == null ? "undefined" : who) + " is listed twice");
            }
            String score = text(entry.score());
            if (score != null && score.length() > 40) {
                throw AppException.badRequest(label + ": score is too long");
            }
            ResultEntry out = new ResultEntry();
            out.setRank(positiveIntOrNull(entry.rank(), label + " rank"));
            out.setRecipientUser(recipientUser);
            out.setRecipientName(recipientName != null ? recipientName : recipientUser != null ? names.get(recipientUser) : null);
            out.setTeamName(teamName);
            out.setScore(score);
            out.setQualified(entry.qualified());
            out.setNote(text(entry.note()));
            normalized.add(out);
        }
        return normalized;
    }

    // ---------------------------------------------------------------- writing

    private Document bson(Object value) {
        Document document = new Document();
        mongo.getConverter().write(value, document);
        return document;
    }

    private List<Document> bsonList(List<?> values) {
        List<Document> documents = new ArrayList<>();
        values.forEach(value -> documents.add(bson(value)));
        return documents;
    }

    private static ObjectId oid(String id) {
        return new ObjectId(id);
    }

    /** A strictly newer {@code updatedAt} (millisecond precision, like MongoDB dates), so it works as a version. */
    private static Instant nextVersion(Instant previous) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return previous != null && !now.isAfter(previous) ? previous.plusMillis(1) : now;
    }

    /** Applies the update only if nobody wrote since {@code read} was loaded; returns the result id, or null to retry. */
    private String writeIfUnchanged(EventResult read, Update update) {
        Query unchanged = Query.query(Criteria.where("_id").is(oid(read.getId())).and("updatedAt").is(read.getUpdatedAt()));
        update.set("updatedAt", nextVersion(read.getUpdatedAt()));
        return mongo.updateFirst(unchanged, update, COLLECTION).getMatchedCount() == 1 ? read.getId() : null;
    }

    /** The first write of an event's results; null when another request created them at the same moment (retry). */
    private String insertResult(Event event, AuthUser actor, Consumer<EventResult> fill) {
        EventResult fresh = new EventResult();
        fresh.setEvent(event.getId());
        fresh.setClub(event.getClub());
        fresh.setCreatedBy(actor.id());
        fresh.setUpdatedBy(actor.id());
        fill.accept(fresh);
        try {
            return mongo.insert(fresh).getId();
        } catch (DuplicateKeyException raced) {
            return null;
        }
    }

    private static AppException tooBusy() {
        return AppException.conflict("These results were changed by someone else at the same moment. Please try again.");
    }

    /** Any write to results refreshes the cached Results page and events feed (EventResult clearOnWrite). */
    private void written() {
        caches.clear(CacheNames.RESULTS);
        caches.clearEventFeed();
    }

    private void record(AuditAction action, AuthUser actor, String resultId, String eventId, Map<String, Object> metadata, String toState) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", oid(eventId));
        details.putAll(metadata);
        audit.record(action, actor.id(), "EventResult", resultId, null, toState, null, details);
    }

    private static Map<String, Object> meta(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private void safely(String what, Runnable effect) {
        try {
            effect.run();
        } catch (RuntimeException e) {
            log.error("Results side effect failed ({}): {}", what, e.getMessage());
        }
    }

    private ResultRound newRound(String name, String description, List<ResultEntry> entries, AuthUser actor) {
        ResultRound round = new ResultRound();
        round.setName(name);
        round.setDescription(description);
        round.setEntries(new ArrayList<>(entries));
        round.setCreatedBy(actor.id());
        round.setUpdatedBy(actor.id());
        return round;
    }

    // ---------------------------------------------------------------- rounds

    public ResultView createRound(AuthUser actor, String eventId, NewRoundBody body) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_RESULTS, CANNOT_MANAGE);
        assertAcceptsResults(event);
        String name = text(body.name());
        if (name == null) {
            throw AppException.badRequest("Give the round a name, e.g. \"Round 1: Idea screening\"");
        }
        String description = text(body.description()) == null ? "" : text(body.description());
        List<ResultEntry> entries = null;
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            List<ResultRound> rounds = result == null ? List.of() : result.getRounds();
            if (rounds.size() >= MAX_ROUNDS) {
                throw AppException.badRequest("An event can have at most " + MAX_ROUNDS + " rounds");
            }
            if (rounds.stream().anyMatch(round -> sameName(round.getName(), name))) {
                throw AppException.conflict("There is already a round called \"" + name + "\"");
            }
            if (entries == null) {
                entries = ResultViews.sortEntries(normalizeEntries(event, body.entries()));
            }
            ResultRound round = newRound(name, description, entries, actor);
            String resultId = result == null
                    ? insertResult(event, actor, fresh -> fresh.getRounds().add(round))
                    : writeIfUnchanged(result, new Update().push("rounds", bson(round)).set("updatedBy", oid(actor.id())));
            if (resultId == null) {
                continue;
            }
            written();
            record(AuditAction.RESULT_ROUND_SAVED, actor, resultId, event.getId(), meta("round", name, "created", true), ResultStatus.DRAFT.name());
            return view(event, context);
        }
        throw tooBusy();
    }

    public ResultView updateRound(AuthUser actor, String eventId, String roundId, RoundChangesBody body) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_RESULTS, CANNOT_MANAGE);
        assertAcceptsResults(event);
        List<ResultEntry> entries = null;
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            ResultRound round = findRound(result, roundId);
            boolean published = round.getStatus() == ResultStatus.PUBLISHED;
            assertMayEdit(context, published, "This round's results");

            Update update = new Update();
            String name = round.getName();
            if (body.name() != null) {
                String renamed = text(body.name());
                if (renamed == null) {
                    throw AppException.badRequest("Round name is required");
                }
                if (result.getRounds().stream().anyMatch(other -> !other.getId().equals(round.getId()) && sameName(other.getName(), renamed))) {
                    throw AppException.conflict("There is already a round called \"" + renamed + "\"");
                }
                name = renamed;
                update.set("rounds.$[r].name", renamed);
            }
            if (body.description() != null) {
                update.set("rounds.$[r].description", text(body.description()) == null ? "" : text(body.description()));
            }
            if (body.entries() != null) {
                if (entries == null) {
                    entries = ResultViews.sortEntries(normalizeEntries(event, body.entries()));
                }
                if (published && entries.isEmpty()) {
                    throw AppException.badRequest("A published round needs at least one entry; withdraw it instead");
                }
                update.set("rounds.$[r].entries", bsonList(entries));
            }
            Instant now = Instant.now();
            update.set("rounds.$[r].updatedBy", oid(actor.id()));
            if (published) {
                update.set("rounds.$[r].correctedAt", now);
            }
            update.set("rounds.$[r].updatedAt", now).set("updatedBy", oid(actor.id())).filterArray(Criteria.where("r._id").is(oid(round.getId())));
            String resultId = writeIfUnchanged(result, update);
            if (resultId == null) {
                continue;
            }
            written();
            record(published ? AuditAction.RESULT_CORRECTED : AuditAction.RESULT_ROUND_SAVED, actor, resultId, event.getId(), meta("round", name), round.getStatus().name());
            return view(event, context);
        }
        throw tooBusy();
    }

    public ResultView deleteRound(AuthUser actor, String eventId, String roundId) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_RESULTS, CANNOT_MANAGE);
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            ResultRound round = findRound(result, roundId);
            if (round.getStatus() == ResultStatus.PUBLISHED) {
                throw AppException.conflict("Published rounds cannot be deleted; the president can withdraw them first", ErrorCode.INVALID_STATE);
            }
            String resultId = writeIfUnchanged(result, new Update().pull("rounds", new Document("_id", oid(round.getId()))).set("updatedBy", oid(actor.id())));
            if (resultId == null) {
                continue;
            }
            written();
            record(AuditAction.RESULT_ROUND_DELETED, actor, resultId, event.getId(), meta("round", round.getName()), null);
            return view(event, context);
        }
        throw tooBusy();
    }

    public ResultView publishRound(AuthUser actor, String eventId, String roundId) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.PUBLISH_RESULTS, "Only the club president can publish results");
        assertAcceptsResults(event);
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            ResultRound round = findRound(result, roundId);
            if (round.getStatus() == ResultStatus.PUBLISHED) {
                throw AppException.conflict("This round is already published", ErrorCode.INVALID_STATE);
            }
            if (round.getEntries().isEmpty()) {
                throw AppException.badRequest("Add at least one entry before publishing this round");
            }
            Instant now = Instant.now();
            Update update = new Update()
                    .set("rounds.$[r].status", ResultStatus.PUBLISHED.name())
                    .set("rounds.$[r].publishedAt", now)
                    .set("rounds.$[r].publishedBy", oid(actor.id()))
                    .set("rounds.$[r].correctedAt", null)
                    .set("rounds.$[r].updatedAt", now)
                    .set("lastPublishedAt", now)
                    .set("updatedBy", oid(actor.id()))
                    .filterArray(Criteria.where("r._id").is(oid(round.getId())));
            String resultId = writeIfUnchanged(result, update);
            if (resultId == null) {
                continue;
            }
            written();
            record(AuditAction.RESULT_ROUND_PUBLISHED, actor, resultId, event.getId(), meta("round", round.getName()), ResultStatus.PUBLISHED.name());

            // Round results matter to the people competing; the whole campus hears about the final results.
            long qualified = round.getEntries().stream().filter(entry -> Boolean.TRUE.equals(entry.getQualified())).count();
            safely("round notifications", () -> notifications.notify(registrantIds(event.getId()), Notice.builder()
                    .type(NotificationType.ROUND_RESULTS)
                    .title(round.getName() + " results: " + event.getTitle())
                    .message(qualified > 0 ? qualified + " " + (qualified == 1 ? "entry goes" : "entries go") + " through to the next round." : "See the standings.")
                    .link(resultsLink(event.getId()))
                    .exclude(List.of(actor.id()))
                    .build()));
            safely("round emails", () -> mailer.sendRoundResultEmails(event, round, context.club(), actor));
            return view(event, context);
        }
        throw tooBusy();
    }

    public ResultView unpublishRound(AuthUser actor, String eventId, String roundId) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.PUBLISH_RESULTS, "Only the club president can withdraw results");
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            ResultRound round = findRound(result, roundId);
            if (round.getStatus() != ResultStatus.PUBLISHED) {
                throw AppException.conflict("This round is not published", ErrorCode.INVALID_STATE);
            }
            Update update = new Update()
                    .set("rounds.$[r].status", ResultStatus.DRAFT.name())
                    .set("rounds.$[r].publishedAt", null)
                    .set("rounds.$[r].publishedBy", null)
                    .set("rounds.$[r].correctedAt", null)
                    .set("rounds.$[r].updatedAt", Instant.now())
                    .set("updatedBy", oid(actor.id()))
                    .filterArray(Criteria.where("r._id").is(oid(round.getId())));
            String resultId = writeIfUnchanged(result, update);
            if (resultId == null) {
                continue;
            }
            written();
            record(AuditAction.RESULT_ROUND_UNPUBLISHED, actor, resultId, event.getId(), meta("round", round.getName()), ResultStatus.DRAFT.name());
            return view(event, context);
        }
        throw tooBusy();
    }

    private List<String> registrantIds(String eventId) {
        Query query = Query.query(Criteria.where("event").is(oid(eventId)).and("status").is(RegistrationStatus.REGISTERED.name()));
        query.fields().include("user");
        return mongo.find(query, Document.class, "eventregistrations").stream().map(row -> row.getObjectId("user").toHexString()).toList();
    }

    // ---------------------------------------------------------------- final results

    public ResultView upsertResult(AuthUser actor, String eventId, ResultBody body) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_RESULTS, CANNOT_MANAGE);
        assertAcceptsResults(event);
        if (!hasStarted(event)) {
            throw AppException.conflict("Final results can be prepared once the event has started; use rounds before that", ErrorCode.INVALID_STATE);
        }
        if (text(body.summary()) == null) {
            throw AppException.badRequest("Result summary is required");
        }
        String summary = body.summary().strip();
        List<ResultAward> awards = null;
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            boolean published = result != null && result.getStatus() == ResultStatus.PUBLISHED;
            assertMayEdit(context, published, "Final results");
            if (awards == null) {
                awards = normalizeAwards(event, body.awards());
            }
            List<ResultAward> finalAwards = awards;
            String resultId;
            if (result == null) {
                resultId = insertResult(event, actor, fresh -> {
                    fresh.setSummary(summary);
                    fresh.setAwards(new ArrayList<>(finalAwards));
                });
            } else {
                Update update = new Update().set("summary", summary).set("awards", bsonList(awards)).set("updatedBy", oid(actor.id()));
                if (published) {
                    update.set("correctedAt", Instant.now());
                }
                resultId = writeIfUnchanged(result, update);
            }
            if (resultId == null) {
                continue;
            }
            written();
            ResultStatus status = published ? ResultStatus.PUBLISHED : ResultStatus.DRAFT;
            record(published ? AuditAction.RESULT_CORRECTED : AuditAction.RESULT_SAVED, actor, resultId, event.getId(), meta("awards", awards.size(), "final", true), status.name());
            return view(event, context);
        }
        throw tooBusy();
    }

    public ResultView publishResult(AuthUser actor, String eventId) {
        Event event = findEvent(eventId);
        ClubContext context = access.assertPermission(actor, event.getClub(), ClubPermission.PUBLISH_RESULTS, "Only the club president can publish results");
        assertAcceptsResults(event);
        if (!hasStarted(event)) {
            throw AppException.conflict("Final results can be published once the event has started", ErrorCode.INVALID_STATE);
        }
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            EventResult result = findResult(event.getId());
            if (result == null || result.getSummary() == null || result.getSummary().isEmpty()) {
                throw AppException.notFound("Save the final results draft before publishing");
            }
            if (result.getStatus() == ResultStatus.PUBLISHED) {
                throw AppException.conflict("Final results are already published", ErrorCode.INVALID_STATE);
            }
            Instant now = Instant.now();
            Update update = new Update()
                    .set("status", ResultStatus.PUBLISHED.name())
                    .set("publishedAt", now)
                    .set("publishedBy", oid(actor.id()))
                    .set("lastPublishedAt", now)
                    .set("updatedBy", oid(actor.id()));
            if (writeIfUnchanged(result, update) == null) {
                continue;
            }
            written();
            result.setStatus(ResultStatus.PUBLISHED);
            result.setPublishedAt(now);
            result.setLastPublishedAt(now);
            record(AuditAction.RESULT_PUBLISHED, actor, result.getId(), event.getId(), meta("final", true), ResultStatus.PUBLISHED.name());
            announce(event, result, context, actor);
            return view(event, context);
        }
        throw tooBusy();
    }

    /**
     * The participants, the club's followers and members, and its mentor are notified in-app (the results page is
     * open to everyone); winners, participants and club members are e-mailed; winners hear their merit certificate
     * is ready.
     */
    private void announce(Event event, EventResult result, ClubContext context, AuthUser actor) {
        String winners = result.getAwards().stream().sorted(ResultViews.BY_POSITION).limit(3)
                .map(award -> award.getTitle() + ": " + (award.getTeamName() != null && !award.getTeamName().isEmpty() ? award.getTeamName() : award.getRecipientName()))
                .collect(Collectors.joining(" · "));
        String summary = result.getSummary();
        safely("result notifications", () -> {
            List<String> recipients = new ArrayList<>(audience.eventParticipantIds(event.getId()));
            recipients.addAll(subscriptions.clubFollowerIds(context.club().getId()));
            recipients.addAll(access.approvedMemberIds(context.club().getId()));
            recipients.add(context.club().getMentor());
            notifications.notify(recipients, Notice.builder()
                    .type(NotificationType.RESULT_PUBLISHED)
                    .title("Results are out for " + event.getTitle())
                    .message(!winners.isEmpty() ? winners : summary.length() > 200 ? summary.substring(0, 200) : summary)
                    .link(resultsLink(event.getId()))
                    .exclude(List.of(actor.id()))
                    .push(Notice.PushTargets.NONE)
                    .build());
        });
        safely("result emails", () -> mailer.sendResultEmails(event, result, context.club(), actor));
        safely("merit certificates", () -> certificates.announceMerit(event, result));
    }

    // ---------------------------------------------------------------- reading

    /** GET /api/events/:id/results — organisers see drafts; everyone else only what is published. */
    public ResultView getResult(AuthUser actor, String eventId) {
        Event event = findEvent(eventId);
        EventResult result = findResult(event.getId());
        ClubContext context = actor != null ? access.context(actor, event.getClub()) : null;
        return visible(result, event, context);
    }

    private ResultView view(Event event, ClubContext context) {
        return visible(findResult(event.getId()), event, context);
    }

    private ResultView visible(EventResult result, Event event, ClubContext context) {
        boolean canEdit = context != null && context.has(ClubPermission.MANAGE_RESULTS);
        boolean canPublish = context != null && context.has(ClubPermission.PUBLISH_RESULTS);
        boolean canSeeDrafts = canEdit || (context != null && context.isMentor());
        boolean somethingPublic = result != null
                && (result.getStatus() == ResultStatus.PUBLISHED || result.getRounds().stream().anyMatch(round -> round.getStatus() == ResultStatus.PUBLISHED));
        if (result == null || (!somethingPublic && !canSeeDrafts)) {
            throw AppException.notFound("Results have not been published yet");
        }
        return views.result(result, event, context == null ? null : context.club(), new Viewer(canSeeDrafts, canEdit, canPublish));
    }
}
