package com.campusconnect.feature.team;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.team.dto.TeamBodies;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teams on team events (controllers/TeamController.js): the leader invites and manages members; invitees accept or
 * decline. Also /api/registrations/invites, a student's pending invites.
 */
@RestController
public class TeamController {

    private final TeamService teamService;
    private final Teams teams;

    public TeamController(TeamService teamService, Teams teams) {
        this.teamService = teamService;
        this.teams = teams;
    }

    @GetMapping("/api/events/{id}/team/candidates")
    public ApiResponse<List<Map<String, Object>>> candidates(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestParam(required = false) String search) {
        return ApiResponse.ok("Students fetched", teamService.searchCandidates(actor, ObjectIds.require(id, "id"), search));
    }

    @PostMapping("/api/events/{id}/team/invites")
    public ApiResponse<Map<String, Object>> invite(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody TeamBodies.Invite body) {
        return ApiResponse.ok("Invites sent", teamService.inviteMembers(actor, ObjectIds.require(id, "id"), body.users()));
    }

    @DeleteMapping("/api/events/{id}/team/members/{userId}")
    public ApiResponse<Map<String, Object>> removeMember(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String userId) {
        ObjectIds.require(id, "id");
        ObjectIds.require(userId, "userId");
        return ApiResponse.ok("Team updated", teamService.removeMember(actor, id, userId));
    }

    @PostMapping("/api/events/{id}/teams/{teamId}/accept")
    public ApiResponse<Map<String, Object>> accept(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String teamId,
            @Valid @RequestBody(required = false) TeamBodies.Accept body) {
        ObjectIds.require(id, "id");
        ObjectIds.require(teamId, "teamId");
        TeamBodies.Accept given = body == null ? TeamBodies.Accept.EMPTY : body;
        Map<String, Object> result = teamService.respondToInvite(actor, id, teamId, true, given.answers(), given.replace());
        return ApiResponse.ok(Boolean.TRUE.equals(result.get("waitlisted")) ? "You joined the team. The team is on the waitlist." : "You joined the team and are registered", result);
    }

    @PostMapping("/api/events/{id}/teams/{teamId}/decline")
    public ApiResponse<Map<String, Object>> decline(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String teamId) {
        ObjectIds.require(id, "id");
        ObjectIds.require(teamId, "teamId");
        return ApiResponse.ok("Invite declined", teamService.respondToInvite(actor, id, teamId, false, null, null));
    }

    @GetMapping("/api/registrations/invites")
    public ApiResponse<List<Map<String, Object>>> myInvites(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Team invites fetched", teams.invitesFor(actor, null));
    }
}
