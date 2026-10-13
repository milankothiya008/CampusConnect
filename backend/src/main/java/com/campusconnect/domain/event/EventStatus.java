package com.campusconnect.domain.event;

import java.util.List;

/** constants/Statuses.js EVENT_STATUS, with the status groups defined next to it. */
public enum EventStatus {
    DRAFT,
    PENDING_APPROVAL,
    NEEDS_CHANGES,
    APPROVED,
    PUBLISHED,
    REJECTED,
    CANCELLED,
    COMPLETED;

    /**
     * EVENT_STATUSES_HOLDING_VENUE: a submitted event reserves its venue while it waits for the mentor, so two clubs
     * cannot request the same slot; drafts and events sent back for changes do not hold a venue.
     */
    public static final List<EventStatus> HOLDING_VENUE = List.of(PENDING_APPROVAL, APPROVED, PUBLISHED);

    /** PUBLIC_EVENT_STATUSES. */
    public static final List<EventStatus> PUBLIC = List.of(PUBLISHED, COMPLETED);

    /** EDITABLE_EVENT_STATUSES. */
    public static final List<EventStatus> EDITABLE = List.of(DRAFT, NEEDS_CHANGES);
}
