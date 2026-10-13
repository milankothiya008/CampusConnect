package com.campusconnect.core.ratelimit;

/** Extra limits on top of the global per-user/per-IP API limit (middleware/RateLimiter.js). */
public enum RateLimitPolicy {
    /** Sign-in, sign-up, one-time codes, refresh, unsubscribe — counted per IP address. */
    AUTH,
    /** Uploads — counted per signed-in user. */
    UPLOAD
}
