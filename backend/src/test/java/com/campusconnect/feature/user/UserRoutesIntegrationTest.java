package com.campusconnect.feature.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.CheckInStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.CertificateKind;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.domain.user.RefreshToken;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** /api/users: accounts, profiles, people search and the participation record. */
class UserRoutesIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    TestUser admin;
    TestUser mentor;
    TestUser president;
    TestUser student;
    TestUser outsider;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        admin = data.admin();
        mentor = data.faculty("CE", "Asha Mentor");
        president = data.student(s -> s.name("Ravi President"));
        student = data.student(s -> s.name("Neha Student").department("IT"));
        outsider = data.student(s -> s.name("Omar Outsider"));
        club = data.activeClub("Drone Club", mentor, president);
    }

    @Nested
    class Accounts {

        @Test
        void theAdminListsAndFiltersAccounts() {
            Response all = api.as(admin).get("/api/users?limit=2");
            assertThat(all.message()).isEqualTo("Users fetched");
            assertThat((Integer) all.at("$.meta.total")).isEqualTo(5);
            assertThat((Integer) all.at("$.meta.totalPages")).isEqualTo(3);
            assertThat((List<?>) all.at("$.data")).hasSize(2);
            assertThat(all.body()).doesNotContain("password");

            assertThat((List<String>) api.as(admin).get("/api/users?role=faculty").at("$.data[*]._id")).containsExactly(mentor.id());
            assertThat((List<String>) api.as(admin).get("/api/users?department=it").at("$.data[*]._id")).containsExactly(student.id());
            assertThat((List<String>) api.as(admin).get("/api/users?search=omar").at("$.data[*].name")).containsExactly("Omar Outsider");
            assertThat(api.as(student).get("/api/users").status()).isEqualTo(403);
        }

        @Test
        void anAccountIsReadableByItsOwnerAndTheAdmin() {
            Response self = api.as(student).get("/api/users/" + student.id());
            assertThat(self.message()).isEqualTo("User fetched");
            assertThat(self.string("$.data.phone")).startsWith("+91");
            assertThat((Boolean) self.at("$.data.isEmailVerified")).isTrue();
            assertThat(api.as(admin).get("/api/users/" + student.id()).status()).isEqualTo(200);
            assertThat(api.as(outsider).get("/api/users/" + student.id()).message()).isEqualTo("University admin access required");
            assertThat(api.as(admin).get("/api/users/0123456789abcdef01234567").message()).isEqualTo("User not found");
        }

        @Test
        void profileEditsCleanTheirValues() {
            Response renamed = api.as(student).put("/api/users/" + student.id(), Map.of("name", "  Neha S  ", "avatar", "/uploads/avatars/neha.png"));
            assertThat(renamed.message()).isEqualTo("Profile updated");
            assertThat(renamed.string("$.data.name")).isEqualTo("Neha S");
            assertThat(renamed.string("$.data.avatar")).isEqualTo("/uploads/avatars/neha.png");
            assertThat(api.as(student).put("/api/users/" + student.id(), Map.of("name", "N")).message()).isEqualTo("Name must be 2-80 characters");
            assertThat(api.as(student).put("/api/users/" + student.id(), Map.of("avatar", "javascript:alert(1)")).message()).isEqualTo("Upload the photo again");
            assertThat(api.as(student).put("/api/users/" + student.id(), Map.of("phone", 9876543210L)).message()).isEqualTo("Invalid value");
            Map<String, Object> clearAvatar = new java.util.HashMap<>();
            clearAvatar.put("avatar", null);
            assertThat((Object) api.as(student).put("/api/users/" + student.id(), clearAvatar).at("$.data.avatar")).isNull();
            assertThat(api.as(outsider).put("/api/users/" + student.id(), Map.of("name", "Hacker")).message()).isEqualTo("You can only update your own profile");
        }

        @Test
        void deactivatingAnAccountEndsItsSessionsAtOnce() {
            RefreshToken token = new RefreshToken();
            token.setUser(student.id());
            token.setTokenHash("hash-for-test");
            token.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
            mongo.insert(token);
            assertThat(api.as(student).get("/api/clubs/mine").status()).isEqualTo(200);

            Response off = api.as(admin).patch("/api/users/" + student.id() + "/status", Map.of("isActive", false));
            assertThat(off.message()).isEqualTo("Account deactivated");
            assertThat((Boolean) off.at("$.data.isActive")).isFalse();
            assertThat(mongo.findById(token.getId(), RefreshToken.class).getRevokedAt()).isNotNull();
            assertThat(api.as(student).get("/api/clubs/mine").status()).isEqualTo(401);
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("USER_STATUS_CHANGED").and("toState").is("INACTIVE")), "auditlogs")).isTrue();

            assertThat(api.as(admin).patch("/api/users/" + student.id() + "/status", Map.of("isActive", true)).message()).isEqualTo("Account activated");
            assertThat(api.as(admin).patch("/api/users/" + admin.id() + "/status", Map.of("isActive", false)).message()).isEqualTo("You cannot change your own account status");
            assertThat(api.as(admin).patch("/api/users/" + student.id() + "/status", Map.of("isActive", "off")).message()).isEqualTo("isActive must be true or false");
            assertThat(api.as(student).patch("/api/users/" + outsider.id() + "/status", Map.of("isActive", false)).status()).isEqualTo(403);
        }
    }

    @Nested
    class Search {

        @Test
        void peopleSearchShowsOnlyPublicFieldsAndNeverTheAdmin() {
            Response res = api.as(student).get("/api/users/people?q=e");
            assertThat(res.message()).isEqualTo("People found");
            assertThat((List<?>) res.at("$.data")).isEmpty();

            Response people = api.as(student).get("/api/users/people?q=  ne  ");
            assertThat((List<String>) people.at("$.data[*].name")).containsExactly("Neha Student");
            assertThat((Boolean) people.at("$.data[0].isSelf")).isTrue();
            assertThat(people.body()).doesNotContain("email").doesNotContain("phone");
            assertThat((List<?>) api.as(student).get("/api/users/people?q=University").at("$.data")).isEmpty();
            assertThat((List<String>) api.as(student).get("/api/users/people?q=en").at("$.data[*].name")).containsExactly("Asha Mentor", "Neha Student", "Ravi President");
            assertThat((List<?>) api.as(student).get("/api/users/people?q=en&limit=1").at("$.data")).hasSize(1);
            assertThat((List<String>) api.as(student).get("/api/users/people?q=ha&department=ce").at("$.data[*].name")).containsExactly("Asha Mentor");
            assertThat(api.as(student).get("/api/users/people?q=" + "x".repeat(81)).message()).isEqualTo("Invalid value");
        }

        @Test
        void thePickerSearchValidatesItsQuery() {
            assertThat(api.as(student).get("/api/users/search").message()).isEqualTo("Invalid value. Enter at least 2 characters");
            assertThat(api.as(student).get("/api/users/search?q=ab&departments=C1").message()).isEqualTo("Invalid departments");
            assertThat(api.as(student).get("/api/users/search?q=ab&accountType=ADMIN").message()).isEqualTo("Invalid value");
            Response found = api.as(student).get("/api/users/search?q=ravi");
            assertThat(found.message()).isEqualTo("Users found");
            assertThat((Map<String, Object>) found.at("$.data[0]")).containsKeys("_id", "name", "email", "accountType", "departmentCode", "batchCode").hasSize(6);
        }
    }

    @Nested
    class Profiles {

        @Test
        void aStudentsProfileShowsActiveClubsAndPastParticipation() {
            Club paused = data.activeClub("Paused Club", mentor, data.student());
            data.addMembership(paused, president);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(paused.getId())), new Update().set("status", "SUSPENDED"), Club.class);
            Event past = event("Robo Race");
            EventRegistration registration = new EventRegistration();
            registration.setEvent(past.getId());
            registration.setUser(president.id());
            registration.setCheckedInAt(past.getStartAt());
            mongo.insert(registration);
            award(past, president, "Winner");

            Response res = api.as(outsider).get("/api/users/" + president.id() + "/profile");
            assertThat(res.message()).isEqualTo("Profile fetched");
            assertThat((List<String>) res.at("$.data.clubs[*].club.name")).containsExactly("Drone Club");
            assertThat(res.string("$.data.clubs[0].roleName")).isEqualTo("President");
            assertThat((Object) res.at("$.data.phone")).isNull();
            assertThat((Boolean) res.at("$.data.isSelf")).isFalse();
            assertThat(res.string("$.data.history.events[0].title")).isEqualTo("Robo Race");
            assertThat(res.string("$.data.history.events[0].attendance")).isEqualTo("Attended");
            assertThat(res.string("$.data.history.awards[0].title")).isEqualTo("Winner");
            assertThat(res.has("$.data.email")).isFalse();

            assertThat(api.as(president).get("/api/users/" + president.id() + "/profile").string("$.data.phone")).startsWith("+91");
            assertThat(api.as(mentor).get("/api/users/" + president.id() + "/profile").string("$.data.phone")).startsWith("+91");
        }

        @Test
        void aFacultyProfileListsMentoredClubsAndNoHistory() {
            Response res = api.as(outsider).get("/api/users/" + mentor.id() + "/profile");
            assertThat((List<String>) res.at("$.data.mentoredClubs[*].name")).containsExactly("Drone Club");
            assertThat((Object) res.at("$.data.history")).isNull();
            assertThat(api.as(outsider).get("/api/users/0123456789abcdef01234567/profile").message()).isEqualTo("Profile not found");
        }

        @Test
        void theParticipationRecordDownloadsAsAPdf() throws Exception {
            Event past = event("Robo Race");
            EventRegistration registration = new EventRegistration();
            registration.setEvent(past.getId());
            registration.setUser(president.id());
            mongo.insert(registration);
            Certificate certificate = new Certificate();
            certificate.setCode("CERT-1234");
            certificate.setEvent(past.getId());
            certificate.setClub(club.getId());
            certificate.setUser(president.id());
            certificate.setKind(CertificateKind.PARTICIPATION);
            certificate.setRecipientName("Ravi President");
            certificate.setEventTitle("Robo Race");
            certificate.setClubName("Drone Club");
            certificate.setEventStartAt(past.getStartAt());
            mongo.insert(certificate);

            MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get("/api/users/me/record.pdf").header("Authorization", "Bearer " + president.token()))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"Ravi_President_participation_record.pdf\"");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
            byte[] pdf = response.getContentAsByteArray();
            assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
            String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
            assertThat(text).contains("Participation record").contains("Ravi President").contains("Drone Club").contains("Robo Race").contains("CERT-1234")
                    .contains("Registered");
        }

        Event event(String title) {
            Event event = new Event();
            event.setTitle(title);
            event.setCategory("SOCIAL_SERVICE");
            event.setClub(club.getId());
            event.setVenue(venues.hall().getId());
            event.setStartAt(Instant.now().minus(3, ChronoUnit.DAYS));
            event.setEndAt(Instant.now().minus(3, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS));
            event.setStatus(EventStatus.COMPLETED);
            event.getCheckIn().setStatus(CheckInStatus.NOT_STARTED);
            return mongo.insert(event);
        }

        void award(Event event, TestUser winner, String title) {
            ResultAward award = new ResultAward();
            award.setTitle(title);
            award.setPosition(1);
            award.setRecipientUser(winner.id());
            EventResult result = new EventResult();
            result.setEvent(event.getId());
            result.setClub(club.getId());
            result.setAwards(List.of(award));
            result.setStatus(ResultStatus.PUBLISHED);
            result.setPublishedAt(Instant.now());
            mongo.insert(result);
        }
    }
}
