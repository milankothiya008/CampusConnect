package com.campusconnect.feature.clubrequest;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.RequireRole;
import com.campusconnect.core.util.Categories;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.feature.club.JsValues;
import com.campusconnect.feature.club.RequestChecks;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/club-requests (routes/ClubRequestRoutes.js): signed-in, verified users only; each step limited to its role. */
@RestController
@RequestMapping("/api/club-requests")
public class ClubRequestController {

    private final ClubRequestService requests;

    public ClubRequestController(ClubRequestService requests) {
        this.requests = requests;
    }

    @RequireRole(GlobalRole.STUDENT)
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body);
        requestFields(checks, false);
        checks.validate();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Club request submitted for faculty review", requests.submitRequest(actor, checks.body())));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String status) {
        return ApiResponse.page("Club requests fetched", requests.listRequests(actor, page, limit, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Club request fetched", requests.getRequestById(actor, ObjectIds.require(id, "id")));
    }

    @RequireRole(GlobalRole.STUDENT)
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        requestFields(checks, true);
        checks.validate();
        return ApiResponse.ok("Club request updated", requests.updateRequest(actor, id, checks.body()));
    }

    @RequireRole(GlobalRole.STUDENT)
    @PostMapping("/{id}/resubmit")
    public ApiResponse<Map<String, Object>> resubmit(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Club request resubmitted for faculty review", requests.resubmitRequest(actor, ObjectIds.require(id, "id")));
    }

    @RequireRole(GlobalRole.FACULTY)
    @PostMapping("/{id}/verify")
    public ApiResponse<Map<String, Object>> verify(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("comment").optional().isString().trim().isLength(0, 2000);
        checks.validate();
        return ApiResponse.ok("Club request verified and sent to the university admin", requests.verifyRequest(actor, id, text(checks, "comment")));
    }

    @RequireRole(GlobalRole.FACULTY)
    @PostMapping("/{id}/request-changes")
    public ApiResponse<Map<String, Object>> requestChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("comment").isString().trim().isLength(5, 2000).withMessage("Please describe the required changes (min 5 characters)");
        checks.validate();
        return ApiResponse.ok("Changes requested", requests.requestChanges(actor, id, text(checks, "comment")));
    }

    @RequireRole({GlobalRole.FACULTY, GlobalRole.ADMIN})
    @PostMapping("/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("reason").isString().trim().isLength(5, 2000).withMessage("A rejection reason is required (min 5 characters)");
        checks.validate();
        return ApiResponse.ok("Club request rejected", requests.rejectRequest(actor, id, text(checks, "reason")));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PostMapping("/{id}/approve")
    public ApiResponse<ClubRequestService.Approval> approve(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Club approved and created", requests.approveRequest(actor, ObjectIds.require(id, "id")));
    }

    private static String text(RequestChecks checks, String field) {
        Object value = checks.body().get(field);
        return value == null ? null : JsValues.string(value);
    }

    /** clubRequestFields(optional): every field is optional when editing. */
    static void requestFields(RequestChecks checks, boolean optional) {
        text(checks, "name", optional, 3, 120, "Club name must be 3-120 characters");
        text(checks, "description", optional, 10, 4000, "Description must be 10-4000 characters");
        text(checks, "purpose", optional, 10, 2000, "Purpose must be 10-2000 characters");
        text(checks, "proposedActivities", optional, 10, 4000, "Proposed activities must be 10-4000 characters");
        text(checks, "reason", optional, 10, 2000, "Reason must be 10-2000 characters");
        checks.field("allDepartments").optional().isBoolean().withMessage("allDepartments must be true or false").toBoolean();
        checks.field("departmentCodes").optional().isArray(30).withMessage("departmentCodes must be a list");
        checks.field("departmentCodes.*").isString().trim().isLength(2, 4).withMessage("Invalid department code");
        RequestChecks.Chain category = checks.field("category");
        if (optional) {
            category.optional();
        }
        category.isIn(Categories.CLUB_CATEGORIES).withMessage("Invalid category");
        checks.field("foundingMemberEmails").optional().isArray(20).withMessage("Up to 20 founding members are allowed");
        checks.field("foundingMemberEmails.*").optional().isEmail().withMessage("Founding members must be university emails");
        checks.field("proposedMentor").optionalFalsy().isMongoId().withMessage("Invalid proposed mentor");
    }

    private static void text(RequestChecks checks, String field, boolean optional, int min, int max, String message) {
        RequestChecks.Chain chain = checks.field(field);
        if (optional) {
            chain.optional();
        }
        chain.isString().trim().isLength(min, max).withMessage(message);
    }
}
