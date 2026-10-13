package com.campusconnect.feature.subscription;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.ClubSubscription;
import com.campusconnect.domain.club.MembershipStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The club "bell", like YouTube's (services/SubscriptionService.js): with it on, a user is e-mailed the club's new
 * events and announcements. Members are subscribed by default and can switch it off; anyone else can switch it on.
 */
@Service
public class SubscriptionService {

    private final MongoOperations mongo;
    private final Refs refs;

    public SubscriptionService(MongoOperations mongo, Refs refs) {
        this.mongo = mongo;
        this.refs = refs;
    }

    public record Summary(boolean subscribed, boolean emailsEnabled, int followerCount) {
    }

    private List<String> approvedMemberIds(String clubId) {
        Query query = Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("status").is(MembershipStatus.APPROVED.name()));
        query.fields().include("user");
        return mongo.find(query, Document.class, "clubmemberships").stream().map(row -> row.getObjectId("user").toHexString()).toList();
    }

    private List<Document> choices(Criteria criteria) {
        Query query = Query.query(criteria);
        query.fields().include("user").include("club").include("enabled");
        return mongo.find(query, Document.class, "clubsubscriptions");
    }

    /** Everyone whose bell is on for this club; {@code membersOnly} limits it to members (members-only posts). */
    public List<String> clubFollowerIds(String clubId, boolean membersOnly) {
        List<String> members = approvedMemberIds(clubId);
        List<Document> choices = choices(Criteria.where("club").is(new ObjectId(clubId)));
        Set<String> optedOut = new HashSet<>();
        Set<String> optedIn = new LinkedHashSet<>();
        for (Document choice : choices) {
            (Boolean.TRUE.equals(choice.getBoolean("enabled")) ? optedIn : optedOut).add(choice.getObjectId("user").toHexString());
        }
        Set<String> followers = new LinkedHashSet<>();
        members.stream().filter(id -> !optedOut.contains(id)).forEach(followers::add);
        if (!membersOnly) {
            followers.addAll(optedIn);
        }
        return new ArrayList<>(followers);
    }

    public List<String> clubFollowerIds(String clubId) {
        return clubFollowerIds(clubId, false);
    }

    /** People who explicitly switched this club off; they should not hear from it through other routes either. */
    public List<String> optedOutIds(String clubId) {
        return choices(Criteria.where("club").is(new ObjectId(clubId)).and("enabled").is(false)).stream()
                .map(choice -> choice.getObjectId("user").toHexString())
                .toList();
    }

    /** The ids of the given clubs that this user follows. */
    public Set<String> followedAmong(String userId, Collection<String> clubIds) {
        if (clubIds == null || clubIds.isEmpty()) {
            return new HashSet<>();
        }
        List<ObjectId> clubs = clubIds.stream().filter(ObjectId::isValid).map(ObjectId::new).toList();
        Query memberships = Query.query(Criteria.where("user").is(new ObjectId(userId)).and("club").in(clubs).and("status").is(MembershipStatus.APPROVED.name()));
        memberships.fields().include("club");
        Map<String, Boolean> choice = new HashMap<>();
        Set<String> followed = new HashSet<>();
        for (Document row : choices(Criteria.where("user").is(new ObjectId(userId)).and("club").in(clubs))) {
            String club = row.getObjectId("club").toHexString();
            boolean enabled = Boolean.TRUE.equals(row.getBoolean("enabled"));
            choice.put(club, enabled);
            if (enabled) {
                followed.add(club);
            }
        }
        for (Document membership : mongo.find(memberships, Document.class, "clubmemberships")) {
            String club = membership.getObjectId("club").toHexString();
            if (!Boolean.FALSE.equals(choice.get(club))) {
                followed.add(club);
            }
        }
        return followed;
    }

    public int followerCount(String clubId) {
        return clubFollowerIds(clubId).size();
    }

    public boolean isSubscribed(String userId, String clubId) {
        Document choice = mongo.findOne(Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("user").is(new ObjectId(userId))), Document.class, "clubsubscriptions");
        if (choice != null) {
            return Boolean.TRUE.equals(choice.getBoolean("enabled"));
        }
        return mongo.exists(Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("user").is(new ObjectId(userId)).and("status").is(MembershipStatus.APPROVED.name())), "clubmemberships");
    }

    private Summary summary(AuthUser actor, String clubId) {
        Map<String, Object> user = refs.user(actor.id(), "emailPreferences");
        @SuppressWarnings("unchecked")
        Map<String, Object> preferences = user == null ? null : (Map<String, Object>) user.get("emailPreferences");
        // The bell only sends e-mail while the "Clubs you follow" e-mail setting is on.
        boolean emailsEnabled = preferences == null || !Boolean.FALSE.equals(preferences.get("clubUpdates"));
        return new Summary(isSubscribed(actor.id(), clubId), emailsEnabled, followerCount(clubId));
    }

    private Document loadClub(String clubId) {
        Document club = ObjectId.isValid(clubId) ? mongo.findById(new ObjectId(clubId), Document.class, "clubs") : null;
        if (club == null) {
            throw AppException.notFound("Club not found");
        }
        return club;
    }

    public Summary getSubscription(AuthUser actor, String clubId) {
        loadClub(clubId);
        return summary(actor, clubId);
    }

    public Summary setSubscription(AuthUser actor, String clubId, boolean enabled) {
        Document club = loadClub(clubId);
        // Switching the bell off always works; switching it on needs a club that is running.
        if (enabled && !ClubStatus.ACTIVE.name().equals(club.getString("status"))) {
            throw AppException.conflict("You can only turn on notifications for active clubs", ErrorCode.CLUB_NOT_ACTIVE);
        }
        upsertChoice(actor.id(), clubId, enabled);
        return summary(actor, clubId);
    }

    /** Clubs whose bell is on for this user, for the notification settings page. */
    public List<Map<String, Object>> listMySubscriptions(AuthUser actor) {
        ObjectId user = new ObjectId(actor.id());
        Query memberships = Query.query(Criteria.where("user").is(user).and("status").is(MembershipStatus.APPROVED.name()));
        memberships.fields().include("club");
        Set<String> memberOf = new LinkedHashSet<>();
        mongo.find(memberships, Document.class, "clubmemberships").forEach(row -> memberOf.add(row.getObjectId("club").toHexString()));
        Map<String, Boolean> choiceByClub = new LinkedHashMap<>();
        choices(Criteria.where("user").is(user)).forEach(row -> choiceByClub.put(row.getObjectId("club").toHexString(), Boolean.TRUE.equals(row.getBoolean("enabled"))));

        Set<String> candidates = new LinkedHashSet<>(memberOf);
        candidates.addAll(choiceByClub.keySet());
        List<String> clubIds = candidates.stream().filter(id -> choiceByClub.getOrDefault(id, memberOf.contains(id))).toList();

        return refs.clubs(clubIds, "name", "logo", "category", "status").values().stream()
                .sorted(Comparator.comparing(club -> String.valueOf(club.get("name"))))
                .map(club -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("club", club);
                    row.put("isMember", memberOf.contains((String) club.get("_id")));
                    return row;
                })
                .toList();
    }

    /** One-click unsubscribe links: switches the bell off without needing the club to be active. */
    public Map<String, Object> turnOffForUser(String userId, String clubId) {
        Map<String, Object> club = ObjectId.isValid(clubId) ? refs.club(clubId, "name") : null;
        if (club == null) {
            return null;
        }
        upsertChoice(userId, clubId, false);
        return club;
    }

    private void upsertChoice(String userId, String clubId, boolean enabled) {
        Instant now = Instant.now();
        mongo.upsert(
                Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("user").is(new ObjectId(userId))),
                new Update().set("enabled", enabled).set("updatedAt", now).setOnInsert("createdAt", now),
                ClubSubscription.class);
    }
}
