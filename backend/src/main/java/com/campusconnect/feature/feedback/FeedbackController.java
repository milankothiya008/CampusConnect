package com.campusconnect.feature.feedback;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.event.dto.WholeNumber;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Feedback after the event: attendees rate it; organisers see the summary (routes/EventRoutes.js). */
@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    public record FeedbackBody(
            @NotNull(message = "Choose 1 to 5 stars") @WholeNumber(min = 1, max = 5, message = "Choose 1 to 5 stars") Object rating,
            @Size(max = 1000, message = "Keep the note under 1000 characters") String note) {
    }

    @GetMapping("/api/events/{id}/feedback")
    public ApiResponse<Map<String, Object>> getFeedback(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Feedback fetched", feedback.getFeedback(actor, ObjectIds.require(id, "id")));
    }

    @PutMapping("/api/events/{id}/feedback")
    public ApiResponse<Map<String, Object>> giveFeedback(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody FeedbackBody body) {
        return ApiResponse.ok("Thanks for your feedback", feedback.giveFeedback(actor, ObjectIds.require(id, "id"), body.rating(), body.note()));
    }
}
