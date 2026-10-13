package com.campusconnect.core.mail;

/** Hands an e-mail to a provider; throws when the provider refuses it so the queue can retry. */
public interface MailTransport {

    /** brevo, smtp, preview or disabled. */
    String mode();

    void send(MailMessage message) throws Exception;
}
