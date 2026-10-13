package com.campusconnect.feature.club;

import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Each club's recruitment drive that is taking — or about to take — applications (RecruitmentService.openDrivesByClub),
 * for the "Recruiting" badges and the club page. Paused clubs recruit nothing. One query for any number of clubs.
 */
@Component
public class OpenDrives {

    private final MongoOperations mongo;
    private final PausedClubs pausedClubs;

    public OpenDrives(MongoOperations mongo, PausedClubs pausedClubs) {
        this.mongo = mongo;
        this.pausedClubs = pausedClubs;
    }

    /** Club id to {@code { _id, title, applicationStart, applicationEnd, open }}. */
    public Map<String, Map<String, Object>> byClub(Collection<String> clubIds) {
        Set<String> paused = pausedClubs.ids();
        List<ObjectId> ids = clubIds.stream().filter(ObjectId::isValid).filter(id -> !paused.contains(id)).distinct().map(ObjectId::new).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Instant now = Instant.now();
        Query query = Query.query(Criteria.where("club").in(ids)
                .and("status").is(RecruitmentStatus.PUBLISHED.name())
                .and("closedAt").is(null)
                .and("applicationEnd").gt(now));
        query.fields().include("club", "title", "applicationStart", "applicationEnd");
        Map<String, Map<String, Object>> byClub = new HashMap<>();
        for (Document drive : mongo.find(query, Document.class, "recruitmentdrives")) {
            Instant start = instant(drive.get("applicationStart"));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("_id", drive.getObjectId("_id").toHexString());
            view.put("title", drive.getString("title"));
            view.put("applicationStart", start);
            view.put("applicationEnd", instant(drive.get("applicationEnd")));
            view.put("open", start == null || !start.isAfter(now));
            byClub.put(drive.getObjectId("club").toHexString(), view);
        }
        return byClub;
    }

    /** A stored date, or an ISO string read back from the shared cache. */
    static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return value instanceof String text ? Instant.parse(text) : null;
    }
}
