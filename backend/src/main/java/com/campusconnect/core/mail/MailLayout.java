package com.campusconnect.core.mail;

import com.campusconnect.core.config.AppProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * The branded HTML layout of every e-mail (MailService.layout), rendered by Thymeleaf from
 * {@code templates/mail/layout.html} — values are HTML-escaped by the template engine.
 */
@Component
public class MailLayout {

    private final ITemplateEngine templates;
    private final AppProperties properties;

    public MailLayout(ITemplateEngine templates, AppProperties properties) {
        this.templates = templates;
        this.properties = properties;
    }

    public record Model(
            String heading,
            List<String> paragraphs,
            EmailContent.Action action,
            String code,
            String footnote,
            String image,
            List<EmailContent.Detail> details,
            String reason,
            List<EmailContent.Action> footerLinks,
            EmailContent.Qr qr) {
    }

    public String render(Model model) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("heading", model.heading());
        context.setVariable("paragraphs", model.paragraphs() == null ? List.of() : model.paragraphs());
        context.setVariable("image", absoluteUrl(model.image()));
        context.setVariable("details", (model.details() == null ? List.<EmailContent.Detail>of() : model.details()).stream()
                .filter(row -> row.value() != null && !row.value().isBlank())
                .map(row -> Map.of("label", String.valueOf(row.label()), "value", row.value()))
                .toList());
        context.setVariable("code", model.code());
        context.setVariable("qr", model.qr() == null ? null : Map.of("src", absoluteUrl(model.qr().src()), "alt", model.qr().alt() == null ? "QR code" : model.qr().alt()));
        context.setVariable("action", model.action() == null ? null : Map.of("label", model.action().label(), "url", absoluteUrl(model.action().url())));
        context.setVariable("footnote", model.footnote());
        context.setVariable("reason", model.reason());
        context.setVariable("footerLinks", (model.footerLinks() == null ? List.<EmailContent.Action>of() : model.footerLinks()).stream()
                .map(link -> Map.of("label", link.label(), "url", absoluteUrl(link.url())))
                .toList());
        context.setVariable("universityName", properties.getUniversity().getName());
        return templates.process("mail/layout", context);
    }

    /** Links and images in e-mails must be absolute; uploads are stored as "/uploads/...". */
    public String absoluteUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.regionMatches(true, 0, "http://", 0, 7) || value.regionMatches(true, 0, "https://", 0, 8)) {
            return value;
        }
        String base = properties.getClientUrl().replaceAll("/$", "");
        return base + (value.startsWith("/") ? "" : "/") + value;
    }
}
