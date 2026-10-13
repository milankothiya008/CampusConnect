package com.campusconnect.feature.club;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.access.DepartmentScope;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Texts;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.audience.AudienceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Club profile edits, the faculty mentor and the president (the write side of services/ClubService.js). */
@Service
public class ClubService {

    static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()-]{6,18}[0-9]$");

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final ClubQueries queries;
    private final ClubCaches caches;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AudienceService audience;
    private final TransactionTemplate transactions;

    private final ApplicationEventPublisher events;

    public ClubService(MongoOperations mongo, ClubAccess access, ClubQueries queries, ClubCaches caches, AuditService audit, NotificationService notifications,
            AudienceService audience, TransactionTemplate transactions, ApplicationEventPublisher events) {
        this.events = events;
        this.mongo = mongo;
        this.access = access;
        this.queries = queries;
        this.caches = caches;
        this.audit = audit;
        this.notifications = notifications;
        this.audience = audience;
        this.transactions = transactions;
    }

    static DepartmentScope.Scope scopeOf(Club club) {
        return new DepartmentScope.Scope(club.isAllDepartments(), club.getDepartmentCodes() == null ? List.of() : club.getDepartmentCodes());
    }

    static String link(Club club) {
        return "/clubs/" + club.getId();
    }

    /** Mongoose's String cast for a value stored as text. */
    static String text(Object value) {
        return value == null ? null : value instanceof String string ? string : JsValues.string(value);
    }

    /**
     * Only the club's own leadership edits its profile — not the mentor and not the university admin. Fields that are
     * present are changed ("" clears most of them); links are validated and cleaned.
     */
    public Map<String, Object> updateClub(AuthUser actor, String clubId, Map<String, Object> data) {
        ClubContext context = access.context(actor, clubId);
        if (!context.has(ClubPermission.MANAGE_CLUB)) {
            throw AppException.forbidden("Only club members with the 'manage club' permission can edit club details");
        }
        Club club = context.club();
        Update update = new Update();
        List<String> changed = new ArrayList<>();

        for (String field : List.of("description", "purpose", "logo", "category", "contactEmail")) {
            if (data.containsKey(field)) {
                Object value = data.get(field);
                String stored = "".equals(value) ? null : text(value);
                if (stored != null && ("description".equals(field) || "purpose".equals(field))) {
                    stored = JsValues.trim(stored);
                } else if (stored != null && "contactEmail".equals(field)) {
                    stored = JsValues.trim(stored).toLowerCase(Locale.ROOT);
                }
                update.set(field, stored);
                changed.add(field);
            }
        }
        for (String field : List.of("tagline", "meetingSchedule", "meetingLocation")) {
            if (data.containsKey(field)) {
                update.set(field, JsValues.trimmed(data.get(field)));
                changed.add(field);
            }
        }
        if (data.containsKey("coverImage")) {
            update.set("coverImage", JsValues.truthy(data.get("coverImage")) ? text(data.get("coverImage")) : null);
            changed.add("coverImage");
        }
        if (data.containsKey("website")) {
            update.set("website", ClubLinks.normalizeUrl(data.get("website"), "Website", null));
            changed.add("website");
        }
        if (data.containsKey("contactPhone")) {
            String phone = JsValues.trimmed(data.get("contactPhone"));
            if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) {
                throw AppException.badRequest("Enter a valid phone number, e.g. +91 98765 43210", ErrorCode.VALIDATION_ERROR);
            }
            update.set("contactPhone", phone.isEmpty() ? null : phone);
            changed.add("contactPhone");
        }
        if (data.containsKey("socialLinks")) {
            Object links = data.get("socialLinks");
            update.set("socialLinks", ClubLinks.toSocialLinks(ClubLinks.normalizeSocialLinks(JsValues.truthy(links) ? links : Map.of())));
            changed.add("socialLinks");
        }
        if (data.containsKey("name") && !JsValues.trimmed(data.get("name")).equals(club.getName())) {
            String name = JsValues.trimmed(data.get("name"));
            Query sameName = Query.query(Criteria.where("name").regex("^" + Texts.escapeRegex(name) + "$", "i").and("_id").ne(club.getId()));
            if (mongo.exists(sameName, Club.class)) {
                throw AppException.conflict("Another club already uses this name", ErrorCode.CONFLICT);
            }
            update.set("name", name);
            changed.add("name");
        }

        if (!changed.isEmpty()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId())), update.set("updatedAt", Instant.now()), Club.class);
            caches.clubChanged();
        }
        audit.record(AuditAction.CLUB_UPDATED, actor.id(), "Club", club.getId(), Map.of("fields", changed));
        return queries.getClub(actor, club.getId());
    }

    /** The admin reassigns the faculty mentor; the mentor must be from the club's departments. */
    public Map<String, Object> setMentor(AuthUser actor, String clubId, String mentorId) {
        ClubAccess.assertAdmin(actor);
        Club club = access.loadClub(clubId);
        User mentor = mongo.findOne(Query.query(Criteria.where("_id").is(mentorId).and("globalRole").is(GlobalRole.FACULTY).and("isActive").is(true)), User.class);
        if (mentor == null) {
            throw AppException.badRequest("Mentor must be an active faculty member", ErrorCode.VALIDATION_ERROR);
        }
        DepartmentScope.assertMentorMatches(new DepartmentScope.Person(mentor.getName(), mentor.getDepartmentCode()), scopeOf(club));

        String previous = club.getMentor();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId())), new Update().set("mentor", mentor.getId()).set("updatedAt", Instant.now()), Club.class);
        caches.clubChanged();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previousMentor", previous);
        metadata.put("mentorId", mentor.getId());
        audit.record(AuditAction.MENTOR_ASSIGNED, actor.id(), "Club", club.getId(), metadata);

        notifications.notify(mentor.getId(), Notice.builder()
                .type(NotificationType.CLUB_UPDATE)
                .title("You are now mentor of " + club.getName())
                .message("You will review this club's events and can appoint its president.")
                .link(link(club))
                .email(true)
                .build());
        return queries.getClub(actor, club.getId());
    }

    /**
     * The faculty mentor appoints (or replaces) the president. The first appointment activates an approved club and
     * announces it to the departments it is open to.
     */
    public Map<String, Object> assignPresident(AuthUser actor, String clubId, String userId) {
        ClubContext context = access.assertMentor(actor, clubId, "Only the club's faculty mentor can appoint the president");
        Club club = context.club();
        if (club.getStatus() != ClubStatus.APPROVED && club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("A president can only be appointed for an approved or active club", ErrorCode.INVALID_STATE);
        }
        User user = mongo.findById(userId, User.class);
        if (user == null || !user.isActive() || !user.isEmailVerified() || user.getAccountType() != AccountType.STUDENT || user.getGlobalRole() != GlobalRole.STUDENT) {
            throw AppException.badRequest("President must be an active, verified student", ErrorCode.VALIDATION_ERROR);
        }
        DepartmentScope.assertStudentsMatch(List.of(new DepartmentScope.Person(user.getName(), user.getDepartmentCode())), scopeOf(club), false, club.getName());
        if (club.getPresident() != null && club.getPresident().equals(user.getId())) {
            throw AppException.conflict("This student is already the president", ErrorCode.CONFLICT);
        }

        String previous = club.getPresident();
        boolean firstActivation = club.getStatus() == ClubStatus.APPROVED;
        ClubStatus toState = firstActivation ? ClubStatus.ACTIVE : club.getStatus();
        transactions.executeWithoutResult(transaction -> {
            Instant now = Instant.now();
            if (previous != null) {
                mongo.updateFirst(Query.query(Criteria.where("club").is(club.getId()).and("user").is(previous).and("role").is(ClubRoles.PRESIDENT)),
                        new Update().set("role", ClubRoles.MEMBER).set("updatedAt", now), ClubMembership.class);
            }
            mongo.upsert(Query.query(Criteria.where("club").is(club.getId()).and("user").is(user.getId())), new Update()
                    .set("role", ClubRoles.PRESIDENT)
                    .set("status", MembershipStatus.APPROVED)
                    .set("decidedBy", actor.id())
                    .set("decidedAt", now)
                    .set("updatedAt", now)
                    .setOnInsert("joinedAt", now)
                    .setOnInsert("createdAt", now), ClubMembership.class);
            mongo.updateFirst(Query.query(Criteria.where("club").is(club.getId()).and("user").is(user.getId()).and("joinedAt").is(null)),
                    new Update().set("joinedAt", now), ClubMembership.class);
            Update clubUpdate = new Update().set("president", user.getId()).set("updatedAt", now);
            if (firstActivation) {
                clubUpdate.set("status", ClubStatus.ACTIVE);
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId())), clubUpdate, Club.class);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("previousPresident", previous);
            metadata.put("presidentId", user.getId());
            audit.record(AuditAction.PRESIDENT_ASSIGNED, actor.id(), "Club", club.getId(), firstActivation ? ClubStatus.APPROVED.name() : toState.name(), toState.name(), null, metadata);
        });
        caches.clubChanged();
        // The new president may have just become a member: refresh the club group chat's member list.
        events.publishEvent(new ClubMembershipChanged(club.getId(), user.getId()));

        notifications.notify(user.getId(), Notice.builder()
                .type(NotificationType.CLUB_ROLE_CHANGED)
                .title("You are now president of " + club.getName())
                .message("You can now manage members, create events and publish updates for your club.")
                .link(link(club))
                .email(true)
                .build());
        if (previous != null) {
            notifications.notify(previous, Notice.builder()
                    .type(NotificationType.CLUB_ROLE_CHANGED)
                    .title("New president for " + club.getName())
                    .message(user.getName() + " has been appointed president. You remain a member.")
                    .link(link(club))
                    .build());
        }
        // A new club is news for the departments it's open to (everyone, for all-department clubs).
        if (firstActivation) {
            String description = club.getDescription() == null ? "" : club.getDescription();
            notifications.notify(audience.clubScopeAudience(club), Notice.builder()
                    .type(NotificationType.NEW_CLUB)
                    .title("New club: " + club.getName())
                    .message(description.length() > 200 ? description.substring(0, 200) : description)
                    .link(link(club))
                    .exclude(List.of(actor.id(), user.getId()))
                    .push(Notice.PushTargets.NONE)
                    .build());
        }
        return queries.getClub(actor, club.getId());
    }
}
