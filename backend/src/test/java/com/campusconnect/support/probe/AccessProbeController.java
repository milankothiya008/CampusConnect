package com.campusconnect.support.probe;

import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthenticatedOnly;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.security.RequireRole;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.domain.user.GlobalRole;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only routes, one per access level, to check the security layer in isolation. */
@RestController
@RequestMapping("/api/test-probe")
public class AccessProbeController {

    private static ApiResponse<Map<String, Object>> who(AuthUser actor) {
        Map<String, Object> data = new HashMap<>();
        data.put("user", actor == null ? null : actor.id());
        return ApiResponse.ok("ok", data);
    }

    @PublicApi
    @GetMapping("/public")
    public ApiResponse<Map<String, Object>> publicRoute(@AuthenticationPrincipal AuthUser actor) {
        return who(actor);
    }

    @OptionalAuth
    @GetMapping("/optional")
    public ApiResponse<Map<String, Object>> optionalRoute(@AuthenticationPrincipal AuthUser actor) {
        return who(actor);
    }

    @AuthenticatedOnly
    @GetMapping("/authenticated")
    public ApiResponse<Map<String, Object>> authenticatedRoute(@AuthenticationPrincipal AuthUser actor) {
        return who(actor);
    }

    @GetMapping("/verified")
    public ApiResponse<Map<String, Object>> verifiedRoute(@AuthenticationPrincipal AuthUser actor) {
        return who(actor);
    }

    @RequireRole(GlobalRole.ADMIN)
    @GetMapping("/admin")
    public ApiResponse<Map<String, Object>> adminRoute(@AuthenticationPrincipal AuthUser actor) {
        return who(actor);
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/auth-limited")
    public ApiResponse<Map<String, Object>> authLimited() {
        return who(null);
    }

    @PostMapping("/echo")
    public ApiResponse<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok("ok", body);
    }
}
