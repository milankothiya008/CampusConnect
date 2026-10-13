package com.campusconnect.core.jobs;

import com.campusconnect.core.mail.MailDelivery;
import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.mail.MailStats;
import com.campusconnect.core.notify.BroadcastJob;
import com.campusconnect.core.notify.NotificationFanout;
import com.campusconnect.core.push.PushDelivery;
import com.campusconnect.core.push.PushJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Executes a job in this process (direct mode, and the fallback when RabbitMQ is down). Never throws. */
@Slf4j
@Component
public class JobRunner {

    private final ObjectProvider<MailDelivery> mail;
    private final ObjectProvider<MailStats> mailStats;
    private final ObjectProvider<PushDelivery> push;
    private final ObjectProvider<NotificationFanout> fanout;

    public JobRunner(ObjectProvider<MailDelivery> mail, ObjectProvider<MailStats> mailStats, ObjectProvider<PushDelivery> push, ObjectProvider<NotificationFanout> fanout) {
        this.mail = mail;
        this.mailStats = mailStats;
        this.push = push;
        this.fanout = fanout;
    }

    public void mail(MailJob job) {
        try {
            mail.getObject().deliver(job, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            mailStats.getObject().failed();
            log.error("Email delivery failed: {} ({})", job.subject(), e.getMessage());
        }
    }

    public void push(PushJob job) {
        try {
            push.getObject().deliver(job);
        } catch (RuntimeException e) {
            log.error("Push delivery failed: {}", e.getMessage());
        }
    }

    public void broadcast(BroadcastJob job) {
        try {
            fanout.getObject().deliver(job);
        } catch (RuntimeException e) {
            log.error("Failed to broadcast notifications ({}): {}", job.type(), e.getMessage());
        }
    }
}
