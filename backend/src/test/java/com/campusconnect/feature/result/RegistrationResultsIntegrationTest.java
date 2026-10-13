package com.campusconnect.feature.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Port of the result-publishing part of tests/integration/registrationResults.test.js (describe "results"); its
 * registration parts belong to the events feature. Every test replays the steps of the tests before it.
 */
class RegistrationResultsIntegrationTest extends IntegrationTest {

    Venues venues;
    Club club;
    TestUser mentor;
    TestUser president;
    TestUser coordinator;
    TestUser marketing;
    TestUser member;
    EventFixtures events;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        mentor = data.faculty("CE", "Mentor");
        data.faculty("CE", "Other Faculty");
        president = data.student(s -> s.name("President"));
        coordinator = data.student(s -> s.name("Event Coordinator"));
        TestUser treasurer = data.student(s -> s.name("Treasurer"));
        marketing = data.student(s -> s.name("Marketing"));
        member = data.student(s -> s.name("Member"));
        club = data.activeClub("AI Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, treasurer, "TREASURER");
        data.addMembership(club, marketing, "MARKETING_COORDINATOR");
        data.addMembership(club, member, "MEMBER");
        events = new EventFixtures(mongo);
    }

    @Nested
    class Results {

        Event sprint;
        TestUser winner;
        TestUser runnerUp;
        TestUser notParticipant;

        @BeforeEach
        void sprint() {
            sprint = events.published(club, venues.auditorium(), 25, event -> {
                event.setTitle("Code Sprint");
                event.setMaxParticipants(20);
            });
            winner = data.student(s -> s.name("Winner"));
            runnerUp = data.student(s -> s.name("Runner Up"));
            notParticipant = data.student();
            events.register(sprint, winner);
            events.register(sprint, runnerUp);
        }

        String results() {
            return "/api/events/" + sprint.getId() + "/results";
        }

        Map<String, Object> resultBody(List<Map<String, Object>> awards) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("summary", "A great sprint with 12 teams competing.");
            body.put("awards", awards);
            return body;
        }

        Map<String, Object> award(Object... pairs) {
            return ResultsIntegrationTest.row(pairs);
        }

        void finishAndComplete() {
            events.finish(sprint, true);
        }

        Response saveDraft() {
            finishAndComplete();
            return api.as(coordinator).put(results(), resultBody(List.of(
                    award("title", "Winner", "position", 1, "recipientUser", winner.id(), "prize", "₹5,000"),
                    award("title", "Runner-up", "position", 2, "recipientUser", runnerUp.id(), "recognition", "Best UI"))));
        }

        @Test
        void finalResultsCannotBeAddedBeforeTheEventStarts() {
            Response res = api.as(president).put(results(), resultBody(List.of(award("title", "Winner", "recipientName", "Team A"))));
            assertThat(res.status()).isEqualTo(409);
        }

        @Test
        void recipientsMustHaveBeenRegisteredParticipants() {
            finishAndComplete();
            Response res = api.as(president).put(results(), resultBody(List.of(award("title", "Winner", "position", 1, "recipientUser", notParticipant.id()))));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.message()).isEqualTo("Award #1: the selected student was not registered for this event");
        }

        @Test
        void unauthorisedMembersCannotManageResults() {
            finishAndComplete();
            Map<String, Object> body = resultBody(List.of(award("title", "Winner", "position", 1, "recipientUser", winner.id())));
            assertThat(api.as(member).put(results(), body).status()).isEqualTo(403);
            Response marketingTry = api.as(marketing).put(results(), body);
            assertThat(marketingTry.status()).isEqualTo(403);
            assertThat(marketingTry.message()).isEqualTo("You cannot manage results for this club");
        }

        @Test
        void theEventCoordinatorSavesADraftThatIsNotPubliclyVisible() {
            Response res = saveDraft();
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("DRAFT");
            assertThat(res.string("$.data.awards[0].recipientName")).isEqualTo("Winner");

            assertThat(api.get(results()).status()).isEqualTo(404);
            assertThat(api.get(results()).message()).isEqualTo("Results have not been published yet");
            assertThat(api.as(mentor).get(results()).status()).isEqualTo(200);
        }

        /**
         * Node also reads the completed event's card in GET /api/events?timeframe=past (the events feature); here the
         * same awards are read from the public results page.
         */
        @Test
        void publishingMakesResultsPublicShowsThemOnTheCompletedEventAndNotifiesThePeopleInvolved() {
            saveDraft();
            Response res = api.as(president).post(results() + "/publish");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("PUBLISHED");

            Response publicView = api.get(results());
            assertThat(publicView.status()).isEqualTo(200);
            assertThat((List<?>) publicView.at("$.data.awards")).hasSize(2);

            // Results are not a feed post any more; they travel with the completed event.
            assertThat(mongo.exists(Query.query(Criteria.where("event").is(new ObjectId(sprint.getId())).and("type").is("RESULT")), FeedPost.class)).isFalse();
            assertThat((List<String>) api.as(notParticipant).get(results()).at("$.data.awards[*].recipientName")).containsExactly("Winner", "Runner Up");

            // Participants, the club's members and its mentor are notified; students with no link to the event or the
            // club aren't (the results are still public for them to see).
            Set<String> notified = mongo.find(Query.query(Criteria.where("type").is("RESULT_PUBLISHED").and("link").is("/results/" + sprint.getId())), Notification.class)
                    .stream().map(Notification::getUser).collect(Collectors.toSet());
            assertThat(notified).contains(winner.id(), member.id(), mentor.id());
            assertThat(notified).doesNotContain(notParticipant.id(), president.id());

            assertThat(api.get("/api/results").string("$.data[0].event.title")).isEqualTo("Code Sprint");
        }
    }
}
