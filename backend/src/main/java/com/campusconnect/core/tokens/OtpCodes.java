package com.campusconnect.core.tokens;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.Hmacs;
import org.springframework.stereotype.Component;

/** Six-digit one-time codes (utils/Token.js): only a keyed hash bound to the user is stored. */
@Component
public class OtpCodes {

    public static final int LENGTH = 6;

    private final String key;

    public OtpCodes(AppProperties properties) {
        this.key = properties.getJwt().getAccessSecret();
    }

    public static String generate() {
        return String.format("%06d", Hmacs.randomInt(1_000_000));
    }

    public String hash(String userId, String code) {
        return Hmacs.hmacHex(key, userId + ":" + code);
    }

    public boolean matches(String userId, String code, String storedHash) {
        return storedHash != null && Hmacs.safeEquals(storedHash, hash(userId, code));
    }
}
