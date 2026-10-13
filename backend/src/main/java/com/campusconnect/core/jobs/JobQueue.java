package com.campusconnect.core.jobs;

import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.notify.BroadcastJob;
import com.campusconnect.core.push.PushJob;

/**
 * Background work that must not slow down a request: e-mail, web push and campus-wide notification fan-out.
 * {@code app.jobs.mode=rabbit} queues them in RabbitMQ (retries, dead letters, shared by all replicas);
 * {@code direct} runs them in the calling thread (tests, single-node development).
 */
public interface JobQueue {

    void mail(MailJob job);

    void push(PushJob job);

    void broadcast(BroadcastJob job);

    /** E-mails waiting to be sent (queued + waiting to retry). */
    long pendingMail();
}
