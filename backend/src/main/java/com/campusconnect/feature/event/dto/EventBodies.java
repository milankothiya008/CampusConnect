package com.campusconnect.feature.event.dto;

import com.campusconnect.core.web.validation.TrimmedSize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the event routes (routes/EventRoutes.js, validators/RequestValidators.js). */
public final class EventBodies {

    private EventBodies() {
    }

    /** optionalCommentRules. */
    public record OptionalComment(@TrimmedSize(max = 2000, optional = true, message = "Invalid value") String comment) {

        public String trimmed() {
            return comment == null ? null : comment.strip();
        }
    }

    /** commentRules. */
    public record Comment(
            @NotNull(message = "Please describe the required changes (min 5 characters)")
            @TrimmedSize(min = 5, max = 2000, message = "Please describe the required changes (min 5 characters)") String comment) {

        public String trimmed() {
            return comment == null ? null : comment.strip();
        }
    }

    /** rejectRules. */
    public record Reject(
            @NotNull(message = "A rejection reason is required (min 5 characters)")
            @TrimmedSize(min = 5, max = 2000, message = "A rejection reason is required (min 5 characters)") String reason) {

        public String trimmed() {
            return reason == null ? null : reason.strip();
        }
    }

    public record Cancel(@Size(max = 1000, message = "Invalid value") String reason) {
    }

    public record Reminder(
            @NotNull(message = "Choose which reminder to send") @Pattern(regexp = "REGISTRATION_CLOSING|EVENT_STARTING", message = "Choose which reminder to send") String kind,
            @Size(max = 500, message = "Keep the note under 500 characters") String note) {
    }

    public record Expenses(
            @NotNull(message = "Invalid value") @Size(max = 60, message = "Invalid value") List<Object> items,
            @Size(max = 1000, message = "Invalid value") String note) {
    }
}
