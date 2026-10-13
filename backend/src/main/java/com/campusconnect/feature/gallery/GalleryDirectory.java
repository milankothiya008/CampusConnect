package com.campusconnect.feature.gallery;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.PageMeta;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.feature.gallery.GalleryDtos.CardEvent;
import com.campusconnect.feature.gallery.GalleryDtos.Galleries;
import com.campusconnect.feature.gallery.GalleryDtos.GalleriesMeta;
import com.campusconnect.feature.gallery.GalleryDtos.GalleriesQuery;
import com.campusconnect.feature.gallery.GalleryDtos.GalleryCard;
import com.campusconnect.feature.gallery.GalleryDtos.SectionCounts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The Gallery section (GalleryService.listGalleries): published and completed events, those with the latest photos
 * first, each with its photo and video counts and a cover (the newest approved photo). {@code show}: all, photos
 * (events that have some) or review (events with uploads waiting for this president / vice-president).
 */
@Service
public class GalleryDirectory {

    private static final String[] EVENT_FIELDS = {"title", "poster", "category", "startAt", "endAt", "status", "club"};
    private static final int PREVIEWS = 4;

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final Refs refs;
    private final GalleryActivity activity;
    private final MediaStore media;

    public GalleryDirectory(MongoOperations mongo, ClubAccess access, Refs refs, GalleryActivity activity, MediaStores mediaStores) {
        this.mongo = mongo;
        this.access = access;
        this.refs = refs;
        this.activity = activity;
        this.media = mediaStores.gallery();
    }

    private static Criteria and(List<Criteria> base, Criteria extra) {
        List<Criteria> all = new ArrayList<>(base);
        all.add(extra);
        return new Criteria().andOperator(all);
    }

    private List<Document> events(Criteria filter, Sort sort, long skip, int limit) {
        Query query = Query.query(filter);
        if (sort != null) {
            query.with(sort).skip(skip).limit(limit);
        }
        for (String field : EVENT_FIELDS) {
            query.fields().include(field);
        }
        return mongo.find(query, Document.class, "events");
    }

    private List<Document> aggregate(List<Document> pipeline) {
        return mongo.getCollection("eventmedias").aggregate(pipeline).into(new ArrayList<>());
    }

    private static Document sumWhen(Document condition) {
        return new Document("$sum", new Document("$cond", Arrays.asList(condition, 1, 0)));
    }

    private static Document eq(String field, Object value) {
        return new Document("$eq", Arrays.asList("$" + field, value));
    }

    public Galleries listGalleries(AuthUser actor, GalleriesQuery query) {
        Pagination pagination = Pagination.of(query.page(), query.limit(), 12, 30);
        List<Criteria> base = new ArrayList<>();
        base.add(Criteria.where("status").in(EventStatus.PUBLIC.stream().map(Enum::name).toList()));
        if (query.search() != null && !query.search().isBlank()) {
            base.add(Texts.search("title", query.search()));
        }
        if (query.club() != null && !query.club().isEmpty()) {
            base.add(Criteria.where("club").is(new ObjectId(query.club())));
        }
        Criteria baseFilter = new Criteria().andOperator(base);

        // Clubs where the viewer approves gallery uploads (president / vice-president).
        List<String> moderated = actor == null ? List.of() : access.clubIdsWithAnyPermission(actor.id(), List.of(ClubPermission.MODERATE_GALLERY));
        // Events with photos come first, most recently updated first; then the rest, newest event first.
        List<ObjectId> withPhotos = activity.eventsWithPhotos();
        long allCount = mongo.count(Query.query(baseFilter), "events");

        List<ObjectId> toReview = moderated.isEmpty() ? List.of()
                : mongo.findDistinct(Query.query(Criteria.where("status").is(GalleryStatus.PENDING.name()).and("club").in(moderated.stream().map(ObjectId::new).toList())),
                        "event", "eventmedias", ObjectId.class);
        Query matchingQuery = Query.query(and(base, Criteria.where("_id").in(withPhotos)));
        matchingQuery.fields().include("_id");
        Set<ObjectId> matching = new HashSet<>();
        mongo.find(matchingQuery, Document.class, "events").forEach(event -> matching.add(event.getObjectId("_id")));
        List<ObjectId> withPhotosOrdered = withPhotos.stream().filter(matching::contains).toList();

        Sort newestFirst = Sort.by(Sort.Direction.DESC, "startAt").and(Sort.by(Sort.Direction.DESC, "_id"));
        List<Document> events;
        long total;
        if ("review".equals(query.show())) {
            Criteria filter = and(base, Criteria.where("_id").in(toReview));
            events = events(filter, newestFirst, pagination.skip(), pagination.limit());
            total = mongo.count(Query.query(filter), "events");
        } else {
            // The page is the events with photos first, then the rest.
            int from = Math.min(pagination.skip(), withPhotosOrdered.size());
            List<ObjectId> firstIds = withPhotosOrdered.subList(from, Math.min(withPhotosOrdered.size(), pagination.skip() + pagination.limit()));
            Criteria others = and(base, Criteria.where("_id").nin(withPhotosOrdered));
            int room = pagination.limit() - firstIds.size();
            boolean withOthers = !"photos".equals(query.show());

            Map<ObjectId, Document> firstById = new HashMap<>();
            if (!firstIds.isEmpty()) {
                events(Criteria.where("_id").in(firstIds), null, 0, 0).forEach(event -> firstById.put(event.getObjectId("_id"), event));
            }
            long othersCount = withOthers ? mongo.count(Query.query(others), "events") : 0;
            events = new ArrayList<>(firstIds.stream().map(firstById::get).filter(Objects::nonNull).toList());
            if (withOthers && room > 0) {
                events.addAll(events(others, newestFirst, Math.max(0, pagination.skip() - withPhotosOrdered.size()), room));
            }
            total = withPhotosOrdered.size() + othersCount;
        }

        List<ObjectId> ids = events.stream().map(event -> event.getObjectId("_id")).toList();
        long reviewCount = toReview.isEmpty() ? 0 : mongo.count(Query.query(and(base, Criteria.where("_id").in(toReview))), "events");
        Map<ObjectId, Document> stats = new HashMap<>();
        Map<ObjectId, List<Document>> covers = new HashMap<>();
        if (!ids.isEmpty()) {
            aggregate(List.of(
                    new Document("$match", new Document("event", new Document("$in", ids))),
                    new Document("$group", new Document("_id", "$event")
                            .append("photos", sumWhen(new Document("$and", List.of(eq("status", GalleryStatus.APPROVED.name()), eq("media.kind", MediaKind.IMAGE.name())))))
                            .append("videos", sumWhen(new Document("$and", List.of(eq("status", GalleryStatus.APPROVED.name()), eq("media.kind", MediaKind.VIDEO.name())))))
                            .append("pending", sumWhen(eq("status", GalleryStatus.PENDING.name())))
                            .append("latestAt", new Document("$max", new Document("$cond", Arrays.asList(eq("status", GalleryStatus.APPROVED.name()), "$createdAt", null)))))))
                    .forEach(row -> stats.put(row.getObjectId("_id"), row));
            // Photos before videos, newest first: the first one is the cover, the first four the previews.
            aggregate(List.of(
                    new Document("$match", new Document("event", new Document("$in", ids)).append("status", GalleryStatus.APPROVED.name())),
                    new Document("$sort", new Document("media.kind", 1).append("createdAt", -1)),
                    new Document("$group", new Document("_id", "$event").append("thumbs", new Document("$firstN", new Document("input", "$media").append("n", PREVIEWS))))))
                    .forEach(row -> covers.put(row.getObjectId("_id"), row.getList("thumbs", Document.class)));
        }
        Map<String, Map<String, Object>> clubs = refs.clubs(events.stream().map(event -> event.getObjectId("club")).filter(Objects::nonNull)
                .map(ObjectId::toHexString).distinct().toList(), "name", "logo");
        Set<String> moderatedSet = new HashSet<>(moderated);

        List<GalleryCard> items = events.stream().map(event -> {
            ObjectId id = event.getObjectId("_id");
            String clubId = event.getObjectId("club") == null ? null : event.getObjectId("club").toHexString();
            Map<String, Object> club = clubId == null ? null : clubs.get(clubId);
            Document row = stats.get(id);
            List<Document> thumbs = covers.get(id);
            String poster = event.getString("poster");
            CardEvent card = new CardEvent(id.toHexString(), event.getString("title"), poster == null || poster.isEmpty() ? null : poster, event.getString("category"),
                    instant(event.get("startAt")), instant(event.get("endAt")), event.getString("status"), club);
            return new GalleryCard(card,
                    number(row, "photos"),
                    number(row, "videos"),
                    row == null ? null : instant(row.get("latestAt")),
                    club != null && moderatedSet.contains(clubId) ? number(row, "pending") : 0,
                    thumbs == null || thumbs.isEmpty() ? null : thumbOf(thumbs.get(0)),
                    thumbs == null ? List.of() : thumbs.stream().map(this::thumbOf).filter(Objects::nonNull).toList());
        }).toList();

        PageMeta meta = pagination.meta(total);
        return new Galleries(items, new GalleriesMeta(meta.total(), meta.page(), meta.limit(), meta.totalPages(),
                new SectionCounts(allCount, withPhotosOrdered.size(), reviewCount), !moderated.isEmpty()));
    }

    private String thumbOf(Document stored) {
        MediaKind kind;
        try {
            kind = MediaKind.valueOf(String.valueOf(stored.get("kind")));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return media.mediaUrls(kind, stored.getString("provider"), stored.getString("key"), (Number) stored.get("version")).thumb();
    }

    private static long number(Document row, String field) {
        return row != null && row.get(field) instanceof Number value ? value.longValue() : 0;
    }

    private static Instant instant(Object value) {
        return value instanceof Date date ? date.toInstant() : null;
    }
}
