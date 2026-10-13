package com.campusconnect.core.tokens;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.Hmacs;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The private address of someone's calendar feed (utils/CalendarToken.js): a signature over the user and their
 * current key version; resetting the link bumps the version, so every older address stops working.
 */
@Component
public class CalendarTokens {

    private static final Pattern USER_ID = Pattern.compile("^[a-fA-F0-9]{24}$");

    private final String key;

    public CalendarTokens(AppProperties properties) {
        this.key = properties.getJwt().getAccessSecret() + ":calendar";
    }

    private String sign(String userId, int version) {
        return Hmacs.hmacBase64Url(key, userId + ":" + version).substring(0, 32);
    }

    public String create(String userId, int version) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(userId.getBytes(StandardCharsets.UTF_8)) + "." + sign(userId, version);
    }

    /** The user id a token names, or null when malformed (the signature is checked by {@link #matches}). */
    public String readUserId(String token) {
        String[] parts = String.valueOf(token == null ? "" : token).split("\\.", -1);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        try {
            String userId = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            return USER_ID.matcher(userId).matches() ? userId : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean matches(String token, String userId, int version) {
        String named = readUserId(token);
        if (named == null || !named.equals(userId)) {
            return false;
        }
        return Hmacs.safeEquals(sign(userId, version), token.split("\\.", -1)[1]);
    }
}
