package com.campusconnect.feature.hackathon;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.HackathonEntry;
import com.campusconnect.domain.result.HackathonJudge;
import com.campusconnect.domain.result.JudgeScore;
import com.campusconnect.domain.result.JudgingCriterion;
import com.campusconnect.domain.result.ProblemStatement;
import com.campusconnect.feature.result.EventFixtures;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** What the Node tests don't cover: judges and teams acting at the same moment, the deadline sweeper, schema limits. */
class HackathonConcurrencyIntegrationTest extends IntegrationTest {

    @Autowired
    HackathonSweeper sweeper;

    Venues venues;
    Club club;
    TestUser president;
    EventFixtures events;
    Event hack;

    @BeforeEach
    void runningHackathon() {
        venues = data.seedReferenceData();
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(s -> s.name("President"));
        club = data.activeClub("Coding Club", mentor, president);
        events = new EventFixtures(mongo);
        hack = events.published(club, venues.auditorium(), 1, event -> {
            event.setTitle("Speed Hack");
            event.setCategory("HACKATHON");
            event.setStartAt(Instant.now().minus(Duration.ofHours(3)));
            event.setEndAt(Instant.now().plus(Duration.ofHours(1)));
        });
    }

    static Instant minutes(long minutes) {
        return Instant.now().plus(Duration.ofMinutes(minutes));
    }

    Hackathon hackathon(Instant reveal, Instant selection, Instant repo, Instant submission, List<TestUser> judges, List<ProblemStatement> problems) {
        Hackathon hackathon = new Hackathon();
        hackathon.setEvent(hack.getId());
        hackathon.setClub(club.getId());
        hackathon.setRevealAt(reveal);
        hackathon.setSelectionDeadline(selection);
        hackathon.setRepoDeadline(repo);
        hackathon.setSubmissionDeadline(submission);
        hackathon.setCriteria(HackathonSupport.defaultCriteria());
        List<HackathonJudge> panel = new ArrayList<>();
        for (TestUser judge : judges) {
            HackathonJudge member = new HackathonJudge();
            member.setUser(judge.id());
            panel.add(member);
        }
        hackathon.setJudges(panel);
        hackathon.setProblemStatements(new ArrayList<>(problems));
        return mongo.insert(hackathon);
    }

    HackathonEntry submittedEntry(TestUser owner) {
        events.register(hack, owner);
        HackathonEntry entry = new HackathonEntry();
        entry.setEvent(hack.getId());
        entry.setEntryKey("user:" + owner.id());
        entry.setOwner(owner.id());
        entry.setName(owner.name());
        entry.getProject().setTitle("Project of " + owner.name());
        entry.getProject().setRepoUrl("https://github.com/x/y");
        entry.setRepoSubmittedAt(minutes(-100));
        entry.setSubmittedAt(minutes(-30));
        return mongo.insert(entry);
    }

    String url(String path) {
        return "/api/events/" + hack.getId() + "/hackathon" + path;
    }

    static Map<String, Object> marks(Hackathon hackathon, double score, String comment) {
        List<Map<String, Object>> marks = new ArrayList<>();
        for (JudgingCriterion criterion : hackathon.getCriteria()) {
            marks.add(Map.of("criterion", criterion.getId(), "score", score));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("marks", marks);
        body.put("comment", comment);
        return body;
    }

    static List<Response> concurrently(List<Callable<Response>> calls) throws Exception {
        List<Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            for (Future<Response> future : pool.invokeAll(calls)) {
                responses.add(future.get());
            }
        }
        return responses;
    }

    @Test
    void judgesScoringTheSameProjectAtTheSameMomentKeepEveryScore() throws Exception {
        List<TestUser> judges = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            judges.add(data.faculty("CE", "Judge " + i));
        }
        Hackathon hackathon = hackathon(minutes(-180), minutes(-150), minutes(-120), minutes(-1), judges, List.of());
        HackathonEntry entry = submittedEntry(data.student(s -> s.name("Solo")));

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < judges.size(); i++) {
            TestUser judge = judges.get(i);
            double score = i + 1;
            calls.add(() -> api.as(judge).put(url("/judging/" + entry.getId()), marks(hackathon, score, "From judge " + score)));
        }
        assertThat(concurrently(calls)).allSatisfy(res -> assertThat(res.status()).as(res.body()).isEqualTo(200));

        HackathonEntry stored = mongo.findById(entry.getId(), HackathonEntry.class);
        assertThat(stored.getScores()).hasSize(8);
        assertThat(stored.getScores()).extracting(JudgeScore::getJudge).containsExactlyInAnyOrderElementsOf(judges.stream().map(TestUser::id).toList());
        for (JudgeScore score : stored.getScores()) {
            int judgeNumber = judges.stream().map(TestUser::id).toList().indexOf(score.getJudge()) + 1;
            assertThat(score.getTotal()).isEqualTo(4.0 * judgeNumber);
            assertThat(score.getMarks()).hasSize(4);
        }

        Response board = api.as(president).get(url("/leaderboard"));
        assertThat((Integer) board.at("$.data.rows[0].judges")).isEqualTo(8);
        assertThat(((Number) board.at("$.data.rows[0].average")).doubleValue()).isEqualTo(18.0);
        assertThat((Integer) board.at("$.data.stats.fullyScored")).isEqualTo(1);
    }

    @Test
    void aJudgeSendingSeveralScoresAtOnceEndsUpWithExactlyOne() throws Exception {
        TestUser judge = data.faculty("CE", "Busy Judge");
        Hackathon hackathon = hackathon(minutes(-180), minutes(-150), minutes(-120), minutes(-1), List.of(judge), List.of());
        HackathonEntry entry = submittedEntry(data.student(s -> s.name("Solo")));

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            double score = i;
            calls.add(() -> api.as(judge).put(url("/judging/" + entry.getId()), marks(hackathon, score, "")));
        }
        assertThat(concurrently(calls)).allSatisfy(res -> assertThat(res.status()).isEqualTo(200));

        List<JudgeScore> scores = mongo.findById(entry.getId(), HackathonEntry.class).getScores();
        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).getJudge()).isEqualTo(judge.id());
        assertThat(scores.get(0).getMarks()).extracting(mark -> mark.getScore()).containsOnly(scores.get(0).getMarks().get(0).getScore());
        // A judge's mark that isn't a whole number is kept to one decimal.
        Response decimal = api.as(judge).put(url("/judging/" + entry.getId()), marks(hackathon, 7.25, "Nice"));
        assertThat(((Number) decimal.at("$.data.entries[0].myScore.marks[0].score")).doubleValue()).isEqualTo(7.3);
        assertThat(api.as(judge).put(url("/judging/" + new ObjectId()), marks(hackathon, 5, "")).status()).isEqualTo(404);
    }

    @Test
    void aProblemLimitedToOneTeamGoesToExactlyOneTeamWhenManyChooseItAtOnce() throws Exception {
        ProblemStatement limited = new ProblemStatement();
        limited.setTitle("Canteen queue");
        limited.setDescription("Cut the lunch-hour queue at the canteen with pre-ordering.");
        limited.setMaxTeams(1);
        hackathon(minutes(-60), minutes(20), minutes(40), minutes(50), List.of(), List.of(limited));

        List<TestUser> students = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TestUser student = data.student(s -> s.name("Solo"));
            events.register(hack, student);
            students.add(student);
        }
        List<Callable<Response>> calls = new ArrayList<>();
        for (TestUser student : students) {
            calls.add(() -> api.as(student).put(url("/entry/problem"), Map.of("problemId", limited.getId())));
        }
        List<Response> responses = concurrently(calls);
        assertThat(responses.stream().filter(res -> res.status() == 200)).hasSize(1);
        assertThat(responses.stream().filter(res -> res.status() == 409)).hasSize(5)
                .allSatisfy(res -> assertThat(res.message()).isEqualTo("\"Canteen queue\" already has its 1 team. Choose another problem."));
        assertThat(mongo.count(Query.query(Criteria.where("event").is(new ObjectId(hack.getId())).and("problemStatement").is(new ObjectId(limited.getId()))),
                HackathonEntry.class)).isEqualTo(1);
    }

    @Test
    void theSweeperSendsEachDeadlineReminderOnce() {
        ProblemStatement problem = new ProblemStatement();
        problem.setTitle("Smart parking");
        problem.setDescription("Help students find a free parking spot.");
        TestUser judge = data.faculty("CE", "Judge");
        Instant repoDeadline = minutes(45);
        Instant submissionDeadline = minutes(55);
        hackathon(minutes(-10), minutes(30), repoDeadline, submissionDeadline, List.of(judge), List.of(problem));
        TestUser picked = data.student(s -> s.name("Picked"));
        TestUser lagging = data.student(s -> s.name("Lagging"));
        events.register(hack, picked);
        events.register(hack, lagging);
        HackathonEntry entry = new HackathonEntry();
        entry.setEvent(hack.getId());
        entry.setEntryKey("user:" + picked.id());
        entry.setOwner(picked.id());
        entry.setName("Picked");
        entry.setProblemStatement(problem.getId());
        mongo.insert(entry);

        Instant now = Instant.now();
        // REVEAL (problems are live), SELECTION_1H (30 min left), REPO_1H and SUBMISSION_1H (both within the hour).
        assertThat(sweeper.sweepHackathons(now)).isEqualTo(4);
        assertThat(sweeper.sweepHackathons(now)).isZero();

        assertThat(titlesFor(picked)).containsExactlyInAnyOrder("Problem statements are live: Speed Hack",
                "Add your code repository by " + CampusTime.formatTime(repoDeadline),
                "Submit your project by " + CampusTime.formatTime(submissionDeadline));
        assertThat(titlesFor(lagging)).hasSize(4).anyMatch(title -> title.startsWith("Choose a problem statement by "));
        assertThat(mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(hack.getId()))), Hackathon.class).getNotified())
                .containsExactlyInAnyOrder("REVEAL", "SELECTION_1H", "REPO_1H", "SUBMISSION_1H");
        assertThat(titlesFor(judge)).isEmpty();
    }

    List<String> titlesFor(TestUser user) {
        return mongo.find(Query.query(Criteria.where("user").is(new ObjectId(user.id())).and("type").is("HACKATHON_UPDATE")), Notification.class).stream()
                .map(Notification::getTitle).toList();
    }

    @Test
    void settingsFollowTheSchemaLimitsAndOnlyHackathonsHaveThem() {
        hackathon(minutes(-180), minutes(-150), minutes(-120), minutes(30), List.of(), List.of());
        String title = "A".repeat(125);
        Response tooLong = api.as(president).put(url(""), Map.of("agenda", List.of(Map.of("title", title, "startsAt", Instant.now().toString()))));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.message()).isEqualTo("Validation failed: Path `title` (`" + "A".repeat(30) + "...`, length 125) is longer than the maximum allowed length (120).");
        Response early = api.as(president).put(url(""), Map.of("agenda", List.of(Map.of("title", "Opening", "startsAt", minutes(-60 * 30).toString()))));
        assertThat(early.message()).isEqualTo("Agenda item 1 must be during the event");
        Response badDate = api.as(president).put(url(""), Map.of("revealAt", "not a date"));
        assertThat(badDate.message()).isEqualTo("Problem statement release is not a valid date and time");
        Response criteria = api.as(president).put(url(""), Map.of("criteria", List.of(Map.of("name", "Impact", "maxScore", 101))));
        assertThat(criteria.message()).isEqualTo("Criterion 1: the maximum score must be 1-100");
        Response agenda = api.as(president).put(url(""), Map.of("agenda", List.of(Map.of("title", "Opening", "startsAt", minutes(-170).toString(), "note", "Welcome"))));
        assertThat(agenda.status()).isEqualTo(200);
        assertThat((List<String>) agenda.at("$.data.agenda[*].title")).containsExactly("Opening");

        Event talk = events.published(club, venues.hall(), 3, event -> event.setTitle("Tech Talk"));
        Response notHackathon = api.as(president).get("/api/events/" + talk.getId() + "/hackathon");
        assertThat(notHackathon.status()).isEqualTo(409);
        assertThat(notHackathon.message()).isEqualTo("This event isn't a hackathon");
        assertThat(api.as(president).get("/api/events/" + new ObjectId() + "/hackathon").status()).isEqualTo(404);
        assertThat(api.as(president).delete(url("/problems/nope")).message()).isEqualTo("Invalid problemId");
        assertThat(api.as(president).put("/api/events/x/hackathon/judging/y", Map.of("marks", List.of())).message()).isEqualTo("Invalid id. Invalid entryId");
        assertThat(api.get(url("")).status()).isEqualTo(401);
    }
}
