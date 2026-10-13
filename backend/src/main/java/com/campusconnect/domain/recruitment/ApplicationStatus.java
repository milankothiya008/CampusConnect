package com.campusconnect.domain.recruitment;

/**
 * constants/Statuses.js APPLICATION_STATUS. One application per student per role. Selected students get an OFFER
 * and accept at most one; the others close automatically. RESERVE candidates can be offered a seat that is declined
 * or expires.
 */
public enum ApplicationStatus {
    APPLIED,
    IN_ROUNDS,
    ELIMINATED,
    OFFERED,
    RESERVE,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    NOT_SELECTED,
    WITHDRAWN
}
