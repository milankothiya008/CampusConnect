package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Port of tests/integration/eventEdits.test.js. */
class EventEditsIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser otherFaculty;
    TestUser president;
    TestUser coordinator;
    TestUser member;
    TestUser student;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty("CE", "Dr. Mentor");
        otherFaculty = data.faculty("CE", "Dr. Other");
        president = data.student(spec -> spec.name("President"));
        coordinator = data.student(spec -> spec.name("Coordinator"));
        member = data.student(spec -> spec.name("Member"));
        student = data.student(spec -> spec.name("Registered Student"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, member, "MEMBER");
    }

    @Nested
    class EditingEvents {

        @Test
        void draftsCanChangeEveryDetailIncludingVenueAndSchedule() {
            String id = flows.draft(president, club, venues.auditorium());
            Response res = api.as(president).put("/api/events/" + id, map(
                    "title", "Hack Night 2.0",
                    "venue", venues.hall().getId(),
                    "eventDate", TestData.futureDate(12),
                    "startTime", "14:00",
                    "endTime", "18:00",
                    "category", "COMPETITION",
                    "maxParticipants", 40,
                    "eligibility", map("departments", List.of("CE"), "batches", List.of("24"))));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Event updated");
            assertThat(res.string("$.data.title")).isEqualTo("Hack Night 2.0");
            assertThat(res.string("$.data.status")).isEqualTo("DRAFT");
            assertThat(res.string("$.data.startTime")).isEqualTo("14:00");
            assertThat(res.string("$.data.category")).isEqualTo("COMPETITION");
            assertThat((Integer) res.at("$.data.maxParticipants")).isEqualTo(40);
            assertThat(res.string("$.data.venue._id")).isEqualTo(venues.hall().getId());
        }

        @Test
        void editingAnApprovedNotYetPublicEventSendsItBackToTheMentor() {
            String id = flows.approved(president, mentor, club, venues.auditorium(), "title", "Approved Talk", "eventDate", TestData.futureDate(14));
            Response res = api.as(president).put("/api/events/" + id, map("title", "Approved Talk (moved)", "startTime", "11:00", "endTime", "12:30"));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Changes saved and sent to your faculty mentor for approval");
            assertThat(res.string("$.data.status")).isEqualTo("PENDING_APPROVAL");
            assertThat(res.string("$.data.title")).isEqualTo("Approved Talk (moved)");
            assertThat(res.string("$.data.startTime")).isEqualTo("11:00");
            assertThat(flows.notified(mentor, "EVENT_CHANGES_REVIEW", "\"Approved Talk (moved)\" was changed after approval")).isTrue();

            // While it waits for review it cannot be edited again.
            Response locked = api.as(president).put("/api/events/" + id, map("title", "Another title"));
            assertThat(locked.status()).isEqualTo(409);
            assertThat(locked.message()).contains("with your faculty mentor for review");
        }
    }

    @Nested
    class PublishedEvents {

        String id;

        @BeforeEach
        void publishedWithAStudent() {
            id = flows.published(president, mentor, club, venues.auditorium(), "title", "Hack Night", "eventDate", TestData.futureDate(20), "maxParticipants", 50);
            assertThat(flows.register(student, id).status()).isEqualTo(201);
        }

        /** The first edit of the original test: a new title, venue, date, times and deadline, with a note. */
        Response winterEdition() {
            return api.as(president).put("/api/events/" + id, map(
                    "title", "Hack Night: Winter Edition",
                    "venue", venues.hall().getId(),
                    "eventDate", TestData.futureDate(21),
                    "startTime", "16:00",
                    "endTime", "20:00",
                    "registrationEnd", EventFlows.fromNow(Duration.ofDays(19)),
                    "updateNote", "We moved to a smaller hall with better Wi-Fi."));
        }

        /** The mentor sends the edit back; the club resubmits only a new title and venue. */
        Response resubmittedEdit() {
            winterEdition();
            api.as(mentor).post("/api/events/" + id + "/changes/request-changes", map("comment", "Keep the original date, just change the venue."));
            return api.as(president).put("/api/events/" + id, map(
                    "title", "Hack Night: Winter Edition",
                    "venue", venues.hall().getId(),
                    "eventDate", TestData.futureDate(20),
                    "startTime", "10:00",
                    "endTime", "13:00",
                    "updateNote", "We moved to a smaller hall with better Wi-Fi."));
        }

        void publishedEdit() {
            resubmittedEdit();
            api.as(mentor).post("/api/events/" + id + "/changes/approve", map("comment", "Looks good"));
            assertThat(api.as(president).post("/api/events/" + id + "/changes/publish").status()).isEqualTo(200);
        }

        @Test
        void changesWaitForTheMentorWhileTheLiveEventStaysAsItIs() {
            Response res = winterEdition();
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).contains("sent to your faculty mentor for approval");
            assertThat(res.string("$.data.title")).isEqualTo("Hack Night");
            assertThat(res.string("$.data.status")).isEqualTo("PUBLISHED");
            assertThat(res.string("$.data.startTime")).isEqualTo("10:00");
            assertThat(res.string("$.data.revision.status")).isEqualTo("PENDING_APPROVAL");
            assertThat(res.string("$.data.revision.note")).isEqualTo("We moved to a smaller hall with better Wi-Fi.");
            assertThat((List<String>) res.at("$.data.revision.fields")).containsExactlyInAnyOrder("endTime", "eventDate", "registrationEnd", "startTime", "title", "venue");
            List<Map<String, Object>> diff = res.at("$.data.revision.diff[?(@.field == 'venue')]");
            assertThat(((Map<?, ?>) diff.get(0).get("from")).get("name")).isEqualTo("Auditorium");
            assertThat(((Map<?, ?>) diff.get(0).get("to")).get("name")).isEqualTo("Seminar Hall A");

            // Students see only the live event, never the pending changes.
            Response publicView = api.as(student).get("/api/events/" + id);
            assertThat(publicView.string("$.data.title")).isEqualTo("Hack Night");
            assertThat(publicView.has("$.data.revision")).isFalse();
            Response feed = api.as(student).get("/api/events?timeframe=upcoming");
            List<Map<String, Object>> card = feed.at("$.data[?(@._id == '" + id + "')]");
            assertThat(card.get(0)).doesNotContainKey("revision");

            assertThat(flows.notified(mentor, "EVENT_CHANGES_REVIEW", "Changes to \"Hack Night\" need your approval")).isTrue();
        }

        @Test
        void theMentorFindsTheEditInTheirReviewQueue() {
            winterEdition();
            Response queue = api.as(mentor).get("/api/events/manage?status=PENDING_APPROVAL");
            List<Map<String, Object>> row = queue.at("$.data[?(@._id == '" + id + "')]");
            assertThat(row.get(0)).containsEntry("status", "PUBLISHED").containsEntry("revisionStatus", "PENDING_APPROVAL");

            Response detail = api.as(mentor).get("/api/events/" + id);
            assertThat((Boolean) detail.at("$.data.viewer.canReviewChanges")).isTrue();
            assertThat((Boolean) detail.at("$.data.viewer.canReview")).isFalse();
        }

        @Test
        void onlyTheClubsMentorReviewsChangesAndNothingCanBePublishedBeforeApproval() {
            winterEdition();
            assertThat(api.as(otherFaculty).post("/api/events/" + id + "/changes/approve").status()).isEqualTo(403);
            assertThat(api.as(president).post("/api/events/" + id + "/changes/approve").status()).isEqualTo(403);
            Response early = api.as(president).post("/api/events/" + id + "/changes/publish");
            assertThat(early.status()).isEqualTo(409);
            assertThat(early.message()).isEqualTo("Only changes approved by the faculty mentor can be published");
        }

        @Test
        void theMentorCanSendAnEditBackEditingAgainResubmitsIt() {
            winterEdition();
            Response sentBack = api.as(mentor).post("/api/events/" + id + "/changes/request-changes", map("comment", "Keep the original date, just change the venue."));
            assertThat(sentBack.string("$.data.revision.status")).isEqualTo("NEEDS_CHANGES");
            assertThat(sentBack.string("$.data.revision.reviewComment")).isEqualTo("Keep the original date, just change the venue.");
            assertThat(flows.notified(president, "EVENT_CHANGES_REQUESTED")).isTrue();

            Response again = api.as(president).put("/api/events/" + id, map(
                    "title", "Hack Night: Winter Edition",
                    "venue", venues.hall().getId(),
                    "eventDate", TestData.futureDate(20),
                    "startTime", "10:00",
                    "endTime", "13:00",
                    "updateNote", "We moved to a smaller hall with better Wi-Fi."));
            assertThat(again.string("$.data.revision.status")).isEqualTo("PENDING_APPROVAL");
            assertThat((List<String>) again.at("$.data.revision.fields")).containsExactlyInAnyOrder("title", "venue");
        }

        @Test
        void onceApprovedTheClubPublishesTheChangesAndRegisteredStudentsAreTold() {
            resubmittedEdit();
            Response approved = api.as(mentor).post("/api/events/" + id + "/changes/approve", map("comment", "Looks good"));
            assertThat(approved.string("$.data.revision.status")).isEqualTo("APPROVED");
            assertThat(approved.string("$.data.title")).isEqualTo("Hack Night");
            assertThat(flows.notified(president, "EVENT_APPROVED", "Changes to \"Hack Night\" were approved")).isTrue();

            Response presidentView = api.as(president).get("/api/events/" + id);
            assertThat((Boolean) presidentView.at("$.data.viewer.canPublishChanges")).isTrue();
            assertThat(api.as(coordinator).post("/api/events/" + id + "/changes/publish").status()).isEqualTo(403);

            Response published = api.as(president).post("/api/events/" + id + "/changes/publish");
            assertThat(published.status()).isEqualTo(200);
            assertThat(published.string("$.data.title")).isEqualTo("Hack Night: Winter Edition");
            assertThat(published.string("$.data.status")).isEqualTo("PUBLISHED");
            assertThat(published.string("$.data.venue._id")).isEqualTo(venues.hall().getId());
            assertThat(published.has("$.data.revision")).isFalse();
            assertThat(flows.event(id).getRevision()).isNull();

            Notification notice = mongo.findOne(Query.query(Criteria.where("user").is(new ObjectId(student.id())).and("type").is("EVENT_UPDATED")), Notification.class);
            assertThat(notice.getTitle()).isEqualTo("Hack Night: Winter Edition has been updated");
            assertThat(notice.getMessage()).isEqualTo("Updated: title and venue. We moved to a smaller hall with better Wi-Fi.");
            assertThat(mongo.exists(Query.query(Criteria.where("event").is(new ObjectId(id)).and("type").is("EVENT_UPDATE").and("title").is("Event details updated")),
                    FeedPost.class)).isTrue();
        }

        @Test
        void aRejectedEditLeavesTheEventUnchangedAndTheClubCanDiscardIt() {
            publishedEdit();
            api.as(president).put("/api/events/" + id, map("shortDescription", "A totally different description of the evening."));
            Response rejected = api.as(mentor).post("/api/events/" + id + "/changes/reject", map("reason", "The current description is clearer."));
            assertThat(rejected.string("$.data.revision.status")).isEqualTo("REJECTED");
            assertThat(rejected.string("$.data.revision.reviewComment")).isEqualTo("The current description is clearer.");
            assertThat(rejected.string("$.data.shortDescription")).isEqualTo("An evening of building things together.");

            Response discarded = api.as(president).delete("/api/events/" + id + "/changes");
            assertThat(discarded.status()).isEqualTo(200);
            assertThat(discarded.has("$.data.revision")).isFalse();
        }

        @Test
        void revertingEveryChangeClearsThePendingEdit() {
            api.as(president).put("/api/events/" + id, map("rules", "New rules"));
            assertThat(flows.event(id).getRevision().getStatus().name()).isEqualTo("PENDING_APPROVAL");
            api.as(president).put("/api/events/" + id, map("rules", "Bring your own laptop."));
            assertThat(flows.event(id).getRevision()).isNull();
        }

        @Test
        void proposedChangesAreValidatedLikeANewEvent() {
            String clash = flows.published(president, mentor, club, venues.auditorium(), "title", "Other Event", "eventDate", TestData.futureDate(30));
            assertThat(clash).isNotBlank();
            Response conflict = api.as(president).put("/api/events/" + id, map(
                    "venue", venues.auditorium().getId(),
                    "eventDate", TestData.futureDate(30),
                    "startTime", "10:00",
                    "endTime", "13:00",
                    "registrationEnd", EventFlows.fromNow(Duration.ofDays(25))));
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.errorCode()).isEqualTo("VENUE_CONFLICT");

            assertThat(api.as(president).put("/api/events/" + id, map("maxParticipants", 0)).status()).isEqualTo(400);
            assertThat(api.as(president).put("/api/events/" + id, map("eventDate", "2020-01-01")).status()).isEqualTo(400);

            Response toTeams = api.as(president).put("/api/events/" + id, map("participationMode", "TEAM", "minTeamSize", 2, "maxTeamSize", 4));
            assertThat(toTeams.status()).isEqualTo(409);
            assertThat(toTeams.message()).isEqualTo("Team settings can't change once students have registered");
        }

        @Test
        void nothingCanBeEditedOnceTheEventHasStarted() {
            flows.times(id, Duration.ofDays(-1), Duration.ofHours(-2), Duration.ofHours(-1), Duration.ofHours(1));
            Response res = api.as(president).put("/api/events/" + id, map("rules", "Late change"));
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.message()).isEqualTo("This event has already started, so its details can no longer be changed");
            assertThat((Boolean) api.as(president).get("/api/events/" + id).at("$.data.viewer.canEdit")).isFalse();
        }
    }

    @Nested
    class SavingAnUnchangedForm {

        @Test
        void timesThatDifferOnlyInSecondsAndReorderedListsAreNotReportedAsChanges() {
            String id = flows.draft(president, club, venues.auditorium(),
                    "eligibility", map("departments", List.of("IT", "CE"), "batches", List.of()),
                    "registrationEnd", Instant.now().plus(Duration.ofDays(8)).plusMillis(17123).toString());
            api.as(president).post("/api/events/" + id + "/submit");
            api.as(mentor).post("/api/events/" + id + "/approve");
            api.as(president).post("/api/events/" + id + "/publish");

            Event stored = flows.event(id);
            String minute = Instant.ofEpochMilli(Math.floorDiv(stored.getRegistrationEnd().toEpochMilli(), 60_000) * 60_000).toString();
            Response res = api.as(president).put("/api/events/" + id, map("registrationEnd", minute,
                    "eligibility", map("departments", List.of("CE", "IT"), "batches", List.of()), "title", stored.getTitle()));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Event updated");
            assertThat(flows.event(id).getRevision()).isNull();

            // Moving the start time re-checks the schedule; the untouched registration times still don't count as changes.
            String openedMinute = Instant.ofEpochMilli(Math.floorDiv(stored.getRegistrationStart().toEpochMilli(), 60_000) * 60_000).toString();
            Response moved = api.as(president).put("/api/events/" + id, map("startTime", "11:00", "registrationStart", openedMinute, "registrationEnd", minute));
            assertThat((List<String>) moved.at("$.data.revision.fields")).containsExactly("startTime");
        }
    }
}
