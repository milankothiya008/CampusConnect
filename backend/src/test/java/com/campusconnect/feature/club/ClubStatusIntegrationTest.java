package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/clubStatus.test.js. The event, its registration, the recruitment drive and the
 * applications are inserted directly (other features own those routes). Where the Node test checks the events feed,
 * registration or offers while the club is paused, this one checks what this feature controls: the paused-club ids
 * those features read, the club directory and the club page.
 */
class ClubStatusIntegrationTest extends IntegrationTest {

    @Autowired
    PausedClubs pausedClubs;

    static Instant inDays(double days) {
        return Instant.now().plusMillis((long) (days * 86_400_000));
    }

    @Nested
    class SuspendingAndReactivatingAClub {

        TestUser admin;
        TestUser mentor;
        TestUser president;
        TestUser attendee;
        TestUser applicant;
        TestUser offered;
        TestUser latecomer;
        Club club;
        Event event;
        RecruitmentDrive drive;

        @BeforeEach
        void seed() {
            TestData.Venues venues = data.seedReferenceData();
            admin = data.admin();
            mentor = data.faculty("CE", "Dr. Mentor");
            president = data.student(s -> s.name("President"));
            attendee = data.student(s -> s.name("Attendee"));
            applicant = data.student(s -> s.name("Applicant"));
            offered = data.student(s -> s.name("Offered"));
            latecomer = data.student(s -> s.name("Latecomer"));
            club = data.activeClub("Robotics Club", mentor, president);
            data.addMembership(club, data.student(), "MEMBER");

            // An upcoming published event with a registered student.
            event = new Event();
            event.setTitle("Robo Race");
            event.setShortDescription("Robots race around the quad.");
            event.setDescription("Teams race robots they built over the semester.");
            event.setCategory("TECHNOLOGY");
            event.setClub(club.getId());
            event.setVenue(venues.auditorium().getId());
            event.setStartAt(inDays(10));
            event.setEndAt(inDays(10).plus(3, ChronoUnit.HOURS));
            event.setEventDate(inDays(10).truncatedTo(ChronoUnit.DAYS));
            event.setStartTime("10:00");
            event.setEndTime("13:00");
            event.setRegistrationStart(inDays(-1));
            event.setRegistrationEnd(inDays(8));
            event.setStatus(EventStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setCreatedBy(president.id());
            event = mongo.insert(event);
            EventRegistration registration = new EventRegistration();
            registration.setEvent(event.getId());
            registration.setUser(attendee.id());
            mongo.insert(registration);

            // A live recruitment drive: one open application, one offer waiting for an answer.
            RecruitmentPosition position = new RecruitmentPosition();
            position.setRole("MEMBER");
            position.setTitle("Member");
            drive = new RecruitmentDrive();
            drive.setClub(club.getId());
            drive.setTitle("Build team");
            drive.setDescription("Builders wanted for the robo race season.");
            drive.setPositions(List.of(position));
            drive.setApplicationStart(Instant.now());
            drive.setApplicationEnd(inDays(6));
            drive.setStatus(RecruitmentStatus.PUBLISHED);
            drive.setPublishedAt(Instant.now());
            drive.setCreatedBy(president.id());
            drive = mongo.insert(drive);
            application(applicant, ApplicationStatus.APPLIED, null);
            application(offered, ApplicationStatus.OFFERED, inDays(1));
        }

        void application(TestUser student, ApplicationStatus status, Instant offerExpiresAt) {
            RecruitmentApplication application = new RecruitmentApplication();
            application.setDrive(drive.getId());
            application.setClub(club.getId());
            application.setApplicant(student.id());
            application.setPosition(drive.getPositions().getFirst().getId());
            application.setStatus(status);
            if (offerExpiresAt != null) {
                application.setOfferedAt(Instant.now());
                application.setOfferExpiresAt(offerExpiresAt);
            }
            mongo.insert(application);
        }

        Response setStatus(TestUser user, String status, String reason) {
            return api.as(user).post("/api/clubs/" + club.getId() + "/status", reason == null ? Map.of("status", status) : Map.of("status", status, "reason", reason));
        }

        void suspend() {
            assertThat(setStatus(admin, "SUSPENDED", "Safety review of the workshop equipment").status()).isEqualTo(200);
        }

        DevInbox.Entry mail(TestUser user, String subject) {
            return data.mailsTo(user.email()).stream().filter(entry -> subject.equals(entry.subject())).findFirst().orElse(null);
        }

        RecruitmentApplication offerOf(TestUser user) {
            return mongo.findOne(Query.query(Criteria.where("drive").is(drive.getId()).and("applicant").is(user.id())), RecruitmentApplication.class);
        }

        @Test
        void onlyTheAdminChangesStatusSuspendingNeedsAReasonImpossibleMovesAreRefused() {
            assertThat(setStatus(president, "SUSPENDED", "Testing").status()).isEqualTo(403);
            Response noReason = setStatus(admin, "SUSPENDED", null);
            assertThat(noReason.status()).isEqualTo(400);
            assertThat(noReason.message()).contains("Give a reason");
            assertThat(setStatus(admin, "ACTIVE", null).message()).contains("already active");
            assertThat(setStatus(admin, "APPROVED", null).status()).isEqualTo(400);
        }

        @Test
        void suspendingPausesEventsAndRecruitmentAndTellsEveryoneInvolved() {
            Response res = setStatus(admin, "SUSPENDED", "Safety review of the workshop equipment");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("SUSPENDED");
            assertThat(res.string("$.data.statusNote")).isEqualTo("Safety review of the workshop equipment");

            DevInbox.Entry leaderMail = mail(president, "Robotics Club has been suspended");
            assertThat(leaderMail.text()).contains("Safety review of the workshop equipment").contains("1 registered student has been told");
            assertThat(mail(mentor, "Robotics Club has been suspended")).isNotNull();
            DevInbox.Entry onHold = mail(attendee, "On hold: Robo Race");
            assertThat(onHold.text()).contains("Your registration is kept");
            assertThat(mail(applicant, "Robotics Club recruitment is on hold")).isNotNull();
            // Students aren't told the admin's internal reason.
            assertThat(onHold.text()).doesNotContain("Safety review");
        }

        @Test
        void whileSuspendedTheClubIsLeftOutOfDiscoveryAndItsRecruitmentIsNotShownAsOpen() {
            suspend();
            assertThat(pausedClubs.ids()).contains(club.getId());
            assertThat((List<String>) api.as(latecomer).get("/api/clubs").at("$.data[*]._id")).doesNotContain(club.getId());
            assertThat(api.as(latecomer).get("/api/clubs/" + club.getId()).status()).isEqualTo(404);
            Response insider = api.as(president).get("/api/clubs/" + club.getId());
            assertThat(insider.status()).isEqualTo(200);
            assertThat((Object) insider.at("$.data.recruiting")).isNull();
            // The offer is untouched while the club is paused.
            assertThat(offerOf(offered).getStatus()).isEqualTo(ApplicationStatus.OFFERED);
        }

        @Test
        void reactivatingResumesEverythingMovesDeadlinesOnByThePausedTimeAndEmailsTheNote() {
            suspend();
            // Pretend the club has been paused for two days.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId())), new Update().set("pausedAt", inDays(-2)), Club.class);
            Instant endBefore = mongo.findById(drive.getId(), RecruitmentDrive.class).getApplicationEnd();
            Instant offerBefore = offerOf(offered).getOfferExpiresAt();

            Response res = setStatus(admin, "ACTIVE", "Equipment checked — all clear");
            assertThat(res.string("$.data.status")).isEqualTo("ACTIVE");

            Instant endAfter = mongo.findById(drive.getId(), RecruitmentDrive.class).getApplicationEnd();
            assertThat(Math.abs(Duration.between(endBefore, endAfter).toMillis() - 2 * 86_400_000L)).isLessThan(60_000L);
            Instant offerAfter = offerOf(offered).getOfferExpiresAt();
            assertThat(Math.abs(Duration.between(offerBefore, offerAfter).toMillis() - 2 * 86_400_000L)).isLessThan(60_000L);

            assertThat(mail(president, "Robotics Club has been reactivated").text()).contains("Equipment checked — all clear");
            assertThat(mail(attendee, "Back on: Robo Race")).isNotNull();
            assertThat(mail(offered, "Robotics Club recruitment has resumed").text()).contains("moved on by about 2 days");

            assertThat(pausedClubs.ids()).doesNotContain(club.getId());
            assertThat((List<String>) api.as(latecomer).get("/api/clubs").at("$.data[*]._id")).contains(club.getId());
        }

        @Test
        void archivingAndReactivatingAnArchivedClub() {
            assertThat(setStatus(admin, "ARCHIVED", "Club merged into the Tech Society").string("$.data.status")).isEqualTo("ARCHIVED");
            assertThat(setStatus(admin, "SUSPENDED", "Not allowed").status()).isEqualTo(409);
            assertThat(setStatus(admin, "ACTIVE", null).string("$.data.status")).isEqualTo("ACTIVE");
        }
    }
}
