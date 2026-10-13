package com.campusconnect.core.mail;

import com.campusconnect.core.notify.EmailCategory;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** What one e-mail says (the options of EmailComposer.composeEmail); see {@link EmailComposer}. */
@Getter
@Builder(toBuilder = true)
public class EmailContent {

    private final EmailCategory category;
    private final String subject;
    private final String heading;
    @Builder.Default
    private final List<String> paragraphs = List.of();
    /** Label/value rows; rows without a value are left out. */
    @Builder.Default
    private final List<Detail> details = List.of();
    private final String image;
    private final Action action;
    /** Why this person got the e-mail. */
    private final String reason;
    /** Makes the unsubscribe link switch off this club's bell instead of the whole category. */
    private final ClubLink club;
    /** Ticket code shown in the text version. */
    private final String code;
    private final Qr qr;

    public record Detail(String label, String value) {
    }

    public record Action(String label, String url) {
    }

    public record ClubLink(String id, String name) {
    }

    public record Qr(String src, String alt) {
    }
}
