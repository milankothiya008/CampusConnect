package com.campusconnect.core.mail;

import com.campusconnect.core.jobs.JobQueue;
import com.campusconnect.core.util.Hmacs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.stereotype.Service;

/**
 * E-mail entry point (services/MailService.js + EmailQueueService.js). Everything goes through the job queue:
 * one-time codes jump the queue; bulk e-mail is de-duplicated by key, sent in the background at a steady rate
 * and retried with back-off, so publishing an event never waits on an e-mail provider.
 */
@Slf4j
@Service
public class MailService {

    /** A dedupe key blocks the same e-mail for the same person for this long. */
    private static final Duration DEDUPE_TTL = Duration.ofDays(35);
    private static final int BATCH = 500;

    private final JobQueue jobs;
    private final MailLayout layout;
    private final MailDelivery delivery;
    private final MailStats stats;
    private final StringRedisTemplate redis;

    public MailService(JobQueue jobs, MailLayout layout, MailDelivery delivery, MailStats stats, StringRedisTemplate redis) {
        this.jobs = jobs;
        this.layout = layout;
        this.delivery = delivery;
        this.stats = stats;
        this.redis = redis;
    }

    /** brevo, smtp, preview or disabled (shown by /api/health). */
    public String deliveryMode() {
        return delivery.mode();
    }

    public boolean isPreview() {
        return "preview".equals(delivery.mode());
    }

    /** Time-critical mail (codes): sent ahead of everything else; failures are logged, never thrown. */
    public void sendNow(MailMessage message) {
        try {
            jobs.mail(MailJob.immediate(message));
        } catch (RuntimeException e) {
            log.error("Email delivery failed: {} ({})", message.subject(), e.getMessage());
        }
    }

    /** Queues bulk e-mails; jobs whose dedupe key was already used are skipped. Returns how many were queued. */
    public int enqueue(List<MailJob> batch) {
        int queued = 0;
        for (int start = 0; start < batch.size(); start += BATCH) {
            List<MailJob> slice = batch.subList(start, Math.min(batch.size(), start + BATCH));
            List<Boolean> fresh = claimDedupeKeys(slice);
            for (int i = 0; i < slice.size(); i++) {
                if (!fresh.get(i)) {
                    continue;
                }
                try {
                    jobs.mail(slice.get(i));
                    queued++;
                } catch (RuntimeException e) {
                    log.error("Failed to queue email: {}", e.getMessage());
                }
            }
        }
        return queued;
    }

    /** SET NX per dedupe key in one pipelined round trip; fails open when Redis is unavailable. */
    private List<Boolean> claimDedupeKeys(List<MailJob> slice) {
        List<Boolean> fresh = new ArrayList<>(slice.size());
        slice.forEach(job -> fresh.add(Boolean.TRUE));
        List<Integer> keyed = new ArrayList<>();
        for (int i = 0; i < slice.size(); i++) {
            if (slice.get(i).dedupeKey() != null) {
                keyed.add(i);
            }
        }
        if (keyed.isEmpty()) {
            return fresh;
        }
        try {
            List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (int index : keyed) {
                    byte[] key = ("cc:mail:dedupe:" + Hmacs.sha256Hex(slice.get(index).dedupeKey())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    connection.stringCommands().set(key, new byte[] {'1'}, Expiration.from(DEDUPE_TTL), RedisStringCommands.SetOption.SET_IF_ABSENT);
                }
                return null;
            });
            for (int i = 0; i < keyed.size(); i++) {
                fresh.set(keyed.get(i), Boolean.TRUE.equals(results.get(i)));
            }
        } catch (RuntimeException e) {
            log.warn("Email de-duplication unavailable: {}", e.getMessage());
        }
        return fresh;
    }

    public void sendVerificationCode(Recipient user, String code, int ttlMinutes) {
        sendNow(new MailMessage(
                user.email(),
                code + " is your CampusConnect verification code",
                layout.render(new MailLayout.Model(
                        "Welcome, " + user.name(),
                        List.of("Enter this code on the verification screen to confirm your university email and activate your account."),
                        null, code,
                        "The code expires in " + ttlMinutes + " minutes. CampusConnect staff will never ask for it. If you did not create an account, you can ignore this email.",
                        null, List.of(), null, List.of(), null)),
                "Hi " + user.name() + ", your CampusConnect verification code is " + code + ". It expires in " + ttlMinutes + " minutes. Never share this code with anyone.",
                code,
                null));
    }

    public void sendPasswordResetCode(Recipient user, String code, int ttlMinutes) {
        sendNow(new MailMessage(
                user.email(),
                code + " is your CampusConnect password reset code",
                layout.render(new MailLayout.Model(
                        "Password reset requested",
                        List.of("Enter this code on the reset screen to choose a new password."),
                        null, code,
                        "The code expires in " + ttlMinutes + " minutes. If you did not request a password reset, you can ignore this email — your password stays the same.",
                        null, List.of(), null, List.of(), null)),
                "Hi " + user.name() + ", your CampusConnect password reset code is " + code + ". It expires in " + ttlMinutes + " minutes. If you did not request a reset, ignore this email.",
                code,
                null));
    }

    /** Admin dashboard figures (EmailQueueService.emailQueueStats). */
    public record QueueStats(long pending, long sentToday, long failedToday) {
    }

    public QueueStats stats() {
        return new QueueStats(jobs.pendingMail(), stats.sentLastDay(), stats.failedLastDay());
    }

    /** Absolute link for e-mails ("/events/1" -> "https://app/events/1"). */
    public String absoluteUrl(String value) {
        return layout.absoluteUrl(value);
    }
}
