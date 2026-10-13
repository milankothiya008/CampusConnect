package com.campusconnect.feature.registration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the registration routes (routes/EventRoutes.js). */
public final class RegistrationBodies {

    public static final String MONGO_ID = "^[0-9a-fA-F]{24}$";

    private RegistrationBodies() {
    }

    /**
     * POST /events/:id/register. Team events also take a team name and the students to invite; {@code replace} lists
     * the events at the same time the student agreed to give up (switch).
     */
    public record Register(
            @Size(max = 60, message = "Invalid value") String teamName,
            @Size(max = 19, message = "Too many invites") List<@NotNull(message = "Invalid student") @Pattern(regexp = MONGO_ID, message = "Invalid student") String> invitees,
            @Size(max = 25, message = "Invalid value") List<Object> answers,
            @Size(max = 25, message = "Invalid value") List<Object> teamAnswers,
            @Size(max = 10, message = "Invalid value") List<@NotNull(message = "Invalid value") @Pattern(regexp = MONGO_ID, message = "Invalid value") String> replace) {

        public static final Register EMPTY = new Register(null, null, null, null, null);
    }

    /** PUT /events/:id/registration-form. */
    public record RegistrationForm(
            @NotNull(message = "Invalid value") Boolean enabled,
            @Size(max = 25, message = "Invalid value") List<Object> questions) {
    }

    /** PUT /events/:id/register/answers. */
    public record Answers(
            @Size(max = 25, message = "Invalid value") List<Object> answers,
            @Size(max = 25, message = "Invalid value") List<Object> teamAnswers) {

        public static final Answers EMPTY = new Answers(null, null);
    }
}
