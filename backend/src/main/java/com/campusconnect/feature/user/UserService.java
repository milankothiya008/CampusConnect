package com.campusconnect.feature.user;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.util.Phones;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.club.JsValues;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Profiles, profile edits and people search (services/UserService.js). */
@Service
public class UserService {

    private static final Pattern WEB_PHOTO = Pattern.compile("^https?://\\S+$", Pattern.UNICODE_CHARACTER_CLASS);

    /** What a profile shows of someone's past participation (never their upcoming schedule). */
    public record History(List<Map<String, Object>> events, List<RecordService.AwardRow> awards, List<Map<String, Object>> certificates) {
    }

    /** What anyone signed in sees on someone's profile page. */
    public record PublicProfile(
            @JsonProperty("_id") String id,
            String name,
            String avatar,
            String phone,
            AccountType accountType,
            GlobalRole globalRole,
            String departmentCode,
            String batchCode,
            Instant joinedAt,
            @JsonProperty("isSelf") boolean isSelf,
            List<Map<String, Object>> clubs,
            List<Map<String, Object>> mentoredClubs,
            History history) {
    }

    private final MongoOperations mongo;
    private final ClubAccess access;
    private final AuthUserService authUsers;
    private final RecordService records;

    public UserService(MongoOperations mongo, ClubAccess access, AuthUserService authUsers, RecordService records) {
        this.mongo = mongo;
        this.access = access;
        this.authUsers = authUsers;
        this.records = records;
    }

    // ---------------------------------------------------------------- own profile

    /**
     * Every student and faculty account has a mobile number (asked for at sign-up), so it can be changed but not
     * removed. Only accounts without one from before that (and not in a club) may leave it empty.
     */
    private String phoneUpdate(String userId, Object value) {
        if (value == null || JsValues.trimmed(value).isEmpty()) {
            Query owner = Query.query(Criteria.where("_id").is(userId));
            owner.fields().include("phone");
            User user = mongo.findOne(owner, User.class);
            boolean member = mongo.exists(Query.query(Criteria.where("user").is(userId).and("status").is(MembershipStatus.APPROVED)), ClubMembership.class);
            if ((user != null && JsValues.truthy(user.getPhone())) || member) {
                throw AppException.badRequest("Your mobile number can be changed but not removed", ErrorCode.PHONE_REQUIRED);
            }
            return null;
        }
        String phone = Phones.normalize(JsValues.string(value));
        if (phone == null) {
            throw AppException.badRequest("Enter a valid 10-digit Indian mobile number", ErrorCode.VALIDATION_ERROR);
        }
        return phone;
    }

    /** Profile photos are uploaded to media storage first; the profile keeps the URL. */
    private static String avatarUpdate(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        String text = JsValues.trimmed(value);
        if (!WEB_PHOTO.matcher(text).matches() && !text.startsWith("/uploads/")) {
            throw AppException.badRequest("Upload the photo again", ErrorCode.VALIDATION_ERROR);
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    public AuthUser updateUser(AuthUser actor, String id, Map<String, Object> data) {
        if (!actor.id().equals(id)) {
            throw AppException.forbidden("You can only update your own profile");
        }
        Update update = new Update().set("updatedAt", Instant.now());
        if (data.containsKey("name")) {
            update.set("name", JsValues.trimmed(data.get("name")));
        }
        if (data.containsKey("phone")) {
            update.set("phone", phoneUpdate(id, data.get("phone")));
        }
        if (data.containsKey("avatar")) {
            update.set("avatar", avatarUpdate(data.get("avatar")));
        }
        User user = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), update, FindAndModifyOptions.options().returnNew(true), User.class);
        if (user == null) {
            throw AppException.notFound("User not found");
        }
        authUsers.evict(id);
        return AuthUser.of(user);
    }

    // ---------------------------------------------------------------- profiles

    /** A profile's phone: faculty numbers for club members, faculty and the admin; students' for the club directory. */
    private boolean canSeePhone(AuthUser actor, User user) {
        if (actor.id().equals(user.getId())) {
            return true;
        }
        return user.getGlobalRole() == GlobalRole.FACULTY ? access.canSeeFacultyContacts(actor) : access.canSeeMemberDirectory(actor);
    }

    private History publicHistory(String userId) {
        RecordService.Record record = records.collectRecord(userId);
        List<Map<String, Object>> events = record.events().stream().limit(30).map(row -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("_id", row.eventId());
            view.put("title", row.title());
            view.put("club", row.club());
            view.put("category", row.category());
            view.put("startAt", row.startAt());
            view.put("endAt", row.endAt());
            view.put("team", row.team());
            view.put("attendance", row.attendance());
            return view;
        }).toList();
        List<Map<String, Object>> certificates = record.certificates().stream().limit(30).map((Certificate row) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", row.getCode());
            view.put("kind", row.getKind());
            view.put("awardTitle", row.getAwardTitle());
            view.put("eventTitle", row.getEventTitle());
            view.put("clubName", row.getClubName());
            view.put("event", row.getEvent());
            view.put("eventStartAt", row.getEventStartAt());
            return view;
        }).toList();
        return new History(events, record.awards().stream().limit(30).toList(), certificates);
    }

    /**
     * Name, role, department and batch, and the active clubs they belong to or mentor. Personal details (e-mail,
     * mobile number) and their schedule, registrations and applications are never included.
     */
    public PublicProfile getPublicProfile(AuthUser actor, String id) {
        Query query = Query.query(Criteria.where("_id").is(id).and("isActive").is(true).and("isEmailVerified").is(true));
        query.fields().include("name", "accountType", "globalRole", "departmentCode", "batchCode", "avatar", "phone", "createdAt");
        User user = mongo.findOne(query, User.class);
        if (user == null) {
            throw AppException.notFound("Profile not found");
        }
        Query membershipQuery = Query.query(Criteria.where("user").is(user.getId()).and("status").is(MembershipStatus.APPROVED));
        membershipQuery.fields().include("club", "role", "joinedAt");
        List<ClubMembership> memberships = mongo.find(membershipQuery, ClubMembership.class);
        Map<String, Club> clubs = Map.of();
        if (!memberships.isEmpty()) {
            Query clubQuery = Query.query(Criteria.where("_id").in(memberships.stream().map(ClubMembership::getClub).toList()));
            clubQuery.fields().include("name", "logo", "category", "status", "roles");
            clubs = mongo.find(clubQuery, Club.class).stream().collect(Collectors.toMap(Club::getId, Function.identity()));
        }
        Map<String, Club> memberOf = clubs;
        List<Map<String, Object>> clubRows = memberships.stream()
                .filter(membership -> memberOf.containsKey(membership.getClub()) && memberOf.get(membership.getClub()).getStatus() == ClubStatus.ACTIVE)
                .sorted(Comparator.comparingInt(membership -> ClubRoles.roleRank(memberOf.get(membership.getClub()), membership.getRole())))
                .map(membership -> {
                    Club club = memberOf.get(membership.getClub());
                    Map<String, Object> clubView = new LinkedHashMap<>();
                    clubView.put("_id", club.getId());
                    clubView.put("name", club.getName());
                    clubView.put("logo", club.getLogo());
                    clubView.put("category", club.getCategory());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("club", clubView);
                    row.put("role", membership.getRole());
                    row.put("roleName", ClubRoles.roleName(club, membership.getRole()));
                    return row;
                })
                .toList();
        List<Map<String, Object>> mentored = List.of();
        if (user.getGlobalRole() == GlobalRole.FACULTY) {
            Query mentoredQuery = Query.query(Criteria.where("mentor").is(new ObjectId(user.getId())).and("status").is(ClubStatus.ACTIVE.name())).with(Sort.by("name"));
            mentoredQuery.fields().include("name", "logo", "category");
            mentored = mongo.find(mentoredQuery, Document.class, "clubs").stream().map(club -> Refs.shape(club, "name", "logo", "category")).toList();
        }
        return new PublicProfile(
                user.getId(),
                user.getName(),
                user.getAvatar() == null || user.getAvatar().isEmpty() ? null : user.getAvatar(),
                canSeePhone(actor, user) && user.getPhone() != null && !user.getPhone().isEmpty() ? user.getPhone() : null,
                user.getAccountType(),
                user.getGlobalRole(),
                user.getDepartmentCode(),
                user.getBatchCode(),
                user.getCreatedAt(),
                actor.id().equals(user.getId()),
                clubRows,
                mentored,
                user.getAccountType() == AccountType.STUDENT ? publicHistory(user.getId()) : null);
    }

    // ---------------------------------------------------------------- search

    /** {@code Number(text)}: NaN when the text is not a number. */
    static double jsNumber(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            if (value.matches("(?i)0x[0-9a-f]+")) {
                return Long.parseLong(value.substring(2), 16);
            }
            return value.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?|[+-]?Infinity") ? Double.parseDouble(value) : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * People search (Explore): students and faculty by name, with only what a profile shows publicly — never e-mail or
     * mobile number. The university admin account isn't listed.
     */
    public List<Map<String, Object>> searchPeople(AuthUser actor, String q, String department, String limit) {
        String text = JsValues.trim(q == null ? "" : q);
        if (text.length() < 2) {
            return List.of();
        }
        Criteria filter = Criteria.where("isActive").is(true).and("isEmailVerified").is(true)
                .and("globalRole").in(GlobalRole.STUDENT.name(), GlobalRole.FACULTY.name())
                .and("name").regex(Texts.searchPattern(text), "i");
        if (department != null && !department.isEmpty()) {
            filter = filter.and("departmentCode").is(department.toUpperCase(Locale.ROOT));
        }
        double requested = jsNumber(limit);
        int size = (int) Math.min(Math.max(Double.isNaN(requested) || requested == 0 ? 20 : requested, 1), 40);
        String[] fields = {"name", "avatar", "accountType", "globalRole", "departmentCode", "batchCode"};
        Query query = Query.query(filter).with(Sort.by("name")).limit(size);
        query.fields().include(fields);
        return mongo.find(query, Document.class, "users").stream().map(person -> {
            Map<String, Object> view = Refs.shape(person, fields);
            view.put("isSelf", actor.id().equals(view.get("_id")));
            return view;
        }).toList();
    }

    /** Directory lookup for pickers (founding members, president, mentors, award recipients). */
    public List<Map<String, Object>> searchUsers(String q, String accountType, String departments) {
        Criteria filter = Criteria.where("isActive").is(true).and("isEmailVerified").is(true)
                .orOperator(Criteria.where("name").regex(Texts.searchPattern(String.valueOf(q)), "i"), Criteria.where("email").regex(Texts.searchPattern(String.valueOf(q)), "i"));
        if (accountType != null && !accountType.isEmpty()) {
            String type = accountType.toUpperCase(Locale.ROOT);
            filter = filter.and("accountType").is(type);
            // Faculty searches pick mentors, and the university admin cannot be one.
            if (AccountType.FACULTY.name().equals(type)) {
                filter = filter.and("globalRole").is(GlobalRole.FACULTY.name());
            }
        }
        if (departments != null && !departments.isEmpty()) {
            filter = filter.and("departmentCode").in(Arrays.stream(departments.toUpperCase(Locale.ROOT).split(",")).filter(code -> !code.isEmpty()).toList());
        }
        String[] fields = {"name", "email", "accountType", "departmentCode", "batchCode"};
        Query query = Query.query(filter).with(Sort.by("name")).limit(10);
        query.fields().include(fields);
        return mongo.find(query, Document.class, "users").stream().map(user -> Refs.shape(user, fields)).toList();
    }
}
