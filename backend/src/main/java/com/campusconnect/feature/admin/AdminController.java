package com.campusconnect.feature.admin;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.security.RequireRole;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.domain.academic.AcademicBatch;
import com.campusconnect.domain.academic.Department;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.feature.club.RequestChecks;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
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

/**
 * /api/admin (routes/AdminRoutes.js). Departments and batches are public reference data (registration and event
 * forms read them, browsers may reuse them for a minute); everything else is for the university admin.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** {@code cacheFor(60)}: public, a minute fresh, ten minutes served stale while revalidating. */
    static final String REFERENCE_CACHE = "public, max-age=60, stale-while-revalidate=600";
    private static final Pattern BATCH_CODE = Pattern.compile("^\\d{2}$");

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @PublicApi
    @GetMapping("/departments")
    public ResponseEntity<ApiResponse<List<Department>>> listDepartments(@RequestParam(required = false) String active) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, REFERENCE_CACHE).body(ApiResponse.ok("Departments fetched", admin.listDepartments(active)));
    }

    @PublicApi
    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<AcademicBatch>>> listBatches(@RequestParam(required = false) String active) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, REFERENCE_CACHE).body(ApiResponse.ok("Academic batches fetched", admin.listBatches(active)));
    }

    @RequireRole(GlobalRole.ADMIN)
    @GetMapping("/stats")
    public ApiResponse<AdminService.Stats> stats(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Platform statistics fetched", admin.getStats(actor));
    }

    @RequireRole(GlobalRole.ADMIN)
    @GetMapping("/faculty")
    public ApiResponse<List<Map<String, Object>>> listFaculty(@AuthenticationPrincipal AuthUser actor, @RequestParam(required = false) String search) {
        return ApiResponse.ok("Faculty fetched", admin.listFaculty(actor, search));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PostMapping("/departments")
    public ResponseEntity<ApiResponse<Department>> createDepartment(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = departmentChecks(RequestChecks.of(body), false);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Department created", admin.createDepartment(actor, checks.body())));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PutMapping("/departments/{id}")
    public ApiResponse<Department> updateDepartment(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = departmentChecks(RequestChecks.of(body).param("id", id), true);
        return ApiResponse.ok("Department updated", admin.updateDepartment(actor, id, checks.body()));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PostMapping("/batches")
    public ResponseEntity<ApiResponse<AcademicBatch>> createBatch(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = batchChecks(RequestChecks.of(body), false);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Academic batch created", admin.createBatch(actor, checks.body())));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PutMapping("/batches/{id}")
    public ApiResponse<AcademicBatch> updateBatch(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = batchChecks(RequestChecks.of(body).param("id", id), true);
        return ApiResponse.ok("Academic batch updated", admin.updateBatch(actor, id, checks.body()));
    }

    /** departmentRules(optional). */
    private static RequestChecks departmentChecks(RequestChecks checks, boolean optional) {
        RequestChecks.Chain code = checks.field("code");
        RequestChecks.Chain name = checks.field("name");
        if (optional) {
            code.optional();
            name.optional();
        }
        code.isString().trim().isLength(2, 4).isAlpha().withMessage("Code must be 2-4 letters");
        name.isString().trim().isLength(2, 120).withMessage("Name is required");
        checks.field("isActive").optional().isBoolean();
        checks.validate();
        return checks;
    }

    /** batchRules(optional). */
    private static RequestChecks batchChecks(RequestChecks checks, boolean optional) {
        RequestChecks.Chain code = checks.field("code");
        RequestChecks.Chain label = checks.field("label");
        if (optional) {
            code.optional();
            label.optional();
        }
        code.matches(BATCH_CODE).withMessage("Batch code must be 2 digits");
        label.isString().trim().isLength(2, 60).withMessage("Label is required");
        checks.field("isActive").optional().isBoolean();
        checks.validate();
        return checks;
    }
}
