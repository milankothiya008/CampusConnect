package com.campusconnect.feature.recruitment;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.recruitment.drive.DriveService;
import com.campusconnect.feature.recruitment.dto.DriveViews.ClubDrives;
import com.campusconnect.feature.recruitment.dto.DriveViews.DriveView;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A club's recruitment drives (the two recruitment routes of routes/ClubRoutes.js); joining a club happens through them. */
@RestController
@RequestMapping("/api/clubs/{clubId}/recruitment")
public class ClubRecruitmentController {

    private final DriveService drives;

    public ClubRecruitmentController(DriveService drives) {
        this.drives = drives;
    }

    @OptionalAuth
    @GetMapping
    public ApiResponse<ClubDrives> listForClub(@AuthenticationPrincipal AuthUser actor, @PathVariable String clubId) {
        return ApiResponse.ok("Recruitment fetched", drives.listClubDrives(actor, ObjectIds.require(clubId, "clubId")));
    }

    /** The drive body (title, description, positions with page-wise forms, eligibility, window) is normalised by the service. */
    @PostMapping
    public ResponseEntity<ApiResponse<DriveView>> create(@AuthenticationPrincipal AuthUser actor, @PathVariable String clubId,
            @RequestBody(required = false) Map<String, Object> body) {
        DriveView drive = drives.createDrive(actor, ObjectIds.require(clubId, "clubId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Recruitment drive saved as a draft", drive));
    }
}
