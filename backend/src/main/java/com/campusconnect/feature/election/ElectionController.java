package com.campusconnect.feature.election;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.election.ElectionViews.ElectionList;
import com.campusconnect.feature.election.ElectionViews.ElectionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/elections (routes/ElectionRoutes.js): anonymous votes among a club's members for its roles. */
@RestController
@RequestMapping("/api/elections")
public class ElectionController {

    private static final String OK = "OK";

    private final ElectionService elections;

    public ElectionController(ElectionService elections) {
        this.elections = elections;
    }

    private static String id(String value) {
        return ObjectIds.require(value, "election");
    }

    @GetMapping
    public ApiResponse<ElectionList> list(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) @NotNull(message = "Choose a club") @Pattern(regexp = ElectionRequests.OBJECT_ID, message = "Choose a club") String club) {
        return ApiResponse.ok(OK, elections.listElections(actor, club));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ElectionView>> create(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody ElectionRequests.CreateBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Election created", elections.createElection(actor, body)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ElectionView> get(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String electionId) {
        return ApiResponse.ok(OK, elections.getElection(actor, id(electionId)));
    }

    /** A JSON map: {@code opensAt: null} ("open it now") differs from leaving it out. */
    @PatchMapping("/{id}")
    public ApiResponse<ElectionView> update(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String electionId,
            @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(OK, elections.updateElection(actor, id(electionId), body == null ? Map.of() : body));
    }

    @PostMapping("/{id}/vote")
    public ApiResponse<ElectionView> vote(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String electionId, @Valid @RequestBody ElectionRequests.VoteBody body) {
        return ApiResponse.ok("Vote recorded", elections.vote(actor, id(electionId), body.candidate()));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<ElectionView> close(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String electionId) {
        return ApiResponse.ok(OK, elections.closeElection(actor, id(electionId)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ElectionView> cancel(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String electionId,
            @Valid @RequestBody(required = false) ElectionRequests.CancelBody body) {
        return ApiResponse.ok(OK, elections.cancelElection(actor, id(electionId), body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/runoff")
    public ResponseEntity<ApiResponse<ElectionView>> runoff(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String electionId,
            @Valid @RequestBody(required = false) ElectionRequests.DetailsBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Runoff created", elections.startRunoff(actor, id(electionId), body)));
    }
}
