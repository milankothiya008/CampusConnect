package com.campusconnect.feature.audience;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.social.AnnouncementAudience;
import com.campusconnect.domain.social.FeedVisibility;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Who a club announcement — and campus news — goes to (services/AudienceService.js). The club picks the audience
 * instead of every message reaching the whole university:
 * <ul>
 *   <li>EVERYONE — the whole campus in-app (the followers also by e-mail and on their phones)</li>
 *   <li>FOLLOWERS — people who follow the club (bell on): in-app, phone and e-mail</li>
 *   <li>MEMBERS — the club's members and its mentor (members with the bell on also by e-mail)</li>
 *   <li>CUSTOM — any mix of club roles, students of chosen departments/batches, chosen people and the mentor</li>
 * </ul>
 */
@Service
public class AudienceService {

    static final int MAX_PEOPLE = 200;

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final SubscriptionService subscriptions;
    private final CampusMailer mailer;

    public AudienceService(MongoOperations mongo, ClubAccess access, SubscriptionService subscriptions, CampusMailer mailer) {
        this.mongo = mongo;
        this.access = access;
        this.subscriptions = subscriptions;
        this.mailer = mailer;
    }

    /** A parsed audience; lists are empty unless the mode is CUSTOM. */
    public record Audience(AnnouncementAudience mode, List<String> roles, List<String> departments, List<String> batches, List<String> users, boolean includeMentor) {
        static Audience of(AnnouncementAudience mode) {
            return new Audience(mode, List.of(), List.of(), List.of(), List.of(), false);
        }
    }

    /** What createPost needs: visibility, who is notified in-app (null = the whole campus), pushed and e-mailed. */
    public record Resolved(FeedVisibility visibility, List<String> notifyTo, List<String> pushTo, List<String> emailTo, List<String> recipients) {
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    private static List<String> codes(Object list) {
        if (!(list instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream().map(code -> String.valueOf(code == null ? "" : code).trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty()).distinct().limit(30).toList();
    }

    private static List<String> ids(Collection<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        values.stream().filter(value -> value != null && !value.isEmpty()).forEach(unique::add);
        return new ArrayList<>(unique);
    }

    /** Normalises what the client sent; the old visibility field still works (PUBLIC → everyone, MEMBERS → members). */
    @SuppressWarnings("unchecked")
    public Audience parse(Club club, Map<String, Object> payload) {
        Map<String, Object> raw = payload != null && payload.get("audience") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        AnnouncementAudience mode;
        try {
            mode = AnnouncementAudience.valueOf(String.valueOf(raw.get("mode")));
        } catch (IllegalArgumentException e) {
            mode = payload != null && FeedVisibility.MEMBERS.name().equals(payload.get("visibility")) ? AnnouncementAudience.MEMBERS : AnnouncementAudience.EVERYONE;
        }
        if (mode != AnnouncementAudience.CUSTOM) {
            return Audience.of(mode);
        }
        List<String> roles = raw.get("roles") instanceof Collection<?> list ? list.stream().map(String::valueOf).distinct().toList() : List.of();
        if (roles.stream().anyMatch(key -> ClubRoles.findRole(club, key) == null)) {
            throw invalid("One of the chosen roles doesn't exist in this club");
        }
        List<String> people = raw.get("users") instanceof Collection<?> list
                ? ids(list.stream().map(user -> user instanceof Map<?, ?> m && m.get("_id") != null ? String.valueOf(m.get("_id")) : String.valueOf(user)).toList())
                        .stream().filter(ObjectId::isValid).toList()
                : List.of();
        if (people.size() > MAX_PEOPLE) {
            throw invalid("Choose at most " + MAX_PEOPLE + " people — or pick a role or department instead");
        }
        Audience audience = new Audience(mode, roles, codes(raw.get("departments")), codes(raw.get("batches")), people, Boolean.TRUE.equals(raw.get("includeMentor")));
        if (audience.roles().isEmpty() && audience.departments().isEmpty() && audience.batches().isEmpty() && audience.users().isEmpty() && !audience.includeMentor()) {
            throw invalid("Choose who should get this announcement");
        }
        return audience;
    }

    private List<String> activeVerified(Criteria criteria) {
        Query query = Query.query(criteria.and("isActive").is(true).and("isEmailVerified").is(true));
        query.fields().include("_id");
        return mongo.find(query, Document.class, "users").stream().map(user -> user.getObjectId("_id").toHexString()).toList();
    }

    private static List<ObjectId> objectIds(Collection<String> values) {
        return values.stream().filter(ObjectId::isValid).map(ObjectId::new).toList();
    }

    /** The people a CUSTOM audience reaches (active, verified accounts only). */
    List<String> customRecipients(Club club, Audience audience) {
        List<String> chosen = new ArrayList<>();
        if (!audience.roles().isEmpty()) {
            Query query = Query.query(Criteria.where("club").is(new ObjectId(club.getId())).and("status").is(MembershipStatus.APPROVED.name()).and("role").in(audience.roles()));
            query.fields().include("user");
            mongo.find(query, Document.class, "clubmemberships").forEach(row -> chosen.add(row.getObjectId("user").toHexString()));
        }
        if (!audience.departments().isEmpty() || !audience.batches().isEmpty()) {
            Criteria students = Criteria.where("accountType").is(AccountType.STUDENT.name());
            if (!audience.departments().isEmpty()) {
                students = students.and("departmentCode").in(audience.departments());
            }
            if (!audience.batches().isEmpty()) {
                students = students.and("batchCode").in(audience.batches());
            }
            chosen.addAll(activeVerified(students));
        }
        if (!audience.users().isEmpty()) {
            chosen.addAll(activeVerified(Criteria.where("_id").in(objectIds(audience.users()))));
        }
        if (audience.includeMentor() && club.getMentor() != null) {
            chosen.add(club.getMentor());
        }
        List<String> unique = ids(chosen);
        if (unique.isEmpty()) {
            return List.of();
        }
        // Role holders and the mentor are known accounts; make sure everyone is still active and verified.
        return activeVerified(Criteria.where("_id").in(objectIds(unique)));
    }

    public Resolved resolve(Club club, Audience audience) {
        switch (audience.mode()) {
            case EVERYONE -> {
                List<String> followers = subscriptions.clubFollowerIds(club.getId());
                return new Resolved(FeedVisibility.PUBLIC, null, followers, followers, null);
            }
            case FOLLOWERS -> {
                List<String> followers = subscriptions.clubFollowerIds(club.getId());
                return new Resolved(FeedVisibility.PUBLIC, followers, followers, followers, null);
            }
            case MEMBERS -> {
                List<String> members = new ArrayList<>(access.approvedMemberIds(club.getId()));
                List<String> followingMembers = subscriptions.clubFollowerIds(club.getId(), true);
                members.add(club.getMentor());
                List<String> everyone = ids(members);
                return new Resolved(FeedVisibility.MEMBERS, everyone, everyone, followingMembers, null);
            }
            default -> {
                List<String> recipients = customRecipients(club, audience);
                return new Resolved(FeedVisibility.AUDIENCE, recipients, recipients, recipients, recipients);
            }
        }
    }

    /** A short description for the post ("Vice-president, Treasurer · Students of CE (batch 24) · 3 people"). */
    public String describe(Club club, Audience audience) {
        if (audience.mode() != AnnouncementAudience.CUSTOM) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (!audience.roles().isEmpty()) {
            parts.add(audience.roles().stream().map(key -> ClubRoles.roleName(club, key)).collect(Collectors.joining(", ")));
        }
        if (!audience.departments().isEmpty() || !audience.batches().isEmpty()) {
            parts.add("Students" + (audience.departments().isEmpty() ? "" : " of " + String.join(", ", audience.departments()))
                    + (audience.batches().isEmpty() ? "" : " (batch " + String.join(", ", audience.batches()) + ")"));
        }
        if (!audience.users().isEmpty()) {
            parts.add(audience.users().size() + " " + (audience.users().size() == 1 ? "person" : "people"));
        }
        if (audience.includeMentor()) {
            parts.add("Faculty mentor");
        }
        return String.join(" · ", parts);
    }

    /** How many people an audience reaches, shown in the composer before sending. */
    public Map<String, Object> count(Club club, Audience audience) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (audience.mode() == AnnouncementAudience.EVERYONE) {
            result.put("count", mongo.count(Query.query(Criteria.where("isActive").is(true).and("isEmailVerified").is(true)), "users"));
            result.put("email", subscriptions.clubFollowerIds(club.getId()).size());
            return result;
        }
        Resolved resolved = resolve(club, audience);
        result.put("count", resolved.notifyTo().size());
        result.put("email", resolved.emailTo().size());
        return result;
    }

    // ---------------------------------------------------------------- who hears about campus activity

    /** A new or changed event: the club's followers and members, and the eligible students — minus anyone who muted the club. */
    public List<String> eventAudience(Event event, String clubId) {
        List<String> all = new ArrayList<>(subscriptions.clubFollowerIds(clubId));
        all.addAll(access.approvedMemberIds(clubId));
        Set<String> muted = new java.util.HashSet<>(subscriptions.optedOutIds(clubId));
        mailer.eligibleStudentIds(event).stream().filter(id -> !muted.contains(id)).forEach(all::add);
        return ids(all);
    }

    /** The people involved in an event: registered or waitlisted. */
    public List<String> eventParticipantIds(String eventId) {
        Query query = Query.query(Criteria.where("event").is(new ObjectId(eventId))
                .and("status").in(RegistrationStatus.REGISTERED.name(), RegistrationStatus.WAITLISTED.name()));
        query.fields().include("user");
        return ids(mongo.find(query, Document.class, "eventregistrations").stream().map(row -> row.getObjectId("user").toHexString()).toList());
    }

    /** A new club: the students and faculty of the departments it is open to (everyone for all-department clubs). */
    public List<String> clubScopeAudience(Club club) {
        Criteria filter = new Criteria();
        if (!club.isAllDepartments() && !club.getDepartmentCodes().isEmpty()) {
            filter = Criteria.where("departmentCode").in(club.getDepartmentCodes());
        }
        return activeVerified(filter);
    }
}
