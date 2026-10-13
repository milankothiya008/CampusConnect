package com.campusconnect.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.mail.MailJob;
import com.campusconnect.core.mail.MailMessage;
import com.campusconnect.core.notify.EmailCategory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobTopologyTest {

    @Test
    void retryQueuesAreNamedAfterTheirDelay() {
        assertThat(JobTopology.mailRetryQueue(Duration.ofMinutes(15))).isEqualTo("cc.mail.retry.15m");
        assertThat(JobTopology.mailRetryQueue(Duration.ofMillis(300))).isEqualTo("cc.mail.retry.300ms");
    }

    @Test
    void theLastRetryDelayRepeats() {
        List<Duration> delays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));
        assertThat(JobTopology.retryDelay(delays, 1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(JobTopology.retryDelay(delays, 2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(JobTopology.retryDelay(delays, 9)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void codesGoFirstThenAccountMailThenUpdatesAboutYourOwnEvents() {
        MailJob code = MailJob.immediate(new MailMessage("a@b.co", "s", "h", "t", "123456", null));
        MailJob account = new MailJob("a@b.co", null, EmailCategory.ACCOUNT, "s", "h", "t", null, null, null, false, 0);
        MailJob activity = new MailJob("a@b.co", null, EmailCategory.EVENT_ACTIVITY, "s", "h", "t", null, null, null, false, 0);
        MailJob news = new MailJob("a@b.co", null, EmailCategory.CLUB_UPDATES, "s", "h", "t", null, null, null, false, 0);
        assertThat(List.of(code.priority(), account.priority(), activity.priority(), news.priority())).containsExactly(10, 9, 7, 5);
    }
}
