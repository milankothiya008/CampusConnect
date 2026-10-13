package com.campusconnect.core.mail;

import com.campusconnect.core.notify.EmailCategory;
import java.util.Map;

/**
 * An e-mail travelling through the job queue. {@code immediate} jobs (one-time codes) skip the bulk sending
 * rate; {@code attempt} counts failed deliveries so far.
 */
public record MailJob(
        String to,
        String user,
        EmailCategory category,
        String subject,
        String html,
        String text,
        Map<String, String> headers,
        String dedupeKey,
        String code,
        boolean immediate,
        int attempt) {

    public static MailJob immediate(MailMessage message) {
        return new MailJob(message.to(), null, EmailCategory.ACCOUNT, message.subject(), message.html(), message.text(), message.headers(), null, message.code(), true, 0);
    }

    public MailMessage message() {
        return new MailMessage(to, subject, html, text, code, headers);
    }

    public MailJob withAttempt(int next) {
        return new MailJob(to, user, category, subject, html, text, headers, dedupeKey, code, immediate, next);
    }

    public MailJob withDedupeKey(String key) {
        return new MailJob(to, user, category, subject, html, text, headers, key, code, immediate, attempt);
    }

    /** Queue priority: codes first, then account e-mails, then updates about your own events, then the rest. */
    public int priority() {
        if (immediate) {
            return 10;
        }
        if (category == EmailCategory.ACCOUNT) {
            return 9;
        }
        return category == EmailCategory.EVENT_ACTIVITY ? 7 : 5;
    }
}
