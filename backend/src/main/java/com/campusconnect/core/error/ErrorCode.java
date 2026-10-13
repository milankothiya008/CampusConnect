package com.campusconnect.core.error;

/** Machine-readable error codes returned as {@code errorCode} (constants/ErrorCodes.js). */
public enum ErrorCode {
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID_EMAIL,
    UNVERIFIED_EMAIL,
    INVALID_CREDENTIALS,
    ACCOUNT_DISABLED,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    OTP_INVALID,
    OTP_EXPIRED,
    REFRESH_REVOKED,
    VENUE_CONFLICT,
    EVENT_FULL,
    REGISTRATION_CLOSED,
    REGISTRATION_NOT_OPEN,
    DUPLICATE_REGISTRATION,
    NOT_ELIGIBLE,
    INVALID_STATE,
    CLUB_NOT_ACTIVE,
    UPLOAD_ERROR,
    RATE_LIMITED,
    PHONE_REQUIRED,
    /** Registering for an event at the same time as one the student already holds a place for. */
    SCHEDULE_CONFLICT
}
