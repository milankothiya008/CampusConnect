package com.campusconnect.core.tokens;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.Hmacs;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Event tickets (utils/TicketToken.js). Each place gets a short code printed on the ticket and typed at the door
 * when a QR can't be scanned, and a signed token (the QR's content). The signature covers the registration and
 * its current code, so a QR from a cancelled registration fails before any database lookup.
 */
@Component
public class TicketTokens {

    /** No 0/O, 1/I/L or U: easy to read out and type. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
    public static final Pattern CODE_PATTERN = Pattern.compile("^CC-[A-HJ-NP-TV-Z2-9]{8}$");
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-f0-9]{24}$");

    private final String key;

    public TicketTokens(AppProperties properties) {
        this.key = properties.getJwt().getAccessSecret() + ":ticket";
    }

    public record Ticket(String registrationId, String ticketCode) {
    }

    public static String generateCode() {
        StringBuilder code = new StringBuilder("CC-");
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET.charAt(Hmacs.randomInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** Accepts "cc-7k3m 9qwa", "CC7K3M9QWA" or "7K3M9QWA"; returns the canonical code or null. */
    public static String normalizeCode(String input) {
        String raw = input == null ? "" : input.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        String body = raw.length() == 10 && raw.startsWith("CC") ? raw.substring(2) : raw;
        String code = "CC-" + body;
        return CODE_PATTERN.matcher(code).matches() ? code : null;
    }

    private String sign(String registrationId, String ticketCode) {
        return Hmacs.hmacBase64Url(key, registrationId + ":" + ticketCode).substring(0, 32);
    }

    public String createToken(String registrationId, String ticketCode) {
        return registrationId + "." + ticketCode + "." + sign(registrationId, ticketCode);
    }

    /** The ticket named by a QR token, or null when it is malformed or tampered with. */
    public Ticket read(String token) {
        String[] parts = String.valueOf(token == null ? "" : token).split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        if (!ID_PATTERN.matcher(parts[0]).matches() || !CODE_PATTERN.matcher(parts[1]).matches()) {
            return null;
        }
        return Hmacs.safeEquals(sign(parts[0], parts[1]), parts[2]) ? new Ticket(parts[0], parts[1]) : null;
    }
}
