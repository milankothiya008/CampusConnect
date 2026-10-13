package com.campusconnect.feature.result;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.result.ResultDtos.Board;
import com.campusconnect.feature.result.ResultDtos.NewRoundBody;
import com.campusconnect.feature.result.ResultDtos.ResultBody;
import com.campusconnect.feature.result.ResultDtos.ResultCard;
import com.campusconnect.feature.result.ResultDtos.ResultView;
import com.campusconnect.feature.result.ResultDtos.RoundChangesBody;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Results: the public Results page (routes/ResultRoutes.js, {@code /api/results}) and each event's rounds and final
 * results (the results routes of routes/EventRoutes.js under {@code /api/events/:id/results}).
 */
@RestController
public class ResultController {

    private final ResultService results;
    private final ResultBoard board;

    public ResultController(ResultService results, ResultBoard board) {
        this.results = results;
        this.board = board;
    }

    @OptionalAuth
    @GetMapping("/api/results")
    public ApiResponse<List<ResultCard>> listPublished(@RequestParam(required = false) String club, @RequestParam(required = false) String search,
            @RequestParam(required = false) String stage, @RequestParam(required = false) String page, @RequestParam(required = false) String limit) {
        if (club != null && !ObjectIds.isValid(club)) {
            throw AppException.badRequest("Invalid value");
        }
        Pagination pagination = Pagination.of(page, limit, 12);
        String normalizedStage = "final".equals(stage) || "live".equals(stage) ? stage : "";
        // Only the first 100 characters ever reach the search pattern (utils/Query.js searchRegex); this also bounds the cache key.
        String text = search == null ? "" : search.strip();
        Board listing = board.list(club == null ? "" : club.toLowerCase(Locale.ROOT), text.length() > 100 ? text.substring(0, 100) : text, normalizedStage,
                pagination.page(), pagination.limit());
        return ApiResponse.ok("Published results fetched", listing.items(), listing.meta());
    }

    @OptionalAuth
    @GetMapping("/api/events/{id}/results")
    public ApiResponse<ResultView> getOne(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Results fetched", results.getResult(actor, PathIds.require("id", id)));
    }

    @PutMapping("/api/events/{id}/results")
    public ApiResponse<ResultView> upsert(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody ResultBody body) {
        ResultView result = results.upsertResult(actor, PathIds.require("id", id), body);
        return ApiResponse.ok(result.status() == ResultStatus.PUBLISHED ? "Published results corrected" : "Final results draft saved", result);
    }

    @PostMapping("/api/events/{id}/results/publish")
    public ApiResponse<ResultView> publish(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Final results published", results.publishResult(actor, PathIds.require("id", id)));
    }

    @PostMapping("/api/events/{id}/results/rounds")
    public ResponseEntity<ApiResponse<ResultView>> createRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody NewRoundBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Round created", results.createRound(actor, PathIds.require("id", id), body)));
    }

    @PutMapping("/api/events/{id}/results/rounds/{roundId}")
    public ApiResponse<ResultView> updateRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String roundId,
            @Valid @RequestBody RoundChangesBody body) {
        String[] valid = PathIds.require("id", id, "roundId", roundId);
        return ApiResponse.ok("Round saved", results.updateRound(actor, valid[0], valid[1], body));
    }

    @DeleteMapping("/api/events/{id}/results/rounds/{roundId}")
    public ApiResponse<ResultView> deleteRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String roundId) {
        String[] valid = PathIds.require("id", id, "roundId", roundId);
        return ApiResponse.ok("Round deleted", results.deleteRound(actor, valid[0], valid[1]));
    }

    @PostMapping("/api/events/{id}/results/rounds/{roundId}/publish")
    public ApiResponse<ResultView> publishRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String roundId) {
        String[] valid = PathIds.require("id", id, "roundId", roundId);
        return ApiResponse.ok("Round results published", results.publishRound(actor, valid[0], valid[1]));
    }

    @PostMapping("/api/events/{id}/results/rounds/{roundId}/unpublish")
    public ApiResponse<ResultView> unpublishRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String roundId) {
        String[] valid = PathIds.require("id", id, "roundId", roundId);
        return ApiResponse.ok("Round results withdrawn", results.unpublishRound(actor, valid[0], valid[1]));
    }
}
