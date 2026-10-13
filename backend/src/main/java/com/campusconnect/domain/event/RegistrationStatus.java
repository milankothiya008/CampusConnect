package com.campusconnect.domain.event;

/** constants/Statuses.js REGISTRATION_STATUS. */
public enum RegistrationStatus {
    REGISTERED,
    /** In the queue for a full event; promoted to REGISTERED in order when a seat frees up. */
    WAITLISTED,
    CANCELLED
}
