package com.campusconnect.feature.registration;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Port of the registration parts of tests/integration/registrationResults.test.js ("event registration" and
 * "participant access is permission-based"), plus concurrency checks the original suite lacks. Publishing results,
 * announcements, notifications and dashboards belong to other features; only how published results appear on the
 * event feed is checked here, with the result inserted directly.
 */
class RegistrationIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser otherFaculty;
    TestUser president;
    TestUser coordinator;
    TestUser treasurer;
    TestUser marketing;
    TestUser member;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty("CE", "Mentor");
        otherFaculty = data.faculty("CE", "Other Faculty");
        president = data.student(spec -> spec.name("President"));
        coordinator = data.student(spec -> spec.name("Event Coordinator"));
        treasurer = data.student(spec -> spec.name("Treasurer"));
        marketing = data.student(spec -> spec.name("Marketing"));
        member = data.student(spec -> spec.name("Member"));
        club = data.activeClub("AI Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, treasurer, "TREASURER");
        data.addMembership(club, marketing, "MARKETING_COORDINATOR");
        data.addMembership(club, member, "MEMBER");
    }

    String publishEvent(Object... overrides) {
        return flows.published(president, mentor, club, venues.auditorium(), overrides);
    }

    static List<Response> inParallel(List<Callable<Response>> calls) throws Exception {
        List<Response> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            for (Future<Response> result : pool.invokeAll(calls)) {
                results.add(result.get());
            }
        }
        return results;
    }

    @Nested
    class EventRegistrationTests {

        String eventId;
        TestUser first;

        @BeforeEach
        void openEvent() {
            eventId = publishEvent("maxParticipants", 2, "eligibility", map("departments", List.of("CE"), "batches", List.of("24")));
            first = data.student(spec -> spec.name("First"));
        }

        /** First holds a seat, one more student holds the other, four wait. */
        void fullWithAQueue() throws Exception {
            flows.register(first, eventId);
            List<TestUser> students = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                students.add(data.student());
            }
            inParallel(students.stream().<Callable<Response>>map(student -> () -> flows.register(student, eventId)).toList());
        }

        @Test
        void aStudentRegistersAndReceivesAConfirmationNotification() {
            Response res = flows.register(first, eventId);
            assertThat(res.status()).isEqualTo(201);
            assertThat((Integer) res.at("$.data.registeredCount")).isEqualTo(1);
            assertThat(res.message()).isEqualTo("You're registered for this event");
            assertThat(flows.notified(first, "REGISTRATION_CONFIRMED")).isTrue();

            Response detail = api.as(first).get("/api/events/" + eventId);
            assertThat(detail.string("$.data.viewer.registration.status")).isEqualTo("REGISTERED");
        }

        @Test
        void duplicateRegistrationIsRejected() {
            flows.register(first, eventId);
            Response res = flows.register(first, eventId);
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.errorCode()).isEqualTo("DUPLICATE_REGISTRATION");
        }

        @Test
        void eligibilityIsEnforcedByDepartmentAndBatch() {
            TestUser itStudent = data.student(spec -> spec.department("IT"));
            TestUser oldBatch = data.student(spec -> spec.batch("23"));
            assertThat(flows.register(itStudent, eventId).errorCode()).isEqualTo("NOT_ELIGIBLE");
            assertThat(flows.register(oldBatch, eventId).errorCode()).isEqualTo("NOT_ELIGIBLE");
        }

        @Test
        void facultyCannotRegister() {
            assertThat(flows.register(otherFaculty, eventId).status()).isEqualTo(403);
        }

        @Test
        void capacityHoldsUnderConcurrentRegistrationsTheRestJoinTheWaitlistInOrder() throws Exception {
            flows.register(first, eventId);
            List<TestUser> students = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                students.add(data.student());
            }
            List<Response> results = inParallel(students.stream().<Callable<Response>>map(student -> () -> flows.register(student, eventId)).toList());

            assertThat(results).allSatisfy(res -> assertThat(res.status()).as(res.body()).isEqualTo(201));
            assertThat(results.stream().filter(res -> !(Boolean) res.at("$.data.waitlisted"))).hasSize(1);
            List<Integer> positions = results.stream().filter(res -> (Boolean) res.at("$.data.waitlisted")).map(res -> (Integer) res.at("$.data.waitlistPosition")).toList();
            assertThat(positions).containsExactlyInAnyOrder(1, 2, 3, 4);
            Event event = flows.event(eventId);
            assertThat(event.getWaitlistCount()).isEqualTo(4);
            assertThat(event.getRegisteredCount()).isEqualTo(2);
            assertThat(mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("REGISTERED")), EventRegistration.class)).isEqualTo(2);
        }

        @Test
        void cancellingHandsTheSeatToTheWaitlistReRegisteringJoinsTheBackOfTheQueue() throws Exception {
            fullWithAQueue();
            assertThat(flows.cancel(first, eventId).status()).isEqualTo(200);
            Event afterCancel = flows.event(eventId);
            assertThat(afterCancel.getRegisteredCount()).isEqualTo(2);
            assertThat(afterCancel.getWaitlistCount()).isEqualTo(3);

            Response again = flows.register(first, eventId);
            assertThat(again.status()).isEqualTo(201);
            assertThat((Boolean) again.at("$.data.waitlisted")).isTrue();
            assertThat((Integer) again.at("$.data.waitlistPosition")).isEqualTo(4);
        }

        @Test
        void registrationFailsAfterTheDeadlineAndWhenManuallyClosed() {
            String late = publishEvent("title", "Late Event", "eventDate", TestData.futureDate(15), "maxParticipants", 20);
            flows.set(late, new org.springframework.data.mongodb.core.query.Update()
                    .set("registrationEnd", Instant.now().minusSeconds(1)).set("registrationStart", Instant.now().minus(Duration.ofDays(1))));
            TestUser student = data.student();
            assertThat(flows.register(student, late).errorCode()).isEqualTo("REGISTRATION_CLOSED");

            String closed = publishEvent("title", "Closed Event", "eventDate", TestData.futureDate(16), "maxParticipants", 20);
            api.as(president).put("/api/events/" + closed, map("registrationClosed", true));
            assertThat(flows.register(student, closed).errorCode()).isEqualTo("REGISTRATION_CLOSED");
        }

        @Test
        void unpublishedEventsDoNotAcceptRegistrations() {
            String draft = flows.draft(president, club, venues.hall(), "title", "Draft Only", "eventDate", TestData.futureDate(17), "maxParticipants", 20);
            TestUser student = data.student();
            assertThat(flows.register(student, draft).status()).isEqualTo(400);
        }

        @Test
        void myRegistrationsListsUpcomingEventsIncludingOnesTheStudentIsWaitlistedFor() throws Exception {
            fullWithAQueue();
            flows.cancel(first, eventId);
            flows.register(first, eventId);
            Response res = api.as(first).get("/api/registrations/me?timeframe=upcoming&includeWaitlist=true");
            assertThat((List<String>) res.at("$.data[*].event._id")).contains(eventId);
        }
    }

    @Nested
    class ParticipantAccessIsPermissionBased {

        String eventId;
        TestUser participant;

        @BeforeEach
        void eventWithAParticipant() {
            eventId = publishEvent("title", "Participants Event", "eventDate", TestData.futureDate(20), "maxParticipants", 20);
            participant = data.student(spec -> spec.name("Participant"));
            flows.register(participant, eventId);
        }

        TestUser who(String label) {
            return switch (label) {
                case "president" -> president;
                case "event coordinator" -> coordinator;
                case "treasurer (view only)" -> treasurer;
                case "faculty mentor" -> mentor;
                case "marketing coordinator" -> marketing;
                case "plain member" -> member;
                case "other faculty" -> otherFaculty;
                default -> data.student(spec -> spec.name("Outsider"));
            };
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "president, 200",
            "event coordinator, 200",
            "treasurer (view only), 200",
            "faculty mentor, 200",
            "marketing coordinator, 403",
            "plain member, 403",
            "other faculty, 403",
            "unrelated student, 403"
        })
        void participantListAccess(String label, int status) {
            assertThat(api.as(who(label)).get("/api/events/" + eventId + "/registrations").status()).isEqualTo(status);
        }

        @Test
        void onlyManageParticipantsRolesCanRemoveAParticipant() {
            String registrationId = api.as(president).get("/api/events/" + eventId + "/registrations").string("$.data[0]._id");

            assertThat(api.as(treasurer).delete("/api/events/" + eventId + "/registrations/" + registrationId).status()).isEqualTo(403);
            assertThat(api.as(mentor).delete("/api/events/" + eventId + "/registrations/" + registrationId).status()).isEqualTo(403);

            Response res = api.as(coordinator).delete("/api/events/" + eventId + "/registrations/" + registrationId, map("reason", "Duplicate team"));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Participant removed");
            assertThat(flows.notified(participant, "REGISTRATION_REMOVED")).isTrue();
        }
    }

    @Nested
    class PublishedResultsOnTheFeed {

        @Test
        void aCompletedEventCarriesItsPublishedResultsInThePastFeed() {
            String eventId = publishEvent("title", "Code Sprint", "eventDate", TestData.futureDate(25), "maxParticipants", 20);
            TestUser winner = data.student(spec -> spec.name("Winner"));
            TestUser runnerUp = data.student(spec -> spec.name("Runner Up"));
            flows.register(winner, eventId);
            flows.register(runnerUp, eventId);
            flows.times(eventId, Duration.ofHours(-5), Duration.ofHours(-4), Duration.ofHours(-3), Duration.ofHours(-1));
            assertThat(api.as(president).post("/api/events/" + eventId + "/complete").status()).isEqualTo(200);

            EventResult result = new EventResult();
            result.setEvent(eventId);
            result.setClub(club.getId());
            result.setSummary("A great sprint with 12 teams competing.");
            result.setAwards(List.of(award("Winner", 1, winner), award("Runner-up", 2, runnerUp)));
            result.setStatus(ResultStatus.PUBLISHED);
            result.setPublishedAt(Instant.now());
            result.setLastPublishedAt(Instant.now());
            mongo.insert(result);

            Response past = api.as(data.student()).get("/api/events?timeframe=past");
            List<Map<String, Object>> cards = past.at("$.data[?(@._id == '" + eventId + "')]");
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0)).containsEntry("resultStage", "final");
            List<String> recipients = past.at("$.data[0].result.awards[*].recipientName");
            assertThat(recipients).containsExactly("Winner", "Runner Up");
            assertThat(past.string("$.data[0].result.awards[0].recipientUser.name")).isEqualTo("Winner");
        }

        ResultAward award(String title, int position, TestUser recipient) {
            ResultAward award = new ResultAward();
            award.setTitle(title);
            award.setPosition(position);
            award.setRecipientUser(recipient.id());
            award.setRecipientName(recipient.name());
            return award;
        }
    }

    @Nested
    class UnderLoad {

        @Test
        void twentyParallelRegistrationsForFiveSeatsFillExactlyFiveAndQueueTheRestInOrder() throws Exception {
            String eventId = publishEvent("title", "Five Seats", "maxParticipants", 5);
            List<TestUser> students = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                students.add(data.student());
            }
            List<Response> results = inParallel(students.stream().<Callable<Response>>map(student -> () -> flows.register(student, eventId)).toList());

            assertThat(results).allSatisfy(res -> assertThat(res.status()).as(res.body()).isEqualTo(201));
            assertThat(results.stream().filter(res -> !(Boolean) res.at("$.data.waitlisted"))).hasSize(5);
            List<Integer> positions = results.stream().filter(res -> (Boolean) res.at("$.data.waitlisted")).map(res -> (Integer) res.at("$.data.waitlistPosition")).toList();
            assertThat(positions).hasSize(15);

            Event event = flows.event(eventId);
            assertThat(event.getRegisteredCount()).isEqualTo(5);
            assertThat(event.getWaitlistCount()).isEqualTo(15);
            Query registered = Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("REGISTERED"));
            assertThat(mongo.count(registered, EventRegistration.class)).isEqualTo(5);
            assertThat(mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("WAITLISTED")), EventRegistration.class)).isEqualTo(15);
            // Every seat holder has a ticket of their own; nobody waiting has one.
            List<EventRegistration> seated = mongo.find(registered, EventRegistration.class);
            Set<String> codes = new HashSet<>();
            seated.forEach(row -> codes.add(row.getTicketCode()));
            assertThat(codes).hasSize(5).doesNotContainNull();
            // Each waiting student has a distinct place; the queue counts 1..15 without gaps.
            List<Integer> queue = students.stream().map(student -> (Integer) api.as(student).get("/api/events/" + eventId).at("$.data.viewer.registration.waitlistPosition"))
                    .filter(java.util.Objects::nonNull).sorted().toList();
            assertThat(queue).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 15).boxed().toList());
        }

        @Test
        void aStudentDoubleClickingRegisterGetsOnePlace() throws Exception {
            String eventId = publishEvent("title", "Double Click", "maxParticipants", 5);
            TestUser student = data.student();
            List<Response> results = inParallel(List.of(() -> flows.register(student, eventId), () -> flows.register(student, eventId), () -> flows.register(student, eventId)));

            assertThat(results.stream().filter(res -> res.status() == 201)).hasSize(1);
            assertThat(results.stream().filter(res -> res.status() == 409)).hasSize(2);
            assertThat(flows.event(eventId).getRegisteredCount()).isEqualTo(1);
            assertThat(mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId))), EventRegistration.class)).isEqualTo(1);
        }

        @Test
        void parallelCancellationsAndRegistrationsKeepTheCountsInStepWithTheRegistrations() throws Exception {
            String eventId = publishEvent("title", "Busy Night", "maxParticipants", 4);
            List<TestUser> holders = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                TestUser student = data.student();
                holders.add(student);
                flows.register(student, eventId);
            }
            List<TestUser> newcomers = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                newcomers.add(data.student());
            }
            List<Callable<Response>> calls = new ArrayList<>();
            holders.subList(0, 4).forEach(student -> calls.add(() -> flows.cancel(student, eventId)));
            newcomers.forEach(student -> calls.add(() -> flows.register(student, eventId)));
            inParallel(calls);

            Event event = flows.event(eventId);
            long registered = mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("REGISTERED")), EventRegistration.class);
            long waiting = mongo.count(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is("WAITLISTED")), EventRegistration.class);
            assertThat(registered).isEqualTo(4);
            assertThat(event.getRegisteredCount()).isEqualTo(4);
            assertThat(event.getWaitlistCount()).isEqualTo((int) waiting);
            assertThat(registered + waiting).isEqualTo(10);
        }
    }
}
