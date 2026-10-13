package com.campusconnect.core.push;

import java.util.List;

/** One notification for every device of these users. */
public record PushJob(List<String> userIds, PushPayload payload) {
}
