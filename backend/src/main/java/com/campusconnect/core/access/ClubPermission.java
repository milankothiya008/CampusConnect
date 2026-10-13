package com.campusconnect.core.access;

/** Club authorities granted by club roles (constants/Permissions.js). */
public enum ClubPermission {
    MANAGE_CLUB,
    MANAGE_MEMBERS,
    ASSIGN_ROLES,
    MANAGE_EVENTS,
    PUBLISH_EVENTS,
    VIEW_PARTICIPANTS,
    MANAGE_PARTICIPANTS,
    MANAGE_RESULTS,
    /** Publishing, withdrawing and correcting official results: the president only. */
    PUBLISH_RESULTS,
    POST_UPDATES,
    /** Opening and closing check-in at the door: the president only. */
    MANAGE_CHECK_IN,
    /** Scanning tickets and marking attendance while check-in is open: every officer. */
    MARK_ATTENDANCE,
    /** Approving photos and videos for an event's gallery: president and vice-president. */
    MODERATE_GALLERY,
    /** Running recruitment drives: the president only. */
    MANAGE_RECRUITMENT,
    /** Sending reminder e-mails about an event. Grantable. */
    SEND_REMINDERS,
    /** The club group chat: announce-only mode and pinned messages. Grantable. */
    MANAGE_CHAT,
    /** Holding anonymous elections for club roles. Grantable. */
    RUN_ELECTIONS
}
