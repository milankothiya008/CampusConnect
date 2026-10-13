package com.campusconnect.core.notify;

import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.Recipient;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import lombok.Builder;
import lombok.Getter;

/**
 * Personal e-mails to many people (NotificationService.emailUsers options): {@code compose} builds each
 * person's content; {@code dedupeKey} (optional) stops the same e-mail reaching someone twice.
 */
@Getter
@Builder
public class EmailRequest {

    private final EmailCategory category;
    private final Function<Recipient, EmailContent> compose;
    private final Function<Recipient, String> dedupeKey;
    @Builder.Default
    private final Collection<String> exclude = List.of();
}
