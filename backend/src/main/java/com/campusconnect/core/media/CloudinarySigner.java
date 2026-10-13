package com.campusconnect.core.media;

import com.campusconnect.core.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Cloudinary request signatures: parameters sorted by name, joined as a query string, followed by the API
 * secret, SHA-1 (MediaStore.signParams). Used for browser upload tickets and to verify upload responses.
 */
@Component
public class CloudinarySigner {

    private final AppProperties properties;

    public CloudinarySigner(AppProperties properties) {
        this.properties = properties;
    }

    public String sign(Map<String, ?> params) {
        String payload = new TreeMap<>(params).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !String.valueOf(entry.getValue()).isEmpty())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((payload + properties.getCloudinary().getApiSecret()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
