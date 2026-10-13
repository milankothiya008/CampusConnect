package com.campusconnect.feature.election;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request bodies of /api/elections with the rules and messages of routes/ElectionRoutes.js. Candidates are
 * {@code { user, statement? }} objects (or bare user ids), read loosely like the original.
 */
public final class ElectionRequests {

    static final String OBJECT_ID = "^[0-9a-fA-F]{24}$";

    private ElectionRequests() {
    }

    /** Fields in rule order: the required ones, then the shared "details". */
    public record CreateBody(
            @NotNull(message = "Choose a club") @Pattern(regexp = OBJECT_ID, message = "Choose a club") String club,
            @NotNull(message = "Choose the role this election is for") @NotEmpty(message = "Choose the role this election is for") String role,
            @NotNull(message = "Choose 2 to 10 candidates") @Size(min = 2, max = 10, message = "Choose 2 to 10 candidates") List<Object> candidates,
            @NotNull(message = "Choose when voting closes") @IsoDate(message = "Choose when voting closes") String closesAt,
            @Size(max = 120, message = "Invalid value") String title,
            @Size(max = 1000, message = "Invalid value") String description,
            @IsoDate(allowEmpty = true, message = "Choose when voting opens") String opensAt) {
    }

    /** A runoff's optional details (the candidates are the tied leaders). */
    public record DetailsBody(
            @Size(max = 120, message = "Invalid value") String title,
            @Size(max = 1000, message = "Invalid value") String description,
            @Size(max = 10, message = "Choose up to 10 candidates") List<Object> candidates,
            @IsoDate(allowEmpty = true, message = "Choose when voting opens") String opensAt,
            @IsoDate(message = "Choose when voting closes") String closesAt) {
    }

    public record VoteBody(@NotNull(message = "Choose a candidate") @Pattern(regexp = OBJECT_ID, message = "Choose a candidate") String candidate) {
    }

    public record CancelBody(@Size(max = 300, message = "Invalid value") String reason) {
    }
}
