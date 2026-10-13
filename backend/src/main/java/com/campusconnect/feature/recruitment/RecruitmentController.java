package com.campusconnect.feature.recruitment;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.recruitment.drive.DriveService;
import com.campusconnect.feature.recruitment.dto.DriveViews.DriveView;
import com.campusconnect.feature.recruitment.dto.DriveViews.OpenDriveItem;
import com.campusconnect.feature.recruitment.dto.DriveViews.ReviewItem;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.CancelBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.CommentBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.DeadlineBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.RequiredCommentBody;
import com.campusconnect.feature.recruitment.selection.SelectionService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/recruitment drives: discovery, the drive page and its lifecycle (routes/RecruitmentRoutes.js). */
@RestController
@RequestMapping("/api/recruitment")
public class RecruitmentController {

    private final DriveService drives;
    private final SelectionService selection;

    public RecruitmentController(DriveService drives, SelectionService selection) {
        this.drives = drives;
        this.selection = selection;
    }

    private static String id(String id) {
        return ObjectIds.require(id, "id");
    }

    @OptionalAuth
    @GetMapping
    public ApiResponse<List<OpenDriveItem>> listOpen(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Recruitment fetched", drives.listOpenDrives(actor));
    }

    @GetMapping("/review")
    public ApiResponse<List<ReviewItem>> listToReview(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Drives to review fetched", drives.listDrivesToReview(actor));
    }

    @OptionalAuth
    @GetMapping("/{id}")
    public ApiResponse<DriveView> get(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Recruitment drive fetched", drives.getDrive(actor, id(id)));
    }

    @PutMapping("/{id}")
    public ApiResponse<DriveView> update(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        List<String> problems = new ArrayList<>();
        if (!ObjectIds.isValid(id)) {
            problems.add("Invalid id");
        }
        problems.addAll(RecruitmentRequests.driveProblems(body));
        if (!problems.isEmpty()) {
            throw AppException.badRequest(String.join(". ", problems));
        }
        return ApiResponse.ok("Recruitment drive saved", drives.updateDrive(actor, id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        drives.deleteDraft(actor, id(id));
        return ApiResponse.ok("Draft deleted");
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<DriveView> submit(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Sent to your faculty mentor for approval", drives.submitDrive(actor, id(id)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<DriveView> approve(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody(required = false) CommentBody body) {
        return ApiResponse.ok("Recruitment approved", drives.approveDrive(actor, id(id), body == null ? null : body.comment()));
    }

    @PostMapping("/{id}/request-changes")
    public ApiResponse<DriveView> requestChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody RequiredCommentBody body) {
        return ApiResponse.ok("Changes requested", drives.requestDriveChanges(actor, id(id), body.comment()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<DriveView> reject(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody RequiredCommentBody body) {
        return ApiResponse.ok("Recruitment rejected", drives.rejectDrive(actor, id(id), body.comment()));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<DriveView> publish(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Recruitment published — eligible students are being notified", drives.publishDrive(actor, id(id)));
    }

    @PutMapping("/{id}/deadline")
    public ApiResponse<DriveView> extend(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody DeadlineBody body) {
        return ApiResponse.ok("Deadline updated", drives.extendDeadline(actor, id(id), body.applicationEnd()));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<DriveView> close(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Applications closed", drives.closeApplications(actor, id(id)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<DriveView> cancel(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody(required = false) CancelBody body) {
        return ApiResponse.ok("Recruitment cancelled", drives.cancelDrive(actor, id(id), body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<DriveView> complete(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Recruitment completed — everyone still waiting has been told", selection.closeRecruitment(actor, id(id)));
    }
}
