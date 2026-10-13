package com.campusconnect.feature.user;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.RequireRole;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.feature.admin.AdminService;
import com.campusconnect.feature.club.JsValues;
import com.campusconnect.feature.club.RequestChecks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/users (routes/UserRoutes.js): signed-in, verified users; the account list and status changes are for the admin. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Pattern DEPARTMENTS = Pattern.compile("^[A-Za-z]{2,4}(,[A-Za-z]{2,4})*$");

    private final UserService users;
    private final RecordService records;
    private final AdminService admin;

    public UserController(UserService users, RecordService records, AdminService admin) {
        this.users = users;
        this.records = records;
        this.admin = admin;
    }

    @RequireRole(GlobalRole.ADMIN)
    @GetMapping
    public ApiResponse<List<AuthUser>> list(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String active,
            @RequestParam(required = false) String search) {
        return ApiResponse.page("Users fetched", admin.listUsers(actor, page, limit, role, accountType, department, active, search));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String departments) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("q", q);
        parameters.put("accountType", accountType);
        parameters.put("departments", departments);
        RequestChecks checks = RequestChecks.ofQuery(parameters);
        checks.field("q").isString().trim().isLength(2, 80).withMessage("Enter at least 2 characters");
        checks.field("accountType").optional().isIn(List.of("STUDENT", "FACULTY"));
        checks.field("departments").optional().matches(DEPARTMENTS).withMessage("Invalid departments");
        checks.validate();
        return ApiResponse.ok("Users found", users.searchUsers(JsValues.string(checks.body().get("q")), accountType, departments));
    }

    /** People search (Explore). */
    @GetMapping("/people")
    public ApiResponse<List<Map<String, Object>>> people(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String limit) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("q", q);
        RequestChecks checks = RequestChecks.ofQuery(parameters);
        checks.field("q").optional().isString().isLength(0, 80);
        checks.validate();
        return ApiResponse.ok("People found", users.searchPeople(actor, q, department, limit));
    }

    /** The student's own participation record as a PDF download. */
    @GetMapping("/me/record.pdf")
    public ResponseEntity<byte[]> myRecord(@AuthenticationPrincipal AuthUser actor) {
        RecordService.RecordFile file = records.recordPdf(actor);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(file.pdf());
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<UserService.PublicProfile> profile(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Profile fetched", users.getPublicProfile(actor, ObjectIds.require(id, "id")));
    }

    @GetMapping("/{id}")
    public ApiResponse<AuthUser> get(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("User fetched", admin.getUserById(actor, ObjectIds.require(id, "id")));
    }

    @PutMapping("/{id}")
    public ApiResponse<AuthUser> update(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("name").optional().isString().trim().isLength(2, 80).withMessage("Name must be 2-80 characters");
        checks.field("avatar").optionalNull().isString().isLength(0, 500);
        checks.field("phone").optionalNull().isString().isLength(0, 20).withMessage("Enter a valid 10-digit Indian mobile number");
        checks.validate();
        return ApiResponse.ok("Profile updated", users.updateUser(actor, id, checks.body()));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PatchMapping("/{id}/status")
    public ApiResponse<AuthUser> setStatus(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = RequestChecks.of(body).param("id", id);
        checks.field("isActive").isBoolean().withMessage("isActive must be true or false");
        checks.validate();
        AuthUser user = admin.setUserActive(actor, id, checks.body().get("isActive"));
        return ApiResponse.ok(user.active() ? "Account activated" : "Account deactivated", user);
    }
}
