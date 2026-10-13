package com.campusconnect.feature.registration;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Updates;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.common.FormQuestion;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationForm;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamRole;
import com.campusconnect.feature.event.EventStore;
import com.campusconnect.feature.event.Values;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Optional registration forms (services/RegistrationFormService.js). The student's name, e-mail, department, batch
 * and phone come from their account; the club adds only the extra questions it needs. On team events each question
 * is answered once per team (by the leader) or by every member when they register or accept the invite.
 */
@Service
public class RegistrationForms {

    static final List<String> SCOPES = List.of("TEAM", "MEMBER");

    private final MongoOperations mongo;
    private final EventStore events;
    private final ClubAccess access;
    private final AuditService audit;

    public RegistrationForms(MongoOperations mongo, EventStore events, ClubAccess access, AuditService audit) {
        this.mongo = mongo;
        this.events = events;
        this.access = access;
        this.audit = audit;
    }

    static boolean isTeam(Event event) {
        return event.getParticipationMode() == ParticipationMode.TEAM;
    }

    static List<FormQuestion> formOf(Event event) {
        RegistrationForm form = event.getRegistrationForm();
        return form != null && form.isEnabled() && form.getQuestions() != null ? form.getQuestions() : List.of();
    }

    /** The organiser's form, cleaned. Individual events have no "once per team" questions. */
    public RegistrationForm normalizeRegistrationForm(Map<String, Object> form, boolean team) {
        Map<String, Object> raw = form == null ? Map.of() : form;
        boolean enabled = Values.truthy(raw.get("enabled"));
        Object questions = Values.truthy(raw.get("questions")) ? raw.get("questions") : List.of();
        List<FormQuestion> normalized = Forms.normalizeQuestions(questions, SCOPES, "registration form");
        if (!team) {
            normalized.forEach(question -> question.setScope("MEMBER"));
        }
        if (enabled && normalized.isEmpty()) {
            throw AppException.badRequest("Add at least one question, or switch the registration form off", ErrorCode.VALIDATION_ERROR);
        }
        RegistrationForm result = new RegistrationForm();
        result.setEnabled(enabled);
        result.setQuestions(normalized);
        return result;
    }

    /** The questions a person answers: everything on individual events; per role on team events. */
    public record Questions(List<FormQuestion> member, List<FormQuestion> team) {
    }

    public Questions questionsFor(Event event, TeamRole role) {
        List<FormQuestion> questions = formOf(event);
        if (!isTeam(event)) {
            return new Questions(questions, List.of());
        }
        return new Questions(
                questions.stream().filter(question -> !"TEAM".equals(question.getScope())).toList(),
                role == TeamRole.LEADER ? questions.stream().filter(question -> "TEAM".equals(question.getScope())).toList() : List.of());
    }

    public record Answers(List<FormAnswer> answers, List<FormAnswer> teamAnswers) {
    }

    /** Validates a registrant's answers ({@code input.answers} / {@code input.teamAnswers}). */
    public Answers answersFor(Event event, TeamRole role, Object answers, Object teamAnswers) {
        Questions questions = questionsFor(event, role);
        return new Answers(
                questions.member().isEmpty() ? List.of() : Forms.readAnswers(questions.member(), answers),
                questions.team().isEmpty() ? List.of() : Forms.readAnswers(questions.team(), teamAnswers));
    }

    /** The club edits its registration form (any time before the event starts). Not reviewed by the mentor. */
    public RegistrationForm updateRegistrationForm(AuthUser actor, String eventId, Map<String, Object> form) {
        Event event = events.find(eventId);
        access.assertPermission(actor, event.getClub(), ClubPermission.MANAGE_EVENTS, "You cannot edit this club's events");
        if (List.of(EventStatus.CANCELLED, EventStatus.REJECTED, EventStatus.COMPLETED).contains(event.getStatus()) || !event.getStartAt().isAfter(Instant.now())) {
            throw AppException.conflict("The registration form can be changed until the event starts", ErrorCode.INVALID_STATE);
        }
        RegistrationForm registrationForm = normalizeRegistrationForm(form, isTeam(event));
        events.update(event.getId(), new Update().set("registrationForm", registrationForm).set("updatedBy", new ObjectId(actor.id())));
        audit.record(AuditAction.EVENT_UPDATED, actor.id(), "Event", event.getId(), Map.of("clubId", event.getClub(), "fields", List.of("registrationForm")));
        return registrationForm;
    }

    /** A registered student (or the team leader, for team questions) changes their answers until the event starts. */
    public Map<String, Object> updateMyAnswers(AuthUser actor, String eventId, Object answers, Object teamAnswers) {
        Event event = events.find(eventId);
        if (!event.getStartAt().isAfter(Instant.now())) {
            throw AppException.conflict("Answers can be changed until the event starts", ErrorCode.INVALID_STATE);
        }
        EventRegistration registration = mongo.findOne(Query.query(Criteria.where("event").is(event.getId()).and("user").is(actor.id())
                .and("status").in(RegistrationStatus.REGISTERED.name(), RegistrationStatus.WAITLISTED.name())), EventRegistration.class);
        if (registration == null) {
            throw AppException.notFound("You're not registered for this event");
        }
        Answers given = answersFor(event, registration.getTeamRole(), answers, teamAnswers);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(registration.getId()))),
                Updates.touch(new Update().set("answers", given.answers())), EventRegistration.class);
        registration.setAnswers(given.answers());
        if (registration.getTeamRole() == TeamRole.LEADER && registration.getTeam() != null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(registration.getTeam()))),
                    Updates.touch(new Update().set("answers", given.teamAnswers())), Team.class);
        }
        return myAnswers(event, registration);
    }

    /** The viewer's own answers, for the event page (null when the event has no form). */
    public Map<String, Object> myAnswers(Event event, EventRegistration registration) {
        if (registration == null || formOf(event).isEmpty()) {
            return null;
        }
        List<FormAnswer> teamAnswers = List.of();
        if (registration.getTeam() != null && registration.getTeamRole() == TeamRole.LEADER) {
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(registration.getTeam())));
            query.fields().include("answers");
            Team team = mongo.findOne(query, Team.class);
            teamAnswers = team == null || team.getAnswers() == null ? List.of() : team.getAnswers();
        }
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("answers", registration.getAnswers() == null ? List.of() : registration.getAnswers());
        mine.put("teamAnswers", teamAnswers);
        return mine;
    }

    /** Answer columns for the participants list and CSV: one row per registration. */
    public List<Map<String, Object>> answerColumns(Event event) {
        return formOf(event).stream().map(question -> {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("_id", question.getId());
            column.put("label", question.getLabel());
            column.put("scope", question.getScope());
            column.put("type", question.getType());
            return column;
        }).toList();
    }

    /** One participant's answers by question id; team questions come from the team's own answers. */
    public Map<String, String> answerCells(Event event, EventRegistration registration, List<FormAnswer> teamAnswers) {
        Map<String, FormAnswer> own = new HashMap<>();
        (registration.getAnswers() == null ? List.<FormAnswer>of() : registration.getAnswers()).forEach(answer -> own.put(answer.getQuestion(), answer));
        Map<String, FormAnswer> shared = new HashMap<>();
        (teamAnswers == null ? List.<FormAnswer>of() : teamAnswers).forEach(answer -> shared.put(answer.getQuestion(), answer));
        Map<String, String> cells = new LinkedHashMap<>();
        for (FormQuestion question : formOf(event)) {
            FormAnswer answer = "TEAM".equals(question.getScope()) && isTeam(event) ? shared.get(question.getId()) : own.get(question.getId());
            cells.put(question.getId(), Forms.answerText(question, answer));
        }
        return cells;
    }
}
