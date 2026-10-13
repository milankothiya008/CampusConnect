package com.campusconnect.feature.calendar;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.LastModified;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RefreshInterval;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Url;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.model.property.immutable.ImmutableAction;
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import net.fortuna.ical4j.model.property.immutable.ImmutableStatus;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;

/**
 * iCalendar (.ics) files with ical4j (utils/ICalendar.js): one event to add, or a whole calendar people subscribe
 * to (RFC 5545). Google Calendar, Apple Calendar and Outlook all read these; a subscribed feed is fetched again from
 * time to time, so changed times and cancellations show up in the person's calendar by themselves.
 */
public final class ICalendar {

    /** Longest line in octets of UTF-8 (RFC 5545 3.1). */
    static final int FOLD_OCTETS = 75;
    private static final Instant SEQUENCE_EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    private ICalendar() {
    }

    /** One entry: CONFIRMED, TENTATIVE or CANCELLED; no alarm when {@code alarmMinutes} is null. */
    public record Item(String uid, Instant start, Instant end, String title, String description, String location, String url, String status,
            Instant updatedAt, Integer alarmMinutes) {
    }

    /** Version numbers must grow whenever an item changes; seconds since 2024 fit easily. */
    static int sequenceOf(Instant updatedAt) {
        Instant changed = updatedAt == null ? Instant.now() : updatedAt;
        return (int) Math.max(0, Duration.between(SEQUENCE_EPOCH, changed).toSeconds());
    }

    private static Status status(String value) {
        return switch (value == null ? "CONFIRMED" : value) {
            case "TENTATIVE" -> ImmutableStatus.VEVENT_TENTATIVE;
            case "CANCELLED" -> ImmutableStatus.VEVENT_CANCELLED;
            default -> ImmutableStatus.VEVENT_CONFIRMED;
        };
    }

    public static String build(String name, List<Item> items, boolean feed) {
        Calendar calendar = new Calendar();
        calendar.withProperty(ImmutableVersion.VERSION_2_0)
                .withProperty(new ProdId("-//CampusConnect//Calendar//EN"))
                .withProperty(ImmutableCalScale.GREGORIAN)
                .withProperty(ImmutableMethod.PUBLISH)
                // ical4j escapes text values, X- properties included, exactly like the original escapeText.
                .withProperty(new XProperty("X-WR-CALNAME", name == null ? "" : name))
                .withProperty(new XProperty("X-WR-TIMEZONE", "Asia/Kolkata"));
        if (feed) {
            calendar.withProperty(new RefreshInterval(new ParameterList(List.of(Value.DURATION)), Duration.ofHours(1)))
                    .withProperty(new XProperty("X-PUBLISHED-TTL", "PT1H"));
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (Item item : items) {
            if (item.start() == null || item.end() == null) {
                continue;
            }
            VEvent event = new VEvent(false);
            event.withProperty(new Uid(item.uid()))
                    .withProperty(new DtStamp(now))
                    .withProperty(new DtStart<>(item.start().truncatedTo(ChronoUnit.SECONDS)))
                    .withProperty(new DtEnd<>(item.end().truncatedTo(ChronoUnit.SECONDS)))
                    .withProperty(new Summary(item.title()))
                    .withProperty(new Sequence(sequenceOf(item.updatedAt())))
                    .withProperty(status(item.status()));
            if (item.updatedAt() != null) {
                event.withProperty(new LastModified(item.updatedAt().truncatedTo(ChronoUnit.SECONDS)));
            }
            if (item.description() != null && !item.description().isEmpty()) {
                event.withProperty(new Description(item.description()));
            }
            if (item.location() != null && !item.location().isEmpty()) {
                event.withProperty(new Location(item.location()));
            }
            if (item.url() != null && !item.url().isEmpty()) {
                event.withProperty(new Url(URI.create(item.url())));
            }
            if (item.alarmMinutes() != null && item.alarmMinutes() > 0 && !"CANCELLED".equals(item.status())) {
                VAlarm alarm = new VAlarm();
                alarm.withProperty(ImmutableAction.DISPLAY)
                        .withProperty(new Description(item.title()))
                        .withProperty(new Trigger(Duration.ofMinutes(-item.alarmMinutes())));
                event.add(alarm);
            }
            calendar.withComponent(event);
        }

        return fold(calendar.toString());
    }

    /**
     * Lines longer than 75 octets continue on the next line after a space. ical4j's own folding counts characters,
     * so titles with emoji or non-Latin letters would overrun the limit; this counts UTF-8 octets like the original.
     */
    static String fold(String unfolded) {
        StringBuilder out = new StringBuilder(unfolded.length() + 64);
        for (String line : unfolded.split("\r\n")) {
            int size = 0;
            int limit = FOLD_OCTETS;
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                int width = utf8Width(codePoint);
                if (size + width > limit) {
                    out.append("\r\n ");
                    size = 0;
                    limit = FOLD_OCTETS - 1;
                }
                out.appendCodePoint(codePoint);
                size += width;
                i += Character.charCount(codePoint);
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    private static int utf8Width(int codePoint) {
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }
}
