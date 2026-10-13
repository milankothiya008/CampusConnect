package com.campusconnect.feature.club;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.PageMeta;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.feature.event.EventJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * A club's events tab (EventService.listClubEvents): the public see published and completed events; the club's event
 * staff and mentor see every status. Each event exactly as the events feed shows it ({@link EventJson}: populated
 * club, venue and people, registration window, published results, the viewer's own registration, follow and like).
 */
@Service
public class ClubEventListing {

    public record EventPage(List<Map<String, Object>> items, Map<String, Object> meta) {
    }

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final EventJson eventJson;

    public ClubEventListing(MongoOperations mongo, ClubAccess access, EventJson eventJson) {
        this.mongo = mongo;
        this.access = access;
        this.eventJson = eventJson;
    }

    public EventPage listClubEvents(AuthUser actor, String clubId, String status, String page, String limit) {
        ClubContext context = access.context(actor, clubId);
        boolean isStaff = context.isMentor() || context.has(ClubPermission.MANAGE_EVENTS) || context.has(ClubPermission.PUBLISH_EVENTS);
        Pagination pagination = Pagination.of(page, limit, 20);
        Criteria filter = Criteria.where("club").is(context.club().getId());
        if (isStaff && status != null && !status.isEmpty()) {
            filter = filter.and("status").is(status.toUpperCase(Locale.ROOT));
        } else if (!isStaff) {
            filter = filter.and("status").in(EventStatus.PUBLIC.stream().map(Enum::name).toList());
        }
        List<Event> events = mongo.find(Query.query(filter).with(Sort.by(Sort.Direction.DESC, "startAt")).skip(pagination.skip()).limit(pagination.limit()), Event.class);
        long total = mongo.count(Query.query(filter), Event.class);

        // EventService.attachRegistrations: serialize, then results and the viewer's own state.
        List<Map<String, Object>> items = eventJson.serializeAll(events);
        eventJson.attachResults(events, items);
        eventJson.attachViewer(actor, items);

        PageMeta pageMeta = pagination.meta(total);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("isStaff", isStaff);
        meta.put("total", pageMeta.total());
        meta.put("page", pageMeta.page());
        meta.put("limit", pageMeta.limit());
        meta.put("totalPages", pageMeta.totalPages());
        return new EventPage(items, meta);
    }
}
