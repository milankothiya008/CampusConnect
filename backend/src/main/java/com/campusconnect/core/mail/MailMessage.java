package com.campusconnect.core.mail;

import java.util.Map;

/**
 * One e-mail ready to hand to a provider. {@code code} is only used to show one-time codes in the development
 * inbox (never returned by the API).
 */
public record MailMessage(String to, String subject, String html, String text, String code, Map<String, String> headers) {
}
