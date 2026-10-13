package com.campusconnect.feature.recruitment;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.recruitment.application.ApplicationService;
import com.campusconnect.feature.recruitment.application.OfferService;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.LocalUpload;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplication;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplicationRow;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.StaffApplicationDetail;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.StaffApplicationRow;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.AcceptBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.AnswersBody;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.UploadsBody;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /api/recruitment applications: applying to a role (one application each), offers, uploads, and the club's view of
 * the applications (routes/RecruitmentRoutes.js).
 */
@RestController
@RequestMapping("/api/recruitment")
public class RecruitmentApplicationController {

    private final ApplicationService applications;
    private final OfferService offers;

    public RecruitmentApplicationController(ApplicationService applications, OfferService offers) {
        this.applications = applications;
        this.offers = offers;
    }

    private static String id(String id) {
        return ObjectIds.require(id, "id");
    }

    private static String positionId(String positionId) {
        return ObjectIds.require(positionId, "positionId");
    }

    private static String applicationId(String applicationId) {
        return ObjectIds.require(applicationId, "applicationId");
    }

    @GetMapping("/mine")
    public ApiResponse<List<MyApplicationRow>> mine(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Your applications fetched", applications.myApplications(actor));
    }

    @GetMapping("/{id}/applications/mine")
    public ApiResponse<List<MyApplication>> myApplications(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Your applications fetched", applications.getMyApplications(actor, id(id)));
    }

    @GetMapping("/{id}/positions/{positionId}/application")
    public ApiResponse<MyApplication> myApplication(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId) {
        return ApiResponse.ok("Your application fetched", applications.getMyApplication(actor, id(id), positionId(positionId)));
    }

    @PostMapping("/{id}/positions/{positionId}/application")
    public ResponseEntity<ApiResponse<MyApplication>> apply(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @Valid @RequestBody(required = false) AnswersBody body) {
        MyApplication application = applications.apply(actor, id(id), positionId(positionId), body == null ? null : body.answers());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Application submitted", application));
    }

    @PutMapping("/{id}/positions/{positionId}/application")
    public ApiResponse<MyApplication> updateApplication(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId,
            @Valid @RequestBody(required = false) AnswersBody body) {
        return ApiResponse.ok("Application updated", applications.updateMyApplication(actor, id(id), positionId(positionId), body == null ? null : body.answers()));
    }

    @DeleteMapping("/{id}/positions/{positionId}/application")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String positionId) {
        applications.withdraw(actor, id(id), positionId(positionId));
        return ApiResponse.ok("Application withdrawn");
    }

    @PostMapping("/{id}/applications/{applicationId}/accept")
    public ApiResponse<List<MyApplication>> accept(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String applicationId,
            @RequestBody(required = false) AcceptBody body) {
        return ApiResponse.ok("Offer accepted — welcome to the club!", offers.accept(actor, id(id), applicationId(applicationId), body == null ? null : body.phone()));
    }

    @PostMapping("/{id}/applications/{applicationId}/decline")
    public ApiResponse<List<MyApplication>> decline(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String applicationId) {
        return ApiResponse.ok("Offer declined", offers.decline(actor, id(id), applicationId(applicationId)));
    }

    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/{id}/uploads")
    public ResponseEntity<ApiResponse<List<MediaStore.UploadTicket>>> uploadTickets(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody UploadsBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Uploads ready", applications.createUploadTickets(actor, id(id), body.kinds())));
    }

    /** Development storage only; production files go straight to Cloudinary with an upload ticket. */
    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/{id}/media")
    public ResponseEntity<ApiResponse<LocalUpload>> uploadLocal(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("File uploaded", applications.uploadLocalFile(actor, id(id), file)));
    }

    @GetMapping("/{id}/applications")
    public ApiResponse<List<StaffApplicationRow>> listApplications(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestParam(required = false) String search, @RequestParam(required = false) String position, @RequestParam(required = false) String status) {
        List<String> problems = new ArrayList<>();
        if (!ObjectIds.isValid(id)) {
            problems.add("Invalid id");
        }
        if (search != null && search.length() > 100) {
            problems.add("Invalid value");
        }
        if (position != null && !ObjectIds.isValid(position)) {
            problems.add("Invalid value");
        }
        if (!problems.isEmpty()) {
            throw AppException.badRequest(String.join(". ", problems));
        }
        return ApiResponse.ok("Applications fetched", applications.listApplications(actor, id, status, position, search));
    }

    @GetMapping("/{id}/applications/{applicationId}")
    public ApiResponse<StaffApplicationDetail> getApplication(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String applicationId) {
        return ApiResponse.ok("Application fetched", applications.getApplication(actor, id(id), applicationId(applicationId)));
    }
}
