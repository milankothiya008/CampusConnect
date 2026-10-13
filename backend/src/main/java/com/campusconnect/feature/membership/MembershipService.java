package com.campusconnect.feature.membership;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.access.DepartmentScope;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.club.ClubCaches;
import com.campusconnect.feature.club.DocumentViews;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Club members (services/MembershipService.js): adding students directly, roles, removal and leaving. Students
 * normally join through recruitment drives; leaving or being removed closes the club chat for them at once
 * ({@link ClubMembershipChanged}).
 */
@Slf4j
@Service
public class MembershipService {

    static final List<String> USER_FIELDS = List.of("name", "email", "departmentCode", "batchCode");

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final Refs refs;
    private final DocumentViews views;
    private final ClubCaches caches;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    public MembershipService(MongoOperations mongo, ClubAccess access, Refs refs, DocumentViews views, ClubCaches caches, AuditService audit,
            NotificationService notifications, ApplicationEventPublisher events) {
        this.mongo = mongo;
        this.access = access;
        this.refs = refs;
        this.views = views;
        this.caches = caches;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
    }

    private static String link(Club club) {
        return "/clubs/" + club.getId();
    }

    private void auditMembership(AuditAction action, AuthUser actor, Club club, ClubMembership membership, String fromState, String toState, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", club.getId());
        metadata.put("userId", membership.getUser());
        metadata.putAll(extra);
        audit.record(action, actor.id(), "ClubMembership", membership.getId(), fromState, toState, null, metadata);
    }

    /** Tells the chat feature (it closes the club chat for someone who left); a listener failure never undoes the change. */
    private void membershipChanged(String clubId, String userId) {
        try {
            events.publishEvent(new ClubMembershipChanged(clubId, userId));
        } catch (RuntimeException e) {
            log.error("Club membership listeners failed for club {}: {}", clubId, e.getMessage());
        }
    }

    private ClubMembership findApprovedMember(String clubId, String userId) {
        ClubMembership membership = access.membership(userId, clubId);
        if (membership == null) {
            throw AppException.notFound("This user is not a member of the club");
        }
        return membership;
    }

    /** The user's membership with its user populated, as the member routes return it. */
    private Map<String, Object> withUser(ClubMembership membership) {
        Map<String, Object> view = views.of(membership);
        view.put("user", DocumentViews.pick(refs.user(membership.getUser(), USER_FIELDS.toArray(String[]::new)), USER_FIELDS));
        return view;
    }

    // ---------------------------------------------------------------- leaving and removal

    public void leaveClub(AuthUser actor, String clubId) {
        Club club = access.loadClub(clubId);
        ClubMembership membership = access.membership(actor.id(), club.getId());
        if (membership == null) {
            throw AppException.notFound("You are not a member of this club");
        }
        if (ClubRoles.PRESIDENT.equals(membership.getRole())) {
            throw AppException.conflict("Hand over the presidency to another member before you leave", ErrorCode.INVALID_STATE);
        }
        // Never delete a membership that became the presidency in the meantime.
        long deleted = mongo.remove(Query.query(Criteria.where("_id").is(membership.getId()).and("role").ne(ClubRoles.PRESIDENT)), ClubMembership.class).getDeletedCount();
        if (deleted == 0) {
            if (access.membership(actor.id(), club.getId()) == null) {
                throw AppException.notFound("You are not a member of this club");
            }
            throw AppException.conflict("Hand over the presidency to another member before you leave", ErrorCode.INVALID_STATE);
        }
        caches.directoryChanged();
        auditMembership(AuditAction.MEMBER_LEFT, actor, club, membership, MembershipStatus.APPROVED.name(), null, Map.of());
        membershipChanged(club.getId(), actor.id());
    }

    public void removeMember(AuthUser actor, String clubId, String userId) {
        ClubContext context = access.assertPermission(actor, clubId, ClubPermission.MANAGE_MEMBERS, "You cannot manage members of this club");
        Club club = context.club();
        if (userId.equals(actor.id())) {
            throw AppException.badRequest("Use 'leave club' to remove yourself", ErrorCode.VALIDATION_ERROR);
        }
        ClubMembership membership = findApprovedMember(club.getId(), userId);
        for (int attempt = 0; ; attempt++) {
            if (ClubRoles.PRESIDENT.equals(membership.getRole())) {
                throw AppException.forbidden("The president cannot be removed");
            }
            // Removing an office-holder is reserved for whoever can assign roles (the president).
            if (!ClubRoles.MEMBER.equals(membership.getRole()) && !context.has(ClubPermission.ASSIGN_ROLES)) {
                throw AppException.forbidden("Only the president can remove club officers");
            }
            // The rules above were checked against this role: remove only if it is still the member's role.
            long deleted = mongo.remove(Query.query(Criteria.where("_id").is(membership.getId()).and("role").is(membership.getRole())), ClubMembership.class).getDeletedCount();
            if (deleted == 1) {
                break;
            }
            if (attempt > 0) {
                throw AppException.conflict("This member's role changed at the same moment. Please try again.", ErrorCode.CONFLICT);
            }
            membership = findApprovedMember(club.getId(), userId);
        }
        caches.directoryChanged();
        auditMembership(AuditAction.MEMBER_REMOVED, actor, club, membership, membership.getRole(), null, Map.of());
        membershipChanged(club.getId(), userId);
    }

    // ---------------------------------------------------------------- roles

    public Map<String, Object> changeMemberRole(AuthUser actor, String clubId, String userId, String role) {
        ClubContext context = access.assertPermission(actor, clubId, ClubPermission.ASSIGN_ROLES, "Only the president can assign club roles");
        Club club = context.club();
        if (ClubRoles.PRESIDENT.equals(role)) {
            throw AppException.badRequest("The presidency is handed over by the president, not assigned", ErrorCode.VALIDATION_ERROR);
        }
        if (ClubRoles.findRole(club, role) == null) {
            throw AppException.badRequest("This club has no such role", ErrorCode.VALIDATION_ERROR);
        }
        if (userId.equals(actor.id())) {
            throw AppException.forbidden("You cannot change your own club role");
        }
        ClubMembership membership = findApprovedMember(club.getId(), userId);
        if (ClubRoles.PRESIDENT.equals(membership.getRole())) {
            throw AppException.conflict("The president's role changes only when the presidency is handed over", ErrorCode.INVALID_STATE);
        }
        // One vice-president per club.
        if (ClubRoles.VICE_PRESIDENT.equals(role)) {
            ClubMembership current = mongo.findOne(Query.query(Criteria.where("club").is(club.getId()).and("role").is(ClubRoles.VICE_PRESIDENT)
                    .and("status").is(MembershipStatus.APPROVED).and("user").ne(userId)), ClubMembership.class);
            if (current != null) {
                Map<String, Object> holder = refs.user(current.getUser(), "name");
                Object name = holder == null ? null : holder.get("name");
                throw AppException.conflict((name == null || String.valueOf(name).isEmpty() ? "Someone" : name) + " is already vice-president. Change their role first.", ErrorCode.CONFLICT);
            }
        }
        String from = membership.getRole();
        ClubMembership updated;
        try {
            updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(membership.getId()).and("status").is(MembershipStatus.APPROVED)),
                    new Update().set("role", role).set("updatedAt", Instant.now()), FindAndModifyOptions.options().returnNew(true), ClubMembership.class);
        } catch (DuplicateKeyException e) {
            throw AppException.conflict("The club already has a vice-president", ErrorCode.CONFLICT);
        }
        if (updated == null) {
            throw AppException.notFound("This user is not a member of the club");
        }
        caches.directoryChanged();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", club.getId());
        metadata.put("userId", userId);
        audit.record(AuditAction.MEMBER_ROLE_CHANGED, actor.id(), "ClubMembership", updated.getId(), from, role, null, metadata);
        notifications.notify(userId, Notice.builder()
                .type(NotificationType.CLUB_ROLE_CHANGED)
                .title("Your role in " + club.getName() + " changed")
                .message("You are now " + ClubRoles.roleName(club, role) + ".")
                .link(link(club))
                .build());
        Map<String, Object> view = withUser(updated);
        view.put("roleName", ClubRoles.roleName(club, role));
        return view;
    }

    // ---------------------------------------------------------------- adding

    /** Direct add (e.g. founders or students who joined offline); the student must be a verified student of the club's departments. */
    public Map<String, Object> addMember(AuthUser actor, String clubId, String userId) {
        ClubContext context = access.assertPermission(actor, clubId, ClubPermission.MANAGE_MEMBERS, "You cannot manage members of this club");
        Club club = context.club();
        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Members can only be added to an active club", ErrorCode.CLUB_NOT_ACTIVE);
        }
        User user = mongo.findById(userId, User.class);
        if (user == null || !user.isEmailVerified() || !user.isActive() || user.getAccountType() != AccountType.STUDENT || user.getGlobalRole() != GlobalRole.STUDENT) {
            throw AppException.badRequest("Only verified students can be added", ErrorCode.VALIDATION_ERROR);
        }
        DepartmentScope.assertStudentsMatch(List.of(new DepartmentScope.Person(user.getName(), user.getDepartmentCode())),
                new DepartmentScope.Scope(club.isAllDepartments(), club.getDepartmentCodes()), false, club.getName());
        ClubMembership existing = mongo.findOne(Query.query(Criteria.where("club").is(club.getId()).and("user").is(userId)), ClubMembership.class);
        if (existing != null && existing.getStatus() == MembershipStatus.APPROVED) {
            throw AppException.conflict("User is already a member", ErrorCode.CONFLICT);
        }
        Instant now = Instant.now();
        ClubMembership membership;
        try {
            // Upsert on "not yet approved": two adds at once leave one membership and one 409.
            membership = mongo.findAndModify(
                    Query.query(Criteria.where("club").is(club.getId()).and("user").is(userId).and("status").ne(MembershipStatus.APPROVED)),
                    new Update()
                            .set("status", MembershipStatus.APPROVED)
                            .set("role", ClubRoles.MEMBER)
                            .set("joinedAt", now)
                            .set("decidedBy", actor.id())
                            .set("decidedAt", now)
                            .set("updatedAt", now)
                            .setOnInsert("createdAt", now),
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    ClubMembership.class);
        } catch (DuplicateKeyException e) {
            throw AppException.conflict("User is already a member", ErrorCode.CONFLICT);
        }
        caches.directoryChanged();
        // The club group chat reads its (cached) member list: the new member is in it at once.
        events.publishEvent(new ClubMembershipChanged(club.getId(), userId));
        auditMembership(AuditAction.MEMBERSHIP_APPROVED, actor, club, membership, existing == null || existing.getStatus() == null ? null : existing.getStatus().name(),
                MembershipStatus.APPROVED.name(), Map.of("direct", true));
        notifications.notify(userId, Notice.builder()
                .type(NotificationType.MEMBERSHIP_APPROVED)
                .title("You were added to " + club.getName())
                .message(actor.name() + " added you as a member.")
                .link(link(club))
                .build());
        return withUser(membership);
    }

    // ---------------------------------------------------------------- lists

    /** A user's approved memberships with their clubs, newest first (the user themself or the admin). */
    public List<Map<String, Object>> getUserClubs(AuthUser actor, String userId) {
        if (!actor.id().equals(userId) && !ClubAccess.isAdmin(actor)) {
            throw AppException.forbidden("You cannot view another user's clubs");
        }
        List<ClubMembership> memberships = mongo.find(Query.query(Criteria.where("user").is(userId).and("status").is(MembershipStatus.APPROVED))
                .with(Sort.by(Sort.Direction.DESC, "joinedAt")), ClubMembership.class);
        Map<String, Map<String, Object>> clubs = refs.clubs(memberships.stream().map(ClubMembership::getClub).toList(), "name", "category", "logo", "status");
        return memberships.stream().map(membership -> {
            Map<String, Object> view = views.of(membership);
            view.put("club", DocumentViews.row(clubs, membership.getClub()));
            return view;
        }).toList();
    }
}
