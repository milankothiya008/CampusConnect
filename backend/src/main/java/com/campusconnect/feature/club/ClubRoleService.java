package com.campusconnect.feature.club;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A club's own roles (services/ClubRoleService.js): the president creates roles with a name and the authorities they
 * grant, edits and deletes them; president, vice-president and member are built in. The presidency itself is handed
 * over by the president to another member.
 *
 * <p>The roles live in an array on the club, so every change is written conditionally on the club's
 * {@code updatedAt} as read (optimistic locking) and retried on a clash — two quick edits never lose one another.
 */
@Service
public class ClubRoleService {

    static final int MAX_ROLES = 20;
    static final List<RecruitmentStatus> LIVE_DRIVES = List.of(RecruitmentStatus.DRAFT, RecruitmentStatus.PENDING_APPROVAL, RecruitmentStatus.NEEDS_CHANGES,
            RecruitmentStatus.APPROVED, RecruitmentStatus.PUBLISHED);
    private static final ClubPermission ASSIGN = ClubPermission.ASSIGN_ROLES;
    private static final String MANAGE_MESSAGE = "Only the president can manage club roles";
    private static final int ATTEMPTS = 3;

    /** One role as the roles page shows it. */
    public record RoleView(String key, String name, String description, List<String> permissions, boolean system, boolean editable, boolean renamable,
            boolean deletable, boolean unique, int memberCount, Map<String, Object> holder) {
    }

    public record RoleList(boolean canManage, List<String> grantable, List<String> presidentOnly, List<RoleView> roles) {
    }

    public record Handover(Map<String, Object> president, String previousRoleName) {
    }

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final Refs refs;
    private final ClubCaches caches;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;

    public ClubRoleService(MongoOperations mongo, ClubAccess access, Refs refs, ClubCaches caches, AuditService audit, NotificationService notifications,
            TransactionTemplate transactions) {
        this.mongo = mongo;
        this.access = access;
        this.refs = refs;
        this.caches = caches;
        this.audit = audit;
        this.notifications = notifications;
        this.transactions = transactions;
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    private static AppException conflict(String message) {
        return AppException.conflict(message, ErrorCode.CONFLICT);
    }

    static List<String> names(List<ClubPermission> permissions) {
        return permissions.stream().map(Enum::name).toList();
    }

    // ---------------------------------------------------------------- reading

    /** Each role with how many members hold it (and who holds the one-person roles). */
    public RoleList listRoles(AuthUser actor, String clubId) {
        ClubContext context = access.context(actor, clubId);
        if (!context.isMember() && !context.isMentor()) {
            throw AppException.forbidden("Only club members and the club's mentor can see its roles");
        }
        Club club = context.club();
        Query query = Query.query(Criteria.where("club").is(club.getId()).and("status").is(MembershipStatus.APPROVED));
        query.fields().include("role", "user");
        List<ClubMembership> memberships = mongo.find(query, ClubMembership.class);
        Map<String, ClubMembership> firstHolder = new LinkedHashMap<>();
        Map<String, Integer> holders = new LinkedHashMap<>();
        for (ClubMembership membership : memberships) {
            holders.merge(membership.getRole(), 1, Integer::sum);
            firstHolder.putIfAbsent(membership.getRole(), membership);
        }
        List<String> uniqueHolders = Stream.of(ClubRoles.PRESIDENT, ClubRoles.VICE_PRESIDENT).map(firstHolder::get).filter(java.util.Objects::nonNull).map(ClubMembership::getUser).toList();
        Map<String, Map<String, Object>> users = refs.users(uniqueHolders, "name", "avatar");

        List<String> everything = new ArrayList<>(names(ClubRoles.GRANTABLE));
        everything.addAll(names(ClubRoles.PRESIDENT_ONLY));
        List<RoleView> roles = ClubRoles.sortedRoles(club).stream().map(role -> {
            boolean unique = ClubRoles.PRESIDENT.equals(role.getKey()) || ClubRoles.VICE_PRESIDENT.equals(role.getKey());
            Map<String, Object> holder = null;
            if (unique && firstHolder.containsKey(role.getKey())) {
                holder = new LinkedHashMap<>();
                Map<String, Object> user = users.get(firstHolder.get(role.getKey()).getUser());
                if (user != null) {
                    holder.put("_id", user.get("_id"));
                    holder.put("name", user.get("name"));
                }
            }
            return new RoleView(
                    role.getKey(),
                    role.getName(),
                    role.getDescription() == null ? "" : role.getDescription(),
                    ClubRoles.PRESIDENT.equals(role.getKey()) ? everything : role.getPermissions(),
                    role.isSystem(),
                    !ClubRoles.PRESIDENT.equals(role.getKey()) && !ClubRoles.MEMBER.equals(role.getKey()),
                    !role.isSystem(),
                    !role.isSystem(),
                    unique,
                    holders.getOrDefault(role.getKey(), 0),
                    holder);
        }).toList();
        return new RoleList(context.has(ASSIGN), names(ClubRoles.GRANTABLE), names(ClubRoles.PRESIDENT_ONLY), roles);
    }

    // ---------------------------------------------------------------- input

    private static List<String> readPermissions(Object permissions) {
        List<String> list = permissions instanceof List<?> values ? new ArrayList<>(new LinkedHashSet<>(values.stream().map(JsValues::string).toList())) : new ArrayList<>();
        List<String> grantable = names(ClubRoles.GRANTABLE);
        List<String> refused = list.stream().filter(permission -> !grantable.contains(permission)).toList();
        if (refused.stream().anyMatch(names(ClubRoles.PRESIDENT_ONLY)::contains)) {
            throw invalid("Club settings, roles, recruitment, publishing results and starting check-in stay with the president");
        }
        if (!refused.isEmpty()) {
            throw invalid("Unknown authority");
        }
        return list;
    }

    private static String readName(Club club, Object name, String exceptKey) {
        String clean = JsValues.collapseSpaces(JsValues.trim(JsValues.truthy(name) ? JsValues.string(name) : ""));
        if (clean.length() < 2 || clean.length() > 40) {
            throw invalid("Role names are 2 to 40 characters");
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (ClubRoles.rolesOf(club).stream().anyMatch(role -> !role.getKey().equals(exceptKey) && role.getName().toLowerCase(Locale.ROOT).equals(lower))) {
            throw conflict("There is already a \"" + clean + "\" role");
        }
        return clean;
    }

    private static String readDescription(Object description) {
        String clean = JsValues.trim(JsValues.truthy(description) ? JsValues.string(description) : "");
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    /** The club's roles to change: its own copy, or the defaults for clubs stored before roles were per-club. */
    private static List<ClubRole> ownRoles(Club club) {
        List<ClubRole> roles = new ArrayList<>();
        for (ClubRole role : ClubRoles.rolesOf(club)) {
            List<String> permissions = role.getPermissions() == null ? new ArrayList<>() : new ArrayList<>(role.getPermissions());
            roles.add(new ClubRole(role.getKey(), role.getName(), role.getDescription(), permissions, role.isSystem(), role.getOrder()));
        }
        return roles;
    }

    private static ClubRole find(List<ClubRole> roles, String key) {
        ClubRole role = roles.stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(null);
        if (role == null) {
            throw AppException.notFound("Role not found");
        }
        return role;
    }

    /** Writes the roles if nobody changed the club since it was read. */
    private boolean saveRoles(Club club, List<ClubRole> roles) {
        Criteria unchanged = Criteria.where("_id").is(club.getId()).and("updatedAt").is(club.getUpdatedAt());
        boolean saved = mongo.updateFirst(Query.query(unchanged), new Update().set("roles", roles).set("updatedAt", Instant.now()), Club.class).getModifiedCount() == 1;
        if (saved) {
            caches.clubChanged();
        }
        return saved;
    }

    private <T> T withRetries(Function<Integer, T> attempt) {
        for (int i = 0; i < ATTEMPTS; i++) {
            T result = attempt.apply(i);
            if (result != null) {
                return result;
            }
        }
        throw AppException.conflict("The club's roles were changed at the same moment. Please try again.", ErrorCode.CONFLICT);
    }

    private void auditRole(AuditAction action, AuthUser actor, Club club, ClubRole role, String extraKey, Object extraValue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("roleKey", role.getKey());
        metadata.put("roleName", role.getName());
        metadata.put(extraKey, extraValue);
        audit.record(action, actor.id(), "Club", club.getId(), metadata);
    }

    // ---------------------------------------------------------------- changes

    public RoleList createRole(AuthUser actor, String clubId, Map<String, Object> body) {
        Club saved = withRetries(attempt -> {
            Club club = access.assertPermission(actor, clubId, ASSIGN, MANAGE_MESSAGE).club();
            List<ClubRole> roles = ownRoles(club);
            if (roles.size() >= MAX_ROLES) {
                throw conflict("A club can have up to " + MAX_ROLES + " roles");
            }
            int order = roles.stream().filter(item -> !ClubRoles.MEMBER.equals(item.getKey())).mapToInt(ClubRole::getOrder).max().orElse(0) + 1;
            ClubRole role = new ClubRole(ClubRoles.newRoleKey(), readName(club, body.get("name"), null), readDescription(body.get("description")),
                    readPermissions(body.get("permissions")), false, order);
            roles.add(role);
            if (!saveRoles(club, roles)) {
                return null;
            }
            auditRole(AuditAction.CLUB_ROLE_CREATED, actor, club, role, "permissions", role.getPermissions());
            return club;
        });
        return listRoles(actor, saved.getId());
    }

    public RoleList updateRole(AuthUser actor, String clubId, String key, Map<String, Object> body) {
        Club saved = withRetries(attempt -> {
            Club club = access.assertPermission(actor, clubId, ASSIGN, MANAGE_MESSAGE).club();
            List<ClubRole> roles = ownRoles(club);
            ClubRole role = find(roles, key);
            if (ClubRoles.PRESIDENT.equals(key) || ClubRoles.MEMBER.equals(key)) {
                throw conflict("The " + role.getName().toLowerCase(Locale.ROOT) + " role can't be changed");
            }
            if (body.containsKey("name") && !role.isSystem()) {
                role.setName(readName(club, body.get("name"), key));
            }
            if (body.containsKey("description")) {
                role.setDescription(readDescription(body.get("description")));
            }
            if (body.containsKey("permissions")) {
                role.setPermissions(readPermissions(body.get("permissions")));
            }
            if (!saveRoles(club, roles)) {
                return null;
            }
            auditRole(AuditAction.CLUB_ROLE_UPDATED, actor, club, role, "permissions", role.getPermissions());
            return club;
        });
        return listRoles(actor, saved.getId());
    }

    /** Deletes a custom role; its holders become members. Not while a recruitment drive recruits for it. */
    public RoleList deleteRole(AuthUser actor, String clubId, String key) {
        Club saved = withRetries(attempt -> {
            Club club = access.assertPermission(actor, clubId, ASSIGN, MANAGE_MESSAGE).club();
            List<ClubRole> roles = ownRoles(club);
            ClubRole role = find(roles, key);
            if (role.isSystem()) {
                throw conflict("The " + role.getName().toLowerCase(Locale.ROOT) + " role is built in and can't be deleted");
            }
            Query driveQuery = Query.query(Criteria.where("club").is(club.getId()).and("status").in(LIVE_DRIVES).and("positions.role").is(key));
            driveQuery.fields().include("title");
            RecruitmentDrive drive = mongo.findOne(driveQuery, RecruitmentDrive.class);
            if (drive != null) {
                throw conflict("\"" + drive.getTitle() + "\" is recruiting for this role. Finish or cancel that drive first.");
            }
            Query holderQuery = Query.query(Criteria.where("club").is(club.getId()).and("role").is(key).and("status").is(MembershipStatus.APPROVED));
            holderQuery.fields().include("user");
            List<String> holders = mongo.find(holderQuery, ClubMembership.class).stream().map(ClubMembership::getUser).toList();
            mongo.updateMulti(Query.query(Criteria.where("club").is(club.getId()).and("role").is(key)),
                    new Update().set("role", ClubRoles.MEMBER).set("updatedAt", Instant.now()), ClubMembership.class);
            caches.directoryChanged();
            roles.removeIf(item -> item.getKey().equals(key));
            if (!saveRoles(club, roles)) {
                return null;
            }
            auditRole(AuditAction.CLUB_ROLE_DELETED, actor, club, role, "membersMoved", holders.size());
            if (!holders.isEmpty()) {
                notifications.notify(holders, Notice.builder()
                        .type(NotificationType.CLUB_ROLE_CHANGED)
                        .title("Your role in " + club.getName() + " changed")
                        .message("The \"" + role.getName() + "\" role was removed, so you're now a member.")
                        .link(ClubService.link(club))
                        .build());
            }
            return club;
        });
        return listRoles(actor, saved.getId());
    }

    /**
     * The president hands the presidency to another member, effective immediately. The outgoing president becomes a
     * member; if the new president was vice-president, that seat becomes free.
     */
    public Handover transferPresidency(AuthUser actor, String clubId, String userId) {
        ClubContext context = access.context(actor, clubId);
        Club club = context.club();
        if (!ClubRoles.PRESIDENT.equals(context.role())) {
            throw AppException.forbidden("Only the president can hand over the presidency");
        }
        if (userId.equals(actor.id())) {
            throw invalid("Choose another member");
        }
        User user = mongo.findById(userId, User.class);
        ClubMembership membership = access.membership(userId, club.getId());
        if (membership == null || user == null) {
            throw invalid("The new president must be a member of the club");
        }
        if (!user.isActive() || !user.isEmailVerified() || user.getAccountType() != AccountType.STUDENT || user.getGlobalRole() != GlobalRole.STUDENT) {
            throw invalid("The new president must be an active, verified student");
        }
        String previousRole = membership.getRole();

        transactions.executeWithoutResult(transaction -> {
            Instant now = Instant.now();
            long stepDown = mongo.updateFirst(Query.query(Criteria.where("club").is(club.getId()).and("user").is(actor.id()).and("role").is(ClubRoles.PRESIDENT)),
                    new Update().set("role", ClubRoles.MEMBER).set("updatedAt", now), ClubMembership.class).getModifiedCount();
            if (stepDown == 0) {
                throw AppException.forbidden("Only the president can hand over the presidency");
            }
            long stepUp = mongo.updateFirst(Query.query(Criteria.where("_id").is(membership.getId()).and("status").is(MembershipStatus.APPROVED)),
                    new Update().set("role", ClubRoles.PRESIDENT).set("decidedBy", actor.id()).set("decidedAt", now).set("updatedAt", now), ClubMembership.class).getModifiedCount();
            if (stepUp == 0) {
                throw invalid("The new president must be a member of the club");
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(club.getId())), new Update().set("president", user.getId()).set("updatedAt", now), Club.class);
            audit.record(AuditAction.PRESIDENCY_TRANSFERRED, actor.id(), "Club", club.getId(), actor.id(), user.getId(), null, Map.of("newPresidentPreviousRole", previousRole));
        });
        caches.directoryChanged();
        caches.clubChanged();

        String link = ClubService.link(club);
        notifications.notify(user.getId(), Notice.builder()
                .type(NotificationType.CLUB_ROLE_CHANGED)
                .title("You are now president of " + club.getName())
                .message(actor.name() + " handed the presidency over to you. You now hold every authority in the club.")
                .link(link)
                .email(true)
                .build());
        notifications.notify(actor.id(), Notice.builder()
                .type(NotificationType.CLUB_ROLE_CHANGED)
                .title("You handed over " + club.getName())
                .message(user.getName() + " is the new president. You remain a member.")
                .link(link)
                .build());
        if (club.getMentor() != null) {
            notifications.notify(club.getMentor(), Notice.builder()
                    .type(NotificationType.CLUB_UPDATE)
                    .title("New president for " + club.getName())
                    .message(actor.name() + " handed the presidency over to " + user.getName() + ".")
                    .link(link)
                    .email(true)
                    .build());
        }
        notifications.notify(access.usersWithPermission(club.getId(), ClubPermission.MANAGE_EVENTS), Notice.builder()
                .type(NotificationType.CLUB_UPDATE)
                .title(user.getName() + " is the new president of " + club.getName())
                .message(actor.name() + " handed over the presidency.")
                .link(link)
                .exclude(List.of(actor.id(), user.getId()))
                .build());

        Map<String, Object> president = new LinkedHashMap<>();
        president.put("_id", user.getId());
        president.put("name", user.getName());
        return new Handover(president, ClubRoles.roleName(club, previousRole));
    }
}
