package com.campusconnect.core.mail;

import com.campusconnect.core.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.client.RestClient;

/**
 * Chooses how e-mail leaves the system (MailService.deliveryMode): Brevo when an API key is set, else SMTP when
 * spring.mail.host is set, else "disabled" in production and the development inbox elsewhere.
 */
@Slf4j
@Configuration
public class MailConfig {

    @Bean
    MailTransport mailTransport(AppProperties properties, ObjectProvider<JavaMailSender> smtp, RestClient.Builder rest, DevInbox inbox, Environment environment) {
        AppProperties.Mail mail = properties.getMail();
        String mode = mail.getMode() == null ? "auto" : mail.getMode().trim().toLowerCase(Locale.ROOT);
        JavaMailSender sender = smtp.getIfAvailable();
        if ("auto".equals(mode)) {
            if (!mail.getBrevoApiKey().isBlank()) {
                mode = "brevo";
            } else if (sender != null) {
                mode = "smtp";
            } else {
                mode = environment.acceptsProfiles(Profiles.of("prod")) ? "disabled" : "preview";
            }
        }
        return switch (mode) {
            case "brevo" -> {
                var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
                factory.setReadTimeout(Duration.ofSeconds(15));
                yield new BrevoMailTransport(rest.requestFactory(factory).build(), mail.getBrevoApiKey(), mail.getFrom());
            }
            case "smtp" -> {
                if (sender == null) {
                    throw new IllegalStateException("app.mail.mode=smtp needs spring.mail.host (SMTP_HOST)");
                }
                yield new SmtpMailTransport(sender, mail.getFrom());
            }
            case "disabled" -> new DisabledMailTransport();
            default -> new PreviewMailTransport(inbox);
        };
    }

    /** Makes a missing or broken e-mail setup obvious at start-up instead of failing silently. */
    @EventListener(ApplicationReadyEvent.class)
    void checkMailConfiguration(ApplicationReadyEvent event) {
        MailTransport transport = event.getApplicationContext().getBean(MailTransport.class);
        AppProperties properties = event.getApplicationContext().getBean(AppProperties.class);
        switch (transport.mode()) {
            case "preview" -> log.warn("No email provider is configured: emails are not sent. Open {}/dev/inbox to read them (development only).", properties.getClientUrl());
            case "disabled" -> log.warn("No email provider is configured (BREVO_API_KEY or SMTP_*): verification and reset emails cannot be delivered.");
            case "brevo" -> log.info("Emails are delivered through the Brevo API");
            case "smtp" -> {
                if (transport instanceof SmtpMailTransport && event.getApplicationContext().getBeanProvider(JavaMailSender.class).getIfAvailable() instanceof JavaMailSenderImpl impl) {
                    try {
                        impl.testConnection();
                        log.info("SMTP connection verified; emails will be delivered ({})", impl.getHost());
                    } catch (Exception e) {
                        log.error("SMTP connection failed; check SMTP_* settings ({}): {}", impl.getHost(), e.getMessage());
                    }
                }
            }
            default -> {
            }
        }
    }
}
