package com.campusconnect.feature.result;

import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMember;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.domain.event.TeamRole;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Events, registrations, teams and check-ins written straight to MongoDB for the results, hackathon and certificate
 * tests — what the Node tests did through the event routes, which belong to the events feature.
 */
public final class EventFixtures {

    private final MongoOperations mongo;

    public EventFixtures(MongoOperations mongo) {
        this.mongo = mongo;
    }

    /**
     * An event like factory.eventPayload ("Hack Night", 10:00–13:00 university time, {@code days} from now, registration
     * closing in 8 days), in the given status.
     */
    public Event event(Club club, Venue venue, EventStatus status, int days, Consumer<Event> customize) {
        String date = TestData.futureDate(days);
        Event event = new Event();
        event.setTitle("Hack Night");
        event.setShortDescription("An evening of building things together.");
        event.setDescription("Teams build projects over one evening and demo them at the end.");
        event.setCategory("TECHNOLOGY");
        event.setClub(club.getId());
        event.setVenue(venue.getId());
        event.setEventDate(CampusTime.dateKeyToInstant(date));
        event.setStartTime("10:00");
        event.setEndTime("13:00");
        event.setStartAt(CampusTime.combineDateAndTime(date, "10:00"));
        event.setEndAt(CampusTime.combineDateAndTime(date, "13:00"));
        event.setRegistrationStart(Instant.now().minus(Duration.ofDays(1)));
        event.setRegistrationEnd(Instant.now().plus(Duration.ofDays(8)));
        event.setMaxParticipants(50);
        event.setRules("Bring your own laptop.");
        event.setCreatedBy(club.getPresident());
        event.setStatus(status);
        if (status == EventStatus.PUBLISHED || status == EventStatus.COMPLETED) {
            event.setPublishedAt(Instant.now());
        }
        if (customize != null) {
            customize.accept(event);
        }
        return mongo.insert(event);
    }

    public Event published(Club club, Venue venue, int days, Consumer<Event> customize) {
        return event(club, venue, EventStatus.PUBLISHED, days, customize);
    }

    /** A placed registration (and the event's seat count), like a successful POST /register. */
    public EventRegistration register(Event event, TestUser user) {
        return register(event, user, null, null);
    }

    public EventRegistration register(Event event, TestUser user, Team team, TeamRole role) {
        EventRegistration registration = new EventRegistration();
        registration.setEvent(event.getId());
        registration.setUser(user.id());
        registration.setStatus(RegistrationStatus.REGISTERED);
        registration.setTeam(team == null ? null : team.getId());
        registration.setTeamRole(role);
        registration.setTicketCode(new ObjectId().toHexString().substring(12).toUpperCase(Locale.ROOT));
        registration.setTicketIssuedAt(Instant.now());
        EventRegistration saved = mongo.insert(registration);
        if (team == null || role == TeamRole.LEADER) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(event.getId()))), new Update().inc("registeredCount", 1), Event.class);
        }
        return saved;
    }

    /** An active team led by {@code leader} with accepted members, every one of them registered (team events). */
    public Team team(Event event, String name, TestUser leader, TestUser... members) {
        Team team = new Team();
        team.setEvent(event.getId());
        team.setName(name);
        team.setNameKey(name.toLowerCase(Locale.ROOT));
        team.setLeader(leader.id());
        List<TeamMember> roster = new ArrayList<>();
        roster.add(member(leader, TeamMemberStatus.LEADER));
        for (TestUser user : members) {
            roster.add(member(user, TeamMemberStatus.ACCEPTED));
        }
        team.setMembers(roster);
        team.setSize(roster.size());
        Team saved = mongo.insert(team);
        register(event, leader, saved, TeamRole.LEADER);
        for (TestUser user : members) {
            register(event, user, saved, TeamRole.MEMBER);
        }
        return saved;
    }

    private static TeamMember member(TestUser user, TeamMemberStatus status) {
        TeamMember member = new TeamMember();
        member.setUser(user.id());
        member.setStatus(status);
        member.setRespondedAt(Instant.now());
        return member;
    }

    /** Attendance marked at the door. */
    public void checkIn(Event event, TestUser... users) {
        for (TestUser user : users) {
            mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(user.id()))),
                    new Update().set("checkedInAt", Instant.now()), EventRegistration.class);
        }
    }

    public void update(Event event, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(event.getId()))), update.set("updatedAt", Instant.now()), Event.class);
    }

    /** Moves the event's start into the past while keeping its status (it is running). */
    public void start(Event event) {
        update(event, new Update()
                .set("registrationStart", Instant.now().minus(Duration.ofHours(5)))
                .set("registrationEnd", Instant.now().minus(Duration.ofHours(4)))
                .set("startAt", Instant.now().minus(Duration.ofHours(1)))
                .set("endAt", Instant.now().plus(Duration.ofHours(5))));
    }

    /** The event happened (registrationResults.test.js moveToPast) — optionally completed too. */
    public void finish(Event event, boolean completed) {
        Update update = new Update()
                .set("registrationStart", Instant.now().minus(Duration.ofHours(5)))
                .set("registrationEnd", Instant.now().minus(Duration.ofHours(4)))
                .set("startAt", Instant.now().minus(Duration.ofHours(3)))
                .set("endAt", Instant.now().minus(Duration.ofHours(1)));
        if (completed) {
            update.set("status", EventStatus.COMPLETED).set("completedAt", Instant.now());
        }
        update(event, update);
    }

    public static Consumer<Event> team(int min, int max) {
        return event -> {
            event.setParticipationMode(ParticipationMode.TEAM);
            event.setMinTeamSize(min);
            event.setMaxTeamSize(max);
        };
    }
}
