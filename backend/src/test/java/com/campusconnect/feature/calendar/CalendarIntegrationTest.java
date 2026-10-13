package com.campusconnect.feature.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of the calendar half of tests/integration/calendarAudience.test.js ("add to calendar and the personal calendar
 * feed"). The other half — announcements going only to the audience the club picks — belongs to the feed feature.
 */
class CalendarIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser asha;
    TestUser bina;
    Club club;
    String eventId;
    String otherId;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(spec -> spec.name("Prez"));
        asha = data.student(spec -> spec.name("Asha"));
        bina = data.student(spec -> spec.name("Bina"));
        club = data.activeClub("Coding Club", mentor, president);
        // The original relied on its default close time (now + 8 days) falling before 18:00 IST on day 8, which is only
        // true when the suite runs before 18:00 IST; closing a week from now keeps it true at any hour.
        String regEnd = EventFlows.fromNow(Duration.ofDays(7));
        eventId = flows.published(president, mentor, club, venues.auditorium(), "title", "Hack Night", "eventDate", TestData.futureDate(8), "startTime", "18:00",
                "endTime", "21:00", "registrationEnd", regEnd);
        otherId = flows.published(president, mentor, club, venues.auditorium(), "title", "Quiz Day", "eventDate", TestData.futureDate(9), "startTime", "10:00",
                "endTime", "12:00", "registrationEnd", regEnd);
    }

    static String pathOf(String url) {
        return URI.create(url).getPath();
    }

    @Test
    void anyoneCanDownloadAPublishedEventAsAnIcsFile() {
        Response res = api.get("/api/calendar/events/" + eventId + ".ics");
        assertThat(res.status()).isEqualTo(200);
        assertThat(String.join(",", res.header("Content-Type"))).contains("text/calendar");
        assertThat(String.join(",", res.header("Content-Disposition"))).contains("Hack Night.ics");
        assertThat(res.body()).contains("BEGIN:VCALENDAR");
        assertThat(res.body()).contains("UID:event-" + eventId + "@campusconnect");
        assertThat(res.body()).contains("SUMMARY:Hack Night");
        assertThat(res.body()).contains("STATUS:CONFIRMED");

        String draft = flows.draft(president, club, venues.hall(), "title", "Secret draft");
        assertThat(api.get("/api/calendar/events/" + draft + ".ics").status()).isEqualTo(404);
    }

    @Test
    void theFeedHoldsTheStudentsOwnEventsFollowsChangesAndCancellationsAndItsLinkCanBeReset() {
        flows.register(asha, eventId);
        flows.register(bina, otherId);

        Map<String, Object> link = api.as(asha).get("/api/calendar/link").at("$.data");
        assertThat((String) link.get("webcal")).startsWith("webcal://");
        assertThat((String) link.get("google")).contains("calendar.google.com");
        String url = (String) link.get("url");
        Response feed = api.get(pathOf(url));
        assertThat(feed.status()).isEqualTo(200);
        assertThat(feed.body()).contains("SUMMARY:Hack Night").doesNotContain("Quiz Day").contains("REFRESH-INTERVAL");

        // A cancelled event stays in the calendar, marked cancelled, so the calendar app updates it.
        flows.set(eventId, new Update().set("status", "CANCELLED"));
        assertThat(api.get(pathOf(url)).body()).contains("STATUS:CANCELLED");

        // Someone else's token, a tampered one, or one from before a reset: not found.
        String tampered = url.replaceFirst("\\.([^.]+)\\.ics$", ".AAAA$1.ics");
        assertThat(api.get(pathOf(tampered)).status()).isEqualTo(404);
        Map<String, Object> fresh = api.as(asha).post("/api/calendar/link/reset").at("$.data");
        assertThat(fresh.get("url")).isNotEqualTo(url);
        assertThat(api.get(pathOf(url)).status()).isEqualTo(404);
        assertThat(api.get(pathOf((String) fresh.get("url"))).status()).isEqualTo(200);
        assertThat(api.get("/api/calendar/link").status()).isEqualTo(401);
    }
}
