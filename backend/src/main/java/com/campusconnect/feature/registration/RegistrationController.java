package com.campusconnect.feature.registration;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.event.RegistrationForm;
import com.campusconnect.feature.registration.dto.RegistrationBodies;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration routes: /api/events/:id/register, /registrations, /registration-count, /registration-form and
 * /register/answers (routes/EventRoutes.js), and /api/registrations/me (routes/RegistrationRoutes.js).
 */
@RestController
public class RegistrationController {

    private final RegistrationService registrations;
    private final RegistrationCancellation cancellation;
    private final RegistrationForms forms;

    public RegistrationController(RegistrationService registrations, RegistrationCancellation cancellation, RegistrationForms forms) {
        this.registrations = registrations;
        this.cancellation = cancellation;
        this.forms = forms;
    }

    @PostMapping("/api/events/{id}/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody(required = false) RegistrationBodies.Register body) {
        Map<String, Object> result = registrations.registerForEvent(actor, ObjectIds.require(id, "id"), body == null ? RegistrationBodies.Register.EMPTY : body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(registerMessage(result), result));
    }

    static String registerMessage(Map<String, Object> result) {
        boolean waitlisted = Boolean.TRUE.equals(result.get("waitlisted"));
        if (result.get("team") instanceof Map<?, ?> team) {
            int invited = team.get("invites") instanceof List<?> invites ? invites.size() : 0;
            String invites = invited > 0 ? " Invites sent to " + invited + " teammate" + (invited == 1 ? "" : "s") + "." : "";
            return waitlisted
                    ? "This event is full. Your team is #" + result.get("waitlistPosition") + " on the waitlist." + invites
                    : "Team \"" + team.get("name") + "\" is registered." + invites;
        }
        return waitlisted ? "This event is full. You're #" + result.get("waitlistPosition") + " on the waitlist." : "You're registered for this event";
    }

    @DeleteMapping("/api/events/{id}/register")
    public ApiResponse<Map<String, Object>> unregister(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        Map<String, Object> result = cancellation.cancelRegistration(actor, ObjectIds.require(id, "id"));
        String message = Boolean.TRUE.equals(result.get("leftTeam")) ? "You left the team"
                : Boolean.TRUE.equals(result.get("disbandedTeam")) ? "Your team's registration was cancelled"
                : Boolean.TRUE.equals(result.get("leftWaitlist")) ? "You left the waitlist"
                : "Your registration was cancelled";
        return ApiResponse.ok(message, result);
    }

    @GetMapping("/api/events/{id}/registrations")
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestParam(required = false) String search) {
        RegistrationService.Participants participants = registrations.listParticipants(actor, ObjectIds.require(id, "id"), search);
        return ApiResponse.ok("Participants fetched", participants.items(), participants.meta());
    }

    @DeleteMapping("/api/events/{id}/registrations/{registrationId}")
    public ApiResponse<Void> removeParticipant(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String registrationId,
            @RequestBody(required = false) Map<String, Object> body) {
        ObjectIds.require(id, "id");
        ObjectIds.require(registrationId, "registrationId");
        Object reason = body == null ? null : body.get("reason");
        registrations.removeParticipant(actor, id, registrationId, reason == null || "".equals(reason) || Boolean.FALSE.equals(reason) ? null : String.valueOf(reason));
        return ApiResponse.ok("Participant removed");
    }

    /** Public, like the original route: anyone may see how full a published event is. */
    @PublicApi
    @GetMapping("/api/events/{id}/registration-count")
    public ApiResponse<Map<String, Object>> count(@PathVariable String id) {
        return ApiResponse.ok("Registration count fetched", registrations.getPublicRegistrationCount(ObjectIds.require(id, "id")));
    }

    /** The registration form: the club edits it any time before the event starts. */
    @PutMapping("/api/events/{id}/registration-form")
    public ApiResponse<RegistrationForm> updateRegistrationForm(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody RegistrationBodies.RegistrationForm body) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("enabled", body.enabled());
        form.put("questions", body.questions());
        return ApiResponse.ok("Registration form saved", forms.updateRegistrationForm(actor, ObjectIds.require(id, "id"), form));
    }

    /** Registrants change their answers until the event starts. */
    @PutMapping("/api/events/{id}/register/answers")
    public ApiResponse<Map<String, Object>> updateMyAnswers(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody(required = false) RegistrationBodies.Answers body) {
        RegistrationBodies.Answers given = body == null ? RegistrationBodies.Answers.EMPTY : body;
        return ApiResponse.ok("Your answers were updated", forms.updateMyAnswers(actor, ObjectIds.require(id, "id"), given.answers(), given.teamAnswers()));
    }

    @GetMapping("/api/registrations/me")
    public ApiResponse<List<Map<String, Object>>> mine(@AuthenticationPrincipal AuthUser actor, @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) String includeWaitlist) {
        return ApiResponse.ok("Your registrations fetched", registrations.getMyRegistrations(actor, timeframe, includeWaitlist));
    }
}
