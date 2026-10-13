package com.campusconnect.feature.club;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.security.RequireRole;
import com.campusconnect.core.util.Categories;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.feature.membership.MembershipService;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/clubs (routes/ClubRoutes.js). Bodies are validated with the express-validator rules of
 * validators/RequestValidators.js ({@link RequestChecks}), path ids first. A club's recruitment drives
 * ({@code GET|POST /:clubId/recruitment}) belong to the recruitment feature.
 */
@RestController
@RequestMapping("/api/clubs")
public class ClubController {

    private static final List<String> STATUSES = List.of(ClubStatus.ACTIVE.name(), ClubStatus.SUSPENDED.name(), ClubStatus.ARCHIVED.name());
    private static final Pattern URL_OR_UPLOAD = Pattern.compile("^(https?://|/uploads/)", Pattern.CASE_INSENSITIVE);

    private final ClubQueries queries;
    private final ClubService clubs;
    private final ClubStatusService statuses;
    private final ClubRoleService roles;
    private final ClubEventListing events;
    private final MembershipService memberships;
    private final SubscriptionService subscriptions;

    public ClubController(ClubQueries queries, ClubService clubs, ClubStatusService statuses, ClubRoleService roles, ClubEventListing events,
            MembershipService memberships, SubscriptionService subscriptions) {
        this.queries = queries;
        this.clubs = clubs;
        this.statuses = statuses;
        this.roles = roles;
        this.events = events;
        this.memberships = memberships;
        this.subscriptions = subscriptions;
    }

    // ---------------------------------------------------------------- directory and club pages

    @OptionalAuth
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listClubs(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String department) {
        return ApiResponse.page("Clubs fetched", queries.listClubs(actor, page, limit, status, search, category, department));
    }

    @GetMapping("/mine")
    public ApiResponse<ClubQueries.MyClubs> myClubs(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Your clubs fetched", queries.getMyClubs(actor));
    }

    @OptionalAuth
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getClub(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Club fetched", queries.getClub(actor, ObjectIds.require(id, "id")));
    }

    @OptionalAuth
    @GetMapping("/{clubId}/events")
    public ApiResponse<List<Map<String, Object>>> listClubEvents(@AuthenticationPrincipal AuthUser actor, @PathVariable String clubId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit) {
        ClubEventListing.EventPage result = events.listClubEvents(actor, ObjectIds.require(clubId, "clubId"), status, page, limit);
        return ApiResponse.ok("Club events fetched", result.items(), result.meta());
    }

    // GET/POST /{clubId}/recruitment (a club's drives; joining happens through them) live in feature.recruitment.

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateClub(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("name").optional().isString().trim().isLength(3, 120);
        checks.field("description").optional().isString().trim().isLength(10, 4000);
        checks.field("purpose").optional().isString().trim().isLength(0, 2000);
        checks.field("category").optional().isIn(Categories.CLUB_CATEGORIES).withMessage("Invalid category");
        checks.field("contactEmail").optionalFalsy().isEmail().withMessage("Invalid contact email");
        optionalUrl(checks, "logo");
        optionalUrl(checks, "coverImage");
        checks.field("tagline").optionalNull().isString().trim().isLength(0, 140).withMessage("Tagline must be at most 140 characters");
        checks.field("website").optionalNull().isString().isLength(0, 300).withMessage("Website link is too long");
        checks.field("contactPhone").optionalNull().isString().trim().isLength(0, 20).withMessage("Phone number is too long");
        checks.field("meetingSchedule").optionalNull().isString().trim().isLength(0, 120).withMessage("Meeting schedule must be at most 120 characters");
        checks.field("meetingLocation").optionalNull().isString().trim().isLength(0, 120).withMessage("Meeting place must be at most 120 characters");
        checks.field("socialLinks").optionalNull().isObject().withMessage("Social links must be an object");
        checks.validate();
        return ApiResponse.ok("Club updated", clubs.updateClub(actor, id, checks.body()));
    }

    /** optionalUrl(field): an http(s) URL or an uploaded file ("" clears it). */
    static void optionalUrl(RequestChecks checks, String field) {
        checks.field(field).optionalNull().isString()
                .check(value -> "".equals(value) || URL_OR_UPLOAD.matcher(JsValues.string(value)).find())
                .withMessage(field + " must be an http(s) URL or an uploaded file");
    }

    // ---------------------------------------------------------------- the bell

    @GetMapping("/{id}/subscription")
    public ApiResponse<SubscriptionService.Summary> getSubscription(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Club notifications fetched", subscriptions.getSubscription(actor, ObjectIds.require(id, "id")));
    }

    @PutMapping("/{id}/subscription")
    public ApiResponse<SubscriptionService.Summary> setSubscription(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("enabled").isBoolean().withMessage("enabled must be true or false");
        checks.validate();
        // Boolean(enabled), like Node: the validated value decides both the active-club check and what is stored.
        SubscriptionService.Summary summary = subscriptions.setSubscription(actor, id, JsValues.truthy(checks.body().get("enabled")));
        return ApiResponse.ok(summary.subscribed() ? "Notifications turned on" : "Notifications turned off", summary);
    }

    // ---------------------------------------------------------------- governance

    @RequireRole(GlobalRole.ADMIN)
    @PostMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> changeStatus(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("status").isIn(STATUSES).withMessage("Choose active, suspended or archived");
        checks.field("reason").optional().isString().trim().isLength(0, 1000);
        checks.validate();
        ClubStatus status = ClubStatus.valueOf(JsValues.string(checks.body().get("status")));
        Object reason = checks.body().get("reason");
        return ApiResponse.ok("Club status updated", statuses.changeClubStatus(actor, id, status, reason == null ? null : JsValues.string(reason)));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PutMapping("/{id}/mentor")
    public ApiResponse<Map<String, Object>> setMentor(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id).mongoId("mentorId");
        checks.validate();
        return ApiResponse.ok("Faculty mentor assigned", clubs.setMentor(actor, id, text(checks, "mentorId")));
    }

    @PostMapping("/{id}/president")
    public ApiResponse<Map<String, Object>> assignPresident(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id).mongoId("userId");
        checks.validate();
        return ApiResponse.ok("President appointed", clubs.assignPresident(actor, id, text(checks, "userId")));
    }

    // ---------------------------------------------------------------- members

    @GetMapping("/{id}/members")
    public ApiResponse<List<Map<String, Object>>> listMembers(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        List<Map<String, Object>> members = queries.listClubMembers(actor, ObjectIds.require(id, "id"));
        return ApiResponse.ok("Club members fetched", members, Map.of("total", members.size()));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addMember(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id).mongoId("userId");
        checks.validate();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Member added", memberships.addMember(actor, id, text(checks, "userId"))));
    }

    @PatchMapping("/{id}/members/{userId}/role")
    public ApiResponse<Map<String, Object>> changeMemberRole(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id).param("userId", userId);
        checks.field("role").isString().trim().isLength(1, 60).withMessage("Choose a role");
        checks.validate();
        return ApiResponse.ok("Member role updated", memberships.changeMemberRole(actor, id, userId, JsValues.string(checks.body().get("role"))));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String userId) {
        RequestChecks.of(null).param("id", id).param("userId", userId).validate();
        memberships.removeMember(actor, id, userId);
        return ApiResponse.ok("Member removed");
    }

    @PostMapping("/{id}/leave")
    public ApiResponse<Void> leaveClub(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        memberships.leaveClub(actor, ObjectIds.require(id, "id"));
        return ApiResponse.ok("You left the club");
    }

    // ---------------------------------------------------------------- the club's roles and the presidency

    @GetMapping("/{id}/roles")
    public ApiResponse<ClubRoleService.RoleList> listRoles(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Club roles fetched", roles.listRoles(actor, ObjectIds.require(id, "id")));
    }

    @PostMapping("/{id}/roles")
    public ResponseEntity<ApiResponse<ClubRoleService.RoleList>> createRole(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = roleChecks(body, id, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Role created", roles.createRole(actor, id, checks.body())));
    }

    @PutMapping("/{id}/roles/{key}")
    public ApiResponse<ClubRoleService.RoleList> updateRole(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String key,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = roleChecks(body, id, false);
        return ApiResponse.ok("Role updated", roles.updateRole(actor, id, key, checks.body()));
    }

    @DeleteMapping("/{id}/roles/{key}")
    public ApiResponse<ClubRoleService.RoleList> deleteRole(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String key) {
        return ApiResponse.ok("Role deleted — its members are now members", roles.deleteRole(actor, ObjectIds.require(id, "id"), key));
    }

    @PostMapping("/{id}/president/transfer")
    public ApiResponse<ClubRoleService.Handover> transferPresidency(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id).mongoId("userId");
        checks.validate();
        ClubRoleService.Handover result = roles.transferPresidency(actor, id, text(checks, "userId"));
        return ApiResponse.ok(result.president().get("name") + " is now president", result);
    }

    /** A validated body value as text. */
    static String text(RequestChecks checks, String field) {
        Object value = checks.body().get(field);
        return value == null ? null : JsValues.string(value);
    }

    /** clubRoleRules(creating): a name and the authorities are required when creating. */
    private static RequestChecks roleChecks(Map<String, Object> body, String id, boolean creating) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        RequestChecks.Chain name = checks.field("name");
        if (!creating) {
            name.optional();
        }
        name.isString().trim().isLength(2, 40).withMessage("Role names are 2 to 40 characters");
        checks.field("description").optional().isString().isLength(0, 200);
        RequestChecks.Chain permissions = checks.field("permissions");
        if (!creating) {
            permissions.optional();
        }
        permissions.isArray(20).withMessage("Choose the role's authorities");
        checks.validate();
        return checks;
    }
}
