package com.campusconnect.core.jobs;

import java.time.Duration;
import java.util.List;

/** RabbitMQ queue names. Jobs are published to the default exchange with the queue name as routing key. */
public final class JobTopology {

    /** Bulk and account e-mail: rate-limited cluster-wide, retried with back-off. */
    public static final String MAIL = "cc.mail";
    /**
     * One-time codes: their own queue and consumers, never throttled, so a sign-up never waits behind a bulk send.
     * Like the direct send in the Node app they are not retried (the person can ask for a new code).
     */
    public static final String MAIL_URGENT = "cc.mail.urgent";
    public static final String MAIL_DEAD = "cc.mail.dead";
    public static final String PUSH = "cc.push";
    public static final String BROADCAST = "cc.broadcast";
    /** Messages a consumer could not process (poison messages), kept for a week for inspection. */
    public static final String DEAD = "cc.dead";

    /**
     * The delay queue of one retry delay (messages wait there, then dead-letter back to {@link #MAIL}). The name
     * carries the delay, so changing {@code app.jobs.mail-retry-delays} creates new queues instead of clashing with
     * the arguments of existing ones.
     */
    public static String mailRetryQueue(Duration delay) {
        long millis = delay.toMillis();
        return "cc.mail.retry." + (millis % 60_000 == 0 ? millis / 60_000 + "m" : millis + "ms");
    }

    /** The delay after the given number of failed deliveries (1-based); the last delay repeats. */
    public static Duration retryDelay(List<Duration> delays, int failures) {
        return delays.get(Math.min(Math.max(failures, 1), delays.size()) - 1);
    }

    private JobTopology() {
    }
}
