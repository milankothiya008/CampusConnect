package com.campusconnect.feature.feed;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.CheckInMethod;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.support.Api;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.function.Consumer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Test data the social tests share. Events are written straight to the database (the events feature owns the event
 * routes); files are posted like the browser does in development storage.
 */
public final class SocialFixtures {

    /** A PNG header followed by filler bytes: recognised as a photo from its first bytes. */
    public static final byte[] PNG = bytes(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, filled(64, 1));
    /** An MP4 ("ftypisom") header followed by filler bytes. */
    public static final byte[] MP4 = bytes(new byte[] {0, 0, 0, 0x18}, "ftypisom".getBytes(StandardCharsets.US_ASCII), filled(64, 2));

    private SocialFixtures() {
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] bytes(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** An event of this club in the given state, ten days from now (like factory.eventPayload). */
    public static Event event(MongoOperations mongo, Club club, String title, EventStatus status, Consumer<Event> customize) {
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        Event event = new Event();
        event.setTitle(title);
        event.setShortDescription("An evening of building things together.");
        event.setDescription("Teams build projects over one evening and demo them at the end.");
        event.setCategory("TECHNOLOGY");
        event.setClub(club.getId());
        event.setEventDate(start.truncatedTo(ChronoUnit.DAYS));
        event.setStartTime("10:00");
        event.setEndTime("13:00");
        event.setStartAt(start);
        event.setEndAt(start.plus(3, ChronoUnit.HOURS));
        event.setRegistrationEnd(Instant.now().plus(8, ChronoUnit.DAYS));
        event.setMaxParticipants(50);
        event.setStatus(status);
        if (status == EventStatus.PUBLISHED) {
            event.setPublishedAt(Instant.now());
        }
        if (customize != null) {
            customize.accept(event);
        }
        return mongo.insert(event);
    }

    public static Event publishedEvent(MongoOperations mongo, Club club, String title) {
        return event(mongo, club, title, EventStatus.PUBLISHED, null);
    }

    public static EventRegistration register(MongoOperations mongo, Event event, TestUser student) {
        EventRegistration registration = new EventRegistration();
        registration.setEvent(event.getId());
        registration.setUser(student.id());
        registration.setStatus(RegistrationStatus.REGISTERED);
        return mongo.insert(registration);
    }

    /** Checked in at the door by an officer (manual check-in). */
    public static void checkIn(MongoOperations mongo, Event event, TestUser student, TestUser officer) {
        mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(event.getId())).and("user").is(new ObjectId(student.id()))),
                new Update().set("checkedInAt", Instant.now()).set("checkedInBy", new ObjectId(officer.id())).set("checkInMethod", CheckInMethod.MANUAL.name()),
                EventRegistration.class);
    }

    public static void setEventTimes(MongoOperations mongo, Event event, Instant startAt, Instant endAt) {
        Update update = new Update();
        if (startAt != null) {
            update.set("startAt", startAt);
        }
        if (endAt != null) {
            update.set("endAt", endAt);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(event.getId()))), update, Event.class);
    }

    /** A file posted as multipart/form-data field "file", signed in as {@code user} (null: anonymous). */
    public static Response upload(Api api, TestUser user, String url, byte[] content, String name) {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart(url);
        request.file(new MockMultipartFile("file", name, "application/octet-stream", content));
        if (user != null) {
            request.header("Authorization", "Bearer " + user.token());
        }
        return api.perform(request);
    }

    /**
     * Skips a ported test whose route belongs to a feature that is not part of this build (it runs once all features
     * are assembled): the API answers unknown routes with "Cannot find METHOD /path on this server".
     */
    public static Response assumeRoute(Response response) {
        Assumptions.assumeFalse(response.status() == 404 && response.message() != null && response.message().startsWith("Cannot find "),
                () -> "Route not in this build: " + response.message());
        return response;
    }
}
