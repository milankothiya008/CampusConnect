package com.campusconnect.feature.hackathon;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMember;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.domain.event.TeamStatus;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.HackathonEntry;
import com.campusconnect.domain.result.JudgingCriterion;
import com.campusconnect.domain.result.ProblemStatement;
import com.campusconnect.domain.result.SubmissionFields;
import com.campusconnect.feature.hackathon.HackathonDtos.EntryView;
import com.campusconnect.feature.hackathon.HackathonDtos.ProblemRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * What every hackathon operation shares (services/HackathonService.js): loading the event, the hackathon settings
 * (created with defaults the first time, and moved along if the event moved), who the viewer is, and the views of
 * entries. Dates are compared like JavaScript compares them (a missing date counts as 0).
 */
@Component
class HackathonSupport {

    static final long MINUTE = 60_000L;
    static final long HOUR = 60 * MINUTE;
    static final String COLLECTION = "hackathons";
    static final String ENTRIES = "hackathonentries";
    private static final String[] EVENT_FIELDS = {"title", "category", "club", "status", "startAt", "endAt", "participationMode"};

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final AuditService audit;

    HackathonSupport(MongoOperations mongo, ClubAccess access, AuditService audit) {
        this.mongo = mongo;
        this.access = access;
        this.audit = audit;
    }

    /** Who the viewer is for this hackathon. */
    record Access(ClubContext context, boolean staff, boolean mentor, boolean resultsManager, boolean judge) {
    }

    /** The viewer's entry as a registered participant: their team, or themselves for individual events. */
    record Participant(String entryKey, String team, String owner, String name, List<String> members) {
    }

    /** The four deadlines, in milliseconds. */
    record Times(long revealAt, long selectionDeadline, long repoDeadline, long submissionDeadline) {
    }

    static AppException fail(String message) {
        return AppException.badRequest(message);
    }

    static ObjectId oid(String id) {
        return new ObjectId(id);
    }

    /** A date as JavaScript compares it: missing = 0. */
    static long ms(Instant value) {
        return value == null ? 0 : value.toEpochMilli();
    }

    static Instant at(long millis) {
        return Instant.ofEpochMilli(millis);
    }

    static List<JudgingCriterion> defaultCriteria() {
        List<JudgingCriterion> criteria = new ArrayList<>();
        for (String name : List.of("Innovation", "Technical complexity", "Design & usability", "Presentation")) {
            JudgingCriterion criterion = new JudgingCriterion();
            criterion.setName(name);
            criterion.setMaxScore(10);
            criteria.add(criterion);
        }
        return criteria;
    }

    // ---------------------------------------------------------------- event and settings

    Event loadEvent(String eventId) {
        Query query = Query.query(Criteria.where("_id").is(oid(eventId)));
        query.fields().include(EVENT_FIELDS);
        Event event = mongo.findOne(query, Event.class);
        if (event == null) {
            throw AppException.notFound("Event not found");
        }
        if (!"HACKATHON".equals(event.getCategory())) {
            throw AppException.conflict("This event isn't a hackathon", ErrorCode.INVALID_STATE);
        }
        return event;
    }

    /**
     * Deadlines that fit inside the event: problems out at the start, a fifth of the time (max 2 h) to choose, the
     * repository a little later, final projects due shortly before the end.
     */
    static Times defaultTimes(Event event) {
        long start = ms(event.getStartAt());
        long end = ms(event.getEndAt());
        long span = end - start;
        long selection = start + Math.min(2 * HOUR, Math.round(span * 0.2));
        long submission = Math.max(selection + 2 * MINUTE, end - Math.min(HOUR, Math.round(span * 0.1)));
        long repo = Math.min(selection + Math.min(2 * HOUR, Math.round(span * 0.15)), submission - MINUTE);
        return new Times(start, selection, repo, Math.min(submission, end));
    }

    static boolean timesFit(Event event, Instant reveal, Instant selection, Instant repo, Instant submission) {
        return repo != null
                && ms(reveal) >= ms(event.getStartAt())
                && ms(reveal) < ms(selection)
                && ms(selection) <= ms(repo)
                && ms(repo) < ms(submission)
                && ms(submission) <= ms(event.getEndAt());
    }

    Hackathon find(String eventId) {
        return mongo.findOne(Query.query(Criteria.where("event").is(oid(eventId))), Hackathon.class);
    }

    /** The event's hackathon settings, created with defaults the first time, and moved along if the event moved. */
    Hackathon ensure(Event event) {
        Hackathon hackathon = find(event.getId());
        if (hackathon == null) {
            Hackathon fresh = new Hackathon();
            fresh.setEvent(event.getId());
            fresh.setClub(event.getClub());
            Times defaults = defaultTimes(event);
            fresh.setRevealAt(at(defaults.revealAt()));
            fresh.setSelectionDeadline(at(defaults.selectionDeadline()));
            fresh.setRepoDeadline(at(defaults.repoDeadline()));
            fresh.setSubmissionDeadline(at(defaults.submissionDeadline()));
            fresh.setCriteria(defaultCriteria());
            try {
                return mongo.insert(fresh);
            } catch (DuplicateKeyException raced) {
                hackathon = find(event.getId());
                if (hackathon == null) {
                    throw raced;
                }
            }
        }
        if (!timesFit(event, hackathon.getRevealAt(), hackathon.getSelectionDeadline(), hackathon.getRepoDeadline(), hackathon.getSubmissionDeadline())) {
            Instant repo = hackathon.getRepoDeadline();
            Instant selection = hackathon.getSelectionDeadline();
            Instant submission = hackathon.getSubmissionDeadline();
            // Older setups had no repository deadline: slot one in between the two they have, if they still fit.
            if (repo == null && selection != null && submission != null) {
                repo = at(ms(selection) + Math.round((ms(submission) - ms(selection)) / 2.0));
            }
            Update update = new Update();
            if (timesFit(event, hackathon.getRevealAt(), selection, repo, submission)) {
                hackathon.setRepoDeadline(repo);
                update.set("repoDeadline", repo);
            } else {
                // The event was rescheduled (or the times no longer fit): put the deadlines back inside it.
                Times defaults = defaultTimes(event);
                hackathon.setRevealAt(at(defaults.revealAt()));
                hackathon.setSelectionDeadline(at(defaults.selectionDeadline()));
                hackathon.setRepoDeadline(at(defaults.repoDeadline()));
                hackathon.setSubmissionDeadline(at(defaults.submissionDeadline()));
                update.set("revealAt", hackathon.getRevealAt()).set("selectionDeadline", hackathon.getSelectionDeadline())
                        .set("repoDeadline", hackathon.getRepoDeadline()).set("submissionDeadline", hackathon.getSubmissionDeadline());
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(hackathon.getId()))), update.set("updatedAt", Instant.now()), COLLECTION);
        }
        return hackathon;
    }

    static SubmissionFields fieldsOf(Hackathon hackathon) {
        SubmissionFields stored = hackathon.getSubmissionFields();
        SubmissionFields fields = new SubmissionFields();
        if (stored != null) {
            if (stored.getDemoUrl() != null) {
                fields.setDemoUrl(stored.getDemoUrl());
            }
            if (stored.getVideoUrl() != null) {
                fields.setVideoUrl(stored.getVideoUrl());
            }
            if (stored.getDeckUrl() != null) {
                fields.setDeckUrl(stored.getDeckUrl());
            }
            if (stored.getTechStack() != null) {
                fields.setTechStack(stored.getTechStack());
            }
        }
        return fields;
    }

    static int maxTotal(Hackathon hackathon) {
        return hackathon.getCriteria().stream().mapToInt(JudgingCriterion::getMaxScore).sum();
    }

    static boolean judgingOpen(Event event, Hackathon hackathon, Instant now) {
        return now.toEpochMilli() >= ms(hackathon.getSubmissionDeadline()) || event.getStatus() == EventStatus.COMPLETED;
    }

    static boolean isJudge(Hackathon hackathon, String userId) {
        return userId != null && hackathon.getJudges().stream().anyMatch(judge -> userId.equals(judge.getUser()));
    }

    static String phaseOf(Event event, Hackathon hackathon, Instant now) {
        long time = now.toEpochMilli();
        if (event.getStatus() == EventStatus.CANCELLED) {
            return "CANCELLED";
        }
        if (time < ms(hackathon.getRevealAt())) {
            return "UPCOMING";
        }
        if (time < ms(hackathon.getSelectionDeadline())) {
            return "SELECTION";
        }
        if (time < ms(hackathon.getRepoDeadline())) {
            return "REPOSITORY";
        }
        if (time < ms(hackathon.getSubmissionDeadline())) {
            return "FINAL";
        }
        return "JUDGING";
    }

    // ---------------------------------------------------------------- people

    Access accessFor(AuthUser actor, Event event, Hackathon hackathon) {
        ClubContext context = actor != null ? access.context(actor, event.getClub()) : null;
        return new Access(
                context,
                context != null && context.has(ClubPermission.MANAGE_EVENTS),
                context != null && context.isMentor(),
                context != null && context.has(ClubPermission.MANAGE_RESULTS),
                actor != null && isJudge(hackathon, actor.id()));
    }

    /** Only the club's event organisers set the hackathon up, and only while the event is still on. */
    ClubContext assertStaff(AuthUser actor, Event event) {
        ClubContext context = access.context(actor, event.getClub());
        if (!context.has(ClubPermission.MANAGE_EVENTS)) {
            throw AppException.forbidden("Only the club's event organisers can set up the hackathon");
        }
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.REJECTED) {
            throw AppException.conflict("This event is no longer running", ErrorCode.INVALID_STATE);
        }
        return context;
    }

    Participant participantFor(AuthUser actor, Event event) {
        if (actor == null) {
            return null;
        }
        Query query = Query.query(Criteria.where("event").is(oid(event.getId())).and("user").is(oid(actor.id())).and("status").is(RegistrationStatus.REGISTERED.name()));
        query.fields().include("team");
        Document registration = mongo.findOne(query, Document.class, "eventregistrations");
        if (registration == null) {
            return null;
        }
        if (event.getParticipationMode() == ParticipationMode.TEAM) {
            ObjectId teamId = registration.getObjectId("team");
            Team team = teamId == null ? null : mongo.findById(teamId, Team.class);
            if (team == null || team.getStatus() != TeamStatus.ACTIVE) {
                return null;
            }
            List<String> members = team.getMembers().stream()
                    .filter(member -> member.getStatus() == TeamMemberStatus.LEADER || member.getStatus() == TeamMemberStatus.ACCEPTED)
                    .map(TeamMember::getUser)
                    .toList();
            return new Participant("team:" + team.getId(), team.getId(), team.getLeader(), team.getName(), members);
        }
        return new Participant("user:" + actor.id(), null, actor.id(), actor.name(), List.of(actor.id()));
    }

    HackathonEntry findEntry(String eventId, String entryKey) {
        return mongo.findOne(Query.query(Criteria.where("event").is(oid(eventId)).and("entryKey").is(entryKey)), HackathonEntry.class);
    }

    static ProblemStatement problem(Hackathon hackathon, String problemId) {
        return problemId == null ? null : hackathon.getProblemStatements().stream().filter(problem -> problemId.equals(problem.getId())).findFirst().orElse(null);
    }

    static EntryView entryView(HackathonEntry entry, Hackathon hackathon) {
        ProblemStatement problem = problem(hackathon, entry.getProblemStatement());
        return new EntryView(entry.getId(), entry.getName(), problem == null ? null : new ProblemRef(problem.getId(), problem.getTitle()), entry.getProblemChosenAt(),
                entry.getProject(), entry.getSubmissionAnswers() == null ? new ArrayList<>() : entry.getSubmissionAnswers(), entry.getRepoSubmittedAt(),
                entry.getSubmittedAt(), entry.getUpdatedAt());
    }

    // ---------------------------------------------------------------- audit

    void audit(Event event, AuditAction action, AuthUser actor, Map<String, Object> metadata) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("clubId", event.getClub() == null ? null : oid(event.getClub()));
        details.putAll(metadata);
        audit.record(action, actor.id(), "Event", event.getId(), details);
    }

    static Map<String, Object> meta(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
