package com.campusconnect.core.jobs;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.mail.MailDelivery;
import java.time.Duration;
import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.mail.MailStats;
import com.campusconnect.core.notify.BroadcastJob;
import com.campusconnect.core.notify.NotificationFanout;
import com.campusconnect.core.push.PushDelivery;
import com.campusconnect.core.push.PushJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumers of the job queues (any replica may take any job). Failed e-mails go to a delay queue and come back
 * after 1, 5, 15 and 60 minutes (app.jobs.mail-retry-delays); after the 5th failure they are parked in {@code cc.mail.dead}. Push and
 * broadcast jobs that throw are dead-lettered to {@code cc.dead} (never requeued in a loop).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.jobs.mode", havingValue = "rabbit", matchIfMissing = true)
public class RabbitJobListeners {

    private final MailDelivery mail;
    private final MailStats mailStats;
    private final PushDelivery push;
    private final NotificationFanout fanout;
    private final RabbitTemplate rabbit;

    private final AppProperties.Jobs settings;

    public RabbitJobListeners(MailDelivery mail, MailStats mailStats, PushDelivery push, NotificationFanout fanout, RabbitTemplate rabbit, AppProperties properties) {
        this.mail = mail;
        this.mailStats = mailStats;
        this.push = push;
        this.fanout = fanout;
        this.rabbit = rabbit;
        this.settings = properties.getJobs();
    }

    @RabbitListener(id = "mail", queues = JobTopology.MAIL, concurrency = "${app.jobs.mail-concurrency:2}")
    public void onMail(MailJob job) throws InterruptedException {
        try {
            mail.deliver(job, true);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            int failures = job.attempt() + 1;
            if (failures >= settings.getMailMaxAttempts()) {
                mailStats.failed();
                log.error("Email permanently failed after {} attempts: {} ({})", failures, job.subject(), e.getMessage());
                rabbit.convertAndSend("", JobTopology.MAIL_DEAD, job.withAttempt(failures));
            } else {
                Duration delay = JobTopology.retryDelay(settings.getMailRetryDelays(), failures);
                log.warn("Email send failed; will retry in {}: {} ({})", delay, job.subject(), e.getMessage());
                rabbit.convertAndSend("", JobTopology.mailRetryQueue(delay), job.withAttempt(failures));
            }
        }
    }

    /** One-time codes: sent at once, never throttled; a failure is logged (the person can request a new code). */
    @RabbitListener(id = "mail-urgent", queues = JobTopology.MAIL_URGENT, concurrency = "${app.jobs.urgent-mail-concurrency:1-2}")
    public void onUrgentMail(MailJob job) throws InterruptedException {
        try {
            mail.deliver(job, false);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            mailStats.failed();
            log.error("Email delivery failed: {} ({})", job.subject(), e.getMessage());
        }
    }

    @RabbitListener(id = "push", queues = JobTopology.PUSH, concurrency = "${app.jobs.push-concurrency:2-4}")
    public void onPush(PushJob job) {
        push.deliver(job);
    }

    @RabbitListener(id = "broadcast", queues = JobTopology.BROADCAST, concurrency = "1-2")
    public void onBroadcast(BroadcastJob job) {
        fanout.deliver(job);
    }
}
