package com.campusconnect.core.jobs;

import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.notify.BroadcastJob;
import com.campusconnect.core.push.PushJob;
import com.campusconnect.core.config.AppProperties;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes jobs to RabbitMQ. If the broker is unreachable the job runs in-process on a virtual thread instead,
 * so a broker outage never loses a verification code or fails a request.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.jobs.mode", havingValue = "rabbit", matchIfMissing = true)
public class RabbitJobQueue implements JobQueue {

    private final RabbitTemplate rabbit;
    private final AmqpAdmin admin;
    private final JobRunner runner;

    private final List<Duration> retryDelays;

    public RabbitJobQueue(RabbitTemplate rabbit, AmqpAdmin admin, JobRunner runner, AppProperties properties) {
        this.rabbit = rabbit;
        this.admin = admin;
        this.runner = runner;
        this.retryDelays = properties.getJobs().getMailRetryDelays();
    }

    @Override
    public void mail(MailJob job) {
        try {
            rabbit.convertAndSend("", job.immediate() ? JobTopology.MAIL_URGENT : JobTopology.MAIL, job, message -> {
                message.getMessageProperties().setPriority(job.priority());
                return message;
            });
        } catch (AmqpException e) {
            log.warn("RabbitMQ unavailable, sending email in-process: {}", e.getMessage());
            Thread.ofVirtual().start(() -> runner.mail(job));
        }
    }

    @Override
    public void push(PushJob job) {
        try {
            rabbit.convertAndSend("", JobTopology.PUSH, job);
        } catch (AmqpException e) {
            log.warn("RabbitMQ unavailable, pushing in-process: {}", e.getMessage());
            Thread.ofVirtual().start(() -> runner.push(job));
        }
    }

    @Override
    public void broadcast(BroadcastJob job) {
        try {
            rabbit.convertAndSend("", JobTopology.BROADCAST, job);
        } catch (AmqpException e) {
            log.warn("RabbitMQ unavailable, broadcasting in-process: {}", e.getMessage());
            Thread.ofVirtual().start(() -> runner.broadcast(job));
        }
    }

    @Override
    public long pendingMail() {
        long pending = count(JobTopology.MAIL) + count(JobTopology.MAIL_URGENT);
        for (Duration delay : new LinkedHashSet<>(retryDelays)) {
            pending += count(JobTopology.mailRetryQueue(delay));
        }
        return pending;
    }

    private long count(String queue) {
        try {
            Properties info = admin.getQueueProperties(queue);
            Object count = info == null ? null : info.get("QUEUE_MESSAGE_COUNT");
            return count instanceof Number number ? number.longValue() : 0;
        } catch (AmqpException e) {
            return 0;
        }
    }
}
