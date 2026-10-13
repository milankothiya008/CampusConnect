package com.campusconnect.feature.club;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.Recipient;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.EmailRequest;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Suspending, archiving and reactivating a club (services/ClubStatusService.js). A pause keeps the club's upcoming
 * events and live recruitment as they are but hides them and closes them to registrations, applications and offers;
 * reactivating resumes them and moves recruitment and offer deadlines on by the paused time, so nobody loses time.
 * The president, the mentor, registered students and applicants are told both times.
 */
@Slf4j
@Service
public class ClubStatusService {

    static final int MINIMUM_REASON = 5;
    static final List<ApplicationStatus> OPEN_APPLICATIONS = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS, ApplicationStatus.OFFERED, ApplicationStatus.RESERVE);
    static final List<RegistrationStatus> HELD_REGISTRATIONS = List.of(RegistrationStatus.REGISTERED, RegistrationStatus.WAITLISTED);

    /** Which status can follow which. An APPROVED club becomes ACTIVE only when the mentor appoints a president. */
    static final Map<ClubStatus, List<ClubStatus>> TRANSITIONS = Map.of(
            ClubStatus.ACTIVE, List.of(ClubStatus.SUSPENDED, ClubStatus.ARCHIVED),
            ClubStatus.SUSPENDED, List.of(ClubStatus.ACTIVE, ClubStatus.ARCHIVED),
            ClubStatus.ARCHIVED, List.of(ClubStatus.ACTIVE),
            ClubStatus.APPROVED, List.of(ClubStatus.ARCHIVED));

    private static final int ATTEMPTS = 3;

    private final MongoOperations mongo;
    private final Refs refs;
    private final ClubQueries queries;
    private final ClubCaches caches;
    private final AuditService audit;
    private final NotificationService notifications;

    public ClubStatusService(MongoOperations mongo, Refs refs, ClubQueries queries, ClubCaches caches, AuditService audit, NotificationService notifications) {
        this.mongo = mongo;
        this.refs = refs;
        this.queries = queries;
        this.caches = caches;
        this.audit = audit;
        this.notifications = notifications;
    }

    static String verb(ClubStatus status) {
        return switch (status) {
            case SUSPENDED -> "suspended";
            case ARCHIVED -> "archived";
            default -> "reactivated";
        };
    }

    private record Change(Club club, ClubStatus from, boolean wasPaused, Instant pausedAt, String note, Instant now) {
    }

    /**
     * The admin changes a club's status. The change is a conditional write on the status it was read with, so two
     * admins acting at once cannot both pause (and e-mail) the same club; the loser is re-checked against the new state.
     */
    public Map<String, Object> changeClubStatus(AuthUser actor, String clubId, ClubStatus status, String reason) {
        if (!ClubAccess.isAdmin(actor)) {
            throw AppException.forbidden("Only the university admin can change a club's status");
        }
        Change change = null;
        for (int attempt = 0; attempt < ATTEMPTS && change == null; attempt++) {
            change = tryChange(clubId, status, reason);
        }
        if (change == null) {
            throw AppException.conflict("This club was changed by someone else at the same moment. Please try again.", ErrorCode.INVALID_STATE);
        }
        Club club = change.club();
        audit.record(AuditAction.CLUB_STATUS_CHANGED, actor.id(), "Club", club.getId(), change.from().name(), status.name(), change.note(), Map.of());
        caches.clubChanged();
        try {
            tellEveryone(actor, change, status);
        } catch (RuntimeException e) {
            log.error("Club status side effects failed for club {} ({}): {}", club.getId(), status, e.getMessage());
        }
        return queries.getClub(actor, club.getId());
    }

    private Change tryChange(String clubId, ClubStatus status, String reason) {
        Club club = ObjectIds.isValid(clubId) ? mongo.findById(clubId, Club.class) : null;
        if (club == null) {
            throw AppException.notFound("Club not found");
        }
        String lower = status.name().toLowerCase(Locale.ROOT);
        if (club.getStatus() == status) {
            throw AppException.conflict(club.getName() + " is already " + lower, ErrorCode.INVALID_STATE);
        }
        if (!TRANSITIONS.getOrDefault(club.getStatus(), List.of()).contains(status)) {
            throw AppException.conflict("A " + String.valueOf(club.getStatus()).toLowerCase(Locale.ROOT) + " club can't become " + lower, ErrorCode.INVALID_STATE);
        }
        if (status == ClubStatus.ACTIVE && club.getPresident() == null) {
            throw AppException.conflict("A president must be assigned before the club can become active", ErrorCode.INVALID_STATE);
        }
        String note = JsValues.trimmed(reason);
        note = note.isEmpty() ? null : note;
        if (PausedClubs.isPaused(status) && (note == null || note.length() < MINIMUM_REASON)) {
            throw AppException.badRequest("Give a reason (at least " + MINIMUM_REASON + " characters) — it's emailed to the president and mentor", ErrorCode.VALIDATION_ERROR);
        }

        Instant now = Instant.now();
        boolean wasPaused = PausedClubs.isPaused(club);
        Update update = new Update().set("status", status).set("statusNote", note).set("statusChangedAt", now).set("updatedAt", now);
        if (PausedClubs.isPaused(status) && !wasPaused) {
            update.set("pausedAt", now);
        }
        if (status == ClubStatus.ACTIVE) {
            update.set("pausedAt", null);
        }
        boolean changed = mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId()).and("status").is(club.getStatus())), update, Club.class).getModifiedCount() == 1;
        if (!changed) {
            return null;
        }
        Instant pausedAt = club.getPausedAt();
        ClubStatus from = club.getStatus();
        club.setStatus(status);
        return new Change(club, from, wasPaused, pausedAt, note, now);
    }

    // ---------------------------------------------------------------- who is affected

    private record Affected(List<Event> events, List<EventRegistration> registrations, List<RecruitmentDrive> drives, List<RecruitmentApplication> applications) {
        static final Affected NONE = new Affected(List.of(), List.of(), List.of(), List.of());
    }

    private Affected affected(Club club, Instant now) {
        Query eventQuery = Query.query(Criteria.where("club").is(club.getId()).and("status").is(EventStatus.PUBLISHED).and("endAt").gt(now)).with(Sort.by("startAt"));
        eventQuery.fields().include("title", "startAt", "endAt", "venue");
        List<Event> events = mongo.find(eventQuery, Event.class);
        List<EventRegistration> registrations = List.of();
        if (!events.isEmpty()) {
            Query query = Query.query(Criteria.where("event").in(events.stream().map(Event::getId).toList()).and("status").in(HELD_REGISTRATIONS));
            query.fields().include("event", "user");
            registrations = mongo.find(query, EventRegistration.class);
        }
        Query driveQuery = Query.query(Criteria.where("club").is(club.getId()).and("status").is(RecruitmentStatus.PUBLISHED));
        driveQuery.fields().include("title", "applicationStart", "applicationEnd", "closedAt");
        List<RecruitmentDrive> drives = mongo.find(driveQuery, RecruitmentDrive.class);
        List<RecruitmentApplication> applications = List.of();
        if (!drives.isEmpty()) {
            Query query = Query.query(Criteria.where("drive").in(drives.stream().map(RecruitmentDrive::getId).toList()).and("status").in(OPEN_APPLICATIONS));
            query.fields().include("drive", "applicant", "status", "offerExpiresAt");
            applications = mongo.find(query, RecruitmentApplication.class);
        }
        return new Affected(events, registrations, drives, applications);
    }

    /** Recruitment and offer deadlines resume where they stopped; returns how long the club was paused. */
    private Duration shiftDeadlines(List<RecruitmentDrive> drives, Instant pausedAt, Instant now) {
        Duration pausedFor = pausedAt == null ? Duration.ZERO : Duration.between(pausedAt, now);
        if (pausedFor.isNegative() || pausedFor.isZero()) {
            return Duration.ZERO;
        }
        for (RecruitmentDrive drive : drives) {
            Update update = new Update();
            boolean any = false;
            if (drive.getClosedAt() == null && drive.getApplicationEnd() != null && drive.getApplicationEnd().isAfter(pausedAt)) {
                update.set("applicationEnd", drive.getApplicationEnd().plus(pausedFor));
                any = true;
            }
            if (drive.getApplicationStart() != null && drive.getApplicationStart().isAfter(pausedAt)) {
                update.set("applicationStart", drive.getApplicationStart().plus(pausedFor));
                any = true;
            }
            if (any) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(drive.getId())), update.set("updatedAt", now), RecruitmentDrive.class);
            }
        }
        if (!drives.isEmpty()) {
            // One atomic update moves every waiting offer on, instead of a write per offer.
            mongo.updateMulti(
                    Query.query(Criteria.where("drive").in(drives.stream().map(RecruitmentDrive::getId).toList()).and("status").is(ApplicationStatus.OFFERED).and("offerExpiresAt").ne(null)),
                    AggregationUpdate.update()
                            .set("offerExpiresAt").toValue(ArithmeticOperators.valueOf("offerExpiresAt").add(pausedFor.toMillis()))
                            .set("updatedAt").toValue(now),
                    RecruitmentApplication.class);
            caches.directoryChanged();
        }
        return pausedFor;
    }

    // ---------------------------------------------------------------- messages

    private void tellEveryone(AuthUser actor, Change change, ClubStatus status) {
        Club club = change.club();
        // Only a pause or a resume changes anything for students (archiving a suspended club doesn't).
        boolean pausing = PausedClubs.isPaused(status) && !change.wasPaused();
        boolean resuming = status == ClubStatus.ACTIVE && change.wasPaused();
        Affected affected = pausing || resuming ? affected(club, change.now()) : Affected.NONE;
        Duration pausedFor = resuming ? shiftDeadlines(affected.drives(), change.pausedAt(), change.now()) : Duration.ZERO;

        List<String> people = new ArrayList<>();
        if (club.getPresident() != null) {
            people.add(club.getPresident());
        }
        if (club.getMentor() != null) {
            people.add(club.getMentor());
        }
        Map<String, Map<String, Object>> existing = refs.users(people, "name");
        List<String> leaders = people.stream().filter(existing::containsKey).toList();
        List<String> participants = new ArrayList<>(new LinkedHashSet<>(affected.registrations().stream().map(EventRegistration::getUser).toList()));
        List<String> applicants = new ArrayList<>(new LinkedHashSet<>(affected.applications().stream().map(RecruitmentApplication::getApplicant).toList()));
        long stamp = change.now().toEpochMilli();
        String link = "/clubs/" + club.getId();

        notifications.notify(leaders, Notice.builder()
                .type(NotificationType.CLUB_UPDATE)
                .title(status == ClubStatus.ACTIVE ? club.getName() + " is active again" : club.getName() + " has been " + verb(status))
                .message(change.note() == null ? "" : change.note())
                .link(link)
                .build());
        EmailContent leadership = leadershipEmail(club, status, change.note(), affected.events().size(), affected.drives().size(), participants.size(), actor);
        notifications.emailUsers(leaders, EmailRequest.builder()
                .category(EmailCategory.ACCOUNT)
                .dedupeKey(user -> "club-status:" + club.getId() + ":" + status + ":" + stamp + ":" + user.id())
                .compose(user -> leadership)
                .build());

        if (!participants.isEmpty()) {
            notifications.notify(participants, Notice.builder()
                    .type(NotificationType.EVENT_UPDATED)
                    .title(resuming ? club.getName() + "'s events are back on" : club.getName() + "'s events are on hold")
                    .message(resuming ? "Your registration still stands." : "Your registration is kept while the club is paused.")
                    .link("/my-registrations")
                    .build());
            notifications.emailUsers(participants, EmailRequest.builder()
                    .category(EmailCategory.ACCOUNT)
                    .dedupeKey(user -> "club-status-events:" + club.getId() + ":" + stamp + ":" + user.id())
                    .compose(user -> {
                        Set<String> mine = affected.registrations().stream().filter(registration -> registration.getUser().equals(user.id()))
                                .map(EventRegistration::getEvent).collect(Collectors.toSet());
                        List<Event> events = affected.events().stream().filter(event -> mine.contains(event.getId())).toList();
                        return participantEmail(club, status, events, user);
                    })
                    .build());
        }
        if (!applicants.isEmpty()) {
            notifications.notify(applicants, Notice.builder()
                    .type(NotificationType.APPLICATION_UPDATE)
                    .title(resuming ? club.getName() + " recruitment has resumed" : club.getName() + " recruitment is on hold")
                    .message(resuming ? "Deadlines have moved on by the time it was paused." : "Your application is kept.")
                    .link("/my-applications")
                    .build());
            notifications.emailUsers(applicants, EmailRequest.builder()
                    .category(EmailCategory.ACCOUNT)
                    .dedupeKey(user -> "club-status-recruitment:" + club.getId() + ":" + stamp + ":" + user.id())
                    .compose(user -> {
                        Set<String> mine = affected.applications().stream().filter(application -> application.getApplicant().equals(user.id()))
                                .map(RecruitmentApplication::getDrive).collect(Collectors.toSet());
                        List<RecruitmentDrive> drives = affected.drives().stream().filter(drive -> mine.contains(drive.getId())).toList();
                        return applicantEmail(club, status, drives, pausedFor);
                    })
                    .build());
        }
    }

    private static String plural(long count, String one, String many) {
        return count == 1 ? one : many;
    }

    static EmailContent leadershipEmail(Club club, ClubStatus status, String note, int events, int drives, int people, AuthUser actor) {
        String verb = verb(status);
        boolean resumed = status == ClubStatus.ACTIVE;
        List<String> paragraphs = new ArrayList<>();
        if (resumed) {
            paragraphs.add("The university admin has reactivated " + club.getName() + ". Everything that was on hold is running again.");
            if (events > 0 || drives > 0) {
                List<String> parts = new ArrayList<>();
                if (events > 0) {
                    parts.add(events + " upcoming " + plural(events, "event", "events"));
                }
                if (drives > 0) {
                    parts.add(drives + " recruitment " + plural(drives, "drive", "drives"));
                }
                paragraphs.add("Resumed: " + String.join(" and ", parts)
                        + ". Recruitment and offer deadlines have moved on by the time the club was paused, and registered students and applicants have been told.");
            }
        } else {
            paragraphs.add("The university admin has " + verb + " " + club.getName() + ".");
            paragraphs.add("While the club is " + verb + ", its upcoming events and recruitment are on hold: students can't see them, register or apply, and new events, posts and members are paused. Nothing is deleted — it all resumes if the club is reactivated.");
            if (people > 0) {
                paragraphs.add(people + " registered " + plural(people, "student has", "students have") + " been told their event is on hold.");
            }
        }
        String actorName = actor.name() == null || actor.name().isEmpty() ? "University admin" : actor.name();
        return EmailContent.builder()
                .subject(resumed ? club.getName() + " has been reactivated" : club.getName() + " has been " + verb)
                .heading(resumed ? club.getName() + " is active again" : club.getName() + " has been " + verb)
                .paragraphs(paragraphs)
                .details(List.of(
                        new EmailContent.Detail("Club", club.getName()),
                        new EmailContent.Detail("Status", resumed ? "Active" : Character.toUpperCase(verb.charAt(0)) + verb.substring(1)),
                        new EmailContent.Detail(resumed ? "Admin's note" : "Reason", note == null ? "—" : note),
                        new EmailContent.Detail("Changed by", actorName)))
                .action(new EmailContent.Action("Open " + club.getName(), "/clubs/" + club.getId()))
                .build();
    }

    private static String when(Instant date) {
        return CampusTime.formatDate(date) + ", " + CampusTime.formatTime(date);
    }

    static EmailContent participantEmail(Club club, ClubStatus status, List<Event> events, Recipient user) {
        boolean resumed = status == ClubStatus.ACTIVE;
        String list = events.stream().map(event -> event.getTitle() + " (" + when(event.getStartAt()) + ")").collect(Collectors.joining("; "));
        String what = events.size() == 1 ? events.getFirst().getTitle() : club.getName() + " events";
        String firstName = (user.name() == null || user.name().isEmpty() ? "there" : user.name()).split(" ")[0];
        List<String> paragraphs = resumed
                ? List.of("Good news, " + firstName + " — " + club.getName() + " is active again, and your registration still stands.", "Event: " + list + ".",
                        "Check the event page for any updates from the club.")
                : List.of(club.getName() + " has been " + verb(status) + " by the university, so its upcoming events are on hold for now.",
                        "You're registered for: " + list + ". Your registration is kept — if the club is reactivated before the event, it goes ahead and we'll email you.");
        return EmailContent.builder()
                .subject((resumed ? "Back on: " : "On hold: ") + what)
                .heading(resumed ? "Your event is back on" : "Your event is on hold")
                .paragraphs(paragraphs)
                .action(events.size() == 1 ? new EmailContent.Action("View event", "/events/" + events.getFirst().getId()) : new EmailContent.Action("Your registrations", "/my-registrations"))
                .build();
    }

    static EmailContent applicantEmail(Club club, ClubStatus status, List<RecruitmentDrive> drives, Duration pausedFor) {
        boolean resumed = status == ClubStatus.ACTIVE;
        String titles = drives.stream().map(RecruitmentDrive::getTitle).collect(Collectors.joining(", "));
        long days = Math.max(1, Math.round(pausedFor.toMillis() / 86_400_000d));
        List<String> paragraphs = new ArrayList<>();
        if (resumed) {
            paragraphs.add(club.getName() + " is active again, so " + titles + " continues where it stopped.");
            if (!pausedFor.isZero()) {
                paragraphs.add("Deadlines — including any offer waiting for your answer — have moved on by about " + days + " " + plural(days, "day", "days") + ".");
            }
        } else {
            paragraphs.add(club.getName() + " has been " + verb(status) + " by the university, so " + titles + " is on hold.");
            paragraphs.add("Your application is kept as it is. If the club is reactivated, recruitment continues and deadlines move on by the time it was paused.");
        }
        return EmailContent.builder()
                .subject(resumed ? club.getName() + " recruitment has resumed" : club.getName() + " recruitment is on hold")
                .heading(resumed ? "Recruitment has resumed" : "Recruitment is on hold")
                .paragraphs(paragraphs)
                .action(new EmailContent.Action("Your applications", "/my-applications"))
                .build();
    }
}
