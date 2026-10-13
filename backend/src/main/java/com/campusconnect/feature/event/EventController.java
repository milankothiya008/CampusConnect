package com.campusconnect.feature.event;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.event.dto.BodyChecks;
import com.campusconnect.feature.event.dto.EventBodies;
import com.campusconnect.feature.event.dto.EventFieldRules;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * /api/events (routes/EventRoutes.js, controllers/EventController.js): discovery, the event lifecycle, edits of
 * published events, reminders and spending. Registration, teams, tickets, check-in and feedback live in their own
 * controllers under the same path; hackathon, gallery, results and certificates routes belong to other features.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventViews views;
    private final EventEditing editing;
    private final EventLifecycle lifecycle;
    private final EventReminders reminders;
    private final EventPlanner planner;

    public EventController(EventViews views, EventEditing editing, EventLifecycle lifecycle, EventReminders reminders, EventPlanner planner) {
        this.views = views;
        this.editing = editing;
        this.lifecycle = lifecycle;
        this.reminders = reminders;
        this.planner = planner;
    }

    // ---------------------------------------------------------------- discovery and detail

    @OptionalAuth
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listEvents(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String page, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String timeframe, @RequestParam(required = false) String category,
            @RequestParam(required = false) String club, @RequestParam(required = false) String search,
            @RequestParam(required = false) String registrationOpen, @RequestParam(required = false) String withCounts) {
        EventViews.Listing listing = views.listEvents(actor, page, limit, timeframe, category, club, search, registrationOpen, withCounts);
        return ApiResponse.ok("Events fetched", listing.items(), listing.meta());
    }

    @GetMapping("/manage")
    public ApiResponse<List<Map<String, Object>>> listManaged(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String page, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String club, @RequestParam(required = false) String status, @RequestParam(required = false) String search) {
        EventViews.Listing listing = views.listManagedEvents(actor, page, limit, club, status, search);
        return ApiResponse.ok("Managed events fetched", listing.items(), listing.meta());
    }

    /** Campus-wide schedule for planning: which slots other clubs have taken (officers, faculty, admin). */
    @GetMapping("/schedule")
    public ApiResponse<Map<String, Object>> schedule(@AuthenticationPrincipal AuthUser actor, @RequestParam(required = false) String from,
            @RequestParam(required = false) String days) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (from != null) {
            query.put("from", from);
        }
        if (days != null) {
            query.put("days", days);
        }
        BodyChecks checks = new BodyChecks(query);
        checks.field("from").optional().isISO8601().withMessage("from must be a date (YYYY-MM-DD)");
        checks.field("days").optional().isInt(1, 31).withMessage("days must be 1 to 31");
        checks.throwIfInvalid();
        return ApiResponse.ok("Schedule fetched", planner.getSchedule(actor, from, days));
    }

    @OptionalAuth
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Event fetched", views.getEventDetail(actor, ObjectIds.require(id, "id")));
    }

    // ---------------------------------------------------------------- lifecycle

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createEvent(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = BodyChecks.copy(body);
        EventFieldRules.checkDraft(payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Event draft created", editing.createDraft(actor, new EventPayload(payload))));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        ObjectIds.require(id, "id");
        Map<String, Object> payload = BodyChecks.copy(body);
        EventFieldRules.checkUpdate(payload);
        Map<String, Object> event = editing.updateEvent(actor, id, new EventPayload(payload));
        Object revision = event.get("revision") instanceof Map<?, ?> pending ? pending.get("status") : null;
        String message = "PENDING_APPROVAL".equals(String.valueOf(revision))
                ? "Changes sent to your faculty mentor for approval. The event stays as it is until they approve."
                : "PENDING_APPROVAL".equals(String.valueOf(event.get("status"))) ? "Changes saved and sent to your faculty mentor for approval" : "Event updated";
        return ApiResponse.ok(message, event);
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Map<String, Object>> submitEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Event submitted to the faculty mentor", lifecycle.submitEvent(actor, ObjectIds.require(id, "id")));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approveEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody(required = false) EventBodies.OptionalComment body) {
        return ApiResponse.ok("Event approved", lifecycle.approveEvent(actor, ObjectIds.require(id, "id"), body == null ? null : body.trimmed()));
    }

    @PostMapping("/{id}/request-changes")
    public ApiResponse<Map<String, Object>> requestChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody EventBodies.Comment body) {
        return ApiResponse.ok("Changes requested", lifecycle.requestEventChanges(actor, ObjectIds.require(id, "id"), body.trimmed()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Map<String, Object>> rejectEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody EventBodies.Reject body) {
        return ApiResponse.ok("Event rejected", lifecycle.rejectEvent(actor, ObjectIds.require(id, "id"), body.trimmed()));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Map<String, Object>> publishEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Event published", lifecycle.publishEvent(actor, ObjectIds.require(id, "id")));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody(required = false) EventBodies.Cancel body) {
        return ApiResponse.ok("Event cancelled", lifecycle.cancelEvent(actor, ObjectIds.require(id, "id"), body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<Map<String, Object>> completeEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Event marked as completed", lifecycle.completeEvent(actor, ObjectIds.require(id, "id")));
    }

    /** Officers with SEND_REMINDERS send "registration closing" or "starting soon" by hand. */
    @PostMapping("/{id}/reminders")
    public ApiResponse<Map<String, Object>> sendReminder(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody EventBodies.Reminder body) {
        Map<String, Object> result = reminders.sendManualReminder(actor, ObjectIds.require(id, "id"), body.kind(), body.note());
        int recipients = ((Number) result.get("recipients")).intValue();
        return ApiResponse.ok(recipients > 0 ? "Reminder sent to " + recipients + " student" + (recipients == 1 ? "" : "s") : "No one to remind right now", result);
    }

    @PutMapping("/{id}/expenses")
    public ApiResponse<Map<String, Object>> recordExpenses(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody EventBodies.Expenses body) {
        return ApiResponse.ok("Spending saved", lifecycle.recordExpenses(actor, ObjectIds.require(id, "id"), body.items(), body.note()));
    }

    // ---------------------------------------------------------------- changes to a published event

    @PostMapping("/{id}/changes/approve")
    public ApiResponse<Map<String, Object>> approveChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody(required = false) EventBodies.OptionalComment body) {
        return ApiResponse.ok("Changes approved", editing.approveEventChanges(actor, ObjectIds.require(id, "id"), body == null ? null : body.trimmed()));
    }

    @PostMapping("/{id}/changes/request-changes")
    public ApiResponse<Map<String, Object>> requestChangesToEdit(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody EventBodies.Comment body) {
        return ApiResponse.ok("Changes requested", editing.requestEventChangesRevision(actor, ObjectIds.require(id, "id"), body.trimmed()));
    }

    @PostMapping("/{id}/changes/reject")
    public ApiResponse<Map<String, Object>> rejectChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody EventBodies.Reject body) {
        return ApiResponse.ok("Changes rejected", editing.rejectEventChanges(actor, ObjectIds.require(id, "id"), body.trimmed()));
    }

    @PostMapping("/{id}/changes/publish")
    public ApiResponse<Map<String, Object>> publishChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Changes published — registered students have been notified", editing.publishEventChanges(actor, ObjectIds.require(id, "id")));
    }

    @DeleteMapping("/{id}/changes")
    public ApiResponse<Map<String, Object>> discardChanges(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Pending changes discarded", editing.discardEventChanges(actor, ObjectIds.require(id, "id")));
    }
}
