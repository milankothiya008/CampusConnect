package com.campusconnect.feature.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubSubscription;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Port of "winners, participants and members each get one fitting email" from tests/integration/notifications.test.js
 * (describe "club bell, campaign emails and unsubscribe" › "publishing results"). The club bell choices made by the
 * earlier Node tests (an IT student turned it on, a member turned it off) are stored directly.
 */
class ResultEmailsIntegrationTest extends IntegrationTest {

    Club club;
    TestUser president;
    TestUser member;
    TestUser mutedMember;
    TestUser itFollower;
    Venues venues;

    void bell(TestUser user, boolean enabled) {
        ClubSubscription subscription = new ClubSubscription();
        subscription.setClub(club.getId());
        subscription.setUser(user.id());
        subscription.setEnabled(enabled);
        mongo.insert(subscription);
    }

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        TestUser mentor = data.faculty("CE", "Mentor");
        president = data.student(s -> s.name("President"));
        member = data.student(s -> s.name("Member"));
        mutedMember = data.student(s -> s.name("Muted Member"));
        itFollower = data.student(s -> s.name("IT Follower").department("IT"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, member, "MEMBER");
        data.addMembership(club, mutedMember, "MEMBER");
        bell(itFollower, true);
        bell(mutedMember, false);
    }

    @Nested
    class PublishingResults {

        Event sprint;
        TestUser winner;
        TestUser participant;

        @BeforeEach
        void completedSprintWithADraft() {
            EventFixtures events = new EventFixtures(mongo);
            sprint = events.published(club, venues.auditorium(), 20, event -> {
                event.setTitle("Code Sprint");
                event.setMaxParticipants(100);
            });
            winner = data.student(s -> s.name("Winner Person"));
            participant = data.student(s -> s.name("Participant Person"));
            events.register(sprint, winner);
            events.register(sprint, participant);
            events.register(sprint, member);
            events.finish(sprint, true);

            ResultAward award = new ResultAward();
            award.setTitle("Winner");
            award.setPosition(1);
            award.setRecipientUser(winner.id());
            award.setRecipientName("Winner Person");
            award.setPrize("₹5,000");
            EventResult result = new EventResult();
            result.setEvent(sprint.getId());
            result.setClub(club.getId());
            result.setSummary("Twelve teams took part.");
            result.setAwards(List.of(award));
            result.setCreatedBy(president.id());
            mongo.insert(result);
        }

        List<String> subjects(TestUser user) {
            return data.mailsTo(user.email()).stream().map(DevInbox.Entry::subject).toList();
        }

        @Test
        void winnersParticipantsAndMembersEachGetOneFittingEmail() {
            assertThat(api.as(president).post("/api/events/" + sprint.getId() + "/results/publish").status()).isEqualTo(200);

            assertThat(subjects(winner)).containsExactly("Congratulations! Winner at Code Sprint");
            assertThat(data.mailsTo(winner.email()).get(0).text()).contains("Prize: ₹5,000");
            assertThat(subjects(participant)).containsExactly("Results are out: Code Sprint");
            // A member who also took part is emailed as a participant, not twice.
            assertThat(subjects(member)).containsExactly("Results are out: Code Sprint");

            // Followers who are not members, the muted member and the publisher get no result email.
            for (TestUser user : List.of(itFollower, mutedMember, president)) {
                assertThat(data.mailsTo(user.email())).as(user.name()).isEmpty();
            }
        }
    }
}
