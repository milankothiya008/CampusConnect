package com.campusconnect.core.security;

/** How a route authenticates (middleware/Auth.js protect / optionalAuth / requireVerified). */
public enum AccessLevel {
    /** No authentication; any token is ignored. */
    PUBLIC,
    /** Anonymous allowed; a sent token must be valid. */
    OPTIONAL,
    /** Signed in; e-mail verification not required. */
    AUTHENTICATED,
    /** Signed in with a verified e-mail (the default). */
    VERIFIED
}
