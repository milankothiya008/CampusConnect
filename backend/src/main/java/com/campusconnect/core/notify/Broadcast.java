package com.campusconnect.core.notify;

import com.campusconnect.domain.notification.NotificationType;
import java.util.Collection;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Campus-wide activity (new events, announcements, results, new clubs) for every active user, in-app only.
 * Deliberately no e-mail; {@code pushTo} is the few people who should also get a phone notification.
 */
@Getter
@Builder
public class Broadcast {

    private final NotificationType type;
    private final String title;
    @Builder.Default
    private final String message = "";
    private final String link;
    @Builder.Default
    private final Collection<String> exclude = List.of();
    @Builder.Default
    private final Collection<String> pushTo = List.of();
}
