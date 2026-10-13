package com.campusconnect.feature.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.recruitment.drive.DriveService;
import com.campusconnect.feature.recruitment.reminder.RecruitmentReminders;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Port of tests/integration/recruitment.test.js ("role-wise recruitment") and the offer test of phone.test.js, plus
 * the races, expired offers and paused clubs the original tests do not cover. The original tests share one drive
 * across the whole file; here every test starts from an empty database, so each one first replays the earlier steps
 * of the story through the API (each step keeps the original test's assertions).
 */
class RecruitmentIntegrationTest extends IntegrationTest {

    static final byte[] PDF = pdf();

    @Autowired
    RecruitmentReminders reminders;

    @Autowired
    DevInbox inbox;

    @Autowired
    PausedClubs pausedClubs;

    @Autowired
    DriveService driveService;

    TestData.Venues venues;
    TestUser mentor;
    TestUser president;
    TestUser vice;
    TestUser member;
    TestUser asha;
    TestUser bina;
    TestUser chirag;
    TestUser dev;
    TestUser itStudent;
    TestUser oldBatch;
    Club club;
    String designKey;
    String driveId;
    Map<String, Object> design;
    Map<String, Object> tech;

    static byte[] pdf() {
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = Arrays.copyOf(head, head.length + 64);
        Arrays.fill(bytes, head.length, bytes.length, (byte) 1);
        return bytes;
    }

    static String inDays(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).toString();
    }

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(s -> s.name("President"));
        vice = data.student(s -> s.name("Vice President"));
        member = data.student(s -> s.name("Plain Member"));
        asha = data.student(s -> s.name("Asha Patel"));
        bina = data.student(s -> s.name("Bina Shah").batch("25"));
        chirag = data.student(s -> s.name("Chirag Rao"));
        dev = data.student(s -> s.name("Dev Nair"));
        itStudent = data.student(s -> s.name("IT Student").department("IT"));
        oldBatch = data.student(s -> s.name("Old Batch").batch("22"));
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, vice, "VICE_PRESIDENT");
        data.addMembership(club, member, "MEMBER");
        designKey = addRole(club, "Design lead", List.of("POST_UPDATES"));
    }

    // ---------------------------------------------------------------- helpers

    /** A custom club role (the original test made it through the club roles API). */
    String addRole(Club target, String name, List<String> permissions) {
        List<ClubRole> roles = new ArrayList<>(ClubRoles.defaultRoles());
        String key = ClubRoles.newRoleKey();
        roles.add(new ClubRole(key, name, "", new ArrayList<>(permissions), false, 10));
        mongo.updateFirst(query(where("_id").is(target.getId())), new Update().set("roles", roles), Club.class);
        return key;
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    Map<String, Object> drivePayload(Map<String, Object> overrides) {
        Map<String, Object> body = map(
                "title", "Core team recruitment 2026",
                "description", "We're looking for builders and designers to run the club next year.",
                "positions", List.of(
                        map("role", designKey, "openings", 1, "description", "Posters, reels and our Instagram.", "form", map("pages", List.of(
                                map("title", "About you", "questions", List.of(map("type", "PARAGRAPH", "label", "Why design for us?", "required", true))),
                                map("title", "Your work", "questions", List.of(
                                        map("type", "LINK", "label", "Portfolio", "required", true),
                                        map("type", "FILE", "label", "Resume (PDF)")))))),
                        map("role", "TECHNICAL_COORDINATOR", "openings", 2, "form", map("pages", List.of(
                                map("title", "Skills", "questions", List.of(map("type", "MULTI_CHOICE", "label", "Stack", "options", List.of("Web", "Mobile", "ML"), "required", true))))))),
                "applicationEnd", inDays(7));
        body.putAll(overrides);
        return body;
    }

    String clubUrl(Club target) {
        return "/api/clubs/" + target.getId() + "/recruitment";
    }

    String driveUrl() {
        return "/api/recruitment/" + driveId;
    }

    static String id(Map<String, Object> document) {
        return (String) document.get("_id");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> questionsOf(Map<String, Object> position) {
        List<Map<String, Object>> pages = (List<Map<String, Object>>) ((Map<String, Object>) position.get("form")).get("pages");
        return pages.stream().flatMap(page -> ((List<Map<String, Object>>) page.get("questions")).stream()).toList();
    }

    List<Object> designAnswers(String why, String portfolio) {
        List<Map<String, Object>> questions = questionsOf(design);
        return new ArrayList<>(List.of(
                map("question", id(questions.get(0)), "text", why == null ? "I make posters for fun." : why),
                map("question", id(questions.get(1)), "text", portfolio == null ? "https://behance.net/me" : portfolio)));
    }

    List<Object> designAnswers() {
        return designAnswers(null, null);
    }

    List<Object> techAnswers() {
        return List.of(map("question", id(questionsOf(tech).get(0)), "choices", List.of("Web", "ML")));
    }

    Response applyTo(TestUser student, Map<String, Object> position, List<?> answers) {
        return api.as(student).post(driveUrl() + "/positions/" + id(position) + "/application", map("answers", answers));
    }

    RecruitmentApplication appOf(TestUser student, Map<String, Object> position) {
        return mongo.findOne(query(where("drive").is(driveId).and("applicant").is(student.id()).and("position").is(id(position))), RecruitmentApplication.class);
    }

    Response rounds(Map<String, Object> position) {
        return api.as(president).get(driveUrl() + "/positions/" + id(position) + "/rounds");
    }

    Response decide(Map<String, Object> position, String roundId, List<Map<String, Object>> decisions) {
        return api.as(president).put(driveUrl() + "/positions/" + id(position) + "/rounds/" + roundId + "/outcomes", map("decisions", decisions));
    }

    Response publish(Map<String, Object> position, String roundId) {
        return api.as(president).post(driveUrl() + "/positions/" + id(position) + "/rounds/" + roundId + "/publish");
    }

    Response finalizeRole(Map<String, Object> position, Map<String, Object> body) {
        return api.as(president).post(driveUrl() + "/positions/" + id(position) + "/finalize", body);
    }

    static Map<String, Object> outcome(RecruitmentApplication application, String value) {
        return map("applicationId", application.getId(), "outcome", value);
    }

    static Map<String, Object> decision(RecruitmentApplication application, String value) {
        return map("applicationId", application.getId(), "decision", value);
    }

    Response upload(TestUser user, byte[] bytes, String name) {
        return api.perform(MockMvcRequestBuilders.multipart(driveUrl() + "/media")
                .file(new MockMultipartFile("file", name, "application/pdf", bytes))
                .header("Authorization", "Bearer " + user.token()));
    }

    List<String> subjects(TestUser user) {
        return data.mailsTo(user.email()).stream().map(DevInbox.Entry::subject).toList();
    }

    DevInbox.Entry mail(TestUser user, Predicate<String> subject) {
        return data.mailsTo(user.email()).stream().filter(entry -> subject.test(entry.subject())).findFirst()
                .orElseThrow(() -> new AssertionError("No matching e-mail to " + user.email() + " in " + subjects(user)));
    }

    boolean notified(TestUser user, String title, String messagePart) {
        var criteria = where("user").is(user.id()).and("title").is(title);
        if (messagePart != null) {
            criteria = criteria.and("message").regex(java.util.regex.Pattern.quote(messagePart));
        }
        return mongo.exists(query(criteria), Notification.class);
    }

    ClubMembership membership(Club target, TestUser user) {
        return mongo.findOne(query(where("club").is(target.getId()).and("user").is(user.id())), ClubMembership.class);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Response response, String path) {
        return (List<Map<String, Object>>) response.at(path);
    }

    static Map<String, Object> named(List<Map<String, Object>> rows, String name) {
        return rows.stream().filter(row -> name.equals(((Map<?, ?>) row.get("applicant")).get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + name));
    }

    /** A drive published straight in the database (the original tests skip the review the same way). */
    void publishInDatabase(String id) {
        mongo.updateFirst(query(where("_id").is(id)), new Update().set("status", RecruitmentStatus.PUBLISHED.name()).set("publishedAt", Instant.now()), RecruitmentDrive.class);
    }

    RecruitmentApplication offerInDatabase(String drive, TestUser applicant, Instant expiresAt) {
        mongo.updateFirst(query(where("drive").is(drive).and("applicant").is(applicant.id())),
                new Update().set("status", ApplicationStatus.OFFERED.name()).set("offeredAt", Instant.now()).set("offerExpiresAt", expiresAt), RecruitmentApplication.class);
        return mongo.findOne(query(where("drive").is(drive).and("applicant").is(applicant.id())), RecruitmentApplication.class);
    }

    // ---------------------------------------------------------------- the story, step by step (each step = one original test)

    void buildTheDrive() {
        String url = clubUrl(club);
        assertThat(api.as(president).post(url, drivePayload(map("positions", List.of(map("role", "PRESIDENT", "form", map("pages", List.of())))))).message())
                .contains("presidency is handed over");
        assertThat(api.as(president).post(url, drivePayload(map("positions", List.of(map("role", "VICE_PRESIDENT", "form", map("pages", List.of())))))).message())
                .contains("already has a vice-president");
        assertThat(api.as(president).post(url, drivePayload(map("positions", List.of(map("role", "TREASURER"), map("role", "TREASURER"))))).message())
                .contains("listed twice");
        assertThat(api.as(president).post(url, drivePayload(map("positions", List.of(map("role", "TREASURER", "form", map("pages", List.of(map("title", "", "questions", List.of())))))))).message())
                .contains("Page 1 of the Treasurer form needs a title");
        assertThat(api.as(vice).post(url, drivePayload(map())).status()).isEqualTo(403);

        Response res = api.as(president).post(url, drivePayload(map()));
        assertThat(res.status()).as(res.toString()).isEqualTo(201);
        assertThat((List<String>) res.at("$.data.positions[*].title")).containsExactly("Design lead", "Technical coordinator");
        design = res.at("$.data.positions[0]");
        tech = res.at("$.data.positions[1]");
        assertThat((List<String>) res.at("$.data.positions[0].form.pages[*].title")).containsExactly("About you", "Your work");
        assertThat((Integer) res.at("$.data.positions[0].questionCount")).isEqualTo(3);
        driveId = res.string("$.data._id");
    }

    void reviewAndPublish() {
        api.as(president).post(driveUrl() + "/submit");
        Response review = api.as(mentor).get("/api/recruitment/review");
        assertThat((List<String>) review.at("$.data[0].positions")).containsExactly("Design lead", "Technical coordinator");
        assertThat((Integer) review.at("$.data[0].questions")).isEqualTo(4);
        api.as(mentor).post(driveUrl() + "/approve", map("comment", "Good"));
        inbox.clear();
        assertThat(api.as(president).post(driveUrl() + "/publish").string("$.data.phase")).isEqualTo("OPEN");
        assertThat(mail(asha, subject -> subject.contains("Coding Club is recruiting")).text()).contains("Design lead, Technical coordinator");
        assertThat(subjects(member)).noneMatch(subject -> subject.contains("recruiting"));
        assertThat(subjects(itStudent)).noneMatch(subject -> subject.contains("recruiting"));
    }

    void applyToTwoRoles() {
        assertThat(applyTo(asha, design, designAnswers("", null)).message()).contains("Please answer \"Why design for us?\"");
        assertThat(applyTo(asha, design, designAnswers(null, "behance.net")).message()).contains("full link");

        Response tickets = api.as(asha).post(driveUrl() + "/uploads", map("kinds", List.of("DOCUMENT")));
        assertThat(tickets.status()).as(tickets.toString()).isEqualTo(201);
        Response uploaded = upload(asha, PDF, "resume.pdf");
        assertThat(uploaded.status()).as(uploaded.toString()).isEqualTo(201);
        inbox.clear();
        Map<String, Object> media = new LinkedHashMap<>(uploaded.at("$.data"));
        media.put("name", "resume.pdf");
        List<Object> answers = designAnswers();
        answers.add(map("question", id(questionsOf(design).get(2)), "media", media));
        Response first = applyTo(asha, design, answers);
        assertThat(first.status()).as(first.toString()).isEqualTo(201);
        assertThat(first.string("$.data.positionTitle")).isEqualTo("Design lead");
        assertThat(first.string("$.data.status")).isEqualTo("APPLIED");
        assertThat((List<String>) first.at("$.data.pages[*].title")).containsExactly("About you", "Your work");
        assertThat(first.string("$.data.pages[1].answers[1].file.name")).isEqualTo("resume.pdf");

        assertThat(applyTo(asha, design, designAnswers()).status()).isEqualTo(409);
        assertThat(applyTo(asha, tech, techAnswers()).status()).isEqualTo(201);
        assertThat(subjects(asha)).contains("Application received: Design lead — Coding Club", "Application received: Technical coordinator — Coding Club");

        Response mine = api.as(asha).get(driveUrl() + "/applications/mine");
        assertThat((List<String>) mine.at("$.data[*].positionTitle")).containsExactly("Design lead", "Technical coordinator");
        assertThat(list(api.as(asha).get(driveUrl()), "$.data.viewer.applications")).hasSize(2);
    }

    void othersApply() {
        assertThat(applyTo(bina, design, designAnswers()).status()).isEqualTo(201);
        assertThat(applyTo(chirag, tech, techAnswers()).status()).isEqualTo(201);
        assertThat(applyTo(dev, tech, techAnswers()).status()).isEqualTo(201);
        assertThat(applyTo(member, tech, techAnswers()).message()).contains("already a member");
        assertThat(applyTo(oldBatch, tech, techAnswers()).status()).isEqualTo(201);

        assertThat(api.as(oldBatch).delete(driveUrl() + "/positions/" + id(tech) + "/application").status()).isEqualTo(200);
        Response list = api.as(president).get(driveUrl() + "/applications?position=" + id(tech));
        assertThat((List<String>) list.at("$.data[*].applicant.name")).containsExactly("Asha Patel", "Chirag Rao", "Dev Nair");
        assertThat(named(list(list, "$.data"), "Asha Patel").get("otherRoles")).isEqualTo(1);
        Response detail = api.as(mentor).get(driveUrl() + "/applications/" + list.string("$.data[0]._id"));
        assertThat((List<String>) detail.at("$.data.otherApplications[*].positionTitle")).containsExactly("Design lead");
    }

    void roundsAfterApplicationsClose() {
        assertThat(api.as(president).post(driveUrl() + "/positions/" + id(design) + "/rounds", map("name", "Screening", "mode", "SCREENING")).message())
                .contains("Close applications");
        api.as(president).post(driveUrl() + "/close");

        Response created = api.as(president).post(driveUrl() + "/positions/" + id(design) + "/rounds", map("name", "Portfolio review", "mode", "SCREENING"));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((List<String>) created.at("$.data.rounds[0].candidates[*].applicant.name")).containsExactly("Asha Patel", "Bina Shah");
        assertThat(list(rounds(tech), "$.data.rounds")).isEmpty();

        String roundId = created.string("$.data.rounds[0]._id");
        decide(design, roundId, List.of(outcome(appOf(asha, design), "QUALIFIED"), outcome(appOf(bina, design), "QUALIFIED")));
        inbox.clear();
        assertThat(publish(design, roundId).status()).isEqualTo(200);
        assertThat(subjects(asha)).anyMatch(subject -> subject.contains("cleared Portfolio review (Design lead)"));
    }

    void interviewSlotsNeverClash() {
        String day = TestData.futureDate(3);
        Instant three = Instant.parse(day + "T09:30:00Z");
        Map<String, Object> techRound = list(api.as(president).post(driveUrl() + "/positions/" + id(tech) + "/rounds", map("name", "Tech interview", "mode", "OFFLINE")),
                "$.data.rounds").get(0);
        Response scheduledTech = api.as(president).put(driveUrl() + "/positions/" + id(tech) + "/rounds/" + id(techRound) + "/schedule",
                map("timing", "SLOTS", "startAt", day + "T15:00:00+05:30", "slotMinutes", 20, "venue", venues.auditorium().getId()));
        assertThat(scheduledTech.status()).as(scheduledTech.toString()).isEqualTo(200);
        Map<?, ?> ashaTech = (Map<?, ?>) named(list(scheduledTech, "$.data.rounds[0].candidates"), "Asha Patel").get("slot");
        assertThat(Instant.parse((String) ashaTech.get("startAt"))).isEqualTo(three);

        Map<String, Object> designRound = list(api.as(president).post(driveUrl() + "/positions/" + id(design) + "/rounds", map("name", "Design interview", "mode", "ONLINE")),
                "$.data.rounds").get(1);
        inbox.clear();
        Response scheduledDesign = api.as(president).put(driveUrl() + "/positions/" + id(design) + "/rounds/" + id(designRound) + "/schedule",
                map("timing", "SLOTS", "startAt", day + "T15:00:00+05:30", "slotMinutes", 20, "meetingLink", "https://meet.google.com/abc-defg-hij"));
        assertThat(scheduledDesign.status()).as(scheduledDesign.toString()).isEqualTo(200);
        assertThat(list(scheduledDesign, "$.data.warnings")).isEmpty();
        List<Map<String, Object>> candidates = list(scheduledDesign, "$.data.rounds[1].candidates");
        // Asha is busy at 15:00 in the tech interview, so Bina takes the first design slot.
        assertThat(Instant.parse((String) ((Map<?, ?>) named(candidates, "Bina Shah").get("slot")).get("startAt"))).isEqualTo(three);
        assertThat(Instant.parse((String) ((Map<?, ?>) named(candidates, "Asha Patel").get("slot")).get("startAt"))).isEqualTo(three.plus(20, ChronoUnit.MINUTES));
        assertThat(mail(asha, subject -> subject.contains("You're invited: Design interview (Design lead)")).text()).contains("Role: Design lead");
    }

    void remindersGoOut() {
        RecruitmentApplication chiragTech = appOf(chirag, tech);
        Instant start = Instant.now().plus(50, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        mongo.updateFirst(query(where("_id").is(chiragTech.getId())),
                new Update().set("slots.0.startAt", start).set("slots.0.endAt", start.plus(20, ChronoUnit.MINUTES)), RecruitmentApplication.class);
        assertThat(reminders.sweepInterviewReminders(Instant.now())).isEqualTo(1);
        assertThat(reminders.sweepInterviewReminders(Instant.now())).isEqualTo(0);
        assertThat(reminders.sweepInterviewReminders(start.minus(8, ChronoUnit.MINUTES))).isEqualTo(1);
    }

    void finalSelection() {
        List<Map<String, Object>> designRounds = list(rounds(design), "$.data.rounds");
        RecruitmentApplication a = appOf(asha, design);
        RecruitmentApplication b = appOf(bina, design);
        decide(design, id(designRounds.get(1)), List.of(outcome(a, "QUALIFIED"), outcome(b, "QUALIFIED")));
        publish(design, id(designRounds.get(1)));

        Response tooMany = finalizeRole(design, map("decisions", List.of(decision(a, "OFFER"), decision(b, "OFFER"))));
        assertThat(tooMany.message()).contains("1 opening");

        inbox.clear();
        Response finalized = finalizeRole(design, map("offerDays", 2, "decisions", List.of(decision(a, "OFFER"), decision(b, "RESERVE"))));
        assertThat(finalized.status()).as(finalized.toString()).isEqualTo(200);
        assertThat((Integer) finalized.at("$.data.seats.openings")).isEqualTo(1);
        assertThat((Integer) finalized.at("$.data.seats.pending")).isEqualTo(1);
        assertThat((Integer) finalized.at("$.data.seats.open")).isEqualTo(0);
        assertThat(mail(asha, "Offer: Design lead at Coding Club 🎉"::equals).text()).contains("accepting this offer closes your other applications");
        assertThat(subjects(bina)).anyMatch(subject -> subject.contains("reserve list: Design lead"));

        // Tech: Asha and Chirag get offers, Dev is thanked.
        List<Map<String, Object>> techRounds = list(rounds(tech), "$.data.rounds");
        RecruitmentApplication ta = appOf(asha, tech);
        RecruitmentApplication tc = appOf(chirag, tech);
        RecruitmentApplication td = appOf(dev, tech);
        decide(tech, id(techRounds.get(0)), List.of(outcome(ta, "QUALIFIED"), outcome(tc, "QUALIFIED"), outcome(td, "QUALIFIED")));
        publish(tech, id(techRounds.get(0)));
        finalizeRole(tech, map("decisions", List.of(decision(ta, "OFFER"), decision(tc, "OFFER"), decision(td, "NOT_SELECTED"))));
        assertThat(appOf(dev, tech).getStatus()).isEqualTo(ApplicationStatus.NOT_SELECTED);
    }

    void acceptOneOffer() {
        RecruitmentApplication ashaTech = appOf(asha, tech);
        assertThat(api.as(bina).post(driveUrl() + "/applications/" + ashaTech.getId() + "/accept").status()).isEqualTo(409);

        inbox.clear();
        Response accepted = api.as(asha).post(driveUrl() + "/applications/" + ashaTech.getId() + "/accept");
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(200);
        assertThat(list(accepted, "$.data").stream().filter(row -> "Technical coordinator".equals(row.get("positionTitle"))).findFirst().orElseThrow().get("status"))
                .isEqualTo("ACCEPTED");
        ClubMembership joined = membership(club, asha);
        assertThat(joined.getStatus()).isEqualTo(MembershipStatus.APPROVED);
        assertThat(joined.getRole()).isEqualTo("TECHNICAL_COORDINATOR");

        RecruitmentApplication designApp = appOf(asha, design);
        assertThat(designApp.getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(designApp.getClosedReason()).isEqualTo("Joined as Technical coordinator");
        assertThat(mail(asha, "Welcome to Coding Club! 🎉"::equals).text()).contains("applications for Design lead have been closed");
        assertThat(notified(president, "Asha Patel joined as Technical coordinator", "Design lead offer is free again")).isTrue();

        assertThat(api.as(asha).post(driveUrl() + "/applications/" + designApp.getId() + "/accept").status()).isEqualTo(409);
    }

    void reserveGetsTheFreedSeat() {
        Response state = rounds(design);
        assertThat((Boolean) state.at("$.data.canOfferReserve")).isTrue();
        assertThat((Integer) state.at("$.data.seats.open")).isEqualTo(1);
        RecruitmentApplication binaApp = appOf(bina, design);
        inbox.clear();
        Response offered = api.as(president).post(driveUrl() + "/positions/" + id(design) + "/offers/" + binaApp.getId());
        assertThat(offered.status()).as(offered.toString()).isEqualTo(200);
        assertThat(subjects(bina)).contains("Offer: Design lead at Coding Club 🎉");

        assertThat(reminders.expireOffers(Instant.now().plus(3, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1);
        assertThat(appOf(bina, design).getStatus()).isEqualTo(ApplicationStatus.EXPIRED);
        assertThat(notified(president, "Offer expired: Design lead", null)).isTrue();
    }

    // ---------------------------------------------------------------- recruitment.test.js

    @Nested
    class BuildingTheDrive {

        @Test
        void rolesComeFromTheClubNoPresidencyNoTakenVicePresidentSeatNoDuplicatesTitledPages() {
            buildTheDrive();
        }

        @Test
        void theMentorReviewsTheWholeDrivePublishingEmailsEligibleStudentsWithEveryRole() {
            buildTheDrive();
            reviewAndPublish();
        }
    }

    @Nested
    class ApplyingOneApplicationPerRole {

        @BeforeEach
        void openDrive() {
            buildTheDrive();
            reviewAndPublish();
        }

        @Test
        void aStudentAppliesToTwoRolesWithTwoSeparateApplicationsEachCheckedAgainstItsOwnForm() {
            applyToTwoRoles();
        }

        @Test
        void othersApplyWithdrawingOneApplicationLeavesTheOthersAlone() {
            applyToTwoRoles();
            othersApply();
        }
    }

    @Nested
    class EachRoleRunsItsOwnSelection {

        @BeforeEach
        void applications() {
            buildTheDrive();
            reviewAndPublish();
            applyToTwoRoles();
            othersApply();
        }

        @Test
        void roundsStartAfterApplicationsCloseSeparatelyPerRole() {
            roundsAfterApplicationsClose();
        }

        @Test
        void interviewSlotsOfAStudentInTwoRolesNeverClash() {
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();
        }

        @Test
        void remindersStillGoOut60And10MinutesBefore() {
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();
            remindersGoOut();
        }

        @Test
        void finalSelectionPerRoleOffersUpToTheOpeningsAReserveListAndThanks() {
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();
            finalSelection();
        }

        @Test
        void acceptingOneOfferTheStudentJoinsInThatRoleAndEveryOtherApplicationCloses() {
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();
            finalSelection();
            acceptOneOffer();
        }

        @Test
        void theFreedSeatGoesToTheReserveListAnUnansweredOfferExpires() {
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();
            finalSelection();
            acceptOneOffer();
            reserveGetsTheFreedSeat();
        }

        @Test
        void onceEveryRoleIsDecidedAndNoOfferIsWaitingTheDriveCompletes() {
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();
            finalSelection();
            acceptOneOffer();
            reserveGetsTheFreedSeat();

            // Chirag's tech offer also expired above; with nothing left, the drive is complete.
            assertThat(mongo.findById(driveId, RecruitmentDrive.class).getStatus()).isEqualTo(RecruitmentStatus.COMPLETED);
            assertThat(notified(mentor, "Coding Club finished recruiting", "1 new member joined through Core team recruitment 2026.")).isTrue();
            Response mine = api.as(asha).get("/api/recruitment/mine");
            List<List<Object>> rows = list(mine, "$.data").stream().map(row -> Arrays.asList(row.get("positionTitle"), row.get("status"), row.get("closedReason"))).toList();
            // The Design lead application closed when Asha joined; she still sees it, with the reason.
            assertThat(rows).contains(Arrays.asList("Technical coordinator", "ACCEPTED", null), Arrays.asList("Design lead", "WITHDRAWN", "Joined as Technical coordinator"));
            assertThat(api.as(president).post(driveUrl() + "/complete").status()).isEqualTo(409);
        }
    }

    @Nested
    class VicePresidentAndCancelling {

        TestUser chessPresident;
        Club chess;
        String vpDrive;

        /** The first test's story: a vice-president drive of the Chess Club, Dev offered the seat. */
        RecruitmentApplication offerTheVicePresidency() {
            chessPresident = data.student(s -> s.name("Chess President"));
            chess = data.activeClub("Chess Club", mentor, chessPresident);
            Response created = api.as(chessPresident).post(clubUrl(chess), drivePayload(map("title", "Vice-president election",
                    "positions", List.of(map("role", "VICE_PRESIDENT", "openings", 3, "form", map("pages", List.of()))))));
            assertThat(created.status()).as(created.toString()).isEqualTo(201);
            assertThat(created.string("$.data.positions[0].title")).isEqualTo("Vice-president");
            assertThat((Integer) created.at("$.data.positions[0].openings")).isEqualTo(1);
            vpDrive = created.string("$.data._id");
            publishInDatabase(vpDrive);
            Response applied = api.as(dev).post("/api/recruitment/" + vpDrive + "/positions/" + created.string("$.data.positions[0]._id") + "/application", map("answers", List.of()));
            assertThat(applied.status()).as(applied.toString()).isEqualTo(201);
            return offerInDatabase(vpDrive, dev, Instant.now().plus(1, ChronoUnit.DAYS));
        }

        @Test
        void aFreeVicePresidentSeatIsRecruitedForExactlyOnePersonAndRecheckedOnAccept() {
            RecruitmentApplication application = offerTheVicePresidency();
            // Meanwhile the president appointed someone else.
            data.addMembership(chess, itStudent, "VICE_PRESIDENT");
            Response refused = api.as(dev).post("/api/recruitment/" + vpDrive + "/applications/" + application.getId() + "/accept");
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.message()).contains("vice-president seat has already been filled");
            assertThat(membership(chess, dev)).isNull();
        }

        @Test
        void decliningAnOfferTellsThePresident() {
            RecruitmentApplication application = offerTheVicePresidency();
            data.addMembership(chess, itStudent, "VICE_PRESIDENT");
            api.as(dev).post("/api/recruitment/" + vpDrive + "/applications/" + application.getId() + "/accept");

            assertThat(api.as(dev).post("/api/recruitment/" + vpDrive + "/applications/" + application.getId() + "/decline").status()).isEqualTo(200);
            assertThat(mongo.findById(application.getId(), RecruitmentApplication.class).getStatus()).isEqualTo(ApplicationStatus.DECLINED);
            assertThat(mongo.exists(query(where("title").is("Offer declined: Vice-president")), Notification.class)).isTrue();
        }

        @Test
        void cancellingALiveDriveClosesEveryOpenApplicationAndTellsTheApplicants() {
            Response created = api.as(president).post(clubUrl(club), drivePayload(map("title", "Spring intake", "positions", List.of(map("role", "TREASURER", "form", map("pages", List.of()))))));
            assertThat(created.status()).as(created.toString()).isEqualTo(201);
            driveId = created.string("$.data._id");
            publishInDatabase(driveId);
            assertThat(api.as(chirag).post(driveUrl() + "/positions/" + created.string("$.data.positions[0]._id") + "/application", map("answers", List.of())).status())
                    .isEqualTo(201);

            inbox.clear();
            Response cancelled = api.as(president).post(driveUrl() + "/cancel", map("reason", "Exams moved earlier"));
            assertThat(cancelled.string("$.data.status")).isEqualTo("CANCELLED");
            assertThat(mail(chirag, "Coding Club recruitment cancelled"::equals).text()).contains("Exams moved earlier");
        }
    }

    // ---------------------------------------------------------------- phone.test.js

    @Nested
    class ClubMembersMobileNumbers {

        @Test
        void acceptingAnOfferNeedsAMobileNumberWhichCanBeGivenWithTheAcceptance() {
            TestUser codingPresident = data.student(s -> s.name("Coding President"));
            Club codingClub = data.activeClub("Coding Society", data.faculty("CE", "Coding Mentor"), codingPresident);
            TestUser joiner = data.student(s -> s.name("Joiner").phone(null));
            Response created = api.as(codingPresident).post(clubUrl(codingClub), map(
                    "title", "Vice-president election",
                    "description", "Pick a vice-president to help run the club this year.",
                    "positions", List.of(map("role", "VICE_PRESIDENT", "openings", 1, "form", map("pages", List.of()))),
                    "applicationEnd", inDays(7)));
            assertThat(created.status()).as(created.toString()).isEqualTo(201);
            String drive = created.string("$.data._id");
            publishInDatabase(drive);
            assertThat(api.as(joiner).post("/api/recruitment/" + drive + "/positions/" + created.string("$.data.positions[0]._id") + "/application", map("answers", List.of()))
                    .status()).isEqualTo(201);
            RecruitmentApplication application = offerInDatabase(drive, joiner, Instant.now().plus(1, ChronoUnit.DAYS));
            String acceptUrl = "/api/recruitment/" + drive + "/applications/" + application.getId() + "/accept";

            Response refused = api.as(joiner).post(acceptUrl);
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.errorCode()).isEqualTo("PHONE_REQUIRED");
            assertThat(membership(codingClub, joiner)).isNull();

            assertThat(api.as(joiner).post(acceptUrl, map("phone", "12345")).status()).isEqualTo(400);

            Response accepted = api.as(joiner).post(acceptUrl, map("phone", "98989 89898"));
            assertThat(accepted.status()).as(accepted.toString()).isEqualTo(200);
            assertThat(mongo.findById(joiner.id(), User.class).getPhone()).isEqualTo("+919898989898");
            assertThat(membership(codingClub, joiner).getStatus()).isEqualTo(MembershipStatus.APPROVED);
        }
    }

    // ---------------------------------------------------------------- beyond the original tests

    @Nested
    class OffersUnderPressure {

        String position;

        /** A published Treasurer drive (one opening) with applications from these students. */
        void treasurerDrive(TestUser... applicants) {
            Response created = api.as(president).post(clubUrl(club), drivePayload(map("title", "Treasurer intake",
                    "positions", List.of(map("role", "TREASURER", "openings", 1, "form", map("pages", List.of()))))));
            assertThat(created.status()).as(created.toString()).isEqualTo(201);
            driveId = created.string("$.data._id");
            position = created.string("$.data.positions[0]._id");
            publishInDatabase(driveId);
            for (TestUser applicant : applicants) {
                assertThat(api.as(applicant).post(driveUrl() + "/positions/" + position + "/application", map("answers", List.of())).status()).isEqualTo(201);
            }
        }

        List<Response> inParallel(List<Callable<Response>> calls) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Response>> gated = calls.stream().<Callable<Response>>map(call -> () -> {
                start.await();
                return call.call();
            }).toList();
            List<Response> results = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
                List<Future<Response>> futures = gated.stream().map(pool::submit).toList();
                start.countDown();
                for (Future<Response> future : futures) {
                    results.add(future.get());
                }
            }
            return results;
        }

        @Test
        void twoParallelAcceptancesOfTheLastOpeningExactlyOneSucceeds() throws Exception {
            treasurerDrive(asha, bina);
            RecruitmentApplication ashaOffer = offerInDatabase(driveId, asha, Instant.now().plus(1, ChronoUnit.DAYS));
            RecruitmentApplication binaOffer = offerInDatabase(driveId, bina, Instant.now().plus(1, ChronoUnit.DAYS));

            List<Response> results = inParallel(List.of(
                    () -> api.as(asha).post(driveUrl() + "/applications/" + ashaOffer.getId() + "/accept"),
                    () -> api.as(bina).post(driveUrl() + "/applications/" + binaOffer.getId() + "/accept")));

            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow().message())
                    .isEqualTo("The Treasurer seat has already been filled");
            assertThat(mongo.count(query(where("drive").is(driveId).and("status").is(ApplicationStatus.ACCEPTED.name())), RecruitmentApplication.class)).isEqualTo(1);
            assertThat(mongo.count(query(where("club").is(club.getId()).and("role").is("TREASURER").and("status").is(MembershipStatus.APPROVED.name())), ClubMembership.class))
                    .isEqualTo(1);
        }

        @Test
        void theSameOfferAcceptedTwiceAtOnceIsAcceptedOnce() throws Exception {
            treasurerDrive(asha);
            RecruitmentApplication offer = offerInDatabase(driveId, asha, Instant.now().plus(1, ChronoUnit.DAYS));
            String url = driveUrl() + "/applications/" + offer.getId() + "/accept";

            List<Response> results = inParallel(List.of(() -> api.as(asha).post(url), () -> api.as(asha).post(url)));

            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(membership(club, asha).getRole()).isEqualTo("TREASURER");
            assertThat(mongo.count(query(where("user").is(president.id()).and("title").is("Asha Patel joined as Treasurer")), Notification.class)).isEqualTo(1);
        }

        @Test
        void anOfferPastItsExpiryCannotBeAccepted() {
            treasurerDrive(asha);
            RecruitmentApplication offer = offerInDatabase(driveId, asha, Instant.now().minus(1, ChronoUnit.MINUTES));

            Response refused = api.as(asha).post(driveUrl() + "/applications/" + offer.getId() + "/accept");

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.message()).isEqualTo("This offer has expired");
            assertThat(refused.errorCode()).isEqualTo("INVALID_STATE");
            assertThat(mongo.findById(offer.getId(), RecruitmentApplication.class).getStatus()).isEqualTo(ApplicationStatus.EXPIRED);
            assertThat(membership(club, asha)).isNull();
        }

        @Test
        void twoReserveOffersForTheLastSeatAtOnceMakeOneOffer() throws Exception {
            treasurerDrive(asha, bina);
            mongo.updateMulti(query(where("drive").is(driveId)), new Update().set("status", ApplicationStatus.RESERVE.name()), RecruitmentApplication.class);
            mongo.updateFirst(query(where("_id").is(driveId)), new Update().set("positions.0.finalizedAt", Instant.now()).set("closedAt", Instant.now()), RecruitmentDrive.class);
            String base = driveUrl() + "/positions/" + position + "/offers/";

            List<Response> results = inParallel(List.of(
                    () -> api.as(president).post(base + appOf(asha, map("_id", position)).getId()),
                    () -> api.as(president).post(base + appOf(bina, map("_id", position)).getId())));

            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(mongo.count(query(where("drive").is(driveId).and("status").is(ApplicationStatus.OFFERED.name())), RecruitmentApplication.class)).isEqualTo(1);
        }
    }

    @Nested
    class VenuesAndStoredShapes {

        @Test
        void interviewRoundsBookTheirVenueAndAreStoredLikeTheOriginalDocuments() {
            buildTheDrive();
            reviewAndPublish();
            applyToTwoRoles();
            othersApply();
            roundsAfterApplicationsClose();
            interviewSlotsNeverClash();

            Document raw = mongo.findById(new ObjectId(driveId), Document.class, "recruitmentdrives");
            Document techRound = raw.getList("positions", Document.class).get(1).getList("rounds", Document.class).get(0);
            assertThat(techRound.get("_id")).isInstanceOf(ObjectId.class);
            assertThat(techRound.get("venue")).isInstanceOf(ObjectId.class);
            assertThat(techRound.get("startAt")).isInstanceOf(Date.class);
            assertThat(techRound.get("createdAt")).isInstanceOf(Date.class);
            assertThat(techRound.getString("status")).isEqualTo("SCHEDULED");
            Document application = mongo.findById(new ObjectId(appOf(asha, design).getId()), Document.class, "recruitmentapplications");
            assertThat(application.get("drive")).isInstanceOf(ObjectId.class);
            assertThat(application.getList("answers", Document.class).get(0).get("question")).isInstanceOf(ObjectId.class);
            assertThat(application.getList("roundResults", Document.class).get(0).get("round")).isInstanceOf(ObjectId.class);
            Document slot = application.getList("slots", Document.class).get(0);
            assertThat(slot.get("round")).isInstanceOf(ObjectId.class);
            assertThat(slot.get("startAt")).isInstanceOf(Date.class);

            // The president sees each round's schedule and venue; visitors see neither.
            assertThat(api.as(president).get(driveUrl()).string("$.data.positions[1].rounds[0].venue.name")).isEqualTo("Auditorium");
            assertThat(api.as(bina).get(driveUrl()).has("$.data.positions[1].rounds[0].venue")).isFalse();
            assertThat((Integer) rounds(tech).at("$.data.rounds[0].venue.capacity")).isEqualTo(300);
            List<Map<String, Object>> mine = list(api.as(asha).get("/api/recruitment/mine"), "$.data");
            Map<?, ?> nextDesign = (Map<?, ?>) mine.stream().filter(row -> "Design lead".equals(row.get("positionTitle"))).findFirst().orElseThrow().get("nextInterview");
            assertThat(nextDesign.get("name")).isEqualTo("Design interview");
            assertThat(nextDesign.get("meetingLink")).isEqualTo("https://meet.google.com/abc-defg-hij");
            Map<?, ?> nextTech = (Map<?, ?>) mine.stream().filter(row -> "Technical coordinator".equals(row.get("positionTitle"))).findFirst().orElseThrow().get("nextInterview");
            assertThat(((Map<?, ?>) nextTech.get("venue")).get("name")).isEqualTo("Auditorium");

            // Another club's offline round can't take the auditorium at the same time.
            String day = TestData.futureDate(3);
            TestUser chessPresident = data.student(s -> s.name("Chess President"));
            Club chess = data.activeClub("Chess Club", mentor, chessPresident);
            Map<String, Object> treasurer = map("role", "TREASURER", "form", map("pages", List.of()));
            Response other = api.as(chessPresident).post(clubUrl(chess), drivePayload(map("title", "Chess intake", "positions", List.of(treasurer))));
            String otherDrive = "/api/recruitment/" + other.string("$.data._id");
            String otherPosition = otherDrive + "/positions/" + other.string("$.data.positions[0]._id");
            publishInDatabase(other.string("$.data._id"));
            assertThat(api.as(oldBatch).post(otherPosition + "/application", map("answers", List.of())).status()).isEqualTo(201);
            api.as(chessPresident).post(otherDrive + "/close");
            String otherRound = api.as(chessPresident).post(otherPosition + "/rounds", map("name", "Interview", "mode", "OFFLINE")).string("$.data.rounds[0]._id");
            Response clash = api.as(chessPresident).put(otherPosition + "/rounds/" + otherRound + "/schedule",
                    map("timing", "COMMON", "startAt", day + "T15:30:00+05:30", "endAt", day + "T16:30:00+05:30", "venue", venues.auditorium().getId()));
            assertThat(clash.status()).as(clash.toString()).isEqualTo(409);
            assertThat(clash.errorCode()).isEqualTo("VENUE_CONFLICT");
            assertThat(clash.message()).contains("Auditorium is already booked");
            Response elsewhere = api.as(chessPresident).put(otherPosition + "/rounds/" + otherRound + "/schedule",
                    map("timing", "COMMON", "startAt", day + "T15:30:00+05:30", "endAt", day + "T16:30:00+05:30", "venue", venues.hall().getId()));
            assertThat(elsewhere.status()).as(elsewhere.toString()).isEqualTo(200);
        }
    }

    @Nested
    class PausedClubsAndAccess {

        @Test
        void aPausedClubsDrivesDisappearFromListingsAndItsApplicationsAndOffersAreOnHold() {
            Response created = api.as(president).post(clubUrl(club), drivePayload(map("title", "Build team", "positions", List.of(map("role", "MEMBER", "form", map("pages", List.of()))))));
            driveId = created.string("$.data._id");
            String position = created.string("$.data.positions[0]._id");
            publishInDatabase(driveId);
            assertThat(api.as(asha).post(driveUrl() + "/positions/" + position + "/application", map("answers", List.of())).status()).isEqualTo(201);
            RecruitmentApplication offer = offerInDatabase(driveId, asha, Instant.now().plus(1, ChronoUnit.DAYS));

            Response open = api.as(bina).get("/api/recruitment");
            assertThat((List<String>) open.at("$.data[*].title")).containsExactly("Build team");
            assertThat((Boolean) open.at("$.data[0].eligible")).isTrue();
            assertThat((Object) api.get("/api/recruitment").at("$.data[0].eligible")).isNull();
            assertThat(driveService.openDrivesByClub(List.of(club.getId()))).containsKey(club.getId());

            mongo.updateFirst(query(where("_id").is(club.getId())), new Update().set("status", ClubStatus.SUSPENDED.name()), Club.class);
            pausedClubs.evict();

            assertThat(list(api.as(bina).get("/api/recruitment"), "$.data")).isEmpty();
            assertThat(driveService.openDrivesByClub(List.of(club.getId()))).isEmpty();
            assertThat(api.as(bina).post(driveUrl() + "/positions/" + position + "/application", map("answers", List.of())).message()).contains("recruitment is on hold");
            assertThat(api.as(asha).post(driveUrl() + "/applications/" + offer.getId() + "/accept").message()).contains("offers are on hold");
            assertThat(reminders.expireOffers(Instant.now().plus(3, ChronoUnit.DAYS))).isZero();
            assertThat(mongo.findById(offer.getId(), RecruitmentApplication.class).getStatus()).isEqualTo(ApplicationStatus.OFFERED);
        }

        @Test
        void draftsAreHiddenAndOnlyThePresidentAndMentorSeeCountsApplicationsAndResumes() {
            buildTheDrive();
            assertThat(api.get(driveUrl()).status()).isEqualTo(404);
            assertThat(api.as(asha).get(driveUrl()).status()).isEqualTo(404);
            assertThat(api.as(mentor).get(driveUrl()).status()).isEqualTo(200);
            assertThat(api.post(driveUrl() + "/submit").status()).isEqualTo(401);
            assertThat(api.as(vice).post(driveUrl() + "/submit").message()).isEqualTo("Only the club president can manage recruitment");

            reviewAndPublish();
            applyToTwoRoles();
            Response visitor = api.as(bina).get(driveUrl());
            assertThat(visitor.has("$.data.counts")).isFalse();
            assertThat(visitor.has("$.data.reviewComment")).isFalse();
            assertThat(visitor.has("$.data.positions[0].counts")).isFalse();
            assertThat((Boolean) visitor.at("$.data.viewer.canApply")).isTrue();
            Response staff = api.as(mentor).get(driveUrl());
            assertThat((Integer) staff.at("$.data.counts.total")).isEqualTo(2);
            assertThat((Integer) staff.at("$.data.positions[0].counts.total")).isEqualTo(1);
            assertThat(staff.has("$.data.reviewComment")).isTrue();
            assertThat((Object) staff.at("$.data.submittedAt")).isNotNull();

            assertThat(api.as(bina).get(driveUrl() + "/applications").status()).isEqualTo(403);
            String ashaDesign = appOf(asha, design).getId();
            assertThat(api.as(bina).get(driveUrl() + "/applications/" + ashaDesign).message()).isEqualTo("Only the club president and faculty mentor can see applications");
            assertThat(api.as(president).get(driveUrl() + "/applications/" + ashaDesign).string("$.data.pages[1].answers[1].file.url")).contains("/uploads/recruitment/" + driveId);
            assertThat(api.as(asha).get(driveUrl() + "/positions/" + id(design) + "/application").string("$.data.pages[1].answers[1].file.name")).isEqualTo("resume.pdf");
            assertThat(api.as(bina).get(driveUrl() + "/positions/" + id(design) + "/application").status()).isEqualTo(404);
        }

        @Test
        void requestRulesAnswerWithTheOriginalMessages() {
            buildTheDrive();
            assertThat(api.as(president).put(driveUrl(), map("positions", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))).message()).isEqualTo("Up to 10 positions");
            assertThat(api.as(president).put(driveUrl(), map("applicationEnd", "next week")).message()).isEqualTo("Invalid deadline");
            assertThat(api.as(president).put("/api/recruitment/nope", map("title", 5)).message()).isEqualTo("Invalid id. Invalid value");
            Response edited = api.as(president).put(driveUrl(), map("title", "Core team 2026"));
            assertThat(edited.status()).as(edited.toString()).isEqualTo(200);
            assertThat(edited.string("$.data.title")).isEqualTo("Core team 2026");
            assertThat(edited.string("$.data.positions[0]._id")).isEqualTo(id(design));
            assertThat(edited.string("$.data.positions[0].form.pages[0].questions[0]._id")).isEqualTo(id(questionsOf(design).get(0)));

            api.as(president).post(driveUrl() + "/submit");
            Response tooShort = api.as(mentor).post(driveUrl() + "/request-changes", map("comment", "no"));
            assertThat(tooShort.status()).isEqualTo(400);
            assertThat(tooShort.message()).isEqualTo("Explain in at least 5 characters");
            assertThat(api.as(president).post(driveUrl() + "/approve").message()).isEqualTo("Only the club's faculty mentor can review recruitment");
            Response changes = api.as(mentor).post(driveUrl() + "/request-changes", map("comment", "Add a portfolio question"));
            assertThat(changes.string("$.data.status")).isEqualTo("NEEDS_CHANGES");
            assertThat(notified(president, "Changes requested: Core team recruitment 2026", "Add a portfolio question")).isFalse();
            assertThat(notified(president, "Changes requested: Core team 2026", "Add a portfolio question")).isTrue();
            assertThat(subjects(president)).contains("Changes requested: Core team 2026");

            assertThat(api.as(president).put(driveUrl() + "/deadline", map("applicationEnd", "soon")).message()).isEqualTo("Choose a new deadline");
            assertThat(api.as(asha).post(driveUrl() + "/uploads", map("kinds", List.of("VIDEO"))).message()).isEqualTo("Attach PDFs or images");
            assertThat(api.as(president).post(driveUrl() + "/positions/" + id(design) + "/rounds", map("name", "Interview", "mode", "PHONE")).message())
                    .isEqualTo("Choose the round type");
            assertThat(api.as(president).post(driveUrl() + "/positions/" + id(design) + "/finalize", map("decisions", List.of(), "offerDays", 30)).message())
                    .isEqualTo("Give 1 to 14 days to answer");

            api.as(president).post(driveUrl() + "/submit");
            Response rejected = api.as(mentor).post(driveUrl() + "/reject", map("comment", "Not this term"));
            assertThat(rejected.string("$.data.status")).isEqualTo("REJECTED");
            assertThat(notified(president, "Recruitment not approved: Core team 2026", "Not this term")).isTrue();
            assertThat(api.as(president).post(driveUrl() + "/cancel").message()).isEqualTo("This drive is already finished");
            assertThat((Boolean) api.as(president).get(clubUrl(club)).at("$.data.canCreate")).isTrue();
        }

        @Test
        void draftsCanBeDeletedDeadlinesMoveUntilRoundsBeginAndFinishedDrivesStayFinished() {
            buildTheDrive();
            assertThat(api.as(president).delete(driveUrl()).status()).isEqualTo(200);
            assertThat(mongo.findById(driveId, RecruitmentDrive.class)).isNull();

            buildTheDrive();
            reviewAndPublish();
            assertThat(api.as(president).delete(driveUrl()).message()).isEqualTo("Only drafts can be deleted. Cancel the drive instead.");
            assertThat(api.as(president).post(driveUrl() + "/close").string("$.data.phase")).isEqualTo("CLOSED");
            assertThat(api.as(president).post(driveUrl() + "/close").message()).isEqualTo("Applications are already closed");
            Response reopened = api.as(president).put(driveUrl() + "/deadline", map("applicationEnd", inDays(10)));
            assertThat(reopened.string("$.data.phase")).isEqualTo("OPEN");
            assertThat((Object) reopened.at("$.data.closedAt")).isNull();
            assertThat(api.as(president).put(driveUrl() + "/deadline", map("applicationEnd", inDays(-1))).message()).isEqualTo("The new deadline must be in the future");
            assertThat(api.as(president).put(driveUrl() + "/deadline", map("applicationEnd", inDays(90))).message()).isEqualTo("Applications can stay open for up to 60 days");

            assertThat(applyTo(bina, design, designAnswers()).status()).isEqualTo(201);
            api.as(president).post(driveUrl() + "/close");
            assertThat(api.as(president).post(driveUrl() + "/positions/" + id(design) + "/rounds", map("name", "Screening", "mode", "SCREENING")).status()).isEqualTo(201);
            assertThat(api.as(president).put(driveUrl() + "/deadline", map("applicationEnd", inDays(10))).message())
                    .isEqualTo("The deadline can only change before selection rounds begin");

            Response cancelled = api.as(president).post(driveUrl() + "/cancel", map());
            assertThat(cancelled.string("$.data.status")).isEqualTo("CANCELLED");
            assertThat(appOf(bina, design).getStatus()).isEqualTo(ApplicationStatus.NOT_SELECTED);
            assertThat(appOf(bina, design).getClosedReason()).isEqualTo("Recruitment cancelled");
            assertThat(notified(bina, "Coding Club recruitment cancelled", "The club cancelled this recruitment drive.")).isTrue();
            assertThat(api.as(president).post(driveUrl() + "/cancel").message()).isEqualTo("This drive is already finished");
            assertThat(api.as(bina).get(driveUrl()).status()).isEqualTo(200);
        }

        @Test
        void aDriveSavedTwiceAtTheSameMomentLeavesOneDraft() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            List<Response> results = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                List<Future<Response>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return api.as(president).post(clubUrl(club), drivePayload(map()));
                    }));
                }
                start.countDown();
                for (Future<Response> future : futures) {
                    results.add(future.get());
                }
            }
            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
            assertThat(mongo.count(query(where("club").is(club.getId())), RecruitmentDrive.class)).isEqualTo(1);
        }
    }
}
