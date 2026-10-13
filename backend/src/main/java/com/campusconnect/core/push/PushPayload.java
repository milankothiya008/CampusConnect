package com.campusconnect.core.push;

/** What the service worker shows (sw.js reads these names). */
public record PushPayload(String title, String body, String url, String tag, String icon, String kind, String conversationId) {

    public static PushPayload of(String title, String body, String url, String tag) {
        return new PushPayload(title, body, url, tag, null, null, null);
    }

    /** Body cut to 240 characters, "/activity" when there is no link (PushService.pushToUsers). */
    public PushPayload normalized() {
        String text = body == null ? "" : body;
        return new PushPayload(title, text.length() > 240 ? text.substring(0, 240) : text, url == null || url.isBlank() ? "/activity" : url, tag, icon, kind, conversationId);
    }
}
