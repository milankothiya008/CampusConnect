package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static com.campusconnect.feature.event.EventFlows.payload;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.audit.AuditLog;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.feature.venue.VenueService;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Port of tests/integration/events.test.js. */
class EventLifecycleIntegrationTest extends IntegrationTest {

    @Autowired
    VenueService venueService;

    TestData.Venues venues;
    EventFlows flows;

    TestUser student(String name) {
        return data.student(spec -> spec.name(name));
    }

    @Nested
    class EventLifecycle {

        TestUser mentor;
        TestUser otherFaculty;
        TestUser admin;
        TestUser president;
        TestUser coordinator;
        TestUser member;
        TestUser outsider;
        TestUser otherPresident;
        Club club;
        Club otherClub;

        @BeforeEach
        void seed() {
            venues = data.seedReferenceData();
            flows = new EventFlows(api, mongo);
            mentor = data.faculty("CE", "Mentor");
            otherFaculty = data.faculty("CE", "Other Faculty");
            admin = data.admin();
            president = student("President");
            coordinator = student("Event Coordinator");
            member = student("Member");
            outsider = student("Outsider");
            otherPresident = student("Other President");
            club = data.activeClub("Coding Club", mentor, president);
            otherClub = data.activeClub("Drama Club", otherFaculty, otherPresident);
            data.addMembership(club, coordinator, "EVENT_COORDINATOR");
            data.addMembership(club, member, "MEMBER");
        }

        /** The coordinator's "Hack Night" draft, open to CE and IT students of batch 24. */
        String hackNightDraft() {
            Response res = api.as(coordinator).post("/api/events",
                    payload(club, venues.auditorium(), "eligibility", map("departments", List.of("ce", "IT"), "batches", List.of("24"))));
            assertThat(res.status()).as(res.body()).isEqualTo(201);
            return res.string("$.data._id");
        }

        String hackNightSubmitted() {
            String id = hackNightDraft();
            assertThat(api.as(president).post("/api/events/" + id + "/submit").status()).isEqualTo(200);
            return id;
        }

        /** Sent back once, moved to Seminar Hall A with 40 places, resubmitted and approved. */
        String hackNightApproved() {
            String id = hackNightSubmitted();
            api.as(mentor).post("/api/events/" + id + "/request-changes", map("comment", "Please move this to Seminar Hall A and cap it at 40."));
            api.as(coordinator).put("/api/events/" + id, map("venue", venues.hall().getId(), "maxParticipants", 40));
            api.as(president).post("/api/events/" + id + "/submit");
            assertThat(api.as(mentor).post("/api/events/" + id + "/approve", map("comment", "Good to go")).string("$.data.status")).isEqualTo("APPROVED");
            return id;
        }

        String hackNightPublished() {
            String id = hackNightApproved();
            assertThat(api.as(president).post("/api/events/" + id + "/publish").status()).isEqualTo(200);
            return id;
        }

        @Test
        void plainMembersOutsidersAndOtherClubsCannotCreateEventsForTheClub() {
            Map<String, Object> body = payload(club, venues.auditorium());
            assertThat(api.as(member).post("/api/events", body).status()).isEqualTo(403);
            assertThat(api.as(outsider).post("/api/events", body).status()).isEqualTo(403);
            assertThat(api.as(otherPresident).post("/api/events", body).status()).isEqualTo(403);
        }

        @Test
        void validatesScheduleDeadlineAndCapacity() {
            Response badDeadline = flows.create(president, club, venues.auditorium(), "registrationEnd", EventFlows.fromNow(Duration.ofDays(30)));
            assertThat(badDeadline.status()).isEqualTo(400);

            Response tooBig = flows.create(president, club, venues.hall(), "maxParticipants", 500);
            assertThat(tooBig.status()).isEqualTo(400);
            assertThat(tooBig.message()).contains("capacity");

            Response backwards = flows.create(president, club, venues.auditorium(), "startTime", "14:00", "endTime", "12:00");
            assertThat(backwards.status()).isEqualTo(400);
        }

        @Test
        void anEventCoordinatorCreatesADraftWithRulesContactAndEligibility() {
            Response res = api.as(coordinator).post("/api/events",
                    payload(club, venues.auditorium(), "eligibility", map("departments", List.of("ce", "IT"), "batches", List.of("24"))));
            assertThat(res.status()).isEqualTo(201);
            assertThat(res.string("$.data.status")).isEqualTo("DRAFT");
            assertThat((List<String>) res.at("$.data.eligibility.departments")).containsExactly("CE", "IT");
            assertThat(res.string("$.data.startAt")).contains("T04:30:00");
            assertThat(res.message()).isEqualTo("Event draft created");
        }

        @Test
        void draftsAreNotPubliclyVisible() {
            String id = hackNightDraft();
            assertThat(api.get("/api/events/" + id).status()).isEqualTo(404);
            assertThat(api.as(member).get("/api/events/" + id).status()).isEqualTo(404);
            List<String> ids = api.get("/api/events").at("$.data[*]._id");
            assertThat(ids).doesNotContain(id);
        }

        @Test
        void submitsToTheMentorOnlyTheClubsMentorOrAnAdminCanReview() {
            String id = hackNightDraft();
            Response res = api.as(president).post("/api/events/" + id + "/submit");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("PENDING_APPROVAL");
            assertThat(flows.notified(mentor, "EVENT_REVIEW")).isTrue();

            assertThat(api.as(otherFaculty).post("/api/events/" + id + "/approve").status()).isEqualTo(403);
            assertThat(api.as(president).post("/api/events/" + id + "/approve").status()).isEqualTo(403);
            assertThat(api.as(president).put("/api/events/" + id, map("title", "Changed while pending")).status()).isEqualTo(409);
        }

        @Test
        void mentorRequestsChangesTheClubEditsAndResubmits() {
            String id = hackNightSubmitted();
            Response changes = api.as(mentor).post("/api/events/" + id + "/request-changes", map("comment", "Please move this to Seminar Hall A and cap it at 40."));
            assertThat(changes.string("$.data.status")).isEqualTo("NEEDS_CHANGES");
            assertThat(changes.string("$.data.reviewComment")).contains("Seminar Hall A");

            Response edit = api.as(coordinator).put("/api/events/" + id, map("venue", venues.hall().getId(), "maxParticipants", 40));
            assertThat(edit.status()).isEqualTo(200);
            assertThat(edit.string("$.data.venue.name")).isEqualTo("Seminar Hall A");

            Response resubmit = api.as(president).post("/api/events/" + id + "/submit");
            assertThat(resubmit.string("$.data.status")).isEqualTo("PENDING_APPROVAL");
        }

        @Test
        void mentorApprovesTheEventIsStillNotPublicUntilPublished() {
            String id = hackNightApproved();
            assertThat(api.as(outsider).get("/api/events/" + id).status()).isEqualTo(404);
        }

        @Test
        void venueConflictsAreDetectedAgainstApprovedPublishedEventsWithAClearMessage() {
            hackNightApproved();
            Response res = flows.create(otherPresident, otherClub, venues.hall(), "title", "Drama Rehearsal", "startTime", "11:00", "endTime", "14:00", "maxParticipants", 30);
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.errorCode()).isEqualTo("VENUE_CONFLICT");
            assertThat(res.message()).contains("Seminar Hall A is already booked 10:00–13:00 for \"Hack Night\"");
            assertThat(res.string("$.details.conflicts[0].title")).isEqualTo("Hack Night");

            Response backToBack = flows.create(otherPresident, otherClub, venues.hall(), "title", "Drama Evening", "startTime", "13:00", "endTime", "15:00", "maxParticipants", 30);
            assertThat(backToBack.status()).isEqualTo(201);
        }

        @Test
        void aSubmittedEventReservesItsVenueUntilTheMentorSendsItBack() {
            String drama = flows.draft(otherPresident, otherClub, venues.auditorium(), "title", "Drama Showcase", "startTime", "09:00", "endTime", "11:00", "maxParticipants", 100);

            // A draft alone does not hold the venue...
            String rival = flows.draft(president, club, venues.auditorium(), "title", "Morning Talk", "startTime", "10:30", "endTime", "12:00", "maxParticipants", 100);

            // ...but submitting does, so the first club to submit gets the slot.
            assertThat(api.as(otherPresident).post("/api/events/" + drama + "/submit").status()).isEqualTo(200);
            Response blocked = api.as(president).post("/api/events/" + rival + "/submit");
            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blocked.errorCode()).isEqualTo("VENUE_CONFLICT");
            assertThat(blocked.message()).isEqualTo(
                    "Auditorium is already requested 09:00–11:00 for \"Drama Showcase\" (Drama Club), which is awaiting faculty approval. Choose another venue or time.");

            // New drafts for that slot are warned straight away, and the picker says who holds the venue.
            Response again = flows.create(president, club, venues.auditorium(), "title", "Another Talk", "startTime", "10:00", "endTime", "10:30", "maxParticipants", 100);
            assertThat(again.errorCode()).isEqualTo("VENUE_CONFLICT");

            String eventDate = (String) payload(club, venues.auditorium()).get("eventDate");
            List<Map<String, Object>> slot = venueService.getAvailableVenues(eventDate, null, "10:30", "12:00", null, null, null);
            Map<String, Object> auditorium = slot.stream().filter(venue -> venues.auditorium().getId().equals(venue.get("_id"))).findFirst().orElseThrow();
            assertThat(auditorium.get("available")).isEqualTo(false);
            assertThat(auditorium.get("bookedBy")).isEqualTo(List.of(map("title", "Drama Showcase", "club", "Drama Club", "startTime", "09:00", "endTime", "11:00",
                    "pendingApproval", true)));

            // Sending the request back for changes frees the venue for others.
            api.as(otherFaculty).post("/api/events/" + drama + "/request-changes", map("comment", "Please pick another slot."));
            assertThat(api.as(president).post("/api/events/" + rival + "/submit").status()).isEqualTo(200);
            assertThat(api.as(mentor).post("/api/events/" + rival + "/approve").status()).isEqualTo(200);

            Response resubmit = api.as(otherPresident).post("/api/events/" + drama + "/submit");
            assertThat(resubmit.status()).isEqualTo(409);
            assertThat(resubmit.errorCode()).isEqualTo("VENUE_CONFLICT");
        }

        @Test
        void twoClubsSubmittingTheSameSlotAtTheSameMomentOnlyOneGetsTheVenue() throws Exception {
            String eventDate = TestData.futureDate(30);
            String first = flows.draft(president, club, venues.auditorium(), "title", "Race A", "eventDate", eventDate, "maxParticipants", 100);
            String second = flows.draft(otherPresident, otherClub, venues.auditorium(), "title", "Race B", "eventDate", eventDate, "startTime", "11:00", "endTime", "12:00",
                    "maxParticipants", 100);

            List<Callable<Response>> submissions = List.of(
                    () -> api.as(president).post("/api/events/" + first + "/submit"),
                    () -> api.as(otherPresident).post("/api/events/" + second + "/submit"));
            List<Integer> statuses = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (Future<Response> result : pool.invokeAll(submissions)) {
                    statuses.add(result.get().status());
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(mongo.count(Query.query(Criteria.where("title").regex("^Race ").and("status").is("PENDING_APPROVAL")), Event.class)).isEqualTo(1);
        }

        @Test
        void rejectsPastDatesAndRegistrationDeadlinesThatHaveAlreadyPassed() {
            String yesterday = LocalDate.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC).toString();
            Response past = flows.create(president, club, venues.hall(), "eventDate", yesterday, "registrationEnd", EventFlows.fromNow(Duration.ofDays(-2)));
            assertThat(past.status()).isEqualTo(400);
            assertThat(past.message()).isEqualTo("Event must be scheduled in the future");

            Response closedDeadline = flows.create(president, club, venues.hall(), "eventDate", TestData.futureDate(12),
                    "registrationStart", EventFlows.fromNow(Duration.ofDays(-3)), "registrationEnd", EventFlows.fromNow(Duration.ofHours(-1)));
            assertThat(closedDeadline.status()).isEqualTo(400);
            assertThat(closedDeadline.message()).isEqualTo("Registration deadline must be in the future");

            // A draft whose deadline slipped by while it sat unsubmitted must be fixed before submission.
            String draft = flows.draft(president, club, venues.hall(), "title", "Stale Draft", "eventDate", TestData.futureDate(12));
            flows.times(draft, Duration.ofHours(-2), Duration.ofHours(-1), null, null);
            Response submit = api.as(president).post("/api/events/" + draft + "/submit");
            assertThat(submit.status()).isEqualTo(409);
            assertThat(submit.message()).contains("registration deadline has passed");
        }

        @Test
        void onlyRolesWithPublishEventsCanPublishPublishingShowsItInTheEventFeedAndNotifiesThePeopleItsFor() {
            String id = hackNightApproved();
            assertThat(api.as(coordinator).post("/api/events/" + id + "/publish").status()).isEqualTo(403);

            Response res = api.as(president).post("/api/events/" + id + "/publish");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("PUBLISHED");

            assertThat(mongo.exists(Query.query(Criteria.where("event").is(new ObjectId(id)).and("type").is("EVENT")), FeedPost.class)).isFalse();
            // The club's members and the students it's open to hear about it; nobody else on campus is notified.
            for (TestUser user : List.of(member, outsider)) {
                assertThat(flows.notified(user, "EVENT_PUBLISHED")).as(user.name()).isTrue();
            }
            for (TestUser user : List.of(otherFaculty, admin)) {
                assertThat(flows.notified(user, "EVENT_PUBLISHED")).as(user.name()).isFalse();
            }
            assertThat(flows.notified(president, "EVENT_PUBLISHED")).isFalse();

            Response feed = api.as(outsider).get("/api/events?timeframe=upcoming&withCounts=true");
            assertThat((List<String>) feed.at("$.data[*].title")).contains("Hack Night");
            assertThat((Map<String, Object>) feed.at("$.meta.counts")).containsEntry("upcoming", 1).containsEntry("ongoing", 0).containsEntry("past", 0);
            assertThat(api.as(outsider).get("/api/events/" + id).status()).isEqualTo(200);
        }

        @Test
        void anUpdateNoteOnAPublishedEventIsPostedStraightToTheEventPage() {
            String id = hackNightPublished();
            Response res = api.as(president).put("/api/events/" + id, map("updateNote", "Please bring your charger!"));
            assertThat(res.status()).isEqualTo(200);
            assertThat(mongo.exists(Query.query(Criteria.where("event").is(new ObjectId(id)).and("type").is("EVENT_UPDATE")), FeedPost.class)).isTrue();
        }

        @Test
        void discoveryListsUpcomingEventsWithFiltersAndSearch() {
            hackNightPublished();
            Response upcoming = api.as(outsider).get("/api/events?timeframe=upcoming&category=TECHNOLOGY&search=hack");
            assertThat((List<String>) upcoming.at("$.data[*].title")).containsExactly("Hack Night");
            assertThat(upcoming.string("$.data[0].registrationState")).isEqualTo("OPEN");
            assertThat((Integer) upcoming.at("$.meta.total")).isEqualTo(1);

            Response past = api.as(outsider).get("/api/events?timeframe=past");
            assertThat((List<?>) past.at("$.data")).isEmpty();
        }

        @Test
        void mentorSeesPendingWorkInTheManagedListTheAdminAndOutsidersSeeNothing() {
            hackNightPublished();
            flows.approved(president, mentor, club, venues.auditorium(), "title", "Morning Talk", "startTime", "10:30", "endTime", "12:00", "maxParticipants", 100);
            List<String> mine = api.as(mentor).get("/api/events/manage?status=APPROVED,PUBLISHED").at("$.data[*].title");
            assertThat(mine).containsExactlyInAnyOrder("Hack Night", "Morning Talk");
            // The university admin has no club authority, so there is nothing for them to manage.
            assertThat((List<?>) api.as(admin).get("/api/events/manage").at("$.data")).isEmpty();
            assertThat((List<?>) api.as(outsider).get("/api/events/manage").at("$.data")).isEmpty();
        }

        @Test
        void rejectionIsFinalCancellingAPublishedEventRequiresAReason() {
            String id = hackNightPublished();
            String draft = flows.draft(president, club, venues.auditorium(), "title", "Rejected Idea", "eventDate", TestData.futureDate(20), "maxParticipants", 10);
            api.as(president).post("/api/events/" + draft + "/submit");
            Response rejected = api.as(mentor).post("/api/events/" + draft + "/reject", map("reason", "Not suitable for campus."));
            assertThat(rejected.string("$.data.status")).isEqualTo("REJECTED");
            assertThat(api.as(president).post("/api/events/" + draft + "/submit").status()).isEqualTo(409);

            assertThat(api.as(president).post("/api/events/" + id + "/cancel").status()).isEqualTo(400);
        }

        @Test
        void anEventCanOnlyBeCompletedAfterItStarts() {
            String id = hackNightPublished();
            assertThat(api.as(president).post("/api/events/" + id + "/complete").status()).isEqualTo(409);

            flows.times(id, Duration.ofHours(-5), Duration.ofHours(-4), Duration.ofHours(-3), Duration.ofHours(-1));
            Response res = api.as(coordinator).post("/api/events/" + id + "/complete");
            assertThat(res.string("$.data.status")).isEqualTo("COMPLETED");
            assertThat(mongo.count(Query.query(Criteria.where("targetId").is(new ObjectId(id))), AuditLog.class)).isGreaterThanOrEqualTo(8);
        }
    }

    @Nested
    class RequestsWithoutABody {

        @Test
        void approvingWithNoCommentAndNoJsonBodyWorks() {
            venues = data.seedReferenceData();
            flows = new EventFlows(api, mongo);
            TestUser mentor = data.faculty();
            TestUser president = data.student();
            Club club = data.activeClub("No Body Club", mentor, president);
            String draft = flows.draft(president, club, venues.auditorium(), "eventDate", TestData.futureDate(40), "maxParticipants", 20);
            api.as(president).post("/api/events/" + draft + "/submit");

            Response res = api.as(mentor).post("/api/events/" + draft + "/approve");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("APPROVED");
        }
    }
}
