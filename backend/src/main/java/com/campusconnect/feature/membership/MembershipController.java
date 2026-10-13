package com.campusconnect.feature.membership;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.club.ClubQueries;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/memberships (routes/MembershipRoutes.js). */
@RestController
@RequestMapping("/api/memberships")
public class MembershipController {

    private final ClubQueries clubs;
    private final MembershipService memberships;

    public MembershipController(ClubQueries clubs, MembershipService memberships) {
        this.clubs = clubs;
        this.memberships = memberships;
    }

    @GetMapping("/me")
    public ApiResponse<ClubQueries.MyClubs> myMemberships(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Your memberships fetched", clubs.getMyClubs(actor));
    }

    @GetMapping("/users/{userId}/clubs")
    public ApiResponse<List<Map<String, Object>>> userClubs(@AuthenticationPrincipal AuthUser actor, @PathVariable String userId) {
        List<Map<String, Object>> rows = memberships.getUserClubs(actor, ObjectIds.require(userId, "userId"));
        return ApiResponse.ok("User clubs fetched", rows, Map.of("total", rows.size()));
    }
}
