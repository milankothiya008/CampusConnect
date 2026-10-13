package com.campusconnect.feature.team.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the team routes (routes/EventRoutes.js). */
public final class TeamBodies {

    static final String MONGO_ID = "^[0-9a-fA-F]{24}$";

    private TeamBodies() {
    }

    /** POST /events/:id/team/invites. */
    public record Invite(
            @NotNull(message = "Choose students to invite") @Size(min = 1, max = 19, message = "Choose students to invite")
            List<@NotNull(message = "Invalid value") @Pattern(regexp = MONGO_ID, message = "Invalid value") String> users) {
    }

    /** POST /events/:id/teams/:teamId/accept: the member's answers, and the events at the same time to give up. */
    public record Accept(
            List<Object> answers,
            @Size(max = 10, message = "Invalid value") List<@NotNull(message = "Invalid value") @Pattern(regexp = MONGO_ID, message = "Invalid value") String> replace) {

        public static final Accept EMPTY = new Accept(null, null);
    }
}
