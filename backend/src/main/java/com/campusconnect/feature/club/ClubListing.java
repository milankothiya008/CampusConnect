package com.campusconnect.feature.club;

import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.MembershipStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Pages of the clubs directory (ClubService.listClubs/withCounts): each club with its president and mentor (no
 * phone numbers), member count and open recruitment drive — related data in one batch query per kind.
 */
@Component
public class ClubListing {

    static final List<String> PRESIDENT_FIELDS = List.of("name", "email", "departmentCode", "batchCode", "avatar");
    static final List<String> MENTOR_FIELDS = List.of("name", "email", "departmentCode", "avatar");

    public record Loaded(List<Map<String, Object>> items, long total) {
    }

    private final MongoOperations mongo;
    private final Refs refs;
    private final DocumentViews views;
    private final OpenDrives openDrives;

    public ClubListing(MongoOperations mongo, Refs refs, DocumentViews views, OpenDrives openDrives) {
        this.mongo = mongo;
        this.refs = refs;
        this.views = views;
        this.openDrives = openDrives;
    }

    /** Directory filters; a null status matches every status (the admin's "ALL"). */
    public static Criteria filter(String status, String search, String category, String department) {
        Criteria criteria = new Criteria();
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (search != null && !search.isEmpty()) {
            criteria = criteria.and("name").regex(Texts.searchPattern(search), "i");
        }
        if (category != null && !category.isEmpty()) {
            criteria = criteria.and("category").is(category.toUpperCase(Locale.ROOT));
        }
        if (department != null && !department.isEmpty()) {
            criteria = criteria.orOperator(Criteria.where("allDepartments").is(true), Criteria.where("departmentCodes").is(department.toUpperCase(Locale.ROOT)));
        }
        return criteria;
    }

    public Loaded load(Criteria filter, Pagination pagination) {
        Query page = Query.query(filter).with(Sort.by("name")).skip(pagination.skip()).limit(pagination.limit());
        List<Club> clubs = mongo.find(page, Club.class);
        long total = mongo.count(Query.query(filter), Club.class);
        return new Loaded(withCounts(clubs), total);
    }

    /** Clubs as the directory lists them. */
    public List<Map<String, Object>> withCounts(List<Club> clubs) {
        List<String> ids = clubs.stream().map(Club::getId).toList();
        Set<String> people = new LinkedHashSet<>();
        clubs.forEach(club -> {
            people.add(club.getPresident());
            people.add(club.getMentor());
        });
        people.remove(null);
        Map<String, Map<String, Object>> users = refs.users(people, "name", "email", "departmentCode", "batchCode", "avatar");
        Map<String, Long> counts = memberCounts(ids);
        Map<String, Map<String, Object>> open = openDrives.byClub(ids);
        return clubs.stream().map(club -> {
            Map<String, Object> view = views.of(club);
            view.put("president", DocumentViews.pick(club.getPresident() == null ? null : users.get(club.getPresident()), PRESIDENT_FIELDS));
            view.put("mentor", DocumentViews.pick(club.getMentor() == null ? null : users.get(club.getMentor()), MENTOR_FIELDS));
            view.put("memberCount", counts.getOrDefault(club.getId(), 0L));
            view.put("recruiting", open.get(club.getId()));
            return view;
        }).toList();
    }

    /** Approved members per club, in one aggregation. */
    public Map<String, Long> memberCounts(Collection<String> clubIds) {
        List<ObjectId> ids = clubIds.stream().filter(Objects::nonNull).filter(ObjectId::isValid).distinct().map(ObjectId::new).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("club").in(ids).and("status").is(MembershipStatus.APPROVED.name())),
                Aggregation.group("club").count().as("count"));
        Map<String, Long> counts = new HashMap<>();
        for (Document row : mongo.aggregate(aggregation, "clubmemberships", Document.class)) {
            counts.put(row.getObjectId("_id").toHexString(), ((Number) row.get("count")).longValue());
        }
        return counts;
    }
}
