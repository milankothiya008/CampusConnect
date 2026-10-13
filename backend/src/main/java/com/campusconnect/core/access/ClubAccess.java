package com.campusconnect.core.access;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Global-role checks and club-level authorisation — services/AuthorizationService.js plus the membership
 * helpers every feature uses (approved members, members holding a permission, …).
 */
@Service
@RequiredArgsConstructor
public class ClubAccess {

    private final MongoOperations mongo;

    // ------------------------------------------------------------------ global roles

    public static boolean isAdmin(AuthUser user) {
        return user != null && user.globalRole() == GlobalRole.ADMIN;
    }

    public static boolean isFaculty(AuthUser user) {
        return user != null && user.globalRole() == GlobalRole.FACULTY;
    }

    public static boolean isStudent(AuthUser user) {
        return user != null && user.globalRole() == GlobalRole.STUDENT && user.accountType() == AccountType.STUDENT;
    }

    public static void assertAdmin(AuthUser user) {
        if (!isAdmin(user)) {
            throw AppException.forbidden("University admin access required");
        }
    }

    public static void assertFaculty(AuthUser user) {
        if (!isFaculty(user)) {
            throw AppException.forbidden("Faculty access required");
        }
    }

    public static void assertStudent(AuthUser user) {
        assertStudent(user, "Only students can perform this action");
    }

    public static void assertStudent(AuthUser user, String message) {
        if (!isStudent(user)) {
            throw AppException.forbidden(message);
        }
    }

    public static void assertVerified(AuthUser user) {
        if (user == null || !user.emailVerified()) {
            throw AppException.forbidden("Email verification required", ErrorCode.UNVERIFIED_EMAIL);
        }
    }

    // ------------------------------------------------------------------ clubs

    public Club loadClub(String clubId) {
        Club club = clubId != null && ObjectId.isValid(clubId) ? mongo.findById(clubId, Club.class) : null;
        if (club == null) {
            throw AppException.notFound("Club not found");
        }
        return club;
    }

    public static boolean isClubMentor(AuthUser user, Club club) {
        return user != null && club != null && club.getMentor() != null && club.getMentor().equals(user.id());
    }

    /** The user's APPROVED membership of the club, or null. */
    public ClubMembership membership(String userId, String clubId) {
        if (userId == null || clubId == null) {
            return null;
        }
        return mongo.findOne(
                Query.query(Criteria.where("club").is(clubId).and("user").is(userId).and("status").is(MembershipStatus.APPROVED)), ClubMembership.class);
    }

    public ClubContext context(AuthUser user, String clubId) {
        return context(user, loadClub(clubId));
    }

    public ClubContext context(AuthUser user, Club club) {
        ClubMembership membership = user != null ? membership(user.id(), club.getId()) : null;
        String role = membership != null ? membership.getRole() : null;
        return new ClubContext(
                club,
                membership,
                role,
                role != null ? ClubRoles.roleName(club, role) : null,
                isAdmin(user),
                isClubMentor(user, club),
                membership != null,
                ClubRoles.permissionsFor(club, role));
    }

    public ClubContext assertPermission(AuthUser user, String clubId, ClubPermission permission) {
        return assertPermission(user, clubId, permission, "You do not have permission for this club action");
    }

    public ClubContext assertPermission(AuthUser user, String clubId, ClubPermission permission, String message) {
        ClubContext context = context(user, clubId);
        if (!context.has(permission)) {
            throw AppException.forbidden(message);
        }
        return context;
    }

    public ClubContext assertPermission(AuthUser user, Club club, ClubPermission permission, String message) {
        ClubContext context = context(user, club);
        if (!context.has(permission)) {
            throw AppException.forbidden(message);
        }
        return context;
    }

    public ClubContext assertMentor(AuthUser user, String clubId) {
        return assertMentor(user, clubId, "Only the club's faculty mentor can perform this action");
    }

    public ClubContext assertMentor(AuthUser user, String clubId, String message) {
        ClubContext context = context(user, clubId);
        if (!context.isMentor()) {
            throw AppException.forbidden(message);
        }
        return context;
    }

    /** Mentors oversee their club, so they can read (not manage) participant and member data. */
    public ClubContext assertCanViewParticipants(AuthUser user, String clubId, ClubPermission permission) {
        ClubContext context = context(user, clubId);
        if (!context.isMentor() && !context.has(permission)) {
            throw AppException.forbidden("You do not have access to participant information for this club");
        }
        return context;
    }

    /** Who may open member lists (with mobile numbers): the admin, any club's mentor and any approved member of any club. */
    public boolean canSeeMemberDirectory(AuthUser user) {
        if (user == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (isFaculty(user) && mongo.exists(Query.query(Criteria.where("mentor").is(user.id())), Club.class)) {
            return true;
        }
        return isApprovedMemberAnywhere(user.id());
    }

    /** Who may see faculty mobile numbers: members of any club, every faculty member and the admin. */
    public boolean canSeeFacultyContacts(AuthUser user) {
        if (user == null) {
            return false;
        }
        if (isAdmin(user) || isFaculty(user)) {
            return true;
        }
        return isApprovedMemberAnywhere(user.id());
    }

    public boolean isApprovedMemberAnywhere(String userId) {
        return mongo.exists(Query.query(Criteria.where("user").is(userId).and("status").is(MembershipStatus.APPROVED)), ClubMembership.class);
    }

    // ------------------------------------------------------------------ membership helpers (MembershipService)

    /** Ids of the club's approved members. */
    public List<String> approvedMemberIds(String clubId) {
        Query query = Query.query(Criteria.where("club").is(clubId).and("status").is(MembershipStatus.APPROVED));
        query.fields().include("user");
        return mongo.find(query, ClubMembership.class).stream().map(ClubMembership::getUser).toList();
    }

    /** Approved members whose club role grants the permission (e.g. who should hear about join requests). */
    public List<String> usersWithPermission(String clubId, ClubPermission permission) {
        Query clubQuery = Query.query(Criteria.where("_id").is(clubId));
        clubQuery.fields().include("roles");
        Club club = mongo.findOne(clubQuery, Club.class);
        List<String> roleKeys = ClubRoles.keysWith(club, permission);
        Query query = Query.query(Criteria.where("club").is(clubId).and("status").is(MembershipStatus.APPROVED).and("role").in(roleKeys));
        query.fields().include("user");
        return mongo.find(query, ClubMembership.class).stream().map(ClubMembership::getUser).toList();
    }

    /** Clubs where the user holds any of the given authorities (via their role in that club). */
    public List<String> clubIdsWithAnyPermission(String userId, Collection<ClubPermission> permissions) {
        Query query = Query.query(Criteria.where("user").is(userId).and("status").is(MembershipStatus.APPROVED));
        query.fields().include("club").include("role");
        List<ClubMembership> memberships = mongo.find(query, ClubMembership.class);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Query clubs = Query.query(Criteria.where("_id").in(memberships.stream().map(ClubMembership::getClub).toList()));
        clubs.fields().include("roles");
        Map<String, Club> byId = mongo.find(clubs, Club.class).stream().collect(Collectors.toMap(Club::getId, Function.identity()));
        return memberships.stream()
                .filter(m -> byId.containsKey(m.getClub()))
                .filter(m -> permissions.stream().anyMatch(p -> ClubRoles.has(byId.get(m.getClub()), m.getRole(), p)))
                .map(ClubMembership::getClub)
                .toList();
    }
}
