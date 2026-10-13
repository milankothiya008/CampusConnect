package com.campusconnect.feature.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The .ics text keeps the lines, order, escaping and 75-octet folding of utils/ICalendar.js. */
class ICalendarTest {

    static final Instant START = Instant.parse("2026-10-21T12:30:00Z");
    static final Instant END = Instant.parse("2026-10-21T15:30:00Z");
    static final Instant UPDATED = Instant.parse("2026-10-01T08:00:00Z");

    static ICalendar.Item item(String title, String status, Integer alarmMinutes) {
        return new ICalendar.Item("event-1@campusconnect", START, END, title, "Bring a laptop", "Auditorium, Main Block", "http://localhost:3000/events/1", status,
                UPDATED, alarmMinutes);
    }

    static List<String> lines(String ics) {
        return List.of(ics.split("\r\n"));
    }

    @Test
    void oneEventFileHasTheOriginalLinesInTheirOrder() {
        String ics = ICalendar.build("Hack Night", List.of(item("Hack Night", null, 30)), false);

        assertThat(ics).endsWith("END:VCALENDAR\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
        List<String> lines = lines(ics);
        assertThat(lines.subList(0, 8)).containsExactly("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//CampusConnect//Calendar//EN", "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH", "X-WR-CALNAME:Hack Night", "X-WR-TIMEZONE:Asia/Kolkata", "BEGIN:VEVENT");
        assertThat(lines.get(8)).isEqualTo("UID:event-1@campusconnect");
        assertThat(lines.get(9)).matches("DTSTAMP:\\d{8}T\\d{6}Z");
        long sequence = Duration.between(Instant.parse("2024-01-01T00:00:00Z"), UPDATED).toSeconds();
        assertThat(lines.subList(10, lines.size())).containsExactly("DTSTART:20261021T123000Z", "DTEND:20261021T153000Z", "SUMMARY:Hack Night",
                "SEQUENCE:" + sequence, "STATUS:CONFIRMED", "LAST-MODIFIED:20261001T080000Z", "DESCRIPTION:Bring a laptop", "LOCATION:Auditorium\\, Main Block",
                "URL:http://localhost:3000/events/1", "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Hack Night", "TRIGGER:-PT30M", "END:VALARM", "END:VEVENT",
                "END:VCALENDAR");
    }

    @Test
    void textIsEscapedLikeTheOriginal() {
        String ics = ICalendar.build("Club; \"Events\", all", List.of(item("Say \"hi\", folks; now\nok\\", null, 60)), false);

        // ical4j writes the 60-minute alarm as -PT1H; the original wrote the same duration as -PT60M.
        assertThat(lines(ics)).contains("X-WR-CALNAME:Club\\; \"Events\"\\, all", "SUMMARY:Say \"hi\"\\, folks\\; now\\nok\\\\", "TRIGGER:-PT1H");
    }

    @Test
    void longLinesFoldAt75OctetsWithoutSplittingACharacter() {
        String title = "Garba Night 🎉 ગરબા — " + "दीवाली 🪔 ".repeat(6);
        String ics = ICalendar.build("Garba", List.of(item(title, null, null)), false);

        for (String line : lines(ics)) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).as(line).isLessThanOrEqualTo(75);
            assertThat(Character.isHighSurrogate(line.charAt(line.length() - 1))).as(line).isFalse();
        }
        assertThat(ics.replace("\r\n ", "")).contains("\r\nSUMMARY:" + title + "\r\n");
    }

    @Test
    void cancelledItemsKeepTheirPlaceWithoutAnAlarmAndFeedsAskToBeRefreshedHourly() {
        ICalendar.Item unscheduled = new ICalendar.Item("draft@campusconnect", null, null, "Unscheduled", null, null, null, null, null, null);
        String ics = ICalendar.build("Asha", List.of(item("Hack Night", "CANCELLED", 60), unscheduled), true);

        assertThat(lines(ics)).contains("STATUS:CANCELLED", "REFRESH-INTERVAL;VALUE=DURATION:PT1H", "X-PUBLISHED-TTL:PT1H").doesNotContain("BEGIN:VALARM");
        assertThat(ics).doesNotContain("Unscheduled");
    }
}
