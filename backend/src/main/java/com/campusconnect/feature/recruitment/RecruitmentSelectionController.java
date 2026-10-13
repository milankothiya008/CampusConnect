package com.campusconnect.feature.recruitment;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.FinalizeBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.OutcomesBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.RoundBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.ScheduleBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.SlotBody;
import com.campusconnect.feature.recruitment.dto.SelectionViews.ScheduledSelection;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Selection;
import com.campusconnect.feature.recruitment.selection.RoundService;
import com.campusconnect.feature.recruitment.selection.SelectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/recruitment/:id/positions/:positionId selection: rounds, interview slots, results, final selection, offers. */
@RestController
@RequestMapping("/api/recruitment/{id}/positions/{positionId}")
public class RecruitmentSelectionController {

    private final RoundService rounds;
    private final SelectionService selection;

    public RecruitmentSelectionController(RoundService rounds, SelectionService selection) {
        this.rounds = rounds;
        this.selection = selection;
    }

    private static String id(String id) {
        return ObjectIds.require(id, "id");
    }

    private static String positionId(String positionId) {
        return ObjectIds.require(positionId, "positionId");
    }

    private static String roundId(String roundId) {
        return ObjectIds.require(roundId, "roundId");
    }

    @GetMapping("/rounds")
    public ApiResponse<Selection> rounds(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId) {
        return ApiResponse.ok("Selection fetched", rounds.getRounds(actor, id(id), positionId(positionId)));
    }

    @PostMapping("/rounds")
    public ResponseEntity<ApiResponse<Selection>> createRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @Valid @RequestBody RoundBody body) {
        Selection result = rounds.createRound(actor, id(id), positionId(positionId), body.name(), body.mode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Round added", result));
    }

    @PutMapping("/rounds/{roundId}/schedule")
    public ApiResponse<ScheduledSelection> scheduleRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @PathVariable String roundId, @Valid @RequestBody ScheduleBody body) {
        if (!RecruitmentRequests.slotMinutesValid(body.slotMinutes())) {
            throw AppException.badRequest("Invalid value");
        }
        return ApiResponse.ok("Round scheduled — candidates are being invited", rounds.scheduleRound(actor, id(id), positionId(positionId), roundId(roundId), body));
    }

    @PatchMapping("/rounds/{roundId}/slots/{applicationId}")
    public ApiResponse<ScheduledSelection> updateSlot(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @PathVariable String roundId, @PathVariable String applicationId, @Valid @RequestBody SlotBody body) {
        return ApiResponse.ok("Slot moved — the candidate has been told", rounds.updateSlot(actor, id(id), positionId(positionId), roundId(roundId),
                ObjectIds.require(applicationId, "applicationId"), body.startAt()));
    }

    @PutMapping("/rounds/{roundId}/outcomes")
    public ApiResponse<Selection> setOutcomes(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @PathVariable String roundId, @Valid @RequestBody OutcomesBody body) {
        return ApiResponse.ok("Results saved", rounds.setOutcomes(actor, id(id), positionId(positionId), roundId(roundId), body.decisions()));
    }

    @PostMapping("/rounds/{roundId}/publish")
    public ApiResponse<Selection> publishRound(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @PathVariable String roundId) {
        return ApiResponse.ok("Results published — every candidate is being emailed", rounds.publishRoundResults(actor, id(id), positionId(positionId), roundId(roundId)));
    }

    @PostMapping("/finalize")
    public ApiResponse<Selection> finalizePosition(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @Valid @RequestBody FinalizeBody body) {
        if (!RecruitmentRequests.offerDaysValid(body.offerDays())) {
            throw AppException.badRequest("Give 1 to 14 days to answer");
        }
        return ApiResponse.ok("Final selection sent — offers are on their way",
                selection.finalizePosition(actor, id(id), positionId(positionId), body.decisions(), body.offerDays()));
    }

    @PostMapping("/offers/{applicationId}")
    public ApiResponse<Selection> offerToReserve(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @PathVariable String applicationId) {
        return ApiResponse.ok("Offer sent", selection.offerToReserve(actor, id(id), positionId(positionId), ObjectIds.require(applicationId, "applicationId")));
    }
}
