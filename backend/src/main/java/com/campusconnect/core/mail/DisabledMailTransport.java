package com.campusconnect.core.mail;

import lombok.extern.slf4j.Slf4j;

/** Production without a provider: e-mails are dropped with a warning. */
@Slf4j
public class DisabledMailTransport implements MailTransport {

    @Override
    public String mode() {
        return "disabled";
    }

    @Override
    public void send(MailMessage message) {
        log.warn("No email provider is configured; email not sent: {}", message.subject());
    }
}
