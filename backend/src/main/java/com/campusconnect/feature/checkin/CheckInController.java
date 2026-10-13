package com.campusconnect.feature.checkin;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.event.CheckInMethod;
import com.campusconnect.feature.checkin.dto.CheckInBodies;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Check-in at the door: /api/events/:id/check-in/** (controllers/CheckInController.js). */
@RestController
@RequestMapping("/api/events/{id}/check-in")
public class CheckInController {

    private final CheckInService checkIn;

    public CheckInController(CheckInService checkIn) {
        this.checkIn = checkIn;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Check-in status fetched", checkIn.getCheckInStatus(actor, ObjectIds.require(id, "id")));
    }

    @PostMapping("/open")
    public ApiResponse<Map<String, Object>> open(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Check-in is open — officers can now scan tickets", checkIn.openCheckIn(actor, ObjectIds.require(id, "id")));
    }

    @PostMapping("/close")
    public ApiResponse<Map<String, Object>> close(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Check-in closed", checkIn.closeCheckIn(actor, ObjectIds.require(id, "id")));
    }

    @GetMapping("/participants")
    public ApiResponse<List<Map<String, Object>>> participants(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestParam(required = false) String search) {
        return ApiResponse.ok("Registered students fetched", checkIn.listForCheckIn(actor, ObjectIds.require(id, "id"), search));
    }

    /** A scanned QR (token) or a typed ticket code. */
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody(required = false) CheckInBodies.Scan body) {
        ObjectIds.require(id, "id");
        boolean hasToken = body != null && body.token() != null && !body.token().isEmpty();
        if (!hasToken && (body == null || body.code() == null)) {
            throw AppException.badRequest("Scan a QR code or enter a ticket code", ErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> result = hasToken
                ? checkIn.markAttendance(actor, id, body.token(), null, null, null, CheckInMethod.QR)
                : checkIn.markAttendance(actor, id, null, body.code(), null, null, CheckInMethod.MANUAL);
        return ApiResponse.ok(String.valueOf(result.get("message")), result);
    }

    @PostMapping("/attendance/{registrationId}")
    public ApiResponse<Map<String, Object>> mark(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String registrationId,
            @Valid @RequestBody(required = false) CheckInBodies.Mark body) {
        ObjectIds.require(id, "id");
        ObjectIds.require(registrationId, "registrationId");
        Map<String, Object> result = checkIn.markAttendance(actor, id, null, null, registrationId, body == null ? null : body.note(), CheckInMethod.MANUAL);
        return ApiResponse.ok(String.valueOf(result.get("message")), result);
    }

    @DeleteMapping("/attendance/{registrationId}")
    public ApiResponse<Map<String, Object>> unmark(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String registrationId) {
        ObjectIds.require(id, "id");
        ObjectIds.require(registrationId, "registrationId");
        Map<String, Object> result = checkIn.unmarkAttendance(actor, id, registrationId);
        return ApiResponse.ok(String.valueOf(result.get("message")), result);
    }
}
