package com.campusconnect.core.push;

import com.campusconnect.core.config.AppProperties;
import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Web Push with VAPID (RFC 8291 payload encryption, RFC 8292 VAPID) via zerodep-web-push-java on the JDK HTTP
 * client. The keys are the base64url pair printed by {@code npx web-push generate-vapid-keys}. Without keys push
 * is off and everything else works.
 */
@Slf4j
@Component
public class WebPushSender {

    private final VAPIDKeyPair vapid;
    private final String subject;
    private final List<String> allowedHosts;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_2).build();

    public WebPushSender(AppProperties properties) {
        AppProperties.Push push = properties.getPush();
        this.subject = push.getSubject();
        this.allowedHosts = push.getAllowedHosts();
        this.vapid = push.isConfigured() ? keyPair(push.getPublicKey(), push.getPrivateKey()) : null;
    }

    public boolean isConfigured() {
        return vapid != null;
    }

    /**
     * Only real browser push services may be used as endpoints, so a forged subscription cannot make the server
     * call internal addresses (SSRF).
     */
    public boolean isAllowedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getPort() == -1
                    && allowedHosts.stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Sends one encrypted message; returns the push service's HTTP status. */
    public int send(String endpoint, String p256dh, String auth, String payload) throws Exception {
        var subscription = new com.zerodeplibs.webpush.PushSubscription();
        subscription.setEndpoint(endpoint);
        var keys = new com.zerodeplibs.webpush.PushSubscription.Keys();
        keys.setP256dh(p256dh);
        keys.setAuth(auth);
        subscription.setKeys(keys);
        HttpRequest request = StandardHttpClientRequestPreparer.getBuilder()
                .pushSubscription(subscription)
                .vapidJWTExpiresAfter(12, TimeUnit.HOURS)
                .vapidJWTSubject(subject)
                .pushMessage(payload)
                .ttl(24, TimeUnit.HOURS)
                .urgencyNormal()
                .build(vapid)
                .toRequestBuilder()
                .timeout(Duration.ofSeconds(10))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static VAPIDKeyPair keyPair(String publicKey, String privateKey) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
            byte[] scalar = Base64.getUrlDecoder().decode(privateKey.trim());
            ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), curve));
            return VAPIDKeyPairs.of(PrivateKeySources.ofECPrivateKey(key), PublicKeySources.ofUncompressedBytes(Base64.getUrlDecoder().decode(publicKey.trim())));
        } catch (Exception e) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY are not a valid web-push key pair", e);
        }
    }
}
