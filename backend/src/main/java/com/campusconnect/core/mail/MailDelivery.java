package com.campusconnect.core.mail;

import com.campusconnect.core.config.AppProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Delivers one queued e-mail. Bulk mail obeys a cluster-wide sending rate (one Bucket4j bucket in Redis shared
 * by every replica, like EMAIL_RATE_PER_MINUTE) so the provider's limits are respected; one-time codes skip it.
 * Addresses matching app.mail.suppress-pattern are dropped here, before they use up any of that rate.
 */
@Slf4j
@Component
public class MailDelivery {

    private static final byte[] BUCKET_KEY = "cc:rl:mail-send".getBytes(StandardCharsets.UTF_8);

    private final MailTransport transport;
    private final MailStats stats;
    private final ProxyManager<byte[]> buckets;
    private final AppProperties properties;
    private final Pattern suppressed;

    public MailDelivery(MailTransport transport, MailStats stats, ProxyManager<byte[]> buckets, AppProperties properties) {
        this.transport = transport;
        this.stats = stats;
        this.buckets = buckets;
        this.properties = properties;
        String pattern = properties.getMail().getSuppressPattern();
        this.suppressed = pattern == null || pattern.isBlank() ? null : Pattern.compile(pattern.trim(), Pattern.CASE_INSENSITIVE);
    }

    /** Throws when the provider refuses the e-mail (the caller decides about retries). */
    public void deliver(MailJob job, boolean throttle) throws Exception {
        if (suppressed != null && job.to() != null && suppressed.matcher(job.to()).matches()) {
            log.debug("Email to a suppressed address not sent: {}", job.subject());
            return;
        }
        if (throttle && !job.immediate()) {
            waitForSendingSlot();
        }
        transport.send(job.message());
        stats.sent();
    }

    private void waitForSendingSlot() throws InterruptedException {
        int perMinute = Math.max(1, properties.getMail().getRatePerMinute());
        int burst = Math.max(1, Math.round(perMinute * 5 / 60f));
        try {
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(burst).refillGreedy(perMinute, Duration.ofMinutes(1)))
                    .build();
            Bucket bucket = buckets.builder().build(BUCKET_KEY, () -> configuration);
            bucket.asBlocking().consume(1);
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Redis unavailable: pace this consumer locally instead.
            log.warn("Mail rate limiter unavailable ({}); pacing locally", e.getMessage());
            Thread.sleep(60_000L / perMinute);
        }
    }

    public String mode() {
        return transport.mode();
    }
}
