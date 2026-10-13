package com.campusconnect.core.mail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * E-mails captured when no provider is configured (development) — shown on the app's /dev/inbox page so
 * verification and reset codes are reachable. Tests read codes from here too. Never used in production.
 */
@Component
public class DevInbox {

    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"<>]+");

    public record Entry(String id, String to, String subject, String text, String html, String code, List<String> links, Instant sentAt) {
    }

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int limit;

    public DevInbox(@Value("${app.mail.preview-limit:100}") int limit) {
        this.limit = limit;
    }

    void add(MailMessage message) {
        List<String> links = new ArrayList<>();
        Matcher matcher = LINK.matcher(String.valueOf(message.text() == null ? "" : message.text()));
        while (matcher.find()) {
            links.add(matcher.group());
        }
        entries.addFirst(new Entry(String.valueOf(sequence.incrementAndGet()), String.valueOf(message.to()).toLowerCase(Locale.ROOT), message.subject(), message.text(), message.html(), message.code(), links, Instant.now()));
        while (entries.size() > limit) {
            entries.pollLast();
        }
    }

    /** Newest first, optionally only those sent to one address. */
    public List<Entry> list(String to) {
        String address = to == null || to.isBlank() ? null : to.trim().toLowerCase(Locale.ROOT);
        return entries.stream().filter(entry -> address == null || entry.to().equals(address)).toList();
    }

    public void clear() {
        entries.clear();
    }
}
