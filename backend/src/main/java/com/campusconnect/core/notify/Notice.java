package com.campusconnect.core.notify;

import com.campusconnect.domain.notification.NotificationType;
import java.util.Collection;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** One in-app notification for a few people (NotificationService.notify options). */
@Getter
@Builder
public class Notice {

    private final NotificationType type;
    private final String title;
    @Builder.Default
    private final String message = "";
    private final String link;
    /** Also queue an e-mail. */
    private final boolean email;
    /** Whether the person's e-mail settings can mute that e-mail. */
    @Builder.Default
    private final EmailCategory emailCategory = EmailCategory.ACCOUNT;
    @Builder.Default
    private final Collection<String> exclude = List.of();
    /** Who also gets it on their phone: everyone notified (default), nobody, or only some of them. */
    @Builder.Default
    private final PushTargets push = PushTargets.ALL;

    public record PushTargets(boolean all, Collection<String> only) {
        public static final PushTargets ALL = new PushTargets(true, List.of());
        public static final PushTargets NONE = new PushTargets(false, List.of());

        public static PushTargets only(Collection<String> ids) {
            return new PushTargets(false, ids == null ? List.of() : ids);
        }
    }
}
