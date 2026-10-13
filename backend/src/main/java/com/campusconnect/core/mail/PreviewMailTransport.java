package com.campusconnect.core.mail;

import lombok.extern.slf4j.Slf4j;

/** Development without a provider: e-mails go to the {@link DevInbox}. */
@Slf4j
public class PreviewMailTransport implements MailTransport {

    private final DevInbox inbox;

    public PreviewMailTransport(DevInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public String mode() {
        return "preview";
    }

    @Override
    public void send(MailMessage message) {
        inbox.add(message);
        log.debug("Email captured in the development inbox: {}", message.subject());
    }
}
