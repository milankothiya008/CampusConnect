package com.campusconnect.feature.hackathon;

import static com.campusconnect.feature.hackathon.HackathonSupport.HOUR;
import static com.campusconnect.feature.hackathon.HackathonSupport.fail;
import static com.campusconnect.feature.hackathon.HackathonSupport.meta;
import static com.campusconnect.feature.hackathon.HackathonSupport.ms;
import static com.campusconnect.feature.hackathon.HackathonSupport.oid;
import static com.campusconnect.feature.hackathon.JsValues.get;
import static com.campusconnect.feature.hackathon.JsValues.string;
import static com.campusconnect.feature.hackathon.JsValues.text;
import static com.campusconnect.feature.hackathon.JsValues.trim;
import static com.campusconnect.feature.hackathon.JsValues.truthy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.common.FormQuestion;
import com.campusconnect.domain.common.SubDocument;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.result.AgendaItem;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.HackathonEntry;
import com.campusconnect.domain.result.HackathonJudge;
import com.campusconnect.domain.result.JudgingCriterion;
import com.campusconnect.domain.result.ProblemStatement;
import com.campusconnect.domain.result.SubmissionFieldSetting;
import com.campusconnect.domain.result.SubmissionFields;
import com.campusconnect.feature.hackathon.HackathonDtos.HackathonView;
import com.campusconnect.feature.hackathon.HackathonDtos.HackathonViewer;
import com.campusconnect.feature.hackathon.HackathonDtos.JudgeView;
import com.campusconnect.feature.hackathon.HackathonDtos.MyEntry;
import com.campusconnect.feature.hackathon.HackathonDtos.PendingEntry;
import com.campusconnect.feature.hackathon.HackathonDtos.ProblemBody;
import com.campusconnect.feature.hackathon.HackathonDtos.ProblemView;
import com.campusconnect.feature.hackathon.HackathonDtos.SettingsBody;
import com.campusconnect.feature.hackathon.HackathonSupport.Access;
import com.campusconnect.feature.hackathon.HackathonSupport.Participant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Hackathon mode for HACKATHON events (services/HackathonService.js), the way a real hackathon runs:
 * <ol>
 *   <li>Students register (alone or as a team) like any event — no problem statement is needed to register.</li>
 *   <li>Problem statements are revealed once the event has started (revealAt).</li>
 *   <li>Each team picks one before selectionDeadline, then registers its code repository before repoDeadline.</li>
 *   <li>The final submission (demo, video, slides) is open from the repository deadline to submissionDeadline.</li>
 *   <li>Judges (faculty, or students not taking part) score every project; the leaderboard becomes the results
 *       draft the president publishes.</li>
 * </ol>
 * This class owns the page view and the organisers' setup (deadlines, agenda, criteria, problems, judges); see
 * {@link HackathonEntries} for the teams' steps and {@link HackathonJudging} for judging and results.
 */
@Slf4j
@Service
public class HackathonService {

    static final int MAX_JUDGES = 15;
    static final int MAX_PROBLEMS = 30;
    static final int MAX_AGENDA = 40;

    private final MongoOperations mongo;
    private final HackathonSupport support;
    private final Refs refs;
    private final NotificationService notifications;

    public HackathonService(MongoOperations mongo, HackathonSupport support, Refs refs, NotificationService notifications) {
        this.mongo = mongo;
        this.support = support;
        this.refs = refs;
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- the page

    public HackathonView getHackathon(AuthUser actor, String eventId) {
        Event event = support.loadEvent(eventId);
        return view(actor, event, support.ensure(event));
    }

    /** The page after a change (the hackathon is re-read, the event has not changed). */
    HackathonView refreshed(AuthUser actor, Event event) {
        return view(actor, event, support.find(event.getId()));
    }

    private HackathonView view(AuthUser actor, Event event, Hackathon hackathon) {
        Access access = support.accessFor(actor, event, hackathon);
        Instant now = Instant.now();
        long time = now.toEpochMilli();
        boolean revealed = time >= ms(hackathon.getRevealAt());
        Participant participant = support.participantFor(actor, event);
        boolean insider = access.staff() || access.mentor() || access.judge();
        boolean showProblems = insider ? access.staff() || access.mentor() || revealed : participant != null && revealed;

        Map<String, Integer> counts = showProblems ? problemCounts(event.getId()) : Map.of();
        HackathonEntry entry = participant != null ? support.findEntry(event.getId(), participant.entryKey()) : null;
        List<JudgeView> judges = access.staff() || access.mentor() ? judges(hackathon) : new ArrayList<>();

        List<AgendaItem> agenda = new ArrayList<>(hackathon.getAgenda());
        agenda.sort(Comparator.comparingLong(item -> ms(item.getStartsAt())));
        List<ProblemView> problems = new ArrayList<>();
        if (showProblems) {
            hackathon.getProblemStatements().forEach(problem -> problems.add(new ProblemView(problem.getId(), problem.getTitle(), problem.getDescription(),
                    problem.getTrack(), problem.getMaxTeams(), counts.getOrDefault(problem.getId(), 0))));
        }
        Object myEntry = null;
        if (participant != null) {
            myEntry = entry != null
                    ? new MyEntry(HackathonSupport.entryView(entry, hackathon), participant.members().size())
                    : new PendingEntry(participant.name(), null, null, null, null, participant.members().size());
        }
        boolean running = event.getStatus() == EventStatus.PUBLISHED;
        HackathonViewer viewer = new HackathonViewer(
                participant != null,
                access.staff(),
                access.judge(),
                access.staff() || access.mentor() || access.resultsManager(),
                access.resultsManager(),
                participant != null && revealed && time < ms(hackathon.getSelectionDeadline()) && running,
                participant != null && revealed && time < ms(hackathon.getRepoDeadline()) && running
                        && (hackathon.getProblemStatements().isEmpty() || (entry != null && entry.getProblemStatement() != null)),
                participant != null && time >= ms(hackathon.getRepoDeadline()) && time < ms(hackathon.getSubmissionDeadline()) && running
                        && entry != null && entry.getRepoSubmittedAt() != null);

        return new HackathonView(event.getId(), HackathonSupport.phaseOf(event, hackathon, now), hackathon.getRevealAt(), hackathon.getSelectionDeadline(),
                hackathon.getRepoDeadline(), hackathon.getSubmissionDeadline(), agenda, hackathon.getCriteria(), HackathonSupport.fieldsOf(hackathon),
                hackathon.getSubmissionQuestions() == null ? new ArrayList<>() : hackathon.getSubmissionQuestions(), HackathonSupport.maxTotal(hackathon),
                hackathon.getProblemStatements().size(), problems, hackathon.getJudges().size(), judges, hackathon.getResultsDraftedAt(), myEntry, viewer);
    }

    /** How many entries chose each problem statement. */
    private Map<String, Integer> problemCounts(String eventId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("event").is(oid(eventId)).and("problemStatement").ne(null)),
                Aggregation.group("problemStatement").count().as("n"));
        Map<String, Integer> counts = new HashMap<>();
        for (Document row : mongo.aggregate(aggregation, HackathonSupport.ENTRIES, Document.class)) {
            counts.put(row.getObjectId("_id").toHexString(), ((Number) row.get("n")).intValue());
        }
        return counts;
    }

    /** The panel for organisers and the mentor: name, account type, department and avatar, in account order. */
    private List<JudgeView> judges(Hackathon hackathon) {
        Map<String, Map<String, Object>> users = refs.users(hackathon.getJudges().stream().map(HackathonJudge::getUser).toList(), "name", "accountType", "departmentCode", "avatar");
        return users.values().stream()
                .sorted(Comparator.comparing(user -> String.valueOf(user.get("_id"))))
                .map(user -> new JudgeView(String.valueOf(user.get("_id")), (String) user.get("name"), (String) user.get("accountType"), (String) user.get("departmentCode"),
                        truthy(user.get("avatar")) ? (String) user.get("avatar") : null))
                .toList();
    }

    // ---------------------------------------------------------------- organisers: setup

    static Instant dateOrThrow(Object value, String label) {
        Instant date = truthy(value) ? JsValues.date(value) : null;
        if (date == null) {
            throw fail(label + " is not a valid date and time");
        }
        return date;
    }

    /** A sub-document keeps the {@code _id} the client sent back (Mongoose kept {@code item._id}). */
    private static void keepId(SubDocument target, Object id) {
        if (truthy(id) && ObjectId.isValid(string(id))) {
            target.setId(string(id).toLowerCase(Locale.ROOT));
        }
    }

    /** Mongoose's maxlength message for a value that is too long (the value shortened to 30 characters). */
    private static String tooLong(String path, String value, int max) {
        String shown = value.length() > 30 ? value.substring(0, 30) + "..." : value;
        return "Path `" + path + "` (`" + shown + "`, length " + value.length() + ") is longer than the maximum allowed length (" + max + ").";
    }

    /** Deadlines, agenda, the submission form and the judging criteria. */
    public HackathonView updateSettings(AuthUser actor, String eventId, SettingsBody input) {
        SettingsBody body = input == null ? SettingsBody.EMPTY : input;
        Event event = support.loadEvent(eventId);
        support.assertStaff(actor, event);
        Hackathon hackathon = support.ensure(event);

        Instant reveal = body.revealAt() != null ? dateOrThrow(body.revealAt(), "Problem statement release") : hackathon.getRevealAt();
        Instant selection = body.selectionDeadline() != null ? dateOrThrow(body.selectionDeadline(), "Problem selection deadline") : hackathon.getSelectionDeadline();
        Instant repo = body.repoDeadline() != null ? dateOrThrow(body.repoDeadline(), "Repository deadline") : hackathon.getRepoDeadline();
        Instant submission = body.submissionDeadline() != null ? dateOrThrow(body.submissionDeadline(), "Submission deadline") : hackathon.getSubmissionDeadline();
        if (ms(reveal) < ms(event.getStartAt())) {
            throw fail("Problem statements can be released once the event has started, not before");
        }
        if (ms(selection) <= ms(reveal)) {
            throw fail("The problem selection deadline must be after the problem statements are released");
        }
        if (ms(repo) < ms(selection)) {
            throw fail("The repository deadline can't be before the problem selection deadline");
        }
        if (ms(submission) <= ms(repo)) {
            throw fail("The final submission deadline must be after the repository deadline");
        }
        if (ms(submission) > ms(event.getEndAt())) {
            throw fail("The submission deadline must be before the event ends");
        }
        Update update = new Update().set("revealAt", reveal).set("selectionDeadline", selection).set("repoDeadline", repo).set("submissionDeadline", submission);
        List<String> tooLong = new ArrayList<>();

        if (body.agenda() != null) {
            List<Object> items = body.agenda();
            if (items.size() > MAX_AGENDA) {
                throw fail("The agenda can have at most " + MAX_AGENDA + " items");
            }
            List<AgendaItem> agenda = new ArrayList<>();
            long earliest = ms(event.getStartAt()) - 24 * HOUR;
            for (int index = 0; index < items.size(); index++) {
                Object item = items.get(index);
                String title = trim(text(get(item, "title")));
                if (title.length() < 2) {
                    throw fail("Agenda item " + (index + 1) + ": add a title");
                }
                Instant startsAt = dateOrThrow(get(item, "startsAt"), "Agenda item " + (index + 1));
                if (startsAt.toEpochMilli() < earliest || startsAt.toEpochMilli() > ms(event.getEndAt())) {
                    throw fail("Agenda item " + (index + 1) + " must be during the event");
                }
                AgendaItem out = new AgendaItem();
                keepId(out, get(item, "_id"));
                out.setTitle(title);
                out.setStartsAt(startsAt);
                out.setNote(JsValues.cut(trim(text(get(item, "note"))), 300));
                if (title.length() > 120) {
                    tooLong.add(tooLong("title", title, 120));
                }
                agenda.add(out);
            }
            update.set("agenda", bsonList(agenda));
        }

        // The final submission form: which standard fields are asked, and any extra questions.
        if (body.submissionFields() != null) {
            SubmissionFields current = HackathonSupport.fieldsOf(hackathon);
            SubmissionFields next = new SubmissionFields();
            next.setDemoUrl(setting(get(body.submissionFields(), "demoUrl"), current.getDemoUrl()));
            next.setVideoUrl(setting(get(body.submissionFields(), "videoUrl"), current.getVideoUrl()));
            next.setDeckUrl(setting(get(body.submissionFields(), "deckUrl"), current.getDeckUrl()));
            next.setTechStack(setting(get(body.submissionFields(), "techStack"), current.getTechStack()));
            update.set("submissionFields", bson(next));
        }
        if (body.submissionQuestions() != null) {
            List<FormQuestion> questions = SubmissionForms.normalizeQuestions(body.submissionQuestions(), "submission form");
            update.set("submissionQuestions", bsonList(questions));
        }

        if (body.criteria() != null) {
            boolean scored = mongo.exists(Query.query(Criteria.where("event").is(oid(event.getId())).and("scores.0").exists(true)), HackathonSupport.ENTRIES);
            if (scored) {
                throw AppException.conflict("Judges have started scoring, so the criteria can't change any more", ErrorCode.INVALID_STATE);
            }
            List<Object> list = body.criteria();
            if (list.isEmpty() || list.size() > 10) {
                throw fail("Use 1 to 10 judging criteria");
            }
            List<JudgingCriterion> criteria = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                Object criterion = list.get(index);
                String name = trim(text(get(criterion, "name")));
                double maxScore = JsValues.number(get(criterion, "maxScore"));
                if (name.length() < 2) {
                    throw fail("Criterion " + (index + 1) + ": add a name");
                }
                if (!JsValues.isInteger(maxScore) || maxScore < 1 || maxScore > 100) {
                    throw fail("Criterion " + (index + 1) + ": the maximum score must be 1-100");
                }
                JudgingCriterion out = new JudgingCriterion();
                keepId(out, get(criterion, "_id"));
                out.setName(name);
                out.setMaxScore((int) maxScore);
                if (name.length() > 60) {
                    tooLong.add(tooLong("name", name, 60));
                }
                criteria.add(out);
            }
            update.set("criteria", bsonList(criteria));
        }
        // Mongoose's schema limits, checked when the document is saved.
        if (!tooLong.isEmpty()) {
            throw fail("Validation failed: " + String.join(". ", tooLong));
        }

        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(hackathon.getId()))), update.set("updatedBy", oid(actor.id())).set("updatedAt", Instant.now()),
                HackathonSupport.COLLECTION);
        support.audit(event, AuditAction.HACKATHON_UPDATED, actor, meta("fields", sentFields(body)));
        return refreshed(actor, event);
    }

    /** The settings the request sent, for the audit trail ({@code Object.keys(payload)}). */
    private static List<String> sentFields(SettingsBody body) {
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("revealAt", body.revealAt());
        sent.put("selectionDeadline", body.selectionDeadline());
        sent.put("repoDeadline", body.repoDeadline());
        sent.put("submissionDeadline", body.submissionDeadline());
        sent.put("agenda", body.agenda());
        sent.put("submissionFields", body.submissionFields());
        sent.put("submissionQuestions", body.submissionQuestions());
        sent.put("criteria", body.criteria());
        List<String> fields = new ArrayList<>();
        sent.forEach((name, value) -> {
            if (value != null) {
                fields.add(name);
            }
        });
        return fields;
    }

    private static SubmissionFieldSetting setting(Object value, SubmissionFieldSetting current) {
        if (value instanceof String name) {
            for (SubmissionFieldSetting setting : SubmissionFieldSetting.values()) {
                if (setting.name().equals(name)) {
                    return setting;
                }
            }
        }
        return current;
    }

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

    private static ProblemStatement normalizeProblem(ProblemBody input) {
        ProblemBody payload = input == null ? ProblemBody.EMPTY : input;
        String title = trim(text(payload.title()));
        String description = trim(text(payload.description()));
        if (title.length() < 3) {
            throw fail("Give the problem statement a title (at least 3 characters)");
        }
        if (description.length() < 10) {
            throw fail("Describe the problem (at least 10 characters)");
        }
        Object rawLimit = payload.maxTeams();
        Integer maxTeams = null;
        if (rawLimit != null && !"".equals(rawLimit)) {
            double limit = JsValues.number(rawLimit);
            if (!JsValues.isInteger(limit) || limit < 1) {
                throw fail("Team limit must be a positive whole number, or empty for no limit");
            }
            maxTeams = (int) limit;
        }
        ProblemStatement problem = new ProblemStatement();
        problem.setTitle(JsValues.cut(title, 160));
        problem.setDescription(JsValues.cut(description, 4000));
        problem.setTrack(JsValues.cut(trim(text(payload.track())), 60));
        problem.setMaxTeams(maxTeams);
        return problem;
    }

    public HackathonView addProblem(AuthUser actor, String eventId, ProblemBody body) {
        Event event = support.loadEvent(eventId);
        support.assertStaff(actor, event);
        Hackathon hackathon = support.ensure(event);
        if (hackathon.getProblemStatements().size() >= MAX_PROBLEMS) {
            throw fail("A hackathon can have at most " + MAX_PROBLEMS + " problem statements");
        }
        ProblemStatement problem = normalizeProblem(body);
        Query room = Query.query(Criteria.where("_id").is(oid(hackathon.getId())).and("problemStatements." + (MAX_PROBLEMS - 1)).exists(false));
        Update update = new Update().push("problemStatements", bson(problem)).set("updatedBy", oid(actor.id())).set("updatedAt", Instant.now());
        if (mongo.updateFirst(room, update, HackathonSupport.COLLECTION).getMatchedCount() == 0) {
            throw fail("A hackathon can have at most " + MAX_PROBLEMS + " problem statements");
        }
        support.audit(event, AuditAction.HACKATHON_UPDATED, actor, meta("problem", "added"));
        return refreshed(actor, event);
    }

    public HackathonView updateProblem(AuthUser actor, String eventId, String problemId, ProblemBody body) {
        Event event = support.loadEvent(eventId);
        support.assertStaff(actor, event);
        Hackathon hackathon = support.ensure(event);
        ProblemStatement problem = HackathonSupport.problem(hackathon, problemId);
        if (problem == null) {
            throw AppException.notFound("Problem statement not found");
        }
        ProblemStatement changes = normalizeProblem(body);
        Update update = new Update()
                .set("problemStatements.$[p].title", changes.getTitle())
                .set("problemStatements.$[p].description", changes.getDescription())
                .set("problemStatements.$[p].track", changes.getTrack())
                .set("problemStatements.$[p].maxTeams", changes.getMaxTeams())
                .set("updatedBy", oid(actor.id()))
                .set("updatedAt", Instant.now())
                .filterArray(Criteria.where("p._id").is(oid(problem.getId())));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(hackathon.getId()))), update, HackathonSupport.COLLECTION);
        support.audit(event, AuditAction.HACKATHON_UPDATED, actor, meta("problem", "updated"));
        return refreshed(actor, event);
    }

    public HackathonView deleteProblem(AuthUser actor, String eventId, String problemId) {
        Event event = support.loadEvent(eventId);
        support.assertStaff(actor, event);
        Hackathon hackathon = support.ensure(event);
        ProblemStatement problem = HackathonSupport.problem(hackathon, problemId);
        if (problem == null) {
            throw AppException.notFound("Problem statement not found");
        }
        if (mongo.exists(Query.query(Criteria.where("event").is(oid(event.getId())).and("problemStatement").is(oid(problem.getId()))), HackathonSupport.ENTRIES)) {
            throw AppException.conflict("A team has already chosen this problem statement, so it can only be edited", ErrorCode.INVALID_STATE);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(hackathon.getId()))),
                new Update().pull("problemStatements", new Document("_id", oid(problem.getId()))).set("updatedBy", oid(actor.id())).set("updatedAt", Instant.now()),
                HackathonSupport.COLLECTION);
        return refreshed(actor, event);
    }

    // ---------------------------------------------------------------- organisers: judges

    public HackathonView addJudge(AuthUser actor, String eventId, String userId) {
        Event event = support.loadEvent(eventId);
        support.assertStaff(actor, event);
        Hackathon hackathon = support.ensure(event);
        if (hackathon.getJudges().size() >= MAX_JUDGES) {
            throw fail("A hackathon can have at most " + MAX_JUDGES + " judges");
        }
        Query userQuery = Query.query(Criteria.where("_id").is(oid(userId)).and("isActive").is(true).and("isEmailVerified").is(true));
        userQuery.fields().include("name").include("accountType");
        Document user = mongo.findOne(userQuery, Document.class, "users");
        if (user == null) {
            throw AppException.notFound("That account wasn't found");
        }
        String name = user.getString("name");
        String accountType = user.getString("accountType");
        if (!"FACULTY".equals(accountType) && !"STUDENT".equals(accountType)) {
            throw fail("Judges must be faculty or students");
        }
        String judgeId = user.getObjectId("_id").toHexString();
        if (HackathonSupport.isJudge(hackathon, judgeId)) {
            throw AppException.conflict(name + " is already a judge");
        }
        boolean takingPart = mongo.exists(Query.query(Criteria.where("event").is(oid(event.getId())).and("user").is(oid(judgeId))
                .and("status").in(RegistrationStatus.REGISTERED.name(), RegistrationStatus.WAITLISTED.name())), "eventregistrations");
        if (takingPart) {
            throw AppException.conflict(name + " is taking part in this hackathon, so they can't judge it");
        }
        Document judge = new Document("user", oid(judgeId)).append("addedBy", oid(actor.id())).append("addedAt", new Date());
        Query open = Query.query(Criteria.where("_id").is(oid(hackathon.getId())).and("judges.user").ne(oid(judgeId)).and("judges." + (MAX_JUDGES - 1)).exists(false));
        if (mongo.updateFirst(open, new Update().push("judges", judge).set("updatedAt", Instant.now()), HackathonSupport.COLLECTION).getMatchedCount() == 0) {
            Hackathon now = support.find(event.getId());
            if (now != null && HackathonSupport.isJudge(now, judgeId)) {
                throw AppException.conflict(name + " is already a judge");
            }
            throw fail("A hackathon can have at most " + MAX_JUDGES + " judges");
        }
        support.audit(event, AuditAction.HACKATHON_JUDGES_UPDATED, actor, meta("added", oid(judgeId)));
        Instant due = hackathon.getSubmissionDeadline();
        try {
            notifications.notify(judgeId, Notice.builder()
                    .type(NotificationType.JUDGE_INVITE)
                    .title("You're a judge for " + event.getTitle())
                    .message("Projects are due " + CampusTime.formatDate(due) + ", " + CampusTime.formatTime(due) + ". Your judging panel opens then.")
                    .link("/events/" + event.getId() + "/hackathon?tab=judging")
                    .email(true)
                    .emailCategory(EmailCategory.ACCOUNT)
                    .build());
        } catch (RuntimeException e) {
            log.error("Failed to tell judge {} about {}: {}", judgeId, event.getId(), e.getMessage());
        }
        return refreshed(actor, event);
    }

    public HackathonView removeJudge(AuthUser actor, String eventId, String userId) {
        Event event = support.loadEvent(eventId);
        support.assertStaff(actor, event);
        Hackathon hackathon = support.ensure(event);
        if (mongo.exists(Query.query(Criteria.where("event").is(oid(event.getId())).and("scores.judge").is(oid(userId))), HackathonSupport.ENTRIES)) {
            throw AppException.conflict("This judge has already scored projects, so they stay on the panel", ErrorCode.INVALID_STATE);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(hackathon.getId()))),
                new Update().pull("judges", new Document("user", oid(userId))).set("updatedAt", Instant.now()), HackathonSupport.COLLECTION);
        support.audit(event, AuditAction.HACKATHON_JUDGES_UPDATED, actor, meta("removed", userId));
        return refreshed(actor, event);
    }
}
