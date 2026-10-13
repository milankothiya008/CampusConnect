package com.campusconnect.feature.admin;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.mail.MailService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.Page;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.academic.AcademicBatch;
import com.campusconnect.domain.academic.Department;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.ClubRequestStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.auth.AuthService;
import com.campusconnect.feature.club.JsValues;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * University administration (services/AdminService.js): departments and batches, accounts, the faculty list and the
 * platform statistics. The start-up seeding lives in feature.bootstrap; the legacy data migrations are not ported.
 */
@Service
public class AdminService {

    /** USER_PUBLIC_FIELDS: what lists show of an account (no mobile number). */
    static final String[] PUBLIC_FIELDS = {"name", "email", "accountType", "globalRole", "isEmailVerified", "departmentCode", "batchCode", "avatar", "isActive", "createdAt"};
    /** USER_ACCOUNT_FIELDS: the public fields plus the mobile number. */
    static final String[] ACCOUNT_FIELDS = {"name", "email", "accountType", "globalRole", "isEmailVerified", "departmentCode", "batchCode", "avatar", "isActive", "createdAt", "phone"};

    public record UserCounts(long total, long students, long faculty) {
    }

    public record ClubCounts(long total, Map<String, Long> byStatus) {
    }

    public record RequestCounts(long pendingFacultyReview, long awaitingAdmin) {
    }

    public record EventCounts(long total, Map<String, Long> byStatus, long upcoming) {
    }

    public record Stats(UserCounts users, ClubCounts clubs, RequestCounts clubRequests, EventCounts events, long registrations, long memberships, long venues,
            MailService.QueueStats emails) {
    }

    private final MongoOperations mongo;
    private final ReferenceData reference;
    private final AuditService audit;
    private final AuthService auth;
    private final AuthUserService authUsers;
    private final MailService mail;

    public AdminService(MongoOperations mongo, ReferenceData reference, AuditService audit, AuthService auth, AuthUserService authUsers, MailService mail) {
        this.mongo = mongo;
        this.reference = reference;
        this.audit = audit;
        this.auth = auth;
        this.authUsers = authUsers;
        this.mail = mail;
    }

    /** Mongoose's String cast and trim of a stored text value. */
    private static String text(Object value) {
        return value == null ? null : JsValues.trim(value instanceof String string ? string : JsValues.string(value));
    }

    // ---------------------------------------------------------------- departments and batches

    public List<Department> listDepartments(String active) {
        return reference.departments("true".equals(active));
    }

    public List<AcademicBatch> listBatches(String active) {
        return reference.batches("true".equals(active));
    }

    public Department createDepartment(AuthUser actor, Map<String, Object> body) {
        ClubAccess.assertAdmin(actor);
        if (!JsValues.truthy(body.get("code")) || !JsValues.truthy(body.get("name"))) {
            throw AppException.badRequest("Code and name are required", ErrorCode.VALIDATION_ERROR);
        }
        Department department = new Department();
        department.setCode(JsValues.trim(JsValues.string(body.get("code")).toUpperCase(Locale.ROOT)));
        department.setName(text(body.get("name")));
        department.setActive(!body.containsKey("isActive") || JsValues.mongooseBoolean(body.get("isActive")));
        Department saved = mongo.insert(department);
        reference.changed();
        return mongo.findById(saved.getId(), Department.class);
    }

    public Department updateDepartment(AuthUser actor, String id, Map<String, Object> body) {
        ClubAccess.assertAdmin(actor);
        Update update = new Update().set("updatedAt", Instant.now());
        if (body.containsKey("name")) {
            update.set("name", text(body.get("name")));
        }
        if (body.containsKey("isActive")) {
            update.set("isActive", JsValues.mongooseBoolean(body.get("isActive")));
        }
        Department department = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), update, FindAndModifyOptions.options().returnNew(true), Department.class);
        if (department == null) {
            throw AppException.notFound("Department not found");
        }
        reference.changed();
        return department;
    }

    public AcademicBatch createBatch(AuthUser actor, Map<String, Object> body) {
        ClubAccess.assertAdmin(actor);
        if (!JsValues.truthy(body.get("code")) || !JsValues.truthy(body.get("label"))) {
            throw AppException.badRequest("Code and label are required", ErrorCode.VALIDATION_ERROR);
        }
        AcademicBatch batch = new AcademicBatch();
        batch.setCode(text(body.get("code")));
        batch.setLabel(text(body.get("label")));
        batch.setActive(!body.containsKey("isActive") || JsValues.mongooseBoolean(body.get("isActive")));
        AcademicBatch saved = mongo.insert(batch);
        reference.changed();
        return mongo.findById(saved.getId(), AcademicBatch.class);
    }

    public AcademicBatch updateBatch(AuthUser actor, String id, Map<String, Object> body) {
        ClubAccess.assertAdmin(actor);
        Update update = new Update().set("updatedAt", Instant.now());
        if (body.containsKey("label")) {
            update.set("label", text(body.get("label")));
        }
        if (body.containsKey("isActive")) {
            update.set("isActive", JsValues.mongooseBoolean(body.get("isActive")));
        }
        AcademicBatch batch = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), update, FindAndModifyOptions.options().returnNew(true), AcademicBatch.class);
        if (batch == null) {
            throw AppException.notFound("Academic batch not found");
        }
        reference.changed();
        return batch;
    }

    // ---------------------------------------------------------------- accounts

    private static Query accountFields(Query query) {
        query.fields().include(ACCOUNT_FIELDS);
        return query;
    }

    public Page<AuthUser> listUsers(AuthUser actor, String page, String limit, String role, String accountType, String department, String active, String search) {
        ClubAccess.assertAdmin(actor);
        Pagination pagination = Pagination.of(page, limit, 20, 100);
        Criteria filter = new Criteria();
        if (role != null && !role.isEmpty()) {
            filter = filter.and("globalRole").is(role.toUpperCase(Locale.ROOT));
        }
        if (accountType != null && !accountType.isEmpty()) {
            filter = filter.and("accountType").is(accountType.toUpperCase(Locale.ROOT));
        }
        if (department != null && !department.isEmpty()) {
            filter = filter.and("departmentCode").is(department.toUpperCase(Locale.ROOT));
        }
        if ("true".equals(active) || "false".equals(active)) {
            filter = filter.and("isActive").is("true".equals(active));
        }
        if (search != null && !search.isEmpty()) {
            filter = filter.orOperator(Criteria.where("name").regex(Texts.searchPattern(search), "i"), Criteria.where("email").regex(Texts.searchPattern(search), "i"));
        }
        Query query = accountFields(Query.query(filter)).with(Sort.by(Sort.Direction.DESC, "createdAt")).skip(pagination.skip()).limit(pagination.limit());
        List<AuthUser> items = mongo.find(query, User.class).stream().map(AuthUser::of).toList();
        return Page.of(items, pagination, mongo.count(Query.query(filter), User.class));
    }

    /** Anyone may read their own account; other accounts are for the admin. */
    public AuthUser getUserById(AuthUser actor, String id) {
        if (actor == null || !actor.id().equals(id)) {
            ClubAccess.assertAdmin(actor);
        }
        User user = mongo.findOne(accountFields(Query.query(Criteria.where("_id").is(id))), User.class);
        if (user == null) {
            throw AppException.notFound("User not found");
        }
        return AuthUser.of(user);
    }

    /** Deactivating an account also ends its sessions. */
    public AuthUser setUserActive(AuthUser actor, String id, Object isActive) {
        ClubAccess.assertAdmin(actor);
        if (actor.id().equals(id)) {
            throw AppException.badRequest("You cannot change your own account status", ErrorCode.VALIDATION_ERROR);
        }
        User user = mongo.findOne(accountFields(Query.query(Criteria.where("_id").is(id))), User.class);
        if (user == null) {
            throw AppException.notFound("User not found");
        }
        boolean from = user.isActive();
        boolean to = JsValues.truthy(isActive);
        User updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), new Update().set("isActive", to).set("updatedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true), User.class);
        authUsers.evict(id);
        if (!to) {
            auth.logoutAll(id);
        }
        audit.record(AuditAction.USER_STATUS_CHANGED, actor.id(), "User", id, from ? "ACTIVE" : "INACTIVE", to ? "ACTIVE" : "INACTIVE", null, Map.of());
        return AuthUser.of(updated == null ? user : updated);
    }

    /** Faculty with the clubs they mentor (the admin's mentor overview). */
    public List<Map<String, Object>> listFaculty(AuthUser actor, String search) {
        ClubAccess.assertAdmin(actor);
        Criteria filter = Criteria.where("globalRole").is(GlobalRole.FACULTY.name());
        if (search != null && !search.isEmpty()) {
            filter = filter.orOperator(Criteria.where("name").regex(Texts.searchPattern(search), "i"), Criteria.where("email").regex(Texts.searchPattern(search), "i"));
        }
        Query query = Query.query(filter).with(Sort.by("name"));
        query.fields().include(PUBLIC_FIELDS);
        List<Document> faculty = mongo.find(query, Document.class, "users");
        Query clubQuery = Query.query(Criteria.where("mentor").in(faculty.stream().map(row -> row.getObjectId("_id")).toList()));
        clubQuery.fields().include("name", "status", "mentor");
        List<Map<String, Object>> clubs = faculty.isEmpty() ? List.of() : mongo.find(clubQuery, Document.class, "clubs").stream()
                .map(club -> Refs.shape(club, "name", "status", "mentor"))
                .toList();
        return faculty.stream().map(row -> {
            Map<String, Object> member = Refs.shape(row, PUBLIC_FIELDS);
            member.put("mentoredClubs", clubs.stream().filter(club -> member.get("_id").equals(club.get("mentor"))).toList());
            return member;
        }).toList();
    }

    // ---------------------------------------------------------------- statistics

    private long count(String collection, Criteria criteria) {
        return mongo.count(criteria == null ? new Query() : Query.query(criteria), collection);
    }

    private Map<String, Long> byStatus(String collection) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Aggregation aggregation = Aggregation.newAggregation(Aggregation.group("status").count().as("count"));
        for (Document row : mongo.aggregate(aggregation, collection, Document.class)) {
            counts.put(String.valueOf(row.get("_id")), ((Number) row.get("count")).longValue());
        }
        return counts;
    }

    public Stats getStats(AuthUser actor) {
        ClubAccess.assertAdmin(actor);
        Map<String, Long> clubs = byStatus("clubs");
        Map<String, Long> events = byStatus("events");
        return new Stats(
                new UserCounts(count("users", null), count("users", Criteria.where("globalRole").is(GlobalRole.STUDENT.name())),
                        count("users", Criteria.where("globalRole").is(GlobalRole.FACULTY.name()))),
                new ClubCounts(clubs.values().stream().mapToLong(Long::longValue).sum(), clubs),
                new RequestCounts(count("clubcreationrequests", Criteria.where("status").is(ClubRequestStatus.PENDING_FACULTY_REVIEW.name())),
                        count("clubcreationrequests", Criteria.where("status").is(ClubRequestStatus.FACULTY_VERIFIED.name()))),
                new EventCounts(events.values().stream().mapToLong(Long::longValue).sum(), events,
                        count("events", Criteria.where("status").is(EventStatus.PUBLISHED.name()).and("startAt").gte(Instant.now()))),
                count("eventregistrations", Criteria.where("status").is(RegistrationStatus.REGISTERED.name())),
                count("clubmemberships", Criteria.where("status").is(MembershipStatus.APPROVED.name())),
                count("venues", null),
                mail.stats());
    }
}
