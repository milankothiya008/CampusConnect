package com.campusconnect.feature.registration;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.feature.team.Teams;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * A student can't be at two events at once (services/ScheduleClashService.js). Registering (or joining a team, or a
 * waitlist) for an event that overlaps one they already hold a place for is refused — unless they choose to switch:
 * the other place is cancelled (if that event hasn't started), exactly as if they had cancelled it themselves, so its
 * seat goes to the next student waiting, and then the new registration goes ahead.
 */
@Service
public class ScheduleClashService {

    private final MongoOperations mongo;
    private final RegistrationCancellation cancellation;
    private final Refs refs;

    public ScheduleClashService(MongoOperations mongo, RegistrationCancellation cancellation, Refs refs) {
        this.mongo = mongo;
        this.cancellation = cancellation;
        this.refs = refs;
    }

    /** The student's other registrations and waitlist places whose times overlap this event. */
    public List<Map<String, Object>> clashesFor(String userId, Event event) {
        Query heldQuery = Query.query(Criteria.where("user").is(new ObjectId(userId)).and("status").in(Teams.ACTIVE).and("event").ne(new ObjectId(event.getId())));
        heldQuery.fields().include("event", "status", "teamRole");
        List<EventRegistration> held = mongo.find(heldQuery, EventRegistration.class);
        if (held.isEmpty()) {
            return List.of();
        }
        Map<String, EventRegistration> byEvent = new HashMap<>();
        held.forEach(row -> byEvent.putIfAbsent(row.getEvent(), row));
        Query overlapping = Query.query(Criteria.where("_id").in(byEvent.keySet().stream().map(ObjectId::new).toList())
                .and("status").is(EventStatus.PUBLISHED.name()).and("startAt").lt(event.getEndAt()).and("endAt").gt(event.getStartAt()))
                .with(Sort.by("startAt"));
        overlapping.fields().include("title", "startAt", "endAt", "club", "participationMode");
        List<Event> others = mongo.find(overlapping, Event.class);
        Map<String, Map<String, Object>> clubs = refs.clubs(others.stream().map(Event::getClub).toList(), "name");
        Instant now = Instant.now();
        return others.stream().map(other -> {
            EventRegistration registration = byEvent.get(other.getId());
            Map<String, Object> eventView = new LinkedHashMap<>();
            eventView.put("_id", other.getId());
            eventView.put("title", other.getTitle());
            eventView.put("startAt", other.getStartAt());
            eventView.put("endAt", other.getEndAt());
            eventView.put("club", clubs.get(other.getClub()));
            Map<String, Object> clash = new LinkedHashMap<>();
            clash.put("event", eventView);
            clash.put("status", registration.getStatus());
            clash.put("teamRole", registration.getTeamRole());
            // A place can be given up only before that event starts.
            clash.put("canSwitch", other.getStartAt().isAfter(now));
            return clash;
        }).toList();
    }

    private static String describe(Map<String, Object> clash) {
        Map<?, ?> event = (Map<?, ?>) clash.get("event");
        return (clash.get("status") == RegistrationStatus.WAITLISTED ? "on the waitlist for" : "registered for") + " \"" + event.get("title") + "\"";
    }

    private static String eventId(Map<String, Object> clash) {
        return String.valueOf(((Map<?, ?>) clash.get("event")).get("_id"));
    }

    /**
     * Throws unless every overlapping place is one the student agreed to give up ({@code replace}, event ids) and can
     * still be given up. The error carries the full list, so the app can ask "switch?". Returns the overlaps.
     */
    public List<Map<String, Object>> assertResolvable(AuthUser actor, Event event, Collection<String> replace) {
        List<Map<String, Object>> clashes = clashesFor(actor.id(), event);
        if (clashes.isEmpty()) {
            return clashes;
        }
        Set<String> agreed = replace == null ? Set.of() : Set.copyOf(replace);
        boolean unresolved = clashes.stream().anyMatch(clash -> !agreed.contains(eventId(clash)));
        if (unresolved) {
            throw AppException.conflict(clashes.size() == 1
                            ? "This event is at the same time as one you're " + describe(clashes.get(0)) + "."
                            : "This event overlaps " + clashes.size() + " events you're registered for.", ErrorCode.SCHEDULE_CONFLICT)
                    .withDetails(Map.of("clashes", clashes));
        }
        clashes.stream().filter(clash -> !Boolean.TRUE.equals(clash.get("canSwitch"))).findFirst().ifPresent(started -> {
            throw AppException.conflict("\"" + ((Map<?, ?>) started.get("event")).get("title") + "\" has already started, so you can't switch from it.",
                    ErrorCode.SCHEDULE_CONFLICT).withDetails(Map.of("clashes", clashes));
        });
        return clashes;
    }

    /**
     * Makes sure nothing overlaps, or switches: the places the student agreed to give up are cancelled. Call after
     * every other check has passed, right before taking the new place. Returns the events switched away from.
     */
    public List<Map<String, Object>> resolveClashes(AuthUser actor, Event event, Collection<String> replace) {
        List<Map<String, Object>> clashes = assertResolvable(actor, event, replace);
        for (Map<String, Object> clash : clashes) {
            cancellation.cancelRegistration(actor, eventId(clash));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> switchedFrom = clashes.stream().map(clash -> (Map<String, Object>) clash.get("event")).toList();
        return switchedFrom;
    }
}
