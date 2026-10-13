package com.campusconnect.feature.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** /api/admin: public reference data, its admin editing (with cache refresh), statistics and the faculty list. */
class AdminRoutesIntegrationTest extends IntegrationTest {

    TestUser admin;
    TestUser faculty;
    TestUser student;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        admin = data.admin();
        faculty = data.faculty("IT", "Ira Faculty");
        student = data.student();
    }

    @Nested
    class Departments {

        @Test
        void arePublicCacheableAndSortedByCode() {
            Response res = api.get("/api/admin/departments");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Departments fetched");
            assertThat(res.header("Cache-Control")).containsExactly("public, max-age=60, stale-while-revalidate=600");
            assertThat((List<String>) res.at("$.data[*].code")).containsExactly("CE", "IT", "ME");
            assertThat((Boolean) res.at("$.data[0].isActive")).isTrue();
            Response cached = api.withToken("garbage").get("/api/admin/departments");
            assertThat(cached.status()).isEqualTo(200);
            assertThat(cached.body()).isEqualTo(res.body());
            Response batches = api.get("/api/admin/batches");
            assertThat(api.get("/api/admin/batches").body()).isEqualTo(batches.body());
        }

        @Test
        void theAdminAddsAndEditsThemAndListsRefreshAtOnce() {
            assertThat((List<String>) api.get("/api/admin/departments?active=true").at("$.data[*].code")).containsExactly("CE", "IT", "ME");

            Response created = api.as(admin).post("/api/admin/departments", Map.of("code", " ec ", "name", " Electronics "));
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.message()).isEqualTo("Department created");
            assertThat(created.string("$.data.code")).isEqualTo("EC");
            assertThat(created.string("$.data.name")).isEqualTo("Electronics");
            assertThat((List<String>) api.get("/api/admin/departments?active=true").at("$.data[*].code")).containsExactly("CE", "EC", "IT", "ME");

            String id = created.string("$.data._id");
            Response updated = api.as(admin).put("/api/admin/departments/" + id, Map.of("code", "XX", "name", "Electronics and Communication", "isActive", false));
            assertThat(updated.message()).isEqualTo("Department updated");
            assertThat(updated.string("$.data.code")).isEqualTo("EC");
            assertThat((Boolean) updated.at("$.data.isActive")).isFalse();
            assertThat((List<String>) api.get("/api/admin/departments?active=true").at("$.data[*].code")).containsExactly("CE", "IT", "ME");
            assertThat((List<String>) api.get("/api/admin/departments").at("$.data[*].code")).containsExactly("CE", "EC", "IT", "ME");
        }

        @Test
        void validationDuplicatesAndPermissions() {
            assertThat(api.as(admin).post("/api/admin/departments", Map.of("code", "C1", "name", "Bad")).message()).isEqualTo("Code must be 2-4 letters");
            assertThat(api.as(admin).post("/api/admin/departments", Map.of("code", "CH")).message()).isEqualTo("Invalid value. Name is required");
            assertThat(api.as(admin).post("/api/admin/departments", Map.of("code", "CH", "name", "Chemical", "isActive", "maybe")).message()).isEqualTo("Invalid value");
            Response duplicate = api.as(admin).post("/api/admin/departments", Map.of("code", "ce", "name", "Again"));
            assertThat(duplicate.status()).isEqualTo(409);
            assertThat(duplicate.message()).isEqualTo("Duplicate value 'CE' for field 'code'. Please use another value.");
            assertThat(api.as(admin).put("/api/admin/departments/0123456789abcdef01234567", Map.of("name", "Nobody")).message()).isEqualTo("Department not found");
            assertThat(api.as(admin).put("/api/admin/departments/nope", Map.of("name", "X")).message()).isEqualTo("Invalid id. Name is required");
            assertThat(api.as(faculty).post("/api/admin/departments", Map.of("code", "CH", "name", "Chemical")).status()).isEqualTo(403);
            assertThat(api.post("/api/admin/departments", Map.of("code", "CH", "name", "Chemical")).status()).isEqualTo(401);
        }
    }

    @Nested
    class Batches {

        @Test
        void areListedNewestFirstAndEditedByTheAdmin() {
            Response list = api.get("/api/admin/batches");
            assertThat(list.message()).isEqualTo("Academic batches fetched");
            assertThat((List<String>) list.at("$.data[*].code")).containsExactly("24", "23");

            Response created = api.as(admin).post("/api/admin/batches", Map.of("code", 25, "label", "Batch 2025"));
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.message()).isEqualTo("Academic batch created");
            assertThat(created.string("$.data.code")).isEqualTo("25");
            assertThat((List<String>) api.get("/api/admin/batches").at("$.data[*].code")).containsExactly("25", "24", "23");

            Response updated = api.as(admin).put("/api/admin/batches/" + created.string("$.data._id"), Map.of("label", "Class of 2029", "isActive", "false"));
            assertThat(updated.message()).isEqualTo("Academic batch updated");
            assertThat(updated.string("$.data.label")).isEqualTo("Class of 2029");
            assertThat((Boolean) updated.at("$.data.isActive")).isFalse();
            assertThat((List<String>) api.get("/api/admin/batches?active=true").at("$.data[*].code")).containsExactly("24", "23");

            assertThat(api.as(admin).post("/api/admin/batches", Map.of("code", "2025", "label", "Batch 2025")).message()).isEqualTo("Batch code must be 2 digits");
            assertThat(api.as(admin).put("/api/admin/batches/0123456789abcdef01234567", Map.of("label", "Nobody")).message()).isEqualTo("Academic batch not found");
        }
    }

    @Nested
    class Overview {

        @Test
        void statisticsCountThePlatform() {
            Club club = data.activeClub(faculty, student);
            data.activeClub(faculty, data.student());
            mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId())), new Update().set("status", "SUSPENDED"), Club.class);

            Response res = api.as(admin).get("/api/admin/stats");
            assertThat(res.message()).isEqualTo("Platform statistics fetched");
            assertThat((Map<String, Object>) res.at("$.data.users")).isEqualTo(Map.of("total", 4, "students", 2, "faculty", 1));
            assertThat((Integer) res.at("$.data.clubs.total")).isEqualTo(2);
            assertThat((Map<String, Object>) res.at("$.data.clubs.byStatus")).isEqualTo(Map.of("ACTIVE", 1, "SUSPENDED", 1));
            assertThat((Map<String, Object>) res.at("$.data.clubRequests")).isEqualTo(Map.of("pendingFacultyReview", 0, "awaitingAdmin", 0));
            assertThat((Integer) res.at("$.data.events.total")).isZero();
            assertThat((Integer) res.at("$.data.memberships")).isEqualTo(2);
            assertThat((Integer) res.at("$.data.venues")).isEqualTo(2);
            assertThat((Map<String, Object>) res.at("$.data.emails")).containsKeys("pending", "sentToday", "failedToday");
            assertThat(api.as(faculty).get("/api/admin/stats").status()).isEqualTo(403);
        }

        @Test
        void theFacultyListShowsWhoMentorsWhat() {
            Club club = data.activeClub("Byte Club", faculty, student);
            TestUser idle = data.faculty("CE", "Zed Faculty");

            Response res = api.as(admin).get("/api/admin/faculty");
            assertThat(res.message()).isEqualTo("Faculty fetched");
            assertThat((List<String>) res.at("$.data[*].name")).containsExactly("Ira Faculty", "Zed Faculty");
            assertThat((Map<String, Object>) res.at("$.data[0].mentoredClubs[0]")).isEqualTo(Map.of("_id", club.getId(), "name", "Byte Club", "status", "ACTIVE", "mentor", faculty.id()));
            assertThat((List<?>) res.at("$.data[1].mentoredClubs")).isEmpty();
            assertThat(res.has("$.data[0].phone")).isFalse();
            assertThat(res.has("$.data[0].isEmailVerified")).isTrue();
            assertThat((List<String>) api.as(admin).get("/api/admin/faculty?search=zed").at("$.data[*]._id")).containsExactly(idle.id());
            assertThat(api.as(student).get("/api/admin/faculty").status()).isEqualTo(403);
        }
    }
}
