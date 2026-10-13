package com.campusconnect.feature.hackathon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultEntry;
import com.campusconnect.feature.result.EventFixtures;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Port of tests/integration/hackathon.test.js ("hackathon mode"). The Node tests walk one hackathon from setup to
 * certificates in order; here every test starts from an empty database and replays the stages before it. The event,
 * the teams and their registrations are written directly (those routes belong to the events feature).
 */
class HackathonIntegrationTest extends IntegrationTest {

    static final long HOUR = 3_600_000L;

    Venues venues;
    TestUser mentor;
    TestUser facultyJudge;
    TestUser studentJudge;
    TestUser president;
    TestUser alpha;
    TestUser alpha2;
    TestUser beta;
    TestUser outsider;
    Club club;
    EventFixtures events;
    Event hack;
    List<Map<String, Object>> problems;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        mentor = data.faculty("CE", "Dr Mentor");
        facultyJudge = data.faculty("CE", "Dr Judge");
        studentJudge = data.student(s -> s.name("Student Judge"));
        president = data.student(s -> s.name("President"));
        alpha = data.student(s -> s.name("Alpha Lead"));
        alpha2 = data.student(s -> s.name("Alpha Two"));
        beta = data.student(s -> s.name("Beta Lead"));
        outsider = data.student(s -> s.name("Outsider"));
        club = data.activeClub("Coding Club", mentor, president);
        events = new EventFixtures(mongo);
    }

    String url(String path) {
        return "/api/events/" + hack.getId() + "/hackathon" + path;
    }

    static Instant minutesFromNow(long minutes) {
        return Instant.now().plus(Duration.ofMinutes(minutes));
    }

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---------------------------------------------------------------- the stages the Node tests go through

    /** Test 1: an overnight hackathon, 18:00 to 06:00 the next morning, published. */
    void overnightHackathon() {
        hack = events.published(club, venues.auditorium(), 10, event -> {
            event.setTitle("HackNight");
            event.setCategory("HACKATHON");
            event.setEndDate(CampusTime.dateKeyToInstant(TestData.futureDate(11)));
            event.setStartTime("18:00");
            event.setEndTime("06:00");
            event.setStartAt(CampusTime.combineDateAndTime(TestData.futureDate(10), "18:00"));
            event.setEndAt(CampusTime.combineDateAndTime(TestData.futureDate(11), "06:00"));
            EventFixtures.team(1, 3).accept(event);
        });
    }

    /** Test 2: teams Alpha (two students) and Beta register; two problem statements are added. */
    void teamsAndProblems() {
        overnightHackathon();
        events.team(hack, "Alpha", alpha, alpha2);
        events.team(hack, "Beta", beta);
        assertThat(api.as(president).post(url("/problems"), body("title", "Smart campus parking",
                "description", "Help students find a free parking spot near their classroom.", "track", "Campus")).status()).isEqualTo(200);
        Response added = api.as(president).post(url("/problems"), body("title", "Canteen queue",
                "description", "Cut the lunch-hour queue at the canteen with pre-ordering.", "maxTeams", 1));
        problems = added.at("$.data.problemStatements");
    }

    String problemId(int index) {
        return (String) problems.get(index).get("_id");
    }

    /** Test 3: a faculty judge and a student judge. */
    void judges() {
        teamsAndProblems();
        assertThat(api.as(president).post(url("/judges"), body("userId", facultyJudge.id())).status()).isEqualTo(200);
        assertThat(api.as(president).post(url("/judges"), body("userId", studentJudge.id())).status()).isEqualTo(200);
    }

    /** Test 4: the event has started; the problems are out until the selection deadline; Alpha picks one. */
    void selectionOpen() {
        judges();
        events.update(hack, new Update().set("startAt", minutesFromNow(-60)).set("endAt", minutesFromNow(11 * 60))
                .set("registrationEnd", minutesFromNow(-120)).set("registrationStart", minutesFromNow(-24 * 60)));
        Response settings = api.as(president).put(url(""), body(
                "revealAt", minutesFromNow(-60).toString(),
                "selectionDeadline", minutesFromNow(30).toString(),
                "repoDeadline", minutesFromNow(90).toString(),
                "submissionDeadline", minutesFromNow(180).toString()));
        assertThat(settings.status()).as(settings.body()).isEqualTo(200);
    }

    void problemsChosen() {
        selectionOpen();
        assertThat(api.as(alpha2).put(url("/entry/problem"), body("problemId", problemId(1))).status()).isEqualTo(200);
    }

    /** Test 5: Beta picks the other problem; both teams add their repository. */
    void repositoriesAdded() {
        problemsChosen();
        assertThat(api.as(beta).put(url("/entry/problem"), body("problemId", problemId(0))).status()).isEqualTo(200);
        assertThat(api.as(beta).put(url("/entry/repo"), body("repoUrl", "https://github.com/beta/parkit")).status()).isEqualTo(200);
        assertThat(api.as(alpha).put(url("/entry/repo"), body("repoUrl", "https://github.com/alpha/quickbite")).status()).isEqualTo(200);
    }

    void setDeadlines(Instant selection, Instant repo, Instant submission) {
        Update update = new Update().set("selectionDeadline", selection).set("repoDeadline", repo);
        if (submission != null) {
            update.set("submissionDeadline", submission);
        }
        mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(hack.getId()))), update, Hackathon.class);
    }

    Map<String, Object> parkIt() {
        return body("title", "ParkIt", "summary", "Live map of free parking spots using cameras.", "videoUrl", "https://youtu.be/parkit", "repoUrl", "https://github.com/sneaky/change");
    }

    Map<String, Object> quickBite() {
        return body("title", "QuickBite", "summary", "Pre-order canteen food and pick it up without queuing.", "demoUrl", "https://quickbite.example.com");
    }

    /** Test 6: the repository deadline passed; both teams make their final submission (Beta first). */
    void projectsSubmitted() {
        repositoriesAdded();
        setDeadlines(minutesFromNow(-20), minutesFromNow(-10), null);
        assertThat(api.as(beta).put(url("/entry/project"), parkIt()).status()).isEqualTo(200);
        assertThat(api.as(alpha).put(url("/entry/project"), quickBite()).status()).isEqualTo(200);
    }

    /** Test 7: the submission deadline passed; judging is open. */
    void judgingOpen() {
        projectsSubmitted();
        setDeadlines(minutesFromNow(-30), minutesFromNow(-20), minutesFromNow(-1));
    }

    List<Map<String, Object>> marks(List<Map<String, Object>> criteria, double... scores) {
        List<Map<String, Object>> marks = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            marks.add(body("criterion", criteria.get(i).get("_id"), "score", scores[i]));
        }
        return marks;
    }

    Map<String, String> entryIds(Response panel) {
        Map<String, String> ids = new LinkedHashMap<>();
        List<Map<String, Object>> entries = panel.at("$.data.entries");
        entries.forEach(entry -> ids.put((String) entry.get("name"), (String) entry.get("_id")));
        return ids;
    }

    /** Test 8: both judges score both projects. */
    void scored() {
        judgingOpen();
        Response panel = api.as(facultyJudge).get(url("/judging"));
        List<Map<String, Object>> criteria = panel.at("$.data.criteria");
        Map<String, String> byName = entryIds(panel);
        assertThat(api.as(facultyJudge).put(url("/judging/" + byName.get("Alpha")), body("marks", marks(criteria, 8, 7, 9, 6), "comment", "Great demo")).status()).isEqualTo(200);
        assertThat(api.as(facultyJudge).put(url("/judging/" + byName.get("Beta")), body("marks", marks(criteria, 5, 5, 5, 5))).status()).isEqualTo(200);
        assertThat(api.as(studentJudge).put(url("/judging/" + byName.get("Alpha")), body("marks", marks(criteria, 9, 8, 8, 7))).status()).isEqualTo(200);
        assertThat(api.as(studentJudge).put(url("/judging/" + byName.get("Beta")), body("marks", marks(criteria, 6, 6, 6, 6))).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- the tests

    /**
     * Node creates the event through POST /api/events (the events feature) and checks it lasts 12 hours. Ported to what
     * this area does with an overnight event: the default deadlines fit inside it, across midnight.
     */
    @Test
    void aHackathonCanRunOvernightItEndsTheNextMorning() {
        overnightHackathon();
        assertThat(Duration.between(hack.getStartAt(), hack.getEndAt())).isEqualTo(Duration.ofHours(12));

        Response view = api.as(president).get(url(""));
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.message()).isEqualTo("Hackathon fetched");
        assertThat(view.string("$.data.phase")).isEqualTo("UPCOMING");
        Instant start = hack.getStartAt();
        assertThat(Instant.parse(view.string("$.data.revealAt"))).isEqualTo(start);
        assertThat(Instant.parse(view.string("$.data.selectionDeadline"))).isEqualTo(start.plusMillis(2 * HOUR));
        assertThat(Instant.parse(view.string("$.data.repoDeadline"))).isEqualTo(start.plusMillis(2 * HOUR + Math.round(12 * HOUR * 0.15)));
        assertThat(Instant.parse(view.string("$.data.submissionDeadline"))).isEqualTo(hack.getEndAt().minusMillis(HOUR));
        assertThat((Integer) view.at("$.data.maxTotal")).isEqualTo(40);
        assertThat((List<String>) view.at("$.data.criteria[*].name")).containsExactly("Innovation", "Technical complexity", "Design & usability", "Presentation");
    }

    @Test
    void teamsRegisterWithoutAProblemStatementProblemsStayHiddenUntilTheEventStarts() {
        overnightHackathon();
        events.team(hack, "Alpha", alpha, alpha2);
        events.team(hack, "Beta", beta);

        Response first = api.as(president).post(url("/problems"), body("title", "Smart campus parking",
                "description", "Help students find a free parking spot near their classroom.", "track", "Campus"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.message()).isEqualTo("Problem statement added");
        Response added = api.as(president).post(url("/problems"), body("title", "Canteen queue",
                "description", "Cut the lunch-hour queue at the canteen with pre-ordering.", "maxTeams", 1));
        problems = added.at("$.data.problemStatements");
        assertThat(problems).hasSize(2);
        assertThat(api.as(alpha).post(url("/problems"), body("title", "Mine", "description", "Students can't add problems.")).status()).isEqualTo(403);

        Response view = api.as(alpha).get(url(""));
        assertThat(view.string("$.data.phase")).isEqualTo("UPCOMING");
        assertThat((Integer) view.at("$.data.problemCount")).isEqualTo(2);
        assertThat((List<?>) view.at("$.data.problemStatements")).isEmpty();
        assertThat((Boolean) view.at("$.data.viewer.isParticipant")).isTrue();
        assertThat((Boolean) view.at("$.data.viewer.canChooseProblem")).isFalse();
        assertThat((List<?>) api.as(outsider).get(url("")).at("$.data.problemStatements")).isEmpty();
        assertThat((List<?>) api.as(mentor).get(url("")).at("$.data.problemStatements")).hasSize(2);
        assertThat(api.as(alpha).put(url("/entry/problem"), body("problemId", problemId(0))).status()).isEqualTo(409);
    }

    @Test
    void judgesAreFacultyOrStudentsWhoArentTakingPartAndAreToldAboutIt() {
        teamsAndProblems();
        Response faculty = api.as(president).post(url("/judges"), body("userId", facultyJudge.id()));
        assertThat(faculty.status()).isEqualTo(200);
        assertThat(faculty.message()).isEqualTo("Judge added");
        Response blocked = api.as(president).post(url("/judges"), body("userId", alpha.id()));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.message()).contains("taking part");
        Response added = api.as(president).post(url("/judges"), body("userId", studentJudge.id()));
        List<String> names = new ArrayList<>(added.at("$.data.judges[*].name"));
        names.sort(null);
        assertThat(names).containsExactly("Dr Judge", "Student Judge");
        assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(facultyJudge.id())).and("type").is("JUDGE_INVITE")), Notification.class)).isTrue();
    }

    @Test
    void onceTheProblemsAreOutEachTeamPicksOneBeforeTheSelectionDeadline() {
        judges();
        events.update(hack, new Update().set("startAt", minutesFromNow(-60)).set("endAt", minutesFromNow(11 * 60))
                .set("registrationEnd", minutesFromNow(-120)).set("registrationStart", minutesFromNow(-24 * 60)));
        Response settings = api.as(president).put(url(""), body(
                "revealAt", minutesFromNow(-60).toString(),
                "selectionDeadline", minutesFromNow(30).toString(),
                "repoDeadline", minutesFromNow(90).toString(),
                "submissionDeadline", minutesFromNow(180).toString()));
        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.message()).isEqualTo("Hackathon settings saved");
        assertThat(settings.string("$.data.phase")).isEqualTo("SELECTION");
        // Each stage's deadline must come after the one before.
        assertThat(api.as(president).put(url(""), body("selectionDeadline", minutesFromNow(100).toString())).status()).isEqualTo(400);
        assertThat(api.as(president).put(url(""), body("repoDeadline", minutesFromNow(200).toString())).status()).isEqualTo(400);

        Response view = api.as(alpha2).get(url(""));
        assertThat((List<String>) view.at("$.data.problemStatements[*].title")).containsExactly("Smart campus parking", "Canteen queue");

        Response chose = api.as(alpha2).put(url("/entry/problem"), body("problemId", problemId(1)));
        assertThat(chose.status()).isEqualTo(200);
        assertThat(chose.message()).isEqualTo("Problem statement chosen");
        assertThat(chose.string("$.data.myEntry.problemStatement.title")).isEqualTo("Canteen queue");
        // The teammate sees the same entry and was told.
        assertThat(api.as(alpha).get(url("")).string("$.data.myEntry.problemStatement.title")).isEqualTo("Canteen queue");
        assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(alpha.id())).and("type").is("HACKATHON_UPDATE").and("title").regex("chose \"Canteen queue\"")),
                Notification.class)).isTrue();

        // "Canteen queue" takes one team only.
        assertThat(api.as(beta).put(url("/entry/problem"), body("problemId", problemId(1))).status()).isEqualTo(409);
        assertThat(api.as(outsider).put(url("/entry/problem"), body("problemId", problemId(0))).status()).isEqualTo(403);
    }

    @Test
    void stage2TeamsAddTheirCodeRepositoryAfterChoosingAProblemBeforeTheRepositoryDeadline() {
        problemsChosen();
        Response early = api.as(beta).put(url("/entry/repo"), body("repoUrl", "https://github.com/beta/parkit"));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.message()).contains("Choose your problem");
        api.as(beta).put(url("/entry/problem"), body("problemId", problemId(0)));

        assertThat(api.as(beta).put(url("/entry/repo"), body("repoUrl", "ftp://nope.example")).status()).isEqualTo(400);
        Response saved = api.as(beta).put(url("/entry/repo"), body("repoUrl", "https://github.com/beta/parkit"));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.message()).isEqualTo("Repository saved");
        assertThat(saved.string("$.data.myEntry.project.repoUrl")).isEqualTo("https://github.com/beta/parkit");
        assertThat((Boolean) saved.at("$.data.viewer.canSubmitRepo")).isTrue();
        assertThat((Boolean) saved.at("$.data.viewer.canSubmit")).isFalse();
        assertThat(api.as(alpha).put(url("/entry/repo"), body("repoUrl", "https://github.com/alpha/quickbite")).status()).isEqualTo(200);

        // The final submission isn't open yet.
        Response tooSoon = api.as(alpha).put(url("/entry/project"), quickBite());
        assertThat(tooSoon.status()).isEqualTo(409);
        assertThat(tooSoon.message()).contains("opens at");
    }

    @Test
    void stage3AfterTheRepositoryDeadlineTeamsMakeTheirFinalSubmission() {
        repositoriesAdded();
        setDeadlines(minutesFromNow(-20), minutesFromNow(-10), null);
        assertThat(api.as(beta).put(url("/entry/repo"), body("repoUrl", "https://github.com/beta/other")).status()).isEqualTo(409);
        Response view = api.as(beta).get(url(""));
        assertThat(view.string("$.data.phase")).isEqualTo("FINAL");
        assertThat((Boolean) view.at("$.data.viewer.canSubmitRepo")).isFalse();
        assertThat((Boolean) view.at("$.data.viewer.canSubmit")).isTrue();

        assertThat(api.as(beta).put(url("/entry/project"), body("title", "ParkIt", "summary", "Live map of free parking spots using cameras.")).status()).isEqualTo(400);
        Response submitted = api.as(beta).put(url("/entry/project"), parkIt());
        assertThat(submitted.status()).isEqualTo(200);
        assertThat(submitted.message()).isEqualTo("Project submitted");
        // The repository stays as it was at the repository deadline.
        assertThat(submitted.string("$.data.myEntry.project.title")).isEqualTo("ParkIt");
        assertThat(submitted.string("$.data.myEntry.project.repoUrl")).isEqualTo("https://github.com/beta/parkit");
        assertThat(submitted.string("$.data.myEntry.project.videoUrl")).isEqualTo("https://youtu.be/parkit");
        Response alphaProject = api.as(alpha).put(url("/entry/project"), quickBite());
        assertThat(alphaProject.status()).isEqualTo(200);
        // Links are stored the way the browser's URL parser writes them.
        assertThat(alphaProject.string("$.data.myEntry.project.demoUrl")).isEqualTo("https://quickbite.example.com/");
    }

    @Test
    void judgingOpensAtTheSubmissionDeadlineLateSubmissionsAreRefused() {
        projectsSubmitted();
        Response closed = api.as(facultyJudge).get(url("/judging"));
        assertThat((Boolean) closed.at("$.data.open")).isFalse();
        assertThat((List<?>) closed.at("$.data.entries")).isEmpty();
        setDeadlines(minutesFromNow(-30), minutesFromNow(-20), minutesFromNow(-1));
        assertThat(api.as(alpha).put(url("/entry/project"), body("title", "Late", "summary", "Trying to change after the deadline.", "repoUrl", "https://github.com/a/b"))
                .status()).isEqualTo(409);

        Response panel = api.as(facultyJudge).get(url("/judging"));
        assertThat(panel.message()).isEqualTo("Judging panel fetched");
        assertThat((Boolean) panel.at("$.data.open")).isTrue();
        assertThat((List<String>) panel.at("$.data.entries[*].name")).containsExactly("Beta", "Alpha");
        assertThat(api.as(outsider).get(url("/judging")).status()).isEqualTo(403);
    }

    @Test
    void judgesScoreEachProjectOnTheCriteriaTheLeaderboardAveragesThem() {
        judgingOpen();
        Response judging = api.as(facultyJudge).get(url("/judging"));
        List<Map<String, Object>> criteria = judging.at("$.data.criteria");
        Map<String, String> byName = entryIds(judging);

        assertThat(api.as(facultyJudge).put(url("/judging/" + byName.get("Alpha")), body("marks", marks(criteria, 11, 7, 9, 6))).status()).isEqualTo(400);
        Response saved = api.as(facultyJudge).put(url("/judging/" + byName.get("Alpha")), body("marks", marks(criteria, 8, 7, 9, 6), "comment", "Great demo"));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.message()).isEqualTo("Score saved");
        api.as(facultyJudge).put(url("/judging/" + byName.get("Beta")), body("marks", marks(criteria, 5, 5, 5, 5)));
        api.as(studentJudge).put(url("/judging/" + byName.get("Alpha")), body("marks", marks(criteria, 9, 8, 8, 7)));
        Response mine = api.as(studentJudge).put(url("/judging/" + byName.get("Beta")), body("marks", marks(criteria, 6, 6, 6, 6)));
        assertThat((Integer) mine.at("$.data.scored")).isEqualTo(2);
        assertThat(api.as(alpha).put(url("/judging/" + byName.get("Beta")), body("marks", marks(criteria, 0, 0, 0, 0))).status()).isEqualTo(403);

        Response board = api.as(president).get(url("/leaderboard"));
        assertThat(board.message()).isEqualTo("Leaderboard fetched");
        List<Map<String, Object>> rows = board.at("$.data.rows");
        assertThat(rows).extracting(row -> row.get("name"), row -> row.get("rank"), row -> ((Number) row.get("average")).doubleValue(), row -> row.get("judges"))
                .containsExactly(tuple("Alpha", 1, 31.0, 2), tuple("Beta", 2, 22.0, 2));
        Map<String, Object> stats = board.at("$.data.stats");
        assertThat(stats).containsEntry("entries", 2).containsEntry("submitted", 2).containsEntry("fullyScored", 2);
        assertThat((List<Map<String, Object>>) board.at("$.data.rows[0].comments")).containsExactly(Map.of("judge", "Dr Judge", "comment", "Great demo"));
        assertThat(api.as(mentor).get(url("/leaderboard")).status()).isEqualTo(200);
        assertThat(api.as(alpha).get(url("/leaderboard")).status()).isEqualTo(403);
        assertThat(api.as(president).put(url(""), body("criteria", List.of(body("name", "Only one", "maxScore", 10)))).status()).isEqualTo(409);
    }

    @Test
    void theLeaderboardBecomesTheResultsDraftThePresidentPublishesItAndWinnersGetMeritCertificates() throws Exception {
        scored();
        events.update(hack, new Update().set("certificatesEnabled", true));
        Response drafted = api.as(president).post(url("/results"), body("winners", 2));
        assertThat(drafted.status()).isEqualTo(200);
        assertThat(drafted.message()).isEqualTo("Results draft prepared — the president can review and publish it");
        EventResult result = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(hack.getId()))), EventResult.class);
        assertThat(result.getStatus().name()).isEqualTo("DRAFT");
        assertThat(result.getAwards()).extracting(ResultAward::getPosition, ResultAward::getTitle, ResultAward::getTeamName)
                .containsExactly(tuple(1, "Winner", "Alpha"), tuple(2, "1st runner-up", "Beta"));
        assertThat(result.getRounds().get(0).getName()).isEqualTo("Judging");
        assertThat(result.getRounds().get(0).getEntries()).extracting(ResultEntry::getRank, ResultEntry::getTeamName, ResultEntry::getScore)
                .containsExactly(tuple(1, "Alpha", "31/40"), tuple(2, "Beta", "22/40"));

        // Checked in: Alpha's members. Then the event ends and results are published.
        events.checkIn(hack, alpha, alpha2);
        events.update(hack, new Update().set("endAt", minutesFromNow(-5)).set("status", EventStatus.COMPLETED).set("completedAt", Instant.now()));
        assertThat(api.as(president).post("/api/events/" + hack.getId() + "/results/publish").status()).isEqualTo(200);
        assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(alpha2.id())).and("type").is("CERTIFICATE_READY")), Notification.class)).isTrue();

        Response mine = api.as(alpha2).get("/api/events/" + hack.getId() + "/certificates");
        assertThat(mine.message()).isEqualTo("Certificates fetched");
        List<Map<String, Object>> items = mine.at("$.data.items");
        assertThat(items).extracting(item -> item.get("kind"), item -> item.get("awardTitle"), item -> item.get("teamName"))
                .containsExactly(tuple("PARTICIPATION", null, "Alpha"), tuple("MERIT", "Winner", "Alpha"));
        // Beta wasn't checked in: merit only.
        assertThat((List<String>) api.as(beta).get("/api/events/" + hack.getId() + "/certificates").at("$.data.items[*].kind")).containsExactly("MERIT");
        assertThat((List<?>) api.as(outsider).get("/api/events/" + hack.getId() + "/certificates").at("$.data.items")).isEmpty();

        String code = (String) items.get(1).get("code");
        MockHttpServletResponse pdf = mvc.perform(MockMvcRequestBuilders.get("/api/certificates/" + code + "/pdf").header("Authorization", "Bearer " + alpha2.token()))
                .andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).contains("pdf");
        assertThat(new String(Arrays.copyOfRange(pdf.getContentAsByteArray(), 0, 4), StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(api.as(beta).get("/api/certificates/" + code + "/pdf").status()).isEqualTo(404);

        Response check = api.get("/api/certificates/verify/" + code);
        assertThat(check.message()).isEqualTo("Certificate checked");
        Map<String, Object> verified = check.at("$.data");
        assertThat(verified).containsEntry("valid", true).containsEntry("recipientName", "Alpha Two").containsEntry("kind", "MERIT")
                .containsEntry("eventTitle", "HackNight").containsEntry("clubName", "Coding Club");
        assertThat((Map<String, Object>) api.get("/api/certificates/verify/CERT-AAAAAAAAAA").at("$.data")).isEqualTo(Map.of("valid", false));
        assertThat((List<?>) api.as(alpha2).get("/api/certificates/mine").at("$.data")).hasSize(2);
    }
}
