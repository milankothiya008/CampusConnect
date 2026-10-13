package com.campusconnect.feature.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.support.Api;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The steps the original tests repeated (create a draft, get it approved, publish, register…) for the event-area
 * integration tests. Each test builds the state it needs, since every test starts with an empty database.
 */
public final class EventFlows {

    public static final Duration HOUR = Duration.ofHours(1);
    public static final Duration DAY = Duration.ofDays(1);

    private final Api api;
    private final MongoOperations mongo;

    public EventFlows(Api api, MongoOperations mongo) {
        this.api = api;
        this.mongo = mongo;
    }

    /** {@code map("title", "X", "maxParticipants", 5)}; values may be null. */
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public static Map<String, Object> payload(Club club, Venue venue, Object... overrides) {
        return TestData.eventPayload(club, venue, map(overrides));
    }

    public static String fromNow(Duration amount) {
        return Instant.now().plus(amount).toString();
    }

    public Response create(TestUser author, Club club, Venue venue, Object... overrides) {
        return api.as(author).post("/api/events", payload(club, venue, overrides));
    }

    /** A draft created through the API; fails the test when the API refuses it. */
    public String draft(TestUser author, Club club, Venue venue, Object... overrides) {
        Response created = create(author, club, venue, overrides);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.string("$.data._id");
    }

    public String approved(TestUser president, TestUser mentor, Club club, Venue venue, Object... overrides) {
        String id = draft(president, club, venue, overrides);
        Response submitted = api.as(president).post("/api/events/" + id + "/submit");
        assertThat(submitted.status()).as(submitted.body()).isEqualTo(200);
        Response approved = api.as(mentor).post("/api/events/" + id + "/approve");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return id;
    }

    public String published(TestUser president, TestUser mentor, Club club, Venue venue, Object... overrides) {
        String id = approved(president, mentor, club, venue, overrides);
        Response published = api.as(president).post("/api/events/" + id + "/publish");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        return id;
    }

    /** Publishes straight in the database (the original tests' {@code Event.updateOne({ status: "PUBLISHED" })}). */
    public void markPublished(String eventId) {
        set(eventId, new Update().set("status", "PUBLISHED").set("publishedAt", Instant.now()));
    }

    public void set(String eventId, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(eventId))), update, Event.class);
    }

    /** Moves the event's times (registration opens, closes; event starts, ends), relative to now. */
    public void times(String eventId, Duration registrationStart, Duration registrationEnd, Duration startAt, Duration endAt) {
        Instant now = Instant.now();
        Update update = new Update();
        if (registrationStart != null) {
            update.set("registrationStart", now.plus(registrationStart));
        }
        if (registrationEnd != null) {
            update.set("registrationEnd", now.plus(registrationEnd));
        }
        if (startAt != null) {
            update.set("startAt", now.plus(startAt));
        }
        if (endAt != null) {
            update.set("endAt", now.plus(endAt));
        }
        set(eventId, update);
    }

    public Response register(TestUser student, String eventId) {
        return api.as(student).post("/api/events/" + eventId + "/register");
    }

    public Response register(TestUser student, String eventId, Map<String, Object> body) {
        return api.as(student).post("/api/events/" + eventId + "/register", body);
    }

    public Response cancel(TestUser student, String eventId) {
        return api.as(student).delete("/api/events/" + eventId + "/register");
    }

    public Event event(String eventId) {
        return mongo.findById(eventId, Event.class);
    }

    public EventRegistration registrationOf(TestUser student, String eventId) {
        return mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("user").is(new ObjectId(student.id()))), EventRegistration.class);
    }

    /** The registration's status, or null when the student never registered. */
    public String statusOf(TestUser student, String eventId) {
        EventRegistration registration = registrationOf(student, eventId);
        return registration == null ? null : registration.getStatus().name();
    }

    public boolean notified(TestUser user, String type) {
        return mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("type").is(type)), Notification.class);
    }

    public boolean notified(TestUser user, String type, String title) {
        return mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("type").is(type).and("title").is(title)), Notification.class);
    }

    /** A notification of any type with exactly this title. */
    public boolean notifiedTitled(TestUser user, String title) {
        return mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("title").is(title)), Notification.class);
    }

    public long countTitled(TestUser user, String titlePattern) {
        return mongo.count(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("title").regex(Pattern.compile(titlePattern))), Notification.class);
    }
}
