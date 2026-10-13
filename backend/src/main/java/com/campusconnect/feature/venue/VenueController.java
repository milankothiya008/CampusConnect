package com.campusconnect.feature.venue;

import com.campusconnect.core.cache.SharedCaches;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.security.RequireRole;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.domain.venue.VenueType;
import com.campusconnect.feature.club.RequestChecks;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/venues (routes/VenueRoutes.js). The list is public reference data (browsers may reuse it for 30 s); the
 * availability check is for signed-in users planning events; creating and editing venues is for the admin. A venue
 * write clears the shared events feed, which shows venue names (the Venue model's clearOnWrite hook).
 */
@RestController
@RequestMapping("/api/venues")
public class VenueController {

    static final String LIST_CACHE = "public, max-age=30, stale-while-revalidate=300";
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final List<String> TYPES = Arrays.stream(VenueType.values()).map(Enum::name).toList();

    private final VenueService venues;
    private final SharedCaches sharedCaches;

    public VenueController(VenueService venues, SharedCaches sharedCaches) {
        this.venues = venues;
        this.sharedCaches = sharedCaches;
    }

    @PublicApi
    @GetMapping
    public ResponseEntity<ApiResponse<List<Venue>>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String club,
            @RequestParam(required = false) String departments) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, LIST_CACHE).body(ApiResponse.ok("Venues fetched", venues.listVenues(status, type, club, departments)));
    }

    @GetMapping("/available")
    public ApiResponse<List<Map<String, Object>>> available(@RequestParam(required = false) String eventDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String excludeEventId,
            @RequestParam(required = false) String club,
            @RequestParam(required = false) String departments) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("eventDate", eventDate);
        parameters.put("endDate", endDate);
        parameters.put("startTime", startTime);
        parameters.put("endTime", endTime);
        parameters.put("excludeEventId", excludeEventId);
        parameters.put("club", club);
        parameters.put("departments", departments);
        RequestChecks checks = RequestChecks.ofQuery(parameters);
        checks.field("eventDate").matches(DATE).withMessage("eventDate must be YYYY-MM-DD");
        checks.field("endDate").optional().matches(DATE).withMessage("endDate must be YYYY-MM-DD");
        checks.field("startTime").matches(TIME).withMessage("startTime must be HH:mm");
        checks.field("endTime").matches(TIME).withMessage("endTime must be HH:mm");
        checks.field("excludeEventId").optional().isMongoId();
        checks.field("club").optional().isMongoId();
        checks.field("departments").optional().isString().isLength(0, 200);
        checks.validate();
        return ApiResponse.ok("Venue availability fetched", venues.getAvailableVenues(eventDate, endDate, startTime, endTime, excludeEventId, club, departments));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PostMapping
    public ResponseEntity<ApiResponse<Venue>> create(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = venueChecks(RequestChecks.of(body), false);
        Venue venue = venues.createVenue(actor, checks.body());
        sharedCaches.clearEventFeed();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Venue created", venue));
    }

    @RequireRole(GlobalRole.ADMIN)
    @PutMapping("/{id}")
    public ApiResponse<Venue> update(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        RequestChecks checks = venueChecks(RequestChecks.of(body).param("id", id), true);
        Venue venue = venues.updateVenue(actor, id, checks.body());
        sharedCaches.clearEventFeed();
        return ApiResponse.ok("Venue updated", venue);
    }

    /** venueRules(optional). */
    private static RequestChecks venueChecks(RequestChecks checks, boolean optional) {
        RequestChecks.Chain name = checks.field("name");
        RequestChecks.Chain location = checks.field("location");
        RequestChecks.Chain capacity = checks.field("capacity");
        if (optional) {
            name.optional();
            location.optional();
            capacity.optional();
        }
        name.isString().trim().isLength(2, 120).withMessage("Venue name is required");
        location.isString().trim().isLength(2, 200).withMessage("Location is required");
        capacity.isInt(1, 100_000).withMessage("Capacity must be a positive number");
        checks.field("status").optional().isIn(List.of("ACTIVE", "INACTIVE"));
        checks.field("type").optional().isIn(TYPES).withMessage("Choose the kind of venue");
        checks.field("departmentCodes").optional().isArray(30).withMessage("Choose the lab's departments");
        checks.validate();
        return checks;
    }
}
