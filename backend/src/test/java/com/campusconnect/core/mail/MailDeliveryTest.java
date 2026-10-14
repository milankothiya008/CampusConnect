package com.campusconnect.core.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.notify.EmailCategory;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailDeliveryTest {

    static final String DEMO_ACCOUNTS = "\\d{2}[a-z]{2}dmo\\d{3}@ddu\\.ac\\.in|demo\\.[a-z]{2}@ddu\\.ac\\.in";

    final List<String> sentTo = new ArrayList<>();
    final MailTransport transport = new MailTransport() {
        @Override
        public String mode() {
            return "test";
        }

        @Override
        public void send(MailMessage message) {
            sentTo.add(message.to());
        }
    };
    final MailStats stats = mock(MailStats.class);
    @SuppressWarnings("unchecked")
    final ProxyManager<byte[]> buckets = mock(ProxyManager.class);

    MailDelivery delivery(String suppressPattern) {
        AppProperties properties = new AppProperties();
        properties.getMail().setSuppressPattern(suppressPattern);
        return new MailDelivery(transport, stats, buckets, properties);
    }

    static MailJob bulk(String to) {
        return new MailJob(to, null, EmailCategory.EVENT_ACTIVITY, "HackNight starts tomorrow", "<p>hi</p>", "hi", null, null, null, false, 0);
    }

    static MailJob code(String to) {
        return MailJob.immediate(new MailMessage(to, "123456 is your code", "<p>123456</p>", "123456", "123456", null));
    }

    @Test
    void suppressedAddressesAreNeverSentAndUseNoSendingSlot() throws Exception {
        MailDelivery delivery = delivery(DEMO_ACCOUNTS);

        delivery.deliver(bulk("24cedmo001@ddu.ac.in"), true);
        delivery.deliver(bulk("DEMO.CE@DDU.AC.IN"), true);
        delivery.deliver(code("26cedmo006@ddu.ac.in"), false);

        assertThat(sentTo).isEmpty();
        verifyNoInteractions(buckets);
        verify(stats, never()).sent();
    }

    @Test
    void everyOtherAddressIsSent() throws Exception {
        MailDelivery delivery = delivery(DEMO_ACCOUNTS);

        delivery.deliver(code("24ceuog001@ddu.ac.in"), false);
        // The whole address must match: a look-alike on another domain is still e-mailed.
        delivery.deliver(code("24cedmo001@ddu.ac.in.example.com"), false);

        assertThat(sentTo).containsExactly("24ceuog001@ddu.ac.in", "24cedmo001@ddu.ac.in.example.com");
    }

    @Test
    void withoutAPatternNothingIsSuppressed() throws Exception {
        delivery("").deliver(code("24cedmo001@ddu.ac.in"), false);

        assertThat(sentTo).containsExactly("24cedmo001@ddu.ac.in");
        verify(stats).sent();
    }
}
