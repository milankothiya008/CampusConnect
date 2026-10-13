package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.EventMedia;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.UploaderRole;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Port of tests/integration/formsLikesBudget.test.js. The gallery listing check of the likes test and the hackathon
 * submission-form test belong to the gallery and hackathon features.
 */
class FormsLikesBudgetIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
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
        asha = data.student(spec -> spec.name("Asha"));
        bina = data.student(spec -> spec.name("Bina"));
        chirag = data.student(spec -> spec.name("Chirag"));
        club = data.activeClub("Coding Club", mentor, president);
    }

    static String regEnd() {
        return EventFlows.fromNow(Duration.ofDays(3));
    }

    /** Question ids by label. */
    static Map<String, String> ids(Response created) {
        List<Map<String, Object>> questions = created.at("$.data.registrationForm.questions");
        Map<String, String> ids = new HashMap<>();
        questions.forEach(question -> ids.put(String.valueOf(question.get("label")), String.valueOf(question.get("_id"))));
        return ids;
    }

    @Test
    void signUpNeedsAValidMobileNumber() {
        Map<String, Object> body = map("name", "New Student", "email", "24ceuog901@ddu.ac.in", "password", "Secret123", "accountType", "STUDENT");
        assertThat(api.post("/api/auth/register", body).status()).isEqualTo(400);
        Map<String, Object> badPhone = new HashMap<>(body);
        badPhone.put("phone", "12345");
        assertThat(api.post("/api/auth/register", badPhone).status()).isEqualTo(400);
        Map<String, Object> goodPhone = new HashMap<>(body);
        goodPhone.put("phone", "98765 43210");
        assertThat(api.post("/api/auth/register", goodPhone).status()).isEqualTo(201);
    }

    @Nested
    class AnIndividualEventWithARegistrationForm {

        String eventId;
        Map<String, String> questions;

        @BeforeEach
        void designSprint() {
            Response created = flows.create(president, club, venues.auditorium(), "title", "Design Sprint", "eventDate", TestData.futureDate(6), "registrationEnd", regEnd(),
                    "registrationForm", map("enabled", true, "questions", List.of(
                            map("type", "SINGLE_CHOICE", "label", "T-shirt size", "options", List.of("S", "M", "L"), "required", true),
                            map("type", "LINK", "label", "Portfolio", "required", false))));
            assertThat(created.status()).isEqualTo(201);
            eventId = created.string("$.data._id");
            questions = ids(created);
            flows.markPublished(eventId);
        }

        Response ashaRegisters() {
            return flows.register(asha, eventId, map("answers", List.of(map("question", questions.get("T-shirt size"), "choices", List.of("M")),
                    map("question", questions.get("Portfolio"), "text", "https://behance.net/asha"))));
        }

        @Test
        void theClubAddsTheQuestionsItNeedsStudentsAnswerThemToRegister() {
            assertThat(flows.register(asha, eventId).status()).isEqualTo(400);
            assertThat(flows.register(asha, eventId, map("answers", List.of(map("question", questions.get("T-shirt size"), "choices", List.of("XL"))))).status())
                    .isEqualTo(400);
            assertThat(ashaRegisters().status()).isEqualTo(201);
        }

        @Test
        void organisersSeeTheAnswersStudentsCanChangeTheirsUntilTheEvent() {
            ashaRegisters();
            Response listed = api.as(president).get("/api/events/" + eventId + "/registrations");
            assertThat((List<String>) listed.at("$.meta.formColumns[*].label")).containsExactly("T-shirt size", "Portfolio");
            assertThat(listed.string("$.data[0].formAnswers['" + questions.get("T-shirt size") + "']")).isEqualTo("M");

            Response changed = api.as(asha).put("/api/events/" + eventId + "/register/answers",
                    map("answers", List.of(map("question", questions.get("T-shirt size"), "choices", List.of("L")))));
            assertThat(changed.status()).isEqualTo(200);
            assertThat((List<String>) api.as(asha).get("/api/events/" + eventId).at("$.data.myAnswers.answers[0].choices")).containsExactly("L");
        }

        @Test
        void theClubCanSwitchTheFormOffAnyTimeBeforeTheEvent() {
            ashaRegisters();
            assertThat(api.as(asha).put("/api/events/" + eventId + "/registration-form", map("enabled", false)).status()).isEqualTo(403);
            assertThat(api.as(president).put("/api/events/" + eventId + "/registration-form", map("enabled", true, "questions", List.of())).status()).isEqualTo(400);
            assertThat(api.as(president).put("/api/events/" + eventId + "/registration-form", map("enabled", false, "questions", List.of())).status()).isEqualTo(200);
            assertThat(flows.register(bina, eventId).status()).isEqualTo(201);
        }
    }

    @Test
    void teamEventsTheLeaderAnswersOncePerTeamQuestionsEveryMemberAnswersTheRest() {
        Response created = flows.create(president, club, venues.hall(), "title", "Team Quiz", "eventDate", TestData.futureDate(7), "maxParticipants", 10,
                "registrationEnd", regEnd(), "participationMode", "TEAM", "minTeamSize", 1, "maxTeamSize", 3,
                "registrationForm", map("enabled", true, "questions", List.of(
                        map("type", "SHORT", "label", "Team idea", "required", true, "scope", "TEAM"),
                        map("type", "SINGLE_CHOICE", "label", "Food", "options", List.of("Veg", "Non-veg"), "required", true, "scope", "MEMBER"))));
        String eventId = created.string("$.data._id");
        Map<String, String> q = ids(created);
        flows.markPublished(eventId);

        assertThat(flows.register(asha, eventId, map("teamName", "Owls", "answers", List.of(map("question", q.get("Food"), "choices", List.of("Veg"))))).status())
                .isEqualTo(400);
        Response registered = flows.register(asha, eventId, map("teamName", "Owls", "invitees", List.of(chirag.id()),
                "answers", List.of(map("question", q.get("Food"), "choices", List.of("Veg"))),
                "teamAnswers", List.of(map("question", q.get("Team idea"), "text", "Campus quiz bot"))));
        assertThat(registered.status()).isEqualTo(201);
        Team team = mongo.findOne(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("name").is("Owls")), Team.class);
        assertThat(team.getAnswers().get(0).getText()).isEqualTo("Campus quiz bot");

        String accept = "/api/events/" + eventId + "/teams/" + team.getId() + "/accept";
        assertThat(api.as(chirag).post(accept).status()).isEqualTo(400);
        assertThat(api.as(chirag).post(accept, map("answers", List.of(map("question", q.get("Food"), "choices", List.of("Non-veg"))))).status()).isEqualTo(200);
        EventRegistration member = flows.registrationOf(chirag, eventId);
        assertThat(member.getAnswers().get(0).getChoices()).containsExactly("Non-veg");

        Response list = api.as(president).get("/api/events/" + eventId + "/registrations");
        List<Map<String, Object>> row = list.at("$.data[?(@.user._id == '" + chirag.id() + "')]");
        assertThat(row.get(0).get("formAnswers")).isEqualTo(map(q.get("Team idea"), "Campus quiz bot", q.get("Food"), "Non-veg"));
    }

    @Nested
    class BudgetAndEquipment {

        String eventId;

        @BeforeEach
        void techFest() {
            Response created = flows.create(president, club, venues.auditorium(), "title", "Tech Fest", "eventDate", TestData.futureDate(12), "registrationEnd", regEnd(),
                    "budgetItems", List.of(map("item", "Prizes", "quantity", 3, "unitCost", 2000), map("item", "Snacks", "quantity", 100, "unitCost", 40)),
                    "equipment", List.of(map("name", "Projector", "quantity", 2)),
                    "budgetNote", "Sponsor covers T-shirts");
            assertThat(created.status()).isEqualTo(201);
            assertThat((Integer) created.at("$.data.budgetTotal")).isEqualTo(10000);
            eventId = created.string("$.data._id");
        }

        @Test
        void theClubAsksForABudgetAndEquipmentOnlyTheClubMentorAndAdminSeeIt() {
            Response mentorView = api.as(mentor).get("/api/events/" + eventId);
            assertThat(mentorView.string("$.data.equipment[0].name")).isEqualTo("Projector");
            assertThat((Integer) mentorView.at("$.data.equipment[0].quantity")).isEqualTo(2);

            api.as(president).post("/api/events/" + eventId + "/submit");
            api.as(mentor).post("/api/events/" + eventId + "/approve");
            flows.markPublished(eventId);
            Response publicView = api.as(asha).get("/api/events/" + eventId);
            assertThat(publicView.has("$.data.budgetItems")).isFalse();
            assertThat(publicView.has("$.data.budgetTotal")).isFalse();
            assertThat(publicView.has("$.data.equipment")).isFalse();
        }

        @Test
        void afterItStartsTheClubRecordsActualSpendingForTheMentor() {
            api.as(president).post("/api/events/" + eventId + "/submit");
            api.as(mentor).post("/api/events/" + eventId + "/approve");
            flows.markPublished(eventId);
            assertThat(api.as(president).put("/api/events/" + eventId + "/expenses", map("items", List.of(map("item", "Prizes", "amount", 6000)))).status()).isEqualTo(409);
            flows.times(eventId, null, null, Duration.ofHours(-1), Duration.ofHours(1));
            assertThat(api.as(asha).put("/api/events/" + eventId + "/expenses", map("items", List.of(map("item", "Prizes", "amount", 6000)))).status()).isEqualTo(403);
            Response saved = api.as(president).put("/api/events/" + eventId + "/expenses",
                    map("items", List.of(map("item", "Prizes", "amount", 6000), map("item", "Snacks", "amount", 3500)), "note", "Bills with the treasurer"));
            assertThat(saved.status()).isEqualTo(200);
            assertThat((Integer) saved.at("$.data.expensesTotal")).isEqualTo(9500);
            assertThat(flows.notified(mentor, "EVENT_UPDATED", "Spending recorded for Tech Fest")).isTrue();
        }
    }

    @Test
    void likesOnEventPostsAndGalleryPhotosOncePerPersonOrganisersSeeWhoLiked() {
        String eventId = flows.draft(president, club, venues.hall(), "title", "Photo Walk", "eventDate", TestData.futureDate(9), "registrationEnd", regEnd());
        assertThat(api.as(asha).put("/api/likes/event/" + eventId, map("liked", true)).status()).isEqualTo(404);
        flows.markPublished(eventId);

        api.as(asha).put("/api/likes/event/" + eventId, map("liked", true));
        Response twice = api.as(asha).put("/api/likes/event/" + eventId, map("liked", true));
        assertThat((Map<String, Object>) twice.at("$.data")).isEqualTo(map("liked", true, "likeCount", 1));
        assertThat((Integer) api.as(bina).put("/api/likes/event/" + eventId, map("liked", true)).at("$.data.likeCount")).isEqualTo(2);
        assertThat((Integer) api.as(bina).put("/api/likes/event/" + eventId, map("liked", false)).at("$.data.likeCount")).isEqualTo(1);

        List<Map<String, Object>> card = api.as(asha).get("/api/events?timeframe=upcoming").at("$.data[?(@._id == '" + eventId + "')]");
        assertThat(card.get(0)).containsEntry("likeCount", 1).containsEntry("likedByMe", true);
        assertThat((List<String>) api.as(president).get("/api/likes/event/" + eventId).at("$.data.items[*].user.name")).containsExactly("Asha");
        assertThat(api.as(bina).get("/api/likes/event/" + eventId).status()).isEqualTo(403);

        EventMedia photo = new EventMedia();
        photo.setEvent(eventId);
        photo.setClub(club.getId());
        photo.setUploader(bina.id());
        photo.setUploaderRole(UploaderRole.PARTICIPANT);
        photo.setStatus(GalleryStatus.APPROVED);
        MediaFile media = new MediaFile();
        media.setKind(MediaKind.IMAGE);
        media.setProvider("local");
        media.setKey("x.jpg");
        photo.setMedia(media);
        photo = mongo.insert(photo);
        assertThat((Integer) api.as(asha).put("/api/likes/media/" + photo.getId(), map("liked", true)).at("$.data.likeCount")).isEqualTo(1);
        assertThat(api.as(bina).get("/api/likes/media/" + photo.getId()).string("$.data.items[0].user.name")).isEqualTo("Asha");

        // Unknown kinds of item and malformed ids are refused like the original validators.
        Response unknown = api.as(asha).put("/api/likes/story/" + eventId, map("liked", true));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.message()).isEqualTo("Unknown item");
        assertThat(api.as(asha).put("/api/likes/event/" + eventId, map("liked", "maybe")).status()).isEqualTo(400);
    }
}
