package com.campusconnect.feature.result;

import static com.campusconnect.feature.result.ResultsIntegrationTest.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Results behaviour the Node tests don't pin down: caching, storage shape, exact validation messages, races. */
class ResultExtrasIntegrationTest extends IntegrationTest {

    Venues venues;
    Club club;
    TestUser president;
    TestUser coordinator;
    TestUser alice;
    TestUser bob;
    EventFixtures events;
    Event event;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        TestUser mentor = data.faculty("CE", "Mentor");
        president = data.student(s -> s.name("President"));
        coordinator = data.student(s -> s.name("Coordinator"));
        alice = data.student(s -> s.name("Alice"));
        bob = data.student(s -> s.name("Bob"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        events = new EventFixtures(mongo);
        event = events.published(club, venues.auditorium(), 4, e -> e.setTitle("Quiz Bowl"));
        events.register(event, alice);
        events.register(event, bob);
    }

    String rounds() {
        return "/api/events/" + event.getId() + "/results/rounds";
    }

    String publishedRound(String name) {
        Response created = api.as(president).post(rounds(), row("name", name, "entries", List.of(row("rank", 1, "recipientUser", alice.id(), "qualified", true))));
        List<Map<String, Object>> list = created.at("$.data.rounds");
        String id = list.stream().filter(round -> name.equals(round.get("name"))).map(round -> (String) round.get("_id")).findFirst().orElseThrow();
        assertThat(api.as(president).post(rounds() + "/" + id + "/publish").status()).isEqualTo(200);
        return id;
    }

    @Test
    void theResultsPageIsServedFromTheCacheAndEveryResultWriteRefreshesIt() {
        publishedRound("Heat 1");
        Response first = api.get("/api/results");
        Response cached = api.get("/api/results");
        assertThat(cached.body()).isEqualTo(first.body());
        assertThat(cached.string("$.data[0].event.club.name")).isEqualTo("Coding Club");
        assertThat(cached.string("$.data[0].event.startAt")).isEqualTo(first.string("$.data[0].event.startAt"));

        publishedRound("Heat 2");
        Response fresh = api.get("/api/results");
        assertThat((Integer) fresh.at("$.data[0].rounds.published")).isEqualTo(2);
        assertThat(fresh.string("$.data[0].rounds.latest.name")).isEqualTo("Heat 2");

        assertThat(api.get("/api/results?club=" + club.getId()).at("$.meta.total").toString()).isEqualTo("1");
        assertThat(api.get("/api/results?club=" + new ObjectId()).at("$.meta.total").toString()).isEqualTo("0");
        Response badClub = api.get("/api/results?club=nope");
        assertThat(badClub.status()).isEqualTo(400);
        assertThat(badClub.message()).isEqualTo("Invalid value");
        Map<String, Object> meta = api.get("/api/results?page=2&limit=1").at("$.meta");
        assertThat(meta).containsEntry("page", 2).containsEntry("limit", 1).containsEntry("total", 1).containsEntry("totalPages", 1);
    }

    @Test
    void resultsAreStoredLikeTheMongooseDocuments() {
        events.start(event);
        publishedRound("Heat 1");
        api.as(president).put("/api/events/" + event.getId() + "/results", row("summary", "Alice won the quiz.", "awards", List.of(
                row("title", "Winner", "position", 1, "recipientUser", alice.id(), "prize", "Books"))));

        Document stored = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(event.getId()))), Document.class, "eventresults");
        assertThat(stored.get("event")).isInstanceOf(ObjectId.class);
        assertThat(stored.get("club")).isInstanceOf(ObjectId.class);
        assertThat(stored.get("updatedAt")).isInstanceOf(Date.class);
        assertThat(stored).doesNotContainKey("_class");
        Document round = stored.getList("rounds", Document.class).get(0);
        assertThat(round.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(round.get("createdBy")).isInstanceOf(ObjectId.class);
        assertThat(round.get("publishedBy")).isInstanceOf(ObjectId.class);
        assertThat(round.getString("status")).isEqualTo("PUBLISHED");
        assertThat(round.get("createdAt")).isInstanceOf(Date.class);
        assertThat(round.getList("entries", Document.class).get(0).get("recipientUser")).isInstanceOf(ObjectId.class);
        Document award = stored.getList("awards", Document.class).get(0);
        assertThat(award.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(award.get("recipientUser")).isInstanceOf(ObjectId.class);
        assertThat(award.getString("recipientName")).isEqualTo("Alice");

        Response view = api.as(president).get("/api/events/" + event.getId() + "/results");
        Map<String, Object> recipient = view.at("$.data.awards[0].recipientUser");
        assertThat(recipient).containsOnlyKeys("_id", "name", "email", "departmentCode", "batchCode").containsEntry("name", "Alice");
        assertThat((Map<String, Object>) view.at("$.data.rounds[0].entries[0].recipientUser")).containsOnlyKeys("_id", "name", "departmentCode", "batchCode");
        assertThat((Map<String, Object>) view.at("$.data.createdBy")).containsOnlyKeys("_id", "name").containsEntry("name", "President");
        assertThat((Map<String, Object>) view.at("$.data.event")).containsKeys("_id", "title", "poster", "startAt", "endAt", "status", "club", "category", "registeredCount",
                "shortDescription").containsEntry("registeredCount", 2);
        assertThat(mongo.exists(Query.query(Criteria.where("action").is("RESULT_SAVED").and("metadata.eventId").is(new ObjectId(event.getId()))), "auditlogs")).isTrue();
    }

    @Test
    void validationMessagesMatchTheExpressValidators() {
        events.start(event);
        Response missingSummary = api.as(president).put("/api/events/" + event.getId() + "/results", row("awards", List.of()));
        assertThat(missingSummary.status()).isEqualTo(400);
        assertThat(missingSummary.message()).isEqualTo("Invalid value. Result summary is required");
        assertThat(missingSummary.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(api.as(president).put("/api/events/" + event.getId() + "/results", row("summary", "abc")).message()).isEqualTo("Result summary is required");
        assertThat(api.as(president).put("/api/events/" + event.getId() + "/results", row("summary", "Long enough", "awards", List.of(row("position", 1, "recipientName", "X"))))
                .message()).isEqualTo("Invalid value. Each award needs a title");

        assertThat(api.as(president).post(rounds(), Map.of()).message()).isEqualTo("Invalid value. Round name must be 1-80 characters");
        assertThat(api.as(president).post(rounds(), row("name", "Heat", "entries", List.of(row("rank", 1.5, "teamName", "A")))).message())
                .isEqualTo("Rank must be a positive whole number");
        assertThat(api.as(president).post(rounds(), row("name", "Heat", "entries", List.of(row("teamName", "A", "score", "x".repeat(41))))).message())
                .isEqualTo("Score must be at most 40 characters");
        assertThat(api.as(president).post(rounds(), row("name", "Heat", "entries", List.of(row("teamName", "A"), row("recipientName", "a")))).message())
                .isEqualTo("Row 2: a is listed twice");

        assertThat(api.as(president).put("/api/events/nope/results/rounds/bad", Map.of()).message()).isEqualTo("Invalid id. Invalid roundId");
        assertThat(api.as(president).post("/api/events/" + event.getId() + "/results/rounds/" + new ObjectId() + "/publish").message()).isEqualTo("Round not found");
        assertThat(api.as(president).post("/api/events/" + new ObjectId() + "/results/publish").message()).isEqualTo("Event not found");
        Response noDraft = api.as(president).post("/api/events/" + event.getId() + "/results/publish");
        assertThat(noDraft.status()).isEqualTo(404);
        assertThat(noDraft.message()).isEqualTo("Save the final results draft before publishing");
        assertThat(api.put("/api/events/" + event.getId() + "/results", row("summary", "Long enough")).status()).isEqualTo(401);
    }

    @Test
    void twoPresidentsClickingPublishAtOnceAnnounceTheRoundOnce() throws Exception {
        Response created = api.as(coordinator).post(rounds(), row("name", "Final", "entries", List.of(row("rank", 1, "recipientUser", alice.id()))));
        String id = created.string("$.data.rounds[0]._id");
        List<Callable<Response>> clicks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clicks.add(() -> api.as(president).post(rounds() + "/" + id + "/publish"));
        }
        List<Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (Future<Response> future : pool.invokeAll(clicks)) {
                responses.add(future.get());
            }
        }
        assertThat(responses.stream().filter(res -> res.status() == 200)).hasSize(1);
        assertThat(responses.stream().filter(res -> res.status() == 409)).hasSize(4).allSatisfy(res -> assertThat(res.message()).isEqualTo("This round is already published"));
        assertThat(mongo.count(Query.query(Criteria.where("type").is("ROUND_RESULTS")), Notification.class)).isEqualTo(2);

        // Organisers editing different rounds at the same moment keep both changes.
        String other = api.as(coordinator).post(rounds(), row("name", "Warm-up")).string("$.data.rounds[1]._id");
        List<Callable<Response>> edits = List.of(
                () -> api.as(president).put(rounds() + "/" + id, row("description", "The last round.")),
                () -> api.as(coordinator).put(rounds() + "/" + other, row("description", "Just for fun.")));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<Response> future : pool.invokeAll(edits)) {
                assertThat(future.get().status()).isEqualTo(200);
            }
        }
        Response view = api.as(president).get("/api/events/" + event.getId() + "/results");
        assertThat((List<String>) view.at("$.data.rounds[*].description")).containsExactly("The last round.", "Just for fun.");
    }
}
