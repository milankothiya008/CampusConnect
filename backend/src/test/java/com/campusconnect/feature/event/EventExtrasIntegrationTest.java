package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.feature.feedback.FeedbackService;
import com.campusconnect.feature.venue.VenueService;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/eventExtras.test.js: multi-day events, reminders and feedback. The "participation record,
 * people and photos" tests (record PDF, people search, profile photo, push keys) belong to the users and push features.
 */
class EventExtrasIntegrationTest extends IntegrationTest {

    @Autowired
    VenueService venueService;

    @Autowired
    EventReminders reminders;

    @Autowired
    FeedbackService feedback;

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser coordinator;
    TestUser plainMember;
    TestUser asha;
    TestUser bina;
    TestUser chirag;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(spec -> spec.name("President"));
        coordinator = data.student(spec -> spec.name("Coordinator"));
        plainMember = data.student(spec -> spec.name("Plain Member"));
        asha = data.student(spec -> spec.name("Asha Patel"));
        bina = data.student(spec -> spec.name("Bina Shah"));
        chirag = data.student(spec -> spec.name("Chirag Rao").department("IT"));
        club = data.activeClub("Coding Club", mentor, president);
        // The president gives the event coordinator the reminder authority.
        List<ClubRole> roles = new ArrayList<>(ClubRoles.defaultRoles());
        roles.stream().filter(role -> role.getKey().equals("EVENT_COORDINATOR")).forEach(role -> role.getPermissions().add("SEND_REMINDERS"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(club.getId()))), new Update().set("roles", roles), Club.class);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, plainMember, "MEMBER");
    }

    @Nested
    class MultiDayEvents {

        @Test
        void anEventCantEndBeforeItStartsOrRunLongerThanAWeek() {
            Response backwards = flows.create(president, club, venues.hall(), "eventDate", TestData.futureDate(10), "endDate", TestData.futureDate(9));
            assertThat(backwards.status()).isEqualTo(400);
            Response tooLong = flows.create(president, club, venues.hall(), "eventDate", TestData.futureDate(10), "endDate", TestData.futureDate(18));
            assertThat(tooLong.message()).contains("at most 7 days");
        }

        @Test
        void aTwoDayEventHoldsItsVenueAcrossBothDays() {
            Response fest = flows.create(president, club, venues.hall(), "title", "Tech Fest", "eventDate", TestData.futureDate(20), "endDate", TestData.futureDate(21),
                    "startTime", "09:00", "endTime", "17:00");
            assertThat(fest.status()).isEqualTo(201);
            api.as(president).post("/api/events/" + fest.string("$.data._id") + "/submit");
            Response clash = flows.create(president, club, venues.hall(), "title", "Morning talk", "eventDate", TestData.futureDate(21), "startTime", "10:00", "endTime", "11:00");
            assertThat(clash.status()).isEqualTo(409);
            List<Map<String, Object>> free = venueService.getAvailableVenues(TestData.futureDate(20), TestData.futureDate(21), "09:00", "17:00", null, null, null);
            assertThat(free.stream().filter(venue -> "Seminar Hall A".equals(venue.get("name"))).findFirst().orElseThrow().get("available")).isEqualTo(false);
            assertThat(free.stream().filter(venue -> "Auditorium".equals(venue.get("name"))).findFirst().orElseThrow().get("available")).isEqualTo(true);
        }
    }

    /** "Hack Night" for CE students, published, with Asha registered. */
    String hackNight() {
        String id = flows.draft(president, club, venues.auditorium(), "title", "Hack Night", "eventDate", TestData.futureDate(5),
                "registrationEnd", EventFlows.fromNow(Duration.ofHours(72)), "eligibility", map("departments", List.of("CE")));
        flows.markPublished(id);
        assertThat(flows.register(asha, id).status()).isEqualTo(201);
        return id;
    }

    @Nested
    class Reminders {

        String eventId;

        @BeforeEach
        void publishHackNight() {
            eventId = hackNight();
        }

        @Test
        void theButtonIsThereOnlyForRolesWithTheReminderAuthority() {
            Response view = api.as(president).get("/api/events/" + eventId);
            assertThat((Boolean) view.at("$.data.viewer.canSendReminders")).isTrue();
            assertThat((Boolean) view.at("$.data.viewer.reminders.REGISTRATION_CLOSING.available")).isTrue();
            assertThat(api.as(plainMember).get("/api/events/" + eventId).has("$.data.viewer.reminders")).isFalse();
            assertThat(api.as(plainMember).post("/api/events/" + eventId + "/reminders", map("kind", "EVENT_STARTING")).status()).isEqualTo(403);
            assertThat(api.as(asha).get("/api/events/" + eventId).has("$.data.remindersSent")).isFalse();
        }

        @Test
        void registrationClosingReachesEligibleStudentsWhoHaventRegisteredOnceEveryFewHours() {
            Response sent = api.as(president).post("/api/events/" + eventId + "/reminders", map("kind", "REGISTRATION_CLOSING", "note", "Only 10 seats left!"));
            assertThat(sent.status()).isEqualTo(200);
            List<String> notified = mongo.findDistinct(Query.query(Criteria.where("type").is("EVENT_REMINDER").and("title").regex("Registration closes")),
                    "user", "notifications", ObjectId.class).stream().map(ObjectId::toHexString).toList();
            assertThat(notified).contains(bina.id());
            // Already registered, and an IT student for a CE event.
            assertThat(notified).doesNotContain(asha.id(), chirag.id());
            assertThat(data.mailsTo(bina.email()).stream().anyMatch(mail -> mail.text().contains("Only 10 seats left!"))).isTrue();

            Response again = api.as(president).post("/api/events/" + eventId + "/reminders", map("kind", "REGISTRATION_CLOSING"));
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.message()).contains("you can send another after");
        }

        @Test
        void aRoleGivenTheAuthoritySendsStartingSoonToEveryoneRegistered() {
            Response sent = api.as(coordinator).post("/api/events/" + eventId + "/reminders", map("kind", "EVENT_STARTING"));
            assertThat(sent.status()).isEqualTo(200);
            assertThat((Integer) sent.at("$.data.recipients")).isEqualTo(1);
            assertThat(sent.message()).isEqualTo("Reminder sent to 1 student");
            assertThat(flows.countTitled(asha, "Starts in")).isEqualTo(1);
        }

        @Test
        void registeredStudentsAreRemindedAutomaticallyADayBeforeAndAnHourBeforeOnceEach() {
            flows.times(eventId, null, Duration.ofHours(20), Duration.ofMinutes(23 * 60 + 30), Duration.ofHours(26));
            assertThat(reminders.sweepEventReminders()).isEqualTo(1);
            assertThat(reminders.sweepEventReminders()).isZero();
            assertThat(flows.countTitled(asha, "^Tomorrow:")).isEqualTo(1);

            flows.times(eventId, null, Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3));
            assertThat(reminders.sweepEventReminders()).isEqualTo(1);
            assertThat(flows.countTitled(asha, "^Starting soon:")).isEqualTo(1);
        }
    }

    @Nested
    class Feedback {

        String eventId;

        @BeforeEach
        void hackNightWithAsha() {
            eventId = hackNight();
        }

        void ended() {
            flows.times(eventId, Duration.ofHours(-48), Duration.ofHours(-5), Duration.ofHours(-3), Duration.ofHours(-1));
        }

        @Test
        void attendeesRateTheEventAfterItEndsTheClubSeesTheAverageAndNotesWithoutNames() {
            assertThat(api.as(asha).put("/api/events/" + eventId + "/feedback", map("rating", 5)).status()).isEqualTo(409);

            ended();
            assertThat(feedback.sweepFeedbackRequests()).isEqualTo(1);
            assertThat(feedback.sweepFeedbackRequests()).isZero();
            assertThat(flows.notified(asha, "FEEDBACK_REQUEST")).isTrue();

            Response mine = api.as(asha).put("/api/events/" + eventId + "/feedback", map("rating", 4, "note", "Great mentors, but the Wi-Fi was slow."));
            assertThat(mine.status()).isEqualTo(200);
            assertThat((Boolean) mine.at("$.data.canGive")).isTrue();
            assertThat((Integer) mine.at("$.data.mine.rating")).isEqualTo(4);
            assertThat(api.as(bina).put("/api/events/" + eventId + "/feedback", map("rating", 1)).status()).isEqualTo(403);
            assertThat(api.as(asha).put("/api/events/" + eventId + "/feedback", map("rating", 6)).status()).isEqualTo(400);

            Response summary = api.as(president).get("/api/events/" + eventId + "/feedback");
            assertThat((Integer) summary.at("$.data.summary.count")).isEqualTo(1);
            assertThat((Integer) summary.at("$.data.summary.average")).isEqualTo(4);
            assertThat((List<Integer>) summary.at("$.data.summary.distribution")).containsExactly(0, 0, 0, 1, 0);
            assertThat((Integer) summary.at("$.data.summary.attendees")).isEqualTo(1);
            assertThat((List<Integer>) summary.at("$.data.summary.notes[*].rating")).containsExactly(4);
            assertThat((List<String>) summary.at("$.data.summary.notes[*].note")).containsExactly("Great mentors, but the Wi-Fi was slow.");
            assertThat(json.writeValueAsString(summary.at("$.data.summary"))).doesNotContain(asha.id());
            assertThat((Integer) api.as(mentor).get("/api/events/" + eventId + "/feedback").at("$.data.summary.count")).isEqualTo(1);
            assertThat((Object) api.as(bina).get("/api/events/" + eventId + "/feedback").at("$.data.summary")).isNull();
        }

        @Test
        void onceCheckInIsUsedOnlyStudentsWhoWereCheckedInCanRate() {
            ended();
            flows.set(eventId, new Update().set("checkIn.status", "CLOSED"));
            assertThat(api.as(asha).put("/api/events/" + eventId + "/feedback", map("rating", 3)).status()).isEqualTo(403);
            mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("user").is(new ObjectId(asha.id()))),
                    new Update().set("checkedInAt", Instant.now()), EventRegistration.class);
            assertThat(api.as(asha).put("/api/events/" + eventId + "/feedback", map("rating", 3)).status()).isEqualTo(200);
        }
    }
}
