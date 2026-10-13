package com.campusconnect.feature.like;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.domain.social.Like;
import com.campusconnect.domain.social.LikeTargetType;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Likes on event posts and gallery photos/videos, like Instagram (services/LikeService.js). Each item keeps a
 * {@code likeCount}; who liked is visible to the people the item belongs to. Stories have their own likes.
 */
@Service
public class LikeService {

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final Refs refs;

    public LikeService(MongoOperations mongo, ClubAccess access, Refs refs) {
        this.mongo = mongo;
        this.access = access;
        this.refs = refs;
    }

    private static String collection(LikeTargetType type) {
        return type == LikeTargetType.EVENT ? "events" : "eventmedias";
    }

    public static LikeTargetType typeOf(String type) {
        try {
            return LikeTargetType.valueOf(String.valueOf(type).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("Unknown item", ErrorCode.VALIDATION_ERROR);
        }
    }

    /** The item if it is visible (a published/completed event, an approved gallery item). */
    private Document loadVisible(LikeTargetType type, String id) {
        Document item = ObjectId.isValid(id) ? mongo.findById(new ObjectId(id), Document.class, collection(type)) : null;
        boolean visible = item != null && (type == LikeTargetType.EVENT
                ? EventStatus.PUBLIC.stream().anyMatch(status -> status.name().equals(item.getString("status")))
                : GalleryStatus.APPROVED.name().equals(item.getString("status")));
        if (!visible) {
            throw AppException.notFound("Post not found");
        }
        return item;
    }

    /** Likes or unlikes. The count changes only when the like really changes, so double taps never double-count. */
    public Map<String, Object> setLiked(AuthUser actor, String type, String id, boolean liked) {
        LikeTargetType kind = typeOf(type);
        Document item = loadVisible(kind, id);
        ObjectId target = item.getObjectId("_id");
        ObjectId user = new ObjectId(actor.id());
        Query own = Query.query(Criteria.where("targetType").is(kind.name()).and("target").is(target).and("user").is(user));
        if (liked) {
            Instant now = Instant.now();
            var result = mongo.upsert(own, new Update()
                    .setOnInsert("targetType", kind.name()).setOnInsert("target", target).setOnInsert("user", user)
                    .setOnInsert("createdAt", now).setOnInsert("updatedAt", now), "likes");
            if (result.getUpsertedId() != null) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(target)), new Update().inc("likeCount", 1), collection(kind));
            }
        } else {
            var result = mongo.remove(own, "likes");
            if (result.getDeletedCount() > 0) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(target).and("likeCount").gt(0)), new Update().inc("likeCount", -1), collection(kind));
            }
        }
        Document fresh = mongo.findById(target, Document.class, collection(kind));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("liked", liked);
        state.put("likeCount", fresh == null || fresh.get("likeCount") == null ? 0 : ((Number) fresh.get("likeCount")).intValue());
        return state;
    }

    private boolean canSeeLikers(AuthUser actor, LikeTargetType type, Document item) {
        String club = item.getObjectId("club") == null ? null : item.getObjectId("club").toHexString();
        if (club == null) {
            return false;
        }
        ClubContext context = access.context(actor, club);
        if (type == LikeTargetType.EVENT) {
            // The club's event organisers and the mentor.
            return context.isMentor() || context.has(ClubPermission.MANAGE_EVENTS);
        }
        // The person who posted it and the gallery moderators.
        ObjectId uploader = item.getObjectId("uploader");
        return (uploader != null && uploader.toHexString().equals(actor.id())) || context.has(ClubPermission.MODERATE_GALLERY);
    }

    /** Who liked it, newest first (at most 200) — for the people the item belongs to. */
    public Map<String, Object> listLikers(AuthUser actor, String type, String id) {
        LikeTargetType kind = typeOf(type);
        Document item = loadVisible(kind, id);
        if (!canSeeLikers(actor, kind, item)) {
            throw AppException.forbidden("Only the organisers can see who liked this");
        }
        List<Like> likes = mongo.find(Query.query(Criteria.where("targetType").is(kind.name()).and("target").is(item.getObjectId("_id")))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(200), Like.class);
        Map<String, Map<String, Object>> users = refs.users(likes.stream().map(Like::getUser).toList(), "name", "avatar", "departmentCode", "batchCode", "accountType");
        List<Map<String, Object>> items = likes.stream()
                .filter(like -> users.containsKey(like.getUser()))
                .map(like -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("user", users.get(like.getUser()));
                    row.put("at", like.getCreatedAt());
                    return row;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("likeCount", item.get("likeCount") == null ? 0 : ((Number) item.get("likeCount")).intValue());
        result.put("items", items);
        return result;
    }

    /** Which of these items the user has liked (ids). */
    public Set<String> likedAmong(AuthUser user, LikeTargetType type, Collection<String> ids) {
        if (user == null || ids == null || ids.isEmpty()) {
            return new HashSet<>();
        }
        Query query = Query.query(Criteria.where("targetType").is(type.name()).and("user").is(new ObjectId(user.id()))
                .and("target").in(ids.stream().filter(ObjectId::isValid).map(ObjectId::new).toList()));
        query.fields().include("target");
        Set<String> liked = new HashSet<>();
        mongo.find(query, Document.class, "likes").forEach(row -> liked.add(row.getObjectId("target").toHexString()));
        return liked;
    }
}
