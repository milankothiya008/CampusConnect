package com.campusconnect.core.jobs;

import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.notify.BroadcastJob;
import com.campusconnect.core.push.PushJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs jobs right away in the calling thread (tests and single-node development without RabbitMQ). */
@Component
@ConditionalOnProperty(name = "app.jobs.mode", havingValue = "direct")
public class DirectJobQueue implements JobQueue {

    private final JobRunner runner;

    public DirectJobQueue(JobRunner runner) {
        this.runner = runner;
    }

    @Override
    public void mail(MailJob job) {
        runner.mail(job);
    }

    @Override
    public void push(PushJob job) {
        runner.push(job);
    }

    @Override
    public void broadcast(BroadcastJob job) {
        runner.broadcast(job);
    }

    @Override
    public long pendingMail() {
        return 0;
    }
}
