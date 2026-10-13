package com.campusconnect.core.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 and hashing helpers for signed links, tickets and one-time codes. */
public final class Hmacs {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hmacs() {
    }

    public static byte[] hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String hmacHex(String key, String data) {
        return HexFormat.of().formatHex(hmacSha256(key, data));
    }

    public static String hmacBase64Url(String key, String data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(key, data));
    }

    public static String sha256Hex(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of two strings (null-safe). */
    public static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code bytes} random bytes as lower-case hex. */
    public static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    public static int randomInt(int bound) {
        return RANDOM.nextInt(bound);
    }
}
