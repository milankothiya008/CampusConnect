package com.campusconnect.feature.registration;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Port of tests/integration/waitlist.test.js (the dashboard statistics test belongs to the dashboard feature). */
class WaitlistIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser coordinator;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty();
        president = data.student(spec -> spec.name("President"));
        coordinator = data.student(spec -> spec.name("Coordinator"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
    }

    String publishEvent(Object... overrides) {
        return flows.published(president, mentor, club, venues.auditorium(), overrides);
    }

    @Nested
    class JoiningAndPromotion {

        String eventId;
        TestUser anu;
        TestUser bina;
        TestUser chirag;
        TestUser dev;
        TestUser esha;

        @BeforeEach
        void hackNight() {
            eventId = publishEvent("title", "Hack Night", "maxParticipants", 2, "eventDate", TestData.futureDate(10));
            anu = data.student(spec -> spec.name("Anu"));
            bina = data.student(spec -> spec.name("Bina"));
            chirag = data.student(spec -> spec.name("Chirag"));
            dev = data.student(spec -> spec.name("Dev"));
            esha = data.student(spec -> spec.name("Esha"));
        }

        /** Anu and Bina take the two seats; Chirag (#1) and Dev (#2) wait. */
        void full() {
            flows.register(anu, eventId);
            flows.register(bina, eventId);
            flows.register(chirag, eventId);
            flows.register(dev, eventId);
        }

        void anuCancels() {
            full();
            flows.cancel(anu, eventId);
        }

        void devLeavesTheWaitlist() {
            anuCancels();
            flows.cancel(dev, eventId);
        }

        void binaIsRemovedAndEshaWaits() {
            devLeavesTheWaitlist();
            flows.register(dev, eventId);
            List<Map<String, Object>> rows = api.as(president).get("/api/events/" + eventId + "/registrations").at("$.data[?(@.user.name == 'Bina')]");
            api.as(president).delete("/api/events/" + eventId + "/registrations/" + rows.get(0).get("_id"), map("reason", "Duplicate team entry"));
            flows.register(esha, eventId);
        }

        TestUser capacityRaised() {
            binaIsRemovedAndEshaWaits();
            TestUser extra = data.student(spec -> spec.name("Farah"));
            flows.register(extra, eventId);
            api.as(president).put("/api/events/" + eventId, map("maxParticipants", 4));
            api.as(mentor).post("/api/events/" + eventId + "/changes/approve");
            assertThat(api.as(president).post("/api/events/" + eventId + "/changes/publish").status()).isEqualTo(200);
            return extra;
        }

        @Test
        void onceTheEventIsFullStudentsJoinTheWaitlistInOrderInsteadOfBeingTurnedAway() {
            assertThat((Boolean) flows.register(anu, eventId).at("$.data.waitlisted")).isFalse();
            assertThat((Boolean) flows.register(bina, eventId).at("$.data.waitlisted")).isFalse();

            Response third = flows.register(chirag, eventId);
            assertThat(third.status()).isEqualTo(201);
            assertThat(third.message()).isEqualTo("This event is full. You're #1 on the waitlist.");
            assertThat((Boolean) third.at("$.data.waitlisted")).isTrue();
            assertThat((Integer) third.at("$.data.waitlistPosition")).isEqualTo(1);
            assertThat((Integer) third.at("$.data.registeredCount")).isEqualTo(2);
            assertThat((Integer) third.at("$.data.waitlistCount")).isEqualTo(1);

            assertThat((Integer) flows.register(dev, eventId).at("$.data.waitlistPosition")).isEqualTo(2);

            Response detail = api.as(dev).get("/api/events/" + eventId);
            assertThat(detail.string("$.data.registrationState")).isEqualTo("FULL");
            assertThat((Integer) detail.at("$.data.waitlistCount")).isEqualTo(2);
            assertThat(detail.string("$.data.viewer.registration.status")).isEqualTo("WAITLISTED");
            assertThat((Integer) detail.at("$.data.viewer.registration.waitlistPosition")).isEqualTo(2);

            assertThat(flows.notified(chirag, "WAITLISTED", "You're #1 on the waitlist for Hack Night")).isTrue();
        }

        @Test
        void joiningTwiceIsRefusedWithTheCurrentPlaceInTheQueue() {
            full();
            Response again = flows.register(chirag, eventId);
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.message()).isEqualTo("You are already on the waitlist (#1)");
        }

        @Test
        void aCancellationPromotesTheFirstStudentWaitingAndEmailsThem() {
            full();
            Response res = flows.cancel(anu, eventId);
            assertThat((Boolean) res.at("$.data.leftWaitlist")).isFalse();
            assertThat((Integer) res.at("$.data.promoted")).isEqualTo(1);
            assertThat((Integer) res.at("$.data.registeredCount")).isEqualTo(2);
            assertThat((Integer) res.at("$.data.waitlistCount")).isEqualTo(1);

            EventRegistration promoted = flows.registrationOf(chirag, eventId);
            assertThat(promoted.getStatus().name()).isEqualTo("REGISTERED");
            assertThat(promoted.getPromotedAt()).isNotNull();
            assertThat(flows.notified(chirag, "REGISTRATION_CONFIRMED", "You're in! A spot opened up for Hack Night")).isTrue();

            List<DevInbox.Entry> mails = data.mailsTo(chirag.email()).stream().filter(mail -> mail.subject().contains("spot opened up")).toList();
            assertThat(mails.get(0).subject()).isEqualTo("You're in! A spot opened up for Hack Night");
            assertThat(mails.get(0).text()).contains("moved off the waitlist and are now registered");

            // Dev moves up to the front of the queue.
            assertThat((Integer) api.as(dev).get("/api/events/" + eventId).at("$.data.viewer.registration.waitlistPosition")).isEqualTo(1);
        }

        @Test
        void leavingTheWaitlistFreesThePlaceWithoutTouchingSeats() {
            anuCancels();
            Response res = flows.cancel(dev, eventId);
            assertThat(res.message()).isEqualTo("You left the waitlist");
            Event event = flows.event(eventId);
            assertThat(event.getRegisteredCount()).isEqualTo(2);
            assertThat(event.getWaitlistCount()).isZero();
            assertThat(flows.statusOf(dev, eventId)).isEqualTo("CANCELLED");
        }

        @Test
        void aNewcomerCannotJumpAheadOfTheQueue() {
            devLeavesTheWaitlist();
            flows.register(dev, eventId);
            // An organiser removes a participant; the seat goes to Dev, not to Esha who registers right after.
            List<Map<String, Object>> rows = api.as(president).get("/api/events/" + eventId + "/registrations").at("$.data[?(@.user.name == 'Bina')]");
            api.as(president).delete("/api/events/" + eventId + "/registrations/" + rows.get(0).get("_id"), map("reason", "Duplicate team entry"));

            assertThat(flows.statusOf(dev, eventId)).isEqualTo("REGISTERED");
            Response newcomer = flows.register(esha, eventId);
            assertThat((Boolean) newcomer.at("$.data.waitlisted")).isTrue();
            assertThat((Integer) newcomer.at("$.data.waitlistPosition")).isEqualTo(1);
        }

        @Test
        void organisersSeeTheWaitlistInOrder() {
            binaIsRemovedAndEshaWaits();
            Response res = api.as(coordinator).get("/api/events/" + eventId + "/registrations");
            assertThat((List<String>) res.at("$.data[*].user.name")).containsExactlyInAnyOrder("Chirag", "Dev");
            assertThat((List<Integer>) res.at("$.meta.waitlist[*].position")).containsExactly(1);
            assertThat((List<String>) res.at("$.meta.waitlist[*].user.name")).containsExactly("Esha");
            assertThat((Integer) res.at("$.meta.event.waitlistCount")).isEqualTo(1);
        }

        @Test
        void raisingTheCapacityOnceTheMentorApprovesTheChangePromotesWaitingStudentsImmediately() {
            binaIsRemovedAndEshaWaits();
            TestUser extra = data.student(spec -> spec.name("Farah"));
            flows.register(extra, eventId);
            assertThat(flows.event(eventId).getWaitlistCount()).isEqualTo(2);

            Response res = api.as(president).put("/api/events/" + eventId, map("maxParticipants", 4));
            assertThat(res.status()).isEqualTo(200);
            assertThat(flows.event(eventId).getMaxParticipants()).isEqualTo(2);
            api.as(mentor).post("/api/events/" + eventId + "/changes/approve");
            assertThat(api.as(president).post("/api/events/" + eventId + "/changes/publish").status()).isEqualTo(200);
            Event event = flows.event(eventId);
            assertThat(event.getRegisteredCount()).isEqualTo(4);
            assertThat(event.getWaitlistCount()).isZero();
            assertThat(flows.statusOf(esha, eventId)).isEqualTo("REGISTERED");
            assertThat(flows.statusOf(extra, eventId)).isEqualTo("REGISTERED");
        }

        @Test
        void myRegistrationsCanIncludeWaitlistedEventsWithTheirPlace() {
            capacityRaised();
            TestUser waiting = data.student(spec -> spec.name("Gita"));
            flows.register(waiting, eventId);
            Response mine = api.as(waiting).get("/api/registrations/me?includeWaitlist=true");
            assertThat((List<?>) mine.at("$.data")).hasSize(1);
            assertThat(mine.string("$.data[0].status")).isEqualTo("WAITLISTED");
            assertThat((Integer) mine.at("$.data[0].waitlistPosition")).isEqualTo(1);
            assertThat((List<?>) api.as(waiting).get("/api/registrations/me").at("$.data")).isEmpty();
        }

        @Test
        void waitlistedStudentsHearWhenTheEventIsCancelled() {
            capacityRaised();
            TestUser waiting = data.student(spec -> spec.name("Gita"));
            flows.register(waiting, eventId);
            Response res = api.as(president).post("/api/events/" + eventId + "/cancel", map("reason", "Venue unavailable"));
            assertThat(res.status()).isEqualTo(200);
            EventRegistration gita = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("WAITLISTED")), EventRegistration.class);
            assertThat(gita.getUser()).isEqualTo(waiting.id());
            assertThat(flows.notified(waiting, "EVENT_CANCELLED")).isTrue();
        }
    }

    @Test
    void parallelCancellationsNeverOverfillTheEventOrPromoteAnyoneTwice() throws Exception {
        String eventId = publishEvent("title", "Rush Hour", "maxParticipants", 3, "eventDate", TestData.futureDate(12), "startTime", "15:00", "endTime", "16:00");
        List<TestUser> students = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            students.add(data.student());
        }
        for (TestUser student : students) {
            flows.register(student, eventId);
        }
        Event full = flows.event(eventId);
        assertThat(full.getRegisteredCount()).isEqualTo(3);
        assertThat(full.getWaitlistCount()).isEqualTo(5);

        List<Callable<Response>> cancellations = students.subList(0, 3).stream().<Callable<Response>>map(student -> () -> flows.cancel(student, eventId)).toList();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            for (Future<Response> result : pool.invokeAll(cancellations)) {
                assertThat(result.get().status()).isEqualTo(200);
            }
        }

        Event event = flows.event(eventId);
        assertThat(event.getRegisteredCount()).isEqualTo(3);
        assertThat(event.getWaitlistCount()).isEqualTo(2);
        assertThat(mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("REGISTERED")), EventRegistration.class)).isEqualTo(3);
        // Promotion follows the queue: students 4-6 got the seats, 7-8 still wait.
        for (TestUser student : students.subList(3, 6)) {
            assertThat(flows.statusOf(student, eventId)).isEqualTo("REGISTERED");
        }
        for (TestUser student : students.subList(6, 8)) {
            assertThat(flows.statusOf(student, eventId)).isEqualTo("WAITLISTED");
        }
    }
}
