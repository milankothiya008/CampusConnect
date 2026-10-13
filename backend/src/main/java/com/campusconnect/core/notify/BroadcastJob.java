package com.campusconnect.core.notify;

import com.campusconnect.domain.notification.NotificationType;
import java.util.List;

/** The queued form of a {@link Broadcast}. */
public record BroadcastJob(NotificationType type, String title, String message, String link, List<String> exclude, List<String> pushTo) {
}
