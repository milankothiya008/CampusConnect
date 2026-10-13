package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.jobs.JobQueue;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubSubscription;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.audience.CampusMailer;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of the event-launch e-mail tests of tests/integration/notifications.test.js ("publishing an event"). The club
 * bell is set straight in the database (its routes belong to the clubs feature).
 *
 * <p>The original first test checked that publishing returned before any e-mail was sent and that the queue delivered
 * them afterwards. Here the job queue runs jobs inline in tests (app.jobs.mode=direct), so "not yet sent" cannot be
 * observed; the test checks the queue is drained and every launch e-mail has arrived once publishing returns. The
 * dev inbox keeps no headers, so the List-Unsubscribe-Post check of the second test has no counterpart.
 */
class EventLaunchEmailsIntegrationTest extends IntegrationTest {

    @Autowired
    CampusMailer campusMailer;

    @Autowired
    DevInbox inbox;

    @Autowired
    JobQueue jobs;

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser member;
    TestUser mutedMember;
    TestUser itFollower;
    TestUser ceStudent;
    TestUser ecStudent;
    TestUser noRecommendations;
    TestUser faculty;
    Club club;
    String eventId;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty("CE", "Mentor");
        president = data.student(spec -> spec.name("President"));
        member = data.student(spec -> spec.name("Member"));
        mutedMember = data.student(spec -> spec.name("Muted Member"));
        itFollower = data.student(spec -> spec.name("IT Follower").department("IT"));
        ceStudent = data.student(spec -> spec.name("CE Student"));
        ecStudent = data.student(spec -> spec.name("EC Student").department("EC"));
        noRecommendations = data.student(spec -> spec.name("No Recommendations"));
        faculty = data.faculty("IT", "Other Faculty");
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, member, "MEMBER");
        data.addMembership(club, mutedMember, "MEMBER");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(noRecommendations.id()))), new Update().set("emailPreferences.eventRecommendations", false),
                User.class);
        bell(itFollower, true);
        bell(mutedMember, false);
        eventId = flows.published(president, mentor, club, venues.auditorium(), "title", "Hack Night", "maxParticipants", 100,
                "eligibility", map("departments", List.of("CE", "IT")));
    }

    void bell(TestUser user, boolean enabled) {
        ClubSubscription subscription = new ClubSubscription();
        subscription.setClub(club.getId());
        subscription.setUser(user.id());
        subscription.setEnabled(enabled);
        mongo.insert(subscription);
    }

    List<DevInbox.Entry> launchMails(TestUser user) {
        return data.mailsTo(user.email()).stream().filter(mail -> mail.subject().contains("just announced") || mail.subject().contains("you can join")).toList();
    }

    @Test
    void publishingQueuesTheLaunchEmailsWhichTheQueueDelivers() {
        assertThat(jobs.pendingMail()).isZero();
        assertThat(launchMails(member)).hasSize(1);
        assertThat(launchMails(itFollower)).hasSize(1);
        assertThat(launchMails(ceStudent)).hasSize(1);
    }

    @Test
    void followersHearItFromTheClubWithALinkToTurnThatClubOff() {
        for (TestUser user : List.of(member, itFollower)) {
            List<DevInbox.Entry> mails = data.mailsTo(user.email());
            assertThat(mails).as(user.name()).hasSize(1);
            assertThat(mails.get(0).subject()).isEqualTo("Coding Club just announced: Hack Night");
            assertThat(mails.get(0).text()).contains("notifications are on for Coding Club");
            assertThat(mails.get(0).text()).containsPattern("Turn off emails from Coding Club: http://localhost:3000/unsubscribe\\?token=");
            assertThat(mails.get(0).html()).contains("/events/" + eventId);
        }
    }

    @Test
    void otherEligibleStudentsGetItAsARecommendationEveryoneElseGetsNothing() {
        DevInbox.Entry recommendation = data.mailsTo(ceStudent.email()).get(0);
        assertThat(recommendation.subject()).isEqualTo("New event you can join: Hack Night");
        assertThat(recommendation.text()).contains("Open to: CE, IT");
        assertThat(recommendation.text()).contains("Unsubscribe from \"New events for you\"");

        // Not eligible, recommendations switched off, muted the club, published it, or faculty: no e-mail.
        for (TestUser user : List.of(ecStudent, noRecommendations, mutedMember, president, faculty, mentor)) {
            assertThat(launchMails(user)).as(user.name()).isEmpty();
        }
    }

    @Test
    void nobodyGetsTheSameLaunchEmailTwice() {
        inbox.clear();
        Event event = flows.event(eventId);
        campusMailer.sendEventLaunchEmails(event, mongo.findById(club.getId(), Club.class), AuthUser.of(mongo.findById(president.id(), User.class)));
        assertThat(data.mailCount()).isZero();
    }
}
