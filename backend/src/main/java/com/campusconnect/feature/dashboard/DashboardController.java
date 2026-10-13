package com.campusconnect.feature.dashboard;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/dashboard (routes/DashboardRoutes.js): signed in with a verified e-mail. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboards;

    public DashboardController(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getDashboard(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Dashboard fetched", dashboards.getDashboard(actor));
    }
}
