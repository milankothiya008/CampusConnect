package com.campusconnect.feature.event;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultRound;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.domain.social.LikeTargetType;
import com.campusconnect.feature.like.LikeService;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events as the API shows them (EventService serialize / populateEvent / attachResults / attachViewer): every stored
 * field, with the club, venue and people populated — one query per collection for a whole page — and without the
 * revision, the reminder log and the budget, which only the organisers see on the detail page.
 */
@Component
public class EventJson {

    static final List<String> HIDDEN = List.of("revision", "remindersSent", "budgetItems", "equipment", "budgetNote", "expenses");

    private final MongoOperations mongo;
    private final Refs refs;
    private final JsonMapper json;
    private final SubscriptionService subscriptions;
    private final LikeService likes;

    public EventJson(MongoOperations mongo, Refs refs, JsonMapper json, SubscriptionService subscriptions, LikeService likes) {
        this.mongo = mongo;
        this.refs = refs;
        this.json = json;
        this.subscriptions = subscriptions;
        this.likes = likes;
    }

    /** The populated references of a page of events. */
    public record Populated(Map<String, Map<String, Object>> clubs, Map<String, Map<String, Object>> venues, Map<String, Map<String, Object>> users) {
    }

    /** populate("club", "name logo category status mentor president"), venue, createdBy, organizer, reviewedBy. */
    public Populated populate(Collection<Event> events) {
        Map<String, Map<String, Object>> clubs = refs.clubs(events.stream().map(Event::getClub).toList(), "name", "logo", "category", "status", "mentor", "president");
        return populate(events, clubs);
    }

    /** As {@link #populate(Collection)}, when the club is already loaded. */
    public Populated populate(Event event, Club club) {
        Map<String, Map<String, Object>> clubs = new HashMap<>();
        if (club != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("_id", club.getId());
            view.put("name", club.getName());
            view.put("logo", club.getLogo());
            view.put("category", club.getCategory());
            view.put("status", club.getStatus() == null ? null : club.getStatus().name());
            view.put("mentor", club.getMentor());
            view.put("president", club.getPresident());
            clubs.put(club.getId(), view);
        }
        return populate(List.of(event), clubs);
    }

    private Populated populate(Collection<Event> events, Map<String, Map<String, Object>> clubs) {
        Map<String, Map<String, Object>> venues = refs.load("venues", events.stream().map(Event::getVenue).toList(), "name", "location", "capacity");
        List<String> people = events.stream().flatMap(event -> Stream.of(event.getCreatedBy(), event.getOrganizer(), event.getReviewedBy())).filter(Objects::nonNull).toList();
        Map<String, Map<String, Object>> users = refs.users(people, "name", "email");
        return new Populated(clubs, venues, users);
    }

    /** The event's stored fields as JSON-ready values (dates become ISO strings). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fields(Event event) {
        return json.convertValue(event, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    public <T> T plain(Object value, Class<T> type) {
        return json.convertValue(value, type);
    }

    /**
     * The live value of an editable field, in the form an edit proposes it: ids as strings, dates as instants and
     * embedded objects as plain maps — so it can be compared with a proposal and shown next to it.
     */
    public Object currentValue(Event event, String field) {
        return switch (field) {
            case "title" -> event.getTitle();
            case "shortDescription" -> event.getShortDescription();
            case "description" -> event.getDescription();
            case "category" -> event.getCategory();
            case "poster" -> event.getPoster();
            case "rules" -> event.getRules();
            case "contact" -> plain(event.getContact(), LinkedHashMap.class);
            case "maxParticipants" -> event.getMaxParticipants();
            case "eligibility" -> plain(event.getEligibility(), LinkedHashMap.class);
            case "venue" -> event.getVenue();
            case "eventDate" -> event.getEventDate();
            case "endDate" -> event.getEndDate();
            case "startTime" -> event.getStartTime();
            case "endTime" -> event.getEndTime();
            case "startAt" -> event.getStartAt();
            case "endAt" -> event.getEndAt();
            case "registrationStart" -> event.getRegistrationStart();
            case "registrationEnd" -> event.getRegistrationEnd();
            case "organizer" -> event.getOrganizer();
            case "participationMode" -> event.getParticipationMode() == null ? null : event.getParticipationMode().name();
            case "minTeamSize" -> event.getMinTeamSize();
            case "maxTeamSize" -> event.getMaxTeamSize();
            case "budgetItems" -> plain(event.getBudgetItems(), ArrayList.class);
            case "equipment" -> plain(event.getEquipment(), ArrayList.class);
            case "budgetNote" -> event.getBudgetNote();
            default -> null;
        };
    }

    private static Map<String, Object> person(Map<String, Map<String, Object>> users, String id, boolean withEmail) {
        Map<String, Object> user = id == null ? null : users.get(id);
        if (user == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", user.get("_id"));
        view.put("name", user.get("name"));
        if (withEmail) {
            view.put("email", user.get("email"));
        }
        return view;
    }

    /** serialize(event): the public shape of an event, with its registration state. */
    public Map<String, Object> serialize(Event event, Populated populated) {
        Map<String, Object> view = fields(event);
        HIDDEN.forEach(view::remove);
        view.put("club", populated.clubs().get(event.getClub()));
        view.put("venue", populated.venues().get(event.getVenue()));
        view.put("createdBy", person(populated.users(), event.getCreatedBy(), false));
        view.put("organizer", person(populated.users(), event.getOrganizer(), true));
        view.put("reviewedBy", person(populated.users(), event.getReviewedBy(), false));
        view.put("registrationState", EventRules.registrationWindowState(event));
        return view;
    }

    public List<Map<String, Object>> serializeAll(List<Event> events) {
        Populated populated = populate(events);
        return events.stream().map(event -> serialize(event, populated)).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Events carry what has been published of their results so feed posts can show them inline: {@code result} holds
     * the final results (winners), {@code resultStage} is "final", "rounds" (only round results so far) or null.
     */
    public void attachResults(List<Event> events, List<Map<String, Object>> items) {
        List<ObjectId> ids = events.stream().filter(event -> EventStatus.PUBLIC.contains(event.getStatus())).map(event -> new ObjectId(event.getId())).toList();
        Map<String, EventResult> byEvent = new HashMap<>();
        Map<String, Map<String, Object>> recipients = Map.of();
        if (!ids.isEmpty()) {
            Query query = Query.query(Criteria.where("event").in(ids)
                    .orOperator(Criteria.where("status").is(ResultStatus.PUBLISHED.name()), Criteria.where("rounds.status").is(ResultStatus.PUBLISHED.name())));
            query.fields().include("event", "summary", "awards", "publishedAt", "status", "rounds.name", "rounds.status", "rounds.publishedAt");
            List<EventResult> results = mongo.find(query, EventResult.class);
            results.forEach(result -> byEvent.put(result.getEvent(), result));
            recipients = refs.users(results.stream().flatMap(result -> result.getAwards().stream()).map(ResultAward::getRecipientUser).filter(Objects::nonNull).toList(), "name");
        }
        for (Map<String, Object> item : items) {
            EventResult found = byEvent.get(String.valueOf(item.get("_id")));
            if (found == null) {
                item.put("result", null);
                item.put("resultStage", null);
                item.put("latestRound", null);
                continue;
            }
            boolean isFinal = found.getStatus() == ResultStatus.PUBLISHED;
            ResultRound latest = found.getRounds().stream().filter(round -> round.getStatus() == ResultStatus.PUBLISHED)
                    .max(Comparator.comparing(ResultRound::getPublishedAt, Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
            if (isFinal) {
                Map<String, Map<String, Object>> names = recipients;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("_id", found.getId());
                result.put("summary", found.getSummary());
                result.put("awards", found.getAwards().stream().map(award -> awardView(award, names)).toList());
                result.put("publishedAt", found.getPublishedAt());
                item.put("result", result);
            } else {
                item.put("result", null);
            }
            item.put("resultStage", isFinal ? "final" : "rounds");
            if (latest == null) {
                item.put("latestRound", null);
            } else {
                Map<String, Object> round = new LinkedHashMap<>();
                round.put("name", latest.getName());
                round.put("publishedAt", latest.getPublishedAt());
                item.put("latestRound", round);
            }
        }
    }

    private static Map<String, Object> awardView(ResultAward award, Map<String, Map<String, Object>> recipients) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", award.getId());
        view.put("title", award.getTitle());
        view.put("position", award.getPosition());
        view.put("recipientName", award.getRecipientName());
        view.put("recipientUser", award.getRecipientUser() == null ? null : recipients.get(award.getRecipientUser()));
        view.put("teamName", award.getTeamName());
        view.put("prize", award.getPrize());
        view.put("recognition", award.getRecognition());
        return view;
    }

    /**
     * The viewer's own registration, follow and like on each event. {@code followingClub} lets the feed offer
     * "Follow" on posts from clubs the viewer doesn't follow yet. Nothing is added for anonymous viewers.
     */
    public void attachViewer(AuthUser actor, List<Map<String, Object>> items) {
        if (actor == null || items.isEmpty()) {
            return;
        }
        List<String> eventIds = items.stream().map(item -> String.valueOf(item.get("_id"))).toList();
        List<String> clubIds = items.stream().map(EventJson::clubId).filter(Objects::nonNull).distinct().toList();
        Query query = Query.query(Criteria.where("user").is(new ObjectId(actor.id())).and("event").in(eventIds.stream().map(ObjectId::new).toList()));
        query.fields().include("event", "status");
        Map<String, String> statusByEvent = mongo.find(query, EventRegistration.class).stream()
                .collect(Collectors.toMap(EventRegistration::getEvent, row -> row.getStatus().name(), (first, second) -> first));
        Set<String> followed = subscriptions.followedAmong(actor.id(), clubIds);
        Set<String> liked = likes.likedAmong(actor, LikeTargetType.EVENT, eventIds);
        for (Map<String, Object> item : items) {
            String id = String.valueOf(item.get("_id"));
            item.put("myRegistration", statusByEvent.get(id));
            item.put("followingClub", followed.contains(clubId(item)));
            item.put("likedByMe", liked.contains(id));
        }
    }

    private static String clubId(Map<String, Object> item) {
        return item.get("club") instanceof Map<?, ?> club && club.get("_id") != null ? String.valueOf(club.get("_id")) : null;
    }
}
