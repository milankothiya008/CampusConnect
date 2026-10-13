package com.campusconnect.core.access;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import java.util.List;

/**
 * Everything needed to authorise one user against one club (AuthorizationService.getClubContext). Club
 * permissions come only from the user's own role in that club; the university admin has none here.
 */
public record ClubContext(
        Club club,
        ClubMembership membership,
        String role,
        String roleName,
        boolean isAdmin,
        boolean isMentor,
        boolean isMember,
        List<String> permissions) {

    public boolean has(ClubPermission permission) {
        return permissions.contains(permission.name());
    }
}
