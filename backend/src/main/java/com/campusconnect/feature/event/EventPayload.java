package com.campusconnect.feature.event;

import java.util.Map;

/**
 * The JSON body of a create or edit, kept as sent. Editing depends on which fields were sent at all
 * ({@code payload[field] !== undefined} in EventService): a field sent as {@code null} counts as sent, a missing one
 * leaves the event's value alone — a record could not tell the two apart.
 */
public record EventPayload(Map<String, Object> raw) {

    public EventPayload {
        raw = raw == null ? Map.of() : raw;
    }

    public boolean has(String field) {
        return raw.containsKey(field);
    }

    public Object get(String field) {
        return raw.get(field);
    }

    /** {@code payload[field] ?? fallback}. */
    public Object getOr(String field, Object fallback) {
        Object value = raw.get(field);
        return value != null ? value : fallback;
    }
}
