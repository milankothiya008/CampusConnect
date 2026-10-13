package com.campusconnect.core.notify;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

/**
 * Every e-mail belongs to one category (constants/EmailCategories.js). People can switch off the optional ones;
 * ACCOUNT e-mails (codes, approvals, confirmations) always go out.
 */
public enum EmailCategory {
    ACCOUNT("account", null, null),
    CLUB_UPDATES("clubUpdates", "Clubs you follow", "New events and announcements from clubs whose bell you turned on."),
    EVENT_RECOMMENDATIONS("eventRecommendations", "New events for you", "Newly published campus events you are eligible to join."),
    EVENT_ACTIVITY("eventActivity", "Your events", "Updates, cancellations and results for events you registered for."),
    RECRUITMENT("recruitment", "Club recruitment", "When a club you can join opens applications. Emails about your own applications always reach you.");

    private final String key;
    private final String label;
    private final String description;

    EmailCategory(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    @JsonValue
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean isOptional() {
        return this != ACCOUNT;
    }

    /** The categories a person can switch off, in settings order. */
    public static final List<EmailCategory> OPTIONAL = Arrays.stream(values()).filter(EmailCategory::isOptional).toList();

    public static EmailCategory fromKey(String key) {
        return Arrays.stream(values()).filter(category -> category.key.equals(key)).findFirst().orElse(null);
    }
}
