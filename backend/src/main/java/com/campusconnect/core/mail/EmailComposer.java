package com.campusconnect.core.mail;

import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.tokens.UnsubscribeTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds one person's copy of an e-mail (services/EmailComposer.js). Optional categories get unsubscribe links in
 * the footer plus List-Unsubscribe headers, so Gmail/Outlook show their own one-click "Unsubscribe" button
 * (RFC 8058). A club link switches off that club's bell instead of the whole category.
 */
@Component
public class EmailComposer {

    public static final String SETTINGS_PATH = "/settings/notifications";

    private final MailLayout layout;
    private final UnsubscribeTokens unsubscribeTokens;

    public EmailComposer(MailLayout layout, UnsubscribeTokens unsubscribeTokens) {
        this.layout = layout;
        this.unsubscribeTokens = unsubscribeTokens;
    }

    public MailJob compose(Recipient user, EmailContent content) {
        EmailCategory category = content.getCategory() == null ? EmailCategory.ACCOUNT : content.getCategory();
        List<EmailContent.Action> footerLinks = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();

        if (category != EmailCategory.ACCOUNT) {
            String categoryToken = unsubscribeTokens.create(user.id(), "pref", category.key());
            String clubToken = content.getClub() != null ? unsubscribeTokens.create(user.id(), "club", content.getClub().id()) : null;
            if (clubToken != null) {
                footerLinks.add(new EmailContent.Action("Turn off emails from " + content.getClub().name(), "/unsubscribe?token=" + clubToken));
            } else {
                footerLinks.add(new EmailContent.Action("Unsubscribe from \"" + category.label() + "\"", "/unsubscribe?token=" + categoryToken));
            }
            footerLinks.add(new EmailContent.Action("Email settings", SETTINGS_PATH));
            headers.put("List-Unsubscribe", "<" + layout.absoluteUrl("/api/notifications/unsubscribe?token=" + (clubToken != null ? clubToken : categoryToken)) + ">");
            headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }

        String firstName = String.valueOf(user.name() == null || user.name().isBlank() ? "there" : user.name()).split(" ")[0];
        String greeting = "Hi " + firstName + ",";

        List<String> lines = new ArrayList<>();
        lines.add(greeting);
        lines.add(content.getHeading());
        lines.addAll(content.getParagraphs());
        content.getDetails().stream().filter(row -> row.value() != null && !row.value().isBlank()).forEach(row -> lines.add(row.label() + ": " + row.value()));
        if (content.getCode() != null) {
            lines.add("Ticket code: " + content.getCode());
        }
        if (content.getQr() != null) {
            lines.add("QR code: " + layout.absoluteUrl(content.getQr().src()));
        }
        if (content.getAction() != null) {
            lines.add(content.getAction().label() + ": " + layout.absoluteUrl(content.getAction().url()));
        }
        lines.add(content.getReason());
        footerLinks.forEach(link -> lines.add(link.label() + ": " + layout.absoluteUrl(link.url())));
        String text = String.join("\n\n", lines.stream().filter(Objects::nonNull).filter(line -> !line.isEmpty()).toList());

        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(greeting);
        paragraphs.addAll(content.getParagraphs());
        String html = layout.render(new MailLayout.Model(content.getHeading(), paragraphs, content.getAction(), content.getCode(), null,
                content.getImage(), content.getDetails(), content.getReason(), footerLinks, content.getQr()));

        return new MailJob(user.email(), user.id(), category, content.getSubject(), html, text, headers.isEmpty() ? null : headers, null, null, false, 0);
    }
}
