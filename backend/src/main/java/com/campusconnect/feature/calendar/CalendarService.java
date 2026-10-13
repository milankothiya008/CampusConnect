package com.campusconnect.feature.calendar;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.tokens.CalendarTokens;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.InterviewSlot;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.team.Teams;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * "Add to calendar" for one event, and each person's private calendar feed (services/CalendarService.js): the events
 * they hold a place for (registered or waitlisted) and their interview slots. Calendar apps refresh the feed on their
 * own, so a changed time or a cancellation reaches the person's calendar without them doing anything.
 */
@Service
public class CalendarService {

    static final Duration PAST = Duration.ofDays(30);

    private final MongoOperations mongo;
    private final CalendarTokens tokens;
    private final Refs refs;
    private final AppProperties properties;

    public CalendarService(MongoOperations mongo, CalendarTokens tokens, Refs refs, AppProperties properties) {
        this.mongo = mongo;
        this.tokens = tokens;
        this.refs = refs;
        this.properties = properties;
    }

    /** An .ics download: its file name and content. */
    public record CalendarFile(String filename, String body) {
    }

    private static AppException notFound() {
        return AppException.notFound("Not found");
    }

    private String appLink(String path) {
        return String.valueOf(properties.getClientUrl() == null ? "" : properties.getClientUrl()).replaceAll("/$", "") + path;
    }

    private String apiBase() {
        String base = properties.getPublicApiUrl() != null && !properties.getPublicApiUrl().isBlank() ? properties.getPublicApiUrl() : properties.getClientUrl();
        return String.valueOf(base == null ? "" : base).replaceAll("/$", "");
    }

    private static String placeOf(Map<String, Object> venue) {
        if (venue == null) {
            return "";
        }
        return Stream.of(venue.get("name"), venue.get("location")).filter(value -> value != null && !String.valueOf(value).isEmpty())
                .map(String::valueOf).collect(Collectors.joining(", "));
    }

    private ICalendar.Item eventItem(Event event, Map<String, Object> club, Map<String, Object> venue, boolean waitlisted) {
        boolean cancelled = event.getStatus() == EventStatus.CANCELLED;
        String link = appLink("/events/" + event.getId());
        String description = Stream.of(club != null && club.get("name") != null ? "By " + club.get("name") : null, event.getShortDescription(),
                        cancelled ? "This event was cancelled." : null, link)
                .filter(value -> value != null && !value.isEmpty()).collect(Collectors.joining("\n\n"));
        return new ICalendar.Item("event-" + event.getId() + "@campusconnect", event.getStartAt(), event.getEndAt(),
                event.getTitle() + (waitlisted && !cancelled ? " (waitlist)" : ""), description, placeOf(venue), link,
                cancelled ? "CANCELLED" : waitlisted ? "TENTATIVE" : "CONFIRMED", event.getUpdatedAt(), 60);
    }

    /** One public event as an .ics file (anyone can add a published event to their calendar). */
    public CalendarFile eventCalendar(String eventId) {
        if (!ObjectId.isValid(eventId)) {
            throw notFound();
        }
        Event event = mongo.findById(eventId, Event.class);
        boolean visible = event != null && (EventStatus.PUBLIC.contains(event.getStatus()) || (event.getStatus() == EventStatus.CANCELLED && event.getPublishedAt() != null));
        if (!visible) {
            throw notFound();
        }
        Map<String, Object> venue = refs.one("venues", event.getVenue(), "name", "location");
        Map<String, Object> club = refs.club(event.getClub(), "name");
        String base = event.getTitle().replaceAll("[^\\w\\- ]+", "").strip();
        String filename = (base.length() > 60 ? base.substring(0, 60) : base);
        return new CalendarFile((filename.isEmpty() ? "event" : filename) + ".ics", ICalendar.build(event.getTitle(), List.of(eventItem(event, club, venue, false)), false));
    }

    private List<ICalendar.Item> interviewItems(String userId, Instant since) {
        Query query = Query.query(Criteria.where("applicant").is(new ObjectId(userId)).and("status").ne(ApplicationStatus.WITHDRAWN.name()).and("slots.endAt").gte(since));
        query.fields().include("drive", "position", "slots", "updatedAt");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);
        if (applications.isEmpty()) {
            return List.of();
        }
        Query drivesQuery = Query.query(Criteria.where("_id").in(applications.stream().map(application -> new ObjectId(application.getDrive())).distinct().toList()));
        drivesQuery.fields().include("title", "club", "positions._id", "positions.title", "positions.rounds");
        Map<String, RecruitmentDrive> drives = mongo.find(drivesQuery, RecruitmentDrive.class).stream().collect(Collectors.toMap(RecruitmentDrive::getId, Function.identity()));
        Map<String, Map<String, Object>> clubs = refs.clubs(drives.values().stream().map(RecruitmentDrive::getClub).toList(), "name");
        List<String> venueIds = drives.values().stream().flatMap(drive -> drive.getPositions().stream()).flatMap(position -> position.getRounds().stream())
                .map(RecruitmentRound::getVenue).filter(Objects::nonNull).toList();
        Map<String, Map<String, Object>> venues = refs.load("venues", venueIds, "name", "location");

        List<ICalendar.Item> items = new ArrayList<>();
        for (RecruitmentApplication application : applications) {
            RecruitmentDrive drive = drives.get(application.getDrive());
            RecruitmentPosition position = drive == null ? null
                    : drive.getPositions().stream().filter(item -> item.getId().equals(application.getPosition())).findFirst().orElse(null);
            if (position == null) {
                continue;
            }
            Map<String, Object> club = clubs.get(drive.getClub());
            for (InterviewSlot slot : application.getSlots()) {
                RecruitmentRound round = position.getRounds().stream().filter(item -> item.getId().equals(slot.getRound())).findFirst().orElse(null);
                if (round == null || (round.getStatus() != RoundStatus.SCHEDULED && round.getStatus() != RoundStatus.RESULTS_PUBLISHED)
                        || slot.getEndAt() == null || slot.getEndAt().isBefore(since)) {
                    continue;
                }
                String link = appLink("/recruitment/" + drive.getId());
                String description = Stream.of(drive.getTitle(), round.getMeetingLink() != null && !round.getMeetingLink().isEmpty() ? "Join: " + round.getMeetingLink() : null,
                                round.getInstructions() != null && !round.getInstructions().isEmpty() ? round.getInstructions() : null, link)
                        .filter(value -> value != null && !value.isEmpty()).collect(Collectors.joining("\n\n"));
                String location = round.getVenue() != null ? placeOf(venues.get(round.getVenue())) : round.getMeetingLink() != null ? round.getMeetingLink() : "";
                items.add(new ICalendar.Item("interview-" + application.getId() + "-" + round.getId() + "@campusconnect", slot.getStartAt(), slot.getEndAt(),
                        round.getName() + ": " + position.getTitle() + " (" + (club != null && club.get("name") != null ? club.get("name") : "Club") + ")",
                        description, location, link, "CONFIRMED", round.getUpdatedAt() != null ? round.getUpdatedAt() : application.getUpdatedAt(), 30));
            }
        }
        return items;
    }

    /** The person's whole feed, read by calendar apps with the private token. */
    public String feedCalendar(String rawToken) {
        String token = String.valueOf(rawToken == null ? "" : rawToken).replaceAll("(?i)\\.ics$", "");
        String userId = tokens.readUserId(token);
        if (userId == null) {
            throw notFound();
        }
        Query userQuery = Query.query(Criteria.where("_id").is(new ObjectId(userId)));
        userQuery.fields().include("name", "isActive", "calendarKeyVersion");
        User user = mongo.findOne(userQuery, User.class);
        if (user == null || !user.isActive() || !tokens.matches(token, user.getId(), user.getCalendarKeyVersion())) {
            throw notFound();
        }

        Instant since = Instant.now().minus(PAST);
        Query registrationQuery = Query.query(Criteria.where("user").is(new ObjectId(user.getId())).and("status").in(Teams.ACTIVE));
        registrationQuery.fields().include("event", "status");
        List<EventRegistration> registrations = mongo.find(registrationQuery, EventRegistration.class);
        Map<String, RegistrationStatus> statusOf = new HashMap<>();
        registrations.forEach(row -> statusOf.putIfAbsent(row.getEvent(), row.getStatus()));
        List<String> statuses = new ArrayList<>(EventStatus.PUBLIC.stream().map(Enum::name).toList());
        statuses.add(EventStatus.CANCELLED.name());
        List<Event> events = statusOf.isEmpty() ? List.of() : mongo.find(Query.query(Criteria.where("_id").in(statusOf.keySet().stream().map(ObjectId::new).toList())
                .and("endAt").gte(since).and("status").in(statuses)), Event.class);
        Map<String, Map<String, Object>> venues = refs.load("venues", events.stream().map(Event::getVenue).toList(), "name", "location");
        Map<String, Map<String, Object>> clubs = refs.clubs(events.stream().map(Event::getClub).toList(), "name");

        List<ICalendar.Item> items = new ArrayList<>();
        events.forEach(event -> items.add(eventItem(event, clubs.get(event.getClub()), venues.get(event.getVenue()),
                statusOf.get(event.getId()) == RegistrationStatus.WAITLISTED)));
        items.addAll(interviewItems(user.getId(), since));
        return ICalendar.build("CampusConnect", items, true);
    }

    private Map<String, Object> linkView(String userId, int version) {
        String url = apiBase() + "/api/calendar/feed/" + tokens.create(userId, version) + ".ics";
        String webcal = url.replaceFirst("(?i)^https?://", "webcal://");
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("url", url);
        link.put("webcal", webcal);
        link.put("google", "https://calendar.google.com/calendar/render?cid=" + URLEncoder.encode(webcal, StandardCharsets.UTF_8).replace("+", "%20"));
        return link;
    }

    public Map<String, Object> getFeedLink(AuthUser actor) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(actor.id())));
        query.fields().include("calendarKeyVersion");
        User user = mongo.findOne(query, User.class);
        return linkView(actor.id(), user == null ? 0 : user.getCalendarKeyVersion());
    }

    /** A new private address; calendars subscribed to the old one stop updating. */
    public Map<String, Object> resetFeedLink(AuthUser actor) {
        User user = mongo.findAndModify(Query.query(Criteria.where("_id").is(new ObjectId(actor.id()))), new Update().inc("calendarKeyVersion", 1),
                FindAndModifyOptions.options().returnNew(true), User.class);
        return linkView(actor.id(), user == null ? 0 : user.getCalendarKeyVersion());
    }
}
