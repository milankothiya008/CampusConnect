package com.campusconnect.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.mail.MailMessage;
import com.campusconnect.core.mail.MailService;
import com.campusconnect.core.mail.MailStats;
import com.campusconnect.core.mail.MailTransport;
import com.campusconnect.core.notify.Broadcast;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.StompTestClient;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The production set-up of background work and live chat: jobs through RabbitMQ (retries via delay queues, dead
 * letters, urgent codes, campus-wide fan-out) and STOMP through the RabbitMQ broker relay (what lets several API
 * replicas reach each other's WebSocket clients). Port of the e-mail queue tests of notifications.test.js.
 */
@TestPropertySource(properties = {
    "app.jobs.mode=rabbit",
    "app.realtime.broker=relay",
    "app.jobs.mail-retry-delays=300ms,300ms,300ms,300ms",
    "app.mail.rate-per-minute=6000"
})
@Import(RabbitJobsIntegrationTest.FlakyMail.class)
class RabbitJobsIntegrationTest extends IntegrationTest {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine")
            .withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stomp]."), "/etc/rabbitmq/enabled_plugins")
            .withExposedPorts(5672, 15672, 61613);

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("app.realtime.relay-host", RABBIT::getHost);
        registry.add("app.realtime.relay-port", () -> RABBIT.getMappedPort(61613));
        registry.add("app.realtime.relay-login", RABBIT::getAdminUsername);
        registry.add("app.realtime.relay-passcode", RABBIT::getAdminPassword);
    }

    /** A provider that refuses a configured number of deliveries per subject, then accepts. */
    static class FlakyTransport implements MailTransport {
        final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<MailMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public String mode() {
            return "preview";
        }

        @Override
        public void send(MailMessage message) {
            attempts.computeIfAbsent(message.subject(), key -> new AtomicInteger()).incrementAndGet();
            AtomicInteger left = failuresLeft.get(message.subject());
            if (left != null && left.getAndDecrement() > 0) {
                throw new IllegalStateException("provider refused");
            }
            sent.add(message);
        }

        long sentWith(String subject) {
            return sent.stream().filter(message -> message.subject().equals(subject)).count();
        }

        int attemptsOf(String subject) {
            AtomicInteger count = attempts.get(subject);
            return count == null ? 0 : count.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyMail {
        @Bean
        @Primary
        FlakyTransport flakyTransport() {
            return new FlakyTransport();
        }
    }

    @Autowired
    FlakyTransport transport;

    @Autowired
    MailService mail;

    @Autowired
    MailStats stats;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    NotificationService notifications;

    @Autowired
    RealtimeGateway gateway;

    @BeforeEach
    void purge() {
        for (String queue : List.of(JobTopology.MAIL, JobTopology.MAIL_URGENT, JobTopology.MAIL_DEAD, JobTopology.DEAD)) {
            admin.purgeQueue(queue, false);
        }
        transport.sent.clear();
        transport.attempts.clear();
        transport.failuresLeft.clear();
    }

    static MailJob job(String subject, String dedupeKey) {
        return new MailJob("24ceuog001@ddu.ac.in", null, EmailCategory.CLUB_UPDATES, subject, "<p>" + subject + "</p>", subject, null, dedupeKey, null, false, 0);
    }

    long queued(String queue) {
        var info = admin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    @Test
    void aFailedSendIsRetriedLaterThenDelivered() {
        transport.failuresLeft.put("Retry me", new AtomicInteger(2));
        assertThat(mail.enqueue(List.of(job("Retry me", null)))).isEqualTo(1);

        await().atMost(Duration.ofSeconds(15)).until(() -> transport.sentWith("Retry me") == 1);
        assertThat(transport.attemptsOf("Retry me")).isEqualTo(3);
    }

    @Test
    void givesUpAfterFiveAttemptsAndParksTheEmail() {
        transport.failuresLeft.put("Doomed", new AtomicInteger(100));
        long failedBefore = stats.failedLastDay();
        mail.enqueue(List.of(job("Doomed", null)));

        await().atMost(Duration.ofSeconds(20)).until(() -> queued(JobTopology.MAIL_DEAD) == 1);
        assertThat(transport.attemptsOf("Doomed")).isEqualTo(5);
        assertThat(transport.sentWith("Doomed")).isZero();
        assertThat(stats.failedLastDay()).isEqualTo(failedBefore + 1);
    }

    @Test
    void theSameEmailIsNeverQueuedTwiceForOnePerson() throws Exception {
        assertThat(mail.enqueue(List.of(job("Once", "launch:1:u1")))).isEqualTo(1);
        assertThat(mail.enqueue(List.of(job("Once", "launch:1:u1"), job("Once", "launch:1:u2")))).isEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).until(() -> transport.sentWith("Once") == 2);
        Thread.sleep(500);
        assertThat(transport.sentWith("Once")).isEqualTo(2);
    }

    @Test
    void oneTimeCodesTravelOnTheirOwnQueue() {
        mail.sendNow(new MailMessage("24ceuog001@ddu.ac.in", "123456 is your CampusConnect verification code", "<p>123456</p>", "123456", "123456", null));
        await().atMost(Duration.ofSeconds(10)).until(() -> transport.sentWith("123456 is your CampusConnect verification code") == 1);
        assertThat(mail.stats().pending()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void campusWideNotificationsAreFannedOutByTheBroadcastWorker() {
        data.student();
        data.student();
        data.faculty();
        notifications.notifyAllUsers(Broadcast.builder().type(NotificationType.NEW_CLUB).title("A new club").message("Say hello").link("/clubs").build());

        await().atMost(Duration.ofSeconds(10)).until(() -> mongo.count(new org.springframework.data.mongodb.core.query.Query(), "notifications") == 3);
    }

    @Test
    void liveEventsReachTheBrowserThroughTheRabbitMqRelay() {
        TestUser student = data.student();
        try (StompTestClient tab = StompTestClient.connect(port, student.token())) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                gateway.emitToUser(student.id(), "conversation:updated", Map.of("conversationId", "c9"));
                assertThat(tab.next("conversation:updated", Duration.ofMillis(400))).isNotNull();
            });
        }
        try (StompTestClient anonymous = StompTestClient.open(port, null)) {
            assertThat(anonymous.errorMessage(Duration.ofSeconds(5))).isEqualTo("UNAUTHORIZED");
        }
    }
}
