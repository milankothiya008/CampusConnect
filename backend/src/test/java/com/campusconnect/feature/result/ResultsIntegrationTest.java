package com.campusconnect.feature.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.cache.CacheNames;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Port of tests/integration/results.test.js ("round-wise and final results"). The Node tests share one database and
 * run in order; here every test starts from an empty database and replays the steps of the tests before it. Events
 * and registrations are written directly (the event routes belong to the events feature).
 */
class ResultsIntegrationTest extends IntegrationTest {

    @Autowired
    CacheManager cacheManager;

    Venues venues;
    Club club;
    TestUser mentor;
    TestUser president;
    TestUser vicePresident;
    TestUser coordinator;
    TestUser member;
    TestUser outsider;
    TestUser alice;
    TestUser bob;
    TestUser carol;
    Event hack;
    EventFixtures events;
    String roundId;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        mentor = data.faculty("CE", "Mentor");
        president = data.student(s -> s.name("President"));
        vicePresident = data.student(s -> s.name("Vice President"));
        coordinator = data.student(s -> s.name("Coordinator"));
        member = data.student(s -> s.name("Member"));
        outsider = data.student(s -> s.name("Outsider"));
        alice = data.student(s -> s.name("Alice"));
        bob = data.student(s -> s.name("Bob"));
        carol = data.student(s -> s.name("Carol"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, vicePresident, "VICE_PRESIDENT");
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, member, "MEMBER");
        events = new EventFixtures(mongo);
        hack = events.published(club, venues.auditorium(), 10, event -> {
            event.setTitle("HackDDU");
            event.setMaxParticipants(100);
        });
        for (TestUser student : List.of(alice, bob, carol)) {
            events.register(hack, student);
        }
    }

    static String rounds(Event event) {
        return "/api/events/" + event.getId() + "/results/rounds";
    }

    static String results(Event event) {
        return "/api/events/" + event.getId() + "/results";
    }

    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    List<Map<String, Object>> screeningEntries() {
        return new ArrayList<>(List.of(
                row("rank", 3, "recipientUser", carol.id(), "score", "61", "qualified", false),
                row("rank", 1, "recipientUser", alice.id(), "teamName", "Team Alpha", "score", "92", "qualified", true),
                row("rank", 2, "recipientUser", bob.id(), "score", "88", "qualified", true, "note", "Best pitch"),
                row("teamName", "Walk-in Team", "score", "40", "qualified", false)));
    }

    Map<String, Object> screening() {
        return row("name", "Round 1: Idea screening", "description", "Top teams go through to the build round.", "entries", screeningEntries());
    }

    Map<String, Object> finalResults() {
        return row("summary", "Twelve teams built for 24 hours. Congratulations to our winners!", "awards", List.of(
                row("title", "Winner", "position", 1, "recipientUser", alice.id(), "teamName", "Team Alpha", "prize", "₹10,000"),
                row("title", "Runner-up", "position", 2, "recipientUser", bob.id(), "prize", "₹5,000")));
    }

    static List<String> names(List<Map<String, Object>> entries) {
        return entries.stream().map(entry -> entry.get("teamName") != null ? (String) entry.get("teamName") : (String) entry.get("recipientName")).toList();
    }

    // ---------------------------------------------------------------- the earlier tests' steps

    String draftScreening() {
        Response res = api.as(coordinator).post(rounds(hack), screening());
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        roundId = res.string("$.data.rounds[0]._id");
        return roundId;
    }

    void publishScreening() {
        draftScreening();
        assertThat(api.as(president).post(rounds(hack) + "/" + roundId + "/publish").status()).isEqualTo(200);
    }

    void publishFinalResults() {
        publishScreening();
        events.start(hack);
        assertThat(api.as(coordinator).put(results(hack), finalResults()).status()).isEqualTo(200);
        assertThat(api.as(president).post(results(hack) + "/publish").status()).isEqualTo(200);
    }

    @Nested
    class Rounds {

        @Test
        void anEventCoordinatorDraftsARoundBeforeTheEventStartsItStaysPrivate() {
            Response res = api.as(coordinator).post(rounds(hack), screening());
            assertThat(res.status()).isEqualTo(201);
            assertThat(res.message()).isEqualTo("Round created");
            assertThat(res.string("$.data.rounds[0].status")).isEqualTo("DRAFT");
            // Standings come back best rank first, unranked rows last.
            assertThat(names(res.at("$.data.rounds[0].entries"))).containsExactly("Team Alpha", "Bob", "Carol", "Walk-in Team");

            assertThat(api.as(outsider).get(results(hack)).status()).isEqualTo(404);
            assertThat((List<?>) api.as(mentor).get(results(hack)).at("$.data.rounds")).hasSize(1);
        }

        @Test
        void validatesStandingsAndPermissions() {
            draftScreening();
            Map<String, Object> twice = row("name", "Twice", "entries", List.of(row("recipientUser", alice.id()), row("recipientUser", alice.id())));
            assertThat(api.as(coordinator).post(rounds(hack), twice).message()).isEqualTo("Row 2: Alice is listed twice");

            Map<String, Object> stranger = row("name", "Stranger", "entries", List.of(row("recipientUser", outsider.id())));
            assertThat(api.as(coordinator).post(rounds(hack), stranger).message()).contains("not registered for this event");

            Map<String, Object> empty = row("name", "Empty row", "entries", List.of(row("score", "10")));
            assertThat(api.as(coordinator).post(rounds(hack), empty).message()).isEqualTo("Row 1: add a participant, name or team");

            assertThat(api.as(coordinator).post(rounds(hack), row("name", "round 1: idea screening")).status()).isEqualTo(409);
            assertThat(api.as(coordinator).post(rounds(hack), row("name", "")).status()).isEqualTo(400);
            assertThat(api.as(member).post(rounds(hack), row("name", "Sneaky")).status()).isEqualTo(403);
            assertThat(api.as(mentor).post(rounds(hack), row("name", "Mentor round")).status()).isEqualTo(403);
        }

        @Test
        void onlyThePresidentCanPublishARound() {
            draftScreening();
            assertThat(api.as(coordinator).post(rounds(hack) + "/" + roundId + "/publish").status()).isEqualTo(403);
            assertThat(api.as(vicePresident).post(rounds(hack) + "/" + roundId + "/publish").status()).isEqualTo(403);

            Response res = api.as(president).post(rounds(hack) + "/" + roundId + "/publish");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.rounds[0].status")).isEqualTo("PUBLISHED");
            assertThat(api.as(president).post(rounds(hack) + "/" + roundId + "/publish").status()).isEqualTo(409);
        }

        @Test
        void aPublishedRoundIsPublicMidEventWhileFinalResultsStayHidden() {
            publishScreening();
            Response res = api.get(results(hack));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.status")).isEqualTo("DRAFT");
            assertThat(res.string("$.data.summary")).isEmpty();
            Map<String, Object> first = res.at("$.data.rounds[0].entries[0]");
            assertThat(first).containsEntry("rank", 1).containsEntry("teamName", "Team Alpha").containsEntry("score", "92").containsEntry("qualified", true);
            assertThat((Map<String, Object>) res.at("$.data.viewer")).isEqualTo(Map.of("canSeeDrafts", false, "canEdit", false, "canPublish", false));
        }

        @Test
        void participantsAreNotifiedAndQualifiersGetAPersonalEmail() {
            publishScreening();
            Set<String> notified = mongo.find(Query.query(Criteria.where("type").is("ROUND_RESULTS")), Notification.class).stream()
                    .map(Notification::getUser).collect(Collectors.toSet());
            assertThat(notified).containsExactlyInAnyOrder(alice.id(), bob.id(), carol.id());
            assertThat(mongo.exists(Query.query(Criteria.where("type").is("ROUND_RESULTS").and("link").is("/results/" + hack.getId())), Notification.class)).isTrue();

            assertThat(roundMails(alice).stream().map(DevInbox.Entry::subject)).containsExactly("You're through! Round 1: Idea screening results for HackDDU");
            assertThat(roundMails(bob).stream().map(DevInbox.Entry::subject)).containsExactly("You're through! Round 1: Idea screening results for HackDDU");
            assertThat(roundMails(carol).stream().map(DevInbox.Entry::subject)).containsExactly("Round 1: Idea screening results are out: HackDDU");
            assertThat(roundMails(carol).get(0).text()).contains("2 entries go through to the next round");
            assertThat(roundMails(outsider)).isEmpty();
        }

        List<DevInbox.Entry> roundMails(TestUser user) {
            return data.mailsTo(user.email()).stream().filter(mail -> mail.subject().contains("Round 1")).toList();
        }

        @Test
        void correctionsToAPublishedRoundArePresidentOnlyAndMarked() {
            publishScreening();
            List<Map<String, Object>> entries = screeningEntries().subList(0, 3);
            List<Map<String, Object>> fixed = new ArrayList<>(entries);
            fixed.add(row("teamName", "Walk-in Team", "score", "45", "qualified", false));
            Map<String, Object> fix = row("entries", fixed);
            assertThat(api.as(coordinator).put(rounds(hack) + "/" + roundId, fix).status()).isEqualTo(403);

            Response res = api.as(president).put(rounds(hack) + "/" + roundId, fix);
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Round saved");
            assertThat(res.string("$.data.rounds[0].correctedAt")).isNotBlank();
            assertThat(res.string("$.data.rounds[0].entries[3].score")).isEqualTo("45");
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("RESULT_CORRECTED")), "auditlogs")).isTrue();

            assertThat(api.as(president).put(rounds(hack) + "/" + roundId, row("entries", List.of())).status()).isEqualTo(400);
        }

        @Test
        void publishedRoundsCannotBeDeletedThePresidentCanWithdrawThem() {
            publishScreening();
            assertThat(api.as(coordinator).delete(rounds(hack) + "/" + roundId).status()).isEqualTo(409);
            assertThat(api.as(coordinator).post(rounds(hack) + "/" + roundId + "/unpublish").status()).isEqualTo(403);

            Response withdrawn = api.as(president).post(rounds(hack) + "/" + roundId + "/unpublish");
            assertThat(withdrawn.message()).isEqualTo("Round results withdrawn");
            assertThat(withdrawn.string("$.data.rounds[0].status")).isEqualTo("DRAFT");
            assertThat(api.get(results(hack)).status()).isEqualTo(404);

            assertThat(api.as(president).post(rounds(hack) + "/" + roundId + "/publish").status()).isEqualTo(200);
        }

        @Test
        void draftRoundsCanBeDeletedByResultsManagers() {
            publishScreening();
            Response created = api.as(coordinator).post(rounds(hack), row("name", "Practice round"));
            List<Map<String, Object>> list = created.at("$.data.rounds");
            String practice = list.stream().filter(round -> "Practice round".equals(round.get("name"))).map(round -> (String) round.get("_id")).findFirst().orElseThrow();

            Response res = api.as(coordinator).delete(rounds(hack) + "/" + practice);
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Round deleted");
            assertThat((List<String>) res.at("$.data.rounds[*].name")).containsExactly("Round 1: Idea screening");
        }
    }

    @Nested
    class FinalResults {

        @Test
        void cannotBePreparedBeforeTheEventStarts() {
            publishScreening();
            Response res = api.as(coordinator).put(results(hack), finalResults());
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.message()).contains("once the event has started");
        }

        @Test
        void canBePublishedByThePresidentWhileTheEventIsStillRunning() {
            publishScreening();
            events.start(hack);
            Response draft = api.as(coordinator).put(results(hack), finalResults());
            assertThat(draft.status()).isEqualTo(200);
            assertThat(draft.message()).isEqualTo("Final results draft saved");

            assertThat(api.as(coordinator).post(results(hack) + "/publish").status()).isEqualTo(403);
            assertThat(api.as(vicePresident).post(results(hack) + "/publish").status()).isEqualTo(403);

            Response res = api.as(president).post(results(hack) + "/publish");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Final results published");
            assertThat(res.string("$.data.status")).isEqualTo("PUBLISHED");
            assertThat(mongo.findById(hack.getId(), Event.class).getStatus()).isEqualTo(EventStatus.PUBLISHED);

            Response publicView = api.get(results(hack));
            assertThat(names(publicView.at("$.data.awards"))).containsExactly("Team Alpha", "Bob");
            assertThat((List<?>) publicView.at("$.data.rounds")).hasSize(1);
        }

        @Test
        void correctionsAfterPublishingArePresidentOnlyAndMarked() {
            publishFinalResults();
            Map<String, Object> corrected = finalResults();
            corrected.put("summary", "Twelve teams built for 24 hours. Corrected prize amounts.");
            assertThat(api.as(coordinator).put(results(hack), corrected).status()).isEqualTo(403);

            Response res = api.as(president).put(results(hack), corrected);
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Published results corrected");
            assertThat(res.string("$.data.correctedAt")).isNotBlank();
            assertThat(res.string("$.data.status")).isEqualTo("PUBLISHED");
        }

        /**
         * Node checks the event page's viewer flags (GET /api/events/:id, the events feature). Ported to what this area
         * returns the same answer through: the results page's viewer.
         */
        @Test
        void theEventPageTellsTheViewerWhatTheyMayDo() {
            publishFinalResults();
            assertThat((Map<String, Object>) api.as(president).get(results(hack)).at("$.data.viewer")).containsEntry("canEdit", true).containsEntry("canPublish", true);
            assertThat((Map<String, Object>) api.as(coordinator).get(results(hack)).at("$.data.viewer")).containsEntry("canEdit", true).containsEntry("canPublish", false);
        }
    }

    @Nested
    class ResultsBoard {

        Event relay;

        @BeforeEach
        void board() {
            publishFinalResults();
            relay = events.published(club, venues.auditorium(), 15, event -> {
                event.setTitle("Code Relay");
                event.setStartAt(CampusTime.combineDateAndTime(TestData.futureDate(15), "15:00"));
                event.setEndAt(CampusTime.combineDateAndTime(TestData.futureDate(15), "17:00"));
            });
            events.register(relay, alice);
            Response created = api.as(president).post(rounds(relay), row("name", "Heat 1", "entries", List.of(row("rank", 1, "recipientUser", alice.id(), "qualified", true))));
            assertThat(api.as(president).post(rounds(relay) + "/" + created.string("$.data.rounds[0]._id") + "/publish").status()).isEqualTo(200);
            // A third event with only a draft never shows up.
            Event hidden = events.published(club, venues.auditorium(), 18, event -> event.setTitle("Secret Sprint"));
            assertThat(api.as(president).post(rounds(hidden), row("name", "Draft only")).status()).isEqualTo(201);
        }

        List<String> titles(Response res) {
            List<String> titles = res.at("$.data[*].event.title");
            return titles;
        }

        @Test
        void listsOneCardPerEventWithAnythingPublishedWithStageCounts() {
            Response all = api.get("/api/results");
            assertThat(all.message()).isEqualTo("Published results fetched");
            assertThat(titles(all)).containsExactlyInAnyOrder("Code Relay", "HackDDU");
            assertThat((Map<String, Object>) all.at("$.meta.counts")).isEqualTo(Map.of("all", 2, "final", 1, "live", 1));
            assertThat((Integer) all.at("$.meta.total")).isEqualTo(2);

            Map<String, Object> hackCard = card(all, "HackDDU");
            assertThat(hackCard).containsEntry("final", true);
            assertThat((Map<String, Object>) hackCard.get("rounds")).containsEntry("published", 1);
            assertThat(((Map<String, Object>) ((Map<String, Object>) hackCard.get("rounds")).get("latest"))).containsEntry("name", "Round 1: Idea screening");
            assertThat(((List<Map<String, Object>>) hackCard.get("winners")).get(0)).containsEntry("teamName", "Team Alpha");

            Map<String, Object> relayCard = card(all, "Code Relay");
            assertThat(relayCard).containsEntry("final", false).containsEntry("winners", List.of());
            assertThat((Map<String, Object>) relayCard.get("rounds")).containsEntry("published", 1);
            assertThat(((Map<String, Object>) ((Map<String, Object>) relayCard.get("rounds")).get("latest"))).containsEntry("name", "Heat 1");
        }

        Map<String, Object> card(Response res, String title) {
            List<Map<String, Object>> cards = res.at("$.data");
            return cards.stream().filter(card -> title.equals(((Map<String, Object>) card.get("event")).get("title"))).findFirst().orElseThrow();
        }

        @Test
        void filtersByStageAndSearch() {
            assertThat(titles(api.get("/api/results?stage=live"))).containsExactly("Code Relay");
            assertThat(titles(api.get("/api/results?stage=final"))).containsExactly("HackDDU");
            assertThat(titles(api.get("/api/results?search=relay"))).containsExactly("Code Relay");
        }

        /**
         * Node checks the events feed card (GET /api/events, the events feature). Ported to what this area owes that
         * feed: the round is public while final results are still to come, and every result write drops the cached
         * events feed so the card's result badge is fresh.
         */
        @Test
        void feedCardsShowTheResultStage() {
            Response relayResults = api.as(outsider).get(results(relay));
            assertThat(relayResults.string("$.data.status")).isEqualTo("DRAFT");
            assertThat((List<String>) relayResults.at("$.data.rounds[*].name")).containsExactly("Heat 1");

            cacheManager.getCache(CacheNames.EVENT_FEED).put("page", "cached");
            Response created = api.as(president).post(rounds(relay), row("name", "Heat 2", "entries", List.of(row("rank", 1, "recipientUser", alice.id()))));
            assertThat(created.status()).isEqualTo(201);
            assertThat(cacheManager.getCache(CacheNames.EVENT_FEED).get("page")).isNull();
        }

        @Test
        void resultsCantBeAddedToEventsThatArentPublished() {
            Event draft = events.event(club, venues.hall(), EventStatus.DRAFT, 30, event -> {
                event.setTitle("Not yet");
                event.setMaxParticipants(10);
            });
            assertThat(api.as(president).post(rounds(draft), row("name", "Too early")).status()).isEqualTo(409);
            assertThat(mongo.exists(Query.query(Criteria.where("event").is(new ObjectId(draft.getId()))), EventResult.class)).isFalse();
        }
    }
}
