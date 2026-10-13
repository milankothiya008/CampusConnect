package com.campusconnect.feature.venue;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.cache.CacheNames;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

/** /api/venues over the existing VenueService: public list, availability for planners, admin editing. */
class VenueRoutesIntegrationTest extends IntegrationTest {

    @Autowired
    CacheManager cacheManager;

    TestData.Venues venues;
    TestUser admin;
    TestUser student;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        admin = data.admin();
        student = data.student();
    }

    @Test
    void theListIsPublicAndCacheable() {
        Response res = api.get("/api/venues");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.message()).isEqualTo("Venues fetched");
        assertThat(res.header("Cache-Control")).containsExactly("public, max-age=30, stale-while-revalidate=300");
        assertThat((List<String>) res.at("$.data[*].name")).containsExactly("Auditorium", "Seminar Hall A");
        assertThat((List<?>) api.get("/api/venues?status=inactive").at("$.data")).isEmpty();
        assertThat(res.body()).doesNotContain("bookingLock");
    }

    @Test
    void availabilityIsForSignedInPlannersAndChecksItsQuery() {
        String date = TestData.futureDate(5);
        assertThat(api.get("/api/venues/available?eventDate=" + date + "&startTime=10:00&endTime=12:00").status()).isEqualTo(401);
        assertThat(api.as(student).get("/api/venues/available").message())
                .isEqualTo("eventDate must be YYYY-MM-DD. startTime must be HH:mm. endTime must be HH:mm");
        assertThat(api.as(student).get("/api/venues/available?eventDate=" + date + "&startTime=25:00&endTime=12:00&club=x").message())
                .isEqualTo("startTime must be HH:mm. Invalid value");

        Response res = api.as(student).get("/api/venues/available?eventDate=" + date + "&startTime=10:00&endTime=12:00");
        assertThat(res.message()).isEqualTo("Venue availability fetched");
        assertThat((List<Boolean>) res.at("$.data[*].available")).containsExactly(true, true);
        assertThat(api.as(student).get("/api/venues/available?eventDate=" + date + "&startTime=12:00&endTime=10:00").message()).isEqualTo("endTime must be after startTime");
    }

    @Test
    void theAdminCreatesAndEditsVenuesWhichRefreshesTheEventsFeed() {
        Response invalid = api.as(admin).post("/api/venues", Map.of());
        assertThat(invalid.message()).isEqualTo("Invalid value. Venue name is required. Invalid value. Location is required. Capacity must be a positive number");
        assertThat(api.as(admin).post("/api/venues", Map.of("name", "Dome", "location", "North", "capacity", 50, "type", "SPACESHIP")).message())
                .isEqualTo("Choose the kind of venue");
        assertThat(api.as(admin).post("/api/venues", Map.of("name", "Lab 1", "location", "CE Block", "capacity", 30, "type", "LAB")).message())
                .isEqualTo("Choose the department(s) this lab belongs to");
        assertThat(api.as(student).post("/api/venues", Map.of("name", "Dome", "location", "North", "capacity", 50)).status()).isEqualTo(403);

        Response created = api.as(admin).post("/api/venues", Map.of("name", "  Open Air Theatre ", "location", "North Lawn", "capacity", "250", "type", "OUTDOOR"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.message()).isEqualTo("Venue created");
        assertThat(created.string("$.data.name")).isEqualTo("Open Air Theatre");
        assertThat((Integer) created.at("$.data.capacity")).isEqualTo(250);
        assertThat(api.as(admin).post("/api/venues", Map.of("name", "Open Air Theatre", "location", "Elsewhere", "capacity", 10)).status()).isEqualTo(409);

        cacheManager.getCache(CacheNames.EVENT_FEED).put("probe", "cached page");
        Response updated = api.as(admin).put("/api/venues/" + venues.hall().getId(), Map.of("capacity", 75));
        assertThat(updated.message()).isEqualTo("Venue updated");
        assertThat((Integer) updated.at("$.data.capacity")).isEqualTo(75);
        assertThat(cacheManager.getCache(CacheNames.EVENT_FEED).get("probe")).isNull();
        assertThat(api.as(admin).put("/api/venues/0123456789abcdef01234567", Map.of("capacity", 75)).message()).isEqualTo("Venue not found");
        assertThat(api.as(admin).put("/api/venues/nope", Map.of("capacity", 0)).message()).isEqualTo("Invalid id. Capacity must be a positive number");
    }
}
