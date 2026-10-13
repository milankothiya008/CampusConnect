package com.campusconnect.feature.user;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.CheckInStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.domain.event.TeamStatus;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.club.DocumentViews;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * A student's participation record (services/RecordService.js): clubs and roles, events they took part in, awards
 * and certificates — the document students attach to placement and scholarship applications. Each kind of related
 * data is loaded in one query.
 */
@Service
public class RecordService {

    public record ClubRow(String club, String role, Instant since) {
    }

    public record EventRow(String eventId, String title, String club, String category, Instant startAt, Instant endAt, String team, String attendance) {
    }

    public record AwardRow(String title, Integer position, String team, String event, String eventId, String club, Instant date) {
    }

    public record Record(Map<String, Object> user, List<ClubRow> clubs, List<EventRow> events, List<AwardRow> awards, List<Certificate> certificates) {
    }

    /** The record as a PDF file. */
    public record RecordFile(byte[] pdf, String filename) {
    }

    private final MongoOperations mongo;
    private final Refs refs;
    private final RecordPdf pdf;
    private final AppProperties properties;

    public RecordService(MongoOperations mongo, Refs refs, RecordPdf pdf, AppProperties properties) {
        this.mongo = mongo;
        this.refs = refs;
        this.pdf = pdf;
        this.properties = properties;
    }

    static String humanize(String value) {
        String text = String.valueOf(value == null ? "" : value).replace('_', ' ').toLowerCase(Locale.ROOT);
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static <T> Map<String, T> byId(List<T> items, Function<T, String> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (first, second) -> first));
    }

    public Record collectRecord(String userId) {
        Instant now = Instant.now();
        Map<String, Object> user = refs.user(userId, "name", "email", "departmentCode", "batchCode", "accountType", "createdAt");

        List<ClubMembership> memberships = mongo.find(Query.query(Criteria.where("user").is(userId).and("status").is(MembershipStatus.APPROVED)), ClubMembership.class);
        Map<String, Club> clubs = memberships.isEmpty() ? Map.of()
                : byId(mongo.find(Query.query(Criteria.where("_id").in(memberships.stream().map(ClubMembership::getClub).toList())), Club.class), Club::getId);

        List<EventRegistration> registrations = mongo.find(Query.query(Criteria.where("user").is(userId).and("status").is(RegistrationStatus.REGISTERED)), EventRegistration.class);
        Map<String, Event> events = Map.of();
        if (!registrations.isEmpty()) {
            Query query = Query.query(Criteria.where("_id").in(registrations.stream().map(EventRegistration::getEvent).toList()));
            query.fields().include("title", "category", "startAt", "endAt", "status", "checkIn", "club", "participationMode");
            events = byId(mongo.find(query, Event.class), Event::getId);
        }
        Map<String, Map<String, Object>> eventClubs = refs.clubs(events.values().stream().map(Event::getClub).filter(Objects::nonNull).toList(), "name");
        Map<String, Map<String, Object>> registrationTeams = refs.load("teams", registrations.stream().map(EventRegistration::getTeam).filter(Objects::nonNull).toList(), "name");

        Query teamQuery = Query.query(Criteria.where("status").is(TeamStatus.ACTIVE).and("members").elemMatch(Criteria.where("user").is(new ObjectId(userId))
                .and("status").in(TeamMemberStatus.LEADER.name(), TeamMemberStatus.ACCEPTED.name())));
        teamQuery.fields().include("event", "name");
        List<Team> teams = mongo.find(teamQuery, Team.class);
        List<Certificate> certificates = mongo.find(Query.query(Criteria.where("user").is(userId).and("revokedAt").is(null)).with(Sort.by(Sort.Direction.DESC, "eventStartAt")), Certificate.class);

        // Events that have happened: completed, or published and already over.
        List<EventRow> eventRows = new ArrayList<>();
        for (EventRegistration registration : registrations) {
            Event event = events.get(registration.getEvent());
            if (event == null || !(event.getStatus() == EventStatus.COMPLETED || (event.getStatus() == EventStatus.PUBLISHED && event.getEndAt() != null && !event.getEndAt().isAfter(now)))) {
                continue;
            }
            boolean checkInUsed = event.getCheckIn() != null && event.getCheckIn().getStatus() != null && event.getCheckIn().getStatus() != CheckInStatus.NOT_STARTED;
            String attendance = registration.getCheckedInAt() != null ? "Attended" : checkInUsed ? "Absent" : "Registered";
            if ("Absent".equals(attendance)) {
                continue;
            }
            Map<String, Object> club = DocumentViews.row(eventClubs, event.getClub());
            Map<String, Object> team = DocumentViews.row(registrationTeams, registration.getTeam());
            eventRows.add(new EventRow(event.getId(), event.getTitle(), club == null || club.get("name") == null ? "" : String.valueOf(club.get("name")),
                    humanize(event.getCategory()), event.getStartAt(), event.getEndAt(), team == null || team.get("name") == null ? null : String.valueOf(team.get("name")), attendance));
        }
        eventRows.sort(Comparator.comparing(EventRow::startAt, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, String> teamNames = new HashMap<>();
        teams.forEach(team -> teamNames.put(team.getEvent(), String.valueOf(team.getName()).toLowerCase(Locale.ROOT)));
        List<EventResult> results = mongo.find(Query.query(Criteria.where("status").is(ResultStatus.PUBLISHED).orOperator(
                Criteria.where("awards.recipientUser").is(new ObjectId(userId)),
                Criteria.where("event").in(teams.stream().map(Team::getEvent).toList()))), EventResult.class);
        Map<String, Event> resultEvents = Map.of();
        if (!results.isEmpty()) {
            Query query = Query.query(Criteria.where("_id").in(results.stream().map(EventResult::getEvent).toList()));
            query.fields().include("title", "startAt", "endAt", "club");
            resultEvents = byId(mongo.find(query, Event.class), Event::getId);
        }
        Map<String, Map<String, Object>> resultClubs = refs.clubs(resultEvents.values().stream().map(Event::getClub).filter(Objects::nonNull).toList(), "name");
        List<AwardRow> awards = new ArrayList<>();
        for (EventResult result : results) {
            Event event = resultEvents.get(result.getEvent());
            String teamName = teamNames.get(result.getEvent());
            result.getAwards().stream()
                    .filter(award -> userId.equals(award.getRecipientUser()) || (award.getTeamName() != null && !award.getTeamName().isEmpty()
                            && award.getTeamName().toLowerCase(Locale.ROOT).equals(teamName)))
                    .forEach(award -> {
                        Map<String, Object> club = event == null ? null : DocumentViews.row(resultClubs, event.getClub());
                        awards.add(new AwardRow(award.getTitle(), award.getPosition(), award.getTeamName(), event == null ? "" : event.getTitle(), event == null ? null : event.getId(),
                                club == null || club.get("name") == null ? "" : String.valueOf(club.get("name")), event == null ? null : event.getStartAt()));
                    });
        }
        awards.sort(Comparator.comparing(AwardRow::date, Comparator.nullsLast(Comparator.reverseOrder())));

        List<ClubRow> clubRows = memberships.stream()
                .filter(membership -> clubs.containsKey(membership.getClub()))
                .map(membership -> {
                    Club club = clubs.get(membership.getClub());
                    return new ClubRow(club.getName(), ClubRoles.roleName(club, membership.getRole()), membership.getJoinedAt() != null ? membership.getJoinedAt() : membership.getCreatedAt());
                })
                .toList();
        return new Record(user, clubRows, eventRows, awards, certificates);
    }

    /** The signed-in user's record as a PDF, named after them. */
    public RecordFile recordPdf(AuthUser actor) {
        Record record = collectRecord(actor.id());
        String name = record.user() == null ? actor.name() : String.valueOf(record.user().get("name"));
        byte[] bytes = pdf.render(record, properties.getUniversity().getName(), properties.getClientUrl());
        return new RecordFile(bytes, name.replaceAll("[^\\w-]+", "_") + "_participation_record.pdf");
    }
}
