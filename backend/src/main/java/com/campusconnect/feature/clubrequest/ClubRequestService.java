package com.campusconnect.feature.clubrequest;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.access.DepartmentScope;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.Page;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubCreationRequest;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubRequestHistoryEntry;
import com.campusconnect.domain.club.ClubRequestStatus;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.club.ClubCaches;
import com.campusconnect.feature.club.DocumentViews;
import com.campusconnect.feature.club.JsValues;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Founding a club (services/ClubRequestService.js): a student proposes it with founding members and a faculty mentor;
 * the mentor verifies it (or asks for changes, or rejects it); the university admin approves it and the club is
 * created with the verifying faculty as mentor and the founders as members. Every step is kept in the request's
 * history and the audit log.
 *
 * <p>Each step is one conditional write on the status it was checked against, so two reviewers acting at once
 * cannot both move the request.
 */
@Service
public class ClubRequestService {

    static final List<String> EDITABLE_FIELDS = List.of("name", "description", "purpose", "proposedActivities", "reason", "category");
    /** Mongoose trims these fields; category is stored as given. */
    static final Set<String> TRIMMED = Set.of("name", "description", "purpose", "proposedActivities", "reason");

    /** The approved request and the club created from it. */
    public record Approval(Map<String, Object> request, Map<String, Object> club) {
    }

    private final MongoOperations mongo;
    private final DepartmentScope departments;
    private final ClubRequestViews views;
    private final DocumentViews documents;
    private final ClubCaches caches;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;

    public ClubRequestService(MongoOperations mongo, DepartmentScope departments, ClubRequestViews views, DocumentViews documents, ClubCaches caches,
            AuditService audit, NotificationService notifications, TransactionTemplate transactions) {
        this.mongo = mongo;
        this.departments = departments;
        this.views = views;
        this.documents = documents;
        this.caches = caches;
        this.audit = audit;
        this.notifications = notifications;
        this.transactions = transactions;
    }

    private static String link(ClubCreationRequest request) {
        return "/club-requests/" + request.getId();
    }

    private static List<String> studentsOf(ClubCreationRequest request) {
        List<String> students = new ArrayList<>();
        students.add(request.getRequester());
        students.addAll(request.getFoundingMembers());
        return students;
    }

    private static DepartmentScope.Scope scopeOf(ClubCreationRequest request) {
        return new DepartmentScope.Scope(request.isAllDepartments(), request.getDepartmentCodes());
    }

    /** A request field as Mongoose stores it (cast to text, trimmed where the schema trims). */
    private static String stored(String field, Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof String string ? string : JsValues.string(value);
        return TRIMMED.contains(field) ? JsValues.trim(text) : text;
    }

    private ClubCreationRequest findRequest(String id) {
        ClubCreationRequest request = mongo.findById(id, ClubCreationRequest.class);
        if (request == null) {
            throw AppException.notFound("Club request not found");
        }
        return request;
    }

    private static ClubRequestHistoryEntry entry(AuditAction action, ClubRequestStatus from, ClubRequestStatus to, AuthUser actor, String reason) {
        ClubRequestHistoryEntry entry = new ClubRequestHistoryEntry();
        entry.setAction(action);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setActor(actor.id());
        entry.setReason(reason);
        entry.setAt(Instant.now());
        return entry;
    }

    private void auditStep(ClubCreationRequest request, AuditAction action, AuthUser actor, ClubRequestStatus from, ClubRequestStatus to, String reason, Map<String, Object> metadata) {
        audit.record(action, actor.id(), "ClubCreationRequest", request.getId(), from == null ? null : from.name(), to.name(), reason, metadata);
    }

    /** One conditional step: the request moves only if it is still in {@code from}. */
    private ClubCreationRequest step(ClubCreationRequest request, ClubRequestStatus from, Update update, ClubRequestHistoryEntry entry, String conflict) {
        ClubCreationRequest updated = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(request.getId()).and("status").is(from)),
                update.push("history", entry).set("updatedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true),
                ClubCreationRequest.class);
        if (updated == null) {
            throw AppException.conflict(conflict, ErrorCode.INVALID_STATE);
        }
        return updated;
    }

    // ---------------------------------------------------------------- checks

    private void assertClubNameAvailable(Object name, String excludeRequestId) {
        String exact = "^" + Texts.escapeRegex(JsValues.trimmed(name)) + "$";
        if (mongo.exists(Query.query(Criteria.where("name").regex(exact, "i")), Club.class)) {
            throw AppException.conflict("A club with this name already exists", ErrorCode.CONFLICT);
        }
        Criteria open = Criteria.where("name").regex(exact, "i").and("status").nin(ClubRequestStatus.REJECTED, ClubRequestStatus.APPROVED);
        if (excludeRequestId != null) {
            open = open.and("_id").ne(excludeRequestId);
        }
        if (mongo.exists(Query.query(open), ClubCreationRequest.class)) {
            throw AppException.conflict("Another request for a club with this name is already in progress", ErrorCode.CONFLICT);
        }
    }

    /** Founding members are verified students, found by e-mail; the requester is never listed twice. */
    private List<User> resolveFoundingMembers(Object emails, String requesterId) {
        Set<String> normalized = new LinkedHashSet<>();
        if (emails instanceof Collection<?> list) {
            list.forEach(email -> {
                String value = JsValues.trim(JsValues.string(email)).toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            });
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("email").in(normalized).and("accountType").is(AccountType.STUDENT).and("globalRole").is(GlobalRole.STUDENT)
                .and("isEmailVerified").is(true).and("isActive").is(true));
        query.fields().include("_id", "name", "email", "departmentCode");
        List<User> users = mongo.find(query, User.class);
        Set<String> found = new LinkedHashSet<>(users.stream().map(User::getEmail).toList());
        List<String> missing = normalized.stream().filter(email -> !found.contains(email)).toList();
        if (!missing.isEmpty()) {
            throw AppException.badRequest("These founding members are not verified CampusConnect students: " + String.join(", ", missing), ErrorCode.VALIDATION_ERROR);
        }
        return users.stream().filter(user -> !user.getId().equals(requesterId)).toList();
    }

    /** The requester and founding members become members on approval, so they must be from the club's departments. */
    private static void assertFoundersMatchScope(AuthUser requester, List<User> founders, DepartmentScope.Scope scope) {
        if (!DepartmentScope.belongs(requester.departmentCode(), scope)) {
            throw AppException.forbidden("You are in " + requester.departmentCode() + ", so the club must include " + requester.departmentCode()
                    + " or be open to all departments.", ErrorCode.NOT_ELIGIBLE);
        }
        DepartmentScope.assertStudentsMatch(founders.stream().map(user -> new DepartmentScope.Person(user.getName(), user.getDepartmentCode())).toList(), scope, false, "The club");
    }

    /** The proposed mentor must be active faculty from one of the club's departments (any faculty for an all-departments club). */
    private String resolveProposedMentor(Object mentorId, DepartmentScope.Scope scope) {
        if (!JsValues.truthy(mentorId)) {
            return null;
        }
        User mentor = mongo.findOne(Query.query(Criteria.where("_id").is(JsValues.string(mentorId)).and("globalRole").is(GlobalRole.FACULTY).and("isActive").is(true)), User.class);
        if (mentor == null) {
            throw AppException.badRequest("Proposed mentor must be an active faculty member", ErrorCode.VALIDATION_ERROR);
        }
        DepartmentScope.assertMentorMatches(new DepartmentScope.Person(mentor.getName(), mentor.getDepartmentCode()), scope);
        return mentor.getId();
    }

    private static Collection<?> codes(Object value) {
        return value instanceof Collection<?> list ? list : null;
    }

    private static void assertFacultyReviewer(AuthUser actor, ClubCreationRequest request) {
        ClubAccess.assertFaculty(actor);
        if (request.getProposedMentor() != null && !request.getProposedMentor().equals(actor.id())) {
            throw AppException.forbidden("This request is assigned to another faculty member");
        }
        if (request.getProposedMentor() == null && !DepartmentScope.belongs(actor.departmentCode(), scopeOf(request))) {
            throw AppException.forbidden("This club is for " + DepartmentScope.describe(scopeOf(request)) + "; only faculty from those departments can review it");
        }
        if (request.getStatus() != ClubRequestStatus.PENDING_FACULTY_REVIEW) {
            throw AppException.conflict("Request is not awaiting faculty review", ErrorCode.INVALID_STATE);
        }
    }

    // ---------------------------------------------------------------- submitting and editing

    public Map<String, Object> submitRequest(AuthUser actor, Map<String, Object> payload) {
        ClubAccess.assertVerified(actor);
        ClubAccess.assertStudent(actor, "Only verified students can request a club");
        List<String> missing = EDITABLE_FIELDS.stream().filter(field -> !JsValues.truthy(payload.get(field)) || JsValues.trimmed(payload.get(field)).isEmpty()).toList();
        if (!missing.isEmpty()) {
            throw AppException.badRequest("Missing required fields: " + String.join(", ", missing), ErrorCode.VALIDATION_ERROR);
        }
        assertClubNameAvailable(payload.get("name"), null);
        DepartmentScope.Scope scope = departments.resolve(payload.get("allDepartments"), codes(payload.get("departmentCodes")));
        List<User> founders = resolveFoundingMembers(payload.get("foundingMemberEmails"), actor.id());
        assertFoundersMatchScope(actor, founders, scope);

        ClubCreationRequest request = new ClubCreationRequest();
        request.setName(JsValues.trimmed(payload.get("name")));
        request.setDescription(stored("description", payload.get("description")));
        request.setPurpose(stored("purpose", payload.get("purpose")));
        request.setProposedActivities(stored("proposedActivities", payload.get("proposedActivities")));
        request.setReason(stored("reason", payload.get("reason")));
        request.setAllDepartments(scope.allDepartments());
        request.setDepartmentCodes(new ArrayList<>(scope.departmentCodes()));
        request.setCategory(stored("category", payload.get("category")));
        request.setRequester(actor.id());
        request.setFoundingMembers(new ArrayList<>(founders.stream().map(User::getId).toList()));
        request.setProposedMentor(resolveProposedMentor(payload.get("proposedMentor"), scope));
        request.setStatus(ClubRequestStatus.PENDING_FACULTY_REVIEW);
        request.getHistory().add(entry(AuditAction.CLUB_REQUEST_SUBMITTED, null, ClubRequestStatus.PENDING_FACULTY_REVIEW, actor, null));
        request = mongo.insert(request);

        auditStep(request, AuditAction.CLUB_REQUEST_SUBMITTED, actor, null, ClubRequestStatus.PENDING_FACULTY_REVIEW, null, Map.of());
        if (request.getProposedMentor() != null) {
            notifications.notify(request.getProposedMentor(), Notice.builder()
                    .type(NotificationType.CLUB_REQUEST_UPDATE)
                    .title("New club request to review")
                    .message(actor.name() + " proposed \"" + request.getName() + "\" and named you as faculty mentor.")
                    .link(link(request))
                    .email(true)
                    .build());
        }
        return views.of(findRequest(request.getId()));
    }

    /** Only the requester edits, and only while the faculty has asked for changes. */
    public Map<String, Object> updateRequest(AuthUser actor, String id, Map<String, Object> payload) {
        ClubCreationRequest request = findRequest(id);
        if (!actor.id().equals(request.getRequester())) {
            throw AppException.forbidden("Only the requesting student can edit this request");
        }
        if (request.getStatus() != ClubRequestStatus.NEEDS_CHANGES) {
            throw AppException.conflict("Requests can only be edited when changes are requested", ErrorCode.INVALID_STATE);
        }
        if (payload.containsKey("name") && !JsValues.trimmed(payload.get("name")).toLowerCase(Locale.ROOT).equals(String.valueOf(request.getName()).toLowerCase(Locale.ROOT))) {
            assertClubNameAvailable(payload.get("name"), request.getId());
        }
        Update update = new Update();
        boolean changed = false;
        for (String field : EDITABLE_FIELDS) {
            if (payload.containsKey(field)) {
                String value = stored(field, payload.get(field));
                if (!Objects.equals(value, current(request, field))) {
                    update.set(field, value);
                    changed = true;
                }
            }
        }
        boolean scopeChanged = payload.containsKey("allDepartments") || payload.containsKey("departmentCodes");
        if (scopeChanged || payload.containsKey("foundingMemberEmails")) {
            DepartmentScope.Scope scope = scopeChanged
                    ? departments.resolve(payload.get("allDepartments") != null ? payload.get("allDepartments") : request.isAllDepartments(),
                            payload.get("departmentCodes") != null ? codes(payload.get("departmentCodes")) : request.getDepartmentCodes())
                    : scopeOf(request);
            List<User> founders;
            if (payload.containsKey("foundingMemberEmails")) {
                founders = resolveFoundingMembers(payload.get("foundingMemberEmails"), actor.id());
            } else {
                Query query = Query.query(Criteria.where("_id").in(request.getFoundingMembers()));
                query.fields().include("_id", "name", "email", "departmentCode");
                founders = mongo.find(query, User.class);
            }
            assertFoundersMatchScope(actor, founders, scope);
            if (request.getProposedMentor() != null) {
                User mentor = mongo.findById(request.getProposedMentor(), User.class);
                if (mentor != null) {
                    DepartmentScope.assertMentorMatches(new DepartmentScope.Person(mentor.getName(), mentor.getDepartmentCode()), scope);
                }
            }
            List<String> founderIds = founders.stream().map(User::getId).toList();
            if (scope.allDepartments() != request.isAllDepartments() || !scope.departmentCodes().equals(request.getDepartmentCodes()) || !founderIds.equals(request.getFoundingMembers())) {
                update.set("allDepartments", scope.allDepartments()).set("departmentCodes", scope.departmentCodes()).set("foundingMembers", founderIds);
                changed = true;
            }
        }
        if (changed) {
            ClubCreationRequest updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(request.getId()).and("status").is(ClubRequestStatus.NEEDS_CHANGES)),
                    update.set("updatedAt", Instant.now()), FindAndModifyOptions.options().returnNew(true), ClubCreationRequest.class);
            if (updated == null) {
                throw AppException.conflict("Requests can only be edited when changes are requested", ErrorCode.INVALID_STATE);
            }
            request = updated;
        }
        return views.of(request);
    }

    private static String current(ClubCreationRequest request, String field) {
        return switch (field) {
            case "name" -> request.getName();
            case "description" -> request.getDescription();
            case "purpose" -> request.getPurpose();
            case "proposedActivities" -> request.getProposedActivities();
            case "reason" -> request.getReason();
            default -> request.getCategory();
        };
    }

    public Map<String, Object> resubmitRequest(AuthUser actor, String id) {
        ClubCreationRequest request = findRequest(id);
        if (!actor.id().equals(request.getRequester())) {
            throw AppException.forbidden("Only the requesting student can resubmit this request");
        }
        String conflict = "Only requests with requested changes can be resubmitted";
        if (request.getStatus() != ClubRequestStatus.NEEDS_CHANGES) {
            throw AppException.conflict(conflict, ErrorCode.INVALID_STATE);
        }
        ClubCreationRequest updated = step(request, ClubRequestStatus.NEEDS_CHANGES, new Update().set("status", ClubRequestStatus.PENDING_FACULTY_REVIEW),
                entry(AuditAction.CLUB_REQUEST_RESUBMITTED, ClubRequestStatus.NEEDS_CHANGES, ClubRequestStatus.PENDING_FACULTY_REVIEW, actor, null), conflict);
        auditStep(updated, AuditAction.CLUB_REQUEST_RESUBMITTED, actor, ClubRequestStatus.NEEDS_CHANGES, ClubRequestStatus.PENDING_FACULTY_REVIEW, null, Map.of());

        // The faculty member who asked for the changes (or the named mentor) is told the request is back.
        String reviewer = request.getProposedMentor();
        if (reviewer == null) {
            List<ClubRequestHistoryEntry> history = request.getHistory();
            for (int i = history.size() - 1; i >= 0 && reviewer == null; i--) {
                if (history.get(i).getAction() == AuditAction.CLUB_REQUEST_CHANGES_REQUESTED) {
                    reviewer = history.get(i).getActor();
                }
            }
        }
        notifications.notify(reviewer, Notice.builder()
                .type(NotificationType.CLUB_REQUEST_UPDATE)
                .title("Club request resubmitted")
                .message("\"" + request.getName() + "\" was updated and is ready for review again.")
                .link(link(request))
                .build());
        return views.of(updated);
    }

    // ---------------------------------------------------------------- review

    public Map<String, Object> verifyRequest(AuthUser actor, String id, String comment) {
        ClubCreationRequest request = findRequest(id);
        assertFacultyReviewer(actor, request);
        String note = JsValues.truthy(comment) ? comment : null;
        ClubCreationRequest updated = step(request, ClubRequestStatus.PENDING_FACULTY_REVIEW, new Update()
                        .set("status", ClubRequestStatus.FACULTY_VERIFIED)
                        .set("verifiedBy", actor.id())
                        .set("verifiedAt", Instant.now())
                        .set("reviewComment", note),
                entry(AuditAction.CLUB_REQUEST_VERIFIED, ClubRequestStatus.PENDING_FACULTY_REVIEW, ClubRequestStatus.FACULTY_VERIFIED, actor, note),
                "Request is not awaiting faculty review");
        auditStep(updated, AuditAction.CLUB_REQUEST_VERIFIED, actor, ClubRequestStatus.PENDING_FACULTY_REVIEW, ClubRequestStatus.FACULTY_VERIFIED, note, Map.of());

        notifications.notify(studentsOf(request), Notice.builder()
                .type(NotificationType.CLUB_REQUEST_UPDATE)
                .title("Club request verified by faculty")
                .message(actor.name() + " verified \"" + request.getName() + "\". It is now awaiting university admin approval.")
                .link(link(request))
                .build());
        Query admins = Query.query(Criteria.where("globalRole").is(GlobalRole.ADMIN).and("isActive").is(true));
        admins.fields().include("_id");
        notifications.notify(mongo.find(admins, User.class).stream().map(User::getId).toList(), Notice.builder()
                .type(NotificationType.CLUB_REQUEST_UPDATE)
                .title("Club request awaiting approval")
                .message("\"" + request.getName() + "\" was verified by " + actor.name() + ".")
                .link(link(request))
                .build());
        return views.of(updated);
    }

    public Map<String, Object> requestChanges(AuthUser actor, String id, String comment) {
        if (!JsValues.truthy(comment) || JsValues.trim(comment).isEmpty()) {
            throw AppException.badRequest("Describe the changes you need", ErrorCode.VALIDATION_ERROR);
        }
        ClubCreationRequest request = findRequest(id);
        assertFacultyReviewer(actor, request);
        String note = JsValues.trim(comment);
        ClubCreationRequest updated = step(request, ClubRequestStatus.PENDING_FACULTY_REVIEW,
                new Update().set("status", ClubRequestStatus.NEEDS_CHANGES).set("reviewComment", note),
                entry(AuditAction.CLUB_REQUEST_CHANGES_REQUESTED, ClubRequestStatus.PENDING_FACULTY_REVIEW, ClubRequestStatus.NEEDS_CHANGES, actor, note),
                "Request is not awaiting faculty review");
        auditStep(updated, AuditAction.CLUB_REQUEST_CHANGES_REQUESTED, actor, ClubRequestStatus.PENDING_FACULTY_REVIEW, ClubRequestStatus.NEEDS_CHANGES, note, Map.of());
        notifications.notify(studentsOf(request), Notice.builder()
                .type(NotificationType.CLUB_REQUEST_UPDATE)
                .title("Changes requested on your club request")
                .message(actor.name() + ": " + note)
                .link(link(request))
                .email(true)
                .build());
        return views.of(updated);
    }

    /** The mentor rejects a request under review; the admin rejects a verified one. */
    public Map<String, Object> rejectRequest(AuthUser actor, String id, String reason) {
        if (!JsValues.truthy(reason) || JsValues.trim(reason).isEmpty()) {
            throw AppException.badRequest("Rejection reason is required", ErrorCode.VALIDATION_ERROR);
        }
        ClubCreationRequest request = findRequest(id);
        String conflict = "Request cannot be rejected in its current state";
        if (request.getStatus() == ClubRequestStatus.PENDING_FACULTY_REVIEW) {
            assertFacultyReviewer(actor, request);
        } else if (request.getStatus() == ClubRequestStatus.FACULTY_VERIFIED) {
            ClubAccess.assertAdmin(actor);
        } else {
            throw AppException.conflict(conflict, ErrorCode.INVALID_STATE);
        }
        ClubRequestStatus from = request.getStatus();
        String note = JsValues.trim(reason);
        ClubCreationRequest updated = step(request, from, new Update()
                        .set("status", ClubRequestStatus.REJECTED)
                        .set("rejectionReason", note)
                        .set("decidedBy", actor.id())
                        .set("decidedAt", Instant.now()),
                entry(AuditAction.CLUB_REQUEST_REJECTED, from, ClubRequestStatus.REJECTED, actor, note), conflict);
        auditStep(updated, AuditAction.CLUB_REQUEST_REJECTED, actor, from, ClubRequestStatus.REJECTED, note, Map.of());
        notifications.notify(studentsOf(request), Notice.builder()
                .type(NotificationType.CLUB_REQUEST_UPDATE)
                .title("Club request rejected")
                .message("\"" + request.getName() + "\" was rejected: " + note)
                .link(link(request))
                .email(true)
                .build());
        return views.of(updated);
    }

    /**
     * The admin approves a verified request: in one transaction the club is created (the verifying faculty becomes its
     * mentor), the founders become members and the request is marked approved. The mentor then picks the president.
     */
    public Approval approveRequest(AuthUser actor, String id) {
        ClubAccess.assertAdmin(actor);
        ClubCreationRequest request = findRequest(id);
        String conflict = "Request is not awaiting admin approval";
        if (request.getStatus() != ClubRequestStatus.FACULTY_VERIFIED) {
            throw AppException.conflict(conflict, ErrorCode.INVALID_STATE);
        }
        if (mongo.exists(Query.query(Criteria.where("name").regex("^" + Texts.escapeRegex(request.getName()) + "$", "i")), Club.class)) {
            throw AppException.conflict("A club with this name already exists", ErrorCode.CONFLICT);
        }
        Club created = transactions.execute(transaction -> {
            Instant now = Instant.now();
            Club club = new Club();
            club.setName(request.getName());
            club.setDescription(request.getDescription());
            club.setPurpose(request.getPurpose());
            club.setCategory(request.getCategory());
            club.setAllDepartments(request.isAllDepartments());
            club.setDepartmentCodes(new ArrayList<>(request.getDepartmentCodes()));
            club.setMentor(request.getVerifiedBy());
            club.setStatus(ClubStatus.APPROVED);
            club.setCreationRequest(request.getId());
            club.setRoles(ClubRoles.defaultRoles());
            Club saved = mongo.insert(club);

            // Founding students join as members; the mentor then chooses the president.
            List<ClubMembership> members = studentsOf(request).stream().map(user -> {
                ClubMembership membership = new ClubMembership();
                membership.setClub(saved.getId());
                membership.setUser(user);
                membership.setRole(ClubRoles.MEMBER);
                membership.setStatus(MembershipStatus.APPROVED);
                membership.setJoinedAt(now);
                membership.setDecidedBy(actor.id());
                membership.setDecidedAt(now);
                return membership;
            }).toList();
            if (!members.isEmpty()) {
                mongo.insert(members, ClubMembership.class);
            }
            step(request, ClubRequestStatus.FACULTY_VERIFIED, new Update()
                            .set("status", ClubRequestStatus.APPROVED)
                            .set("decidedBy", actor.id())
                            .set("decidedAt", now)
                            .set("club", saved.getId()),
                    entry(AuditAction.CLUB_REQUEST_APPROVED, ClubRequestStatus.FACULTY_VERIFIED, ClubRequestStatus.APPROVED, actor, null), conflict);
            auditStep(request, AuditAction.CLUB_REQUEST_APPROVED, actor, ClubRequestStatus.FACULTY_VERIFIED, ClubRequestStatus.APPROVED, null, Map.of("clubId", saved.getId()));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("mentorId", request.getVerifiedBy());
            audit.record(AuditAction.MENTOR_ASSIGNED, actor.id(), "Club", saved.getId(), null, ClubStatus.APPROVED.name(), null, metadata);
            return saved;
        });
        caches.clubChanged();
        caches.directoryChanged();

        String clubLink = "/clubs/" + created.getId();
        notifications.notify(studentsOf(request), Notice.builder()
                .type(NotificationType.CLUB_REQUEST_UPDATE)
                .title("Your club was approved!")
                .message("\"" + request.getName() + "\" is officially a CampusConnect club. Your faculty mentor will now select the president.")
                .link(clubLink)
                .email(true)
                .build());
        notifications.notify(request.getVerifiedBy(), Notice.builder()
                .type(NotificationType.CLUB_UPDATE)
                .title("You are now a faculty mentor")
                .message("\"" + request.getName() + "\" was approved. Select a student as club president to activate it.")
                .link(clubLink)
                .email(true)
                .build());
        return new Approval(views.of(findRequest(request.getId())), documents.of(mongo.findById(created.getId(), Club.class)));
    }

    // ---------------------------------------------------------------- reading

    /**
     * Admins see every request; faculty the ones addressed to them plus open requests nobody has been named for, as
     * long as the club is for their department; students their own and the ones they found.
     */
    public Page<Map<String, Object>> listRequests(AuthUser actor, String page, String limit, String status) {
        Pagination pagination = Pagination.of(page, limit, 20);
        List<Criteria> conditions = new ArrayList<>();
        if (status != null && !status.isEmpty() && !status.toUpperCase(Locale.ROOT).equals("ALL")) {
            conditions.add(Criteria.where("status").is(status.toUpperCase(Locale.ROOT)));
        }
        if (ClubAccess.isAdmin(actor)) {
            // Everything.
        } else if (ClubAccess.isFaculty(actor)) {
            conditions.add(new Criteria().orOperator(
                    Criteria.where("proposedMentor").is(actor.id()),
                    Criteria.where("verifiedBy").is(actor.id()),
                    Criteria.where("proposedMentor").is(null).and("status").is(ClubRequestStatus.PENDING_FACULTY_REVIEW)
                            .orOperator(Criteria.where("allDepartments").is(true), Criteria.where("departmentCodes").is(actor.departmentCode()))));
        } else {
            conditions.add(new Criteria().orOperator(Criteria.where("requester").is(actor.id()), Criteria.where("foundingMembers").is(actor.id())));
        }
        Criteria filter = conditions.isEmpty() ? new Criteria() : new Criteria().andOperator(conditions);
        List<ClubCreationRequest> requests = mongo.find(Query.query(filter).with(Sort.by(Sort.Direction.DESC, "updatedAt")).skip(pagination.skip()).limit(pagination.limit()),
                ClubCreationRequest.class);
        long total = mongo.count(Query.query(filter), ClubCreationRequest.class);
        return Page.of(views.of(requests), pagination, total);
    }

    public Map<String, Object> getRequestById(AuthUser actor, String id) {
        ClubCreationRequest request = findRequest(id);
        Map<String, Object> view = views.of(request);
        if (!canView(actor, request, view)) {
            throw AppException.forbidden("You cannot view this request");
        }
        return view;
    }

    /** Decided on the populated request, like Node: a reference to a deleted user counts as none. */
    @SuppressWarnings("unchecked")
    private static boolean canView(AuthUser actor, ClubCreationRequest request, Map<String, Object> view) {
        if (ClubAccess.isAdmin(actor)) {
            return true;
        }
        String me = actor.id();
        if (ClubAccess.isFaculty(actor)) {
            return me.equals(idOf(view.get("proposedMentor"))) || me.equals(idOf(view.get("verifiedBy")))
                    || (view.get("proposedMentor") == null && request.getStatus() == ClubRequestStatus.PENDING_FACULTY_REVIEW
                            && DepartmentScope.belongs(actor.departmentCode(), scopeOf(request)));
        }
        return me.equals(idOf(view.get("requester"))) || ((List<Object>) view.get("foundingMembers")).stream().anyMatch(member -> me.equals(idOf(member)));
    }

    private static String idOf(Object populated) {
        return populated instanceof Map<?, ?> map ? (String) map.get("_id") : null;
    }
}
