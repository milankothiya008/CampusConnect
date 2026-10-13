package com.campusconnect.core.tokens;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.Hmacs;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unsubscribe links work without signing in, so each token is signed and names exactly one person and one
 * thing to switch off: an e-mail category ("pref") or a club's bell ("club") — utils/UnsubscribeToken.js.
 */
@Component
public class UnsubscribeTokens {

    public static final List<String> SCOPES = List.of("pref", "club");

    private final String key;
    private final JsonMapper json = JsonMapper.builder().build();

    public UnsubscribeTokens(AppProperties properties) {
        this.key = properties.getJwt().getAccessSecret() + ":unsubscribe";
    }

    public record Unsubscribe(String userId, String scope, String key) {
    }

    private String sign(String payload) {
        return Hmacs.hmacBase64Url(key, payload).substring(0, 32);
    }

    public String create(String userId, String scope, String target) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("u", userId);
        body.put("s", scope);
        body.put("k", target);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    /** The decoded token, or null when it is malformed or tampered with. */
    @SuppressWarnings("unchecked")
    public Unsubscribe read(String token) {
        String[] parts = String.valueOf(token == null ? "" : token).split("\\.", -1);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        if (!Hmacs.safeEquals(sign(parts[0]), parts[1])) {
            return null;
        }
        try {
            Map<String, Object> body = json.readValue(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), Map.class);
            Object u = body.get("u");
            Object s = body.get("s");
            Object k = body.get("k");
            if (u == null || k == null || !SCOPES.contains(String.valueOf(s))) {
                return null;
            }
            return new Unsubscribe(String.valueOf(u), String.valueOf(s), String.valueOf(k));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
