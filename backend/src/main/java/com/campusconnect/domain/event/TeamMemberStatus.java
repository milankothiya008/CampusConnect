package com.campusconnect.domain.event;

/** constants/Statuses.js TEAM_MEMBER_STATUS: the leader who registered, and the students they invited. */
public enum TeamMemberStatus {
    LEADER,
    INVITED,
    ACCEPTED,
    DECLINED,
    CANCELLED,
    LEFT,
    REMOVED
}
