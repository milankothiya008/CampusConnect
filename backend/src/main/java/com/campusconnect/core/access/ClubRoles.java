package com.campusconnect.core.access;

import static com.campusconnect.core.access.ClubPermission.ASSIGN_ROLES;
import static com.campusconnect.core.access.ClubPermission.MANAGE_CHECK_IN;
import static com.campusconnect.core.access.ClubPermission.MANAGE_CLUB;
import static com.campusconnect.core.access.ClubPermission.MANAGE_EVENTS;
import static com.campusconnect.core.access.ClubPermission.MANAGE_MEMBERS;
import static com.campusconnect.core.access.ClubPermission.MANAGE_PARTICIPANTS;
import static com.campusconnect.core.access.ClubPermission.MANAGE_RECRUITMENT;
import static com.campusconnect.core.access.ClubPermission.MANAGE_RESULTS;
import static com.campusconnect.core.access.ClubPermission.MARK_ATTENDANCE;
import static com.campusconnect.core.access.ClubPermission.MODERATE_GALLERY;
import static com.campusconnect.core.access.ClubPermission.POST_UPDATES;
import static com.campusconnect.core.access.ClubPermission.PUBLISH_EVENTS;
import static com.campusconnect.core.access.ClubPermission.PUBLISH_RESULTS;
import static com.campusconnect.core.access.ClubPermission.VIEW_PARTICIPANTS;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bson.types.ObjectId;

/**
 * Club roles are defined per club (Club.roles) — utils/ClubRoles.js. Three are built in and exist in every
 * club: PRESIDENT (every authority, exactly one, handed over rather than assigned), VICE_PRESIDENT (at most one;
 * the president chooses its authorities) and MEMBER (none). Everything else is a custom role. Authorities in
 * {@link #PRESIDENT_ONLY} can never be given to another role.
 */
public final class ClubRoles {

    public static final String PRESIDENT = "PRESIDENT";
    public static final String VICE_PRESIDENT = "VICE_PRESIDENT";
    public static final String MEMBER = "MEMBER";

    public static final List<ClubPermission> PRESIDENT_ONLY = List.of(MANAGE_CLUB, ASSIGN_ROLES, MANAGE_RECRUITMENT, PUBLISH_RESULTS, MANAGE_CHECK_IN);

    public static final List<ClubPermission> GRANTABLE = Arrays.stream(ClubPermission.values()).filter(p -> !PRESIDENT_ONLY.contains(p)).toList();

    private ClubRoles() {
    }

    /** Roles every new club starts with (keys kept from before roles were per-club). */
    public static List<ClubRole> defaultRoles() {
        List<ClubRole> roles = new ArrayList<>();
        roles.add(new ClubRole(PRESIDENT, "President", "Leads the club. Holds every authority.", names(ClubPermission.values()), true, 0));
        roles.add(new ClubRole(VICE_PRESIDENT, "Vice-president", "Second in command.",
                names(MANAGE_MEMBERS, MANAGE_EVENTS, PUBLISH_EVENTS, VIEW_PARTICIPANTS, MANAGE_PARTICIPANTS, MANAGE_RESULTS, POST_UPDATES, MARK_ATTENDANCE, MODERATE_GALLERY), true, 1));
        roles.add(new ClubRole("EVENT_COORDINATOR", "Event coordinator", "Plans events and runs them on the day.",
                names(MANAGE_EVENTS, VIEW_PARTICIPANTS, MANAGE_PARTICIPANTS, MANAGE_RESULTS, MARK_ATTENDANCE), false, 2));
        roles.add(new ClubRole("MARKETING_COORDINATOR", "Marketing coordinator", "Announcements, posters and social media.", names(POST_UPDATES, MARK_ATTENDANCE), false, 3));
        roles.add(new ClubRole("TECHNICAL_COORDINATOR", "Technical coordinator", "Technical side of events.", names(MANAGE_EVENTS, VIEW_PARTICIPANTS, MARK_ATTENDANCE), false, 4));
        roles.add(new ClubRole("TREASURER", "Treasurer", "Budgets and registrations.", names(VIEW_PARTICIPANTS, MARK_ATTENDANCE), false, 5));
        roles.add(new ClubRole(MEMBER, "Member", "Part of the club.", new ArrayList<>(), true, 99));
        return roles;
    }

    /** The club's roles, falling back to the defaults for clubs stored before roles were per-club. */
    public static List<ClubRole> rolesOf(Club club) {
        return club != null && club.getRoles() != null && !club.getRoles().isEmpty() ? club.getRoles() : defaultRoles();
    }

    public static ClubRole findRole(Club club, String key) {
        if (key == null) {
            return null;
        }
        return rolesOf(club).stream().filter(role -> key.equals(role.getKey())).findFirst().orElse(null);
    }

    /** Authorities of a role in this club. The president always holds every authority. */
    public static List<String> permissionsFor(Club club, String key) {
        if (key == null) {
            return List.of();
        }
        if (PRESIDENT.equals(key)) {
            return names(ClubPermission.values());
        }
        ClubRole role = findRole(club, key);
        if (role == null || role.getPermissions() == null) {
            return List.of();
        }
        List<String> grantable = GRANTABLE.stream().map(Enum::name).toList();
        return role.getPermissions().stream().filter(grantable::contains).toList();
    }

    public static boolean has(Club club, String key, ClubPermission permission) {
        return permissionsFor(club, key).contains(permission.name());
    }

    public static String roleName(Club club, String key) {
        ClubRole role = findRole(club, key);
        if (role != null) {
            return role.getName();
        }
        if (key == null) {
            return null;
        }
        String spaced = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Keys of the roles in this club that hold a permission (always including the president). */
    public static List<String> keysWith(Club club, ClubPermission permission) {
        return rolesOf(club).stream().filter(role -> permissionsFor(club, role.getKey()).contains(permission.name())).map(ClubRole::getKey).toList();
    }

    public static List<ClubRole> sortedRoles(Club club) {
        return rolesOf(club).stream().sorted(Comparator.comparingInt(ClubRole::getOrder)).toList();
    }

    /** Order of a membership in member lists: president, vice-president, custom roles, members. */
    public static int roleRank(Club club, String key) {
        List<ClubRole> sorted = sortedRoles(club);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(key)) {
                return i;
            }
        }
        return 50;
    }

    public static String newRoleKey() {
        return "R_" + new ObjectId().toHexString();
    }

    private static List<String> names(ClubPermission... permissions) {
        return new ArrayList<>(Arrays.stream(permissions).map(Enum::name).toList());
    }
}
