package com.campusconnect.feature.hackathon;

import static com.campusconnect.feature.hackathon.HackathonSupport.fail;
import static com.campusconnect.feature.hackathon.HackathonSupport.meta;
import static com.campusconnect.feature.hackathon.HackathonSupport.ms;
import static com.campusconnect.feature.hackathon.HackathonSupport.oid;
import static com.campusconnect.feature.hackathon.JsValues.cut;
import static com.campusconnect.feature.hackathon.JsValues.text;
import static com.campusconnect.feature.hackathon.JsValues.trim;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.HackathonEntry;
import com.campusconnect.domain.result.HackathonProject;
import com.campusconnect.domain.result.ProblemStatement;
import com.campusconnect.domain.result.SubmissionFieldSetting;
import com.campusconnect.domain.result.SubmissionFields;
import com.campusconnect.feature.hackathon.HackathonDtos.HackathonView;
import com.campusconnect.feature.hackathon.HackathonDtos.ProjectBody;
import com.campusconnect.feature.hackathon.HackathonDtos.RepoBody;
import com.campusconnect.feature.hackathon.HackathonSupport.Participant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * The teams' steps (services/HackathonService.js): choose a problem statement before the selection deadline,
 * register the code repository before the repository deadline, then make the final submission until the submission
 * deadline. Each step writes only its own fields, so teammates working at the same time don't overwrite each other.
 * A problem limited to N teams never gets more: choices of such a problem are serialised across replicas with a short
 * Redis lock (the Node app counted and saved without one).
 */
@Slf4j
@Service
public class HackathonEntries {

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(4);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final MongoOperations mongo;
    private final HackathonSupport support;
    private final HackathonService hackathons;
    private final NotificationService notifications;
    private final StringRedisTemplate redis;

    public HackathonEntries(MongoOperations mongo, HackathonSupport support, HackathonService hackathons, NotificationService notifications, StringRedisTemplate redis) {
        this.mongo = mongo;
        this.support = support;
        this.hackathons = hackathons;
        this.notifications = notifications;
        this.redis = redis;
    }

    private record Loaded(HackathonEntry entry, Participant participant) {
    }

    private static Document emptyProject() {
        return new Document("title", "").append("summary", "").append("repoUrl", "").append("demoUrl", "").append("videoUrl", "").append("deckUrl", "")
                .append("techStack", "");
    }

    /** The participant's entry (their team's, or their own), created the first time they do something. */
    private Loaded loadEntryFor(AuthUser actor, Event event) {
        Participant participant = support.participantFor(actor, event);
        if (participant == null) {
            throw AppException.forbidden("Only registered participants (and their team) can do this");
        }
        Query query = Query.query(Criteria.where("event").is(oid(event.getId())).and("entryKey").is(participant.entryKey()));
        Date now = new Date();
        Update update = new Update()
                .setOnInsert("team", participant.team() == null ? null : oid(participant.team()))
                .setOnInsert("owner", participant.owner() == null ? null : oid(participant.owner()))
                .setOnInsert("name", participant.name())
                .setOnInsert("problemStatement", null)
                .setOnInsert("problemChosenAt", null)
                .setOnInsert("problemChosenBy", null)
                .setOnInsert("project", emptyProject())
                .setOnInsert("repoSubmittedAt", null)
                .setOnInsert("repoSubmittedBy", null)
                .setOnInsert("submissionAnswers", new ArrayList<>())
                .setOnInsert("submittedAt", null)
                .setOnInsert("submittedBy", null)
                .setOnInsert("scores", new ArrayList<>())
                .setOnInsert("createdAt", now)
                .setOnInsert("updatedAt", now);
        HackathonEntry entry;
        try {
            entry = mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), HackathonEntry.class, HackathonSupport.ENTRIES);
        } catch (DuplicateKeyException raced) {
            entry = mongo.findOne(query, HackathonEntry.class, HackathonSupport.ENTRIES);
        }
        return new Loaded(entry, participant);
    }

    private void notifyTeam(Participant participant, AuthUser actor, String title, String message, String link) {
        List<String> others = participant.members().stream().filter(id -> !id.equals(actor.id())).toList();
        if (others.isEmpty()) {
            return;
        }
        try {
            notifications.notify(others, Notice.builder().type(NotificationType.HACKATHON_UPDATE).title(title).message(message).link(link).build());
        } catch (RuntimeException e) {
            log.error("Failed to tell the team about a hackathon change: {}", e.getMessage());
        }
    }

    private static String hackathonLink(Event event) {
        return "/events/" + event.getId() + "/hackathon";
    }

    private static void assertRunning(Event event) {
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw AppException.conflict("This hackathon isn't running", ErrorCode.INVALID_STATE);
        }
    }

    // ---------------------------------------------------------------- stage 1: the problem statement

    public HackathonView chooseProblem(AuthUser actor, String eventId, String problemId) {
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        Instant now = Instant.now();
        assertRunning(event);
        if (now.toEpochMilli() < ms(hackathon.getRevealAt())) {
            throw AppException.conflict("Problem statements haven't been released yet", ErrorCode.INVALID_STATE);
        }
        if (now.toEpochMilli() >= ms(hackathon.getSelectionDeadline())) {
            throw AppException.conflict("The problem selection deadline has passed", ErrorCode.INVALID_STATE);
        }
        ProblemStatement problem = HackathonSupport.problem(hackathon, problemId);
        if (problem == null) {
            throw AppException.notFound("Problem statement not found");
        }
        Loaded loaded = loadEntryFor(actor, event);
        HackathonEntry entry = loaded.entry();
        if (problem.getId().equals(entry.getProblemStatement())) {
            return hackathons.refreshed(actor, event);
        }
        Runnable choose = () -> {
            if (problem.getMaxTeams() != null && problem.getMaxTeams() > 0) {
                long taken = mongo.count(Query.query(Criteria.where("event").is(oid(event.getId())).and("problemStatement").is(oid(problem.getId()))), HackathonSupport.ENTRIES);
                if (taken >= problem.getMaxTeams()) {
                    throw AppException.conflict("\"" + problem.getTitle() + "\" already has its " + problem.getMaxTeams() + " team" + (problem.getMaxTeams() == 1 ? "" : "s")
                            + ". Choose another problem.");
                }
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(entry.getId()))),
                    new Update().set("problemStatement", oid(problem.getId())).set("problemChosenAt", now).set("problemChosenBy", oid(actor.id())).set("updatedAt", now),
                    HackathonSupport.ENTRIES);
        };
        if (problem.getMaxTeams() != null && problem.getMaxTeams() > 0) {
            withProblemLock(problem.getId(), () -> {
                choose.run();
                return null;
            });
        } else {
            choose.run();
        }
        notifyTeam(loaded.participant(), actor, entry.getName() + " chose \"" + problem.getTitle() + "\"",
                actor.name() + " picked your team's problem statement. It can be changed until " + CampusTime.formatTime(hackathon.getSelectionDeadline()) + ".",
                hackathonLink(event));
        return hackathons.refreshed(actor, event);
    }

    /** One choice of a limited problem at a time, on every replica; without Redis it behaves like the Node app. */
    private <T> T withProblemLock(String problemId, Supplier<T> task) {
        String key = "cc:lock:hackathon-problem:" + problemId;
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            long giveUpAt = System.currentTimeMillis() + LOCK_WAIT.toMillis();
            while (!(locked = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, LOCK_TTL)))) {
                if (System.currentTimeMillis() > giveUpAt) {
                    throw AppException.conflict("Another team is choosing this problem statement right now. Please try again.");
                }
                Thread.sleep(20 + ThreadLocalRandom.current().nextInt(40));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Problem lock unavailable, choosing without it: {}", e.getMessage());
            locked = false;
        }
        try {
            return task.get();
        } finally {
            if (locked) {
                try {
                    redis.execute(RELEASE, List.of(key), token);
                } catch (RuntimeException ignored) {
                    // The lock expires by itself.
                }
            }
        }
    }

    // ---------------------------------------------------------------- links

    /** {@code cleanUrl}: an http(s) link as the browser's URL parser writes it, at most 500 characters. */
    static String cleanUrl(Object value, String label, boolean required) {
        String text = trim(text(value));
        if (text.isEmpty()) {
            if (required) {
                throw fail("Add the " + label);
            }
            return "";
        }
        WebLinks.Parsed url = WebLinks.parse(text);
        if (url == null) {
            throw fail("The " + label + " must be a full link starting with https://");
        }
        if (!url.isHttp()) {
            throw fail("The " + label + " must be an http(s) link");
        }
        return cut(url.href(), 500);
    }

    // ---------------------------------------------------------------- stage 2: the code repository

    public HackathonView submitRepo(AuthUser actor, String eventId, RepoBody body) {
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        Instant now = Instant.now();
        assertRunning(event);
        if (now.toEpochMilli() < ms(hackathon.getRevealAt())) {
            throw AppException.conflict("Repositories can be added once the problem statements are released", ErrorCode.INVALID_STATE);
        }
        if (now.toEpochMilli() >= ms(hackathon.getRepoDeadline())) {
            throw AppException.conflict("The repository deadline has passed", ErrorCode.INVALID_STATE);
        }
        Loaded loaded = loadEntryFor(actor, event);
        HackathonEntry entry = loaded.entry();
        if (!hackathon.getProblemStatements().isEmpty() && entry.getProblemStatement() == null) {
            throw AppException.conflict("Choose your problem statement first", ErrorCode.INVALID_STATE);
        }
        String repoUrl = cleanUrl(body.repoUrl(), "repository link", true);
        boolean first = entry.getRepoSubmittedAt() == null;
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(entry.getId()))),
                new Update().set("project.repoUrl", repoUrl).set("repoSubmittedAt", now).set("repoSubmittedBy", oid(actor.id())).set("updatedAt", now),
                HackathonSupport.ENTRIES);
        support.audit(event, AuditAction.HACKATHON_SUBMITTED, actor, meta("entry", oid(entry.getId()), "stage", "repository", "first", first));
        notifyTeam(loaded.participant(), actor, first ? entry.getName() + " added its code repository" : entry.getName() + "'s repository link was changed",
                "By " + actor.name() + ". It can be changed until " + CampusTime.formatTime(hackathon.getRepoDeadline()) + "; the final submission opens then.",
                hackathonLink(event));
        return hackathons.refreshed(actor, event);
    }

    // ---------------------------------------------------------------- stage 3: the final submission

    private static String field(SubmissionFieldSetting setting, Object value, String label, boolean techStack) {
        if (setting == SubmissionFieldSetting.OFF) {
            return "";
        }
        String out = techStack ? cut(trim(text(value)), 300) : cleanUrl(value, label, false);
        if (setting == SubmissionFieldSetting.REQUIRED && out.isEmpty()) {
            throw fail("Add the " + label);
        }
        return out;
    }

    public HackathonView submitProject(AuthUser actor, String eventId, ProjectBody input) {
        ProjectBody body = input == null ? ProjectBody.EMPTY : input;
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        Instant now = Instant.now();
        assertRunning(event);
        if (now.toEpochMilli() < ms(hackathon.getRepoDeadline())) {
            throw AppException.conflict("The final submission opens at " + CampusTime.formatTime(hackathon.getRepoDeadline()) + ", after the repository deadline",
                    ErrorCode.INVALID_STATE);
        }
        if (now.toEpochMilli() >= ms(hackathon.getSubmissionDeadline())) {
            throw AppException.conflict("The submission deadline has passed", ErrorCode.INVALID_STATE);
        }
        Loaded loaded = loadEntryFor(actor, event);
        HackathonEntry entry = loaded.entry();
        if (entry.getRepoSubmittedAt() == null) {
            throw AppException.conflict("Your team didn't add a code repository before the repository deadline, so it can't make a final submission", ErrorCode.INVALID_STATE);
        }

        String title = trim(text(body.title()));
        String summary = trim(text(body.summary()));
        if (title.length() < 2) {
            throw fail("Give your project a name");
        }
        if (summary.length() < 20) {
            throw fail("Describe what you built (at least 20 characters)");
        }
        // Fields follow the organisers' form: REQUIRED must be filled, OFF is ignored.
        SubmissionFields fields = HackathonSupport.fieldsOf(hackathon);
        HackathonProject project = new HackathonProject();
        project.setTitle(cut(title, 120));
        project.setSummary(cut(summary, 3000));
        // The repository is locked from the repository deadline.
        project.setRepoUrl(entry.getProject() != null && entry.getProject().getRepoUrl() != null ? entry.getProject().getRepoUrl() : "");
        project.setDemoUrl(field(fields.getDemoUrl(), body.demoUrl(), "live demo link", false));
        project.setVideoUrl(field(fields.getVideoUrl(), body.videoUrl(), "demo video link", false));
        project.setDeckUrl(field(fields.getDeckUrl(), body.deckUrl(), "presentation link", false));
        project.setTechStack(field(fields.getTechStack(), body.techStack(), "tech stack", true));
        // With the default form (both optional), a project still needs something to look at.
        if (fields.getDemoUrl() == SubmissionFieldSetting.OPTIONAL && fields.getVideoUrl() == SubmissionFieldSetting.OPTIONAL
                && project.getDemoUrl().isEmpty() && project.getVideoUrl().isEmpty()) {
            throw fail("Add a live demo link or a demo video");
        }
        List<FormAnswer> answers = SubmissionForms.readAnswers(hackathon.getSubmissionQuestions(), body.answers());

        boolean first = entry.getSubmittedAt() == null;
        Document projectDocument = new Document();
        mongo.getConverter().write(project, projectDocument);
        List<Document> answerDocuments = new ArrayList<>();
        answers.forEach(answer -> {
            Document document = new Document();
            mongo.getConverter().write(answer, document);
            answerDocuments.add(document);
        });
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(entry.getId()))),
                new Update().set("project", projectDocument).set("submissionAnswers", answerDocuments).set("submittedAt", now).set("submittedBy", oid(actor.id()))
                        .set("updatedAt", now),
                HackathonSupport.ENTRIES);
        support.audit(event, AuditAction.HACKATHON_SUBMITTED, actor, meta("entry", oid(entry.getId()), "first", first));
        notifyTeam(loaded.participant(), actor, first ? entry.getName() + " submitted \"" + project.getTitle() + "\"" : entry.getName() + "'s submission was updated",
                "By " + actor.name() + ". You can edit it until " + CampusTime.formatTime(hackathon.getSubmissionDeadline()) + ".", hackathonLink(event));
        return hackathons.refreshed(actor, event);
    }
}
